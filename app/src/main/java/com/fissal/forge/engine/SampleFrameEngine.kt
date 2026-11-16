package com.fissal.forge.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.fissal.forge.domain.logic.FilesizeEstimator
import com.fissal.forge.domain.logic.LumaFrame
import com.fissal.forge.domain.logic.PerceptualAdvisor
import com.fissal.forge.domain.logic.SampleCalibrationRatio
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.RenderedSampleResult
import com.fissal.forge.domain.model.SampleCandidateSpec
import com.fissal.forge.domain.model.SampleRenderStatus
import com.fissal.forge.domain.model.TrimSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

data class BaselineSampleOutput(
    val baselineResult: RenderedSampleResult,
    val referenceClipBytes: Long,
    val referenceLuma: LumaFrame,
    val resolvedTimestampMs: Long,
    val imageSourceBitmap: Bitmap?,
    val stagedVideoFile: File?
)

/**
 * Coordinates Sample Lab session caching, representative frame extraction (with optional
 * black-fade dodge), authentic multi-candidate rendering, BT.709 luma SSIM scoring, and
 * Pareto-knee annotation without holding unbounded rasters in heap.
 */
class SampleFrameEngine(
    private val appContext: Context,
    private val ffmpegEngine: FFmpegCliEngine,
    private val imageRawEngine: ImageRawEngine,
    private val probeEngine: MediaProbeEngine
) {

    private val cacheRoot: File
        get() = File(appContext.cacheDir, "sample_lab")

    suspend fun prepareBaseline(
        sessionId: String,
        sourceUri: Uri,
        probe: MediaProbeResult,
        crop: CropSpec? = null,
        trim: TrimSpec? = null,
        requestedTimestampMs: Long? = null,
        autoDodgeBlackFade: Boolean = false,
        existingStagedVideoFile: File? = null,
        maxPreviewDim: Int = 1920
    ): BaselineSampleOutput = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val sessionDir = File(cacheRoot, sanitizeSessionId(sessionId)).apply { mkdirs() }
        val origFile = File(sessionDir, "orig.png")

        if (probe.mediaKind == MediaKind.IMAGE || probe.mediaKind == MediaKind.CAMERA_RAW) {
            val sourceBmp = imageRawEngine.decodeReferenceSourceBitmap(
                uri = sourceUri,
                probe = probe,
                crop = crop,
                maxDimension = maxPreviewDim
            )
            try {
                coroutineContext.ensureActive()
                FileOutputStream(origFile).use { fos ->
                    if (!sourceBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                        throw IOException("Failed to write baseline sample PNG")
                    }
                    fos.flush()
                }
                val luma = extractLumaFrame(sourceBmp, maxAnalysisDim = MAX_ANALYSIS_DIM)
                val sampleBytes = origFile.length().coerceAtLeast(1L)
                val baselineResult = RenderedSampleResult(
                    candidateId = ORIG_CANDIDATE_ID,
                    previewFilePath = origFile.absolutePath,
                    width = sourceBmp.width,
                    height = sourceBmp.height,
                    sampleBytes = sampleBytes,
                    projectedTotalBytes = probe.fileSizeBytes,
                    sizeRatioVsOriginal = 1.0,
                    perceptualScore = 1.0,
                    status = SampleRenderStatus.READY
                )
                return@withContext BaselineSampleOutput(
                    baselineResult = baselineResult,
                    referenceClipBytes = sampleBytes,
                    referenceLuma = luma,
                    resolvedTimestampMs = 0L,
                    imageSourceBitmap = sourceBmp,
                    stagedVideoFile = null
                )
            } catch (err: Throwable) {
                if (!sourceBmp.isRecycled) sourceBmp.recycle()
                runCatching { origFile.delete() }
                throw err
            }
        }

        val stagedVideo = resolveOrStageSessionVideo(
            sourceUri = sourceUri,
            sessionDir = sessionDir,
            existingStaged = existingStagedVideoFile
        )

        val activeStart = (trim?.startMs ?: 0L).coerceAtLeast(0L)
        val activeEnd = (trim?.endMs?.takeIf { it > activeStart } ?: probe.durationMs.coerceAtLeast(activeStart))
            .let { if (probe.durationMs > 0L) it.coerceAtMost(probe.durationMs) else it }
        var resolvedTs = requestedTimestampMs?.coerceIn(activeStart, activeEnd.coerceAtLeast(activeStart))
            ?: pickRandomTimestampMs(probe.durationMs, trimSpec = trim)

        var extraction: VideoReferenceExtraction
        var luma: LumaFrame
        try {
            extraction = ffmpegEngine.extractVideoReferenceFrameToFile(
                inputFile = stagedVideo,
                probe = probe,
                crop = crop,
                timestampMs = resolvedTs,
                workDir = sessionDir,
                outputPngFile = origFile,
                maxPreviewDim = maxPreviewDim
            )
            luma = extractLumaFromFileSafely(origFile, maxAnalysisDim = MAX_ANALYSIS_DIM)

            if (autoDodgeBlackFade && PerceptualAdvisor.isNearBlackFrame(luma) && (activeEnd - activeStart) > 1500L) {
                val span = activeEnd - activeStart
                val fallbackOffsets = listOf(
                    pickRandomTimestampMs(probe.durationMs, trimSpec = trim, previousTimestampMs = resolvedTs),
                    activeStart + (span * 0.38).roundToLong(),
                    activeStart + (span * 0.68).roundToLong()
                ).distinct().filter { abs(it - resolvedTs) > 250L }.take(2)

                for (altTs in fallbackOffsets) {
                    coroutineContext.ensureActive()
                    val altExtraction = ffmpegEngine.extractVideoReferenceFrameToFile(
                        inputFile = stagedVideo,
                        probe = probe,
                        crop = crop,
                        timestampMs = altTs,
                        workDir = sessionDir,
                        outputPngFile = origFile,
                        maxPreviewDim = maxPreviewDim
                    )
                    val altLuma = extractLumaFromFileSafely(origFile, maxAnalysisDim = MAX_ANALYSIS_DIM)
                    extraction = altExtraction
                    luma = altLuma
                    resolvedTs = altTs
                    if (!PerceptualAdvisor.isNearBlackFrame(altLuma)) {
                        break
                    }
                }
            }
        } catch (err: Throwable) {
            runCatching { origFile.delete() }
            throw err
        }

        val baselineResult = RenderedSampleResult(
            candidateId = ORIG_CANDIDATE_ID,
            previewFilePath = origFile.absolutePath,
            width = extraction.width,
            height = extraction.height,
            sampleBytes = origFile.length().coerceAtLeast(1L),
            projectedTotalBytes = probe.fileSizeBytes,
            sizeRatioVsOriginal = 1.0,
            perceptualScore = 1.0,
            status = SampleRenderStatus.READY
        )

        BaselineSampleOutput(
            baselineResult = baselineResult,
            referenceClipBytes = extraction.referenceClipBytes,
            referenceLuma = luma,
            resolvedTimestampMs = resolvedTs,
            imageSourceBitmap = null,
            stagedVideoFile = stagedVideo
        )
    }

    suspend fun renderCandidate(
        sessionId: String,
        candidate: SampleCandidateSpec,
        probe: MediaProbeResult,
        baseline: BaselineSampleOutput,
        maxPreviewDim: Int = 1920
    ): RenderedSampleResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (candidate.isOriginalBaseline) {
            return@withContext baseline.baselineResult.copy(candidateId = candidate.id)
        }

        val sessionDir = File(cacheRoot, sanitizeSessionId(sessionId)).apply { mkdirs() }
        val safeCand = sanitizeId(candidate.id, "cand_${candidate.indexNumber}")
        val isVisualPhoto = probe.mediaKind == MediaKind.IMAGE || probe.mediaKind == MediaKind.CAMERA_RAW
        val previewExt = if (isVisualPhoto) {
            (candidate.pipelineSpec.convert?.targetExtension
                ?: if (probe.mediaKind == MediaKind.CAMERA_RAW) "jpg" else probe.extension)
                .lowercase(Locale.US)
                .ifBlank { "jpg" }
        } else {
            "png"
        }
        val previewFile = File(sessionDir, "${safeCand}_preview.$previewExt")

        try {
            val outWidth: Int
            val outHeight: Int
            val sampleBytes: Long
            val calibration: SampleCalibrationRatio

            if (isVisualPhoto) {
                val srcBitmap = baseline.imageSourceBitmap
                    ?: throw IOException("Missing image reference bitmap for sample session $sessionId")
                calibration = imageRawEngine.renderImageCandidateToFile(
                    uprightReferenceBitmap = srcBitmap,
                    probe = probe,
                    spec = candidate.pipelineSpec,
                    outputFile = previewFile,
                    sampleWorkMaxDim = minOf(maxPreviewDim.coerceAtLeast(16), MAX_ANALYSIS_DIM)
                )
                val (w, h) = readImageBounds(previewFile)
                outWidth = w
                outHeight = h
                sampleBytes = previewFile.length().coerceAtLeast(1L)
            } else {
                val stagedVideo = baseline.stagedVideoFile
                    ?: throw IOException("Missing staged video file for sample session $sessionId")
                val videoResult = ffmpegEngine.renderVideoSampleCandidateToFile(
                    inputFile = stagedVideo,
                    probe = probe,
                    spec = candidate.pipelineSpec,
                    timestampMs = baseline.resolvedTimestampMs,
                    workDir = sessionDir,
                    outputPngFile = previewFile,
                    referenceClipBytes = baseline.referenceClipBytes,
                    maxPreviewDim = maxPreviewDim
                )
                outWidth = videoResult.width
                outHeight = videoResult.height
                sampleBytes = videoResult.sampleClipBytes
                calibration = videoResult.calibration
            }

            coroutineContext.ensureActive()

            val candLuma = extractLumaFromFileSafely(previewFile, maxAnalysisDim = MAX_ANALYSIS_DIM)
            val perceptualScore = PerceptualAdvisor.computePerceptualScore(baseline.referenceLuma, candLuma)
            val projectedTotalBytes = FilesizeEstimator.estimateLocalPipeline(
                probe = probe,
                spec = candidate.pipelineSpec,
                sampleCalibration = calibration
            ).estimatedBytes
            val sizeRatio = if (probe.fileSizeBytes > 0L) {
                projectedTotalBytes.toDouble() / probe.fileSizeBytes.toDouble()
            } else {
                1.0
            }

            RenderedSampleResult(
                candidateId = candidate.id,
                previewFilePath = previewFile.absolutePath,
                width = outWidth,
                height = outHeight,
                sampleBytes = sampleBytes,
                projectedTotalBytes = projectedTotalBytes,
                sizeRatioVsOriginal = sizeRatio,
                perceptualScore = perceptualScore,
                calibration = calibration,
                status = SampleRenderStatus.READY
            )
        } catch (ce: CancellationException) {
            runCatching { previewFile.delete() }
            throw ce
        } catch (err: Throwable) {
            runCatching { previewFile.delete() }
            RenderedSampleResult(
                candidateId = candidate.id,
                previewFilePath = null,
                status = SampleRenderStatus.FAILED,
                error = err.message ?: "Sample render failed"
            )
        }
    }

    fun evaluateParetoBest(
        originalBytes: Long,
        renderedResults: Map<String, RenderedSampleResult>,
        candidates: List<SampleCandidateSpec>
    ): Map<String, RenderedSampleResult> {
        val inputs = candidates.mapNotNull { candidate ->
            val rendered = renderedResults[candidate.id] ?: return@mapNotNull null
            if (rendered.status != SampleRenderStatus.READY) return@mapNotNull null
            com.fissal.forge.domain.logic.CandidateEvaluationInput(
                candidateId = candidate.id,
                projectedTotalBytes = rendered.projectedTotalBytes,
                perceptualScore = rendered.perceptualScore,
                isOriginalBaseline = candidate.isOriginalBaseline
            )
        }

        val recommendation = PerceptualAdvisor.recommendBestCandidate(originalBytes, inputs)
        return renderedResults.mapValues { (id, result) ->
            if (recommendation == null) {
                result.copy(
                    isRecommendedBest = false,
                    recommendationSummary = null
                )
            } else {
                result.copy(
                    isRecommendedBest = (id == recommendation.bestCandidateId),
                    recommendationSummary = recommendation.summaryByCandidateId[id]
                )
            }
        }
    }

    suspend fun cleanupSession(sessionId: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                File(cacheRoot, sanitizeSessionId(sessionId)).deleteRecursively()
            }
        }
    }

    suspend fun sweepAllCache() {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                cacheRoot.deleteRecursively()
            }
        }
    }

    private suspend fun resolveOrStageSessionVideo(
        sourceUri: Uri,
        sessionDir: File,
        existingStaged: File?
    ): File {
        if (existingStaged != null && existingStaged.exists() && existingStaged.length() > 0L) {
            return existingStaged
        }
        val recoverableStaged = sessionDir.listFiles { f ->
            f.isFile && f.name.startsWith("source") && f.length() > 0L
        }?.maxByOrNull { it.lastModified() }
        if (recoverableStaged != null) {
            return recoverableStaged
        }
        if (sourceUri.scheme == "file" && sourceUri.path != null) {
            val localFile = File(sourceUri.path!!)
            if (localFile.exists() && localFile.canRead()) {
                return localFile
            }
        }
        return probeEngine.stageUriToTempFile(sourceUri, sessionDir, prefix = "source")
    }

    private fun extractLumaFromFileSafely(file: File, maxAnalysisDim: Int = MAX_ANALYSIS_DIM): LumaFrame {
        val bmp = decodeSampleFileSafely(file, maxDimensionPx = maxAnalysisDim)
            ?: throw IOException("Failed to decode sample frame for perceptual analysis (${file.name})")
        return try {
            extractLumaFrame(bmp, maxAnalysisDim = maxAnalysisDim)
        } finally {
            if (!bmp.isRecycled) {
                bmp.recycle()
            }
        }
    }

    private fun readImageBounds(file: File): Pair<Int, Int> {
        if (!file.exists() || file.length() <= 0L) return 0 to 0
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
    }

    private fun sanitizeSessionId(sessionId: String): String = sanitizeId(sessionId, "default")

    private fun sanitizeId(raw: String, fallback: String): String =
        raw.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(64).ifBlank { fallback }

    companion object {
        private const val ORIG_CANDIDATE_ID = "orig"
        private const val MAX_ANALYSIS_DIM = 1920

        /**
         * Picks a representative timestamp inside the active clip or trim range, avoiding the
         * first and last 8% (or >= 1500 ms margin when active clip > 6000 ms), and avoiding
         * landing within 8% of [previousTimestampMs] when possible.
         */
        fun pickRandomTimestampMs(
            durationMs: Long,
            trimSpec: TrimSpec? = null,
            previousTimestampMs: Long? = null,
            random: Random = Random.Default
        ): Long {
            val rangeStart = (trimSpec?.startMs ?: 0L).coerceAtLeast(0L)
            val rawEnd = trimSpec?.endMs?.takeIf { it > rangeStart } ?: durationMs.coerceAtLeast(rangeStart)
            val rangeEnd = if (durationMs > 0L) rawEnd.coerceAtMost(durationMs) else rawEnd
            val span = (rangeEnd - rangeStart).coerceAtLeast(0L)

            if (span <= 0L) return rangeStart
            if (span <= 500L) return rangeStart + span / 2L

            val pctMargin = (span * 0.08).roundToLong()
            val margin = if (span > 6_000L) maxOf(pctMargin, 1_500L) else pctMargin

            val safeMin: Long
            val safeMax: Long
            if (margin * 2L >= span) {
                safeMin = rangeStart + (span / 4L)
                safeMax = rangeStart + ((span * 3L) / 4L)
            } else {
                safeMin = rangeStart + margin
                safeMax = rangeEnd - margin
            }

            if (safeMax <= safeMin) {
                return safeMin.coerceIn(rangeStart, rangeEnd)
            }

            if (previousTimestampMs == null) {
                return random.nextLong(safeMin, safeMax + 1L)
            }

            val avoidRadius = (span * 0.08).roundToLong().coerceAtLeast(1L)
            var bestCandidate = random.nextLong(safeMin, safeMax + 1L)
            var bestDist = abs(bestCandidate - previousTimestampMs)
            if (bestDist > avoidRadius) {
                return bestCandidate
            }

            repeat(11) {
                val cand = random.nextLong(safeMin, safeMax + 1L)
                val dist = abs(cand - previousTimestampMs)
                if (dist > avoidRadius) {
                    return cand
                }
                if (dist > bestDist) {
                    bestDist = dist
                    bestCandidate = cand
                }
            }

            for (edge in listOf(safeMin, safeMax)) {
                val dist = abs(edge - previousTimestampMs)
                if (dist > bestDist) {
                    bestDist = dist
                    bestCandidate = edge
                }
            }

            return bestCandidate
        }

        /**
         * Safely decodes an image file bounded to [maxDimensionPx] using power-of-2 subsampling
         * plus exact downscale, recycling any oversized intermediate bitmap.
         */
        fun decodeSampleFileSafely(file: File, maxDimensionPx: Int): Bitmap? {
            if (!file.exists() || file.length() <= 0L || maxDimensionPx <= 0) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var inSampleSize = 1
            while (max(bounds.outWidth / inSampleSize, bounds.outHeight / inSampleSize) > maxDimensionPx * 2) {
                inSampleSize *= 2
            }

            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return null

            val curMax = max(decoded.width, decoded.height)
            if (curMax <= maxDimensionPx) {
                return decoded
            }

            val scale = maxDimensionPx.toFloat() / curMax.toFloat()
            val targetW = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (decoded.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            if (scaled !== decoded) {
                decoded.recycle()
            }
            return scaled
        }

        /**
         * Extracts planar BT.709 float luma `[0..255]` from [bitmap] bounded to [maxAnalysisDim],
         * recycling any temporary downscaled or software copy while preserving caller's [bitmap].
         */
        fun extractLumaFrame(bitmap: Bitmap, maxAnalysisDim: Int = MAX_ANALYSIS_DIM): LumaFrame {
            require(!bitmap.isRecycled) { "Cannot extract luma from recycled bitmap" }
            val safeCap = maxAnalysisDim.coerceAtLeast(16)
            val curMax = max(bitmap.width, bitmap.height)

            var tempScaled: Bitmap? = null
            var tempSoftware: Bitmap? = null
            try {
                val sized = if (curMax > safeCap) {
                    val scale = safeCap.toFloat() / curMax.toFloat()
                    val tw = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                    val th = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, tw, th, true).also {
                        if (it !== bitmap) tempScaled = it
                    }
                } else {
                    bitmap
                }

                val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    sized.config == Bitmap.Config.HARDWARE
                ) {
                    sized.copy(Bitmap.Config.ARGB_8888, false).also {
                        tempSoftware = it
                    } ?: throw IOException("Failed to copy HARDWARE bitmap for luma extraction")
                } else {
                    sized
                }

                val w = readable.width
                val h = readable.height
                val luma = FloatArray(w * h)
                val row = IntArray(w)

                for (y in 0 until h) {
                    readable.getPixels(row, 0, w, 0, y, w, 1)
                    val rowOffset = y * w
                    for (x in 0 until w) {
                        val px = row[x]
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        luma[rowOffset + x] = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    }
                }

                return LumaFrame(width = w, height = h, luma = luma)
            } finally {
                tempSoftware?.let { if (it !== bitmap && it !== tempScaled && !it.isRecycled) it.recycle() }
                tempScaled?.let { if (it !== bitmap && !it.isRecycled) it.recycle() }
            }
        }
    }
}
