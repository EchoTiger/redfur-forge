package com.fissal.forge.ui

import android.graphics.Bitmap
import com.fissal.forge.auth.ServiceDescriptor
import com.fissal.forge.domain.logic.SampleCalibrationRatio
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.CropRectNormalized
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.RenderedSampleResult
import com.fissal.forge.domain.model.SampleCandidateSpec
import com.fissal.forge.domain.model.SampleVoteState
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice

enum class MainTab {
    FORGE,
    QUEUE,
    SETTINGS
}

enum class StudioToolTab {
    CONVERT,
    COMPRESS,
    TRIM,
    CROP
}

data class StudioItemConfig(
    val convertEnabled: Boolean = false,
    val targetExtension: String = "mp4",
    val videoCodec: VideoCodecChoice = VideoCodecChoice.H264,
    val audioCodec: AudioCodecChoice = AudioCodecChoice.AAC,
    val compressEnabled: Boolean = false,
    val compressMode: CompressMode = CompressMode.TARGET_BYTES,
    val compressionTier: CompressionTier = CompressionTier.PERCEPTUAL_AV1,
    val videoCrf: Int = 23,
    val imageQuality: Int = 88,
    val targetMbText: String = "8",
    val maxDimensionPx: Int? = null,
    val rawHalfSizeFast: Boolean = false,
    val trimEnabled: Boolean = false,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val fastKeyframeCopy: Boolean = false,
    val fadeInOut: Boolean = false,
    val muteAudio: Boolean = false,
    val silenceSkipEnabled: Boolean = false,
    val silenceThresholdDb: Int = -38,
    val silenceMinDurationMs: Int = 600,
    val cropEnabled: Boolean = false,
    val cropPreset: AspectPreset = AspectPreset.AUTO_CLOSEST,
    val cropRect: CropRectNormalized = CropRectNormalized(),
    val stripMetadata: Boolean = false
)

data class AuthPromptState(
    val service: ServiceDescriptor?,
    val targetUrl: String,
    val reason: String,
    val isPaywallOrSubscription: Boolean = false,
    val retryCleanUrl: String? = null,
    val retryJobId: String? = null
)

data class StudioSessionState(
    val items: List<MediaProbeResult>,
    val itemConfigs: List<StudioItemConfig>,
    val selectedItemIndex: Int = 0,
    val previewBitmap: Bitmap? = null,
    val isLoadingPreview: Boolean = true,
    val cameFromShareIntent: Boolean = false,
    val activeTab: StudioToolTab = StudioToolTab.CONVERT,
    val inlineWatchJobIds: List<String> = emptyList(),
    val originalSampleBitmap: Bitmap? = null,
    val compressedSampleBitmap: Bitmap? = null,
    val isGeneratingSample: Boolean = false,
    val showCompressedSample: Boolean = false,
    val sampleCalibration: SampleCalibrationRatio? = null
) {
    val primaryItem: MediaProbeResult
        get() = items.getOrElse(selectedItemIndex) { items.first() }

    val activeConfig: StudioItemConfig
        get() = itemConfigs.getOrElse(selectedItemIndex) { itemConfigs.first() }

    val isBatch: Boolean
        get() = items.size > 1

    // Convenience forwarding properties to keep panel call sites clean while supporting per-item configs
    val convertEnabled: Boolean get() = activeConfig.convertEnabled
    val targetExtension: String get() = activeConfig.targetExtension
    val videoCodec: VideoCodecChoice get() = activeConfig.videoCodec
    val audioCodec: AudioCodecChoice get() = activeConfig.audioCodec
    val compressEnabled: Boolean get() = activeConfig.compressEnabled
    val compressMode: CompressMode get() = activeConfig.compressMode
    val compressionTier: CompressionTier get() = activeConfig.compressionTier
    val videoCrf: Int get() = activeConfig.videoCrf
    val imageQuality: Int get() = activeConfig.imageQuality
    val targetMbText: String get() = activeConfig.targetMbText
    val maxDimensionPx: Int? get() = activeConfig.maxDimensionPx
    val rawHalfSizeFast: Boolean get() = activeConfig.rawHalfSizeFast
    val trimEnabled: Boolean get() = activeConfig.trimEnabled
    val trimStartMs: Long get() = activeConfig.trimStartMs
    val trimEndMs: Long get() = activeConfig.trimEndMs
    val fastKeyframeCopy: Boolean get() = activeConfig.fastKeyframeCopy
    val fadeInOut: Boolean get() = activeConfig.fadeInOut
    val muteAudio: Boolean get() = activeConfig.muteAudio
    val silenceSkipEnabled: Boolean get() = activeConfig.silenceSkipEnabled
    val silenceThresholdDb: Int get() = activeConfig.silenceThresholdDb
    val silenceMinDurationMs: Int get() = activeConfig.silenceMinDurationMs
    val cropEnabled: Boolean get() = activeConfig.cropEnabled
    val cropPreset: AspectPreset get() = activeConfig.cropPreset
    val cropRect: CropRectNormalized get() = activeConfig.cropRect
    val stripMetadata: Boolean get() = activeConfig.stripMetadata

    fun updateActiveConfig(transform: (StudioItemConfig) -> StudioItemConfig): StudioSessionState {
        val idx = selectedItemIndex.coerceIn(0, itemConfigs.lastIndex)
        val updated = itemConfigs.toMutableList()
        updated[idx] = transform(updated[idx])
        return copy(itemConfigs = updated)
    }
}

data class SampleLabSessionState(
    val sessionId: String,
    val studioItemIndex: Int,
    val probe: MediaProbeResult,
    val inheritedTrim: TrimSpec? = null,
    val inheritedCrop: CropSpec? = null,
    val stripMetadata: Boolean = false,
    val returnToHubOnClose: Boolean = false,
    val timestampMs: Long = 0L,
    val includeOriginalFrame: Boolean = true,
    val origVoteState: SampleVoteState = SampleVoteState.NEUTRAL,
    val candidates: List<SampleCandidateSpec> = emptyList(),
    val renderedResults: Map<String, RenderedSampleResult> = emptyMap(),
    val referencePreviewPath: String? = null,
    val isPreparingBaseline: Boolean = true,
    val isRenderingSamples: Boolean = false,
    val baselineError: String? = null,
    val isFullscreenCompareOpen: Boolean = false,
    val fullscreenActiveTabId: String = "orig"
) {
    fun buildComparatorTabs(): List<SampleCandidateSpec> {
        val list = mutableListOf<SampleCandidateSpec>()
        val hasOrigReady = includeOriginalFrame && !referencePreviewPath.isNullOrBlank()
        if (hasOrigReady) {
            list += SampleCandidateSpec(
                id = "orig",
                indexNumber = 0,
                label = "ORIG",
                pipelineSpec = PipelineSpec(),
                isOriginalBaseline = true,
                voteState = origVoteState
            )
        }
        list += candidates.filter { !it.isOriginalBaseline }
        return list
    }
}
