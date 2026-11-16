package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.ChapterTrackItem
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.PlaylistTrackItem
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.domain.model.VideoCodecChoice
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class SampleCalibrationRatio(
    val referenceBytes: Long,
    val compressedBytes: Long,
    val areaScaleToFinal: Double = 1.0
)

data class EstimatedSizeResult(
    val estimatedBytes: Long,
    val minBytes: Long,
    val maxBytes: Long,
    val sourceBytes: Long? = null,
    val deltaPercent: Int? = null,
    val isCalibratedFromSample: Boolean = false
) {
    fun formattedEstimate(): String = "~${FilesizeEstimator.formatBytes(estimatedBytes)}"

    fun formattedWithDelta(): String {
        val delta = deltaPercent
        return when {
            delta == null || delta == 0 -> formattedEstimate()
            delta < 0 -> "${formattedEstimate()} ($delta%)"
            else -> "${formattedEstimate()} (+$delta%)"
        }
    }
}

/**
 * Pure JVM estimator for local media pipelines (video, GIF, audio, raster images, Camera RAW)
 * and remote yt-dlp links (single items, playlists, selective chapters, and full batches).
 */
object FilesizeEstimator {

    private val AUDIO_ONLY_EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav")
    private const val REF_1080P_PIXELS = 1920.0 * 1080.0

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    fun estimateLocalPipeline(
        probe: MediaProbeResult,
        spec: PipelineSpec,
        sampleCalibration: SampleCalibrationRatio? = null
    ): EstimatedSizeResult {
        val targetExt = resolveTargetExtension(probe, spec)
        val sourceBytes = probe.fileSizeBytes.takeIf { it > 0L }
        val silenceSkipEnabled = spec.silenceSkip?.enabled == true || spec.trim?.silenceSkip?.enabled == true

        val effectiveDurationMs = computeEffectiveDurationMs(probe, spec)
        val durationRatio = if (probe.durationMs > 0L) {
            (effectiveDurationMs.toDouble() / probe.durationMs.toDouble()).coerceIn(0.01, 1.0)
        } else {
            1.0
        }

        val isAudioOnly = probe.mediaKind == MediaKind.AUDIO || targetExt in AUDIO_ONLY_EXTENSIONS
        if (isAudioOnly) {
            return estimateAudioPipeline(
                probe = probe,
                spec = spec,
                targetExt = targetExt,
                effectiveDurationMs = effectiveDurationMs,
                durationRatio = durationRatio,
                silenceSkipEnabled = silenceSkipEnabled,
                sourceBytes = sourceBytes
            )
        }

        if (probe.mediaKind == MediaKind.IMAGE || probe.mediaKind == MediaKind.CAMERA_RAW) {
            return estimateImageOrRawPipeline(
                probe = probe,
                spec = spec,
                targetExt = targetExt,
                sourceBytes = sourceBytes,
                sampleCalibration = sampleCalibration
            )
        }

        // Video / GIF pipeline
        val srcW = probe.effectiveWidth
        val srcH = probe.effectiveHeight
        val pixelCrop = if (spec.crop != null && srcW > 1 && srcH > 1) {
            AspectRatioSnapper.toEvenPixelCrop(spec.crop.rect, srcW, srcH)
        } else {
            null
        }
        val postCropW = pixelCrop?.width ?: srcW
        val postCropH = pixelCrop?.height ?: srcH

        if (targetExt == "gif") {
            return estimateGifPipeline(
                postCropW = postCropW,
                postCropH = postCropH,
                effectiveDurationMs = effectiveDurationMs,
                requestedMaxDim = spec.compress?.maxDimensionPx,
                sourceBytes = sourceBytes
            )
        }

        val trim = spec.trim
        val compress = spec.compress
        val convert = spec.convert

        // Passthrough when no transform stage is enabled
        if (!spec.hasTransformStage && sourceBytes != null) {
            return buildResult(
                estimatedBytes = sourceBytes,
                minBytes = sourceBytes,
                maxBytes = sourceBytes,
                sourceBytes = sourceBytes
            )
        }

        // Zero-loss / Stream Copy
        val canFastCopyTrim = trim != null &&
            trim.fastKeyframeCopy &&
            spec.crop == null &&
            compress == null &&
            !trim.fadeInOut &&
            !trim.muteAudio &&
            !silenceSkipEnabled &&
            (convert == null || convert.videoCodec == VideoCodecChoice.COPY)

        val canExplicitVideoCopy = convert?.videoCodec == VideoCodecChoice.COPY &&
            spec.crop == null &&
            compress == null &&
            trim?.fadeInOut != true &&
            !silenceSkipEnabled

        if ((canFastCopyTrim || canExplicitVideoCopy) && sourceBytes != null) {
            val baseScaled = (sourceBytes.toDouble() * durationRatio).roundToLong().coerceAtLeast(1024L)
            val audioMutedAdjustment = if (trim?.muteAudio == true || convert?.audioCodec == AudioCodecChoice.MUTE) {
                val durSec = (effectiveDurationMs / 1000.0).coerceAtLeast(0.5)
                val approxAudioBytes = (128_000.0 * durSec / 8.0).roundToLong()
                (baseScaled - approxAudioBytes).coerceAtLeast((baseScaled * 0.70).roundToLong())
            } else {
                baseScaled
            }
            return buildResult(
                estimatedBytes = audioMutedAdjustment,
                minBytes = (audioMutedAdjustment * 0.92).roundToLong(),
                maxBytes = (audioMutedAdjustment * 1.08).roundToLong(),
                sourceBytes = sourceBytes
            )
        }

        // Video Target-Bytes Mode
        if (compress?.mode == CompressMode.TARGET_BYTES && compress.targetBytes != null) {
            return estimateVideoTargetBytes(
                probe = probe,
                spec = spec,
                targetExt = targetExt,
                targetBytes = compress.targetBytes,
                postCropW = postCropW,
                postCropH = postCropH,
                effectiveDurationMs = effectiveDurationMs,
                sourceBytes = sourceBytes,
                sampleCalibration = sampleCalibration
            )
        }

        // Video Quality Mode (CRF)
        return estimateVideoQualityCrf(
            probe = probe,
            spec = spec,
            targetExt = targetExt,
            postCropW = postCropW,
            postCropH = postCropH,
            effectiveDurationMs = effectiveDurationMs,
            sourceBytes = sourceBytes,
            sampleCalibration = sampleCalibration
        )
    }

    fun estimateLinkChip(
        chip: ResolvedLinkChipItem,
        defaultVideoCrf: Int = 23
    ): EstimatedSizeResult? {
        val settings = chip.settings

        // Chapter-split handling (respects customSettings per chapter cleanly)
        if (settings.splitByChapters && chip.chapters.isNotEmpty()) {
            val selectedChapters = chip.chapters.filter { it.selected }
            if (selectedChapters.isEmpty()) {
                if (settings.targetSizeMb != null && settings.targetSizeMb > 0.0) {
                    val perItem = (settings.targetSizeMb * 1024.0 * 1024.0).roundToLong().coerceAtLeast(1L)
                    return buildResult(
                        estimatedBytes = perItem,
                        minBytes = (perItem * 0.88).roundToLong(),
                        maxBytes = perItem,
                        sourceBytes = chip.approxBytes
                    )
                }
                return null
            }
            val chapterResults = selectedChapters.mapNotNull { estimateChapterTrack(chip, it) }
            if (chapterResults.isEmpty()) return null
            val est = chapterResults.sumOf { it.estimatedBytes }
            val min = chapterResults.sumOf { it.minBytes }
            val max = chapterResults.sumOf { it.maxBytes }
            val src = chip.approxBytes?.takeIf { it > 0L && selectedChapters.size == chip.chapters.size }
            return buildResult(est, min, max, sourceBytes = src)
        }

        val isPlaylist = chip.effectivePlaylistActive && chip.tracks.isNotEmpty()
        val itemCount = if (isPlaylist) chip.selectedTrackCount.coerceAtLeast(1) else 1

        // 1. Target size MB override
        val targetMb = settings.targetSizeMb
        if (targetMb != null && targetMb > 0.0) {
            val perItemBytes = (targetMb * 1024.0 * 1024.0).roundToLong().coerceAtLeast(1L)
            val totalBytes = perItemBytes * itemCount.toLong()
            return buildResult(
                estimatedBytes = totalBytes,
                minBytes = (totalBytes * 0.88).roundToLong(),
                maxBytes = totalBytes,
                sourceBytes = if (!isPlaylist) chip.approxBytes?.takeIf { it > 0L } else null
            )
        }

        // 2. Audio-only download mode
        if (settings.mode == DownloadMode.AUDIO_ONLY) {
            val totalSec = resolveSelectedLinkDurationSec(chip, isPlaylist) ?: run {
                val approx = chip.approxBytes?.takeIf { it > 0L } ?: return null
                val audioEst = (approx * 0.14).roundToLong().coerceAtLeast(16_384L)
                return buildResult(
                    estimatedBytes = audioEst,
                    minBytes = (audioEst * 0.70).roundToLong(),
                    maxBytes = (audioEst * 1.30).roundToLong(),
                    sourceBytes = approx
                )
            }
            val bytesPerSec = linkAudioBytesPerSec(settings.audioFormat)
            val estimated = (totalSec * bytesPerSec).roundToLong() + (4_096L * itemCount)
            return buildResult(
                estimatedBytes = estimated,
                minBytes = (estimated * 0.85).roundToLong(),
                maxBytes = (estimated * 1.15).roundToLong()
            )
        }

        // 3. Resolved approxBytes from yt-dlp
        val approx = chip.approxBytes
        if (approx != null && approx > 0L) {
            val resScale = resolutionCapScale(settings.maxHeight)
            val selectionRatio = if (isPlaylist) {
                resolvePlaylistSelectionRatio(chip)
            } else {
                1.0
            }
            val estimated = (approx.toDouble() * resScale * selectionRatio).roundToLong().coerceAtLeast(16_384L)
            return buildResult(
                estimatedBytes = estimated,
                minBytes = (estimated * 0.80).roundToLong(),
                maxBytes = (estimated * 1.25).roundToLong(),
                sourceBytes = if (resScale < 0.99 || selectionRatio < 0.99) approx else null
            )
        }

        // 4. Duration-based video bitrate fallback
        val totalDurationSec = resolveSelectedLinkDurationSec(chip, isPlaylist) ?: return null
        val videoBps = estimateVideoBitrateBpsForHeight(settings.maxHeight)
        val crfFactor = 2.0.pow((23.0 - defaultVideoCrf.coerceIn(14, 42)) / 24.0).coerceIn(0.75, 1.35)
        val estimated = ((videoBps.toDouble() * totalDurationSec / 8.0) * crfFactor).roundToLong().coerceAtLeast(16_384L)
        return buildResult(
            estimatedBytes = estimated,
            minBytes = (estimated * 0.72).roundToLong(),
            maxBytes = (estimated * 1.35).roundToLong()
        )
    }

    fun estimatePlaylistTrack(
        chip: ResolvedLinkChipItem,
        track: PlaylistTrackItem
    ): EstimatedSizeResult? {
        val settings = chip.settings
        val targetMb = settings.targetSizeMb
        if (targetMb != null && targetMb > 0.0) {
            val bytes = (targetMb * 1024.0 * 1024.0).roundToLong().coerceAtLeast(1L)
            return buildResult(
                estimatedBytes = bytes,
                minBytes = (bytes * 0.88).roundToLong(),
                maxBytes = bytes
            )
        }

        val trackDurSec = track.durationSec?.takeIf { it > 0L }?.toDouble()
            ?: chip.durationSec?.takeIf { it > 0L }?.let { total ->
                val count = chip.tracks.size.coerceAtLeast(1)
                total.toDouble() / count.toDouble()
            }

        if (settings.mode == DownloadMode.AUDIO_ONLY) {
            val dur = trackDurSec ?: return null
            val bytes = (dur * linkAudioBytesPerSec(settings.audioFormat)).roundToLong() + 4_096L
            return buildResult(
                estimatedBytes = bytes,
                minBytes = (bytes * 0.85).roundToLong(),
                maxBytes = (bytes * 1.15).roundToLong()
            )
        }

        val approx = chip.approxBytes
        if (approx != null && approx > 0L && chip.tracks.isNotEmpty()) {
            val resScale = resolutionCapScale(settings.maxHeight)
            val trackShare = resolveTrackShare(chip, track)
            val bytes = (approx.toDouble() * resScale * trackShare).roundToLong().coerceAtLeast(8_192L)
            return buildResult(
                estimatedBytes = bytes,
                minBytes = (bytes * 0.80).roundToLong(),
                maxBytes = (bytes * 1.25).roundToLong()
            )
        }

        val dur = trackDurSec ?: return null
        val bps = estimateVideoBitrateBpsForHeight(settings.maxHeight)
        val bytes = ((bps.toDouble() * dur) / 8.0).roundToLong().coerceAtLeast(16_384L)
        return buildResult(
            estimatedBytes = bytes,
            minBytes = (bytes * 0.72).roundToLong(),
            maxBytes = (bytes * 1.35).roundToLong()
        )
    }

    fun estimateChapterTrack(
        chip: ResolvedLinkChipItem,
        chapter: ChapterTrackItem
    ): EstimatedSizeResult? {
        val effSettings = chapter.customSettings ?: chip.settings
        val targetMb = effSettings.targetSizeMb
        if (targetMb != null && targetMb > 0.0) {
            val bytes = (targetMb * 1024.0 * 1024.0).roundToLong().coerceAtLeast(1L)
            return buildResult(
                estimatedBytes = bytes,
                minBytes = (bytes * 0.88).roundToLong(),
                maxBytes = bytes
            )
        }

        val durSec = chapter.durationMs / 1000.0
        if (durSec <= 0.0) return null

        if (effSettings.mode == DownloadMode.AUDIO_ONLY) {
            val bytes = (durSec * linkAudioBytesPerSec(effSettings.audioFormat)).roundToLong() + 4_096L
            return buildResult(
                estimatedBytes = bytes,
                minBytes = (bytes * 0.85).roundToLong(),
                maxBytes = (bytes * 1.15).roundToLong()
            )
        }

        val approx = chip.approxBytes
        if (approx != null && approx > 0L) {
            val totalChapterSec = chip.chapters.sumOf { it.durationMs } / 1000.0
            val totalSec = chip.durationSec?.takeIf { it > 0L }?.toDouble()
                ?: totalChapterSec.takeIf { it > 0.0 }
                ?: durSec
            val share = (durSec / totalSec).coerceIn(0.0, 1.0)
            val resScale = resolutionCapScale(effSettings.maxHeight)
            val bytes = (approx.toDouble() * share * resScale).roundToLong().coerceAtLeast(4_096L)
            return buildResult(
                estimatedBytes = bytes,
                minBytes = (bytes * 0.80).roundToLong(),
                maxBytes = (bytes * 1.25).roundToLong()
            )
        }

        val bps = estimateVideoBitrateBpsForHeight(effSettings.maxHeight)
        val bytes = ((bps.toDouble() * durSec) / 8.0).roundToLong().coerceAtLeast(8_192L)
        return buildResult(
            estimatedBytes = bytes,
            minBytes = (bytes * 0.72).roundToLong(),
            maxBytes = (bytes * 1.35).roundToLong()
        )
    }

    fun estimateBatchLinks(
        chips: List<ResolvedLinkChipItem>,
        defaultVideoCrf: Int = 23
    ): EstimatedSizeResult? {
        if (chips.isEmpty()) return null
        val results = chips.mapNotNull { estimateLinkChip(it, defaultVideoCrf) }
        if (results.isEmpty()) return null

        val totalEst = results.sumOf { it.estimatedBytes }
        val totalMin = results.sumOf { it.minBytes }
        val totalMax = results.sumOf { it.maxBytes }
        val allHaveSource = results.all { it.sourceBytes != null && it.sourceBytes > 0L }
        val totalSource = if (allHaveSource) results.sumOf { it.sourceBytes ?: 0L } else null
        return buildResult(
            estimatedBytes = totalEst,
            minBytes = totalMin,
            maxBytes = totalMax,
            sourceBytes = totalSource,
            isCalibratedFromSample = results.any { it.isCalibratedFromSample }
        )
    }

    // --- Local pipeline helpers ---

    private fun estimateAudioPipeline(
        probe: MediaProbeResult,
        spec: PipelineSpec,
        targetExt: String,
        effectiveDurationMs: Long,
        durationRatio: Double,
        silenceSkipEnabled: Boolean,
        sourceBytes: Long?
    ): EstimatedSizeResult {
        val trim = spec.trim
        val compress = spec.compress
        val convert = spec.convert
        val silenceFactor = if (silenceSkipEnabled) 0.90 else 1.0
        val minBand = if (silenceSkipEnabled) 0.68 else 0.85

        // Stream copy on audio-only
        val canAudioStreamCopy = probe.mediaKind == MediaKind.AUDIO && !silenceSkipEnabled && (
            !spec.hasTransformStage ||
                (trim != null && trim.fastKeyframeCopy && compress == null && !trim.fadeInOut && !trim.muteAudio &&
                    (convert == null || convert.audioCodec == AudioCodecChoice.COPY)) ||
                (convert?.audioCodec == AudioCodecChoice.COPY && compress == null &&
                    (trim == null || (!trim.fadeInOut && !trim.muteAudio)))
            )

        if (canAudioStreamCopy && sourceBytes != null) {
            val est = (sourceBytes.toDouble() * durationRatio).roundToLong().coerceAtLeast(1024L)
            return buildResult(
                estimatedBytes = est,
                minBytes = (est * 0.92).roundToLong(),
                maxBytes = (est * 1.08).roundToLong(),
                sourceBytes = sourceBytes
            )
        }

        // Target-Bytes Mode
        if (compress?.mode == CompressMode.TARGET_BYTES && compress.targetBytes != null) {
            val targetBytes = compress.targetBytes.coerceAtLeast(16L * 1024L)
            val canPassthrough = probe.mediaKind == MediaKind.AUDIO &&
                trim == null &&
                spec.crop == null &&
                !silenceSkipEnabled &&
                sourceBytes != null &&
                sourceBytes in 1L..targetBytes &&
                (convert == null || convert.targetExtension.isBlank() ||
                    convert.targetExtension.equals(probe.extension, ignoreCase = true))

            if (canPassthrough) {
                return buildResult(
                    estimatedBytes = sourceBytes!!,
                    minBytes = sourceBytes,
                    maxBytes = sourceBytes,
                    sourceBytes = sourceBytes
                )
            }

            val durMs = effectiveDurationMs.coerceAtLeast(1000L)
            val durSec = (durMs / 1000.0).coerceAtLeast(0.5)
            val targetBps = BitrateBudgetCalculator.calculateAudioBitrateBps(targetBytes, durMs)
            val effectiveBps = if (targetExt == "opus" || targetExt == "ogg") {
                targetBps.coerceAtMost(192_000L)
            } else {
                targetBps
            }
            val rawEst = ((effectiveBps.toDouble() * durSec) / 8.0 * silenceFactor).roundToLong() + 4_096L
            val est = rawEst.coerceAtMost(targetBytes)
            return buildResult(
                estimatedBytes = est,
                minBytes = (est * minBand).roundToLong(),
                maxBytes = min(targetBytes, (est * 1.04).roundToLong()),
                sourceBytes = sourceBytes
            )
        }

        // Quality / Convert Audio Mode
        val durSec = (effectiveDurationMs / 1000.0).coerceAtLeast(0.5)
        val effectiveAudioCodec = when (targetExt) {
            "opus", "ogg" -> AudioCodecChoice.OPUS
            "mp3" -> AudioCodecChoice.MP3
            "m4a", "aac" -> AudioCodecChoice.AAC
            "flac" -> AudioCodecChoice.FLAC
            "wav" -> AudioCodecChoice.WAV
            else -> convert?.audioCodec ?: AudioCodecChoice.AAC
        }

        val (bytesPerSec, headerBytes, bandLow, bandHigh) = when (effectiveAudioCodec) {
            AudioCodecChoice.OPUS -> AudioProfile(128_000.0 / 8.0, 4_096L, minBand, 1.15)
            AudioCodecChoice.AAC, AudioCodecChoice.MP3 -> AudioProfile(192_000.0 / 8.0, 4_096L, minBand, 1.12)
            AudioCodecChoice.FLAC -> AudioProfile(800_000.0 / 8.0, 8_192L, if (silenceSkipEnabled) 0.62 else 0.75, 1.18)
            AudioCodecChoice.WAV -> AudioProfile(192_000.0, 44L, if (silenceSkipEnabled) 0.70 else 0.92, 1.05)
            AudioCodecChoice.MUTE -> AudioProfile(0.0, 1_024L, 1.0, 1.0)
            AudioCodecChoice.COPY -> AudioProfile(192_000.0 / 8.0, 4_096L, 0.85, 1.15)
        }

        val est = ((bytesPerSec * durSec * silenceFactor).roundToLong() + headerBytes).coerceAtLeast(1024L)
        return buildResult(
            estimatedBytes = est,
            minBytes = (est * bandLow).roundToLong(),
            maxBytes = (est * bandHigh).roundToLong(),
            sourceBytes = sourceBytes
        )
    }

    private fun estimateImageOrRawPipeline(
        probe: MediaProbeResult,
        spec: PipelineSpec,
        targetExt: String,
        sourceBytes: Long?,
        sampleCalibration: SampleCalibrationRatio?
    ): EstimatedSizeResult {
        if (!spec.hasTransformStage && probe.mediaKind == MediaKind.IMAGE && sourceBytes != null) {
            return buildResult(
                estimatedBytes = sourceBytes,
                minBytes = sourceBytes,
                maxBytes = sourceBytes,
                sourceBytes = sourceBytes
            )
        }

        val fallbackW = if (probe.mediaKind == MediaKind.CAMERA_RAW) 4000 else 1920
        val fallbackH = if (probe.mediaKind == MediaKind.CAMERA_RAW) 3000 else 1080
        val srcW = probe.effectiveWidth.takeIf { it > 0 } ?: fallbackW
        val srcH = probe.effectiveHeight.takeIf { it > 0 } ?: fallbackH

        val pixelCrop = if (spec.crop != null && srcW > 1 && srcH > 1) {
            AspectRatioSnapper.toEvenPixelCrop(spec.crop.rect, srcW, srcH)
        } else {
            null
        }
        val postCropW = (pixelCrop?.width ?: srcW).toDouble()
        val postCropH = (pixelCrop?.height ?: srcH).toDouble()

        val halfFactor = if (probe.mediaKind == MediaKind.CAMERA_RAW && spec.compress?.rawHalfSizeFast == true) {
            0.5
        } else {
            1.0
        }
        val afterHalfW = postCropW * halfFactor
        val afterHalfH = postCropH * halfFactor
        val afterHalfMax = max(afterHalfW, afterHalfH)
        val reqMaxDim = spec.compress?.maxDimensionPx?.takeIf { it > 0 }
        val dimScale = if (reqMaxDim != null && afterHalfMax > reqMaxDim) {
            reqMaxDim.toDouble() / afterHalfMax
        } else {
            1.0
        }

        val outW = (afterHalfW * dimScale).coerceAtLeast(2.0)
        val outH = (afterHalfH * dimScale).coerceAtLeast(2.0)
        val outPixels = outW * outH

        val imgExt = targetExt.ifBlank { if (probe.mediaKind == MediaKind.CAMERA_RAW) "jpg" else "jpg" }
        val quality = (spec.compress?.imageQuality ?: 90).coerceIn(15, 100)
        val isPng = imgExt == "png"

        val theoreticalBytes = (outPixels * imageBytesPerPixel(imgExt, quality))
            .roundToLong()
            .coerceAtLeast(1_024L)

        // Target-Bytes mode
        if (spec.compress?.mode == CompressMode.TARGET_BYTES && spec.compress.targetBytes != null) {
            val targetBytes = spec.compress.targetBytes.coerceAtLeast(1_024L)
            if (sampleCalibration != null && sampleCalibration.referenceBytes > 0L) {
                val projected = (sampleCalibration.compressedBytes.toDouble() *
                    sampleCalibration.areaScaleToFinal.coerceAtLeast(0.05)).roundToLong().coerceAtLeast(512L)
                val est = if (isPng) projected else projected.coerceAtMost(targetBytes)
                return buildResult(
                    estimatedBytes = est,
                    minBytes = (est * 0.88).roundToLong(),
                    maxBytes = if (isPng) (est * 1.12).roundToLong() else min(targetBytes, (est * 1.05).roundToLong()),
                    sourceBytes = sourceBytes,
                    isCalibratedFromSample = true
                )
            }

            if (isPng) {
                val aspect = min(outW, outH) / max(outW, outH).coerceAtLeast(1.0)
                val minDownscaledPng = (240.0 * (240.0 * aspect) * 1.55).roundToLong().coerceAtLeast(2_048L)
                val est = when {
                    theoreticalBytes <= targetBytes -> theoreticalBytes
                    targetBytes >= minDownscaledPng -> (targetBytes * 0.90).roundToLong()
                    else -> minDownscaledPng
                }
                return buildResult(
                    estimatedBytes = est,
                    minBytes = (est * 0.82).roundToLong(),
                    maxBytes = (est * 1.18).roundToLong(),
                    sourceBytes = sourceBytes
                )
            } else {
                val ceiling = (targetBytes * 0.96).roundToLong().coerceAtLeast(512L).coerceAtMost(targetBytes)
                val naturalQ92 = (outPixels * imageBytesPerPixel(imgExt, 92)).roundToLong().coerceAtLeast(1_024L)
                val est = min(naturalQ92, ceiling)
                return buildResult(
                    estimatedBytes = est,
                    minBytes = (est * 0.85).roundToLong(),
                    maxBytes = min(targetBytes, (est * 1.03).roundToLong()),
                    sourceBytes = sourceBytes
                )
            }
        }

        // Quality / Convert Image Mode with optional Sample Calibration
        if (sampleCalibration != null && sampleCalibration.referenceBytes > 0L) {
            val projected = (sampleCalibration.compressedBytes.toDouble() *
                sampleCalibration.areaScaleToFinal.coerceAtLeast(0.05)).coerceAtLeast(512.0)
            val boundedTheory = theoreticalBytes.toDouble().coerceIn(projected * 0.65, projected * 1.35)
            val sampleWeight = if (sampleCalibration.areaScaleToFinal <= 2.0) 0.90 else 0.80
            val est = (projected * sampleWeight + boundedTheory * (1.0 - sampleWeight))
                .roundToLong()
                .coerceAtLeast(512L)
            return buildResult(
                estimatedBytes = est,
                minBytes = (est * 0.90).roundToLong(),
                maxBytes = (est * 1.10).roundToLong(),
                sourceBytes = sourceBytes,
                isCalibratedFromSample = true
            )
        }

        return buildResult(
            estimatedBytes = theoreticalBytes,
            minBytes = (theoreticalBytes * 0.78).roundToLong(),
            maxBytes = (theoreticalBytes * 1.24).roundToLong(),
            sourceBytes = sourceBytes
        )
    }

    private fun estimateGifPipeline(
        postCropW: Int,
        postCropH: Int,
        effectiveDurationMs: Long,
        requestedMaxDim: Int?,
        sourceBytes: Long?
    ): EstimatedSizeResult {
        val w = postCropW.takeIf { it > 0 } ?: 1280
        val h = postCropH.takeIf { it > 0 } ?: 720
        val maxSide = max(w, h)
        val gifMaxDim = min(540, requestedMaxDim?.takeIf { it > 0 } ?: 540)
        val scale = if (maxSide > gifMaxDim) gifMaxDim.toDouble() / maxSide.toDouble() else 1.0
        val gifPixels = (w * scale) * (h * scale)
        val durSec = (effectiveDurationMs / 1000.0).coerceAtLeast(0.5)
        // 15 fps * ~0.16 B/px/frame under palettegen diff + bayer dither
        val est = (gifPixels * 15.0 * 0.16 * durSec).roundToLong().coerceAtLeast(8_192L)
        return buildResult(
            estimatedBytes = est,
            minBytes = (est * 0.72).roundToLong(),
            maxBytes = (est * 1.32).roundToLong(),
            sourceBytes = sourceBytes
        )
    }

    private fun estimateVideoTargetBytes(
        probe: MediaProbeResult,
        spec: PipelineSpec,
        targetExt: String,
        targetBytes: Long,
        postCropW: Int,
        postCropH: Int,
        effectiveDurationMs: Long,
        sourceBytes: Long?,
        sampleCalibration: SampleCalibrationRatio?
    ): EstimatedSizeResult {
        val safeTarget = targetBytes.coerceAtLeast(32L * 1024L)
        val silenceSkipEnabled = spec.silenceSkip?.enabled == true || spec.trim?.silenceSkip?.enabled == true

        val canPassthrough = spec.trim == null &&
            spec.crop == null &&
            !silenceSkipEnabled &&
            sourceBytes != null &&
            sourceBytes in 1L..safeTarget &&
            (spec.convert == null || spec.convert.targetExtension.isBlank() ||
                spec.convert.targetExtension.equals(probe.extension, ignoreCase = true))

        if (canPassthrough) {
            return buildResult(
                estimatedBytes = sourceBytes!!,
                minBytes = sourceBytes,
                maxBytes = sourceBytes,
                sourceBytes = sourceBytes
            )
        }

        val videoCodec = FFmpegCommandBuilder.resolveEffectiveVideoCodec(probe, spec)
        val hasAudio = probe.hasAudio &&
            spec.trim?.muteAudio != true &&
            spec.convert?.audioCodec != AudioCodecChoice.MUTE
        val preferOpusAudio = targetExt == "webm" ||
            spec.convert?.audioCodec == AudioCodecChoice.OPUS ||
            (spec.compress?.tier == CompressionTier.PERCEPTUAL_AV1 &&
                spec.convert?.audioCodec != AudioCodecChoice.AAC &&
                spec.convert?.audioCodec != AudioCodecChoice.MP3 &&
                targetExt in setOf("mp4", "mkv", "webm"))

        val budgetPlan = BitrateBudgetCalculator.calculateVideoBudget(
            targetBytes = safeTarget,
            durationMs = effectiveDurationMs.coerceAtLeast(1000L),
            sourceWidth = postCropW,
            sourceHeight = postCropH,
            hasAudio = hasAudio,
            isHevc = videoCodec == VideoCodecChoice.HEVC,
            videoCodec = videoCodec,
            preferOpusAudio = preferOpusAudio
        )

        val baseEstimated = budgetPlan.estimatedOutputBytes.coerceIn(1L, safeTarget)

        if (sampleCalibration != null && sampleCalibration.referenceBytes > 0L) {
            val ratio = (sampleCalibration.compressedBytes.toDouble() / sampleCalibration.referenceBytes.toDouble())
                .coerceIn(0.75, 1.05)
            val floor = min(baseEstimated, (safeTarget * 0.75).roundToLong())
            val refined = (baseEstimated.toDouble() * (0.85 + 0.15 * ratio))
                .roundToLong()
                .coerceIn(floor, safeTarget)
            return buildResult(
                estimatedBytes = refined,
                minBytes = (refined * 0.88).roundToLong(),
                maxBytes = safeTarget,
                sourceBytes = sourceBytes,
                isCalibratedFromSample = true
            )
        }

        return buildResult(
            estimatedBytes = baseEstimated,
            minBytes = (baseEstimated * 0.88).roundToLong(),
            maxBytes = safeTarget,
            sourceBytes = sourceBytes
        )
    }

    private fun estimateVideoQualityCrf(
        probe: MediaProbeResult,
        spec: PipelineSpec,
        targetExt: String,
        postCropW: Int,
        postCropH: Int,
        effectiveDurationMs: Long,
        sourceBytes: Long?,
        sampleCalibration: SampleCalibrationRatio?
    ): EstimatedSizeResult {
        val rawCodec = FFmpegCommandBuilder.resolveEffectiveVideoCodec(probe, spec)
        val effectiveCodec = if (rawCodec == VideoCodecChoice.COPY) VideoCodecChoice.H264 else rawCodec
        val crf = (spec.compress?.videoCrf ?: 23).coerceIn(14, 42)

        val w = postCropW.takeIf { it > 0 } ?: 1920
        val h = postCropH.takeIf { it > 0 } ?: 1080
        val maxSide = max(w, h)
        val reqMaxDim = spec.compress?.maxDimensionPx?.takeIf { it > 0 }
        val scale = if (reqMaxDim != null && maxSide > reqMaxDim) {
            reqMaxDim.toDouble() / maxSide.toDouble()
        } else {
            1.0
        }
        val finalPixels = ((w * scale) * (h * scale)).coerceAtLeast(16_000.0)
        val pixelScale = (finalPixels / REF_1080P_PIXELS).pow(0.85)

        // Monotonic ordering: AV1 < HEVC <= VP9 < H264
        val codecEfficiency = when (effectiveCodec) {
            VideoCodecChoice.AV1 -> 0.48
            VideoCodecChoice.HEVC -> 0.65
            VideoCodecChoice.VP9 -> 0.72
            VideoCodecChoice.H264, VideoCodecChoice.COPY -> 1.00
        }

        // Standard +6 CRF halves bitrate around 1080p CRF 23 (~2.4 Mbps H.264 baseline)
        val videoBps = 2_400_000.0 * 2.0.pow((23.0 - crf) / 6.0) * codecEfficiency * pixelScale

        val hasAudio = probe.hasAudio &&
            spec.trim?.muteAudio != true &&
            spec.convert?.audioCodec != AudioCodecChoice.MUTE
        val audioChoice = when {
            !hasAudio -> AudioCodecChoice.MUTE
            spec.convert?.audioCodec != null -> spec.convert.audioCodec
            targetExt == "webm" -> AudioCodecChoice.OPUS
            else -> AudioCodecChoice.AAC
        }
        val audioBps = when (audioChoice) {
            AudioCodecChoice.OPUS -> 96_000.0
            AudioCodecChoice.AAC, AudioCodecChoice.MP3, AudioCodecChoice.COPY -> 128_000.0
            AudioCodecChoice.FLAC -> 750_000.0
            AudioCodecChoice.WAV -> 1_536_000.0
            AudioCodecChoice.MUTE -> 0.0
        }

        val durSec = (effectiveDurationMs / 1000.0).coerceAtLeast(0.5)
        val containerReserve = 16_384L + (durSec * 350.0).roundToLong()
        val audioBytes = ((audioBps * durSec) / 8.0).roundToLong()
        val theoreticalVideoBytes = ((videoBps * durSec) / 8.0).roundToLong()
        val theoreticalTotal = (theoreticalVideoBytes + audioBytes + containerReserve).coerceAtLeast(4_096L)

        if (sampleCalibration != null && sampleCalibration.referenceBytes > 0L) {
            // Video sample micro-clip is 1.0s video-only (-an); subtract nominal muxer header before scaling
            val sampleVideoBytesPerSec = (sampleCalibration.compressedBytes - 4_096L)
                .coerceAtLeast((sampleCalibration.compressedBytes * 0.75).roundToLong())
                .coerceAtLeast(1_024L)
            val areaScale = sampleCalibration.areaScaleToFinal.coerceAtLeast(0.05).pow(0.85)
            val sampleProjectedTotal = (sampleVideoBytesPerSec.toDouble() * areaScale * durSec).roundToLong() +
                audioBytes + containerReserve
            val blended = (sampleProjectedTotal * 0.72 + theoreticalTotal * 0.28).roundToLong().coerceAtLeast(4_096L)
            return buildResult(
                estimatedBytes = blended,
                minBytes = (blended * 0.85).roundToLong(),
                maxBytes = (blended * 1.15).roundToLong(),
                sourceBytes = sourceBytes,
                isCalibratedFromSample = true
            )
        }

        return buildResult(
            estimatedBytes = theoreticalTotal,
            minBytes = (theoreticalTotal * 0.75).roundToLong(),
            maxBytes = (theoreticalTotal * 1.28).roundToLong(),
            sourceBytes = sourceBytes
        )
    }

    private fun imageBytesPerPixel(ext: String, quality: Int): Double {
        if (ext == "png") return 1.55
        val qNorm = (quality.coerceIn(15, 100) - 15) / 85.0
        val jpegBpp = 0.042 + 0.14 * qNorm.pow(1.4) + 0.45 * qNorm.pow(4.2)
        return when (ext) {
            "webp" -> jpegBpp * 0.70
            else -> jpegBpp
        }
    }

    private fun resolveTargetExtension(probe: MediaProbeResult, spec: PipelineSpec): String {
        val explicit = spec.convert?.targetExtension?.lowercase(Locale.US)?.trim()
        if (!explicit.isNullOrBlank()) return explicit
        val existing = probe.extension.lowercase(Locale.US).trim()
        if (existing.isNotBlank()) return existing
        return when (probe.mediaKind) {
            MediaKind.CAMERA_RAW, MediaKind.IMAGE -> "jpg"
            MediaKind.AUDIO -> "m4a"
            else -> "mp4"
        }
    }

    private fun computeEffectiveDurationMs(probe: MediaProbeResult, spec: PipelineSpec): Long {
        val trim = spec.trim
        val start = (trim?.startMs ?: 0L).coerceAtLeast(0L)
        val end = trim?.endMs?.takeIf { it > start }
        val base = when {
            end != null -> end - start
            probe.durationMs > start -> probe.durationMs - start
            else -> probe.durationMs
        }
        return if (probe.isTimeBased) base.coerceAtLeast(500L) else base.coerceAtLeast(0L)
    }

    // --- Link chip / playlist / chapter helpers ---

    private fun linkAudioBytesPerSec(audioFormat: String): Double = when (audioFormat.lowercase(Locale.US)) {
        "opus", "ogg" -> 128_000.0 / 8.0
        "mp3", "m4a", "aac" -> 192_000.0 / 8.0
        "flac" -> 850_000.0 / 8.0
        "wav" -> 1_411_200.0 / 8.0
        else -> 192_000.0 / 8.0
    }

    private fun estimateVideoBitrateBpsForHeight(maxHeight: Int?): Long = when {
        maxHeight == null -> 4_500_000L
        maxHeight >= 2160 -> 8_500_000L
        maxHeight >= 1440 -> 5_500_000L
        maxHeight >= 1080 -> 3_000_000L
        maxHeight >= 720 -> 1_600_000L
        maxHeight >= 480 -> 850_000L
        else -> 500_000L
    }

    private fun resolutionCapScale(maxHeight: Int?): Double = when {
        maxHeight == null || maxHeight >= 1080 -> 1.0
        maxHeight >= 720 -> 0.58 + (maxHeight - 720) * (1.0 - 0.58) / (1080.0 - 720.0)
        maxHeight >= 480 -> 0.35 + (maxHeight - 480) * (0.58 - 0.35) / (720.0 - 480.0)
        else -> (0.35 * (maxHeight.coerceAtLeast(144).toDouble() / 480.0)).coerceAtLeast(0.14)
    }

    private fun resolveSelectedLinkDurationSec(
        chip: ResolvedLinkChipItem,
        isPlaylist: Boolean
    ): Double? {
        if (!isPlaylist) {
            return chip.durationSec?.takeIf { it > 0L }?.toDouble()
        }
        val selected = chip.tracks.filter { it.selected }
        if (selected.isEmpty()) {
            val total = chip.durationSec?.takeIf { it > 0L } ?: return null
            return total.toDouble() / chip.tracks.size.coerceAtLeast(1).toDouble()
        }
        val known = selected.mapNotNull { it.durationSec?.takeIf { d -> d > 0L } }
        if (known.size == selected.size) {
            return known.sum().toDouble()
        }
        if (known.isNotEmpty()) {
            val avg = known.average()
            return avg * selected.size.toDouble()
        }
        val total = chip.durationSec?.takeIf { it > 0L } ?: return null
        return total.toDouble() * (selected.size.toDouble() / chip.tracks.size.coerceAtLeast(1).toDouble())
    }

    private fun resolvePlaylistSelectionRatio(chip: ResolvedLinkChipItem): Double {
        val tracks = chip.tracks
        if (tracks.isEmpty()) return 1.0
        val selected = tracks.filter { it.selected }
        if (selected.isEmpty()) return 1.0 / tracks.size.toDouble()
        val allHaveDur = tracks.all { (it.durationSec ?: 0L) > 0L }
        if (allHaveDur) {
            val totalDur = tracks.sumOf { it.durationSec ?: 0L }.toDouble()
            val selDur = selected.sumOf { it.durationSec ?: 0L }.toDouble()
            if (totalDur > 0.0) return (selDur / totalDur).coerceIn(0.01, 1.0)
        }
        return (selected.size.toDouble() / tracks.size.toDouble()).coerceIn(0.01, 1.0)
    }

    private fun resolveTrackShare(
        chip: ResolvedLinkChipItem,
        track: PlaylistTrackItem
    ): Double {
        val tracks = chip.tracks
        if (tracks.isEmpty()) return 1.0
        val allHaveDur = tracks.all { (it.durationSec ?: 0L) > 0L }
        if (allHaveDur && (track.durationSec ?: 0L) > 0L) {
            val totalDur = tracks.sumOf { it.durationSec ?: 0L }.toDouble()
            if (totalDur > 0.0) return ((track.durationSec ?: 0L).toDouble() / totalDur).coerceIn(0.001, 1.0)
        }
        return 1.0 / tracks.size.toDouble()
    }

    private fun buildResult(
        estimatedBytes: Long,
        minBytes: Long,
        maxBytes: Long,
        sourceBytes: Long? = null,
        isCalibratedFromSample: Boolean = false
    ): EstimatedSizeResult {
        val safeEst = estimatedBytes.coerceAtLeast(0L)
        val safeMin = minBytes.coerceIn(0L, safeEst)
        val safeMax = maxBytes.coerceAtLeast(safeEst)
        val validSource = sourceBytes?.takeIf { it > 0L }
        val delta = validSource?.let { src ->
            (((safeEst - src).toDouble() / src.toDouble()) * 100.0).roundToInt()
        }
        return EstimatedSizeResult(
            estimatedBytes = safeEst,
            minBytes = safeMin,
            maxBytes = safeMax,
            sourceBytes = validSource,
            deltaPercent = delta,
            isCalibratedFromSample = isCalibratedFromSample
        )
    }

    private data class AudioProfile(
        val bytesPerSec: Double,
        val headerBytes: Long,
        val bandLow: Double,
        val bandHigh: Double
    )
}
