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
import com.fissal.forge.domain.logic.YtDlpJsonParser
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressSpec
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.CropRectNormalized
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.DeliverySpec
import com.fissal.forge.domain.model.DetectedLinkKind
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.LinkItemSettings
import com.fissal.forge.domain.model.LinkResolveState
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.domain.model.ResolvedUrlMetadata
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.pipeline.OrchestratorMetrics
import com.fissal.forge.update.AppUpdateManager
import com.fissal.forge.update.AppUpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    val cropEnabled: Boolean = false,
    val cropPreset: AspectPreset = AspectPreset.AUTO_CLOSEST,
    val cropRect: CropRectNormalized = CropRectNormalized(),
    val stripMetadata: Boolean = false
)

data class StudioSessionState(
    val items: List<MediaProbeResult>,
    val itemConfigs: List<StudioItemConfig>,
    val selectedItemIndex: Int = 0,
    val previewBitmap: Bitmap? = null,
    val isLoadingPreview: Boolean = true,
    val cameFromShareIntent: Boolean = false,
    val activeTab: StudioToolTab = StudioToolTab.CONVERT,
    val inlineWatchJobIds: List<String> = emptyList()
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

class ForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val app: ForgeApplication = application as ForgeApplication
    private val orchestrator = app.orchestrator
    private val settingsRepo = app.settingsRepository
    private val probeEngine = app.probeEngine
    private val imageRawEngine = app.imageRawEngine
    private val ytDlpEngine = app.ytDlpEngine
    private val binaryManager = app.binaryManager
    private val appUpdateManager = app.appUpdateManager

    val settings: StateFlow<ForgeSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ForgeSettings())

    val jobs: StateFlow<List<ForgeJobEntity>> = orchestrator.jobsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val metrics: StateFlow<OrchestratorMetrics> = orchestrator.metrics

    val appUpdateState: StateFlow<AppUpdateState> = appUpdateManager.state

    init {
        viewModelScope.launch {
            val initial = settingsRepo.settingsFlow.first()
            if (initial.autoCheckAppUpdates) {
                appUpdateManager.checkForUpdates(
                    autoDownload = initial.autoDownloadAppUpdates,
                    userInitiated = false
                )
            }
        }
    }

    private val _selectedTab = MutableStateFlow(MainTab.FORGE)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _urlBatchText = MutableStateFlow("")
    val urlBatchText: StateFlow<String> = _urlBatchText.asStateFlow()

    private val _parsedUrls = MutableStateFlow<List<ParsedUrlItem>>(emptyList())
    val parsedUrls: StateFlow<List<ParsedUrlItem>> = _parsedUrls.asStateFlow()

    private val _resolvedLinks = MutableStateFlow<List<ResolvedLinkChipItem>>(emptyList())
    val resolvedLinks: StateFlow<List<ResolvedLinkChipItem>> = _resolvedLinks.asStateFlow()

    private val metadataCache = ConcurrentHashMap<String, ResolvedUrlMetadata>()
    private val resolveJobs = ConcurrentHashMap<String, Job>()
    private val resolveSemaphore = Semaphore(3)

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
        val parsed = UrlBatchParser.extractUrls(newText, settings.value.stripUrlTracking)
        _parsedUrls.value = parsed
        syncResolvedLinkChips(parsed)
    }

    private fun syncResolvedLinkChips(parsed: List<ParsedUrlItem>) {
        val activeUrlSet = parsed.map { it.cleanUrl }.toSet()
        // Cancel resolvers for URLs removed from input box
        resolveJobs.keys.toList().forEach { url ->
            if (url !in activeUrlSet) {
                resolveJobs.remove(url)?.cancel()
            }
        }

        val existingMap = _resolvedLinks.value.associateBy { it.cleanUrl }
        val curSettings = settings.value

        val updatedList = parsed.map { item ->
            val existing = existingMap[item.cleanUrl]
            if (existing != null) {
                existing
            } else {
                val cached = metadataCache[item.cleanUrl]
                if (cached != null) {
                    buildChipFromMetadata(item, cached, curSettings)
                } else {
                    val hintKind = YtDlpJsonParser.inferContentMediaHint(item.cleanUrl)
                    val initialMode = if (hintKind == DetectedLinkKind.AUDIO) {
                        DownloadMode.AUDIO_ONLY
                    } else {
                        curSettings.defaultDownloadMode
                    }
                    val explicitPlaylist = item.cleanUrl.contains("/playlist", ignoreCase = true) ||
                        item.cleanUrl.contains("/sets/", ignoreCase = true) ||
                        item.cleanUrl.contains("/album/", ignoreCase = true)
                    ResolvedLinkChipItem(
                        cleanUrl = item.cleanUrl,
                        host = item.host,
                        isPlaylistLikeUrl = item.isPlaylistLike,
                        resolveState = LinkResolveState.RESOLVING,
                        detectedKind = if (item.isPlaylistLike) DetectedLinkKind.PLAYLIST else hintKind,
                        contentMediaKind = hintKind,
                        settings = LinkItemSettings(
                            mode = initialMode,
                            maxHeight = curSettings.defaultMaxVideoHeight.takeIf { it > 0 },
                            audioFormat = curSettings.defaultAudioFormat,
                            playlistSingleOnly = if (explicitPlaylist) false else curSettings.playlistSingleOnly,
                            embedMetadata = curSettings.embedDownloadMetadata
                        )
                    ).also { launchMetadataResolve(item) }
                }
            }
        }
        _resolvedLinks.value = updatedList
    }

    private fun launchMetadataResolve(item: ParsedUrlItem) {
        if (resolveJobs.containsKey(item.cleanUrl)) return
        val job = viewModelScope.launch {
            try {
                val meta = resolveSemaphore.withPermit {
                    ytDlpEngine.resolveMetadata(item.cleanUrl, item.cleanUrl.hashCode().toString())
                }
                metadataCache[item.cleanUrl] = meta
                _resolvedLinks.update { list ->
                    list.map { chip ->
                        if (chip.cleanUrl == item.cleanUrl) {
                            applyMetadataToExistingChip(chip, meta)
                        } else {
                            chip
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Removed from input
            } catch (err: Throwable) {
                _resolvedLinks.update { list ->
                    list.map { chip ->
                        if (chip.cleanUrl == item.cleanUrl) {
                            chip.copy(
                                resolveState = LinkResolveState.FAILED,
                                errorHint = err.message?.lineSequence()?.firstOrNull()?.take(80) ?: "Preview unavailable (download still works)"
                            )
                        } else {
                            chip
                        }
                    }
                }
            } finally {
                resolveJobs.remove(item.cleanUrl)
            }
        }
        resolveJobs[item.cleanUrl] = job
    }

    private fun buildChipFromMetadata(
        item: ParsedUrlItem,
        meta: ResolvedUrlMetadata,
        curSettings: ForgeSettings
    ): ResolvedLinkChipItem {
        val smartMode = if (!item.isPlaylistLike && meta.contentMediaKind == DetectedLinkKind.AUDIO) {
            DownloadMode.AUDIO_ONLY
        } else if (meta.detectedKind == DetectedLinkKind.PLAYLIST && meta.contentMediaKind == DetectedLinkKind.AUDIO) {
            DownloadMode.AUDIO_ONLY
        } else {
            curSettings.defaultDownloadMode
        }
        val autoFullPlaylist = meta.tracks.size > 1
        return ResolvedLinkChipItem(
            cleanUrl = item.cleanUrl,
            host = meta.host.ifBlank { item.host },
            isPlaylistLikeUrl = item.isPlaylistLike,
            resolveState = LinkResolveState.RESOLVED,
            title = meta.title,
            uploader = meta.uploader,
            thumbnailUrl = meta.thumbnailUrl,
            detectedKind = meta.detectedKind,
            contentMediaKind = meta.contentMediaKind,
            durationSec = meta.durationSec,
            approxBytes = meta.approxBytes,
            tracks = meta.tracks,
            settings = LinkItemSettings(
                mode = smartMode,
                maxHeight = curSettings.defaultMaxVideoHeight.takeIf { it > 0 },
                audioFormat = curSettings.defaultAudioFormat,
                playlistSingleOnly = if (autoFullPlaylist) false else curSettings.playlistSingleOnly,
                embedMetadata = curSettings.embedDownloadMetadata
            ),
            isExpanded = meta.tracks.size > 1
        )
    }

    private fun applyMetadataToExistingChip(
        chip: ResolvedLinkChipItem,
        meta: ResolvedUrlMetadata
    ): ResolvedLinkChipItem {
        val updatedSettings = if (chip.userCustomized) {
            chip.settings
        } else {
            val smartMode = if (meta.contentMediaKind == DetectedLinkKind.AUDIO) {
                DownloadMode.AUDIO_ONLY
            } else {
                chip.settings.mode
            }
            chip.settings.copy(
                mode = smartMode,
                playlistSingleOnly = if (meta.tracks.size > 1) false else chip.settings.playlistSingleOnly
            )
        }
        return chip.copy(
            resolveState = LinkResolveState.RESOLVED,
            title = meta.title,
            uploader = meta.uploader,
            thumbnailUrl = meta.thumbnailUrl,
            host = meta.host.ifBlank { chip.host },
            detectedKind = meta.detectedKind,
            contentMediaKind = meta.contentMediaKind,
            durationSec = meta.durationSec,
            approxBytes = meta.approxBytes,
            tracks = meta.tracks,
            settings = updatedSettings,
            isExpanded = chip.isExpanded || (!chip.userCustomized && meta.tracks.size > 1),
            errorHint = null
        )
    }

    fun toggleLinkChipExpanded(cleanUrl: String) {
        _resolvedLinks.update { list ->
            list.map { if (it.cleanUrl == cleanUrl) it.copy(isExpanded = !it.isExpanded) else it }
        }
    }

    fun retryResolveLink(cleanUrl: String) {
        val parsed = _parsedUrls.value.firstOrNull { it.cleanUrl == cleanUrl } ?: return
        resolveJobs.remove(cleanUrl)?.cancel()
        _resolvedLinks.update { list ->
            list.map { if (it.cleanUrl == cleanUrl) it.copy(resolveState = LinkResolveState.RESOLVING, errorHint = null) else it }
        }
        launchMetadataResolve(parsed)
    }

    fun removeResolvedLink(cleanUrl: String) {
        resolveJobs.remove(cleanUrl)?.cancel()
        val remaining = _resolvedLinks.value.filterNot { it.cleanUrl == cleanUrl }
        _resolvedLinks.value = remaining
        val strippedText = remaining.joinToString("\n") { it.cleanUrl }
        _urlBatchText.value = strippedText
        _parsedUrls.value = _parsedUrls.value.filterNot { it.cleanUrl == cleanUrl }
    }

    fun updateLinkChipSettings(cleanUrl: String, transform: (LinkItemSettings) -> LinkItemSettings) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    chip.copy(
                        settings = transform(chip.settings).copy(scopeBadge = "Custom"),
                        userCustomized = true
                    )
                } else {
                    chip
                }
            }
        }
    }

    fun togglePlaylistTrack(cleanUrl: String, trackIndex: Int) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    val nextTracks = chip.tracks.map { tr ->
                        if (tr.index == trackIndex) tr.copy(selected = !tr.selected) else tr
                    }
                    chip.copy(tracks = nextTracks, userCustomized = true)
                } else {
                    chip
                }
            }
        }
    }

    fun setAllPlaylistTracks(cleanUrl: String, selected: Boolean) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    chip.copy(tracks = chip.tracks.map { it.copy(selected = selected) }, userCustomized = true)
                } else {
                    chip
                }
            }
        }
    }

    fun applySettingsToSameType(sourceUrl: String) {
        val source = _resolvedLinks.value.firstOrNull { it.cleanUrl == sourceUrl } ?: return
        val matchKind = source.contentMediaKind
        val badge = "All ${matchKind.label}"
        var affected = 0
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.contentMediaKind == matchKind) {
                    affected++
                    chip.copy(
                        settings = source.settings.copy(
                            playlistSingleOnly = chip.settings.playlistSingleOnly,
                            scopeBadge = badge
                        ),
                        userCustomized = true
                    )
                } else {
                    chip
                }
            }
        }
        showBanner("Applied settings across $affected ${matchKind.label.lowercase()} link(s)")
    }

    fun applySettingsToSameSite(sourceUrl: String) {
        val source = _resolvedLinks.value.firstOrNull { it.cleanUrl == sourceUrl } ?: return
        val host = source.host
        if (host.isBlank()) return
        var affected = 0
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.host.equals(host, ignoreCase = true)) {
                    affected++
                    chip.copy(
                        settings = source.settings.copy(
                            playlistSingleOnly = chip.settings.playlistSingleOnly,
                            scopeBadge = host
                        ),
                        userCustomized = true
                    )
                } else {
                    chip
                }
            }
        }
        showBanner("Applied settings across $affected link(s) on $host")
    }

    fun applySettingsToAllLinks(sourceUrl: String) {
        val source = _resolvedLinks.value.firstOrNull { it.cleanUrl == sourceUrl } ?: return
        _resolvedLinks.update { list ->
            list.map { chip ->
                chip.copy(
                    settings = source.settings.copy(
                        playlistSingleOnly = chip.settings.playlistSingleOnly,
                        scopeBadge = "All Links"
                    ),
                    userCustomized = true
                )
            }
        }
        showBanner("Applied settings to all ${_resolvedLinks.value.size} link(s)")
    }

    fun applyQuickBatchPreset(transform: (LinkItemSettings) -> LinkItemSettings) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                chip.copy(settings = transform(chip.settings))
            }
        }
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

    fun queueResolvedBatch(
        copyToClipboard: Boolean,
        saveToDevice: Boolean
    ) {
        val chips = _resolvedLinks.value
        if (chips.isEmpty()) {
            showBanner("Paste at least one valid URL first")
            return
        }
        val curSettings = settings.value
        viewModelScope.launch {
            val count = orchestrator.enqueueResolvedLinks(chips, saveToDevice, copyToClipboard, curSettings)
            resolveJobs.values.forEach { it.cancel() }
            resolveJobs.clear()
            _urlBatchText.value = ""
            _parsedUrls.value = emptyList()
            _resolvedLinks.value = emptyList()
            _selectedTab.value = MainTab.QUEUE
            showBanner("Queued $count download${if (count == 1) "" else "s"}")
        }
    }

    fun openStudioForUris(uris: List<Uri>, fromShareIntent: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val probed = withContext(Dispatchers.IO) {
                uris.map { probeEngine.probeUri(it) }
            }
            val cur = settings.value
            val configs = probed.map { item -> buildInitialStudioConfig(item, cur) }
            val primary = probed.first()
            val defaultTab = when (primary.mediaKind) {
                MediaKind.CAMERA_RAW -> StudioToolTab.CONVERT
                MediaKind.IMAGE, MediaKind.VIDEO -> StudioToolTab.COMPRESS
                MediaKind.AUDIO -> StudioToolTab.CONVERT
                else -> StudioToolTab.CONVERT
            }

            _studioSession.value = StudioSessionState(
                items = probed,
                itemConfigs = configs,
                selectedItemIndex = 0,
                previewBitmap = null,
                isLoadingPreview = true,
                cameFromShareIntent = fromShareIntent,
                activeTab = defaultTab
            )

            loadPreviewForSelectedItem(0)
        }
    }

    private fun buildInitialStudioConfig(item: MediaProbeResult, cur: ForgeSettings): StudioItemConfig {
        val defaultExt = when (item.mediaKind) {
            MediaKind.CAMERA_RAW -> cur.defaultImageFormat
            MediaKind.IMAGE -> item.extension.ifBlank { cur.defaultImageFormat }
            MediaKind.VIDEO -> cur.defaultVideoExt
            MediaKind.AUDIO -> cur.defaultAudioFormat
            else -> item.extension.ifBlank { "mp4" }
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
        return StudioItemConfig(
            convertEnabled = item.mediaKind == MediaKind.CAMERA_RAW,
            targetExtension = defaultExt,
            videoCodec = cur.defaultVideoCodec,
            audioCodec = if (defaultExt == "mp3") AudioCodecChoice.MP3 else AudioCodecChoice.AAC,
            compressEnabled = false,
            compressMode = CompressMode.TARGET_BYTES,
            compressionTier = tier,
            videoCrf = cur.defaultVideoCrf,
            imageQuality = cur.defaultImageQuality,
            targetMbText = cur.defaultTargetSizeMb.toString(),
            rawHalfSizeFast = cur.rawHalfSizeFast,
            trimEnabled = false,
            trimStartMs = 0L,
            trimEndMs = item.durationMs,
            cropEnabled = false,
            cropPreset = resolvedPreset,
            cropRect = resolvedRect,
            stripMetadata = cur.stripMetadataOnSave
        )
    }

    private fun loadPreviewForSelectedItem(index: Int) {
        val state = _studioSession.value ?: return
        val target = state.items.getOrNull(index) ?: return
        val cur = settings.value
        viewModelScope.launch {
            _studioSession.update { curState ->
                if (curState == null) null else curState.copy(isLoadingPreview = true)
            }
            val preview = imageRawEngine.loadEditorPreviewBitmap(Uri.parse(target.uriString), target)
            _studioSession.update { latest ->
                if (latest == null || latest.selectedItemIndex != index) {
                    preview?.let { if (!it.isRecycled) it.recycle() }
                    return@update latest
                }
                val oldPreview = latest.previewBitmap
                if (oldPreview != null && oldPreview !== preview && !oldPreview.isRecycled) {
                    oldPreview.recycle()
                }
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
        }
    }

    fun selectStudioItem(index: Int) {
        val cur = _studioSession.value ?: return
        if (index !in cur.items.indices || index == cur.selectedItemIndex) return
        _studioSession.update { it?.copy(selectedItemIndex = index) }
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
        showBanner("Applied settings to $count ${sourceProbe.mediaKind.name.lowercase()} file(s)")
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
        showBanner("Applied settings to all ${state.items.size} files")
    }

    fun closeStudioSession() {
        _studioSession.update { state ->
            state?.previewBitmap?.let { if (!it.isRecycled) it.recycle() }
            null
        }
    }

    fun updateStudio(transform: (StudioItemConfig) -> StudioItemConfig) {
        _studioSession.update { state ->
            state?.updateActiveConfig(transform)
        }
    }

    fun updateStudioTab(tab: StudioToolTab) {
        _studioSession.update { state ->
            if (state == null) null
            else state.copy(activeTab = tab).updateActiveConfig { cfg ->
                when (tab) {
                    StudioToolTab.CONVERT -> cfg.copy(convertEnabled = true)
                    StudioToolTab.COMPRESS -> cfg.copy(compressEnabled = true)
                    StudioToolTab.TRIM -> cfg.copy(trimEnabled = true)
                    StudioToolTab.CROP -> cfg.copy(cropEnabled = true)
                }
            }
        }
    }

    fun clearInlineWatchJobs() {
        _studioSession.update { it?.copy(inlineWatchJobIds = emptyList()) }
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
            state.updateActiveConfig { cfg ->
                cfg.copy(
                    cropEnabled = true,
                    cropPreset = resolvedPreset,
                    cropRect = resolvedRect
                )
            }
        }
    }

    fun enqueueStudioSession(saveToDevice: Boolean, copyToClipboard: Boolean) {
        val state = _studioSession.value ?: return
        val curSettings = settings.value

        val batchPairs = state.items.mapIndexed { idx, probe ->
            val cfg = state.itemConfigs[idx]
            val stripMeta = cfg.stripMetadata || (!saveToDevice && copyToClipboard && curSettings.stripMetadataOnClipboard)

            val convertSpec = if (cfg.convertEnabled || probe.mediaKind == MediaKind.CAMERA_RAW ||
                (cfg.compressEnabled && cfg.compressMode == CompressMode.TARGET_BYTES && probe.mediaKind == MediaKind.VIDEO)
            ) {
                val chosenVideo = when {
                    cfg.convertEnabled -> cfg.videoCodec
                    cfg.compressEnabled && cfg.compressMode == CompressMode.TARGET_BYTES -> when (cfg.compressionTier) {
                        CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                        CompressionTier.TWO_PASS_HEVC -> VideoCodecChoice.HEVC
                        CompressionTier.COMPATIBLE_H264 -> VideoCodecChoice.H264
                    }
                    else -> cfg.videoCodec
                }
                val chosenAudio = when {
                    cfg.muteAudio -> AudioCodecChoice.MUTE
                    cfg.convertEnabled -> cfg.audioCodec
                    cfg.compressEnabled && cfg.compressMode == CompressMode.TARGET_BYTES &&
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

            val compressSpec = if (cfg.compressEnabled) {
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

            val trimSpec = if (cfg.trimEnabled && probe.isTimeBased) {
                TrimSpec(
                    startMs = cfg.trimStartMs,
                    endMs = cfg.trimEndMs.takeIf { it > cfg.trimStartMs && it < probe.durationMs }
                        ?: if (cfg.trimEndMs > cfg.trimStartMs) cfg.trimEndMs else null,
                    fastKeyframeCopy = cfg.fastKeyframeCopy,
                    fadeInOut = cfg.fadeInOut,
                    muteAudio = cfg.muteAudio
                )
            } else {
                null
            }

            val cropSpec = if (cfg.cropEnabled && probe.isVisual) {
                CropSpec(preset = cfg.cropPreset, rect = cfg.cropRect)
            } else {
                null
            }

            probe to PipelineSpec(
                convert = convertSpec,
                compress = compressSpec,
                trim = trimSpec,
                crop = cropSpec,
                delivery = DeliverySpec(
                    saveToDevice = saveToDevice,
                    copyToClipboard = copyToClipboard,
                    stripMetadata = stripMeta,
                    filenameSuffix = curSettings.filenameSuffix
                )
            )
        }

        viewModelScope.launch {
            val ids = orchestrator.enqueueLocalBatch(batchPairs)
            if (copyToClipboard && !state.isBatch) {
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

    fun removeJob(jobId: String) {
        viewModelScope.launch { orchestrator.removeJob(jobId) }
    }

    fun cancelPlaylistGroup(groupId: String) {
        orchestrator.cancelPlaylistGroup(groupId)
    }

    fun removePlaylistGroup(groupId: String) {
        viewModelScope.launch {
            orchestrator.removePlaylistGroup(groupId)
            refreshCacheSize()
            showBanner("Removed playlist from queue")
        }
    }

    fun retryPlaylistGroup(groupId: String) {
        viewModelScope.launch { orchestrator.retryPlaylistGroup(groupId) }
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
            val freedUpdates = appUpdateManager.clearCachedApks()
            val freed = freedUpdates + orchestrator.clearAllCaches()
            _cacheSizeBytes.value = orchestrator.totalCacheBytes()
            val mb = freed / (1024.0 * 1024.0)
            showBanner(String.format("Cleared %.1f MB of staging & export cache", mb))
        }
    }

    fun checkForAppUpdates() {
        appUpdateManager.checkForUpdates(
            autoDownload = settings.value.autoDownloadAppUpdates,
            userInitiated = true
        )
    }

    fun startAppUpdate(autoInstallAfter: Boolean = true) {
        appUpdateManager.downloadUpdate(autoInstallAfter)
    }

    fun cancelAppUpdate() {
        appUpdateManager.cancelDownload()
    }

    fun prepareAppUpdateInstall(): AppUpdateManager.InstallAction {
        return appUpdateManager.prepareInstallIntent()
    }

    fun consumeAutoInstallFlag() {
        appUpdateManager.consumeAutoInstallFlag()
    }

    fun dismissUpdateBanner(tagName: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(dismissedUpdateTag = tagName) }
        }
    }
}
