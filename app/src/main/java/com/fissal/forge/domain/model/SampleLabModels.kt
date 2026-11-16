package com.fissal.forge.domain.model

import com.fissal.forge.domain.logic.FFmpegCommandBuilder
import com.fissal.forge.domain.logic.SampleCalibrationRatio
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Serializable
enum class SampleVoteState {
    NEUTRAL,
    UP,
    DOWN
}

@Serializable
enum class SampleRenderStatus {
    IDLE,
    RENDERING,
    READY,
    FAILED
}

@Serializable
data class SampleEntryDraft(
    val targetExtension: String = "mp4",
    val videoCodec: VideoCodecChoice = VideoCodecChoice.AV1,
    val compressMode: CompressMode = CompressMode.QUALITY,
    val videoCrf: Int = 28,
    val imageQuality: Int = 82,
    val targetMbText: String = "10",
    val maxDimensionPx: Int? = null,
    val rawHalfSizeFast: Boolean = false,
    val compressionTier: CompressionTier = CompressionTier.PERCEPTUAL_AV1
) {
    /**
     * Returns a copy whose container, video codec, and compression tier are mutually compatible
     * for [mediaKind].
     */
    fun harmonizedForContainer(
        ext: String,
        codec: VideoCodecChoice,
        mediaKind: MediaKind
    ): SampleEntryDraft {
        val rawExt = ext.lowercase(Locale.US).trim()
        if (mediaKind == MediaKind.IMAGE || mediaKind == MediaKind.CAMERA_RAW) {
            val imgExt = when (rawExt) {
                "jpg", "jpeg" -> "jpg"
                "webp" -> "webp"
                "png" -> "png"
                else -> "jpg"
            }
            return copy(targetExtension = imgExt)
        }

        val videoExt = when (rawExt) {
            "mp4", "m4v", "mov", "mkv", "webm", "gif" -> rawExt
            else -> "mp4"
        }

        if (videoExt == "gif") {
            return copy(
                targetExtension = "gif",
                videoCodec = VideoCodecChoice.COPY,
                compressMode = CompressMode.QUALITY
            )
        }

        val concreteCodec = if (codec == VideoCodecChoice.COPY) {
            when (compressionTier) {
                CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                CompressionTier.TWO_PASS_HEVC -> VideoCodecChoice.HEVC
                CompressionTier.COMPATIBLE_H264 -> VideoCodecChoice.H264
            }
        } else {
            codec
        }

        val nextCodec = when (videoExt) {
            "webm" -> when (concreteCodec) {
                VideoCodecChoice.AV1, VideoCodecChoice.VP9 -> concreteCodec
                else -> VideoCodecChoice.VP9
            }
            "mp4", "m4v", "mov" -> when (concreteCodec) {
                VideoCodecChoice.VP9 -> VideoCodecChoice.H264
                else -> concreteCodec
            }
            else -> concreteCodec // mkv supports AV1, HEVC, VP9, H264
        }

        val nextTier = when (nextCodec) {
            VideoCodecChoice.AV1 -> CompressionTier.PERCEPTUAL_AV1
            VideoCodecChoice.HEVC -> CompressionTier.TWO_PASS_HEVC
            VideoCodecChoice.H264 -> CompressionTier.COMPATIBLE_H264
            VideoCodecChoice.VP9, VideoCodecChoice.COPY -> compressionTier
        }

        return copy(
            targetExtension = videoExt,
            videoCodec = nextCodec,
            compressionTier = nextTier
        )
    }

    /**
     * Converts this sample draft into an executable [PipelineSpec], preserving [inheritedTrim],
     * [inheritedCrop], and [stripMetadata].
     */
    fun toPipelineSpec(
        probe: MediaProbeResult,
        inheritedTrim: TrimSpec? = null,
        inheritedCrop: CropSpec? = null,
        stripMetadata: Boolean = false
    ): PipelineSpec {
        val normalized = harmonizedForContainer(targetExtension, videoCodec, probe.mediaKind)
        val parsedTargetBytes = parseTargetBytes(normalized.targetMbText)
        val effectiveMode = if (
            normalized.compressMode == CompressMode.TARGET_BYTES &&
            parsedTargetBytes != null &&
            normalized.targetExtension != "gif"
        ) {
            CompressMode.TARGET_BYTES
        } else {
            CompressMode.QUALITY
        }

        val cleanMaxDim = normalized.maxDimensionPx?.takeIf { it > 0 }
        val compressSpec = CompressSpec(
            mode = effectiveMode,
            videoCrf = normalized.videoCrf.coerceIn(14, 42),
            imageQuality = normalized.imageQuality.coerceIn(15, 100),
            targetBytes = if (effectiveMode == CompressMode.TARGET_BYTES) parsedTargetBytes else null,
            maxDimensionPx = cleanMaxDim,
            rawHalfSizeFast = normalized.rawHalfSizeFast,
            tier = normalized.compressionTier,
            twoPass = true
        )

        val convertSpec = if (probe.mediaKind == MediaKind.IMAGE || probe.mediaKind == MediaKind.CAMERA_RAW) {
            ConvertSpec(targetExtension = normalized.targetExtension)
        } else {
            val resolvedVideoCodec = if (
                effectiveMode == CompressMode.TARGET_BYTES &&
                normalized.videoCodec == VideoCodecChoice.COPY
            ) {
                when (normalized.compressionTier) {
                    CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                    CompressionTier.TWO_PASS_HEVC -> VideoCodecChoice.HEVC
                    CompressionTier.COMPATIBLE_H264 -> VideoCodecChoice.H264
                }
            } else {
                normalized.videoCodec
            }

            val isMuted = inheritedTrim?.muteAudio == true || normalized.targetExtension == "gif"
            val audioCodec = when {
                isMuted -> AudioCodecChoice.MUTE
                normalized.targetExtension == "webm" -> AudioCodecChoice.OPUS
                normalized.targetExtension in OPUS_FRIENDLY_CONTAINERS &&
                    (resolvedVideoCodec == VideoCodecChoice.AV1 ||
                        (effectiveMode == CompressMode.TARGET_BYTES &&
                            normalized.compressionTier == CompressionTier.PERCEPTUAL_AV1)) -> AudioCodecChoice.OPUS
                else -> AudioCodecChoice.AAC
            }

            ConvertSpec(
                targetExtension = normalized.targetExtension,
                videoCodec = resolvedVideoCodec,
                audioCodec = audioCodec
            )
        }

        return PipelineSpec(
            convert = convertSpec,
            compress = compressSpec,
            trim = inheritedTrim,
            crop = inheritedCrop,
            delivery = DeliverySpec(stripMetadata = stripMetadata),
            silenceSkip = inheritedTrim?.silenceSkip
        )
    }

    /**
     * Generates a concise human-readable label for sample cards and selector pills.
     */
    fun autoLabel(mediaKind: MediaKind): String {
        val normalized = harmonizedForContainer(targetExtension, videoCodec, mediaKind)
        val ext = normalized.targetExtension

        if (mediaKind == MediaKind.IMAGE || mediaKind == MediaKind.CAMERA_RAW) {
            val parts = mutableListOf(
                ext.uppercase(Locale.US),
                formatDimLabel(normalized.maxDimensionPx)
            )
            if (mediaKind == MediaKind.CAMERA_RAW && normalized.rawHalfSizeFast) {
                parts += "½RAW"
            }
            if (normalized.compressMode == CompressMode.TARGET_BYTES) {
                formatTargetMbBadge(normalized.targetMbText)?.let { parts += it }
            } else if (ext != "png") {
                parts += "Q${normalized.imageQuality.coerceIn(15, 100)}"
            }
            return parts.joinToString(" • ")
        }

        if (ext == "gif") {
            val gifDim = min(540, normalized.maxDimensionPx?.takeIf { it > 0 } ?: 540)
            return "GIF • ${gifDim}px"
        }

        val codecLabel = when (normalized.videoCodec) {
            VideoCodecChoice.AV1 -> "AV1"
            VideoCodecChoice.HEVC -> "HEVC"
            VideoCodecChoice.VP9 -> "VP9"
            VideoCodecChoice.H264 -> "H.264"
            VideoCodecChoice.COPY -> "Copy"
        }
        val dimLabel = formatDimLabel(normalized.maxDimensionPx)
        val rateLabel = if (normalized.compressMode == CompressMode.TARGET_BYTES) {
            formatTargetMbBadge(normalized.targetMbText) ?: "CRF ${normalized.videoCrf.coerceIn(14, 42)}"
        } else if (normalized.videoCodec == VideoCodecChoice.COPY) {
            "Copy"
        } else {
            "CRF ${normalized.videoCrf.coerceIn(14, 42)}"
        }
        return "$codecLabel • $dimLabel • $rateLabel"
    }

    internal fun dedupeKey(mediaKind: MediaKind): String {
        val n = harmonizedForContainer(targetExtension, videoCodec, mediaKind)
        val targetTag = if (n.compressMode == CompressMode.TARGET_BYTES) {
            "TB:${parseTargetBytes(n.targetMbText) ?: 0L}:${n.compressionTier.name}"
        } else if (mediaKind == MediaKind.IMAGE || mediaKind == MediaKind.CAMERA_RAW) {
            if (n.targetExtension == "png") "PNG" else "Q:${n.imageQuality.coerceIn(15, 100)}"
        } else {
            "CRF:${n.videoCrf.coerceIn(14, 42)}"
        }
        return "${n.targetExtension}|${n.videoCodec.name}|${n.maxDimensionPx ?: 0}|${n.rawHalfSizeFast}|$targetTag"
    }

    companion object {
        private val OPUS_FRIENDLY_CONTAINERS = setOf("mp4", "mkv", "webm")

        fun fromPipelineSpec(probe: MediaProbeResult, spec: PipelineSpec): SampleEntryDraft {
            val fallbackExt = when (probe.mediaKind) {
                MediaKind.CAMERA_RAW -> "jpg"
                MediaKind.IMAGE -> probe.extension.lowercase(Locale.US).ifBlank { "jpg" }
                else -> probe.extension.lowercase(Locale.US).ifBlank { "mp4" }
            }
            val rawExt = spec.convert?.targetExtension?.lowercase(Locale.US)?.trim()?.ifBlank { null } ?: fallbackExt
            val rawCodec = if (probe.mediaKind == MediaKind.IMAGE || probe.mediaKind == MediaKind.CAMERA_RAW) {
                VideoCodecChoice.H264
            } else {
                FFmpegCommandBuilder.resolveEffectiveVideoCodec(probe, spec)
            }
            val compress = spec.compress
            val targetMbText = compress?.targetBytes?.takeIf { it > 0L }?.let { formatBytesToMbInput(it) } ?: "10"

            return SampleEntryDraft(
                targetExtension = rawExt,
                videoCodec = rawCodec,
                compressMode = compress?.mode ?: CompressMode.QUALITY,
                videoCrf = (compress?.videoCrf ?: 23).coerceIn(14, 42),
                imageQuality = (compress?.imageQuality ?: 88).coerceIn(15, 100),
                targetMbText = targetMbText,
                maxDimensionPx = compress?.maxDimensionPx?.takeIf { it > 0 },
                rawHalfSizeFast = compress?.rawHalfSizeFast ?: false,
                compressionTier = compress?.tier ?: CompressionTier.PERCEPTUAL_AV1
            ).harmonizedForContainer(rawExt, rawCodec, probe.mediaKind)
        }

        fun defaultStarterDrafts(
            probe: MediaProbeResult,
            currentStudioSpec: PipelineSpec? = null
        ): List<SampleEntryDraft> {
            val srcMaxDim = max(probe.effectiveWidth, probe.effectiveHeight)
            val starters = if (probe.mediaKind == MediaKind.IMAGE || probe.mediaKind == MediaKind.CAMERA_RAW) {
                val cap1080 = if (srcMaxDim == 0 || srcMaxDim > 1920) 1920 else null
                val cap720 = if (srcMaxDim == 0 || srcMaxDim > 1280) 1280 else null
                listOf(
                    SampleEntryDraft(
                        targetExtension = "webp",
                        compressMode = CompressMode.QUALITY,
                        imageQuality = 82,
                        maxDimensionPx = null,
                        rawHalfSizeFast = false
                    ),
                    SampleEntryDraft(
                        targetExtension = "jpg",
                        compressMode = CompressMode.QUALITY,
                        imageQuality = 85,
                        maxDimensionPx = cap1080,
                        rawHalfSizeFast = false
                    ),
                    SampleEntryDraft(
                        targetExtension = "webp",
                        compressMode = CompressMode.QUALITY,
                        imageQuality = 70,
                        maxDimensionPx = cap720,
                        rawHalfSizeFast = probe.mediaKind == MediaKind.CAMERA_RAW
                    )
                )
            } else {
                val cap720 = if (srcMaxDim == 0 || srcMaxDim > 1280) 1280 else null
                val cap480 = if (srcMaxDim == 0 || srcMaxDim > 854) 854 else null
                listOf(
                    SampleEntryDraft(
                        targetExtension = "mp4",
                        videoCodec = VideoCodecChoice.AV1,
                        compressMode = CompressMode.QUALITY,
                        videoCrf = 28,
                        maxDimensionPx = null,
                        compressionTier = CompressionTier.PERCEPTUAL_AV1
                    ),
                    SampleEntryDraft(
                        targetExtension = "mp4",
                        videoCodec = VideoCodecChoice.HEVC,
                        compressMode = CompressMode.QUALITY,
                        videoCrf = 24,
                        maxDimensionPx = cap720,
                        compressionTier = CompressionTier.TWO_PASS_HEVC
                    ),
                    SampleEntryDraft(
                        targetExtension = "mp4",
                        videoCodec = VideoCodecChoice.H264,
                        compressMode = CompressMode.QUALITY,
                        videoCrf = 24,
                        maxDimensionPx = cap480,
                        compressionTier = CompressionTier.COMPATIBLE_H264
                    )
                )
            }.map { it.harmonizedForContainer(it.targetExtension, it.videoCodec, probe.mediaKind) }

            val combined = if (currentStudioSpec != null && currentStudioSpec.hasTransformStage) {
                val currentDraft = fromPipelineSpec(probe, currentStudioSpec)
                listOf(currentDraft) + starters
            } else {
                starters
            }
            return combined.distinctBy { it.dedupeKey(probe.mediaKind) }
        }

        fun bestKneeSeedDrafts(probe: MediaProbeResult): List<SampleEntryDraft> {
            val srcMaxDim = max(probe.effectiveWidth, probe.effectiveHeight)
            val seeds = if (probe.mediaKind == MediaKind.IMAGE || probe.mediaKind == MediaKind.CAMERA_RAW) {
                val cap1080 = if (srcMaxDim == 0 || srcMaxDim > 1920) 1920 else null
                val cap720 = if (srcMaxDim == 0 || srcMaxDim > 1280) 1280 else null
                listOf(
                    SampleEntryDraft(
                        targetExtension = "webp",
                        compressMode = CompressMode.QUALITY,
                        imageQuality = 88,
                        maxDimensionPx = null
                    ),
                    SampleEntryDraft(
                        targetExtension = "webp",
                        compressMode = CompressMode.QUALITY,
                        imageQuality = 80,
                        maxDimensionPx = cap1080
                    ),
                    SampleEntryDraft(
                        targetExtension = "jpg",
                        compressMode = CompressMode.QUALITY,
                        imageQuality = 82,
                        maxDimensionPx = cap1080
                    ),
                    SampleEntryDraft(
                        targetExtension = "webp",
                        compressMode = CompressMode.QUALITY,
                        imageQuality = 72,
                        maxDimensionPx = cap720,
                        rawHalfSizeFast = probe.mediaKind == MediaKind.CAMERA_RAW
                    ),
                    SampleEntryDraft(
                        targetExtension = "webp",
                        compressMode = CompressMode.QUALITY,
                        imageQuality = 62,
                        maxDimensionPx = cap720
                    )
                )
            } else {
                val cap720 = if (srcMaxDim == 0 || srcMaxDim > 1280) 1280 else null
                val cap480 = if (srcMaxDim == 0 || srcMaxDim > 854) 854 else null
                listOf(
                    SampleEntryDraft(
                        targetExtension = "mp4",
                        videoCodec = VideoCodecChoice.AV1,
                        compressMode = CompressMode.QUALITY,
                        videoCrf = 24,
                        maxDimensionPx = null,
                        compressionTier = CompressionTier.PERCEPTUAL_AV1
                    ),
                    SampleEntryDraft(
                        targetExtension = "mp4",
                        videoCodec = VideoCodecChoice.AV1,
                        compressMode = CompressMode.QUALITY,
                        videoCrf = 28,
                        maxDimensionPx = null,
                        compressionTier = CompressionTier.PERCEPTUAL_AV1
                    ),
                    SampleEntryDraft(
                        targetExtension = "mp4",
                        videoCodec = VideoCodecChoice.AV1,
                        compressMode = CompressMode.QUALITY,
                        videoCrf = 30,
                        maxDimensionPx = cap720,
                        compressionTier = CompressionTier.PERCEPTUAL_AV1
                    ),
                    SampleEntryDraft(
                        targetExtension = "mp4",
                        videoCodec = VideoCodecChoice.HEVC,
                        compressMode = CompressMode.QUALITY,
                        videoCrf = 25,
                        maxDimensionPx = cap720,
                        compressionTier = CompressionTier.TWO_PASS_HEVC
                    ),
                    SampleEntryDraft(
                        targetExtension = "mp4",
                        videoCodec = VideoCodecChoice.H264,
                        compressMode = CompressMode.QUALITY,
                        videoCrf = 26,
                        maxDimensionPx = cap480,
                        compressionTier = CompressionTier.COMPATIBLE_H264
                    )
                )
            }
            return seeds
                .map { it.harmonizedForContainer(it.targetExtension, it.videoCodec, probe.mediaKind) }
                .distinctBy { it.dedupeKey(probe.mediaKind) }
        }

        private fun parseTargetBytes(targetMbText: String): Long? {
            val mb = targetMbText.trim().toDoubleOrNull() ?: return null
            if (!mb.isFinite() || mb <= 0.0) return null
            return (mb * 1024.0 * 1024.0).roundToLong().coerceAtLeast(1024L)
        }

        private fun formatDimLabel(maxDimensionPx: Int?): String = when {
            maxDimensionPx == null || maxDimensionPx <= 0 -> "Orig"
            maxDimensionPx == 1920 -> "1080p"
            maxDimensionPx == 1280 -> "720p"
            maxDimensionPx == 854 -> "480p"
            maxDimensionPx == 640 -> "360p"
            else -> "${maxDimensionPx}px"
        }

        private fun formatTargetMbBadge(targetMbText: String): String? {
            val mb = targetMbText.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val num = if (abs(mb - mb.roundToInt()) < 1e-6) {
                mb.roundToInt().toString()
            } else {
                String.format(Locale.US, "%.2f", mb).trimEnd('0').trimEnd('.')
            }
            return "≤ $num MB"
        }

        private fun formatBytesToMbInput(bytes: Long): String {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            return if (abs(mb - mb.roundToInt()) < 0.01 && mb >= 1.0) {
                mb.roundToInt().toString()
            } else {
                String.format(Locale.US, "%.2f", mb).trimEnd('0').trimEnd('.')
            }
        }
    }
}

data class SampleCandidateSpec(
    val id: String,
    val indexNumber: Int,
    val label: String,
    val pipelineSpec: PipelineSpec,
    val isOriginalBaseline: Boolean = false,
    val voteState: SampleVoteState = SampleVoteState.NEUTRAL,
    val draft: SampleEntryDraft? = null
)

data class RenderedSampleResult(
    val candidateId: String,
    val previewFilePath: String?,
    val width: Int = 0,
    val height: Int = 0,
    val sampleBytes: Long = 0L,
    val projectedTotalBytes: Long = 0L,
    val sizeRatioVsOriginal: Double = 1.0,
    val perceptualScore: Double = 1.0,
    val isRecommendedBest: Boolean = false,
    val recommendationSummary: String? = null,
    val calibration: SampleCalibrationRatio? = null,
    val status: SampleRenderStatus = SampleRenderStatus.READY,
    val error: String? = null
)
