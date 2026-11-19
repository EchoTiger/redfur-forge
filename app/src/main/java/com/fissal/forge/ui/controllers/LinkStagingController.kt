package com.fissal.forge.ui.controllers

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import com.fissal.forge.auth.AuthRequiredException
import com.fissal.forge.auth.ConnectedServiceSummary
import com.fissal.forge.auth.CredentialRedactor
import com.fissal.forge.auth.ServiceCatalog
import com.fissal.forge.auth.ServiceDescriptor
import com.fissal.forge.auth.SiteAuthVault
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.domain.logic.ParsedUrlItem
import com.fissal.forge.domain.logic.UrlBatchParser
import com.fissal.forge.domain.logic.YtDlpJsonParser
import com.fissal.forge.domain.model.ChapterTrackItem
import com.fissal.forge.domain.model.DetectedLinkKind
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.LinkItemSettings
import com.fissal.forge.domain.model.LinkResolveState
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.domain.model.ResolvedUrlMetadata
import com.fissal.forge.engine.YtDlpEngine
import com.fissal.forge.pipeline.AuthRequiredEvent
import com.fissal.forge.pipeline.PipelineOrchestrator
import com.fissal.forge.ui.AuthPromptState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns URL batch parsing, async yt-dlp metadata staging, playlist/chapter selection,
 * per-link & bulk rule propagation, and site authentication prompts/retries.
 */
class LinkStagingController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val settings: StateFlow<ForgeSettings>,
    private val jobs: StateFlow<List<ForgeJobEntity>>,
    private val ytDlpEngine: YtDlpEngine,
    private val siteAuthVault: SiteAuthVault,
    private val orchestrator: PipelineOrchestrator,
    private val onShowBanner: (String) -> Unit,
    private val onOpenDownloadWorkspace: () -> Unit,
    private val onNavigateToQueue: () -> Unit,
    private val onRetryJob: (String) -> Unit,
    private val onRetryPlaylistGroup: (String) -> Unit
) {
    private val siteConnections: StateFlow<List<ConnectedServiceSummary>> = siteAuthVault.connectionsFlow

    private val _urlBatchText = MutableStateFlow("")
    val urlBatchText: StateFlow<String> = _urlBatchText.asStateFlow()

    private val _parsedUrls = MutableStateFlow<List<ParsedUrlItem>>(emptyList())
    val parsedUrls: StateFlow<List<ParsedUrlItem>> = _parsedUrls.asStateFlow()

    private val _resolvedLinks = MutableStateFlow<List<ResolvedLinkChipItem>>(emptyList())
    val resolvedLinks: StateFlow<List<ResolvedLinkChipItem>> = _resolvedLinks.asStateFlow()

    private val _checkingServiceId = MutableStateFlow<String?>(null)
    val checkingServiceId: StateFlow<String?> = _checkingServiceId.asStateFlow()

    private val _authPrompt = MutableStateFlow<AuthPromptState?>(null)
    val authPrompt: StateFlow<AuthPromptState?> = _authPrompt.asStateFlow()

    private val metadataCache = ConcurrentHashMap<String, ResolvedUrlMetadata>()
    private val resolveJobs = ConcurrentHashMap<String, Job>()
    private val resolveSemaphore = Semaphore(3)
    private var urlSyncJob: Job? = null

    fun onAuthRequiredFromOrchestrator(ev: AuthRequiredEvent) {
        _authPrompt.value = AuthPromptState(
            service = ev.exception.service ?: ServiceCatalog.matchUrl(ev.url, siteAuthVault.connectedCustomDomains()),
            targetUrl = ev.url,
            reason = ev.exception.userFacingReason,
            isPaywallOrSubscription = ev.exception.isPaywallOrSubscription,
            retryCleanUrl = null,
            retryJobId = ev.jobId
        )
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
            urlSyncJob = scope.launch {
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
        val job = scope.launch {
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
        scope.launch {
            runCatching {
                siteAuthVault.saveWebViewSession(service, userAgent, visitedUrls)
            }.onSuccess { summary ->
                applyConnectedSessionAndRetry(summary, pendingRetryUrl, pendingRetryJobId)
                onShowBanner("Connected ${summary.service.displayName} securely (${summary.cookieCount} cookies)")
            }.onFailure { err ->
                onShowBanner(CredentialRedactor.sanitizeDiagnostic(err.message, 140).ifBlank { "Could not capture sign-in session" })
            }
        }
    }

    fun importCookiesForService(
        service: ServiceDescriptor,
        uri: Uri,
        pendingRetryUrl: String? = null,
        pendingRetryJobId: String? = null
    ) {
        scope.launch {
            val defaultUa = runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrDefault("")
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            val result = siteAuthVault.importCookiesTxt(service, uri, defaultUa)
            result.onSuccess { summary ->
                applyConnectedSessionAndRetry(summary, pendingRetryUrl, pendingRetryJobId)
                onShowBanner("Imported ${summary.cookieCount} encrypted cookies for ${summary.service.displayName}")
            }.onFailure { err ->
                onShowBanner(CredentialRedactor.sanitizeDiagnostic(err.message, 140).ifBlank { "Failed to import cookies.txt" })
            }
        }
    }

    fun checkSiteConnection(serviceId: String) {
        if (_checkingServiceId.value != null) return
        scope.launch {
            _checkingServiceId.value = serviceId
            try {
                val summary = siteAuthVault.checkConnection(serviceId)
                if (summary != null) {
                    onShowBanner("${summary.service.displayName}: ${summary.statusMessage}")
                }
            } catch (err: Throwable) {
                onShowBanner(CredentialRedactor.sanitizeDiagnostic(err.message, 140).ifBlank { "Connection check failed" })
            } finally {
                _checkingServiceId.value = null
            }
        }
    }

    fun removeSiteConnection(serviceId: String) {
        scope.launch {
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
                onShowBanner("Disconnected & wiped local session cookies")
            }.onFailure { err ->
                onShowBanner(CredentialRedactor.sanitizeDiagnostic(err.message, 140).ifBlank { "Failed to remove site connection" })
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
                onRetryPlaylistGroup(groupId)
            } else {
                onRetryJob(pendingRetryJobId)
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
            affected = 0
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
        onShowBanner("Applied settings across $affected ${matchKind.label.lowercase()} link(s)")
    }

    fun applySettingsToSameSite(sourceUrl: String) {
        val source = _resolvedLinks.value.firstOrNull { it.cleanUrl == sourceUrl } ?: return
        val host = source.host
        if (host.isBlank()) return
        var affected = 0
        _resolvedLinks.update { list ->
            affected = 0
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
        onShowBanner("Applied settings across $affected link(s) on $host")
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
        onShowBanner("Applied settings to all ${_resolvedLinks.value.size} link(s)")
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
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) {
            onShowBanner("Clipboard is empty")
            return
        }
        val clip = clipboard.primaryClip ?: return
        val desc = clip.description
        if (desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) {
            val text = clip.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
            if (text.isNotBlank()) {
                val combined = if (_urlBatchText.value.isBlank()) text else "${_urlBatchText.value.trimEnd()}\n$text"
                onOpenDownloadWorkspace()
                updateUrlBatchText(combined, immediate = true)
            }
        } else {
            onShowBanner("Clipboard holds media, not URL text")
        }
    }

    fun queueResolvedBatch(
        copyToClipboard: Boolean,
        saveToDevice: Boolean
    ) {
        val chips = _resolvedLinks.value
        if (chips.isEmpty()) {
            onShowBanner("Paste at least one valid URL first")
            return
        }
        val curSettings = settings.value
        scope.launch {
            val count = orchestrator.enqueueResolvedLinks(chips, saveToDevice, copyToClipboard, curSettings)
            urlSyncJob?.cancel()
            resolveJobs.values.forEach { it.cancel() }
            resolveJobs.clear()
            _urlBatchText.value = ""
            _parsedUrls.value = emptyList()
            _resolvedLinks.value = emptyList()
            onNavigateToQueue()
            onShowBanner("Queued $count download${if (count == 1) "" else "s"}")
        }
    }

    fun cleanupOnCleared() {
        urlSyncJob?.cancel()
        urlSyncJob = null
        resolveJobs.values.forEach { it.cancel() }
        resolveJobs.clear()
    }
}
