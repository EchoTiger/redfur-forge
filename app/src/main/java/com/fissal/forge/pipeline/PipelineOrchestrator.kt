package com.fissal.forge.pipeline

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.fissal.forge.data.local.ForgeJobDao
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.local.JobLane
import com.fissal.forge.data.local.JobStatus
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.data.settings.ForgeSettingsRepository
import com.fissal.forge.delivery.ClipboardExportManager
import com.fissal.forge.delivery.MediaStoreExporter
import com.fissal.forge.domain.logic.BitrateBudgetCalculator
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressSpec
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.DeliverySpec
import com.fissal.forge.domain.model.DetectedLinkKind
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.DownloadSpec
import com.fissal.forge.domain.model.LinkItemSettings
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.engine.FFmpegCliEngine
import com.fissal.forge.engine.ImageRawEngine
import com.fissal.forge.engine.MediaProbeEngine
import com.fissal.forge.engine.YtDlpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

data class OrchestratorMetrics(
    val activeNetworkCount: Int = 0,
    val maxNetworkSlots: Int = 3,
    val activeComputeSlotsUsed: Int = 0,
    val maxComputeSlots: Int = 2,
    val totalActiveJobs: Int = 0
)

/**
 * Dual-lane resource-weighted pipeline orchestrator.
 * - Network Lane runs concurrent yt-dlp downloads bounded by user settings.
 * - Compute Lane runs RAM-weighted FFmpeg (SVT-AV1 / 2-pass HEVC / H.264 / Opus) & LibRaw transforms without OOMs.
 * - Fans playlists out into individually tracked & controllable song jobs grouped by groupId.
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
    val clipboardExportManager: ClipboardExportManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val activeCoroutines = ConcurrentHashMap<String, Job>()
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
            val wantsPlaylist = !chip.settings.playlistSingleOnly &&
                (chip.tracks.isNotEmpty() || chip.detectedKind == DetectedLinkKind.PLAYLIST || chip.isPlaylistLikeUrl)

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
                sponsorBlock = linkSettings.sponsorBlock,
                useAria2c = globalSettings.useAria2c
            ),
            convert = convertSpec,
            compress = compressSpec,
            delivery = DeliverySpec(
                saveToDevice = saveToDevice,
                copyToClipboard = copyToClipboard,
                stripMetadata = linkSettings.stripMetadata || (globalSettings.stripMetadataOnClipboard && copyToClipboard && !saveToDevice),
                filenameSuffix = globalSettings.filenameSuffix
            )
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
        val job = scope.launch {
            try {
                val meta = ytDlpEngine.resolveMetadata(chip.cleanUrl, placeholder.id)
                jobDao.deleteById(placeholder.id)

                val tracks = meta.tracks.ifEmpty { emptyList() }
                if (tracks.isEmpty()) {
                    val spec = buildSpecFromLinkSettings(
                        chipUrl = chip.cleanUrl,
                        durationSec = meta.durationSec ?: chip.durationSec,
                        linkSettings = chip.settings,
                        playlistSingleOnly = true,
                        playlistItemIndex = null,
                        saveToDevice = saveToDevice,
                        copyToClipboard = copyToClipboard,
                        globalSettings = settings
                    )
                    val single = placeholder.copy(
                        id = UUID.randomUUID().toString(),
                        title = meta.title,
                        sourceLabel = "${chip.host} • ${spec.summaryLabel()}",
                        status = JobStatus.QUEUED.name,
                        progress = 0f,
                        statusText = "Queued • ${spec.summaryLabel()}",
                        groupId = null,
                        groupTitle = null,
                        playlistIndex = null,
                        playlistTotal = null,
                        thumbnailUrl = meta.thumbnailUrl ?: chip.thumbnailUrl
                    )
                    jobDao.upsert(single)
                    launchJobCoroutine(single, null, spec)
                } else {
                    val total = tracks.size
                    for ((idx, track) in tracks.withIndex()) {
                        val useDirectUrl = !track.trackUrl.isNullOrBlank()
                        val spec = buildSpecFromLinkSettings(
                            chipUrl = if (useDirectUrl) track.trackUrl!! else chip.cleanUrl,
                            durationSec = track.durationSec ?: chip.durationSec,
                            linkSettings = chip.settings,
                            playlistSingleOnly = useDirectUrl,
                            playlistItemIndex = if (useDirectUrl) null else track.index,
                            saveToDevice = saveToDevice,
                            copyToClipboard = copyToClipboard,
                            globalSettings = settings
                        )
                        val trackEntity = ForgeJobEntity(
                            id = UUID.randomUUID().toString(),
                            title = track.title,
                            sourceLabel = "${chip.host} • Track ${idx + 1}/$total",
                            sourceUriString = spec.download?.url ?: chip.cleanUrl,
                            mediaKind = MediaKind.REMOTE_URL.name,
                            lane = JobLane.NETWORK.name,
                            status = JobStatus.QUEUED.name,
                            progress = 0f,
                            statusText = "Queued • ${spec.summaryLabel()}",
                            pipelineSpecJson = json.encodeToString(spec),
                            groupId = groupId,
                            groupTitle = meta.title,
                            playlistIndex = idx + 1,
                            playlistTotal = total,
                            thumbnailUrl = track.thumbnailUrl ?: meta.thumbnailUrl ?: chip.thumbnailUrl
                        )
                        jobDao.upsert(trackEntity)
                        launchJobCoroutine(trackEntity, null, spec)
                    }
                }
            } catch (_: CancellationException) {
                jobDao.markTerminalState(placeholder.id, JobStatus.CANCELED.name, "Canceled", null)
            } catch (t: Throwable) {
                jobDao.markTerminalState(placeholder.id, JobStatus.FAILED.name, "Playlist resolve failed", t.message)
            } finally {
                activeCoroutines.remove(placeholder.id)
                refreshMetrics()
            }
        }
        activeCoroutines[placeholder.id] = job
        refreshMetrics()
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
        val spec = runCatching { json.decodeFromString<PipelineSpec>(existing.pipelineSpecJson) }.getOrNull() ?: return
        val reset = existing.copy(
            status = JobStatus.QUEUED.name,
            progress = 0f,
            statusText = "Retrying...",
            errorMessage = null,
            updatedAtMs = System.currentTimeMillis()
        )
        jobDao.upsert(reset)
        launchJobCoroutine(reset, null, spec)
    }

    fun cancelJob(jobId: String) {
        activeCoroutines.remove(jobId)?.cancel()
        scope.launch {
            jobDao.markTerminalState(jobId, JobStatus.CANCELED.name, "Canceled", null)
            refreshMetrics()
        }
    }

    suspend fun removeJob(jobId: String) {
        activeCoroutines.remove(jobId)?.cancel()
        val job = jobDao.getById(jobId)
        job?.outputPath?.let { runCatching { File(it).delete() } }
        runCatching { File(jobsWorkRoot, jobId).deleteRecursively() }
        jobDao.deleteById(jobId)
        refreshMetrics()
    }

    fun cancelPlaylistGroup(groupId: String) {
        scope.launch {
            val groupJobs = jobDao.getByGroupId(groupId)
            for (job in groupJobs) {
                activeCoroutines.remove(job.id)?.cancel()
                if (job.isActive) {
                    jobDao.markTerminalState(job.id, JobStatus.CANCELED.name, "Canceled", null)
                }
            }
            refreshMetrics()
        }
    }

    suspend fun removePlaylistGroup(groupId: String) {
        val groupJobs = jobDao.getByGroupId(groupId)
        for (job in groupJobs) {
            activeCoroutines.remove(job.id)?.cancel()
            job.outputPath?.let { runCatching { File(it).delete() } }
            runCatching { File(jobsWorkRoot, job.id).deleteRecursively() }
        }
        jobDao.deleteByGroupId(groupId)
        refreshMetrics()
    }

    suspend fun retryPlaylistGroup(groupId: String) {
        val groupJobs = jobDao.getByGroupId(groupId)
        for (job in groupJobs) {
            if (job.statusEnum == JobStatus.FAILED || job.statusEnum == JobStatus.CANCELED) {
                retryJob(job.id)
            }
        }
    }

    fun cancelAll() {
        val keys = activeCoroutines.keys.toList()
        keys.forEach { key ->
            activeCoroutines.remove(key)?.cancel()
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
        exportsDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in keptPaths) {
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
        listOf(exportsDir, jobsWorkRoot, File(appContext.cacheDir, "probe_staging")).forEach { dir ->
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
            directorySizeBytes(File(appContext.cacheDir, "updates")) +
            clipboardExportManager.clipboardCacheSizeBytes()
    }

    private fun launchJobCoroutine(
        initialEntity: ForgeJobEntity,
        preProbed: MediaProbeResult?,
        spec: PipelineSpec
    ) {
        val job = scope.launch {
            val workDir = File(jobsWorkRoot, initialEntity.id).apply { mkdirs() }
            try {
                if (spec.download != null) {
                    runDownloadAndTransformJob(initialEntity, spec, workDir)
                } else {
                    runLocalTransformJob(initialEntity, preProbed, spec, workDir)
                }
            } catch (_: CancellationException) {
                jobDao.markTerminalState(initialEntity.id, JobStatus.CANCELED.name, "Canceled", null)
            } catch (t: Throwable) {
                jobDao.markTerminalState(
                    id = initialEntity.id,
                    status = JobStatus.FAILED.name,
                    statusText = "Failed",
                    errorMessage = t.message ?: t::class.java.simpleName
                )
            } finally {
                workDir.deleteRecursively()
                activeCoroutines.remove(initialEntity.id)
                refreshMetrics()
            }
        }
        activeCoroutines[initialEntity.id] = job
        refreshMetrics()
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

            val exportedCopy = stageExportFile(finalFile, buildOutputFilename(downloaded.name, finalFile.extension, spec))
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
                    jobDao.updateProgress(entity.id, JobStatus.PROCESSING.name, msg, pct.coerceIn(0f, 98f), JobLane.COMPUTE.name)
                }
            }

            val outputName = buildOutputFilename(probe.displayName, processed.extension, spec)
            stageExportFile(processed, outputName)
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
                val threads = if (effectiveMaxComputeSlots() <= 2) 4 else 3
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

    private fun stageExportFile(source: File, desiredName: String): File {
        val dest = File(exportsDir, desiredName)
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
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
        return when {
            totalGb <= 6.2 -> 2
            totalGb <= 9.2 -> 3
            else -> 4
        }
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
