package com.fissal.forge.ui.controllers

import android.net.Uri
import com.fissal.forge.data.local.FormatPresetDao
import com.fissal.forge.data.local.FormatPresetEntity
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.domain.logic.AspectRatioSnapper
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.SampleCalibrationRatio
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressSpec
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.DeliverySpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.SilenceSkipSpec
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.engine.FFmpegCliEngine
import com.fissal.forge.engine.ImageRawEngine
import com.fissal.forge.engine.MediaProbeEngine
import com.fissal.forge.pipeline.PipelineOrchestrator
import com.fissal.forge.ui.StudioItemConfig
import com.fissal.forge.ui.StudioSessionState
import com.fissal.forge.ui.StudioToolTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Owns Forge Studio session state, batch item configs, editor previews, inline compression
 * sample calibration, preset overlays, and local-batch pipeline construction/enqueueing.
 */
class StudioSessionController(
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val settings: StateFlow<ForgeSettings>,
    private val deviceCapabilities: DeviceCapabilityReport,
    private val probeEngine: MediaProbeEngine,
    private val imageRawEngine: ImageRawEngine,
    private val ffmpegEngine: FFmpegCliEngine,
    private val orchestrator: PipelineOrchestrator,
    private val presetDao: FormatPresetDao,
    private val presetJson: Json,
    private val onShowBanner: (String) -> Unit,
    private val onNavigateToQueue: () -> Unit,
    private val onBeforeInitializeSession: () -> Unit
) {
    private val _studioSession = MutableStateFlow<StudioSessionState?>(null)
    val studioSession: StateFlow<StudioSessionState?> = _studioSession.asStateFlow()

    private var studioPreviewJob: Job? = null
    private var samplePreviewJob: Job? = null
    private val sampleVideoMutex = Mutex()
    private val samplePreviewDir: File
        get() = File(cacheDir, "sample_preview")
    private var cachedSampleVideoUri: String? = null
    private var cachedSampleVideoFile: File? = null

    fun sweepInitialCache() {
        scope.launch(Dispatchers.IO) {
            sampleVideoMutex.withLock {
                runCatching { samplePreviewDir.deleteRecursively() }
            }
        }
    }

    fun openStudioForUris(uris: List<Uri>, fromShareIntent: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            initializeStudioSessionForUris(uris, fromShareIntent)
        }
    }

    suspend fun initializeStudioSessionForUris(
        uris: List<Uri>,
        fromShareIntent: Boolean
    ): StudioSessionState? {
        if (uris.isEmpty()) return null
        onBeforeInitializeSession()
        val cur = settings.value
        val (probed, configs) = withContext(Dispatchers.IO) {
            val probedItems = uris.map { probeEngine.probeUri(it) }
            val defaultByKind = HashMap<MediaKind, PipelineSpec?>()
            for (kind in probedItems.map { it.mediaKind }.distinct()) {
                val preset = presetDao.getDefaultForKind(kind.name)
                defaultByKind[kind] = preset?.let {
                    runCatching { presetJson.decodeFromString<PipelineSpec>(it.specJson) }.getOrNull()
                }
            }
            val builtConfigs = probedItems.map { item ->
                val base = buildInitialStudioConfig(item, cur)
                val defaultSpec = defaultByKind[item.mediaKind]
                if (defaultSpec != null) overlayPresetOntoConfig(base, item, defaultSpec) else base
            }
            probedItems to builtConfigs
        }
        val primary = probed.first()
        val defaultTab = when (primary.mediaKind) {
            MediaKind.CAMERA_RAW -> StudioToolTab.CONVERT
            MediaKind.IMAGE, MediaKind.VIDEO -> StudioToolTab.COMPRESS
            MediaKind.AUDIO -> StudioToolTab.CONVERT
            else -> StudioToolTab.CONVERT
        }

        val session = StudioSessionState(
            items = probed,
            itemConfigs = configs,
            selectedItemIndex = 0,
            previewBitmap = null,
            isLoadingPreview = true,
            cameFromShareIntent = fromShareIntent,
            activeTab = defaultTab
        )
        _studioSession.value = session
        loadPreviewForSelectedItem(0)
        return session
    }

    private fun buildInitialStudioConfig(item: MediaProbeResult, cur: ForgeSettings): StudioItemConfig {
        val rawExt = item.extension.lowercase(Locale.US).trim()
        val defaultExt = when (item.mediaKind) {
            MediaKind.CAMERA_RAW -> cur.defaultImageFormat
            MediaKind.IMAGE -> when (rawExt) {
                "jpg", "jpeg" -> "jpg"
                "png", "webp" -> rawExt
                else -> cur.defaultImageFormat
            }
            MediaKind.VIDEO -> cur.defaultVideoExt
            MediaKind.AUDIO -> cur.defaultAudioFormat
            else -> rawExt.ifBlank { "mp4" }
        }
        val (resolvedPreset, resolvedRect) = AspectRatioSnapper.resolvePresetRect(
            preset = cur.defaultCropPreset,
            effectiveWidth = item.effectiveWidth,
            effectiveHeight = item.effectiveHeight
        )
        val tier = when (cur.defaultVideoCodec) {
            VideoCodecChoice.AV1 -> CompressionTier.PERCEPTUAL_AV1
            VideoCodecChoice.HEVC -> CompressionTier.TWO_PASS_HEVC
            else -> CompressionTier.COMPATIBLE_H264
        }
        val initialVideoCodec = if (defaultExt == "webm") {
            if (cur.defaultVideoCodec == VideoCodecChoice.AV1) VideoCodecChoice.AV1 else VideoCodecChoice.VP9
        } else {
            cur.defaultVideoCodec
        }
        val initialAudioCodec = when (defaultExt) {
            "mp3" -> AudioCodecChoice.MP3
            "opus", "ogg", "webm" -> AudioCodecChoice.OPUS
            "flac" -> AudioCodecChoice.FLAC
            "wav" -> AudioCodecChoice.WAV
            else -> AudioCodecChoice.AAC
        }
        return StudioItemConfig(
            convertEnabled = item.mediaKind == MediaKind.CAMERA_RAW,
            targetExtension = defaultExt,
            videoCodec = initialVideoCodec,
            audioCodec = initialAudioCodec,
            compressEnabled = false,
            compressMode = CompressMode.TARGET_BYTES,
            compressionTier = tier,
            videoCrf = cur.defaultVideoCrf,
            imageQuality = cur.defaultImageQuality,
            targetMbText = cur.defaultTargetSizeMb.toString(),
            rawHalfSizeFast = cur.rawHalfSizeFast || deviceCapabilities.recommendRawHalfSizeByDefault,
            trimEnabled = false,
            trimStartMs = 0L,
            trimEndMs = item.durationMs,
            silenceThresholdDb = cur.defaultSilenceSkip.thresholdDb,
            silenceMinDurationMs = cur.defaultSilenceSkip.minSilenceMs,
            cropEnabled = false,
            cropPreset = resolvedPreset,
            cropRect = resolvedRect,
            stripMetadata = cur.stripMetadataOnSave
        )
    }

    private fun loadPreviewForSelectedItem(index: Int) {
        studioPreviewJob?.cancel()
        studioPreviewJob = null
        val state = _studioSession.value ?: return
        val target = state.items.getOrNull(index) ?: return
        val cur = settings.value
        studioPreviewJob = scope.launch {
            _studioSession.update { curState ->
                if (curState == null) null else curState.copy(isLoadingPreview = true)
            }
            val preview = imageRawEngine.loadEditorPreviewBitmap(Uri.parse(target.uriString), target)
            var adopted = false
            _studioSession.update { latest ->
                adopted = false
                if (latest == null || latest.selectedItemIndex != index) {
                    return@update latest
                }
                adopted = true
                val updatedItems = latest.items.toMutableList()
                val updatedConfigs = latest.itemConfigs.toMutableList()
                if (preview != null && target.effectiveWidth <= 0) {
                    val patched = target.copy(rawWidth = preview.width, rawHeight = preview.height)
                    updatedItems[index] = patched
                    val (preset2, rect2) = AspectRatioSnapper.resolvePresetRect(
                        preset = cur.defaultCropPreset,
                        effectiveWidth = patched.effectiveWidth,
                        effectiveHeight = patched.effectiveHeight
                    )
                    updatedConfigs[index] = updatedConfigs[index].copy(cropPreset = preset2, cropRect = rect2)
                }
                latest.copy(
                    items = updatedItems,
                    itemConfigs = updatedConfigs,
                    previewBitmap = preview,
                    isLoadingPreview = false
                )
            }
            if (!adopted && preview != null && !preview.isRecycled) {
                preview.recycle()
            }
            val current = _studioSession.value
            if (current != null &&
                current.selectedItemIndex == index &&
                current.compressEnabled &&
                current.primaryItem.isVisual
            ) {
                scheduleInlineSampleGeneration()
            }
        }
    }

    fun selectStudioItem(index: Int) {
        val cur = _studioSession.value ?: return
        if (index !in cur.items.indices || index == cur.selectedItemIndex) return
        studioPreviewJob?.cancel()
        studioPreviewJob = null
        samplePreviewJob?.cancel()
        _studioSession.update {
            it?.copy(
                selectedItemIndex = index,
                previewBitmap = null,
                isLoadingPreview = true,
                originalSampleBitmap = null,
                compressedSampleBitmap = null,
                isGeneratingSample = false,
                showCompressedSample = false,
                sampleCalibration = null
            )
        }
        loadPreviewForSelectedItem(index)
    }

    fun applyStudioToSameType() {
        val state = _studioSession.value ?: return
        val sourceProbe = state.primaryItem
        val sourceCfg = state.activeConfig
        var count = 0
        val nextConfigs = state.items.mapIndexed { idx, probe ->
            val old = state.itemConfigs[idx]
            if (probe.mediaKind == sourceProbe.mediaKind) {
                count++
                sourceCfg.copy(
                    trimStartMs = old.trimStartMs,
                    trimEndMs = old.trimEndMs,
                    cropPreset = old.cropPreset,
                    cropRect = old.cropRect
                )
            } else {
                old
            }
        }
        _studioSession.update { it?.copy(itemConfigs = nextConfigs) }
        onShowBanner("Applied settings to $count ${sourceProbe.mediaKind.name.lowercase()} file(s)")
    }

    fun applyStudioToAll() {
        val state = _studioSession.value ?: return
        val sourceCfg = state.activeConfig
        val nextConfigs = state.items.mapIndexed { idx, probe ->
            val old = state.itemConfigs[idx]
            sourceCfg.copy(
                targetExtension = if (probe.mediaKind == state.primaryItem.mediaKind) sourceCfg.targetExtension else old.targetExtension,
                trimStartMs = old.trimStartMs,
                trimEndMs = old.trimEndMs,
                cropPreset = old.cropPreset,
                cropRect = old.cropRect
            )
        }
        _studioSession.update { it?.copy(itemConfigs = nextConfigs) }
        onShowBanner("Applied settings to all ${state.items.size} files")
    }

    fun closeStudioSession() {
        studioPreviewJob?.cancel()
        studioPreviewJob = null
        val activeSampleJob = samplePreviewJob
        samplePreviewJob = null
        activeSampleJob?.cancel()
        scope.launch(Dispatchers.IO) {
            runCatching { activeSampleJob?.cancelAndJoin() }
            sampleVideoMutex.withLock {
                cachedSampleVideoUri = null
                cachedSampleVideoFile = null
                runCatching { samplePreviewDir.deleteRecursively() }
            }
        }
        _studioSession.update { null }
    }

    fun updateStudio(transform: (StudioItemConfig) -> StudioItemConfig) {
        var shouldRegenerateSample = false
        var turnedCompressOff = false
        _studioSession.update { state ->
            if (state == null) {
                null
            } else {
                val before = state.activeConfig
                val nextState = state.updateActiveConfig(transform)
                val after = nextState.activeConfig
                val sampleInputsChanged = before.sampleSignature() != after.sampleSignature()
                val turnedCompressOn = !before.compressEnabled && after.compressEnabled
                turnedCompressOff = before.compressEnabled && !after.compressEnabled
                shouldRegenerateSample = (sampleInputsChanged || turnedCompressOn) &&
                    after.compressEnabled &&
                    nextState.primaryItem.isVisual
                when {
                    !after.compressEnabled -> nextState.copy(
                        originalSampleBitmap = null,
                        compressedSampleBitmap = null,
                        isGeneratingSample = false,
                        showCompressedSample = false,
                        sampleCalibration = null
                    )
                    sampleInputsChanged -> nextState.copy(sampleCalibration = null)
                    else -> nextState
                }
            }
        }
        if (turnedCompressOff) {
            samplePreviewJob?.cancel()
            samplePreviewJob = null
        } else if (shouldRegenerateSample) {
            scheduleInlineSampleGeneration()
        }
    }

    fun setStudioSectionEnabled(section: StudioToolTab, enabled: Boolean) {
        updateStudio { cfg ->
            when (section) {
                StudioToolTab.CONVERT -> cfg.copy(convertEnabled = enabled)
                StudioToolTab.COMPRESS -> cfg.copy(compressEnabled = enabled)
                StudioToolTab.TRIM -> cfg.copy(trimEnabled = enabled)
                StudioToolTab.CROP -> cfg.copy(cropEnabled = enabled)
            }
        }
    }

    fun toggleSamplePreviewComparison() {
        _studioSession.update { state ->
            val comp = state?.compressedSampleBitmap
            if (state == null || comp == null || comp.isRecycled) {
                state
            } else {
                state.copy(showCompressedSample = !state.showCompressedSample)
            }
        }
    }

    fun clearInlineWatchJobs() {
        _studioSession.update { it?.copy(inlineWatchJobIds = emptyList()) }
    }

    fun selectStudioCropPreset(preset: AspectPreset) {
        var shouldRegenerateSample = false
        _studioSession.update { state ->
            if (state == null) return@update null
            val p = state.primaryItem
            val (resolvedPreset, resolvedRect) = AspectRatioSnapper.resolvePresetRect(
                preset = preset,
                effectiveWidth = p.effectiveWidth,
                effectiveHeight = p.effectiveHeight
            )
            val next = state.updateActiveConfig { cfg ->
                cfg.copy(
                    cropEnabled = true,
                    cropPreset = resolvedPreset,
                    cropRect = resolvedRect
                )
            }.copy(
                originalSampleBitmap = null,
                compressedSampleBitmap = null,
                showCompressedSample = false,
                sampleCalibration = null
            )
            shouldRegenerateSample = next.compressEnabled && p.isVisual
            next
        }
        if (shouldRegenerateSample) {
            scheduleInlineSampleGeneration()
        }
    }

    fun applyPresetToStudio(preset: FormatPresetEntity) {
        val item = _studioSession.value?.primaryItem ?: return
        val decoded = runCatching { presetJson.decodeFromString<PipelineSpec>(preset.specJson) }.getOrNull()
        if (decoded == null) {
            onShowBanner("Preset format could not be read")
            return
        }
        updateStudio { cfg -> overlayPresetOntoConfig(cfg, item, decoded) }
        onShowBanner("Applied ${preset.emoji} ${preset.name}")
    }

    fun applySampleCandidateSpec(
        targetItemIndex: Int,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        calibration: SampleCalibrationRatio?
    ) {
        val currentStudio = _studioSession.value
        if (currentStudio != null && targetItemIndex in currentStudio.items.indices &&
            currentStudio.selectedItemIndex != targetItemIndex
        ) {
            selectStudioItem(targetItemIndex)
        }
        _studioSession.update { st ->
            if (st == null) return@update null
            val updatedState = st.updateActiveConfig { cfg ->
                overlayPresetOntoConfig(cfg, probe, spec).copy(
                    convertEnabled = true,
                    compressEnabled = true
                )
            }
            updatedState.copy(
                activeTab = StudioToolTab.COMPRESS,
                sampleCalibration = calibration,
                showCompressedSample = false
            )
        }
        scheduleInlineSampleGeneration()
    }

    fun buildPipelineSpecForStudioItem(
        probe: MediaProbeResult,
        cfg: StudioItemConfig,
        saveToDevice: Boolean = true,
        copyToClipboard: Boolean = false,
        forceCompressSpec: Boolean = false
    ): PipelineSpec {
        val curSettings = settings.value
        val stripMeta = cfg.stripMetadata || (!saveToDevice && copyToClipboard && curSettings.stripMetadataOnClipboard)
        val includeCompress = cfg.compressEnabled || forceCompressSpec

        val convertSpec = if (cfg.convertEnabled || probe.mediaKind == MediaKind.CAMERA_RAW ||
            (includeCompress && cfg.compressMode == CompressMode.TARGET_BYTES && probe.mediaKind == MediaKind.VIDEO)
        ) {
            val chosenVideo = when {
                cfg.convertEnabled -> cfg.videoCodec
                includeCompress && cfg.compressMode == CompressMode.TARGET_BYTES -> when (cfg.compressionTier) {
                    CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                    CompressionTier.TWO_PASS_HEVC -> VideoCodecChoice.HEVC
                    CompressionTier.COMPATIBLE_H264 -> VideoCodecChoice.H264
                }
                else -> cfg.videoCodec
            }
            val chosenAudio = when {
                cfg.muteAudio -> AudioCodecChoice.MUTE
                cfg.convertEnabled -> cfg.audioCodec
                includeCompress && cfg.compressMode == CompressMode.TARGET_BYTES &&
                    cfg.compressionTier == CompressionTier.PERCEPTUAL_AV1 -> AudioCodecChoice.OPUS
                else -> cfg.audioCodec
            }
            ConvertSpec(
                targetExtension = cfg.targetExtension,
                videoCodec = chosenVideo,
                audioCodec = chosenAudio
            )
        } else {
            null
        }

        val targetBytes = cfg.targetMbText.toDoubleOrNull()?.let { mb ->
            if (mb > 0.0) (mb * 1024.0 * 1024.0).roundToLong() else null
        }

        val compressSpec = if (includeCompress) {
            CompressSpec(
                mode = cfg.compressMode,
                videoCrf = cfg.videoCrf,
                imageQuality = cfg.imageQuality,
                targetBytes = if (cfg.compressMode == CompressMode.TARGET_BYTES) targetBytes else null,
                maxDimensionPx = cfg.maxDimensionPx,
                rawHalfSizeFast = cfg.rawHalfSizeFast,
                tier = cfg.compressionTier,
                twoPass = true
            )
        } else {
            null
        }

        val silenceSkipSpec = if (cfg.trimEnabled && cfg.silenceSkipEnabled && probe.isTimeBased) {
            SilenceSkipSpec(
                enabled = true,
                thresholdDb = cfg.silenceThresholdDb.coerceIn(-90, -10),
                minSilenceMs = cfg.silenceMinDurationMs.coerceIn(100, 10_000)
            )
        } else {
            null
        }

        val trimSpec = if (cfg.trimEnabled && probe.isTimeBased) {
            TrimSpec(
                startMs = cfg.trimStartMs,
                endMs = cfg.trimEndMs.takeIf { it > cfg.trimStartMs && it < probe.durationMs }
                    ?: if (cfg.trimEndMs > cfg.trimStartMs) cfg.trimEndMs else null,
                fastKeyframeCopy = cfg.fastKeyframeCopy,
                fadeInOut = cfg.fadeInOut,
                muteAudio = cfg.muteAudio,
                silenceSkip = silenceSkipSpec
            )
        } else {
            null
        }

        val cropSpec = if (cfg.cropEnabled && probe.isVisual) {
            CropSpec(preset = cfg.cropPreset, rect = cfg.cropRect)
        } else {
            null
        }

        return PipelineSpec(
            convert = convertSpec,
            compress = compressSpec,
            trim = trimSpec,
            crop = cropSpec,
            delivery = DeliverySpec(
                saveToDevice = saveToDevice,
                copyToClipboard = copyToClipboard,
                stripMetadata = stripMeta,
                filenameSuffix = curSettings.filenameSuffix
            ),
            silenceSkip = silenceSkipSpec
        )
    }

    fun enqueueStudioSession(saveToDevice: Boolean, copyToClipboard: Boolean) {
        val state = _studioSession.value ?: return

        val batchPairs = state.items.mapIndexed { idx, probe ->
            val cfg = state.itemConfigs[idx]
            probe to buildPipelineSpecForStudioItem(
                probe = probe,
                cfg = cfg,
                saveToDevice = saveToDevice,
                copyToClipboard = copyToClipboard
            )
        }

        scope.launch {
            val ids = orchestrator.enqueueLocalBatch(batchPairs)
            if (copyToClipboard && !state.isBatch) {
                _studioSession.update { it?.copy(inlineWatchJobIds = ids) }
            } else {
                closeStudioSession()
                onNavigateToQueue()
                onShowBanner("Queued ${batchPairs.size} job${if (batchPairs.size > 1) "s" else ""}")
            }
        }
    }

    fun moveInlineStudioToBackground() {
        closeStudioSession()
        onNavigateToQueue()
    }

    fun cleanupOnCleared() {
        studioPreviewJob?.cancel()
        studioPreviewJob = null
        samplePreviewJob?.cancel()
        samplePreviewJob = null
        cachedSampleVideoUri = null
        cachedSampleVideoFile = null
        val previewDir = samplePreviewDir
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            sampleVideoMutex.withLock {
                runCatching { previewDir.deleteRecursively() }
            }
        }
    }

    private fun overlayPresetOntoConfig(
        cfg: StudioItemConfig,
        probe: MediaProbeResult,
        spec: PipelineSpec
    ): StudioItemConfig {
        val conv = spec.convert
        val comp = spec.compress
        val trim = spec.trim
        val silence = spec.silenceSkip ?: trim?.silenceSkip
        val targetExt = conv?.targetExtension?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: cfg.targetExtension
        val validExt = when (probe.mediaKind) {
            MediaKind.IMAGE, MediaKind.CAMERA_RAW ->
                if (targetExt in setOf("jpg", "jpeg", "png", "webp")) {
                    if (targetExt == "jpeg") "jpg" else targetExt
                } else {
                    cfg.targetExtension
                }
            else -> targetExt
        }
        val targetMbText = comp?.targetBytes?.takeIf { it > 0L }?.let { bytes ->
            val mb = bytes / (1024.0 * 1024.0)
            if (abs(mb - mb.roundToInt()) < 0.01 && mb >= 1.0) {
                mb.roundToInt().toString()
            } else {
                String.format(Locale.US, "%.2f", mb).trimEnd('0').trimEnd('.')
            }
        } ?: cfg.targetMbText

        val hasCustomTrimRange = cfg.trimStartMs > 0L || (probe.durationMs > 0L && cfg.trimEndMs in 1L until probe.durationMs)
        val needsTrimForPresetFlags = (trim != null && (trim.fastKeyframeCopy || trim.fadeInOut || trim.muteAudio)) ||
            silence?.enabled == true
        val nextTrimEnabled = probe.isTimeBased && ((cfg.trimEnabled && hasCustomTrimRange) || needsTrimForPresetFlags)

        return cfg.copy(
            convertEnabled = conv != null,
            targetExtension = validExt,
            videoCodec = conv?.videoCodec ?: cfg.videoCodec,
            audioCodec = conv?.audioCodec ?: cfg.audioCodec,
            compressEnabled = comp != null,
            compressMode = comp?.mode ?: cfg.compressMode,
            compressionTier = comp?.tier ?: cfg.compressionTier,
            videoCrf = (comp?.videoCrf ?: cfg.videoCrf).coerceIn(14, 42),
            imageQuality = (comp?.imageQuality ?: cfg.imageQuality).coerceIn(15, 100),
            targetMbText = targetMbText,
            maxDimensionPx = if (comp != null) comp.maxDimensionPx else null,
            rawHalfSizeFast = comp?.rawHalfSizeFast ?: false,
            trimEnabled = nextTrimEnabled,
            fastKeyframeCopy = trim?.fastKeyframeCopy ?: false,
            fadeInOut = trim?.fadeInOut ?: false,
            muteAudio = trim?.muteAudio ?: false,
            silenceSkipEnabled = silence?.enabled ?: false,
            silenceThresholdDb = silence?.thresholdDb ?: cfg.silenceThresholdDb,
            silenceMinDurationMs = silence?.minSilenceMs ?: cfg.silenceMinDurationMs,
            stripMetadata = spec.delivery.stripMetadata
        )
    }

    private fun scheduleInlineSampleGeneration() {
        samplePreviewJob?.cancel()
        val state = _studioSession.value ?: return
        val item = state.primaryItem
        if (!item.isVisual || !state.compressEnabled) return

        val itemIdx = state.selectedItemIndex
        val cfg = state.activeConfig
        if (item.mediaKind != MediaKind.VIDEO && (state.previewBitmap == null || state.previewBitmap.isRecycled)) {
            return
        }

        val spec = buildPipelineSpecForStudioItem(
            probe = item,
            cfg = cfg,
            forceCompressSpec = true
        )

        _studioSession.update { cur ->
            if (cur != null && cur.selectedItemIndex == itemIdx) {
                cur.copy(isGeneratingSample = true)
            } else {
                cur
            }
        }

        lateinit var launchedJob: Job
        launchedJob = scope.launch {
            try {
                delay(350L)
                val currentState = _studioSession.value ?: return@launch
                if (currentState.selectedItemIndex != itemIdx) return@launch

                when (item.mediaKind) {
                    MediaKind.IMAGE, MediaKind.CAMERA_RAW -> {
                        val previewBmp = currentState.previewBitmap ?: return@launch
                        if (previewBmp.isRecycled) return@launch
                        val res = imageRawEngine.generateImageSamplePreview(previewBmp, item, spec)
                        var adopted = false
                        _studioSession.update { latest ->
                            adopted = false
                            if (latest != null && latest.selectedItemIndex == itemIdx) {
                                adopted = true
                                latest.copy(
                                    originalSampleBitmap = res.originalBitmap,
                                    compressedSampleBitmap = res.compressedBitmap,
                                    sampleCalibration = res.calibration,
                                    isGeneratingSample = false
                                )
                            } else {
                                latest
                            }
                        }
                        if (!adopted) {
                            if (!res.originalBitmap.isRecycled) res.originalBitmap.recycle()
                            if (!res.compressedBitmap.isRecycled) res.compressedBitmap.recycle()
                        }
                    }
                    MediaKind.VIDEO -> {
                        val workDir = samplePreviewDir.apply { if (!exists()) mkdirs() }
                        val videoFile = withContext(Dispatchers.IO) {
                            resolveOrStageSampleVideo(item.uriString)
                        }
                        val startMs = if (cfg.trimEnabled) cfg.trimStartMs.coerceAtLeast(0L) else 0L
                        val endMs = if (cfg.trimEnabled && cfg.trimEndMs > startMs) {
                            cfg.trimEndMs
                        } else {
                            item.durationMs.coerceAtLeast(startMs)
                        }
                        val midpointMs = if (endMs > startMs) (startMs + endMs) / 2L else 0L
                        val res = ffmpegEngine.generateVideoSampleFrame(
                            inputFile = videoFile,
                            probe = item,
                            spec = spec,
                            timestampMs = midpointMs,
                            workDir = workDir
                        )
                        var adopted = false
                        _studioSession.update { latest ->
                            adopted = false
                            if (latest != null && latest.selectedItemIndex == itemIdx) {
                                adopted = true
                                latest.copy(
                                    originalSampleBitmap = res.originalBitmap,
                                    compressedSampleBitmap = res.compressedBitmap,
                                    sampleCalibration = res.calibration,
                                    isGeneratingSample = false
                                )
                            } else {
                                latest
                            }
                        }
                        if (!adopted) {
                            if (!res.originalBitmap.isRecycled) res.originalBitmap.recycle()
                            if (!res.compressedBitmap.isRecycled) res.compressedBitmap.recycle()
                        }
                    }
                    else -> {
                        _studioSession.update { latest ->
                            if (latest != null && latest.selectedItemIndex == itemIdx) {
                                latest.copy(isGeneratingSample = false)
                            } else {
                                latest
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _studioSession.update { latest ->
                    if (latest != null && samplePreviewJob === launchedJob) {
                        latest.copy(isGeneratingSample = false)
                    } else {
                        latest
                    }
                }
            }
        }
        samplePreviewJob = launchedJob
    }

    private suspend fun resolveOrStageSampleVideo(uriString: String): File = sampleVideoMutex.withLock {
        val dir = samplePreviewDir.apply { if (!exists()) mkdirs() }
        val existing = cachedSampleVideoFile
        if (cachedSampleVideoUri == uriString && existing != null && existing.exists() && existing.canRead()) {
            return@withLock existing
        }
        val previous = existing
        val parsedUri = Uri.parse(uriString)
        val staged = if (parsedUri.scheme == "file" && parsedUri.path != null) {
            File(parsedUri.path!!)
        } else {
            probeEngine.stageUriToTempFile(parsedUri, dir, "sample_src")
        }
        if (previous != null && previous.absolutePath != staged.absolutePath &&
            previous.parentFile?.absolutePath == dir.absolutePath
        ) {
            runCatching { previous.delete() }
        }
        cachedSampleVideoUri = uriString
        cachedSampleVideoFile = staged
        staged
    }

    private fun StudioItemConfig.sampleSignature(): List<Any?> = listOf(
        convertEnabled,
        targetExtension,
        videoCodec,
        audioCodec,
        compressMode,
        compressionTier,
        videoCrf,
        imageQuality,
        targetMbText,
        maxDimensionPx,
        rawHalfSizeFast,
        trimEnabled,
        trimStartMs,
        trimEndMs,
        muteAudio,
        silenceSkipEnabled,
        silenceThresholdDb,
        silenceMinDurationMs,
        cropEnabled,
        cropPreset,
        cropRect
    )
}
