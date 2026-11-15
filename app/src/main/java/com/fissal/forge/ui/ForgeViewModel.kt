package com.fissal.forge.ui

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fissal.forge.ForgeApplication
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.domain.logic.AspectRatioSnapper
import com.fissal.forge.domain.logic.ParsedUrlItem
import com.fissal.forge.domain.logic.UrlBatchParser
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressSpec
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.CropRectNormalized
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.DeliverySpec
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.DownloadSpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.pipeline.OrchestratorMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

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

data class StudioSessionState(
    val items: List<MediaProbeResult>,
    val previewBitmap: Bitmap? = null,
    val isLoadingPreview: Boolean = true,
    val cameFromShareIntent: Boolean = false,
    val activeTab: StudioToolTab = StudioToolTab.CONVERT,

    // Toggleable stages
    val convertEnabled: Boolean = false,
    val targetExtension: String = "mp4",
    val videoCodec: VideoCodecChoice = VideoCodecChoice.H264,
    val audioCodec: AudioCodecChoice = AudioCodecChoice.AAC,

    val compressEnabled: Boolean = false,
    val compressMode: CompressMode = CompressMode.TARGET_BYTES,
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

    val cropEnabled: Boolean = false,
    val cropPreset: AspectPreset = AspectPreset.AUTO_CLOSEST,
    val cropRect: CropRectNormalized = CropRectNormalized(),

    val stripMetadata: Boolean = false,
    val inlineWatchJobIds: List<String> = emptyList()
) {
    val primaryItem: MediaProbeResult
        get() = items.first()

    val isBatch: Boolean
        get() = items.size > 1

    val anyStageEnabled: Boolean
        get() = convertEnabled || compressEnabled || trimEnabled || cropEnabled
}

class ForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val app: ForgeApplication = application as ForgeApplication
    private val orchestrator = app.orchestrator
    private val settingsRepo = app.settingsRepository
    private val probeEngine = app.probeEngine
    private val imageRawEngine = app.imageRawEngine
    private val binaryManager = app.binaryManager

    val settings: StateFlow<ForgeSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ForgeSettings())

    val jobs: StateFlow<List<ForgeJobEntity>> = orchestrator.jobsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val metrics: StateFlow<OrchestratorMetrics> = orchestrator.metrics

    private val _selectedTab = MutableStateFlow(MainTab.FORGE)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _urlBatchText = MutableStateFlow("")
    val urlBatchText: StateFlow<String> = _urlBatchText.asStateFlow()

    private val _parsedUrls = MutableStateFlow<List<ParsedUrlItem>>(emptyList())
    val parsedUrls: StateFlow<List<ParsedUrlItem>> = _parsedUrls.asStateFlow()

    private val _studioSession = MutableStateFlow<StudioSessionState?>(null)
    val studioSession: StateFlow<StudioSessionState?> = _studioSession.asStateFlow()

    private val _ytDlpVersion = MutableStateFlow(binaryManager.getYtDlpVersionName())
    val ytDlpVersion: StateFlow<String> = _ytDlpVersion.asStateFlow()

    private val _isUpdatingYtDlp = MutableStateFlow(false)
    val isUpdatingYtDlp: StateFlow<Boolean> = _isUpdatingYtDlp.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow(orchestrator.totalCacheBytes())
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    fun selectTab(tab: MainTab) {
        _selectedTab.value = tab
        if (tab == MainTab.SETTINGS) {
            refreshCacheSize()
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun showBanner(message: String) {
        _statusBanner.value = message
    }

    fun updateUrlBatchText(newText: String) {
        _urlBatchText.value = newText
        _parsedUrls.value = UrlBatchParser.extractUrls(newText, settings.value.stripUrlTracking)
    }

    fun pasteUrlsFromClipboard() {
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) {
            showBanner("Clipboard is empty")
            return
        }
        val clip = clipboard.primaryClip ?: return
        val desc = clip.description
        if (desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) {
            val text = clip.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
            if (text.isNotBlank()) {
                val combined = if (_urlBatchText.value.isBlank()) text else "${_urlBatchText.value.trimEnd()}\n$text"
                updateUrlBatchText(combined)
            }
        } else {
            showBanner("Clipboard holds media, not URL text")
        }
    }

    fun queueUrlDownloads(
        mode: DownloadMode,
        maxHeight: Int?,
        audioFormat: String,
        playlistSingleOnly: Boolean,
        targetSizeMb: Int?,
        copyToClipboard: Boolean,
        saveToDevice: Boolean
    ) {
        val urls = _parsedUrls.value
        if (urls.isEmpty()) {
            showBanner("Paste at least one valid URL first")
            return
        }
        val currentSettings = settings.value
        val compressSpec = if (targetSizeMb != null && targetSizeMb > 0) {
            CompressSpec(
                mode = CompressMode.TARGET_BYTES,
                videoCrf = currentSettings.defaultVideoCrf,
                targetBytes = targetSizeMb.toLong() * 1024L * 1024L
            )
        } else {
            null
        }
        val convertSpec = if (compressSpec != null && mode != DownloadMode.AUDIO_ONLY) {
            ConvertSpec(
                targetExtension = "mp4",
                videoCodec = currentSettings.defaultVideoCodec,
                audioCodec = AudioCodecChoice.AAC
            )
        } else {
            null
        }

        val specs = urls.map { item ->
            PipelineSpec(
                download = DownloadSpec(
                    url = item.cleanUrl,
                    playlistSingleOnly = playlistSingleOnly,
                    mode = mode,
                    maxHeight = maxHeight,
                    audioFormat = audioFormat,
                    embedMetadata = currentSettings.embedDownloadMetadata,
                    embedThumbnail = currentSettings.embedDownloadThumbnail,
                    useAria2c = currentSettings.useAria2c
                ),
                convert = convertSpec,
                compress = compressSpec,
                delivery = DeliverySpec(
                    saveToDevice = saveToDevice,
                    copyToClipboard = copyToClipboard,
                    stripMetadata = currentSettings.stripMetadataOnClipboard && copyToClipboard && !saveToDevice,
                    filenameSuffix = currentSettings.filenameSuffix
                )
            )
        }

        viewModelScope.launch {
            orchestrator.enqueueDownloadBatch(specs)
            _urlBatchText.value = ""
            _parsedUrls.value = emptyList()
            _selectedTab.value = MainTab.QUEUE
            showBanner("Queued ${specs.size} download${if (specs.size > 1) "s" else ""}")
        }
    }

    fun openStudioForUris(uris: List<Uri>, fromShareIntent: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val probed = withContext(Dispatchers.IO) {
                uris.map { probeEngine.probeUri(it) }
            }
            val primary = probed.first()
            val cur = settings.value

            val defaultExt = when (primary.mediaKind) {
                MediaKind.CAMERA_RAW -> cur.defaultImageFormat
                MediaKind.IMAGE -> primary.extension.ifBlank { cur.defaultImageFormat }
                MediaKind.VIDEO -> cur.defaultVideoExt
                MediaKind.AUDIO -> cur.defaultAudioFormat
                else -> primary.extension.ifBlank { "mp4" }
            }

            val defaultTab = when (primary.mediaKind) {
                MediaKind.CAMERA_RAW -> StudioToolTab.CONVERT
                MediaKind.IMAGE -> StudioToolTab.COMPRESS
                MediaKind.VIDEO -> StudioToolTab.COMPRESS
                MediaKind.AUDIO -> StudioToolTab.CONVERT
                else -> StudioToolTab.CONVERT
            }

            val (resolvedPreset, resolvedRect) = AspectRatioSnapper.resolvePresetRect(
                preset = cur.defaultCropPreset,
                effectiveWidth = primary.effectiveWidth,
                effectiveHeight = primary.effectiveHeight
            )

            _studioSession.value = StudioSessionState(
                items = probed,
                previewBitmap = null,
                isLoadingPreview = true,
                cameFromShareIntent = fromShareIntent,
                activeTab = defaultTab,
                convertEnabled = primary.mediaKind == MediaKind.CAMERA_RAW,
                targetExtension = defaultExt,
                videoCodec = cur.defaultVideoCodec,
                audioCodec = if (defaultExt == "mp3") AudioCodecChoice.MP3 else AudioCodecChoice.AAC,
                compressEnabled = false,
                compressMode = CompressMode.TARGET_BYTES,
                videoCrf = cur.defaultVideoCrf,
                imageQuality = cur.defaultImageQuality,
                targetMbText = cur.defaultTargetSizeMb.toString(),
                rawHalfSizeFast = cur.rawHalfSizeFast,
                trimEnabled = false,
                trimStartMs = 0L,
                trimEndMs = primary.durationMs,
                cropEnabled = false,
                cropPreset = resolvedPreset,
                cropRect = resolvedRect,
                stripMetadata = cur.stripMetadataOnSave
            )

            // Load preview asynchronously without blocking Studio screen mount
            val preview = imageRawEngine.loadEditorPreviewBitmap(Uri.parse(primary.uriString), primary)
            _studioSession.update { state ->
                if (state == null) null
                else {
                    val updatedItems = if (preview != null && primary.effectiveWidth <= 0) {
                        val patched = primary.copy(rawWidth = preview.width, rawHeight = preview.height)
                        listOf(patched) + state.items.drop(1)
                    } else {
                        state.items
                    }
                    val p = updatedItems.first()
                    val (preset2, rect2) = AspectRatioSnapper.resolvePresetRect(
                        preset = cur.defaultCropPreset,
                        effectiveWidth = p.effectiveWidth,
                        effectiveHeight = p.effectiveHeight
                    )
                    state.copy(
                        items = updatedItems,
                        previewBitmap = preview,
                        isLoadingPreview = false,
                        cropPreset = preset2,
                        cropRect = rect2
                    )
                }
            }
        }
    }

    fun closeStudioSession() {
        _studioSession.update { state ->
            state?.previewBitmap?.let { if (!it.isRecycled) it.recycle() }
            null
        }
    }

    fun updateStudio(transform: (StudioSessionState) -> StudioSessionState) {
        _studioSession.update { state ->
            if (state == null) null else transform(state)
        }
    }

    fun selectStudioCropPreset(preset: AspectPreset) {
        _studioSession.update { state ->
            if (state == null) return@update null
            val p = state.primaryItem
            val (resolvedPreset, resolvedRect) = AspectRatioSnapper.resolvePresetRect(
                preset = preset,
                effectiveWidth = p.effectiveWidth,
                effectiveHeight = p.effectiveHeight
            )
            state.copy(
                cropEnabled = true,
                cropPreset = resolvedPreset,
                cropRect = resolvedRect
            )
        }
    }

    fun enqueueStudioSession(saveToDevice: Boolean, copyToClipboard: Boolean) {
        val state = _studioSession.value ?: return
        val curSettings = settings.value
        val stripMeta = state.stripMetadata || (!saveToDevice && copyToClipboard && curSettings.stripMetadataOnClipboard)

        val convertSpec = if (state.convertEnabled || state.primaryItem.mediaKind == MediaKind.CAMERA_RAW) {
            ConvertSpec(
                targetExtension = state.targetExtension,
                videoCodec = state.videoCodec,
                audioCodec = if (state.muteAudio) AudioCodecChoice.MUTE else state.audioCodec
            )
        } else {
            null
        }

        val targetBytes = state.targetMbText.toDoubleOrNull()?.let { mb ->
            if (mb > 0.0) (mb * 1024.0 * 1024.0).roundToLong() else null
        }

        val compressSpec = if (state.compressEnabled) {
            CompressSpec(
                mode = state.compressMode,
                videoCrf = state.videoCrf,
                imageQuality = state.imageQuality,
                targetBytes = if (state.compressMode == CompressMode.TARGET_BYTES) targetBytes else null,
                maxDimensionPx = state.maxDimensionPx,
                rawHalfSizeFast = state.rawHalfSizeFast
            )
        } else {
            null
        }

        val trimSpec = if (state.trimEnabled && state.primaryItem.isTimeBased) {
            TrimSpec(
                startMs = state.trimStartMs,
                endMs = state.trimEndMs.takeIf { it > state.trimStartMs && it < state.primaryItem.durationMs }
                    ?: if (state.trimEndMs > state.trimStartMs) state.trimEndMs else null,
                fastKeyframeCopy = state.fastKeyframeCopy,
                fadeInOut = state.fadeInOut,
                muteAudio = state.muteAudio
            )
        } else {
            null
        }

        val cropSpec = if (state.cropEnabled && state.primaryItem.isVisual) {
            CropSpec(
                preset = state.cropPreset,
                rect = state.cropRect
            )
        } else {
            null
        }

        val delivery = DeliverySpec(
            saveToDevice = saveToDevice,
            copyToClipboard = copyToClipboard,
            stripMetadata = stripMeta,
            filenameSuffix = curSettings.filenameSuffix
        )

        val batchPairs = state.items.map { probe ->
            probe to PipelineSpec(
                convert = convertSpec,
                compress = compressSpec,
                trim = trimSpec,
                crop = cropSpec,
                delivery = delivery
            )
        }

        viewModelScope.launch {
            val ids = orchestrator.enqueueLocalBatch(batchPairs)
            if (copyToClipboard && !state.isBatch) {
                // Keep inline quick-progress open so user sees instant "Ready to Paste!"
                _studioSession.update { it?.copy(inlineWatchJobIds = ids) }
            } else {
                closeStudioSession()
                _selectedTab.value = MainTab.QUEUE
                showBanner("Queued ${batchPairs.size} job${if (batchPairs.size > 1) "s" else ""}")
            }
        }
    }

    fun moveInlineStudioToBackground() {
        closeStudioSession()
        _selectedTab.value = MainTab.QUEUE
    }

    fun copyCompletedJobToClipboard(jobId: String) {
        viewModelScope.launch {
            val ok = orchestrator.copyCompletedJobToClipboard(jobId)
            showBanner(if (ok) "Copied to clipboard — ready to paste!" else "Output file no longer in cache")
        }
    }

    fun buildShareIntentForJob(job: ForgeJobEntity): Intent? {
        val uriStr = job.outputUriString
        val path = job.outputPath
        val shareUri = when {
            path != null && File(path).exists() -> FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                File(path)
            )
            !uriStr.isNullOrBlank() -> Uri.parse(uriStr)
            else -> return null
        }
        val mime = job.outputMimeType ?: "*/*"
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun retryJob(jobId: String) {
        viewModelScope.launch { orchestrator.retryJob(jobId) }
    }

    fun cancelJob(jobId: String) {
        orchestrator.cancelJob(jobId)
    }

    fun cancelAllJobs() {
        orchestrator.cancelAll()
    }

    fun clearFinishedJobs() {
        viewModelScope.launch {
            orchestrator.clearFinishedJobs()
            refreshCacheSize()
        }
    }

    fun updateSettings(transform: (ForgeSettings) -> ForgeSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    fun updateYtDlp(nightly: Boolean) {
        if (_isUpdatingYtDlp.value) return
        viewModelScope.launch {
            _isUpdatingYtDlp.value = true
            runCatching {
                binaryManager.updateYtDlp(nightly)
            }.onSuccess { status ->
                _ytDlpVersion.value = binaryManager.getYtDlpVersionName()
                showBanner("yt-dlp update: ${status?.name ?: "Complete"}")
            }.onFailure { err ->
                showBanner("yt-dlp update failed: ${err.message}")
            }
            _isUpdatingYtDlp.value = false
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheSizeBytes.value = orchestrator.totalCacheBytes()
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = orchestrator.clearAllCaches()
            _cacheSizeBytes.value = orchestrator.totalCacheBytes()
            val mb = freed / (1024.0 * 1024.0)
            showBanner(String.format("Cleared %.1f MB of staging & export cache", mb))
        }
    }
}
