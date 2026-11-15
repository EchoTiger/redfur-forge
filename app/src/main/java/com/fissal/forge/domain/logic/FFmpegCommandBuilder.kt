package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import java.util.Locale
import kotlin.math.min

enum class EncodePassMode {
    SINGLE,
    PASS_1,
    PASS_2
}

/**
 * Builds deterministic CLI argument lists for libffmpeg.so.
 * Supports single-pass perceptual SVT-AV1 + Opus, true 2-pass HEVC/H.264/VP9,
 * Lanczos scaling, adaptive FPS stepping, gentle temporal denoising, and clean stream copies.
 */
object FFmpegCommandBuilder {

    private val AUDIO_ONLY_EXTENSIONS = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav")

    fun resolveEffectiveVideoCodec(probe: MediaProbeResult, spec: PipelineSpec): VideoCodecChoice {
        val targetExt = (spec.convert?.targetExtension ?: probe.extension).lowercase(Locale.US)
        val convert = spec.convert
        val compress = spec.compress
        return when {
            targetExt == "gif" -> VideoCodecChoice.COPY
            targetExt == "webm" -> when {
                convert?.videoCodec == VideoCodecChoice.AV1 -> VideoCodecChoice.AV1
                convert == null && compress?.mode == CompressMode.TARGET_BYTES &&
                    compress.tier == CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                convert == null || convert.videoCodec == VideoCodecChoice.H264 || convert.videoCodec == VideoCodecChoice.HEVC ->
                    VideoCodecChoice.VP9
                else -> convert.videoCodec
            }
            convert != null -> convert.videoCodec
            compress?.mode == CompressMode.TARGET_BYTES -> when (compress.tier) {
                CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                CompressionTier.TWO_PASS_HEVC -> VideoCodecChoice.HEVC
                CompressionTier.COMPATIBLE_H264 -> VideoCodecChoice.H264
            }
            else -> VideoCodecChoice.H264
        }
    }

    fun shouldUseTwoPass(probe: MediaProbeResult, spec: PipelineSpec): Boolean {
        val compress = spec.compress ?: return false
        if (compress.mode != CompressMode.TARGET_BYTES || !compress.twoPass || compress.targetBytes == null) {
            return false
        }
        val targetExt = (spec.convert?.targetExtension ?: probe.extension).lowercase(Locale.US)
        if (targetExt in AUDIO_ONLY_EXTENSIONS || targetExt == "gif" || probe.mediaKind == MediaKind.AUDIO) {
            return false
        }
        return when (resolveEffectiveVideoCodec(probe, spec)) {
            VideoCodecChoice.HEVC, VideoCodecChoice.H264, VideoCodecChoice.VP9 -> true
            VideoCodecChoice.AV1, VideoCodecChoice.COPY -> false
        }
    }

    fun buildCommand(
        inputPath: String,
        outputPath: String,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        threadCount: Int = 4,
        bitrateMultiplier: Double = 1.0,
        passMode: EncodePassMode = EncodePassMode.SINGLE,
        passLogPrefix: String? = null,
        complexityFactor: Double = 1.0
    ): List<String> {
        val trim = spec.trim
        val crop = spec.crop
        val convert = spec.convert
        val compress = spec.compress
        val targetExt = (convert?.targetExtension ?: probe.extension).lowercase(Locale.US)

        val effectiveStartMs = (trim?.startMs ?: 0L).coerceAtLeast(0L)
        val effectiveEndMs = trim?.endMs?.takeIf { it > effectiveStartMs }
        val effectiveDurationMs = when {
            effectiveEndMs != null -> effectiveEndMs - effectiveStartMs
            probe.durationMs > effectiveStartMs -> probe.durationMs - effectiveStartMs
            else -> probe.durationMs
        }.coerceAtLeast(0L)

        val args = mutableListOf(
            "-nostdin",
            "-y",
            "-hide_banner",
            "-progress",
            "pipe:1",
            "-nostats"
        )

        val isAudioOnly = targetExt in AUDIO_ONLY_EXTENSIONS || probe.mediaKind == MediaKind.AUDIO
        val canFastStreamCopyTrim = trim != null &&
            trim.fastKeyframeCopy &&
            crop == null &&
            compress == null &&
            !trim.fadeInOut &&
            !trim.muteAudio &&
            (convert == null || convert.videoCodec == VideoCodecChoice.COPY)

        if (canFastStreamCopyTrim) {
            if (effectiveStartMs > 0L) {
                args += listOf("-ss", formatSeconds(effectiveStartMs))
            }
            if (effectiveEndMs != null) {
                args += listOf("-to", formatSeconds(effectiveEndMs))
            }
            args += listOf("-i", inputPath, "-c", "copy", "-avoid_negative_ts", "make_zero")
            if (spec.delivery.stripMetadata) {
                args += listOf("-map_metadata", "-1")
            }
            args += outputPath
            return args
        }

        if (effectiveStartMs > 0L) {
            args += listOf("-ss", formatSeconds(effectiveStartMs))
        }
        args += listOf("-i", inputPath)
        if (effectiveEndMs != null && effectiveDurationMs > 0L) {
            args += listOf("-t", formatSeconds(effectiveDurationMs))
        }

        if (isAudioOnly) {
            buildAudioOnlyArgs(
                args = args,
                targetExt = targetExt,
                spec = spec,
                effectiveDurationMs = effectiveDurationMs,
                threadCount = threadCount,
                bitrateMultiplier = bitrateMultiplier
            )
            args += outputPath
            return args
        }

        // Visual / Video pipeline
        val effW = probe.effectiveWidth
        val effH = probe.effectiveHeight
        val pixelCrop = if (crop != null && effW > 1 && effH > 1) {
            AspectRatioSnapper.toEvenPixelCrop(crop.rect, effW, effH)
        } else {
            null
        }
        val postCropW = pixelCrop?.width ?: effW
        val postCropH = pixelCrop?.height ?: effH

        val videoCodec = resolveEffectiveVideoCodec(probe, spec)

        val hasAudio = probe.hasAudio &&
            trim?.muteAudio != true &&
            convert?.audioCodec != AudioCodecChoice.MUTE &&
            targetExt != "gif"

        val preferOpusAudio = targetExt == "webm" ||
            convert?.audioCodec == AudioCodecChoice.OPUS ||
            (compress?.mode == CompressMode.TARGET_BYTES &&
                compress.tier == CompressionTier.PERCEPTUAL_AV1 &&
                convert?.audioCodec != AudioCodecChoice.AAC &&
                convert?.audioCodec != AudioCodecChoice.MP3 &&
                targetExt in setOf("mp4", "mkv", "webm"))

        val budgetPlan = if (
            compress?.mode == CompressMode.TARGET_BYTES &&
            compress.targetBytes != null &&
            targetExt != "gif"
        ) {
            val scaledBytes = (compress.targetBytes * bitrateMultiplier).toLong().coerceAtLeast(32L * 1024L)
            BitrateBudgetCalculator.calculateVideoBudget(
                targetBytes = scaledBytes,
                durationMs = effectiveDurationMs.coerceAtLeast(1000L),
                sourceWidth = postCropW,
                sourceHeight = postCropH,
                hasAudio = hasAudio,
                isHevc = videoCodec == VideoCodecChoice.HEVC,
                videoCodec = videoCodec,
                preferOpusAudio = preferOpusAudio,
                complexityFactor = complexityFactor
            )
        } else {
            null
        }

        val requestedMaxDim = listOfNotNull(
            compress?.maxDimensionPx,
            budgetPlan?.targetMaxDimension
        ).minOrNull()

        if (targetExt == "gif") {
            val gifFilters = mutableListOf<String>()
            if (pixelCrop != null) {
                gifFilters += "crop=${pixelCrop.width}:${pixelCrop.height}:${pixelCrop.x}:${pixelCrop.y}"
            }
            val gifMaxDim = requestedMaxDim ?: 540
            gifFilters += "fps=15"
            gifFilters += "scale='min($gifMaxDim,iw)':'min($gifMaxDim,ih)':force_original_aspect_ratio=decrease:flags=lanczos"
            val chain = gifFilters.joinToString(",")
            args += listOf(
                "-filter_complex",
                "$chain,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=5",
                "-loop",
                "0"
            )
            if (spec.delivery.stripMetadata) {
                args += listOf("-map_metadata", "-1")
            }
            args += listOf("-threads", threadCount.coerceIn(1, 8).toString(), outputPath)
            return args
        }

        val vfParts = mutableListOf<String>()
        if (pixelCrop != null) {
            vfParts += "crop=${pixelCrop.width}:${pixelCrop.height}:${pixelCrop.x}:${pixelCrop.y}"
        }
        if (budgetPlan?.targetFps != null) {
            vfParts += "fps=${budgetPlan.targetFps}"
        }
        if (requestedMaxDim != null) {
            vfParts += "scale='min($requestedMaxDim,iw)':'min($requestedMaxDim,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos"
        } else {
            vfParts += "scale=trunc(iw/2)*2:trunc(ih/2)*2:flags=lanczos"
        }
        if (budgetPlan?.enableLightDenoise == true) {
            vfParts += "hqdn3d=1.2:1.2:2.5:2.5"
        }
        if (trim?.fadeInOut == true) {
            vfParts += "fade=t=in:st=0:d=0.4"
            if (effectiveDurationMs > 1200L) {
                val outStart = (effectiveDurationMs / 1000.0) - 0.4
                vfParts += String.format(Locale.US, "fade=t=out:st=%.3f:d=0.4", outStart.coerceAtLeast(0.5))
            }
        }

        val filterRequired = vfParts.isNotEmpty() && (videoCodec != VideoCodecChoice.COPY || pixelCrop != null || requestedMaxDim != null || compress != null)
        val effectiveVideoCodec = if (videoCodec == VideoCodecChoice.COPY && filterRequired) {
            VideoCodecChoice.H264
        } else {
            videoCodec
        }

        if (effectiveVideoCodec == VideoCodecChoice.COPY) {
            args += listOf("-c:v", "copy")
        } else {
            args += listOf("-vf", vfParts.joinToString(","))
            val crf = (compress?.videoCrf ?: 23).coerceIn(14, 42)
            when (effectiveVideoCodec) {
                VideoCodecChoice.AV1 -> {
                    val svtParams = buildString {
                        append("enable-qm=1:qm-min=0:enable-variance-boost=1:variance-boost-strength=2:enable-overlays=1:fast-decode=1")
                        if (budgetPlan != null) {
                            append(":overshoot-pct=10")
                        }
                    }
                    args += listOf(
                        "-c:v", "libsvtav1",
                        "-preset", "7",
                        "-pix_fmt", "yuv420p",
                        "-g", "240",
                        "-svtav1-params", svtParams
                    )
                    if (budgetPlan != null) {
                        args += listOf("-b:v", budgetPlan.videoBitrateBps.toString())
                    } else {
                        args += listOf("-crf", (crf + 6).coerceIn(18, 48).toString())
                    }
                }
                VideoCodecChoice.H264 -> {
                    val preset = when {
                        passMode == EncodePassMode.PASS_1 -> "fast"
                        passMode == EncodePassMode.PASS_2 -> "medium"
                        else -> "veryfast"
                    }
                    args += listOf("-c:v", "libx264", "-preset", preset, "-pix_fmt", "yuv420p", "-x264-params", "aq-mode=3")
                    if (passMode == EncodePassMode.PASS_2) {
                        args += listOf("-g", "240")
                    }
                    if (passLogPrefix != null && passMode != EncodePassMode.SINGLE) {
                        args += listOf("-pass", if (passMode == EncodePassMode.PASS_1) "1" else "2", "-passlogfile", passLogPrefix)
                    }
                    appendRateControlArgs(args, budgetPlan, crf, vp9Mode = false)
                }
                VideoCodecChoice.HEVC -> {
                    val preset = if (passMode == EncodePassMode.PASS_2) "medium" else "fast"
                    val x265Params = mutableListOf("aq-mode=3")
                    if (passLogPrefix != null && passMode == EncodePassMode.PASS_1) {
                        x265Params += listOf("pass=1", "no-slow-firstpass=1", "stats=${passLogPrefix}_x265.log")
                    } else if (passLogPrefix != null && passMode == EncodePassMode.PASS_2) {
                        x265Params += listOf("pass=2", "stats=${passLogPrefix}_x265.log")
                    }
                    args += listOf("-c:v", "libx265", "-preset", preset, "-pix_fmt", "yuv420p")
                    if (passMode != EncodePassMode.PASS_1) {
                        args += listOf("-tag:v", "hvc1")
                    }
                    args += listOf("-x265-params", x265Params.joinToString(":"))
                    appendRateControlArgs(args, budgetPlan, crf, vp9Mode = false)
                }
                VideoCodecChoice.VP9 -> {
                    val speed = if (passMode == EncodePassMode.PASS_1) "4" else "2"
                    args += listOf("-c:v", "libvpx-vp9", "-row-mt", "1", "-speed", speed, "-pix_fmt", "yuv420p")
                    if (passLogPrefix != null && passMode != EncodePassMode.SINGLE) {
                        args += listOf("-pass", if (passMode == EncodePassMode.PASS_1) "1" else "2", "-passlogfile", passLogPrefix)
                    }
                    appendRateControlArgs(args, budgetPlan, crf, vp9Mode = true)
                }
                VideoCodecChoice.COPY -> Unit
            }
        }

        if (passMode == EncodePassMode.PASS_1) {
            args += listOf(
                "-an",
                "-threads", threadCount.coerceIn(1, 8).toString(),
                "-f", "null",
                "/dev/null"
            )
            return args
        }

        // Audio encoding
        if (!hasAudio) {
            args += "-an"
        } else {
            val audioChoice = when {
                convert?.audioCodec != null -> convert.audioCodec
                preferOpusAudio -> AudioCodecChoice.OPUS
                targetExt == "webm" -> AudioCodecChoice.OPUS
                else -> AudioCodecChoice.AAC
            }
            val afParts = mutableListOf<String>()
            if (audioChoice == AudioCodecChoice.OPUS) {
                // Resample to 48kHz with highpass via SoX so Opus accepts any phone/web input cleanly
                afParts += listOf("aresample=48000:resampler=soxr", "highpass=f=35")
            }
            if (trim?.fadeInOut == true) {
                afParts += "afade=t=in:st=0:d=0.4"
                if (effectiveDurationMs > 1200L) {
                    val outStart = (effectiveDurationMs / 1000.0) - 0.4
                    afParts += String.format(Locale.US, "afade=t=out:st=%.3f:d=0.4", outStart.coerceAtLeast(0.5))
                }
            }
            if (afParts.isNotEmpty() && audioChoice != AudioCodecChoice.COPY && audioChoice != AudioCodecChoice.MUTE) {
                args += listOf("-af", afParts.joinToString(","))
            }

            val audioBps = budgetPlan?.audioBitrateBps ?: 128_000L
            when (audioChoice) {
                AudioCodecChoice.COPY -> {
                    if (trim?.fadeInOut == true) {
                        args += listOf("-c:a", "aac", "-b:a", audioBps.toString())
                    } else {
                        args += listOf("-c:a", "copy")
                    }
                }
                AudioCodecChoice.AAC -> {
                    if (budgetPlan != null) args += listOf("-ac", "2")
                    args += listOf("-c:a", "aac", "-b:a", audioBps.toString())
                }
                AudioCodecChoice.MP3 -> {
                    if (budgetPlan != null) args += listOf("-ac", "2")
                    args += listOf("-c:a", "libmp3lame", "-b:a", audioBps.toString())
                }
                AudioCodecChoice.OPUS -> {
                    if (budgetPlan != null) args += listOf("-ac", "2")
                    args += listOf(
                        "-c:a", "libopus",
                        "-b:a", min(audioBps, 128_000L).toString(),
                        "-vbr", "on",
                        "-compression_level", "10",
                        "-frame_duration", "60"
                    )
                }
                AudioCodecChoice.FLAC -> args += listOf("-c:a", "flac")
                AudioCodecChoice.WAV -> args += listOf("-c:a", "pcm_s16le")
                AudioCodecChoice.MUTE -> args += "-an"
            }
        }

        if (targetExt in setOf("mp4", "m4v", "mov")) {
            args += listOf("-movflags", "+faststart")
        }
        if (spec.delivery.stripMetadata) {
            args += listOf("-map_metadata", "-1")
        }
        args += listOf("-threads", threadCount.coerceIn(1, 8).toString(), outputPath)
        return args
    }

    private fun appendRateControlArgs(
        args: MutableList<String>,
        budgetPlan: VideoBudgetPlan?,
        crf: Int,
        vp9Mode: Boolean
    ) {
        if (budgetPlan != null) {
            args += listOf(
                "-b:v", budgetPlan.videoBitrateBps.toString(),
                "-maxrate", budgetPlan.maxrateBps.toString(),
                "-bufsize", budgetPlan.bufsizeBps.toString()
            )
        } else if (vp9Mode) {
            args += listOf("-crf", (crf + 6).coerceAtMost(45).toString(), "-b:v", "0")
        } else {
            args += listOf("-crf", crf.toString())
        }
    }

    private fun buildAudioOnlyArgs(
        args: MutableList<String>,
        targetExt: String,
        spec: PipelineSpec,
        effectiveDurationMs: Long,
        threadCount: Int,
        bitrateMultiplier: Double
    ) {
        args += "-vn"
        val compress = spec.compress
        val trim = spec.trim
        val afParts = mutableListOf<String>()
        if (targetExt == "opus" || targetExt == "ogg") {
            afParts += "aresample=48000:resampler=soxr"
        }
        if (trim?.fadeInOut == true) {
            afParts += "afade=t=in:st=0:d=0.4"
            if (effectiveDurationMs > 1200L) {
                val outStart = (effectiveDurationMs / 1000.0) - 0.4
                afParts += String.format(Locale.US, "afade=t=out:st=%.3f:d=0.4", outStart.coerceAtLeast(0.5))
            }
        }
        if (afParts.isNotEmpty()) {
            args += listOf("-af", afParts.joinToString(","))
        }

        val targetBps = if (compress?.mode == CompressMode.TARGET_BYTES && compress.targetBytes != null) {
            val scaledBytes = (compress.targetBytes * bitrateMultiplier).toLong().coerceAtLeast(16L * 1024L)
            BitrateBudgetCalculator.calculateAudioBitrateBps(scaledBytes, effectiveDurationMs.coerceAtLeast(1000L))
        } else {
            192_000L
        }

        when (targetExt) {
            "mp3" -> args += listOf("-c:a", "libmp3lame", "-b:a", targetBps.toString())
            "m4a", "aac" -> args += listOf("-c:a", "aac", "-b:a", targetBps.toString())
            "opus", "ogg" -> args += listOf(
                "-c:a", "libopus",
                "-b:a", targetBps.coerceAtMost(192_000L).toString(),
                "-vbr", "on",
                "-compression_level", "10"
            )
            "flac" -> args += listOf("-c:a", "flac")
            "wav" -> args += listOf("-c:a", "pcm_s16le")
            else -> args += listOf("-c:a", "aac", "-b:a", targetBps.toString())
        }

        if (spec.delivery.stripMetadata) {
            args += listOf("-map_metadata", "-1")
        }
        args += listOf("-threads", threadCount.coerceIn(1, 8).toString())
    }

    private fun formatSeconds(ms: Long): String {
        return String.format(Locale.US, "%.3f", ms / 1000.0)
    }
}
