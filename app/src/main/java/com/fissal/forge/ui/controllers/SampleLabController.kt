package com.fissal.forge.ui.controllers

import android.net.Uri
import com.fissal.forge.auth.CredentialRedactor
import com.fissal.forge.data.local.FormatPresetEntity
import com.fissal.forge.domain.logic.SampleCalibrationRatio
import com.fissal.forge.domain.logic.ViewportSyncMath
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.RenderedSampleResult
import com.fissal.forge.domain.model.SampleCandidateSpec
import com.fissal.forge.domain.model.SampleEntryDraft
import com.fissal.forge.domain.model.SampleRenderStatus
import com.fissal.forge.domain.model.SampleVoteState
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.engine.BaselineSampleOutput
import com.fissal.forge.engine.SampleFrameEngine
import com.fissal.forge.ui.SampleLabSessionState
import com.fissal.forge.ui.StudioItemConfig
import com.fissal.forge.ui.StudioSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
import java.util.UUID

/**
 * Owns Sample Frame Lab session state, reference frame extraction, candidate matrix
 * rendering, Pareto quality-vs-size scoring, and Fullscreen Blink Comparator navigation.
 */
class SampleLabController(
    private val scope: CoroutineScope,
    private val sampleFrameEngine: SampleFrameEngine,
    private val presetJson: Json,
    private val studioSession: StateFlow<StudioSessionState?>,
    private val buildStudioPipelineSpec: (MediaProbeResult, StudioItemConfig, Boolean) -> PipelineSpec,
    private val onInitializeStudioForUris: suspend (List<Uri>) -> StudioSessionState?,
    private val onSelectStudioItem: (Int) -> Unit,
    private val onApplyCandidateToStudio: (Int, MediaProbeResult, PipelineSpec, SampleCalibrationRatio?) -> Unit,
    private val onCloseStudioSession: () -> Unit,
    private val onShowBanner: (String) -> Unit
) {
    private val _sampleLabSession = MutableStateFlow<SampleLabSessionState?>(null)
    val sampleLabSession: StateFlow<SampleLabSessionState?> = _sampleLabSession.asStateFlow()

    private var sampleLabJob: Job? = null
    private val sampleLabMutex = Mutex()
    private var activeSampleBaseline: BaselineSampleOutput? = null
    private var activeSampleBaselineSessionId: String? = null
    private var activeSampleBaselineTimestampMs: Long = -1L
    private var activeSampleBaselineCrop: CropSpec? = null

    fun sweepInitialCache() {
        scope.launch(Dispatchers.IO) {
            sampleLabMutex.withLock {
                runCatching { sampleFrameEngine.sweepAllCache() }
            }
        }
    }

    fun openSampleLabForUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val state = onInitializeStudioForUris(uris) ?: return@launch
            val visualIdx = state.items.indexOfFirst { it.isVisual }
            if (visualIdx < 0) {
                onShowBanner("Sample Frame Lab supports video, photo, and Camera RAW files")
                return@launch
            }
            if (visualIdx != state.selectedItemIndex) {
                onSelectStudioItem(visualIdx)
            }
            openSampleLabForIndex(visualIdx, returnToHubOnClose = true)
        }
    }

    fun openSampleLabFromStudio() {
        val studio = studioSession.value ?: return
        openSampleLabForIndex(studio.selectedItemIndex, returnToHubOnClose = false)
    }

    private fun openSampleLabForIndex(index: Int, returnToHubOnClose: Boolean) {
        val studio = studioSession.value ?: return
        val item = studio.items.getOrNull(index) ?: return
        if (!item.isVisual) {
            onShowBanner("Sample Frame Lab supports videos, images, and Camera RAW files")
            return
        }
        val cfg = studio.itemConfigs.getOrNull(index) ?: studio.activeConfig
        val currentStudioSpec = buildStudioPipelineSpec(item, cfg, true)
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
            scope.launch(Dispatchers.IO) {
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
            onCloseStudioSession()
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
            onShowBanner("Limit 12 concurrent Sample Lab configs per session")
        }
        return added
    }

    fun addPresetAsSampleLabCandidate(preset: FormatPresetEntity) {
        val session = _sampleLabSession.value ?: return
        val spec = runCatching { presetJson.decodeFromString<PipelineSpec>(preset.specJson) }.getOrNull()
        if (spec == null) {
            onShowBanner("Could not read saved preset")
            return
        }
        val draft = SampleEntryDraft.fromPipelineSpec(session.probe, spec)
        if (addSampleLabCandidate(draft)) {
            onShowBanner("Added ${preset.emoji} ${preset.name} to Sample Lab")
        }
    }

    fun updateSampleLabCandidateDraft(
        candidateId: String,
        transform: (SampleEntryDraft) -> SampleEntryDraft
    ) {
        var oldRenderedPath: String? = null
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
            oldRenderedPath = nextResults[candidateId]?.previewFilePath
            nextResults[candidateId] = RenderedSampleResult(
                candidateId = candidateId,
                previewFilePath = null,
                status = SampleRenderStatus.IDLE
            )
            val evaluated = sampleFrameEngine.evaluateParetoBest(
                originalBytes = session.probe.fileSizeBytes,
                renderedResults = nextResults,
                candidates = nextCandidates
            )
            session.copy(candidates = nextCandidates, renderedResults = evaluated)
        }
        val stalePath = oldRenderedPath
        if (!stalePath.isNullOrBlank() && candidateId != "orig") {
            scope.launch(Dispatchers.IO) {
                sampleLabMutex.withLock {
                    val stillInUse = _sampleLabSession.value?.renderedResults?.values?.any {
                        it.status == SampleRenderStatus.READY && it.previewFilePath == stalePath
                    } == true
                    if (!stillInUse) {
                        runCatching { File(stalePath).delete() }
                    }
                }
            }
        }
    }

    fun removeSampleLabCandidate(candidateId: String) {
        val current = _sampleLabSession.value ?: return
        val existingUser = current.candidates.filter { !it.isOriginalBaseline }
        if (existingUser.size <= 1 || candidateId == "orig") {
            onShowBanner("Keep at least 1 sample config")
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
            scope.launch(Dispatchers.IO) {
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
        onApplyCandidateToStudio(
            session.studioItemIndex,
            session.probe,
            candidate.pipelineSpec,
            rendered?.calibration
        )
        // Keep Studio open even if Sample Lab originally launched from Hub
        _sampleLabSession.update { it?.copy(returnToHubOnClose = false) }
        closeSampleLab()
        onShowBanner("Applied #${candidate.indexNumber} (${candidate.label}) to Studio")
    }

    fun cleanupOnCleared() {
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
        }
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
        launched = scope.launch {
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

                var bannerPayload: Pair<List<SampleCandidateSpec>, Map<String, RenderedSampleResult>>? = null
                _sampleLabSession.update { cur ->
                    if (cur == null || cur.sessionId != targetSessionId) return@update cur
                    val evaluated = sampleFrameEngine.evaluateParetoBest(
                        originalBytes = cur.probe.fileSizeBytes,
                        renderedResults = cur.renderedResults,
                        candidates = cur.candidates
                    )
                    if (announceBestOnFinish) {
                        bannerPayload = cur.candidates to evaluated
                    }
                    cur.copy(isRenderingSamples = false, renderedResults = evaluated)
                }
                bannerPayload?.let { (candidates, evaluated) ->
                    announceBestSampleBanner(candidates, evaluated)
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
            onShowBanner("★ Best Config: #${winner.indexNumber} ${winner.label}$detail")
        } else {
            onShowBanner("Render sample candidates to evaluate Pareto Best Config")
        }
    }
}
