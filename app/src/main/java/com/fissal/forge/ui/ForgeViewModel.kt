package com.fissal.forge.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fissal.forge.ForgeApplication
import com.fissal.forge.auth.ConnectedServiceSummary
import com.fissal.forge.auth.CredentialRedactor
import com.fissal.forge.auth.ServiceDescriptor
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.local.FormatPresetEntity
import com.fissal.forge.data.local.JobStatus
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.ParsedUrlItem
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.LinkItemSettings
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.domain.model.SampleEntryDraft
import com.fissal.forge.domain.model.SampleVoteState
import com.fissal.forge.pipeline.OrchestratorMetrics
import com.fissal.forge.ui.controllers.LinkStagingController
import com.fissal.forge.ui.controllers.SampleLabController
import com.fissal.forge.ui.controllers.StudioSessionController
import com.fissal.forge.update.AppUpdateManager
import com.fissal.forge.update.AppUpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Top-level UI coordinator for Redfur Forge. Delegates complex feature state to dedicated
 * controllers ([LinkStagingController], [StudioSessionController], [SampleLabController])
 * while keeping a unified reactive facade for Compose screens.
 */
class ForgeViewModel(application: Application) : AndroidViewModel(application) {

    private val app: ForgeApplication = application as ForgeApplication
    private val orchestrator = app.orchestrator
    private val presetDao = app.database.presetDao()
    private val presetJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val settingsRepo = app.settingsRepository
    private val siteAuthVault = app.siteAuthVault
    private val binaryManager = app.binaryManager
    private val appUpdateManager = app.appUpdateManager

    val deviceCapabilities: DeviceCapabilityReport = app.deviceCapabilities

    val formatPresets: StateFlow<List<FormatPresetEntity>> = presetDao.observeAllPresets()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<ForgeSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ForgeSettings())

    val siteConnections: StateFlow<List<ConnectedServiceSummary>> = siteAuthVault.connectionsFlow

    val jobs: StateFlow<List<ForgeJobEntity>> = orchestrator.jobsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val metrics: StateFlow<OrchestratorMetrics> = orchestrator.metrics
    val appUpdateState: StateFlow<AppUpdateState> = appUpdateManager.state
    val ytDlpVersion: StateFlow<String> = binaryManager.ytDlpVersionFlow
    val isUpdatingYtDlp: StateFlow<Boolean> = binaryManager.isUpdatingYtDlpFlow

    private val _selectedTab = MutableStateFlow(MainTab.FORGE)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _isDownloadWorkspaceOpen = MutableStateFlow(false)
    val isDownloadWorkspaceOpen: StateFlow<Boolean> = _isDownloadWorkspaceOpen.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val linkStaging = LinkStagingController(
        appContext = app,
        scope = viewModelScope,
        settings = settings,
        jobs = jobs,
        ytDlpEngine = app.ytDlpEngine,
        siteAuthVault = siteAuthVault,
        orchestrator = orchestrator,
        onShowBanner = ::showBanner,
        onOpenDownloadWorkspace = { _isDownloadWorkspaceOpen.value = true },
        onNavigateToQueue = { _selectedTab.value = MainTab.QUEUE },
        onRetryJob = ::retryJob,
        onRetryPlaylistGroup = ::retryPlaylistGroup
    )

    private lateinit var sampleLab: SampleLabController

    private val studio = StudioSessionController(
        cacheDir = app.cacheDir,
        scope = viewModelScope,
        settings = settings,
        deviceCapabilities = deviceCapabilities,
        probeEngine = app.probeEngine,
        imageRawEngine = app.imageRawEngine,
        ffmpegEngine = app.ffmpegEngine,
        orchestrator = orchestrator,
        presetDao = presetDao,
        presetJson = presetJson,
        onShowBanner = ::showBanner,
        onNavigateToQueue = { _selectedTab.value = MainTab.QUEUE },
        onBeforeInitializeSession = { sampleLab.dismissSampleLabForNavigation() }
    )

    val urlBatchText: StateFlow<String> = linkStaging.urlBatchText
    val parsedUrls: StateFlow<List<ParsedUrlItem>> = linkStaging.parsedUrls
    val resolvedLinks: StateFlow<List<ResolvedLinkChipItem>> = linkStaging.resolvedLinks
    val checkingServiceId: StateFlow<String?> = linkStaging.checkingServiceId
    val authPrompt: StateFlow<AuthPromptState?> = linkStaging.authPrompt
    val studioSession: StateFlow<StudioSessionState?> = studio.studioSession
    val sampleLabSession: StateFlow<SampleLabSessionState?>

    init {
        sampleLab = SampleLabController(
            scope = viewModelScope,
            sampleFrameEngine = app.sampleFrameEngine,
            presetJson = presetJson,
            studioSession = studio.studioSession,
            buildStudioPipelineSpec = { probe, cfg, forceCompress ->
                studio.buildPipelineSpecForStudioItem(
                    probe = probe,
                    cfg = cfg,
                    forceCompressSpec = forceCompress
                )
            },
            onInitializeStudioForUris = { uris ->
                studio.initializeStudioSessionForUris(uris, fromShareIntent = false)
            },
            onSelectStudioItem = studio::selectStudioItem,
            onApplyCandidateToStudio = studio::applySampleCandidateSpec,
            onCloseStudioSession = studio::closeStudioSession,
            onShowBanner = ::showBanner
        )
        sampleLabSession = sampleLab.sampleLabSession

        studio.sweepInitialCache()
        sampleLab.sweepInitialCache()

        viewModelScope.launch {
            orchestrator.authRequiredEvents.collect { ev ->
                linkStaging.onAuthRequiredFromOrchestrator(ev)
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
        refreshCacheSize()
    }

    override fun onCleared() {
        super.onCleared()
        linkStaging.cleanupOnCleared()
        studio.cleanupOnCleared()
        sampleLab.cleanupOnCleared()
    }

    // --- Navigation & Status Banner ---

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

    // --- Link Staging, Playlists, Chapters & Site Auth ---

    fun updateUrlBatchText(newText: String, immediate: Boolean = false) =
        linkStaging.updateUrlBatchText(newText, immediate)

    fun toggleLinkChipExpanded(cleanUrl: String) = linkStaging.toggleLinkChipExpanded(cleanUrl)
    fun retryResolveLink(cleanUrl: String) = linkStaging.retryResolveLink(cleanUrl)
    fun removeResolvedLink(cleanUrl: String) = linkStaging.removeResolvedLink(cleanUrl)
    fun clearAllResolvedLinks() = linkStaging.clearAllResolvedLinks()

    fun updateLinkChipSettings(cleanUrl: String, transform: (LinkItemSettings) -> LinkItemSettings) =
        linkStaging.updateLinkChipSettings(cleanUrl, transform)

    fun togglePlaylistTrack(cleanUrl: String, trackIndex: Int) =
        linkStaging.togglePlaylistTrack(cleanUrl, trackIndex)

    fun setAllPlaylistTracks(cleanUrl: String, selected: Boolean) =
        linkStaging.setAllPlaylistTracks(cleanUrl, selected)

    fun setSplitByChapters(cleanUrl: String, enabled: Boolean) =
        linkStaging.setSplitByChapters(cleanUrl, enabled)

    fun toggleChapterTrack(cleanUrl: String, chapterIndex: Int) =
        linkStaging.toggleChapterTrack(cleanUrl, chapterIndex)

    fun setAllChapterTracks(cleanUrl: String, selected: Boolean) =
        linkStaging.setAllChapterTracks(cleanUrl, selected)

    fun setBulkChapterMode(cleanUrl: String, mode: DownloadMode, audioFormat: String? = null) =
        linkStaging.setBulkChapterMode(cleanUrl, mode, audioFormat)

    fun toggleChapterTrackModeOverride(cleanUrl: String, chapterIndex: Int) =
        linkStaging.toggleChapterTrackModeOverride(cleanUrl, chapterIndex)

    fun setChapterTrackAudioFormat(cleanUrl: String, chapterIndex: Int, audioFormat: String) =
        linkStaging.setChapterTrackAudioFormat(cleanUrl, chapterIndex, audioFormat)

    fun dismissAuthPrompt() = linkStaging.dismissAuthPrompt()
    fun promptSignInForChip(cleanUrl: String) = linkStaging.promptSignInForChip(cleanUrl)

    fun completeWebViewSignIn(
        service: ServiceDescriptor,
        userAgent: String,
        visitedUrls: List<String>,
        pendingRetryUrl: String? = null,
        pendingRetryJobId: String? = null
    ) = linkStaging.completeWebViewSignIn(service, userAgent, visitedUrls, pendingRetryUrl, pendingRetryJobId)

    fun importCookiesForService(
        service: ServiceDescriptor,
        uri: Uri,
        pendingRetryUrl: String? = null,
        pendingRetryJobId: String? = null
    ) = linkStaging.importCookiesForService(service, uri, pendingRetryUrl, pendingRetryJobId)

    fun checkSiteConnection(serviceId: String) = linkStaging.checkSiteConnection(serviceId)
    fun removeSiteConnection(serviceId: String) = linkStaging.removeSiteConnection(serviceId)
    fun applySettingsToSameType(sourceUrl: String) = linkStaging.applySettingsToSameType(sourceUrl)
    fun applySettingsToSameSite(sourceUrl: String) = linkStaging.applySettingsToSameSite(sourceUrl)
    fun applySettingsToAllLinks(sourceUrl: String) = linkStaging.applySettingsToAllLinks(sourceUrl)

    fun applyQuickBatchPreset(transform: (LinkItemSettings) -> LinkItemSettings) =
        linkStaging.applyQuickBatchPreset(transform)

    fun pasteUrlsFromClipboard() = linkStaging.pasteUrlsFromClipboard()

    fun queueResolvedBatch(copyToClipboard: Boolean, saveToDevice: Boolean) =
        linkStaging.queueResolvedBatch(copyToClipboard, saveToDevice)

    // --- Forge Studio ---

    fun openStudioForUris(uris: List<Uri>, fromShareIntent: Boolean) =
        studio.openStudioForUris(uris, fromShareIntent)

    fun selectStudioItem(index: Int) = studio.selectStudioItem(index)
    fun applyStudioToSameType() = studio.applyStudioToSameType()
    fun applyStudioToAll() = studio.applyStudioToAll()
    fun closeStudioSession() = studio.closeStudioSession()
    fun updateStudio(transform: (StudioItemConfig) -> StudioItemConfig) = studio.updateStudio(transform)
    fun setStudioSectionEnabled(section: StudioToolTab, enabled: Boolean) =
        studio.setStudioSectionEnabled(section, enabled)

    fun toggleSamplePreviewComparison() = studio.toggleSamplePreviewComparison()
    fun clearInlineWatchJobs() = studio.clearInlineWatchJobs()
    fun selectStudioCropPreset(preset: AspectPreset) = studio.selectStudioCropPreset(preset)
    fun applyPresetToStudio(preset: FormatPresetEntity) = studio.applyPresetToStudio(preset)

    fun buildPipelineSpecForStudioItem(
        probe: MediaProbeResult,
        cfg: StudioItemConfig,
        saveToDevice: Boolean = true,
        copyToClipboard: Boolean = false,
        forceCompressSpec: Boolean = false
    ): PipelineSpec = studio.buildPipelineSpecForStudioItem(
        probe = probe,
        cfg = cfg,
        saveToDevice = saveToDevice,
        copyToClipboard = copyToClipboard,
        forceCompressSpec = forceCompressSpec
    )

    fun enqueueStudioSession(saveToDevice: Boolean, copyToClipboard: Boolean) =
        studio.enqueueStudioSession(saveToDevice, copyToClipboard)

    fun moveInlineStudioToBackground() = studio.moveInlineStudioToBackground()

    // --- Sample Frame Lab & Fullscreen Blink Comparator ---

    fun openSampleLabForUris(uris: List<Uri>) = sampleLab.openSampleLabForUris(uris)
    fun openSampleLabFromStudio() = sampleLab.openSampleLabFromStudio()
    fun closeSampleLab() = sampleLab.closeSampleLab()
    fun dismissSampleLabForNavigation() = sampleLab.dismissSampleLabForNavigation()
    fun updateSampleLabTimestamp(timestampMs: Long) = sampleLab.updateSampleLabTimestamp(timestampMs)
    fun rollRandomSampleLabTimestamp() = sampleLab.rollRandomSampleLabTimestamp()
    fun retrySampleLabBaseline() = sampleLab.retrySampleLabBaseline()
    fun setSampleLabIncludeOriginal(enabled: Boolean) = sampleLab.setSampleLabIncludeOriginal(enabled)
    fun addSampleLabCandidate(initialDraft: SampleEntryDraft? = null): Boolean =
        sampleLab.addSampleLabCandidate(initialDraft)

    fun addPresetAsSampleLabCandidate(preset: FormatPresetEntity) =
        sampleLab.addPresetAsSampleLabCandidate(preset)

    fun updateSampleLabCandidateDraft(
        candidateId: String,
        transform: (SampleEntryDraft) -> SampleEntryDraft
    ) = sampleLab.updateSampleLabCandidateDraft(candidateId, transform)

    fun removeSampleLabCandidate(candidateId: String) = sampleLab.removeSampleLabCandidate(candidateId)
    fun voteSampleLabCandidate(candidateId: String, vote: SampleVoteState) =
        sampleLab.voteSampleLabCandidate(candidateId, vote)

    fun renderAllSampleLabCandidates() = sampleLab.renderAllSampleLabCandidates()
    fun renderSingleSampleLabCandidate(candidateId: String) = sampleLab.renderSingleSampleLabCandidate(candidateId)
    fun suggestBestSampleConfig() = sampleLab.suggestBestSampleConfig()
    fun openFullscreenComparator(initialCandidateId: String?) = sampleLab.openFullscreenComparator(initialCandidateId)
    fun closeFullscreenComparator() = sampleLab.closeFullscreenComparator()
    fun selectFullscreenComparatorTab(tabId: String) = sampleLab.selectFullscreenComparatorTab(tabId)
    fun useSampleCandidateInStudio(candidateId: String) = sampleLab.useSampleCandidateInStudio(candidateId)

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

    // --- Queue Actions & Completed Output Routing ---

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
            studio.initializeStudioSessionForUris(listOf(uri), fromShareIntent = false)
        }
    }

    fun openCompletedGroupInStudio(groupId: String) {
        val completed = jobs.value
            .filter { it.groupId == groupId && it.statusEnum == JobStatus.COMPLETED }
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
            studio.initializeStudioSessionForUris(uris, fromShareIntent = false)
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

    // --- Settings, yt-dlp OTA, Cache & App Updates ---

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
            showBanner(String.format(Locale.US, "Cleared %.1f MB of staging & export cache", mb))
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
