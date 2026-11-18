package com.fissal.forge.ui

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebSettings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fissal.forge.ForgeApplication
import com.fissal.forge.auth.AuthRequiredException
import com.fissal.forge.auth.ConnectedServiceSummary
import com.fissal.forge.auth.CredentialRedactor
import com.fissal.forge.auth.ServiceCatalog
import com.fissal.forge.auth.ServiceDescriptor
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.local.FormatPresetEntity
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.domain.logic.AspectRatioSnapper
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.ParsedUrlItem
import com.fissal.forge.domain.logic.SampleCalibrationRatio
import com.fissal.forge.domain.logic.UrlBatchParser
import com.fissal.forge.domain.logic.ViewportSyncMath
import com.fissal.forge.domain.logic.YtDlpJsonParser
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.ChapterTrackItem
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
import com.fissal.forge.domain.model.RenderedSampleResult
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.domain.model.ResolvedUrlMetadata
import com.fissal.forge.domain.model.SampleCandidateSpec
import com.fissal.forge.domain.model.SampleEntryDraft
import com.fissal.forge.domain.model.SampleRenderStatus
import com.fissal.forge.domain.model.SampleVoteState
import com.fissal.forge.domain.model.SilenceSkipSpec
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.engine.BaselineSampleOutput
import com.fissal.forge.engine.SampleFrameEngine
import com.fissal.forge.pipeline.OrchestratorMetrics
import com.fissal.forge.update.AppUpdateManager
import com.fissal.forge.update.AppUpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
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

class ForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val app: ForgeApplication = application as ForgeApplication
    private val orchestrator = app.orchestrator
    private val presetDao = app.database.presetDao()
    private val presetJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val settingsRepo = app.settingsRepository
    private val siteAuthVault = app.siteAuthVault
    private val probeEngine = app.probeEngine
    private val imageRawEngine = app.imageRawEngine
    private val ffmpegEngine = app.ffmpegEngine
    private val sampleFrameEngine = app.sampleFrameEngine
    private val ytDlpEngine = app.ytDlpEngine
    private val binaryManager = app.binaryManager
    private val appUpdateManager = app.appUpdateManager

    val deviceCapabilities: DeviceCapabilityReport = app.deviceCapabilities

    val formatPresets: StateFlow<List<FormatPresetEntity>> = presetDao.observeAllPresets()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<ForgeSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ForgeSettings())

    val siteConnections: StateFlow<List<ConnectedServiceSummary>> = siteAuthVault.connectionsFlow

    private val _checkingServiceId = MutableStateFlow<String?>(null)
    val checkingServiceId: StateFlow<String?> = _checkingServiceId.asStateFlow()

    private val _authPrompt = MutableStateFlow<AuthPromptState?>(null)
    val authPrompt: StateFlow<AuthPromptState?> = _authPrompt.asStateFlow()

    val jobs: StateFlow<List<ForgeJobEntity>> = orchestrator.jobsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val metrics: StateFlow<OrchestratorMetrics> = orchestrator.metrics

    val appUpdateState: StateFlow<AppUpdateState> = appUpdateManager.state

    private var studioPreviewJob: Job? = null
    private var samplePreviewJob: Job? = null
    private val sampleVideoMutex = Mutex()
    private val samplePreviewDir: File
        get() = File(app.cacheDir, "sample_preview")
    private var cachedSampleVideoUri: String? = null
    private var cachedSampleVideoFile: File? = null

    private val _sampleLabSession = MutableStateFlow<SampleLabSessionState?>(null)
    val sampleLabSession: StateFlow<SampleLabSessionState?> = _sampleLabSession.asStateFlow()
    private var sampleLabJob: Job? = null
    private val sampleLabMutex = Mutex()
    private var activeSampleBaseline: BaselineSampleOutput? = null
    private var activeSampleBaselineSessionId: String? = null
    private var activeSampleBaselineTimestampMs: Long = -1L
    private var activeSampleBaselineCrop: CropSpec? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            sampleVideoMutex.withLock {
                runCatching { samplePreviewDir.deleteRecursively() }
            }
            sampleLabMutex.withLock {
                runCatching { sampleFrameEngine.sweepAllCache() }
            }
        }
        viewModelScope.launch {
            orchestrator.authRequiredEvents.collect { ev ->
                _authPrompt.value = AuthPromptState(
                    service = ev.exception.service ?: ServiceCatalog.matchUrl(ev.url, siteAuthVault.connectedCustomDomains()),
                    targetUrl = ev.url,
                    reason = ev.exception.userFacingReason,
                    isPaywallOrSubscription = ev.exception.isPaywallOrSubscription,
                    retryCleanUrl = null,
                    retryJobId = ev.jobId
                )
            }
        }
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

    override fun onCleared() {
        super.onCleared()
        studioPreviewJob?.cancel()
        studioPreviewJob = null
        samplePreviewJob?.cancel()
        samplePreviewJob = null
        cachedSampleVideoUri = null
        cachedSampleVideoFile = null
        val previewDir = samplePreviewDir
        val activeLabJob = sampleLabJob
        sampleLabJob = null
        activeLabJob?.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { activeLabJob?.cancelAndJoin() }
            sampleLabMutex.withLock {
                val bmp = activeSampleBaseline?.imageSourceBitmap
                if (bmp != null && !bmp.isRecycled) {
                    bmp.recycle()
                }
                activeSampleBaseline = null
                activeSampleBaselineSessionId = null
                activeSampleBaselineTimestampMs = -1L
                activeSampleBaselineCrop = null
                runCatching { sampleFrameEngine.sweepAllCache() }
            }
            sampleVideoMutex.withLock {
                runCatching { previewDir.deleteRecursively() }
            }
        }
    }

    private val _selectedTab = MutableStateFlow(MainTab.FORGE)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _isDownloadWorkspaceOpen = MutableStateFlow(false)
    val isDownloadWorkspaceOpen: StateFlow<Boolean> = _isDownloadWorkspaceOpen.asStateFlow()

    private val _urlBatchText = MutableStateFlow("")
    val urlBatchText: StateFlow<String> = _urlBatchText.asStateFlow()

    private val _parsedUrls = MutableStateFlow<List<ParsedUrlItem>>(emptyList())
    val parsedUrls: StateFlow<List<ParsedUrlItem>> = _parsedUrls.asStateFlow()

    private val _resolvedLinks = MutableStateFlow<List<ResolvedLinkChipItem>>(emptyList())
    val resolvedLinks: StateFlow<List<ResolvedLinkChipItem>> = _resolvedLinks.asStateFlow()

    private val metadataCache = ConcurrentHashMap<String, ResolvedUrlMetadata>()
    private val resolveJobs = ConcurrentHashMap<String, Job>()
    private val resolveSemaphore = Semaphore(3)
    private var urlSyncJob: Job? = null

    private val _studioSession = MutableStateFlow<StudioSessionState?>(null)
    val studioSession: StateFlow<StudioSessionState?> = _studioSession.asStateFlow()

    val ytDlpVersion: StateFlow<String> = binaryManager.ytDlpVersionFlow
    val isUpdatingYtDlp: StateFlow<Boolean> = binaryManager.isUpdatingYtDlpFlow

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

    fun openDownloadWorkspace() {
        _isDownloadWorkspaceOpen.value = true
    }

    fun closeDownloadWorkspace() {
        _isDownloadWorkspaceOpen.value = false
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun showBanner(message: String) {
        _statusBanner.value = message
    }

    fun updateUrlBatchText(newText: String, immediate: Boolean = false) {
        _urlBatchText.value = newText
        urlSyncJob?.cancel()
        val shouldProcessImmediately = immediate ||
            newText.isBlank() ||
            newText.endsWith('\n') ||
            newText.endsWith(' ')
        if (shouldProcessImmediately) {
            processUrlInputText(newText)
        } else {
            urlSyncJob = viewModelScope.launch {
                delay(350L)
                processUrlInputText(newText)
            }
        }
    }

    private fun processUrlInputText(rawText: String) {
        val parsed = UrlBatchParser.extractUrls(rawText, settings.value.stripUrlTracking)
        _parsedUrls.value = parsed
        syncResolvedLinkChips(parsed)
    }

    private fun syncResolvedLinkChips(parsed: List<ParsedUrlItem>) {
        val activeUrlSet = parsed.map { it.cleanUrl }.toSet()
        // Cancel resolvers for un-committed URLs removed before lookup completed
        resolveJobs.keys.toList().forEach { url ->
            if (url !in activeUrlSet) {
                resolveJobs.remove(url)?.cancel()
            }
        }

        val curSettings = settings.value
        val keptExisting = _resolvedLinks.value.filter { chip ->
            chip.isCommitted ||
                chip.resolveState == LinkResolveState.RESOLVED ||
                !chip.authRequiredReason.isNullOrBlank() ||
                chip.cleanUrl in activeUrlSet
        }
        val existingUrlSet = keptExisting.mapTo(HashSet()) { it.cleanUrl }
        val newlyAdded = ArrayList<ResolvedLinkChipItem>()
        val cacheMatchedUrls = ArrayList<String>()

        for (item in parsed) {
            if (item.cleanUrl in existingUrlSet) continue
            val cached = metadataCache[item.cleanUrl]
            if (cached != null) {
                newlyAdded += buildChipFromMetadata(item, cached, curSettings).copy(isCommitted = true)
                cacheMatchedUrls += item.cleanUrl
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
                val matchedConnectionId = resolveConnectedSiteId(item.cleanUrl)
                newlyAdded += ResolvedLinkChipItem(
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
                        sponsorBlock = curSettings.defaultSponsorBlockCategories.isNotEmpty(),
                        embedMetadata = curSettings.embedDownloadMetadata,
                        sponsorBlockCategories = curSettings.defaultSponsorBlockCategories,
                        siteConnectionId = matchedConnectionId,
                        silenceSkip = curSettings.defaultSilenceSkip
                    )
                )
                existingUrlSet += item.cleanUrl
                launchMetadataResolve(item, matchedConnectionId)
            }
        }

        _resolvedLinks.value = keptExisting + newlyAdded
        cacheMatchedUrls.forEach { stripResolvedUrlFromInputBox(it) }
    }

    private fun stripResolvedUrlFromInputBox(cleanUrl: String) {
        val stripTracking = settings.value.stripUrlTracking
        val before = _urlBatchText.value
        val after = UrlBatchParser.removeResolvedUrlFromText(before, cleanUrl, stripTracking)
        if (after != before) {
            _urlBatchText.value = after
            _parsedUrls.value = UrlBatchParser.extractUrls(after, stripTracking)
        }
    }

    private fun launchMetadataResolve(item: ParsedUrlItem, explicitConnectionId: String? = null) {
        if (resolveJobs.containsKey(item.cleanUrl)) return
        val job = viewModelScope.launch {
            try {
                val siteConnId = explicitConnectionId
                    ?: _resolvedLinks.value.firstOrNull { it.cleanUrl == item.cleanUrl }?.settings?.siteConnectionId
                    ?: resolveConnectedSiteId(item.cleanUrl)
                val meta = resolveSemaphore.withPermit {
                    ytDlpEngine.resolveMetadata(
                        url = item.cleanUrl,
                        cancelKey = UUID.randomUUID().toString(),
                        siteConnectionId = siteConnId
                    )
                }
                metadataCache[item.cleanUrl] = meta
                _resolvedLinks.update { list ->
                    list.map { chip ->
                        if (chip.cleanUrl == item.cleanUrl) {
                            applyMetadataToExistingChip(chip, meta).copy(isCommitted = true)
                        } else {
                            chip
                        }
                    }
                }
                stripResolvedUrlFromInputBox(item.cleanUrl)
            } catch (_: CancellationException) {
                // Removed from input
            } catch (ex: AuthRequiredException) {
                _resolvedLinks.update { list ->
                    list.map { chip ->
                        if (chip.cleanUrl == item.cleanUrl) {
                            chip.copy(
                                resolveState = LinkResolveState.FAILED,
                                isCommitted = true,
                                errorHint = ex.userFacingReason,
                                authRequiredReason = ex.userFacingReason,
                                authIsPaywall = ex.isPaywallOrSubscription,
                                authServiceId = ex.service?.id
                            )
                        } else {
                            chip
                        }
                    }
                }
                stripResolvedUrlFromInputBox(item.cleanUrl)
                _authPrompt.value = AuthPromptState(
                    service = ex.service ?: ServiceCatalog.matchUrl(item.cleanUrl, siteAuthVault.connectedCustomDomains()),
                    targetUrl = item.cleanUrl,
                    reason = ex.userFacingReason,
                    isPaywallOrSubscription = ex.isPaywallOrSubscription,
                    retryCleanUrl = item.cleanUrl,
                    retryJobId = null
                )
            } catch (err: Throwable) {
                val safeErr = CredentialRedactor.sanitizeDiagnostic(
                    err.message?.lineSequence()?.firstOrNull(),
                    maxChars = 140
                ).ifBlank { "Preview unavailable (download still works)" }
                _resolvedLinks.update { list ->
                    list.map { chip ->
                        if (chip.cleanUrl == item.cleanUrl) {
                            chip.copy(
                                resolveState = LinkResolveState.FAILED,
                                errorHint = safeErr
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
            chapters = meta.chapters,
            settings = LinkItemSettings(
                mode = smartMode,
                maxHeight = curSettings.defaultMaxVideoHeight.takeIf { it > 0 },
                audioFormat = curSettings.defaultAudioFormat,
                playlistSingleOnly = if (autoFullPlaylist) false else curSettings.playlistSingleOnly,
                sponsorBlock = curSettings.defaultSponsorBlockCategories.isNotEmpty(),
                embedMetadata = curSettings.embedDownloadMetadata,
                sponsorBlockCategories = curSettings.defaultSponsorBlockCategories,
                siteConnectionId = resolveConnectedSiteId(item.cleanUrl),
                silenceSkip = curSettings.defaultSilenceSkip
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
            chapters = meta.chapters,
            settings = updatedSettings,
            isExpanded = chip.isExpanded || (!chip.userCustomized && meta.tracks.size > 1),
            errorHint = null,
            authRequiredReason = null,
            authIsPaywall = false,
            authServiceId = null
        )
    }

    fun toggleLinkChipExpanded(cleanUrl: String) {
        _resolvedLinks.update { list ->
            list.map { if (it.cleanUrl == cleanUrl) it.copy(isExpanded = !it.isExpanded) else it }
        }
    }

    fun retryResolveLink(cleanUrl: String) {
        val chip = _resolvedLinks.value.firstOrNull { it.cleanUrl == cleanUrl }
        val parsed = _parsedUrls.value.firstOrNull { it.cleanUrl == cleanUrl }
            ?: UrlBatchParser.extractUrls(cleanUrl, stripTracking = false).firstOrNull()
            ?: if (chip != null) {
                ParsedUrlItem(
                    rawUrl = cleanUrl,
                    cleanUrl = cleanUrl,
                    isPlaylistLike = chip.isPlaylistLikeUrl,
                    host = chip.host
                )
            } else {
                return
            }
        resolveJobs.remove(cleanUrl)?.cancel()
        val connId = chip?.settings?.siteConnectionId ?: resolveConnectedSiteId(cleanUrl)
        _resolvedLinks.update { list ->
            list.map {
                if (it.cleanUrl == cleanUrl) {
                    it.copy(
                        resolveState = LinkResolveState.RESOLVING,
                        errorHint = null,
                        authRequiredReason = null,
                        authIsPaywall = false,
                        authServiceId = null
                    )
                } else {
                    it
                }
            }
        }
        launchMetadataResolve(parsed, connId)
    }

    fun removeResolvedLink(cleanUrl: String) {
        resolveJobs.remove(cleanUrl)?.cancel()
        _resolvedLinks.update { list -> list.filterNot { it.cleanUrl == cleanUrl } }
        stripResolvedUrlFromInputBox(cleanUrl)
    }

    fun clearAllResolvedLinks() {
        urlSyncJob?.cancel()
        resolveJobs.values.forEach { it.cancel() }
        resolveJobs.clear()
        _urlBatchText.value = ""
        _parsedUrls.value = emptyList()
        _resolvedLinks.value = emptyList()
    }

    fun updateLinkChipSettings(cleanUrl: String, transform: (LinkItemSettings) -> LinkItemSettings) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    val nextSettings = transform(chip.settings).copy(scopeBadge = "Custom")
                    chip.copy(
                        settings = nextSettings,
                        chapters = syncChapterOverridesWithBase(chip.chapters, nextSettings),
                        userCustomized = true
                    )
                } else {
                    chip
                }
            }
        }
    }

    private fun syncChapterOverridesWithBase(
        chapters: List<ChapterTrackItem>,
        nextBase: LinkItemSettings
    ): List<ChapterTrackItem> {
        if (chapters.none { it.customSettings != null }) return chapters
        return chapters.map { ch ->
            val existing = ch.customSettings ?: return@map ch
            ch.copy(
                customSettings = nextBase.copy(
                    mode = existing.mode,
                    audioFormat = existing.audioFormat
                )
            )
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

    fun setSplitByChapters(cleanUrl: String, enabled: Boolean) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    chip.copy(
                        settings = chip.settings.copy(
                            splitByChapters = enabled,
                            scopeBadge = if (enabled) "Chapters" else chip.settings.scopeBadge?.takeUnless { it == "Chapters" }
                        ),
                        isExpanded = chip.isExpanded || enabled,
                        userCustomized = true
                    )
                } else {
                    chip
                }
            }
        }
    }

    fun toggleChapterTrack(cleanUrl: String, chapterIndex: Int) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    val nextChapters = chip.chapters.map { ch ->
                        if (ch.index == chapterIndex) ch.copy(selected = !ch.selected) else ch
                    }
                    chip.copy(chapters = nextChapters, userCustomized = true)
                } else {
                    chip
                }
            }
        }
    }

    fun setAllChapterTracks(cleanUrl: String, selected: Boolean) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    chip.copy(
                        chapters = chip.chapters.map { it.copy(selected = selected) },
                        userCustomized = true
                    )
                } else {
                    chip
                }
            }
        }
    }

    fun setBulkChapterMode(cleanUrl: String, mode: DownloadMode, audioFormat: String? = null) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    val nextFormat = audioFormat ?: chip.settings.audioFormat
                    val nextSettings = chip.settings.copy(
                        mode = mode,
                        audioFormat = nextFormat,
                        splitByChapters = true
                    )
                    val clearedChapterOverrides = chip.chapters.map { ch ->
                        val existingCustom = ch.customSettings
                        if (existingCustom == null) {
                            ch
                        } else {
                            ch.copy(
                                customSettings = existingCustom.copy(
                                    mode = mode,
                                    audioFormat = nextFormat
                                )
                            )
                        }
                    }
                    chip.copy(
                        settings = nextSettings,
                        chapters = clearedChapterOverrides,
                        userCustomized = true
                    )
                } else {
                    chip
                }
            }
        }
    }

    fun toggleChapterTrackModeOverride(cleanUrl: String, chapterIndex: Int) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    val nextChapters = chip.chapters.map { ch ->
                        if (ch.index == chapterIndex) {
                            val base = ch.customSettings ?: chip.settings
                            val nextMode = if (base.mode == DownloadMode.AUDIO_ONLY) {
                                if (chip.settings.mode == DownloadMode.VIDEO_BEST_MKV) {
                                    DownloadMode.VIDEO_BEST_MKV
                                } else {
                                    DownloadMode.VIDEO_MP4
                                }
                            } else {
                                DownloadMode.AUDIO_ONLY
                            }
                            ch.copy(customSettings = base.copy(mode = nextMode))
                        } else {
                            ch
                        }
                    }
                    chip.copy(chapters = nextChapters, userCustomized = true)
                } else {
                    chip
                }
            }
        }
    }

    fun setChapterTrackAudioFormat(cleanUrl: String, chapterIndex: Int, audioFormat: String) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                if (chip.cleanUrl == cleanUrl) {
                    val nextChapters = chip.chapters.map { ch ->
                        if (ch.index == chapterIndex) {
                            val base = ch.customSettings ?: chip.settings
                            ch.copy(
                                customSettings = base.copy(
                                    mode = DownloadMode.AUDIO_ONLY,
                                    audioFormat = audioFormat
                                )
                            )
                        } else {
                            ch
                        }
                    }
                    chip.copy(chapters = nextChapters, userCustomized = true)
                } else {
                    chip
                }
            }
        }
    }

    fun dismissAuthPrompt() {
        _authPrompt.value = null
    }

    fun promptSignInForChip(cleanUrl: String) {
        val chip = _resolvedLinks.value.firstOrNull { it.cleanUrl == cleanUrl } ?: return
        val service = chip.authServiceId?.let { ServiceCatalog.findById(it) }
            ?: ServiceCatalog.matchUrl(cleanUrl, siteAuthVault.connectedCustomDomains())
        _authPrompt.value = AuthPromptState(
            service = service,
            targetUrl = cleanUrl,
            reason = chip.authRequiredReason ?: chip.errorHint ?: "Sign in to access private or members-only media.",
            isPaywallOrSubscription = chip.authIsPaywall,
            retryCleanUrl = cleanUrl,
            retryJobId = null
        )
    }

    fun completeWebViewSignIn(
        service: ServiceDescriptor,
        userAgent: String,
        visitedUrls: List<String>,
        pendingRetryUrl: String? = null,
        pendingRetryJobId: String? = null
    ) {
        viewModelScope.launch {
            runCatching {
                siteAuthVault.saveWebViewSession(service, userAgent, visitedUrls)
            }.onSuccess { summary ->
                applyConnectedSessionAndRetry(summary, pendingRetryUrl, pendingRetryJobId)
                showBanner("Connected ${summary.service.displayName} securely (${summary.cookieCount} cookies)")
            }.onFailure { err ->
                showBanner(CredentialRedactor.sanitizeDiagnostic(err.message, 140).ifBlank { "Could not capture sign-in session" })
            }
        }
    }

    fun importCookiesForService(
        service: ServiceDescriptor,
        uri: Uri,
        pendingRetryUrl: String? = null,
        pendingRetryJobId: String? = null
    ) {
        viewModelScope.launch {
            val defaultUa = runCatching { WebSettings.getDefaultUserAgent(app) }.getOrDefault("")
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            val result = siteAuthVault.importCookiesTxt(service, uri, defaultUa)
            result.onSuccess { summary ->
                applyConnectedSessionAndRetry(summary, pendingRetryUrl, pendingRetryJobId)
                showBanner("Imported ${summary.cookieCount} encrypted cookies for ${summary.service.displayName}")
            }.onFailure { err ->
                showBanner(CredentialRedactor.sanitizeDiagnostic(err.message, 140).ifBlank { "Failed to import cookies.txt" })
            }
        }
    }

    fun checkSiteConnection(serviceId: String) {
        if (_checkingServiceId.value != null) return
        viewModelScope.launch {
            _checkingServiceId.value = serviceId
            try {
                val summary = siteAuthVault.checkConnection(serviceId)
                if (summary != null) {
                    showBanner("${summary.service.displayName}: ${summary.statusMessage}")
                }
            } catch (err: Throwable) {
                showBanner(CredentialRedactor.sanitizeDiagnostic(err.message, 140).ifBlank { "Connection check failed" })
            } finally {
                _checkingServiceId.value = null
            }
        }
    }

    fun removeSiteConnection(serviceId: String) {
        viewModelScope.launch {
            runCatching {
                siteAuthVault.removeConnection(serviceId)
            }.onSuccess {
                _resolvedLinks.update { list ->
                    list.map { chip ->
                        if (chip.settings.siteConnectionId == serviceId) {
                            chip.copy(settings = chip.settings.copy(siteConnectionId = null))
                        } else {
                            chip
                        }
                    }
                }
                showBanner("Disconnected & wiped local session cookies")
            }.onFailure { err ->
                showBanner(CredentialRedactor.sanitizeDiagnostic(err.message, 140).ifBlank { "Failed to remove site connection" })
            }
        }
    }

    private fun applyConnectedSessionAndRetry(
        summary: ConnectedServiceSummary,
        pendingRetryUrl: String?,
        pendingRetryJobId: String?
    ) {
        _authPrompt.value = null
        val matchedService = summary.service
        val urlsToClear = metadataCache.keys.filter { cachedUrl ->
            cachedUrl == pendingRetryUrl ||
                ServiceCatalog.matchUrl(cachedUrl, siteAuthVault.connectedCustomDomains())?.id == summary.serviceId ||
                matchedService.hostMatchers.any { matcher ->
                    val host = ServiceCatalog.extractHost(cachedUrl).orEmpty()
                    host == matcher || host.endsWith(".$matcher")
                }
        }
        urlsToClear.forEach { metadataCache.remove(it) }

        val urlsToAutoRetry = mutableSetOf<String>()
        if (!pendingRetryUrl.isNullOrBlank()) {
            urlsToAutoRetry += pendingRetryUrl
        }

        _resolvedLinks.update { list ->
            list.map { chip ->
                val hostMatches = chip.cleanUrl == pendingRetryUrl ||
                    chip.authServiceId == summary.serviceId ||
                    ServiceCatalog.matchUrl(chip.cleanUrl, siteAuthVault.connectedCustomDomains())?.id == summary.serviceId
                if (hostMatches) {
                    if (chip.resolveState == LinkResolveState.FAILED) {
                        urlsToAutoRetry += chip.cleanUrl
                    }
                    chip.copy(
                        settings = chip.settings.copy(siteConnectionId = summary.serviceId),
                        authRequiredReason = null,
                        authIsPaywall = false,
                        authServiceId = null
                    )
                } else {
                    chip
                }
            }
        }

        urlsToAutoRetry.forEach { retryUrl ->
            retryResolveLink(retryUrl)
        }
        if (!pendingRetryJobId.isNullOrBlank()) {
            val groupId = jobs.value.firstOrNull { it.id == pendingRetryJobId }?.groupId
            if (!groupId.isNullOrBlank()) {
                retryPlaylistGroup(groupId)
            } else {
                retryJob(pendingRetryJobId)
            }
        }
    }

    private fun resolveConnectedSiteId(url: String): String? {
        val matched = ServiceCatalog.matchUrl(url, siteAuthVault.connectedCustomDomains()) ?: return null
        return matched.id.takeIf { id ->
            siteConnections.value.any { it.serviceId == id }
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
                    val nextSettings = source.settings.copy(
                        playlistSingleOnly = chip.settings.playlistSingleOnly,
                        splitByChapters = chip.settings.splitByChapters,
                        siteConnectionId = chip.settings.siteConnectionId,
                        scopeBadge = badge
                    )
                    chip.copy(
                        settings = nextSettings,
                        chapters = syncChapterOverridesWithBase(chip.chapters, nextSettings),
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
                    val nextSettings = source.settings.copy(
                        playlistSingleOnly = chip.settings.playlistSingleOnly,
                        splitByChapters = chip.settings.splitByChapters,
                        siteConnectionId = chip.settings.siteConnectionId,
                        scopeBadge = host
                    )
                    chip.copy(
                        settings = nextSettings,
                        chapters = syncChapterOverridesWithBase(chip.chapters, nextSettings),
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
                val nextSettings = source.settings.copy(
                    playlistSingleOnly = chip.settings.playlistSingleOnly,
                    splitByChapters = chip.settings.splitByChapters,
                    siteConnectionId = chip.settings.siteConnectionId,
                    scopeBadge = "All Links"
                )
                chip.copy(
                    settings = nextSettings,
                    chapters = syncChapterOverridesWithBase(chip.chapters, nextSettings),
                    userCustomized = true
                )
            }
        }
        showBanner("Applied settings to all ${_resolvedLinks.value.size} link(s)")
    }

    fun applyQuickBatchPreset(transform: (LinkItemSettings) -> LinkItemSettings) {
        _resolvedLinks.update { list ->
            list.map { chip ->
                val nextSettings = transform(chip.settings)
                chip.copy(
                    settings = nextSettings,
                    chapters = syncChapterOverridesWithBase(chip.chapters, nextSettings)
                )
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
                _isDownloadWorkspaceOpen.value = true
                updateUrlBatchText(combined, immediate = true)
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
            urlSyncJob?.cancel()
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
            initializeStudioSessionForUris(uris, fromShareIntent)
        }
    }

    fun openSampleLabForUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val state = initializeStudioSessionForUris(uris, fromShareIntent = false) ?: return@launch
            val visualIdx = state.items.indexOfFirst { it.isVisual }
            if (visualIdx < 0) {
                showBanner("Sample Frame Lab supports video, photo, and Camera RAW files")
                return@launch
            }
            if (visualIdx != state.selectedItemIndex) {
                selectStudioItem(visualIdx)
            }
            openSampleLabForIndex(visualIdx, returnToHubOnClose = true)
        }
    }

    private suspend fun initializeStudioSessionForUris(
        uris: List<Uri>,
        fromShareIntent: Boolean
    ): StudioSessionState? {
        if (uris.isEmpty()) return null
        internalCloseSampleLab(allowParentStudioClose = false)
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
        studioPreviewJob = viewModelScope.launch {
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
        studioPreviewJob?.cancel()
        studioPreviewJob = null
        val activeSampleJob = samplePreviewJob
        samplePreviewJob = null
        activeSampleJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
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

        val silenceSkipSpec = if (cfg.silenceSkipEnabled && probe.isTimeBased) {
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
        launchedJob = viewModelScope.launch {
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

    // --- Emoji Format Presets (Room) ---

    fun saveFormatPreset(
        mediaKind: MediaKind,
        spec: PipelineSpec,
        emoji: String,
        name: String,
        isDefaultForKind: Boolean
    ) {
        val cleanName = name.trim().take(48)
        if (cleanName.isEmpty()) {
            showBanner("Enter a preset name first")
            return
        }
        val cleanEmoji = emoji.trim().ifBlank { "🔥" }
        val normalizedJson = spec.toNormalizedJson()
        viewModelScope.launch {
            runCatching {
                val entity = FormatPresetEntity(
                    id = UUID.randomUUID().toString(),
                    emoji = cleanEmoji,
                    name = cleanName,
                    mediaKind = mediaKind.name,
                    specJson = normalizedJson,
                    isDefaultForKind = isDefaultForKind
                )
                withContext(Dispatchers.IO) {
                    presetDao.savePresetWithDefault(entity)
                }
            }.onSuccess {
                val defaultTag = if (isDefaultForKind) " (Default)" else ""
                showBanner("Saved preset $cleanEmoji $cleanName$defaultTag")
            }.onFailure { err ->
                showBanner(err.message ?: "Could not save format preset")
            }
        }
    }

    fun toggleFormatPresetDefault(preset: FormatPresetEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (preset.isDefaultForKind) {
                    presetDao.clearDefaultForKind(preset.mediaKind)
                } else {
                    presetDao.setDefaultForKind(preset.mediaKind, preset.id)
                }
            }
            if (preset.isDefaultForKind) {
                showBanner("Cleared default flag on ${preset.emoji} ${preset.name}")
            } else {
                showBanner("${preset.emoji} ${preset.name} auto-applies to new files")
            }
        }
    }

    fun deleteFormatPreset(preset: FormatPresetEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                presetDao.deleteById(preset.id)
            }
            showBanner("Deleted preset ${preset.emoji} ${preset.name}")
        }
    }

    fun applyPresetToStudio(preset: FormatPresetEntity) {
        val item = _studioSession.value?.primaryItem ?: return
        val decoded = runCatching { presetJson.decodeFromString<PipelineSpec>(preset.specJson) }.getOrNull()
        if (decoded == null) {
            showBanner("Preset format could not be read")
            return
        }
        updateStudio { cfg -> overlayPresetOntoConfig(cfg, item, decoded) }
        showBanner("Applied ${preset.emoji} ${preset.name}")
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
        val targetExt = conv?.targetExtension?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: cfg.targetExtension
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
                String.format(java.util.Locale.US, "%.2f", mb).trimEnd('0').trimEnd('.')
            }
        } ?: cfg.targetMbText

        val hasCustomTrimRange = cfg.trimStartMs > 0L || (probe.durationMs > 0L && cfg.trimEndMs in 1L until probe.durationMs)
        val needsTrimForPresetFlags = trim != null && (trim.fastKeyframeCopy || trim.fadeInOut || trim.muteAudio)
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

    // --- Sample Frame Lab & Fullscreen Blink Comparator ---

    fun openSampleLabFromStudio() {
        val studio = _studioSession.value ?: return
        openSampleLabForIndex(studio.selectedItemIndex, returnToHubOnClose = false)
    }

    private fun openSampleLabForIndex(index: Int, returnToHubOnClose: Boolean) {
        val studio = _studioSession.value ?: return
        val item = studio.items.getOrNull(index) ?: return
        if (!item.isVisual) {
            showBanner("Sample Frame Lab supports videos, images, and Camera RAW files")
            return
        }
        val cfg = studio.itemConfigs.getOrNull(index) ?: studio.activeConfig
        val currentStudioSpec = buildPipelineSpecForStudioItem(
            probe = item,
            cfg = cfg,
            forceCompressSpec = true
        )
        val inheritedTrim = currentStudioSpec.trim
        val inheritedCrop = currentStudioSpec.crop
        val initialTsMs = if (item.mediaKind == MediaKind.VIDEO && item.durationMs > 0L) {
            val trimStart = (inheritedTrim?.startMs ?: 0L).coerceAtLeast(0L)
            val trimEnd = inheritedTrim?.endMs?.takeIf { it > trimStart } ?: item.durationMs
            ((trimStart + trimEnd) / 2L).coerceIn(0L, item.durationMs)
        } else {
            0L
        }

        val starters = SampleEntryDraft.defaultStarterDrafts(item, currentStudioSpec)
        val candidates = starters.mapIndexed { idx, draft ->
            SampleCandidateSpec(
                id = "cand_${UUID.randomUUID()}",
                indexNumber = idx + 1,
                label = draft.autoLabel(item.mediaKind),
                pipelineSpec = draft.toPipelineSpec(item, inheritedTrim, inheritedCrop, cfg.stripMetadata),
                isOriginalBaseline = false,
                voteState = SampleVoteState.NEUTRAL,
                draft = draft
            )
        }

        val sessionId = "lab_${UUID.randomUUID().toString().take(12)}"
        val previousJob = sampleLabJob
        sampleLabJob = null
        previousJob?.cancel()

        val initialResults = candidates.associate { cand ->
            cand.id to RenderedSampleResult(
                candidateId = cand.id,
                previewFilePath = null,
                status = SampleRenderStatus.IDLE
            )
        }

        val session = SampleLabSessionState(
            sessionId = sessionId,
            studioItemIndex = index,
            probe = item,
            inheritedTrim = inheritedTrim,
            inheritedCrop = inheritedCrop,
            stripMetadata = cfg.stripMetadata,
            returnToHubOnClose = returnToHubOnClose,
            timestampMs = initialTsMs,
            includeOriginalFrame = true,
            origVoteState = SampleVoteState.NEUTRAL,
            candidates = candidates,
            renderedResults = initialResults,
            referencePreviewPath = null,
            isPreparingBaseline = true,
            isRenderingSamples = true,
            baselineError = null,
            isFullscreenCompareOpen = false,
            fullscreenActiveTabId = "orig"
        )
        _sampleLabSession.value = session
        launchSampleLabRenderPipeline(
            targetSessionId = sessionId,
            requestedTsMs = initialTsMs,
            autoDodgeBlackFade = (item.mediaKind == MediaKind.VIDEO),
            candidateIdsToRender = candidates.map { it.id },
            previousJobToJoin = previousJob
        )
    }

    fun closeSampleLab() {
        internalCloseSampleLab(allowParentStudioClose = true)
    }

    fun dismissSampleLabForNavigation() {
        internalCloseSampleLab(allowParentStudioClose = false)
    }

    private fun internalCloseSampleLab(allowParentStudioClose: Boolean) {
        val current = _sampleLabSession.value
        val activeJob = sampleLabJob
        sampleLabJob = null
        activeJob?.cancel()
        _sampleLabSession.value = null
        val shouldCloseStudio = allowParentStudioClose && (current?.returnToHubOnClose == true)

        if (current != null || activeJob != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { activeJob?.cancelAndJoin() }
                sampleLabMutex.withLock {
                    val bmp = activeSampleBaseline?.imageSourceBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        bmp.recycle()
                    }
                    activeSampleBaseline = null
                    activeSampleBaselineSessionId = null
                    activeSampleBaselineTimestampMs = -1L
                    activeSampleBaselineCrop = null
                    if (current != null) {
                        sampleFrameEngine.cleanupSession(current.sessionId)
                    }
                }
            }
        }
        if (shouldCloseStudio) {
            closeStudioSession()
        }
    }

    fun updateSampleLabTimestamp(timestampMs: Long) {
        val session = _sampleLabSession.value ?: return
        if (session.probe.mediaKind != MediaKind.VIDEO || session.probe.durationMs <= 0L) return
        val clamped = timestampMs.coerceIn(0L, session.probe.durationMs)
        val prevJob = sampleLabJob
        sampleLabJob = null
        prevJob?.cancel()

        val resetResults = session.candidates.associate { cand ->
            cand.id to RenderedSampleResult(
                candidateId = cand.id,
                previewFilePath = null,
                status = SampleRenderStatus.IDLE
            )
        }
        _sampleLabSession.update { cur ->
            cur?.copy(
                timestampMs = clamped,
                referencePreviewPath = null,
                isPreparingBaseline = true,
                isRenderingSamples = true,
                baselineError = null,
                renderedResults = resetResults
            )
        }
        launchSampleLabRenderPipeline(
            targetSessionId = session.sessionId,
            requestedTsMs = clamped,
            autoDodgeBlackFade = false,
            candidateIdsToRender = session.candidates.map { it.id },
            previousJobToJoin = prevJob
        )
    }

    fun rollRandomSampleLabTimestamp() {
        val session = _sampleLabSession.value ?: return
        if (session.probe.mediaKind != MediaKind.VIDEO || session.probe.durationMs <= 0L) return
        val nextTs = SampleFrameEngine.pickRandomTimestampMs(
            durationMs = session.probe.durationMs,
            trimSpec = session.inheritedTrim,
            previousTimestampMs = session.timestampMs
        )
        val prevJob = sampleLabJob
        sampleLabJob = null
        prevJob?.cancel()

        val resetResults = session.candidates.associate { cand ->
            cand.id to RenderedSampleResult(
                candidateId = cand.id,
                previewFilePath = null,
                status = SampleRenderStatus.IDLE
            )
        }
        _sampleLabSession.update { cur ->
            cur?.copy(
                timestampMs = nextTs,
                referencePreviewPath = null,
                isPreparingBaseline = true,
                isRenderingSamples = true,
                baselineError = null,
                renderedResults = resetResults
            )
        }
        launchSampleLabRenderPipeline(
            targetSessionId = session.sessionId,
            requestedTsMs = nextTs,
            autoDodgeBlackFade = true,
            candidateIdsToRender = session.candidates.map { it.id },
            previousJobToJoin = prevJob
        )
    }

    fun retrySampleLabBaseline() {
        val session = _sampleLabSession.value ?: return
        val prevJob = sampleLabJob
        sampleLabJob = null
        prevJob?.cancel()

        val resetResults = session.candidates.associate { cand ->
            cand.id to RenderedSampleResult(
                candidateId = cand.id,
                previewFilePath = null,
                status = SampleRenderStatus.IDLE
            )
        }
        _sampleLabSession.update { cur ->
            cur?.copy(
                isPreparingBaseline = true,
                isRenderingSamples = true,
                baselineError = null,
                renderedResults = resetResults
            )
        }
        launchSampleLabRenderPipeline(
            targetSessionId = session.sessionId,
            requestedTsMs = session.timestampMs,
            autoDodgeBlackFade = false,
            candidateIdsToRender = session.candidates.map { it.id },
            previousJobToJoin = prevJob
        )
    }

    fun setSampleLabIncludeOriginal(enabled: Boolean) {
        _sampleLabSession.update { session ->
            if (session == null) return@update null
            val nextSession = session.copy(includeOriginalFrame = enabled)
            if (!enabled && nextSession.fullscreenActiveTabId == "orig") {
                val nextTabs = nextSession.buildComparatorTabs()
                nextSession.copy(
                    fullscreenActiveTabId = nextTabs.firstOrNull()?.id ?: "orig"
                )
            } else {
                nextSession
            }
        }
    }

    fun addSampleLabCandidate(initialDraft: SampleEntryDraft? = null): Boolean {
        var limitHit = false
        var added = false
        _sampleLabSession.update { session ->
            limitHit = false
            added = false
            if (session == null) return@update null
            val existingCands = session.candidates.filter { !it.isOriginalBaseline }
            if (existingCands.size >= 12) {
                limitHit = true
                return@update session
            }
            val nextIdx = existingCands.size + 1
            val defaultDraft = (initialDraft ?: when (session.probe.mediaKind) {
                MediaKind.IMAGE, MediaKind.CAMERA_RAW -> SampleEntryDraft(
                    targetExtension = "jpg",
                    compressMode = CompressMode.QUALITY,
                    imageQuality = 78,
                    maxDimensionPx = 1280
                )
                else -> SampleEntryDraft(
                    targetExtension = "mp4",
                    videoCodec = VideoCodecChoice.HEVC,
                    compressMode = CompressMode.QUALITY,
                    videoCrf = 26,
                    maxDimensionPx = 1280,
                    compressionTier = CompressionTier.TWO_PASS_HEVC
                )
            }).harmonizedForContainer(
                ext = initialDraft?.targetExtension ?: if (session.probe.mediaKind == MediaKind.VIDEO) "mp4" else "jpg",
                codec = initialDraft?.videoCodec ?: VideoCodecChoice.HEVC,
                mediaKind = session.probe.mediaKind
            )
            val newCand = SampleCandidateSpec(
                id = "cand_${UUID.randomUUID()}",
                indexNumber = nextIdx,
                label = defaultDraft.autoLabel(session.probe.mediaKind),
                pipelineSpec = defaultDraft.toPipelineSpec(
                    probe = session.probe,
                    inheritedTrim = session.inheritedTrim,
                    inheritedCrop = session.inheritedCrop,
                    stripMetadata = session.stripMetadata
                ),
                isOriginalBaseline = false,
                voteState = SampleVoteState.NEUTRAL,
                draft = defaultDraft
            )
            val nextResults = session.renderedResults.toMutableMap()
            nextResults[newCand.id] = RenderedSampleResult(
                candidateId = newCand.id,
                previewFilePath = null,
                status = SampleRenderStatus.IDLE
            )
            added = true
            session.copy(
                candidates = existingCands + newCand,
                renderedResults = nextResults
            )
        }
        if (limitHit) {
            showBanner("Limit 12 concurrent Sample Lab configs per session")
        }
        return added
    }

    fun addPresetAsSampleLabCandidate(preset: FormatPresetEntity) {
        val session = _sampleLabSession.value ?: return
        val spec = runCatching { presetJson.decodeFromString<PipelineSpec>(preset.specJson) }.getOrNull()
        if (spec == null) {
            showBanner("Could not read saved preset")
            return
        }
        val draft = SampleEntryDraft.fromPipelineSpec(session.probe, spec)
        if (addSampleLabCandidate(draft)) {
            showBanner("Added ${preset.emoji} ${preset.name} to Sample Lab")
        }
    }

    fun updateSampleLabCandidateDraft(
        candidateId: String,
        transform: (SampleEntryDraft) -> SampleEntryDraft
    ) {
        _sampleLabSession.update { session ->
            if (session == null) return@update null
            val nextCandidates = session.candidates.map { cand ->
                if (cand.id != candidateId) {
                    cand
                } else {
                    val curDraft = cand.draft ?: SampleEntryDraft.fromPipelineSpec(session.probe, cand.pipelineSpec)
                    val updated = transform(curDraft)
                    val harmonized = updated.harmonizedForContainer(
                        ext = updated.targetExtension,
                        codec = updated.videoCodec,
                        mediaKind = session.probe.mediaKind
                    )
                    cand.copy(
                        label = harmonized.autoLabel(session.probe.mediaKind),
                        pipelineSpec = harmonized.toPipelineSpec(
                            probe = session.probe,
                            inheritedTrim = session.inheritedTrim,
                            inheritedCrop = session.inheritedCrop,
                            stripMetadata = session.stripMetadata
                        ),
                        draft = harmonized
                    )
                }
            }
            val nextResults = session.renderedResults.toMutableMap()
            val oldPath = nextResults[candidateId]?.previewFilePath
            nextResults[candidateId] = RenderedSampleResult(
                candidateId = candidateId,
                previewFilePath = null,
                status = SampleRenderStatus.IDLE
            )
            if (!oldPath.isNullOrBlank() && candidateId != "orig") {
                viewModelScope.launch(Dispatchers.IO) {
                    sampleLabMutex.withLock {
                        val stillInUse = _sampleLabSession.value?.renderedResults?.values?.any {
                            it.status == SampleRenderStatus.READY && it.previewFilePath == oldPath
                        } == true
                        if (!stillInUse) {
                            runCatching { File(oldPath).delete() }
                        }
                    }
                }
            }
            val evaluated = sampleFrameEngine.evaluateParetoBest(
                originalBytes = session.probe.fileSizeBytes,
                renderedResults = nextResults,
                candidates = nextCandidates
            )
            session.copy(candidates = nextCandidates, renderedResults = evaluated)
        }
    }

    fun removeSampleLabCandidate(candidateId: String) {
        val current = _sampleLabSession.value ?: return
        val existingUser = current.candidates.filter { !it.isOriginalBaseline }
        if (existingUser.size <= 1 || candidateId == "orig") {
            showBanner("Keep at least 1 sample config")
            return
        }

        val oldTabs = current.buildComparatorTabs()
        val oldActiveIdx = oldTabs.indexOfFirst { it.id == current.fullscreenActiveTabId }.coerceAtLeast(0)
        val filtered = existingUser.filterNot { it.id == candidateId }
        val renumbered = filtered.mapIndexed { idx, cand -> cand.copy(indexNumber = idx + 1) }

        val deletedPath = current.renderedResults[candidateId]?.previewFilePath
        val nextResults = current.renderedResults.toMutableMap().apply { remove(candidateId) }
        val reEvaluated = sampleFrameEngine.evaluateParetoBest(
            originalBytes = current.probe.fileSizeBytes,
            renderedResults = nextResults,
            candidates = renumbered
        )

        val nextSessionPreTab = current.copy(candidates = renumbered, renderedResults = reEvaluated)
        val newTabs = nextSessionPreTab.buildComparatorTabs()
        val nextTabId = if (current.fullscreenActiveTabId == candidateId && newTabs.isNotEmpty()) {
            val clampedIdx = ViewportSyncMath.clampActiveIndexAfterRemoval(oldActiveIdx, newTabs.size)
            newTabs[clampedIdx].id
        } else if (newTabs.any { it.id == current.fullscreenActiveTabId }) {
            current.fullscreenActiveTabId
        } else {
            newTabs.firstOrNull()?.id ?: "orig"
        }

        _sampleLabSession.value = nextSessionPreTab.copy(fullscreenActiveTabId = nextTabId)
        if (!deletedPath.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                sampleLabMutex.withLock {
                    runCatching { File(deletedPath).delete() }
                }
            }
        }
    }

    fun voteSampleLabCandidate(candidateId: String, vote: SampleVoteState) {
        _sampleLabSession.update { session ->
            if (session == null) return@update null
            if (candidateId == "orig") {
                session.copy(origVoteState = vote)
            } else {
                session.copy(
                    candidates = session.candidates.map { cand ->
                        if (cand.id == candidateId) cand.copy(voteState = vote) else cand
                    }
                )
            }
        }
    }

    fun renderAllSampleLabCandidates() {
        val session = _sampleLabSession.value ?: return
        val prevJob = sampleLabJob
        sampleLabJob = null
        prevJob?.cancel()

        val targets = session.candidates
            .filter { !it.isOriginalBaseline && session.renderedResults[it.id]?.status != SampleRenderStatus.READY }
            .ifEmpty { session.candidates.filter { !it.isOriginalBaseline } }

        val nextResults = session.renderedResults.toMutableMap()
        nextResults.forEach { (id, res) ->
            if (res.status == SampleRenderStatus.RENDERING) {
                nextResults[id] = RenderedSampleResult(
                    candidateId = id,
                    previewFilePath = null,
                    status = SampleRenderStatus.IDLE
                )
            }
        }
        targets.forEach { cand ->
            nextResults[cand.id] = RenderedSampleResult(
                candidateId = cand.id,
                previewFilePath = null,
                status = SampleRenderStatus.IDLE
            )
        }
        _sampleLabSession.update { cur ->
            cur?.copy(isRenderingSamples = true, renderedResults = nextResults)
        }
        launchSampleLabRenderPipeline(
            targetSessionId = session.sessionId,
            requestedTsMs = session.timestampMs,
            autoDodgeBlackFade = false,
            candidateIdsToRender = targets.map { it.id },
            previousJobToJoin = prevJob
        )
    }

    fun renderSingleSampleLabCandidate(candidateId: String) {
        val session = _sampleLabSession.value ?: return
        if (session.candidates.none { it.id == candidateId }) return
        val prevJob = sampleLabJob
        sampleLabJob = null
        prevJob?.cancel()

        val nextResults = session.renderedResults.toMutableMap()
        nextResults.forEach { (id, res) ->
            if (res.status == SampleRenderStatus.RENDERING && id != candidateId) {
                nextResults[id] = RenderedSampleResult(
                    candidateId = id,
                    previewFilePath = null,
                    status = SampleRenderStatus.IDLE
                )
            }
        }
        nextResults[candidateId] = RenderedSampleResult(
            candidateId = candidateId,
            previewFilePath = null,
            status = SampleRenderStatus.RENDERING
        )
        _sampleLabSession.update { cur ->
            cur?.copy(isRenderingSamples = true, renderedResults = nextResults)
        }
        launchSampleLabRenderPipeline(
            targetSessionId = session.sessionId,
            requestedTsMs = session.timestampMs,
            autoDodgeBlackFade = false,
            candidateIdsToRender = listOf(candidateId),
            previousJobToJoin = prevJob
        )
    }

    fun suggestBestSampleConfig() {
        val session = _sampleLabSession.value ?: return
        val seeds = SampleEntryDraft.bestKneeSeedDrafts(session.probe)
        val existingKeys = session.candidates
            .mapNotNull { it.draft?.let { d -> d.autoLabel(session.probe.mediaKind) to d.targetExtension } }
            .toMutableSet()

        val mergedCandidates = session.candidates.toMutableList()
        val updatedResults = session.renderedResults.toMutableMap()
        for (seed in seeds) {
            if (mergedCandidates.size >= 6) break
            val key = seed.autoLabel(session.probe.mediaKind) to seed.targetExtension
            if (key in existingKeys) continue
            existingKeys += key
            val newCand = SampleCandidateSpec(
                id = "cand_${UUID.randomUUID()}",
                indexNumber = mergedCandidates.size + 1,
                label = seed.autoLabel(session.probe.mediaKind),
                pipelineSpec = seed.toPipelineSpec(
                    probe = session.probe,
                    inheritedTrim = session.inheritedTrim,
                    inheritedCrop = session.inheritedCrop,
                    stripMetadata = session.stripMetadata
                ),
                isOriginalBaseline = false,
                voteState = SampleVoteState.NEUTRAL,
                draft = seed
            )
            mergedCandidates += newCand
            updatedResults[newCand.id] = RenderedSampleResult(
                candidateId = newCand.id,
                previewFilePath = null,
                status = SampleRenderStatus.IDLE
            )
        }

        val toRender = mergedCandidates
            .filter { updatedResults[it.id]?.status != SampleRenderStatus.READY }
            .map { it.id }

        if (toRender.isEmpty()) {
            val evaluated = sampleFrameEngine.evaluateParetoBest(
                originalBytes = session.probe.fileSizeBytes,
                renderedResults = updatedResults,
                candidates = mergedCandidates
            )
            _sampleLabSession.update { cur ->
                cur?.copy(candidates = mergedCandidates, renderedResults = evaluated)
            }
            announceBestSampleBanner(mergedCandidates, evaluated)
            return
        }

        val prevJob = sampleLabJob
        sampleLabJob = null
        prevJob?.cancel()
        updatedResults.forEach { (id, res) ->
            if (res.status == SampleRenderStatus.RENDERING) {
                updatedResults[id] = RenderedSampleResult(
                    candidateId = id,
                    previewFilePath = null,
                    status = SampleRenderStatus.IDLE
                )
            }
        }
        _sampleLabSession.update { cur ->
            cur?.copy(
                candidates = mergedCandidates,
                renderedResults = updatedResults,
                isRenderingSamples = true
            )
        }
        launchSampleLabRenderPipeline(
            targetSessionId = session.sessionId,
            requestedTsMs = session.timestampMs,
            autoDodgeBlackFade = false,
            candidateIdsToRender = toRender,
            previousJobToJoin = prevJob,
            announceBestOnFinish = true
        )
    }

    fun openFullscreenComparator(initialCandidateId: String?) {
        _sampleLabSession.update { session ->
            if (session == null) return@update null
            val tabs = session.buildComparatorTabs()
            if (tabs.isEmpty()) return@update session
            val targetId = when {
                initialCandidateId != null && tabs.any { it.id == initialCandidateId } -> initialCandidateId
                session.includeOriginalFrame && tabs.any { it.id == "orig" } -> "orig"
                else -> tabs.first().id
            }
            session.copy(
                isFullscreenCompareOpen = true,
                fullscreenActiveTabId = targetId
            )
        }
    }

    fun closeFullscreenComparator() {
        _sampleLabSession.update { it?.copy(isFullscreenCompareOpen = false) }
    }

    fun selectFullscreenComparatorTab(tabId: String) {
        _sampleLabSession.update { session ->
            if (session == null) return@update null
            session.copy(fullscreenActiveTabId = tabId)
        }
    }

    fun useSampleCandidateInStudio(candidateId: String) {
        val session = _sampleLabSession.value ?: return
        val candidate = session.candidates.firstOrNull { it.id == candidateId } ?: return
        val rendered = session.renderedResults[candidate.id]
        val spec = candidate.pipelineSpec

        val targetIdx = session.studioItemIndex
        val currentStudio = _studioSession.value
        if (currentStudio != null && targetIdx in currentStudio.items.indices &&
            currentStudio.selectedItemIndex != targetIdx
        ) {
            selectStudioItem(targetIdx)
        }

        _studioSession.update { st ->
            if (st == null) return@update null
            val updatedState = st.updateActiveConfig { cfg ->
                overlayPresetOntoConfig(cfg, session.probe, spec).copy(
                    convertEnabled = true,
                    compressEnabled = true
                )
            }
            updatedState.copy(
                activeTab = StudioToolTab.COMPRESS,
                sampleCalibration = rendered?.calibration,
                showCompressedSample = false
            )
        }
        // Keep Studio open even if Sample Lab originally launched from Hub!
        _sampleLabSession.update { it?.copy(returnToHubOnClose = false) }
        closeSampleLab()
        scheduleInlineSampleGeneration()
        showBanner("Applied #${candidate.indexNumber} (${candidate.label}) to Studio")
    }

    private fun launchSampleLabRenderPipeline(
        targetSessionId: String,
        requestedTsMs: Long,
        autoDodgeBlackFade: Boolean,
        candidateIdsToRender: List<String>,
        previousJobToJoin: Job?,
        announceBestOnFinish: Boolean = false
    ) {
        lateinit var launched: Job
        launched = viewModelScope.launch {
            try {
                runCatching { previousJobToJoin?.cancelAndJoin() }
                val baseline = sampleLabMutex.withLock {
                    val curSession = _sampleLabSession.value ?: return@withLock null
                    if (curSession.sessionId != targetSessionId) return@withLock null

                    val existing = activeSampleBaseline
                    if (existing != null &&
                        activeSampleBaselineSessionId == targetSessionId &&
                        activeSampleBaselineTimestampMs == requestedTsMs &&
                        activeSampleBaselineCrop == curSession.inheritedCrop
                    ) {
                        existing
                    } else {
                        val sameSession = activeSampleBaselineSessionId == targetSessionId
                        val reuseVideo = if (sameSession) existing?.stagedVideoFile else null
                        val fresh = sampleFrameEngine.prepareBaseline(
                            sessionId = targetSessionId,
                            sourceUri = Uri.parse(curSession.probe.uriString),
                            probe = curSession.probe,
                            crop = curSession.inheritedCrop,
                            trim = curSession.inheritedTrim,
                            requestedTimestampMs = requestedTsMs,
                            autoDodgeBlackFade = autoDodgeBlackFade,
                            existingStagedVideoFile = reuseVideo
                        )
                        val oldBmp = existing?.imageSourceBitmap
                        if (oldBmp != null && oldBmp !== fresh.imageSourceBitmap && !oldBmp.isRecycled) {
                            oldBmp.recycle()
                        }
                        activeSampleBaseline = fresh
                        activeSampleBaselineSessionId = targetSessionId
                        activeSampleBaselineTimestampMs = fresh.resolvedTimestampMs
                        activeSampleBaselineCrop = curSession.inheritedCrop
                        fresh
                    }
                } ?: return@launch

                _sampleLabSession.update { cur ->
                    if (cur == null || cur.sessionId != targetSessionId) return@update cur
                    val nextRes = cur.renderedResults.toMutableMap()
                    nextRes["orig"] = baseline.baselineResult
                    cur.copy(
                        timestampMs = baseline.resolvedTimestampMs,
                        referencePreviewPath = baseline.baselineResult.previewFilePath,
                        isPreparingBaseline = false,
                        baselineError = null,
                        renderedResults = nextRes
                    )
                }

                for (candId in candidateIdsToRender) {
                    val snapshot = _sampleLabSession.value ?: break
                    if (snapshot.sessionId != targetSessionId) break
                    val cand = snapshot.candidates.firstOrNull { it.id == candId } ?: continue

                    _sampleLabSession.update { cur ->
                        if (cur == null || cur.sessionId != targetSessionId) return@update cur
                        val nextRes = cur.renderedResults.toMutableMap()
                        nextRes[candId] = RenderedSampleResult(
                            candidateId = candId,
                            previewFilePath = null,
                            status = SampleRenderStatus.RENDERING
                        )
                        cur.copy(renderedResults = nextRes)
                    }

                    val rendered = sampleLabMutex.withLock {
                        if (activeSampleBaselineSessionId != targetSessionId) return@withLock null
                        val latestCand = _sampleLabSession.value?.candidates?.firstOrNull { it.id == candId }
                        if (latestCand == null || latestCand.pipelineSpec != cand.pipelineSpec) {
                            return@withLock null
                        }
                        sampleFrameEngine.renderCandidate(
                            sessionId = targetSessionId,
                            candidate = cand,
                            probe = snapshot.probe,
                            baseline = baseline
                        )
                    } ?: continue

                    var staleRenderedPathToDelete: String? = null
                    _sampleLabSession.update { cur ->
                        staleRenderedPathToDelete = null
                        if (cur == null || cur.sessionId != targetSessionId) {
                            staleRenderedPathToDelete = rendered.previewFilePath
                            return@update cur
                        }
                        val latestCand = cur.candidates.firstOrNull { it.id == candId }
                        if (latestCand == null || latestCand.pipelineSpec != cand.pipelineSpec) {
                            staleRenderedPathToDelete = rendered.previewFilePath
                            return@update cur
                        }
                        val nextRes = cur.renderedResults.toMutableMap()
                        nextRes[candId] = rendered
                        val evaluated = sampleFrameEngine.evaluateParetoBest(
                            originalBytes = cur.probe.fileSizeBytes,
                            renderedResults = nextRes,
                            candidates = cur.candidates
                        )
                        cur.copy(renderedResults = evaluated)
                    }
                    staleRenderedPathToDelete?.takeIf { it.isNotBlank() }?.let { orphanPath ->
                        withContext(Dispatchers.IO) {
                            sampleLabMutex.withLock {
                                val stillReferenced = _sampleLabSession.value?.renderedResults?.values?.any {
                                    it.status == SampleRenderStatus.READY && it.previewFilePath == orphanPath
                                } == true
                                if (!stillReferenced) {
                                    runCatching { File(orphanPath).delete() }
                                }
                            }
                        }
                    }
                }

                _sampleLabSession.update { cur ->
                    if (cur == null || cur.sessionId != targetSessionId) return@update cur
                    val evaluated = sampleFrameEngine.evaluateParetoBest(
                        originalBytes = cur.probe.fileSizeBytes,
                        renderedResults = cur.renderedResults,
                        candidates = cur.candidates
                    )
                    if (announceBestOnFinish) {
                        announceBestSampleBanner(cur.candidates, evaluated)
                    }
                    cur.copy(isRenderingSamples = false, renderedResults = evaluated)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                _sampleLabSession.update { cur ->
                    if (cur == null || cur.sessionId != targetSessionId) return@update cur
                    val clearedResults = cur.renderedResults.mapValues { (id, res) ->
                        if (res.status == SampleRenderStatus.RENDERING) {
                            RenderedSampleResult(
                                candidateId = id,
                                previewFilePath = null,
                                status = SampleRenderStatus.IDLE
                            )
                        } else {
                            res
                        }
                    }
                    val cleanBaselineErr = CredentialRedactor.sanitizeDiagnostic(
                        err.message,
                        maxChars = 140
                    ).ifBlank { "Could not extract reference frame" }
                    cur.copy(
                        isPreparingBaseline = false,
                        isRenderingSamples = false,
                        baselineError = cleanBaselineErr,
                        renderedResults = clearedResults
                    )
                }
            }
        }
        sampleLabJob = launched
    }

    private fun announceBestSampleBanner(
        candidates: List<SampleCandidateSpec>,
        renderedResults: Map<String, RenderedSampleResult>
    ) {
        val winner = candidates.firstOrNull { renderedResults[it.id]?.isRecommendedBest == true }
        val summary = winner?.let { renderedResults[it.id]?.recommendationSummary }
        if (winner != null) {
            val detail = if (!summary.isNullOrBlank()) " ($summary)" else ""
            showBanner("★ Best Config: #${winner.indexNumber} ${winner.label}$detail")
        } else {
            showBanner("Render sample candidates to evaluate Pareto Best Config")
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

    fun openCompletedJobInStudio(jobId: String) {
        val job = jobs.value.firstOrNull { it.id == jobId } ?: return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { resolvePlayableOutputUri(job) }
            if (uri == null) {
                showBanner("Output file is no longer available on device")
                return@launch
            }
            initializeStudioSessionForUris(listOf(uri), fromShareIntent = false)
        }
    }

    fun openCompletedGroupInStudio(groupId: String) {
        val completed = jobs.value
            .filter { it.groupId == groupId && it.statusEnum == com.fissal.forge.data.local.JobStatus.COMPLETED }
            .sortedWith(compareBy<ForgeJobEntity> { it.playlistIndex ?: Int.MAX_VALUE }.thenBy { it.createdAtMs })
        if (completed.isEmpty()) {
            showBanner("No completed items ready to forge yet")
            return
        }
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) {
                completed.mapNotNull { resolvePlayableOutputUri(it) }
            }
            if (uris.isEmpty()) {
                showBanner("Completed output files are no longer available on device")
                return@launch
            }
            initializeStudioSessionForUris(uris, fromShareIntent = false)
        }
    }

    private fun resolvePlayableOutputUri(job: ForgeJobEntity): Uri? {
        val path = job.outputPath
        if (!path.isNullOrBlank()) {
            val f = File(path)
            if (f.exists() && f.isFile && f.canRead()) {
                return Uri.fromFile(f)
            }
        }
        val uriStr = job.outputUriString
        if (!uriStr.isNullOrBlank()) {
            val parsed = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
            val readable = runCatching {
                app.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { true } == true
            }.getOrDefault(false)
            if (readable) return parsed
        }
        return null
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
        if (binaryManager.isUpdatingYtDlpFlow.value) return
        viewModelScope.launch {
            runCatching {
                binaryManager.updateYtDlp(nightly)
            }.onSuccess { status ->
                if (!nightly) {
                    val nowMs = System.currentTimeMillis()
                    settingsRepo.update { prev ->
                        prev.copy(
                            ytDlpFirstRunUpdateDone = true,
                            lastYtDlpUpdateCheckMs = nowMs
                        )
                    }
                }
                showBanner("yt-dlp update: ${status?.name ?: "Complete"}")
            }.onFailure { err ->
                val cleanErr = CredentialRedactor.sanitizeDiagnostic(
                    err.message,
                    maxChars = 140
                ).ifBlank { "Unknown error" }
                showBanner("yt-dlp update failed: $cleanErr")
            }
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
