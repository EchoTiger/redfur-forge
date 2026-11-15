package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import java.util.Locale
import kotlin.math.min

/**
 * Builds deterministic CLI argument lists for libffmpeg.so.
 */
object FFmpegCommandBuilder {

    private val AUDIO_ONLY_EXTENSIONS = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav")

    fun buildCommand(
        inputPath: String,
        outputPath: String,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        threadCount: Int = 4,
        bitrateMultiplier: Double = 1.0
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

        val videoCodec = when {
            targetExt == "gif" -> VideoCodecChoice.COPY // handled via palette filter
            targetExt == "webm" && (convert == null || convert.videoCodec == VideoCodecChoice.H264 || convert.videoCodec == VideoCodecChoice.HEVC) ->
                VideoCodecChoice.VP9
            convert != null -> convert.videoCodec
            else -> VideoCodecChoice.H264
        }

        val hasAudio = probe.hasAudio &&
            trim?.muteAudio != true &&
            convert?.audioCodec != AudioCodecChoice.MUTE &&
            targetExt != "gif"

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
                isHevc = videoCodec == VideoCodecChoice.HEVC
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
        if (requestedMaxDim != null) {
            vfParts += "scale='min($requestedMaxDim,iw)':'min($requestedMaxDim,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2"
        } else {
            vfParts += "scale=trunc(iw/2)*2:trunc(ih/2)*2"
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
            when (effectiveVideoCodec) {
                VideoCodecChoice.H264 -> {
                    args += listOf("-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p")
                }
                VideoCodecChoice.HEVC -> {
                    args += listOf("-c:v", "libx265", "-preset", "fast", "-pix_fmt", "yuv420p", "-tag:v", "hvc1")
                }
                VideoCodecChoice.VP9 -> {
                    args += listOf("-c:v", "libvpx-vp9", "-row-mt", "1", "-pix_fmt", "yuv420p")
                }
                VideoCodecChoice.COPY -> Unit
            }

            if (budgetPlan != null) {
                args += listOf(
                    "-b:v", budgetPlan.videoBitrateBps.toString(),
                    "-maxrate", budgetPlan.maxrateBps.toString(),
                    "-bufsize", budgetPlan.bufsizeBps.toString()
                )
            } else {
                val crf = (compress?.videoCrf ?: 23).coerceIn(14, 42)
                if (effectiveVideoCodec == VideoCodecChoice.VP9) {
                    args += listOf("-crf", (crf + 6).coerceAtMost(45).toString(), "-b:v", "0")
                } else {
                    args += listOf("-crf", crf.toString())
                }
            }
        }

        // Audio encoding
        if (!hasAudio) {
            args += "-an"
        } else {
            val audioChoice = convert?.audioCodec ?: when (targetExt) {
                "webm" -> AudioCodecChoice.OPUS
                "mkv" -> AudioCodecChoice.AAC
                else -> AudioCodecChoice.AAC
            }
            if (trim?.fadeInOut == true) {
                val afParts = mutableListOf("afade=t=in:st=0:d=0.4")
                if (effectiveDurationMs > 1200L) {
                    val outStart = (effectiveDurationMs / 1000.0) - 0.4
                    afParts += String.format(Locale.US, "afade=t=out:st=%.3f:d=0.4", outStart.coerceAtLeast(0.5))
                }
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
                AudioCodecChoice.AAC -> args += listOf("-c:a", "aac", "-b:a", audioBps.toString())
                AudioCodecChoice.MP3 -> args += listOf("-c:a", "libmp3lame", "-b:a", audioBps.toString())
                AudioCodecChoice.OPUS -> args += listOf("-c:a", "libopus", "-b:a", min(audioBps, 128_000L).toString())
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
        if (trim?.fadeInOut == true) {
            val afParts = mutableListOf("afade=t=in:st=0:d=0.4")
            if (effectiveDurationMs > 1200L) {
                val outStart = (effectiveDurationMs / 1000.0) - 0.4
                afParts += String.format(Locale.US, "afade=t=out:st=%.3f:d=0.4", outStart.coerceAtLeast(0.5))
            }
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
            "opus", "ogg" -> args += listOf("-c:a", "libopus", "-b:a", targetBps.coerceAtMost(192_000L).toString())
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
