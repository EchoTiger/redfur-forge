package com.fissal.forge.pipeline

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.fissal.forge.auth.AuthErrorClassifier
import com.fissal.forge.auth.AuthRequiredException
import com.fissal.forge.auth.ServiceCatalog
import com.fissal.forge.auth.SiteAuthVault
import com.fissal.forge.data.local.ForgeJobDao
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.local.JobLane
import com.fissal.forge.data.local.JobStatus
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.data.settings.ForgeSettingsRepository
import com.fissal.forge.delivery.ClipboardExportManager
import com.fissal.forge.delivery.MediaStoreExporter
import com.fissal.forge.domain.logic.BitrateBudgetCalculator
import com.fissal.forge.domain.logic.DeviceCapabilityAdvisor
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.ChapterTrackItem
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressSpec
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.DeliverySpec
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.DownloadSpec
import com.fissal.forge.domain.model.LinkItemSettings
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.domain.model.SilenceSkipSpec
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.engine.FFmpegCliEngine
import com.fissal.forge.engine.ImageRawEngine
import com.fissal.forge.engine.MediaProbeEngine
import com.fissal.forge.engine.YtDlpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

data class OrchestratorMetrics(
    val activeNetworkCount: Int = 0,
    val maxNetworkSlots: Int = 3,
    val activeComputeSlotsUsed: Int = 0,
    val maxComputeSlots: Int = 2,
    val totalActiveJobs: Int = 0
)

data class AuthRequiredEvent(
    val jobId: String,
    val url: String,
    val exception: AuthRequiredException
)

/**
 * Dual-lane resource-weighted pipeline orchestrator.
 * - Network Lane runs concurrent yt-dlp downloads bounded by user settings.
 * - Compute Lane runs RAM-weighted FFmpeg (SVT-AV1 / 2-pass HEVC / H.264 / Opus) & LibRaw transforms without OOMs.
 * - Fans playlists and chapter-split videos out into individually tracked & controllable jobs grouped by groupId.
 */
class PipelineOrchestrator(
    private val appContext: Context,
    private val jobDao: ForgeJobDao,
    private val settingsRepository: ForgeSettingsRepository,
    private val probeEngine: MediaProbeEngine,
    private val ytDlpEngine: YtDlpEngine,
    private val ffmpegEngine: FFmpegCliEngine,
    private val imageRawEngine: ImageRawEngine,
    private val mediaStoreExporter: MediaStoreExporter,
    val clipboardExportManager: ClipboardExportManager,
    private val deviceCapabilities: DeviceCapabilityReport = DeviceCapabilityAdvisor.profileDevice(appContext),
    private val siteAuthVault: SiteAuthVault? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val activeCoroutines = ConcurrentHashMap<String, Job>()
    private val activeChapterGroups = ConcurrentHashMap<String, ChapterGroupSession>()
    private val pendingGroupCleanups = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val networkPool = WeightedSlotPool()
    private val computePool = WeightedSlotPool()

    private val settingsState: StateFlow<ForgeSettings> = settingsRepository.settingsFlow
        .stateIn(scope, SharingStarted.Eagerly, ForgeSettings())

    private val _metrics = MutableStateFlow(
        OrchestratorMetrics(
            maxNetworkSlots = 3,
            maxComputeSlots = defaultComputeSlotsForDeviceRam()
        )
    )
    val metrics: StateFlow<OrchestratorMetrics> = _metrics.asStateFlow()

    private val _authRequiredEvents = MutableSharedFlow<AuthRequiredEvent>(extraBufferCapacity = 8)
    val authRequiredEvents: SharedFlow<AuthRequiredEvent> = _authRequiredEvents.asSharedFlow()

    val jobsFlow = jobDao.observeAllJobs()

    private val exportsDir: File
        get() = File(appContext.cacheDir, "exports").apply { if (!exists()) mkdirs() }

    private val jobsWorkRoot: File
        get() = File(appContext.cacheDir, "job_work").apply { if (!exists()) mkdirs() }

    init {
        scope.launch {
            jobDao.resetInterruptedJobs()
            refreshMetrics()
        }
    }

    suspend fun enqueueDownloadBatch(specs: List<PipelineSpec>): List<String> {
        val ids = mutableListOf<String>()
        for (spec in specs) {
            val dl = spec.download ?: continue
            val id = UUID.randomUUID().toString()
            val cleanTitle = dl.url.removePrefix("https://").removePrefix("http://").take(64)
            val entity = ForgeJobEntity(
                id = id,
                title = cleanTitle,
                sourceLabel = dl.url,
                sourceUriString = dl.url,
                mediaKind = MediaKind.REMOTE_URL.name,
                lane = JobLane.NETWORK.name,
                status = JobStatus.QUEUED.name,
                progress = 0f,
                statusText = "Queued for download...",
                pipelineSpecJson = json.encodeToString(spec)
            )
            jobDao.upsert(entity)
            launchJobCoroutine(entity, null, spec)
            ids += id
        }
        return ids
    }

    suspend fun enqueueResolvedLinks(
        chips: List<ResolvedLinkChipItem>,
        saveToDevice: Boolean,
        copyToClipboard: Boolean,
        settings: ForgeSettings
    ): Int {
        var totalQueued = 0
        for (chip in chips) {
            val wantsPlaylist = chip.effectivePlaylistActive
            val wantsChapters = !chip.effectivePlaylistActive &&
                chip.settings.splitByChapters &&
                chip.chapters.size >= 2

            if (wantsPlaylist && chip.tracks.isNotEmpty()) {
                val selectedTracks = chip.tracks.filter { it.selected }
                if (selectedTracks.isEmpty()) continue
                val groupId = "pl_${UUID.randomUUID()}"
                val total = selectedTracks.size
                for ((idx, track) in selectedTracks.withIndex()) {
                    val useDirectTrackUrl = !track.trackUrl.isNullOrBlank()
                    val spec = buildSpecFromLinkSettings(
                        chipUrl = if (useDirectTrackUrl) track.trackUrl!! else chip.cleanUrl,
                        durationSec = track.durationSec ?: chip.durationSec,
                        linkSettings = chip.settings,
                        playlistSingleOnly = useDirectTrackUrl,
                        playlistItemIndex = if (useDirectTrackUrl) null else track.index,
                        saveToDevice = saveToDevice,
                        copyToClipboard = copyToClipboard,
                        globalSettings = settings
                    )
                    val id = UUID.randomUUID().toString()
                    val entity = ForgeJobEntity(
                        id = id,
                        title = track.title,
                        sourceLabel = "${chip.host.ifBlank { "playlist" }} • Track ${idx + 1}/$total",
                        sourceUriString = spec.download?.url ?: chip.cleanUrl,
                        mediaKind = MediaKind.REMOTE_URL.name,
                        lane = JobLane.NETWORK.name,
                        status = JobStatus.QUEUED.name,
                        progress = 0f,
                        statusText = "Queued • ${spec.summaryLabel()}",
                        pipelineSpecJson = json.encodeToString(spec),
                        groupId = groupId,
                        groupTitle = chip.title,
                        playlistIndex = idx + 1,
                        playlistTotal = total,
                        thumbnailUrl = track.thumbnailUrl ?: chip.thumbnailUrl
                    )
                    jobDao.upsert(entity)
                    launchJobCoroutine(entity, null, spec)
                    totalQueued++
                }
            } else if (wantsPlaylist && chip.tracks.isEmpty()) {
                // Playlist not yet resolved: reserve a group expander job and fan out tracks once metadata arrives
                val groupId = "pl_${UUID.randomUUID()}"
                val expanderId = "${groupId}_expander"
                val baseSpec = buildSpecFromLinkSettings(
                    chipUrl = chip.cleanUrl,
                    durationSec = chip.durationSec,
                    linkSettings = chip.settings,
                    playlistSingleOnly = false,
                    playlistItemIndex = null,
                    saveToDevice = saveToDevice,
                    copyToClipboard = copyToClipboard,
                    globalSettings = settings
                )
                val placeholder = ForgeJobEntity(
                    id = expanderId,
                    title = "Resolving playlist tracks...",
                    sourceLabel = "${chip.host} • Fetching tracklist",
                    sourceUriString = chip.cleanUrl,
                    mediaKind = MediaKind.REMOTE_URL.name,
                    lane = JobLane.NETWORK.name,
                    status = JobStatus.DOWNLOADING.name,
                    progress = 5f,
                    statusText = "Fetching playlist tracklist...",
                    pipelineSpecJson = json.encodeToString(baseSpec),
                    groupId = groupId,
                    groupTitle = chip.title,
                    playlistIndex = 1,
                    playlistTotal = 1,
                    thumbnailUrl = chip.thumbnailUrl
                )
                jobDao.upsert(placeholder)
                launchPlaylistExpansionJob(placeholder, chip, saveToDevice, copyToClipboard, settings, groupId)
                totalQueued++
            } else if (wantsChapters) {
                val selectedChapters = chip.chapters.filter { it.selected }
                if (selectedChapters.isEmpty()) continue
                totalQueued += enqueueChapterGroup(
                    chipUrl = chip.cleanUrl,
                    chipHost = chip.host,
                    chipTitle = chip.title,
                    chipUploader = chip.uploader,
                    chipThumbnailUrl = chip.thumbnailUrl,
                    defaultSettings = chip.settings,
                    selectedChapters = selectedChapters,
                    saveToDevice = saveToDevice,
                    copyToClipboard = copyToClipboard,
                    globalSettings = settings
                )
            } else {
                val spec = buildSpecFromLinkSettings(
                    chipUrl = chip.cleanUrl,
                    durationSec = chip.durationSec,
                    linkSettings = chip.settings,
                    playlistSingleOnly = true,
                    playlistItemIndex = null,
                    saveToDevice = saveToDevice,
                    copyToClipboard = copyToClipboard,
                    globalSettings = settings
                )
                val id = UUID.randomUUID().toString()
                val entity = ForgeJobEntity(
                    id = id,
                    title = chip.title,
                    sourceLabel = "${chip.host.ifBlank { "web" }} • ${spec.summaryLabel()}",
                    sourceUriString = chip.cleanUrl,
                    mediaKind = MediaKind.REMOTE_URL.name,
                    lane = JobLane.NETWORK.name,
                    status = JobStatus.QUEUED.name,
                    progress = 0f,
                    statusText = "Queued • ${spec.summaryLabel()}",
                    pipelineSpecJson = json.encodeToString(spec),
                    thumbnailUrl = chip.thumbnailUrl
                )
                jobDao.upsert(entity)
                launchJobCoroutine(entity, null, spec)
                totalQueued++
            }
        }
        return totalQueued
    }

    private suspend fun enqueueChapterGroup(
        chipUrl: String,
        chipHost: String,
        chipTitle: String,
        chipUploader: String?,
        chipThumbnailUrl: String?,
        defaultSettings: LinkItemSettings,
        selectedChapters: List<ChapterTrackItem>,
        saveToDevice: Boolean,
        copyToClipboard: Boolean,
        globalSettings: ForgeSettings
    ): Int {
        if (selectedChapters.isEmpty()) return 0
        val groupId = "ch_${UUID.randomUUID()}"
        val total = selectedChapters.size
        val chapterEntries = ArrayList<ChapterJobEntry>(total)

        for ((idx, chapter) in selectedChapters.withIndex()) {
            val effSettings = chapter.customSettings ?: defaultSettings
            val pos = idx + 1
            val customName = "${padChapterIndex(pos, total)} - ${chapter.title}"
            val chapterSpec = buildChapterPipelineSpec(
                chipUrl = chipUrl,
                chipTitle = chipTitle,
                chipUploader = chipUploader,
                chapter = chapter,
                chapterIndex = pos,
                chapterTotal = total,
                customOutputName = customName,
                effSettings = effSettings,
                saveToDevice = saveToDevice,
                copyToClipboard = copyToClipboard,
                globalSettings = globalSettings
            )
            val entity = ForgeJobEntity(
                id = UUID.randomUUID().toString(),
                title = customName,
                sourceLabel = "${chipHost.ifBlank { "chapters" }} • ${chapter.formattedRange()}",
                sourceUriString = chipUrl,
                mediaKind = MediaKind.REMOTE_URL.name,
                lane = JobLane.NETWORK.name,
                status = JobStatus.QUEUED.name,
                progress = 0f,
                statusText = "Queued • ${chapterSpec.summaryLabel()}",
                pipelineSpecJson = json.encodeToString(chapterSpec),
                groupId = groupId,
                groupTitle = chipTitle,
                playlistIndex = pos,
                playlistTotal = total,
                thumbnailUrl = chipThumbnailUrl
            )
            jobDao.upsert(entity)
            chapterEntries += ChapterJobEntry(entity = entity, spec = chapterSpec, chapter = chapter)
        }

        launchChapterGroupJobs(
            groupId = groupId,
            sourceUrl = chipUrl,
            items = chapterEntries,
            fallbackSettings = defaultSettings
        )
        return chapterEntries.size
    }

    private fun padChapterIndex(pos: Int, total: Int): String {
        val width = total.coerceAtLeast(1).toString().length.coerceAtLeast(2)
        return String.format(Locale.US, "%0${width}d", pos)
    }

    private fun buildChapterPipelineSpec(
        chipUrl: String,
        chipTitle: String,
        chipUploader: String?,
        chapter: ChapterTrackItem,
        chapterIndex: Int,
        chapterTotal: Int,
        customOutputName: String,
        effSettings: LinkItemSettings,
        saveToDevice: Boolean,
        copyToClipboard: Boolean,
        globalSettings: ForgeSettings
    ): PipelineSpec {
        val targetBytes = effSettings.targetSizeMb?.takeIf { it > 0.0 }?.let {
            (it * 1024.0 * 1024.0).roundToLong()
        }
        val compressSpec = if (targetBytes != null) {
            CompressSpec(
                mode = CompressMode.TARGET_BYTES,
                videoCrf = globalSettings.defaultVideoCrf,
                targetBytes = targetBytes,
                tier = effSettings.compressionTier,
                twoPass = true
            )
        } else {
            null
        }

        val convertSpec = if (effSettings.mode == DownloadMode.AUDIO_ONLY) {
            val audioExt = effSettings.audioFormat.lowercase(Locale.US).trim().ifBlank { "mp3" }
            ConvertSpec(
                targetExtension = audioExt,
                audioCodec = audioCodecForExtension(audioExt)
            )
        } else {
            val targetExt = if (effSettings.mode == DownloadMode.VIDEO_BEST_MKV) "mkv" else "mp4"
            val (vCodec, aCodec) = if (targetBytes != null) {
                val vc = when (effSettings.compressionTier) {
                    CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                    CompressionTier.TWO_PASS_HEVC -> VideoCodecChoice.HEVC
                    CompressionTier.COMPATIBLE_H264 -> VideoCodecChoice.H264
                }
                val ac = if (effSettings.compressionTier == CompressionTier.PERCEPTUAL_AV1) {
                    AudioCodecChoice.OPUS
                } else {
                    AudioCodecChoice.AAC
                }
                vc to ac
            } else {
                VideoCodecChoice.H264 to AudioCodecChoice.AAC
            }
            ConvertSpec(
                targetExtension = targetExt,
                videoCodec = vCodec,
                audioCodec = aCodec
            )
        }

        val activeSilence = effSettings.silenceSkip.takeIf { it.enabled }

        return PipelineSpec(
            download = DownloadSpec(
                url = chipUrl,
                playlistSingleOnly = true,
                mode = effSettings.mode,
                maxHeight = effSettings.maxHeight,
                audioFormat = effSettings.audioFormat,
                embedMetadata = effSettings.embedMetadata,
                embedThumbnail = globalSettings.embedDownloadThumbnail && effSettings.embedMetadata,
                sponsorBlock = effSettings.effectiveSponsorBlockCategories.isNotEmpty(),
                useAria2c = globalSettings.useAria2c,
                sponsorBlockCategories = effSettings.sponsorBlockCategories,
                siteConnectionId = effSettings.siteConnectionId?.trim()?.takeIf { it.isNotEmpty() },
                splitByChapters = true
            ),
            convert = convertSpec,
            compress = compressSpec,
            trim = TrimSpec(
                startMs = chapter.startMs,
                endMs = chapter.endMs,
                fastKeyframeCopy = false,
                silenceSkip = activeSilence
            ),
            delivery = DeliverySpec(
                saveToDevice = saveToDevice,
                copyToClipboard = copyToClipboard,
                stripMetadata = effSettings.stripMetadata || (globalSettings.stripMetadataOnClipboard && copyToClipboard && !saveToDevice),
                filenameSuffix = globalSettings.filenameSuffix,
                customOutputBaseName = customOutputName,
                metadataTitle = chapter.title,
                metadataArtist = chipUploader,
                metadataAlbum = chipTitle,
                metadataTrack = "$chapterIndex/$chapterTotal"
            ),
            silenceSkip = activeSilence
        )
    }

    private fun buildSpecFromLinkSettings(
        chipUrl: String,
        durationSec: Long?,
        linkSettings: LinkItemSettings,
        playlistSingleOnly: Boolean,
        playlistItemIndex: Int?,
        saveToDevice: Boolean,
        copyToClipboard: Boolean,
        globalSettings: ForgeSettings
    ): PipelineSpec {
        val targetBytes = linkSettings.targetSizeMb?.takeIf { it > 0.0 }?.let {
            (it * 1024.0 * 1024.0).roundToLong()
        }

        val compressSpec = if (targetBytes != null) {
            CompressSpec(
                mode = CompressMode.TARGET_BYTES,
                videoCrf = globalSettings.defaultVideoCrf,
                targetBytes = targetBytes,
                tier = linkSettings.compressionTier,
                twoPass = true
            )
        } else {
            null
        }

        val convertSpec = if (targetBytes != null) {
            if (linkSettings.mode == DownloadMode.AUDIO_ONLY) {
                ConvertSpec(
                    targetExtension = linkSettings.audioFormat,
                    audioCodec = audioCodecForExtension(linkSettings.audioFormat)
                )
            } else {
                val targetExt = if (linkSettings.mode == DownloadMode.VIDEO_BEST_MKV) "mkv" else "mp4"
                val vCodec = when (linkSettings.compressionTier) {
                    CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                    CompressionTier.TWO_PASS_HEVC -> VideoCodecChoice.HEVC
                    CompressionTier.COMPATIBLE_H264 -> VideoCodecChoice.H264
                }
                val aCodec = if (linkSettings.compressionTier == CompressionTier.PERCEPTUAL_AV1) {
                    AudioCodecChoice.OPUS
                } else {
                    AudioCodecChoice.AAC
                }
                ConvertSpec(targetExtension = targetExt, videoCodec = vCodec, audioCodec = aCodec)
            }
        } else {
            null
        }

        // Smart download ceiling: if a tiny target size is requested, avoid fetching wasteful 4K streams
        val recommendedCap = if (targetBytes != null && linkSettings.mode != DownloadMode.AUDIO_ONLY && durationSec != null && durationSec > 0L) {
            BitrateBudgetCalculator.calculateVideoBudget(
                targetBytes = targetBytes,
                durationMs = durationSec * 1000L,
                sourceWidth = 1920,
                sourceHeight = 1080,
                hasAudio = true,
                isHevc = linkSettings.compressionTier == CompressionTier.TWO_PASS_HEVC,
                videoCodec = convertSpec?.videoCodec ?: VideoCodecChoice.AV1,
                preferOpusAudio = convertSpec?.audioCodec == AudioCodecChoice.OPUS
            ).recommendedDownloadHeightCap
        } else {
            null
        }

        val effectiveMaxHeight = listOfNotNull(linkSettings.maxHeight, recommendedCap).minOrNull()
        val activeSilence = linkSettings.silenceSkip.takeIf { it.enabled }

        return PipelineSpec(
            download = DownloadSpec(
                url = chipUrl,
                playlistSingleOnly = playlistSingleOnly,
                playlistItemIndex = playlistItemIndex,
                mode = linkSettings.mode,
                maxHeight = effectiveMaxHeight,
                audioFormat = linkSettings.audioFormat,
                embedMetadata = linkSettings.embedMetadata,
                embedThumbnail = globalSettings.embedDownloadThumbnail && linkSettings.embedMetadata,
                sponsorBlock = linkSettings.effectiveSponsorBlockCategories.isNotEmpty(),
                useAria2c = globalSettings.useAria2c,
                sponsorBlockCategories = linkSettings.effectiveSponsorBlockCategories,
                siteConnectionId = linkSettings.siteConnectionId?.trim()?.takeIf { it.isNotEmpty() },
                splitByChapters = linkSettings.splitByChapters
            ),
            convert = convertSpec,
            compress = compressSpec,
            delivery = DeliverySpec(
                saveToDevice = saveToDevice,
                copyToClipboard = copyToClipboard,
                stripMetadata = linkSettings.stripMetadata || (globalSettings.stripMetadataOnClipboard && copyToClipboard && !saveToDevice),
                filenameSuffix = globalSettings.filenameSuffix
            ),
            silenceSkip = activeSilence
        )
    }

    private fun launchPlaylistExpansionJob(
        placeholder: ForgeJobEntity,
        chip: ResolvedLinkChipItem,
        saveToDevice: Boolean,
        copyToClipboard: Boolean,
        settings: ForgeSettings,
        groupId: String
    ) {
        launchPlaylistExpansionCore(
            placeholder = placeholder,
            cleanUrl = chip.cleanUrl,
            host = chip.host,
            fallbackUploader = chip.uploader,
            fallbackDurationSec = chip.durationSec,
            fallbackThumbnailUrl = chip.thumbnailUrl,
            linkSettings = chip.settings,
            saveToDevice = saveToDevice,
            copyToClipboard = copyToClipboard,
            settings = settings,
            groupId = groupId
        )
    }

    private fun launchPlaylistExpansionCore(
        placeholder: ForgeJobEntity,
        cleanUrl: String,
        host: String,
        fallbackUploader: String?,
        fallbackDurationSec: Long?,
        fallbackThumbnailUrl: String?,
        linkSettings: LinkItemSettings,
        saveToDevice: Boolean,
        copyToClipboard: Boolean,
        settings: ForgeSettings,
        groupId: String
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val explicitSiteId = linkSettings.siteConnectionId?.trim()?.takeIf { it.isNotEmpty() }
                val meta = ytDlpEngine.resolveMetadata(
                    url = cleanUrl,
                    cancelKey = placeholder.id,
                    siteConnectionId = explicitSiteId
                )
                jobDao.deleteById(placeholder.id)

                val resolvedTitle = meta.title.ifBlank { placeholder.groupTitle ?: "Media" }
                val tracks = meta.tracks.ifEmpty { emptyList() }
                val metaSelectedChapters = meta.chapters.filter { it.selected }
                if (tracks.isEmpty() && linkSettings.splitByChapters && metaSelectedChapters.size >= 2) {
                    enqueueChapterGroup(
                        chipUrl = cleanUrl,
                        chipHost = host,
                        chipTitle = resolvedTitle,
                        chipUploader = meta.uploader ?: fallbackUploader,
                        chipThumbnailUrl = meta.thumbnailUrl ?: fallbackThumbnailUrl,
                        defaultSettings = linkSettings,
                        selectedChapters = metaSelectedChapters,
                        saveToDevice = saveToDevice,
                        copyToClipboard = copyToClipboard,
                        globalSettings = settings
                    )
                } else if (tracks.isEmpty()) {
                    val spec = buildSpecFromLinkSettings(
                        chipUrl = cleanUrl,
                        durationSec = meta.durationSec ?: fallbackDurationSec,
                        linkSettings = linkSettings,
                        playlistSingleOnly = true,
                        playlistItemIndex = null,
                        saveToDevice = saveToDevice,
                        copyToClipboard = copyToClipboard,
                        globalSettings = settings
                    )
                    val single = placeholder.copy(
                        id = UUID.randomUUID().toString(),
                        title = resolvedTitle,
                        sourceLabel = "$host • ${spec.summaryLabel()}",
                        status = JobStatus.QUEUED.name,
                        progress = 0f,
                        statusText = "Queued • ${spec.summaryLabel()}",
                        groupId = null,
                        groupTitle = null,
                        playlistIndex = null,
                        playlistTotal = null,
                        thumbnailUrl = meta.thumbnailUrl ?: fallbackThumbnailUrl
                    )
                    jobDao.upsert(single)
                    launchJobCoroutine(single, null, spec)
                } else {
                    val total = tracks.size
                    for ((idx, track) in tracks.withIndex()) {
                        val useDirectUrl = !track.trackUrl.isNullOrBlank()
                        val spec = buildSpecFromLinkSettings(
                            chipUrl = if (useDirectUrl) track.trackUrl!! else cleanUrl,
                            durationSec = track.durationSec ?: fallbackDurationSec,
                            linkSettings = linkSettings,
                            playlistSingleOnly = useDirectUrl,
                            playlistItemIndex = if (useDirectUrl) null else track.index,
                            saveToDevice = saveToDevice,
                            copyToClipboard = copyToClipboard,
                            globalSettings = settings
                        )
                        val trackEntity = ForgeJobEntity(
                            id = UUID.randomUUID().toString(),
                            title = track.title,
                            sourceLabel = "$host • Track ${idx + 1}/$total",
                            sourceUriString = spec.download?.url ?: cleanUrl,
                            mediaKind = MediaKind.REMOTE_URL.name,
                            lane = JobLane.NETWORK.name,
                            status = JobStatus.QUEUED.name,
                            progress = 0f,
                            statusText = "Queued • ${spec.summaryLabel()}",
                            pipelineSpecJson = json.encodeToString(spec),
                            groupId = groupId,
                            groupTitle = resolvedTitle,
                            playlistIndex = idx + 1,
                            playlistTotal = total,
                            thumbnailUrl = track.thumbnailUrl ?: meta.thumbnailUrl ?: fallbackThumbnailUrl
                        )
                        jobDao.upsert(trackEntity)
                        launchJobCoroutine(trackEntity, null, spec)
                    }
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    jobDao.markTerminalState(placeholder.id, JobStatus.CANCELED.name, "Canceled", null)
                }
            } catch (ex: AuthRequiredException) {
                withContext(NonCancellable) {
                    handleAuthRequiredFailure(placeholder.id, cleanUrl, ex)
                }
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    val authEx = classifyAuthFailure(cleanUrl, t)
                    if (authEx != null) {
                        handleAuthRequiredFailure(placeholder.id, cleanUrl, authEx)
                    } else {
                        jobDao.markTerminalState(placeholder.id, JobStatus.FAILED.name, "Playlist resolve failed", t.message)
                    }
                }
            } finally {
                activeCoroutines.remove(placeholder.id)
                refreshMetrics()
            }
        }
        activeCoroutines[placeholder.id] = job
        refreshMetrics()
        if (!job.start()) {
            activeCoroutines.remove(placeholder.id, job)
            refreshMetrics()
        }
    }

    suspend fun enqueueLocalBatch(items: List<Pair<MediaProbeResult, PipelineSpec>>): List<String> {
        val ids = mutableListOf<String>()
        for ((probe, spec) in items) {
            val id = UUID.randomUUID().toString()
            val entity = ForgeJobEntity(
                id = id,
                title = probe.displayName,
                sourceLabel = "${probe.displayResolution()} • ${probe.formattedSize()}",
                sourceUriString = probe.uriString,
                mediaKind = probe.mediaKind.name,
                lane = JobLane.COMPUTE.name,
                status = JobStatus.QUEUED.name,
                progress = 0f,
                statusText = "Queued for processing...",
                pipelineSpecJson = json.encodeToString(spec),
                inputBytes = probe.fileSizeBytes
            )
            jobDao.upsert(entity)
            launchJobCoroutine(entity, probe, spec)
            ids += id
        }
        return ids
    }

    suspend fun retryJob(jobId: String) {
        val existing = jobDao.getById(jobId) ?: return
        if (activeCoroutines.containsKey(jobId)) return
        val spec = runCatching { json.decodeFromString<PipelineSpec>(existing.pipelineSpecJson) }.getOrNull() ?: return
        val reset = existing.copy(
            status = JobStatus.QUEUED.name,
            progress = 0f,
            statusText = "Retrying...",
            errorMessage = null,
            updatedAtMs = System.currentTimeMillis()
        )
        jobDao.upsert(reset)

        val groupId = existing.groupId
        if (groupId != null && groupId.startsWith("pl_") && existing.id.endsWith("_expander") && spec.download != null) {
            val expanding = reset.copy(
                status = JobStatus.DOWNLOADING.name,
                progress = 5f,
                statusText = "Fetching playlist tracklist..."
            )
            jobDao.upsert(expanding)
            val dl = spec.download
            val linkSettings = LinkItemSettings(
                mode = dl.mode,
                maxHeight = dl.maxHeight,
                audioFormat = dl.audioFormat,
                embedMetadata = dl.embedMetadata,
                sponsorBlock = dl.effectiveSponsorBlockCategories.isNotEmpty(),
                sponsorBlockCategories = dl.effectiveSponsorBlockCategories,
                targetSizeMb = spec.compress?.targetBytes?.let { it.toDouble() / (1024.0 * 1024.0) },
                compressionTier = spec.compress?.tier ?: CompressionTier.PERCEPTUAL_AV1,
                playlistSingleOnly = dl.playlistSingleOnly,
                splitByChapters = dl.splitByChapters,
                siteConnectionId = dl.siteConnectionId,
                stripMetadata = spec.delivery.stripMetadata,
                silenceSkip = spec.silenceSkip ?: SilenceSkipSpec()
            )
            val cleanUrl = dl.url.ifBlank { existing.sourceUriString.orEmpty() }
            val host = ServiceCatalog.extractHost(cleanUrl)
                ?: existing.sourceLabel.substringBefore(" •").trim().ifBlank { "playlist" }
            launchPlaylistExpansionCore(
                placeholder = expanding,
                cleanUrl = cleanUrl,
                host = host,
                fallbackUploader = null,
                fallbackDurationSec = null,
                fallbackThumbnailUrl = existing.thumbnailUrl,
                linkSettings = linkSettings,
                saveToDevice = spec.delivery.saveToDevice,
                copyToClipboard = spec.delivery.copyToClipboard,
                settings = settingsState.value,
                groupId = groupId
            )
            return
        }
        if (groupId != null && groupId.startsWith("ch_") && spec.download != null) {
            val entry = ChapterJobEntry(entity = reset, spec = spec)
            val activeSession = activeChapterGroups[groupId]
            if (activeSession != null && attachSiblingToSession(activeSession, entry)) {
                return
            }
            val sourceUrl = spec.download.url.ifBlank { existing.sourceUriString.orEmpty() }
            launchChapterGroupJobs(groupId, sourceUrl, listOf(entry), null)
            return
        }

        launchJobCoroutine(reset, null, spec)
    }

    fun cancelJob(jobId: String) {
        activeCoroutines.remove(jobId)?.cancel()
        val matchingSession = activeChapterGroups.values.firstOrNull { it.trackedJobIds.remove(jobId) }
        scope.launch {
            if (matchingSession != null) {
                matchingSession.progressDbMutex.withLock {
                    jobDao.markTerminalState(jobId, JobStatus.CANCELED.name, "Canceled", null)
                }
            } else {
                jobDao.markTerminalState(jobId, JobStatus.CANCELED.name, "Canceled", null)
            }
            refreshMetrics()
        }
    }

    suspend fun removeJob(jobId: String) {
        activeCoroutines.remove(jobId)?.cancel()
        activeChapterGroups.values.forEach { it.trackedJobIds.remove(jobId) }
        val job = jobDao.getById(jobId)
        job?.outputPath?.let { runCatching { File(it).delete() } }
        runCatching { jobExportDir(jobId).deleteRecursively() }
        runCatching { File(jobsWorkRoot, jobId).deleteRecursively() }
        jobDao.deleteById(jobId)
        refreshMetrics()
    }

    fun cancelPlaylistGroup(groupId: String) {
        val session = activeChapterGroups[groupId]
        if (session != null) {
            session.trackedJobIds.toList().forEach { id ->
                session.trackedJobIds.remove(id)
                activeCoroutines.remove(id)?.cancel()
            }
            session.sharedDownloadJob.cancel()
        }
        scope.launch {
            val groupJobs = jobDao.getByGroupId(groupId)
            for (job in groupJobs) {
                session?.trackedJobIds?.remove(job.id)
                activeCoroutines.remove(job.id)?.cancel()
                if (job.isActive) {
                    if (session != null) {
                        session.progressDbMutex.withLock {
                            jobDao.markTerminalState(job.id, JobStatus.CANCELED.name, "Canceled", null)
                        }
                    } else {
                        jobDao.markTerminalState(job.id, JobStatus.CANCELED.name, "Canceled", null)
                    }
                }
            }
            refreshMetrics()
        }
    }

    suspend fun removePlaylistGroup(groupId: String) {
        val session = activeChapterGroups[groupId]
        if (session != null) {
            session.trackedJobIds.toList().forEach { id ->
                session.trackedJobIds.remove(id)
                activeCoroutines.remove(id)?.cancel()
            }
            session.sharedDownloadJob.cancel()
        }
        val groupJobs = jobDao.getByGroupId(groupId)
        for (job in groupJobs) {
            activeCoroutines.remove(job.id)?.cancel()
            job.outputPath?.let { runCatching { File(it).delete() } }
            runCatching { jobExportDir(job.id).deleteRecursively() }
            runCatching { File(jobsWorkRoot, job.id).deleteRecursively() }
        }
        pendingGroupCleanups[groupId]?.let { cleanup ->
            runCatching { cleanup.await() }
        }
        runCatching { File(jobsWorkRoot, groupId).deleteRecursively() }
        jobDao.deleteByGroupId(groupId)
        refreshMetrics()
    }

    suspend fun retryPlaylistGroup(groupId: String) {
        val groupJobs = jobDao.getByGroupId(groupId)
        val retryable = groupJobs.filter {
            (it.statusEnum == JobStatus.FAILED || it.statusEnum == JobStatus.CANCELED) &&
                !activeCoroutines.containsKey(it.id)
        }
        if (retryable.isEmpty()) return

        if (groupId.startsWith("ch_")) {
            val entries = mutableListOf<ChapterJobEntry>()
            for (job in retryable) {
                val spec = runCatching { json.decodeFromString<PipelineSpec>(job.pipelineSpecJson) }.getOrNull() ?: continue
                if (spec.download == null) continue
                val reset = job.copy(
                    status = JobStatus.QUEUED.name,
                    progress = 0f,
                    statusText = "Retrying...",
                    errorMessage = null,
                    updatedAtMs = System.currentTimeMillis()
                )
                jobDao.upsert(reset)
                entries += ChapterJobEntry(entity = reset, spec = spec)
            }
            if (entries.isEmpty()) return

            val unattached = mutableListOf<ChapterJobEntry>()
            val activeSession = activeChapterGroups[groupId]
            if (activeSession != null) {
                for (entry in entries) {
                    if (!attachSiblingToSession(activeSession, entry)) {
                        unattached += entry
                    }
                }
            } else {
                unattached += entries
            }

            if (unattached.isNotEmpty()) {
                val sourceUrl = unattached.first().spec.download?.url
                    ?.takeIf { it.isNotBlank() }
                    ?: unattached.first().entity.sourceUriString.orEmpty()
                launchChapterGroupJobs(groupId, sourceUrl, unattached, null)
            }
            return
        }

        for (job in retryable) {
            retryJob(job.id)
        }
    }

    fun cancelAll() {
        val keys = activeCoroutines.keys.toList()
        keys.forEach { key ->
            activeCoroutines.remove(key)?.cancel()
        }
        activeChapterGroups.values.forEach { session ->
            session.trackedJobIds.clear()
            session.sharedDownloadJob.cancel()
        }
        scope.launch {
            jobDao.resetInterruptedJobs()
            refreshMetrics()
            ForgeForegroundService.stop(appContext)
        }
    }

    suspend fun clearFinishedJobs() {
        jobDao.clearFinishedJobs()
        val activeJobs = jobDao.observeAllJobs().first()
        val keptPaths = activeJobs.mapNotNull { it.outputPath }.toSet()
        val keptJobDirs = activeJobs.map { jobExportDir(it.id).absolutePath }.toSet()
        exportsDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in keptJobDirs && file.absolutePath !in keptPaths) {
                file.deleteRecursively()
            }
        }
    }

    suspend fun copyCompletedJobToClipboard(jobId: String): Boolean {
        val job = jobDao.getById(jobId) ?: return false
        val path = job.outputPath ?: return false
        val file = File(path)
        if (!file.exists()) return false
        clipboardExportManager.copyFileToClipboard(file, file.name)
        return true
    }

    fun clearAllCaches(): Long {
        var cleared = 0L
        listOf(
            exportsDir,
            jobsWorkRoot,
            File(appContext.cacheDir, "probe_staging"),
            File(appContext.cacheDir, "sample_preview"),
            File(appContext.cacheDir, "sample_lab")
        ).forEach { dir ->
            cleared += directorySizeBytes(dir)
            dir.deleteRecursively()
        }
        val updatesDir = File(appContext.cacheDir, "updates")
        if (updatesDir.exists()) {
            updatesDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.endsWith(".part")) {
                    val len = file.length()
                    if (file.delete()) cleared += len
                }
            }
        }
        cleared += clipboardExportManager.clipboardCacheSizeBytes()
        clipboardExportManager.clearClipboardStaging()
        return cleared
    }

    fun totalCacheBytes(): Long {
        return directorySizeBytes(exportsDir) +
            directorySizeBytes(jobsWorkRoot) +
            directorySizeBytes(File(appContext.cacheDir, "probe_staging")) +
            directorySizeBytes(File(appContext.cacheDir, "sample_preview")) +
            directorySizeBytes(File(appContext.cacheDir, "sample_lab")) +
            directorySizeBytes(File(appContext.cacheDir, "updates")) +
            clipboardExportManager.clipboardCacheSizeBytes()
    }

    private fun launchJobCoroutine(
        initialEntity: ForgeJobEntity,
        preProbed: MediaProbeResult?,
        spec: PipelineSpec
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val workDir = File(jobsWorkRoot, initialEntity.id).apply { mkdirs() }
            try {
                if (spec.download != null) {
                    runDownloadAndTransformJob(initialEntity, spec, workDir)
                } else {
                    runLocalTransformJob(initialEntity, preProbed, spec, workDir)
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    jobDao.markTerminalState(initialEntity.id, JobStatus.CANCELED.name, "Canceled", null)
                }
            } catch (ex: AuthRequiredException) {
                withContext(NonCancellable) {
                    val targetUrl = spec.download?.url ?: initialEntity.sourceUriString.orEmpty()
                    handleAuthRequiredFailure(initialEntity.id, targetUrl, ex)
                }
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    val targetUrl = spec.download?.url ?: initialEntity.sourceUriString.orEmpty()
                    val authEx = if (spec.download != null) classifyAuthFailure(targetUrl, t) else null
                    if (authEx != null) {
                        handleAuthRequiredFailure(initialEntity.id, targetUrl, authEx)
                    } else {
                        jobDao.markTerminalState(
                            id = initialEntity.id,
                            status = JobStatus.FAILED.name,
                            statusText = "Failed",
                            errorMessage = t.message ?: t::class.java.simpleName
                        )
                    }
                }
            } finally {
                workDir.deleteRecursively()
                activeCoroutines.remove(initialEntity.id)
                refreshMetrics()
            }
        }
        activeCoroutines[initialEntity.id] = job
        refreshMetrics()
        if (!job.start()) {
            activeCoroutines.remove(initialEntity.id, job)
            refreshMetrics()
        }
    }

    private suspend fun launchChapterGroupJobs(
        groupId: String,
        sourceUrl: String,
        items: List<ChapterJobEntry>,
        fallbackSettings: LinkItemSettings?
    ) {
        if (items.isEmpty()) return
        pendingGroupCleanups[groupId]?.await()

        val groupWorkDir = File(jobsWorkRoot, groupId).apply { mkdirs() }
        val sharedDownloadSpec = buildSharedChapterDownloadSpec(sourceUrl, items, fallbackSettings)
        val session = ChapterGroupSession(
            groupId = groupId,
            sourceUrl = sourceUrl,
            groupWorkDir = groupWorkDir,
            totalBatchSize = items.size,
            remainingSiblings = AtomicInteger(items.size)
        )
        items.forEach { session.trackedJobIds.add(it.entity.id) }
        pendingGroupCleanups[groupId] = session.cleanupDone
        activeChapterGroups[groupId] = session

        session.sharedDownloadJob = scope.launch(start = CoroutineStart.LAZY) {
            val progressChannel = Channel<Pair<Float, String>>(Channel.CONFLATED)
            val progressPump = launch {
                for ((pct, msg) in progressChannel) {
                    val mappedPct = (pct * 0.48f).coerceIn(0f, 48f)
                    session.progressDbMutex.withLock {
                        for (id in session.trackedJobIds) {
                            if (activeCoroutines.containsKey(id) && !session.sharedSourceDeferred.isCompleted) {
                                jobDao.updateProgress(
                                    id = id,
                                    status = JobStatus.DOWNLOADING.name,
                                    statusText = msg,
                                    progress = mappedPct,
                                    lane = JobLane.NETWORK.name
                                )
                            }
                        }
                    }
                    delay(150L)
                }
            }

            try {
                val maxNet = { settingsState.value.maxConcurrentDownloads.coerceIn(1, 5) }
                val sharedDlDir = File(session.groupWorkDir, "shared_dl").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val downloadedFiles = networkPool.withSlots(
                    cost = 1,
                    capacityProvider = maxNet,
                    onPoolChange = { refreshMetrics() }
                ) {
                    session.progressDbMutex.withLock {
                        for (id in session.trackedJobIds) {
                            if (activeCoroutines.containsKey(id)) {
                                jobDao.updateProgress(
                                    id = id,
                                    status = JobStatus.DOWNLOADING.name,
                                    statusText = "Starting shared download...",
                                    progress = 1f,
                                    lane = JobLane.NETWORK.name
                                )
                            }
                        }
                    }
                    refreshMetrics()
                    ytDlpEngine.download(
                        jobId = groupId,
                        spec = sharedDownloadSpec,
                        workDir = sharedDlDir
                    ) { pct, msg ->
                        progressChannel.trySend(pct to msg)
                    }
                }

                progressChannel.close()
                progressPump.cancelAndJoin()

                val sharedFile = downloadedFiles.firstOrNull()
                    ?: throw IOException("Shared chapter download produced no output file")

                session.progressDbMutex.withLock {
                    for (id in session.trackedJobIds) {
                        if (activeCoroutines.containsKey(id)) {
                            jobDao.updateProgress(
                                id = id,
                                status = JobStatus.DOWNLOADING.name,
                                statusText = "Probing source chapters...",
                                progress = 48f,
                                lane = JobLane.NETWORK.name
                            )
                        }
                    }
                }

                val sharedProbe = probeEngine.probeLocalFile(sharedFile, sourceUrl)
                session.sharedSourceDeferred.complete(SharedChapterSource(sharedFile, sharedProbe))
            } catch (ce: CancellationException) {
                progressChannel.close()
                withContext(NonCancellable) { progressPump.cancelAndJoin() }
                session.sharedDownloadFailed.set(true)
                session.sharedSourceDeferred.cancel(ce)
                throw ce
            } catch (t: Throwable) {
                progressChannel.close()
                withContext(NonCancellable) { progressPump.cancelAndJoin() }
                session.sharedDownloadFailed.set(true)
                session.sharedSourceDeferred.completeExceptionally(t)
            }
        }

        for (entry in items) {
            launchChapterSiblingCoroutine(session, entry)
        }
        session.sharedDownloadJob.start()
    }

    private fun launchChapterSiblingCoroutine(
        session: ChapterGroupSession,
        entry: ChapterJobEntry
    ) {
        val entity = entry.entity
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val chapterWorkDir = File(session.groupWorkDir, "ch_${entity.id}").apply { mkdirs() }
            try {
                val shared = session.sharedSourceDeferred.await()
                currentCoroutineContext().ensureActive()

                val baseTrim = entry.spec.trim
                val rawEndMs = entry.chapter?.endMs ?: baseTrim?.endMs
                val adjustedTrim = if (
                    baseTrim != null &&
                    rawEndMs != null &&
                    shared.probe.durationMs > 0L &&
                    rawEndMs >= shared.probe.durationMs - 500L
                ) {
                    baseTrim.copy(endMs = null)
                } else {
                    baseTrim
                }
                val effectiveSpec = entry.spec.copy(trim = adjustedTrim)

                val effectiveMediaKind = if (effectiveSpec.download?.mode == DownloadMode.AUDIO_ONLY) {
                    MediaKind.AUDIO
                } else {
                    shared.probe.mediaKind
                }
                val weight = computeSlotWeight(effectiveMediaKind)
                val maxCompute = { effectiveMaxComputeSlots() }

                val finalFile = computePool.withSlots(
                    cost = weight,
                    capacityProvider = maxCompute,
                    onPoolChange = { refreshMetrics() }
                ) {
                    val idxLabel = entity.playlistIndex ?: 1
                    val totalLabel = entity.playlistTotal ?: 1
                    jobDao.updateProgress(
                        id = entity.id,
                        status = JobStatus.PROCESSING.name,
                        statusText = "Slicing chapter $idxLabel/$totalLabel...",
                        progress = 50f,
                        lane = JobLane.COMPUTE.name
                    )
                    refreshMetrics()

                    val sliceChannel = Channel<Pair<Float, String>>(Channel.CONFLATED)
                    val slicePump = launch {
                        for ((subPct, msg) in sliceChannel) {
                            if (!activeCoroutines.containsKey(entity.id)) break
                            val mapped = (50f + (subPct * 0.48f)).coerceIn(50f, 98f)
                            jobDao.updateProgress(
                                id = entity.id,
                                status = JobStatus.PROCESSING.name,
                                statusText = msg,
                                progress = mapped,
                                lane = JobLane.COMPUTE.name
                            )
                            delay(150L)
                        }
                    }
                    try {
                        transformLocalFile(
                            jobId = entity.id,
                            sourceUri = Uri.fromFile(shared.file),
                            stagedInput = shared.file,
                            probe = shared.probe,
                            spec = effectiveSpec,
                            workDir = chapterWorkDir
                        ) { subPct, msg ->
                            sliceChannel.trySend(subPct to msg)
                        }
                    } finally {
                        sliceChannel.close()
                        withContext(NonCancellable) {
                            slicePump.cancelAndJoin()
                        }
                    }
                }

                val outputName = buildOutputFilename(shared.file.name, finalFile.extension, effectiveSpec)
                val exportedCopy = stageExportFile(entity.id, finalFile, outputName)
                val outBytes = exportedCopy.length()
                var finalUri: Uri? = null
                var finalMime = mediaStoreExporter.extensionToMime(exportedCopy.extension)

                if (effectiveSpec.delivery.saveToDevice) {
                    val saved = mediaStoreExporter.exportToDevice(exportedCopy, exportedCopy.name)
                    finalUri = saved.contentUri
                    finalMime = saved.mimeType
                }
                if (effectiveSpec.delivery.copyToClipboard) {
                    val clipUri = clipboardExportManager.copyFileToClipboard(exportedCopy, exportedCopy.name)
                    if (finalUri == null) finalUri = clipUri
                }
                if (finalUri == null) {
                    finalUri = FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        exportedCopy
                    )
                }

                val chapterDurationMs = ((rawEndMs ?: shared.probe.durationMs) - (baseTrim?.startMs ?: 0L))
                    .coerceAtLeast(0L)
                val approxInputBytes = if (shared.probe.durationMs > 0L && chapterDurationMs > 0L) {
                    val share = (chapterDurationMs.toDouble() / shared.probe.durationMs.toDouble()).coerceIn(0.0, 1.0)
                    (shared.file.length().toDouble() * share).roundToLong().coerceAtLeast(1L)
                } else {
                    (shared.file.length() / session.totalBatchSize.coerceAtLeast(1)).coerceAtLeast(1L)
                }

                val doneMsg = buildDoneMessage(effectiveSpec, approxInputBytes, outBytes, 1)
                jobDao.markCompleted(
                    id = entity.id,
                    statusText = doneMsg,
                    inputBytes = approxInputBytes,
                    outputBytes = outBytes,
                    outputPath = exportedCopy.absolutePath,
                    outputUriString = finalUri.toString(),
                    outputMimeType = finalMime
                )
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    session.progressDbMutex.withLock {
                        session.trackedJobIds.remove(entity.id)
                        jobDao.markTerminalState(entity.id, JobStatus.CANCELED.name, "Canceled", null)
                    }
                }
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    session.trackedJobIds.remove(entity.id)
                    if (!currentCoroutineContext().isActive) {
                        session.progressDbMutex.withLock {
                            jobDao.markTerminalState(entity.id, JobStatus.CANCELED.name, "Canceled", null)
                        }
                    } else {
                        val authEx = classifyAuthFailure(session.sourceUrl, t)
                        if (authEx != null) {
                            val emit = session.authEventEmitted.compareAndSet(false, true)
                            handleAuthRequiredFailure(
                                jobId = entity.id,
                                url = session.sourceUrl,
                                ex = authEx,
                                emitEvent = emit
                            )
                        } else {
                            jobDao.markTerminalState(
                                id = entity.id,
                                status = JobStatus.FAILED.name,
                                statusText = "Failed",
                                errorMessage = t.message ?: t::class.java.simpleName
                            )
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    runCatching { chapterWorkDir.deleteRecursively() }
                    activeCoroutines.remove(entity.id)
                    session.trackedJobIds.remove(entity.id)
                    decrementAndCleanupIfLast(session)
                    refreshMetrics()
                }
            }
        }
        activeCoroutines[entity.id] = job
        refreshMetrics()
        if (!job.start()) {
            activeCoroutines.remove(entity.id, job)
            session.trackedJobIds.remove(entity.id)
            scope.launch {
                decrementAndCleanupIfLast(session)
                refreshMetrics()
            }
        }
    }

    private suspend fun attachSiblingToSession(
        session: ChapterGroupSession,
        entry: ChapterJobEntry
    ): Boolean {
        if (session.sharedDownloadFailed.get()) return false
        while (true) {
            val cur = session.remainingSiblings.get()
            if (cur <= 0) return false
            if (session.remainingSiblings.compareAndSet(cur, cur + 1)) break
        }
        if (session.sharedDownloadFailed.get()) {
            decrementAndCleanupIfLast(session)
            return false
        }
        session.trackedJobIds.add(entry.entity.id)
        launchChapterSiblingCoroutine(session, entry)
        return true
    }

    private suspend fun decrementAndCleanupIfLast(session: ChapterGroupSession) {
        val remaining = session.remainingSiblings.decrementAndGet()
        if (remaining == 0) {
            activeChapterGroups.remove(session.groupId, session)
            try {
                session.sharedDownloadJob.cancelAndJoin()
                runCatching { session.groupWorkDir.deleteRecursively() }
            } finally {
                session.cleanupDone.complete(Unit)
                pendingGroupCleanups.remove(session.groupId, session.cleanupDone)
            }
        }
    }

    private fun buildSharedChapterDownloadSpec(
        sourceUrl: String,
        items: List<ChapterJobEntry>,
        fallbackSettings: LinkItemSettings?
    ): DownloadSpec {
        val downloadSpecs = items.mapNotNull { it.spec.download }
        val videoSpecs = downloadSpecs.filter { it.mode != DownloadMode.AUDIO_ONLY }
        val anyVideo = videoSpecs.isNotEmpty()

        val sharedMode: DownloadMode
        val sharedMaxHeight: Int?
        val sharedAudioFormat: String
        if (anyVideo) {
            val allVideoAreMp4 = videoSpecs.all { it.mode == DownloadMode.VIDEO_MP4 }
            sharedMode = if (allVideoAreMp4) DownloadMode.VIDEO_MP4 else DownloadMode.VIDEO_BEST_MKV
            sharedMaxHeight = if (videoSpecs.any { it.maxHeight == null }) {
                null
            } else {
                videoSpecs.mapNotNull { it.maxHeight }.maxOrNull()
            }
            sharedAudioFormat = "m4a"
        } else {
            sharedMode = DownloadMode.AUDIO_ONLY
            sharedMaxHeight = null
            val distinctFormats = downloadSpecs
                .map { it.audioFormat.lowercase(Locale.US).trim().ifBlank { "mp3" } }
                .distinct()
            sharedAudioFormat = if (distinctFormats.size == 1) {
                distinctFormats.first()
            } else {
                "flac"
            }
        }

        // Disable SponsorBlock cuts when splitting by pre-resolved chapters so chapter timestamps never drift.
        val siteConnectionId = downloadSpecs
            .firstNotNullOfOrNull { it.siteConnectionId?.trim()?.takeIf { id -> id.isNotEmpty() } }
            ?: fallbackSettings?.siteConnectionId?.trim()?.takeIf { it.isNotEmpty() }

        return DownloadSpec(
            url = sourceUrl,
            playlistSingleOnly = true,
            playlistItemIndex = null,
            mode = sharedMode,
            maxHeight = sharedMaxHeight,
            audioFormat = sharedAudioFormat,
            embedMetadata = downloadSpecs.any { it.embedMetadata } || (fallbackSettings?.embedMetadata == true),
            embedThumbnail = downloadSpecs.any { it.embedThumbnail },
            sponsorBlock = false,
            useAria2c = downloadSpecs.any { it.useAria2c } || settingsState.value.useAria2c,
            sponsorBlockCategories = emptySet(),
            siteConnectionId = siteConnectionId,
            splitByChapters = true
        )
    }

    private fun classifyAuthFailure(url: String, error: Throwable): AuthRequiredException? {
        if (error is AuthRequiredException) return error
        val msg = listOfNotNull(error.message, error.cause?.message).joinToString("\n")
        return AuthErrorClassifier.classifyYtDlpError(
            url = url,
            rawStderr = msg,
            customDomains = siteAuthVault?.connectedCustomDomains().orEmpty()
        )
    }

    private suspend fun handleAuthRequiredFailure(
        jobId: String,
        url: String,
        ex: AuthRequiredException,
        emitEvent: Boolean = true
    ) {
        val statusText = "Sign-in required (${ex.service?.displayName ?: "Site"})"
        jobDao.markTerminalState(
            id = jobId,
            status = JobStatus.FAILED.name,
            statusText = statusText,
            errorMessage = ex.userFacingReason
        )
        if (emitEvent) {
            _authRequiredEvents.emit(AuthRequiredEvent(jobId = jobId, url = url, exception = ex))
        }
    }

    private suspend fun runDownloadAndTransformJob(
        entity: ForgeJobEntity,
        spec: PipelineSpec,
        workDir: File
    ) {
        val dlSpec = spec.download ?: return
        val maxNet = { settingsState.value.maxConcurrentDownloads.coerceIn(1, 5) }

        val downloadedFiles = networkPool.withSlots(cost = 1, capacityProvider = maxNet, onPoolChange = { refreshMetrics() }) {
            jobDao.updateProgress(entity.id, JobStatus.DOWNLOADING.name, "Starting yt-dlp...", 2f, JobLane.NETWORK.name)
            refreshMetrics()
            ytDlpEngine.download(entity.id, dlSpec, File(workDir, "dl")) { pct, msg ->
                scope.launch {
                    if (!activeCoroutines.containsKey(entity.id)) return@launch
                    val mappedPct = if (spec.hasTransformStage) pct * 0.55f else pct
                    jobDao.updateProgress(entity.id, JobStatus.DOWNLOADING.name, msg, mappedPct, JobLane.NETWORK.name)
                }
            }
        }

        val totalInputBytes = downloadedFiles.sumOf { it.length() }
        var totalOutputBytes = 0L
        var lastDeliverable: File? = null
        var lastSavedUri: Uri? = null
        var lastMime: String? = null

        for ((index, downloaded) in downloadedFiles.withIndex()) {
            val finalFile: File
            val probe = probeEngine.probeLocalFile(downloaded, dlSpec.url)

            if (spec.hasTransformStage) {
                val weight = computeSlotWeight(probe.mediaKind)
                val maxCompute = { effectiveMaxComputeSlots() }
                finalFile = computePool.withSlots(cost = weight, capacityProvider = maxCompute, onPoolChange = { refreshMetrics() }) {
                    val baseProgress = 55f
                    jobDao.updateProgress(
                        entity.id,
                        JobStatus.PROCESSING.name,
                        "Perceptual forge: ${downloaded.name}...",
                        baseProgress,
                        JobLane.COMPUTE.name
                    )
                    refreshMetrics()
                    transformLocalFile(
                        jobId = "${entity.id}_$index",
                        sourceUri = Uri.fromFile(downloaded),
                        stagedInput = downloaded,
                        probe = probe,
                        spec = spec,
                        workDir = workDir
                    ) { subPct, msg ->
                        scope.launch {
                            if (!activeCoroutines.containsKey(entity.id)) return@launch
                            jobDao.updateProgress(
                                entity.id,
                                JobStatus.PROCESSING.name,
                                msg,
                                55f + (subPct * 0.43f),
                                JobLane.COMPUTE.name
                            )
                        }
                    }
                }
            } else {
                finalFile = downloaded
            }

            val exportedCopy = stageExportFile(entity.id, finalFile, buildOutputFilename(downloaded.name, finalFile.extension, spec))
            totalOutputBytes += exportedCopy.length()
            lastDeliverable = exportedCopy
            lastMime = mediaStoreExporter.extensionToMime(exportedCopy.extension)

            if (spec.delivery.saveToDevice) {
                val saved = mediaStoreExporter.exportToDevice(exportedCopy, exportedCopy.name)
                lastSavedUri = saved.contentUri
                lastMime = saved.mimeType
            }
        }

        val deliverable = lastDeliverable ?: throw IOException("No deliverable produced")
        if (spec.delivery.copyToClipboard) {
            val clipUri = clipboardExportManager.copyFileToClipboard(deliverable, deliverable.name)
            if (lastSavedUri == null) lastSavedUri = clipUri
        } else if (lastSavedUri == null) {
            lastSavedUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                deliverable
            )
        }

        val doneMsg = buildDoneMessage(spec, totalInputBytes, totalOutputBytes, downloadedFiles.size)
        jobDao.markCompleted(
            id = entity.id,
            statusText = doneMsg,
            inputBytes = totalInputBytes,
            outputBytes = totalOutputBytes,
            outputPath = deliverable.absolutePath,
            outputUriString = lastSavedUri?.toString(),
            outputMimeType = lastMime
        )
    }

    private suspend fun runLocalTransformJob(
        entity: ForgeJobEntity,
        preProbed: MediaProbeResult?,
        spec: PipelineSpec,
        workDir: File
    ) {
        val uriStr = entity.sourceUriString ?: throw IOException("Missing source URI")
        val sourceUri = Uri.parse(uriStr)
        val probe = preProbed ?: probeEngine.probeUri(sourceUri)

        val weight = computeSlotWeight(probe.mediaKind)
        val maxCompute = { effectiveMaxComputeSlots() }

        val deliverable = computePool.withSlots(cost = weight, capacityProvider = maxCompute, onPoolChange = { refreshMetrics() }) {
            jobDao.updateProgress(entity.id, JobStatus.PROCESSING.name, "Preparing ${probe.displayName}...", 4f, JobLane.COMPUTE.name)
            refreshMetrics()

            val stagedInput: File? = if (probe.mediaKind == MediaKind.VIDEO || probe.mediaKind == MediaKind.AUDIO) {
                probeEngine.stageUriToTempFile(sourceUri, File(workDir, "in"), "src")
            } else {
                null
            }

            val processed = transformLocalFile(
                jobId = entity.id,
                sourceUri = sourceUri,
                stagedInput = stagedInput,
                probe = probe,
                spec = spec,
                workDir = workDir
            ) { pct, msg ->
                scope.launch {
                    if (!activeCoroutines.containsKey(entity.id)) return@launch
                    jobDao.updateProgress(entity.id, JobStatus.PROCESSING.name, msg, pct.coerceIn(0f, 98f), JobLane.COMPUTE.name)
                }
            }

            val outputName = buildOutputFilename(probe.displayName, processed.extension, spec)
            stageExportFile(entity.id, processed, outputName)
        }

        val mime = mediaStoreExporter.extensionToMime(deliverable.extension)
        var finalUri: Uri? = null

        if (spec.delivery.saveToDevice) {
            finalUri = mediaStoreExporter.exportToDevice(deliverable, deliverable.name).contentUri
        }
        if (spec.delivery.copyToClipboard) {
            val clipUri = clipboardExportManager.copyFileToClipboard(deliverable, deliverable.name)
            if (finalUri == null) finalUri = clipUri
        }
        if (finalUri == null) {
            finalUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                deliverable
            )
        }

        val inBytes = probe.fileSizeBytes.takeIf { it > 0L } ?: entity.inputBytes
        val outBytes = deliverable.length()
        val doneMsg = buildDoneMessage(spec, inBytes, outBytes, 1)

        jobDao.markCompleted(
            id = entity.id,
            statusText = doneMsg,
            inputBytes = inBytes,
            outputBytes = outBytes,
            outputPath = deliverable.absolutePath,
            outputUriString = finalUri.toString(),
            outputMimeType = mime
        )
    }

    private suspend fun transformLocalFile(
        jobId: String,
        sourceUri: Uri,
        stagedInput: File?,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        workDir: File,
        onProgress: (Float, String) -> Unit
    ): File {
        val outDir = File(workDir, "out").apply { mkdirs() }
        val targetExt = resolveTargetExtension(probe, spec)
        val outFile = File(outDir, "forge_$jobId.$targetExt")

        return when (probe.mediaKind) {
            MediaKind.IMAGE, MediaKind.CAMERA_RAW -> {
                imageRawEngine.processImageOrRaw(
                    sourceUri = sourceUri,
                    probe = probe,
                    spec = spec,
                    outputFile = outFile,
                    onProgress = onProgress
                )
            }
            MediaKind.VIDEO, MediaKind.AUDIO -> {
                val inputFile = stagedInput ?: probeEngine.stageUriToTempFile(sourceUri, File(workDir, "in"), "src")
                val threads = if (effectiveMaxComputeSlots() <= 2) {
                    deviceCapabilities.recommendedFfmpegThreads
                } else {
                    deviceCapabilities.recommendedFfmpegThreads.coerceAtMost(4)
                }
                ffmpegEngine.executePipeline(
                    inputFile = inputFile,
                    outputFile = outFile,
                    probe = probe,
                    spec = spec,
                    threadCount = threads,
                    onProgress = onProgress
                )
            }
            else -> throw IOException("Unsupported media type: ${probe.extension}")
        }
    }

    private fun resolveTargetExtension(probe: MediaProbeResult, spec: PipelineSpec): String {
        val requested = spec.convert?.targetExtension?.lowercase(Locale.US)?.trim()
        if (!requested.isNullOrBlank()) return requested
        return when (probe.mediaKind) {
            MediaKind.CAMERA_RAW -> "jpg"
            MediaKind.IMAGE -> if (probe.extension in setOf("jpg", "jpeg", "png", "webp")) probe.extension else "jpg"
            MediaKind.VIDEO -> if (probe.extension in setOf("mp4", "mkv", "webm", "mov")) probe.extension else "mp4"
            MediaKind.AUDIO -> if (probe.extension in setOf("mp3", "m4a", "opus", "flac", "wav")) probe.extension else "m4a"
            else -> probe.extension.ifBlank { "bin" }
        }
    }

    private fun audioCodecForExtension(ext: String): AudioCodecChoice {
        return when (ext.lowercase(Locale.US)) {
            "mp3" -> AudioCodecChoice.MP3
            "opus", "ogg" -> AudioCodecChoice.OPUS
            "flac" -> AudioCodecChoice.FLAC
            "wav" -> AudioCodecChoice.WAV
            else -> AudioCodecChoice.AAC
        }
    }

    private fun buildOutputFilename(originalName: String, targetExt: String, spec: PipelineSpec): String {
        val customRaw = spec.delivery.customOutputBaseName
        if (!customRaw.isNullOrBlank()) {
            val cleanCustom = customRaw
                .replace(Regex("""[/\\:*?"<>|\u0000-\u001F]"""), "_")
                .trim { it.isWhitespace() || it == '.' }
                .take(100)
                .trimEnd { it.isWhitespace() || it == '.' }
                .ifBlank { "redfur_track" }
            return "$cleanCustom.$targetExt"
        }

        val base = originalName.substringBeforeLast('.')
            .replace(Regex("""[^A-Za-z0-9._ -]"""), "_")
            .trim()
            .take(64)
            .ifBlank { "redfur_media" }
        val suffix = spec.delivery.filenameSuffix.ifBlank { "_forge" }
        val cleanBase = if (base.endsWith(suffix)) base else "$base$suffix"
        val timestamp = (System.currentTimeMillis() % 100_000L)
        return "${cleanBase}_$timestamp.$targetExt"
    }

    private fun jobExportDir(jobId: String): File {
        val safeJobFolder = jobId.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "job" }
        return File(exportsDir, safeJobFolder)
    }

    private fun stageExportFile(jobId: String, source: File, desiredName: String): File {
        val dir = jobExportDir(jobId).apply { mkdirs() }
        val dest = File(dir, desiredName)
        source.copyTo(dest, overwrite = true)
        return dest
    }

    private fun buildDoneMessage(
        spec: PipelineSpec,
        inputBytes: Long,
        outputBytes: Long,
        count: Int
    ): String {
        val actionLabel = when {
            spec.delivery.saveToDevice && spec.delivery.copyToClipboard -> "Saved & copied"
            spec.delivery.copyToClipboard -> "Copied to clipboard"
            else -> "Saved to RedfurForge"
        }
        val countPrefix = if (count > 1) "$count files • " else ""
        val delta = if (inputBytes > 0L && outputBytes > 0L && outputBytes < inputBytes) {
            val pct = (((inputBytes - outputBytes).toDouble() / inputBytes.toDouble()) * 100.0).toInt()
            " (-$pct%)"
        } else {
            ""
        }
        return "$countPrefix$actionLabel$delta"
    }

    private fun computeSlotWeight(kind: MediaKind): Int {
        return when (kind) {
            MediaKind.VIDEO, MediaKind.CAMERA_RAW -> 2
            else -> 1
        }
    }

    private fun effectiveMaxComputeSlots(): Int {
        val override = settingsState.value.computeSlotsOverride
        return if (override > 0) override else defaultComputeSlotsForDeviceRam()
    }

    private fun defaultComputeSlotsForDeviceRam(): Int {
        return deviceCapabilities.recommendedComputeSlots
    }

    private fun refreshMetrics() {
        val netUsed = networkPool.currentUsed()
        val compUsed = computePool.currentUsed()
        val totalActive = activeCoroutines.size
        val next = OrchestratorMetrics(
            activeNetworkCount = netUsed,
            maxNetworkSlots = settingsState.value.maxConcurrentDownloads.coerceIn(1, 5),
            activeComputeSlotsUsed = compUsed,
            maxComputeSlots = effectiveMaxComputeSlots(),
            totalActiveJobs = totalActive
        )
        _metrics.value = next

        if (totalActive > 0) {
            ForgeForegroundService.pushUpdate(
                context = appContext,
                title = "Redfur Forge ($totalActive active)",
                text = "Net: $netUsed/${next.maxNetworkSlots} • Compute: $compUsed/${next.maxComputeSlots} slots",
                progress = 0,
                indeterminate = true
            )
        } else {
            ForgeForegroundService.stop(appContext)
        }
    }

    private fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private data class ChapterJobEntry(
        val entity: ForgeJobEntity,
        val spec: PipelineSpec,
        val chapter: ChapterTrackItem? = null
    )

    private data class SharedChapterSource(
        val file: File,
        val probe: MediaProbeResult
    )

    private class ChapterGroupSession(
        val groupId: String,
        val sourceUrl: String,
        val groupWorkDir: File,
        val totalBatchSize: Int,
        val remainingSiblings: AtomicInteger
    ) {
        lateinit var sharedDownloadJob: Job
        val sharedSourceDeferred = CompletableDeferred<SharedChapterSource>()
        val trackedJobIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val progressDbMutex = Mutex()
        val sharedDownloadFailed = AtomicBoolean(false)
        val authEventEmitted = AtomicBoolean(false)
        val cleanupDone = CompletableDeferred<Unit>()
    }

    /**
     * Deadlock-free weighted slot pool supporting atomic multi-slot acquisition and cancellation.
     */
    private class WeightedSlotPool {
        private val mutex = Mutex()
        private var used = 0
        private val waiters = mutableListOf<CompletableDeferred<Unit>>()

        fun currentUsed(): Int = used

        suspend fun <T> withSlots(
            cost: Int,
            capacityProvider: () -> Int,
            onPoolChange: () -> Unit,
            block: suspend () -> T
        ): T {
            val effectiveCost = cost.coerceAtLeast(1)
            var acquiredCost = 0
            while (true) {
                var waiter: CompletableDeferred<Unit>? = null
                mutex.withLock {
                    val cap = capacityProvider().coerceAtLeast(1)
                    val clampedCost = effectiveCost.coerceAtMost(cap)
                    if (used + clampedCost <= cap) {
                        used += clampedCost
                        acquiredCost = clampedCost
                    } else {
                        waiter = CompletableDeferred<Unit>().also { waiters.add(it) }
                    }
                }
                if (waiter == null) break
                try {
                    waiter!!.await()
                } catch (e: Throwable) {
                    mutex.withLock { waiters.remove(waiter) }
                    throw e
                }
            }

            onPoolChange()
            return try {
                block()
            } finally {
                mutex.withLock {
                    used = (used - acquiredCost).coerceAtLeast(0)
                    val snapshot = waiters.toList()
                    waiters.clear()
                    snapshot.forEach { it.complete(Unit) }
                }
                onPoolChange()
            }
        }
    }
}
