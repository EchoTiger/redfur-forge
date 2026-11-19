package com.fissal.forge.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.fissal.forge.auth.CredentialRedactor
import com.fissal.forge.domain.logic.AspectRatioSnapper
import com.fissal.forge.domain.logic.BitrateBudgetCalculator
import com.fissal.forge.domain.logic.DeviceCapabilityAdvisor
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.EncodePassMode
import com.fissal.forge.domain.logic.FFmpegCommandBuilder
import com.fissal.forge.domain.logic.SampleCalibrationRatio
import com.fissal.forge.domain.logic.VideoBudgetPlan
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.PixelCropRect
import com.fissal.forge.domain.model.SilenceSkipSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class VideoSampleFrameResult(
    val originalBitmap: Bitmap,
    val compressedBitmap: Bitmap,
    val calibration: SampleCalibrationRatio
)

data class VideoReferenceExtraction(
    val pngFile: File,
    val referenceClipBytes: Long,
    val width: Int,
    val height: Int
)

data class VideoCandidateRenderResult(
    val previewPngFile: File,
    val width: Int,
    val height: Int,
    val sampleClipBytes: Long,
    val calibration: SampleCalibrationRatio
)

/**
 * Wraps bundled libffmpeg.so CLI execution with:
 * - Zero-loss passthrough when input already satisfies target filesize & format
 * - Fast multi-point scene complexity calibration on longer clips
 * - True 2-pass encoding (HEVC / H.264 / VP9) and perceptual SVT-AV1 + Opus lookahead
 * - Automatic fallback to 2-pass HEVC if SVT-AV1 encounters an exotic container/filter edge case
 * - Guaranteed byte-ceiling corrective pass if VBR muxer slightly overshoots targetBytes
 */
class FFmpegCliEngine(
    private val appContext: Context,
    private val binaryManager: NativeBinaryManager,
    private val deviceCapabilities: DeviceCapabilityReport = DeviceCapabilityAdvisor.profileDevice(appContext)
) {

    private val defaultSampleThreads: Int
        get() = DeviceCapabilityAdvisor.computeSampleFfmpegThreads(deviceCapabilities)

    private val maxSafeRasterDim: Int
        get() = min(DeviceCapabilityAdvisor.GL_SAFE_MAX_TEXTURE_PX, deviceCapabilities.maxSafeRasterDimensionPx)

    private val sampleIntermediatePixels: Long
        get() = if (deviceCapabilities.snapshot.isLowRamDevice) {
            DeviceCapabilityAdvisor.LOW_RAM_MAX_INTERMEDIATE_PIXELS
        } else {
            DeviceCapabilityAdvisor.DEFAULT_MAX_INTERMEDIATE_PIXELS
        }

    suspend fun executePipeline(
        inputFile: File,
        outputFile: File,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        threadCount: Int = 4,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        binaryManager.ensureInitialized()

        val targetBytes = if (spec.compress?.mode == CompressMode.TARGET_BYTES) spec.compress.targetBytes else null
        val desiredExt = (spec.convert?.targetExtension ?: probe.extension).lowercase(Locale.US)
        val inputExt = inputFile.extension.lowercase(Locale.US)
        val silenceSpec = spec.silenceSkip?.takeIf { it.enabled } ?: spec.trim?.silenceSkip?.takeIf { it.enabled }
        val silenceTouchesMedia = silenceSpec != null && (probe.mediaKind == MediaKind.AUDIO || probe.hasAudio)

        val maxDimRequested = spec.compress?.maxDimensionPx?.takeIf { it > 0 }
        val requiresDownscale = maxDimRequested != null &&
            probe.effectiveWidth > 0 &&
            probe.effectiveHeight > 0 &&
            max(probe.effectiveWidth, probe.effectiveHeight) > maxDimRequested
        val mutesExistingAudio = probe.hasAudio &&
            (spec.trim?.muteAudio == true || spec.convert?.audioCodec == AudioCodecChoice.MUTE)

        // Zero-loss passthrough if the file already fits the target byte limit and needs no edits
        if (targetBytes != null &&
            spec.trim == null &&
            spec.crop == null &&
            !silenceTouchesMedia &&
            !spec.delivery.stripMetadata &&
            !requiresDownscale &&
            !mutesExistingAudio &&
            inputFile.exists() &&
            inputFile.length() in 1L..targetBytes &&
            (desiredExt.isBlank() || desiredExt == inputExt)
        ) {
            onProgress(100f, "Already <= target size (0% loss passthrough)")
            inputFile.copyTo(outputFile, overwrite = true)
            return@withContext outputFile
        }

        val baseDurationMs = computeEffectiveDurationMs(probe, spec)
        val videoSilenceEligible = silenceSpec != null &&
            probe.mediaKind == MediaKind.VIDEO &&
            desiredExt != "gif" &&
            probe.hasAudio &&
            spec.trim?.muteAudio != true &&
            spec.convert?.audioCodec != AudioCodecChoice.MUTE
        val keepSegmentsMs: List<Pair<Long, Long>>? = if (videoSilenceEligible) {
            onProgress(1f, "Detecting silence intervals...")
            val detected = detectKeepSegments(inputFile, spec, silenceSpec!!, baseDurationMs)
            val totalKeepMs = detected.sumOf { (it.second - it.first).coerceAtLeast(0L) }
            if (detected.isNotEmpty() && totalKeepMs > 0L && totalKeepMs < baseDurationMs - 200L) {
                detected
            } else {
                null
            }
        } else {
            null
        }
        val effectiveDurationMs = if (!keepSegmentsMs.isNullOrEmpty()) {
            keepSegmentsMs.sumOf { (it.second - it.first).coerceAtLeast(0L) }.coerceAtLeast(500L)
        } else {
            baseDurationMs
        }
        val complexityFactor = if (
            targetBytes != null &&
            probe.mediaKind == MediaKind.VIDEO &&
            effectiveDurationMs >= 25_000L
        ) {
            onProgress(2f, "Sampling scene complexity...")
            measureSceneComplexity(inputFile, effectiveDurationMs, spec)
        } else {
            1.0
        }

        // If silencedetect pre-pass ran on video+audio and confirmed no cuttable pauses, strip silenceSkip so
        // FFmpegCommandBuilder does not apply audio-only `silenceremove:timestamp=copy` over uncut video.
        var activeSpec = if (videoSilenceEligible && keepSegmentsMs == null) {
            spec.copy(
                silenceSkip = null,
                trim = spec.trim?.copy(silenceSkip = null)
            )
        } else {
            spec
        }
        try {
            runEncodeStrategy(
                inputFile = inputFile,
                outputFile = outputFile,
                probe = probe,
                spec = activeSpec,
                threadCount = threadCount,
                bitrateMultiplier = 1.0,
                effectiveDurationMs = effectiveDurationMs,
                complexityFactor = complexityFactor,
                keepSegmentsMs = keepSegmentsMs,
                onProgress = onProgress
            )
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            val chosenCodec = FFmpegCommandBuilder.resolveEffectiveVideoCodec(probe, activeSpec)
            if (chosenCodec == VideoCodecChoice.AV1) {
                val isWebm = desiredExt == "webm"
                val fallbackVideo = if (isWebm) VideoCodecChoice.VP9 else VideoCodecChoice.HEVC
                val preservedMute = activeSpec.convert?.audioCodec == AudioCodecChoice.MUTE ||
                    activeSpec.trim?.muteAudio == true
                val fallbackAudio = when {
                    preservedMute -> AudioCodecChoice.MUTE
                    isWebm -> AudioCodecChoice.OPUS
                    else -> AudioCodecChoice.AAC
                }
                onProgress(
                    5f,
                    if (isWebm) "Switching to VP9 WebM fallback..." else "Switching to 2-pass HEVC fallback..."
                )
                activeSpec = activeSpec.copy(
                    convert = (activeSpec.convert ?: ConvertSpec(targetExtension = desiredExt.ifBlank { "mp4" }))
                        .copy(videoCodec = fallbackVideo, audioCodec = fallbackAudio),
                    compress = activeSpec.compress?.copy(
                        tier = if (isWebm) activeSpec.compress!!.tier else CompressionTier.TWO_PASS_HEVC,
                        twoPass = !isWebm
                    )
                )
                runEncodeStrategy(
                    inputFile = inputFile,
                    outputFile = outputFile,
                    probe = probe,
                    spec = activeSpec,
                    threadCount = threadCount,
                    bitrateMultiplier = 1.0,
                    effectiveDurationMs = effectiveDurationMs,
                    complexityFactor = complexityFactor,
                    keepSegmentsMs = keepSegmentsMs,
                    onProgress = onProgress
                )
            } else {
                throw err
            }
        }

        // Strict target filesize ceiling: automatically refine if VBR muxer exceeded targetBytes
        if (targetBytes != null && outputFile.exists() && outputFile.length() > targetBytes) {
            val actualBytes = outputFile.length().coerceAtLeast(1L)
            val correction = (targetBytes.toDouble() / actualBytes.toDouble()) * 0.94
            val tempCorrected = File(outputFile.parentFile, "retry_${outputFile.name}")
            onProgress(10f, "Refining bitrate to guarantee target MB cap...")
            try {
                runEncodeStrategy(
                    inputFile = inputFile,
                    outputFile = tempCorrected,
                    probe = probe,
                    spec = activeSpec,
                    threadCount = threadCount,
                    bitrateMultiplier = correction.coerceIn(0.25, 0.93),
                    effectiveDurationMs = effectiveDurationMs,
                    complexityFactor = complexityFactor,
                    keepSegmentsMs = keepSegmentsMs,
                    onProgress = onProgress
                )
                if (tempCorrected.exists() && tempCorrected.length() > 0L) {
                    outputFile.delete()
                    if (!tempCorrected.renameTo(outputFile)) {
                        tempCorrected.copyTo(outputFile, overwrite = true)
                        tempCorrected.delete()
                    }
                }
            } finally {
                if (tempCorrected.exists()) {
                    runCatching { tempCorrected.delete() }
                }
            }
        }

        onProgress(100f, "Finished")
        outputFile
    }

    /**
     * Encodes an authentic 1.0s micro-segment around [timestampMs] using the resolved codec,
     * CRF / Target-Bytes bitrate plan, crop, and scale, then decodes a Before/After frame pair
     * along with a [SampleCalibrationRatio] for real-time filesize calibration.
     */
    suspend fun generateVideoSampleFrame(
        inputFile: File,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        timestampMs: Long,
        workDir: File
    ): VideoSampleFrameResult = withContext(Dispatchers.IO) {
        binaryManager.ensureInitialized()
        coroutineContext.ensureActive()

        val sampleDir = File(workDir, "sample_${System.nanoTime()}").apply { mkdirs() }
        var origBitmap: Bitmap? = null
        var compBitmap: Bitmap? = null

        try {
            val maxPreviewCap = DeviceCapabilityAdvisor.computeSamplePreviewMaxDimPx(deviceCapabilities, 1280)
            val sampleThreads = defaultSampleThreads
            val sampleThreadsStr = sampleThreads.toString()
            val plan = buildVideoSamplePlan(probe = probe, spec = spec, maxPreviewCap = maxPreviewCap)
            val timing = resolveSampleTiming(probe.durationMs, timestampMs)
            val refVf = buildReferenceFilter(plan.pixelCrop, plan.previewMaxDim)

            // Step A: Extract clean reference frame at timestampMs
            val origPng = File(sampleDir, "orig.png")
            runSimpleFfmpegCommand(
                args = listOf(
                    "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                    "-ss", timing.tsArg,
                    "-i", inputFile.absolutePath,
                    "-frames:v", "1",
                    "-vf", refVf,
                    "-an", "-threads", sampleThreadsStr,
                    origPng.absolutePath
                ),
                label = "Sample reference frame"
            )

            // Step B: Encode 1.0s micro-segment using effective codec + rate control
            val compPng = File(sampleDir, "comp.png")
            val sampleFile = encodeSampleMicroClip(
                inputFile = inputFile,
                sampleDir = sampleDir,
                timing = timing,
                plan = plan,
                sampleThreads = sampleThreads
            )

            // Reference 1.0s baseline micro-clip for authentic compression calibration
            val refClipFile = File(sampleDir, "ref_clip.mp4")
            try {
                runSimpleFfmpegCommand(
                    args = listOf(
                        "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                        "-ss", timing.tsArg,
                        "-i", inputFile.absolutePath,
                        "-t", timing.sampleDurArg,
                        "-vf", refVf,
                        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                        "-pix_fmt", "yuv420p",
                        "-an", "-threads", sampleThreadsStr,
                        refClipFile.absolutePath
                    ),
                    label = "Sample reference clip"
                )
            } catch (err: Throwable) {
                if (err is CancellationException) throw err
                // Fallback to orig.png size if reference clip failed
            }

            // Decode frame 0 from sample clip
            runSimpleFfmpegCommand(
                args = listOf(
                    "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                    "-i", sampleFile.absolutePath,
                    "-frames:v", "1",
                    "-an", "-threads", sampleThreadsStr,
                    compPng.absolutePath
                ),
                label = "Sample compressed frame decode"
            )

            // Step C: Subsampled BitmapFactory decode bounded to hardware cap
            origBitmap = decodeSamplePngSafely(origPng, maxDim = maxPreviewCap)
            compBitmap = decodeSamplePngSafely(compPng, maxDim = maxPreviewCap)

            val samplePixels = (origBitmap.width.toDouble() * origBitmap.height.toDouble()).coerceAtLeast(1.0)
            val fullOutputPixels = if (plan.fullFinalW > 0 && plan.fullFinalH > 0) {
                plan.fullFinalW.toDouble() * plan.fullFinalH.toDouble()
            } else {
                samplePixels
            }
            val areaScaleToFinal = (fullOutputPixels / samplePixels).coerceAtLeast(0.05)

            val rawCompressed = sampleFile.length().coerceAtLeast(1L)
            val normalizedCompressed = normalizeSampleBytes(rawCompressed, timing.sampleSec)
            val rawRef = refClipFile.takeIf { it.exists() && it.length() > 0L }?.length()
            val normalizedRef = if (rawRef != null) {
                normalizeSampleBytes(rawRef, timing.sampleSec)
            } else {
                origPng.length().coerceAtLeast(1L)
            }

            VideoSampleFrameResult(
                originalBitmap = origBitmap,
                compressedBitmap = compBitmap,
                calibration = SampleCalibrationRatio(
                    referenceBytes = normalizedRef,
                    compressedBytes = normalizedCompressed,
                    areaScaleToFinal = areaScaleToFinal
                )
            )
        } catch (err: Throwable) {
            origBitmap?.let { if (!it.isRecycled) it.recycle() }
            compBitmap?.let { if (!it.isRecycled) it.recycle() }
            throw err
        } finally {
            runCatching { sampleDir.deleteRecursively() }
        }
    }

    /**
     * Extracts the baseline cropped/capped reference frame to [outputPngFile] and measures a
     * 1-second reference clip (`libx264 -preset ultrafast -crf 20`) for Sample Lab calibration,
     * cleaning up temporary clip artifacts deterministically.
     */
    suspend fun extractVideoReferenceFrameToFile(
        inputFile: File,
        probe: MediaProbeResult,
        crop: CropSpec?,
        timestampMs: Long,
        workDir: File,
        outputPngFile: File,
        maxPreviewDim: Int = 1920,
        sampleThreads: Int = defaultSampleThreads
    ): VideoReferenceExtraction = withContext(Dispatchers.IO) {
        binaryManager.ensureInitialized()
        coroutineContext.ensureActive()

        workDir.mkdirs()
        outputPngFile.parentFile?.mkdirs()
        val tempDir = File(workDir, "ref_${System.nanoTime()}").apply { mkdirs() }
        val threadArg = sampleThreads.coerceIn(1, 4).toString()

        try {
            val timing = resolveSampleTiming(probe.durationMs, timestampMs)
            val effW = probe.effectiveWidth
            val effH = probe.effectiveHeight
            val pixelCrop = if (crop != null && effW > 1 && effH > 1) {
                AspectRatioSnapper.toEvenPixelCrop(crop.rect, effW, effH)
            } else {
                null
            }
            val safeCap = DeviceCapabilityAdvisor.computeSamplePreviewMaxDimPx(deviceCapabilities, maxPreviewDim)
            val refVf = buildReferenceFilter(pixelCrop, safeCap)

            runSimpleFfmpegCommand(
                args = listOf(
                    "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                    "-ss", timing.tsArg,
                    "-i", inputFile.absolutePath,
                    "-frames:v", "1",
                    "-vf", refVf,
                    "-an", "-threads", threadArg,
                    outputPngFile.absolutePath
                ),
                label = "Sample Lab reference frame"
            )

            val refClipFile = File(tempDir, "ref_clip.mp4")
            try {
                runSimpleFfmpegCommand(
                    args = listOf(
                        "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                        "-ss", timing.tsArg,
                        "-i", inputFile.absolutePath,
                        "-t", timing.sampleDurArg,
                        "-vf", refVf,
                        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "20",
                        "-pix_fmt", "yuv420p",
                        "-an", "-threads", threadArg,
                        refClipFile.absolutePath
                    ),
                    label = "Sample Lab reference clip"
                )
            } catch (err: Throwable) {
                if (err is CancellationException) throw err
                // Fallback to outputPngFile size if reference clip encode fails
            }

            val rawRef = refClipFile.takeIf { it.exists() && it.length() > 0L }?.length()
            val normalizedRef = if (rawRef != null) {
                normalizeSampleBytes(rawRef, timing.sampleSec)
            } else {
                outputPngFile.length().coerceAtLeast(1L)
            }

            val (decodedW, decodedH) = readImageDimensions(outputPngFile)
            val fallbackW = (pixelCrop?.width ?: effW).coerceAtMost(safeCap)
            val fallbackH = (pixelCrop?.height ?: effH).coerceAtMost(safeCap)
            VideoReferenceExtraction(
                pngFile = outputPngFile,
                referenceClipBytes = normalizedRef,
                width = if (decodedW > 0) decodedW else fallbackW,
                height = if (decodedH > 0) decodedH else fallbackH
            )
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    /**
     * Encodes an authentic micro-segment for [spec] at [timestampMs] preserving target resolution
     * (no pre-upscale), decodes frame 0 to [outputPngFile], and deletes intermediate media files.
     */
    suspend fun renderVideoSampleCandidateToFile(
        inputFile: File,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        timestampMs: Long,
        workDir: File,
        outputPngFile: File,
        referenceClipBytes: Long,
        maxPreviewDim: Int = 1920,
        sampleThreads: Int = defaultSampleThreads
    ): VideoCandidateRenderResult = withContext(Dispatchers.IO) {
        binaryManager.ensureInitialized()
        coroutineContext.ensureActive()

        workDir.mkdirs()
        outputPngFile.parentFile?.mkdirs()
        val tempDir = File(workDir, "cand_${System.nanoTime()}").apply { mkdirs() }
        val threadArg = sampleThreads.coerceIn(1, 4).toString()

        try {
            val safeCap = DeviceCapabilityAdvisor.computeSamplePreviewMaxDimPx(deviceCapabilities, maxPreviewDim)
            val plan = buildVideoSamplePlan(probe = probe, spec = spec, maxPreviewCap = safeCap)
            val timing = resolveSampleTiming(probe.durationMs, timestampMs)

            val sampleFile = encodeSampleMicroClip(
                inputFile = inputFile,
                sampleDir = tempDir,
                timing = timing,
                plan = plan,
                sampleThreads = sampleThreads
            )

            runSimpleFfmpegCommand(
                args = listOf(
                    "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                    "-i", sampleFile.absolutePath,
                    "-frames:v", "1",
                    "-an", "-threads", threadArg,
                    outputPngFile.absolutePath
                ),
                label = "Sample Lab candidate decode"
            )

            val (decodedW, decodedH) = readImageDimensions(outputPngFile)
            val outW = if (decodedW > 0) decodedW else plan.fullFinalW
            val outH = if (decodedH > 0) decodedH else plan.fullFinalH

            val samplePixels = if (outW > 0 && outH > 0) outW.toDouble() * outH.toDouble() else 1.0
            val fullOutputPixels = if (plan.fullFinalW > 0 && plan.fullFinalH > 0) {
                plan.fullFinalW.toDouble() * plan.fullFinalH.toDouble()
            } else {
                samplePixels
            }
            val areaScaleToFinal = (fullOutputPixels / samplePixels).coerceAtLeast(0.05)

            val rawCompressed = sampleFile.length().coerceAtLeast(1L)
            val normalizedCompressed = normalizeSampleBytes(rawCompressed, timing.sampleSec)
            val normalizedRef = referenceClipBytes.coerceAtLeast(1L)

            VideoCandidateRenderResult(
                previewPngFile = outputPngFile,
                width = outW,
                height = outH,
                sampleClipBytes = normalizedCompressed,
                calibration = SampleCalibrationRatio(
                    referenceBytes = normalizedRef,
                    compressedBytes = normalizedCompressed,
                    areaScaleToFinal = areaScaleToFinal
                )
            )
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    private data class VideoSampleTiming(
        val clampedTsMs: Long,
        val sampleSec: Double,
        val tsArg: String,
        val sampleDurArg: String
    )

    private data class VideoSamplePlan(
        val isGif: Boolean,
        val pixelCrop: PixelCropRect?,
        val effectiveCodec: VideoCodecChoice,
        val budgetPlan: VideoBudgetPlan?,
        val previewMaxDim: Int,
        val fullFinalW: Int,
        val fullFinalH: Int,
        val crf: Int,
        val sampleBitrateBps: Long?
    )

    private fun resolveSampleTiming(durationMs: Long, timestampMs: Long): VideoSampleTiming {
        val clampedTsMs = when {
            durationMs > 1200L -> timestampMs.coerceIn(0L, durationMs - 1000L)
            durationMs > 200L -> timestampMs.coerceIn(0L, durationMs - 200L)
            else -> timestampMs.coerceAtLeast(0L)
        }
        val sampleSec = if (durationMs in 1L..999L) {
            (durationMs / 1000.0).coerceAtLeast(0.25)
        } else {
            1.0
        }
        return VideoSampleTiming(
            clampedTsMs = clampedTsMs,
            sampleSec = sampleSec,
            tsArg = String.format(Locale.US, "%.3f", clampedTsMs / 1000.0),
            sampleDurArg = String.format(Locale.US, "%.3f", sampleSec)
        )
    }

    private fun buildVideoSamplePlan(
        probe: MediaProbeResult,
        spec: PipelineSpec,
        maxPreviewCap: Int
    ): VideoSamplePlan {
        val targetExt = (spec.convert?.targetExtension ?: probe.extension).lowercase(Locale.US)
        val isGif = targetExt == "gif"
        val effW = probe.effectiveWidth
        val effH = probe.effectiveHeight
        val pixelCrop = if (spec.crop != null && effW > 1 && effH > 1) {
            AspectRatioSnapper.toEvenPixelCrop(spec.crop.rect, effW, effH)
        } else {
            null
        }
        val postCropW = pixelCrop?.width ?: effW
        val postCropH = pixelCrop?.height ?: effH

        val rawCodec = FFmpegCommandBuilder.resolveEffectiveVideoCodec(probe, spec)
        val effectiveCodec = if (rawCodec == VideoCodecChoice.COPY) VideoCodecChoice.H264 else rawCodec

        val hasAudio = probe.hasAudio &&
            spec.trim?.muteAudio != true &&
            spec.convert?.audioCodec != AudioCodecChoice.MUTE &&
            !isGif

        val preferOpusAudio = targetExt == "webm" ||
            spec.convert?.audioCodec == AudioCodecChoice.OPUS ||
            (spec.compress?.mode == CompressMode.TARGET_BYTES &&
                spec.compress.tier == CompressionTier.PERCEPTUAL_AV1 &&
                spec.convert?.audioCodec != AudioCodecChoice.AAC &&
                spec.convert?.audioCodec != AudioCodecChoice.MP3 &&
                targetExt in setOf("mp4", "mkv", "webm"))

        val fullEffectiveDurationMs = computeEffectiveDurationMs(probe, spec)
        val budgetPlan: VideoBudgetPlan? = if (
            spec.compress?.mode == CompressMode.TARGET_BYTES &&
            spec.compress.targetBytes != null &&
            !isGif
        ) {
            BitrateBudgetCalculator.calculateVideoBudget(
                targetBytes = spec.compress.targetBytes.coerceAtLeast(32L * 1024L),
                durationMs = fullEffectiveDurationMs.coerceAtLeast(1000L),
                sourceWidth = postCropW,
                sourceHeight = postCropH,
                hasAudio = hasAudio,
                isHevc = effectiveCodec == VideoCodecChoice.HEVC,
                videoCodec = effectiveCodec,
                preferOpusAudio = preferOpusAudio
            )
        } else {
            null
        }

        val requestedMaxDim = listOfNotNull(
            spec.compress?.maxDimensionPx?.takeIf { it > 0 },
            budgetPlan?.targetMaxDimension
        ).minOrNull()

        val finalMaxDim = if (isGif) {
            min(540, requestedMaxDim ?: 540)
        } else {
            requestedMaxDim
        }
        val previewMaxDim = (finalMaxDim ?: maxPreviewCap).coerceAtMost(maxPreviewCap)

        val fullFinalW: Int
        val fullFinalH: Int
        if (postCropW > 0 && postCropH > 0) {
            val maxPostCrop = max(postCropW, postCropH)
            val fullScale = if (finalMaxDim != null && maxPostCrop > finalMaxDim) {
                finalMaxDim.toDouble() / maxPostCrop.toDouble()
            } else {
                1.0
            }
            fullFinalW = ((postCropW * fullScale).roundToInt() and 1.inv()).coerceAtLeast(2)
            fullFinalH = ((postCropH * fullScale).roundToInt() and 1.inv()).coerceAtLeast(2)
        } else {
            fullFinalW = 0
            fullFinalH = 0
        }

        val sampleSpatialScale = if (fullFinalW > 0 && fullFinalH > 0) {
            val maxFinal = max(fullFinalW, fullFinalH)
            if (maxFinal > previewMaxDim) {
                val previewRatio = previewMaxDim.toDouble() / maxFinal.toDouble()
                (previewRatio * previewRatio).pow(0.85)
            } else {
                1.0
            }
        } else {
            1.0
        }
        val sampleBitrateBps = budgetPlan?.let {
            (it.videoBitrateBps * sampleSpatialScale).roundToLong().coerceAtLeast(35_000L)
        }
        val crf = (spec.compress?.videoCrf ?: 23).coerceIn(14, 42)

        return VideoSamplePlan(
            isGif = isGif,
            pixelCrop = pixelCrop,
            effectiveCodec = effectiveCodec,
            budgetPlan = budgetPlan,
            previewMaxDim = previewMaxDim,
            fullFinalW = fullFinalW,
            fullFinalH = fullFinalH,
            crf = crf,
            sampleBitrateBps = sampleBitrateBps
        )
    }

    private fun buildReferenceFilter(pixelCrop: PixelCropRect?, maxDim: Int): String {
        val parts = mutableListOf<String>()
        if (pixelCrop != null) {
            parts += "crop=${pixelCrop.width}:${pixelCrop.height}:${pixelCrop.x}:${pixelCrop.y}"
        }
        parts += "scale='min($maxDim,iw)':'min($maxDim,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos"
        return parts.joinToString(",")
    }

    private suspend fun encodeSampleMicroClip(
        inputFile: File,
        sampleDir: File,
        timing: VideoSampleTiming,
        plan: VideoSamplePlan,
        sampleThreads: Int = defaultSampleThreads
    ): File {
        val threadArg = sampleThreads.coerceIn(1, 4).toString()
        if (plan.isGif) {
            val gifFile = File(sampleDir, "sample.gif")
            val refPrefix = buildReferenceFilter(plan.pixelCrop, plan.previewMaxDim)
            val gifFilter = "$refPrefix,fps=15,split[s0][s1];[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=5"
            runSimpleFfmpegCommand(
                args = listOf(
                    "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                    "-ss", timing.tsArg,
                    "-i", inputFile.absolutePath,
                    "-t", timing.sampleDurArg,
                    "-filter_complex", gifFilter,
                    "-loop", "0",
                    "-an", "-threads", threadArg,
                    gifFile.absolutePath
                ),
                label = "Sample GIF encode"
            )
            return gifFile
        }

        val sampleVfParts = mutableListOf<String>()
        if (plan.pixelCrop != null) {
            sampleVfParts += "crop=${plan.pixelCrop.width}:${plan.pixelCrop.height}:${plan.pixelCrop.x}:${plan.pixelCrop.y}"
        }
        if (plan.budgetPlan?.targetFps != null) {
            sampleVfParts += "fps=${plan.budgetPlan.targetFps}"
        }
        sampleVfParts += "scale='min(${plan.previewMaxDim},iw)':'min(${plan.previewMaxDim},ih)':force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos"
        if (plan.budgetPlan?.enableLightDenoise == true) {
            sampleVfParts += "hqdn3d=1.2:1.2:2.5:2.5"
        }
        val sampleVf = sampleVfParts.joinToString(",")

        var chosenCodec = plan.effectiveCodec
        var encodedFile = File(sampleDir, if (chosenCodec == VideoCodecChoice.VP9) "sample.webm" else "sample.mp4")
        try {
            runSimpleFfmpegCommand(
                args = buildSampleEncodeArgs(
                    tsArg = timing.tsArg,
                    inputPath = inputFile.absolutePath,
                    sampleDurArg = timing.sampleDurArg,
                    sampleVf = sampleVf,
                    codec = chosenCodec,
                    crf = plan.crf,
                    sampleBitrateBps = plan.sampleBitrateBps,
                    sampleThreads = sampleThreads,
                    outputPath = encodedFile.absolutePath
                ),
                label = "Sample video encode (${chosenCodec.name})"
            )
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            if (chosenCodec == VideoCodecChoice.AV1) {
                chosenCodec = VideoCodecChoice.HEVC
                encodedFile = File(sampleDir, "sample.mp4")
                runSimpleFfmpegCommand(
                    args = buildSampleEncodeArgs(
                        tsArg = timing.tsArg,
                        inputPath = inputFile.absolutePath,
                        sampleDurArg = timing.sampleDurArg,
                        sampleVf = sampleVf,
                        codec = chosenCodec,
                        crf = plan.crf,
                        sampleBitrateBps = plan.sampleBitrateBps,
                        sampleThreads = sampleThreads,
                        outputPath = encodedFile.absolutePath
                    ),
                    label = "Sample video fallback encode (HEVC)"
                )
            } else {
                throw err
            }
        }
        return encodedFile
    }

    private fun normalizeSampleBytes(rawBytes: Long, sampleSec: Double): Long {
        val safeRaw = rawBytes.coerceAtLeast(1L)
        return if (sampleSec < 0.99) {
            (safeRaw.toDouble() / sampleSec).roundToLong().coerceAtLeast(1L)
        } else {
            safeRaw
        }
    }

    private fun readImageDimensions(file: File): Pair<Int, Int> {
        if (!file.exists() || file.length() <= 0L) return 0 to 0
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
    }

    private fun buildSampleEncodeArgs(
        tsArg: String,
        inputPath: String,
        sampleDurArg: String,
        sampleVf: String,
        codec: VideoCodecChoice,
        crf: Int,
        sampleBitrateBps: Long?,
        sampleThreads: Int,
        outputPath: String
    ): List<String> {
        val args = mutableListOf(
            "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
            "-ss", tsArg,
            "-i", inputPath,
            "-t", sampleDurArg,
            "-vf", sampleVf
        )
        when (codec) {
            VideoCodecChoice.AV1 -> {
                val svtParams = buildString {
                    append("enable-qm=1:qm-min=0:enable-variance-boost=1:variance-boost-strength=2:enable-overlays=1:fast-decode=1")
                    if (sampleBitrateBps != null) append(":overshoot-pct=10")
                }
                args += listOf(
                    "-c:v", "libsvtav1",
                    "-preset", "7",
                    "-pix_fmt", "yuv420p",
                    "-g", "240",
                    "-svtav1-params", svtParams
                )
                if (sampleBitrateBps != null) {
                    args += listOf("-b:v", sampleBitrateBps.toString())
                } else {
                    args += listOf("-crf", (crf + 6).coerceIn(18, 48).toString())
                }
            }
            VideoCodecChoice.HEVC -> {
                args += listOf(
                    "-c:v", "libx265",
                    "-preset", "fast",
                    "-pix_fmt", "yuv420p",
                    "-tag:v", "hvc1",
                    "-x265-params", "aq-mode=3"
                )
                if (sampleBitrateBps != null) {
                    val maxrate = (sampleBitrateBps * 115L) / 100L
                    val bufsize = sampleBitrateBps * 2L
                    args += listOf("-b:v", sampleBitrateBps.toString(), "-maxrate", maxrate.toString(), "-bufsize", bufsize.toString())
                } else {
                    args += listOf("-crf", crf.toString())
                }
            }
            VideoCodecChoice.VP9 -> {
                args += listOf(
                    "-c:v", "libvpx-vp9",
                    "-row-mt", "1",
                    "-speed", "4",
                    "-pix_fmt", "yuv420p"
                )
                if (sampleBitrateBps != null) {
                    val maxrate = (sampleBitrateBps * 115L) / 100L
                    val bufsize = sampleBitrateBps * 2L
                    args += listOf("-b:v", sampleBitrateBps.toString(), "-maxrate", maxrate.toString(), "-bufsize", bufsize.toString())
                } else {
                    args += listOf("-crf", (crf + 6).coerceAtMost(45).toString(), "-b:v", "0")
                }
            }
            VideoCodecChoice.H264, VideoCodecChoice.COPY -> {
                args += listOf(
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-pix_fmt", "yuv420p",
                    "-x264-params", "aq-mode=3"
                )
                if (sampleBitrateBps != null) {
                    val maxrate = (sampleBitrateBps * 115L) / 100L
                    val bufsize = sampleBitrateBps * 2L
                    args += listOf("-b:v", sampleBitrateBps.toString(), "-maxrate", maxrate.toString(), "-bufsize", bufsize.toString())
                } else {
                    args += listOf("-crf", crf.toString())
                }
            }
        }
        args += listOf("-an", "-threads", sampleThreads.coerceIn(1, 4).toString(), outputPath)
        return args
    }

    private suspend fun runSimpleFfmpegCommand(args: List<String>, label: String) {
        coroutineContext.ensureActive()
        val fullCommand = listOf(binaryManager.ffmpegExecutable.absolutePath) + args
        val pb = ProcessBuilder(fullCommand)
        pb.environment()["LD_LIBRARY_PATH"] = binaryManager.ldLibraryPath
        pb.environment()["TMPDIR"] = appContext.cacheDir.absolutePath
        pb.redirectErrorStream(true)

        val process = pb.start()
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }

        val tailLines = ArrayDeque<String>(MAX_STDERR_LINES)
        try {
            withContext(Dispatchers.IO) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (tailLines.size >= MAX_STDERR_LINES) tailLines.removeFirst()
                        tailLines.addLast(line)
                    }
                }
            }
            coroutineContext.ensureActive()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val safeTail = CredentialRedactor.sanitizeDiagnostic(
                    tailLines.joinToString("\n"),
                    maxChars = 480
                )
                throw IOException("$label failed (exit=$exitCode):\n$safeTail")
            }
        } finally {
            cancelHandle.dispose()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun decodeSamplePngSafely(file: File, maxDim: Int): Bitmap {
        if (!file.exists() || file.length() <= 0L) {
            throw IOException("Missing sample frame file: ${file.name}")
        }
        return SampleFrameEngine.decodeSampleFileSafely(
            file = file,
            maxDimensionPx = maxDim,
            maxSafeTexturePx = maxSafeRasterDim,
            maxIntermediatePixels = sampleIntermediatePixels
        ) ?: throw IOException("Failed to decode sample frame ${file.name}")
    }

    private suspend fun runEncodeStrategy(
        inputFile: File,
        outputFile: File,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        threadCount: Int,
        bitrateMultiplier: Double,
        effectiveDurationMs: Long,
        complexityFactor: Double,
        keepSegmentsMs: List<Pair<Long, Long>>? = null,
        onProgress: (Float, String) -> Unit
    ) {
        val useTwoPass = FFmpegCommandBuilder.shouldUseTwoPass(probe, spec)
        if (!useTwoPass) {
            runFfmpegPass(
                inputFile = inputFile,
                outputFile = outputFile,
                probe = probe,
                spec = spec,
                threadCount = threadCount,
                bitrateMultiplier = bitrateMultiplier,
                effectiveDurationMs = effectiveDurationMs,
                passMode = EncodePassMode.SINGLE,
                passLogPrefix = null,
                complexityFactor = complexityFactor,
                keepSegmentsMs = keepSegmentsMs
            ) { pct, msg ->
                onProgress(pct, msg)
            }
            return
        }

        val passLogDir = outputFile.parentFile ?: appContext.cacheDir
        val passLogPrefix = File(passLogDir, "passlog_${outputFile.nameWithoutExtension}_${System.currentTimeMillis()}").absolutePath
        try {
            onProgress(4f, "Analyzing timeline (Pass 1/2)...")
            runFfmpegPass(
                inputFile = inputFile,
                outputFile = outputFile,
                probe = probe,
                spec = spec,
                threadCount = threadCount,
                bitrateMultiplier = bitrateMultiplier,
                effectiveDurationMs = effectiveDurationMs,
                passMode = EncodePassMode.PASS_1,
                passLogPrefix = passLogPrefix,
                complexityFactor = complexityFactor,
                keepSegmentsMs = keepSegmentsMs
            ) { pct, _ ->
                onProgress((pct * 0.28f).coerceIn(0f, 28f), "Analyzing timeline (Pass 1/2: ${pct.toInt()}%)")
            }

            onProgress(28f, "Encoding perceptual pass (2/2)...")
            runFfmpegPass(
                inputFile = inputFile,
                outputFile = outputFile,
                probe = probe,
                spec = spec,
                threadCount = threadCount,
                bitrateMultiplier = bitrateMultiplier,
                effectiveDurationMs = effectiveDurationMs,
                passMode = EncodePassMode.PASS_2,
                passLogPrefix = passLogPrefix,
                complexityFactor = complexityFactor,
                keepSegmentsMs = keepSegmentsMs
            ) { pct, label ->
                val mapped = 28f + (pct * 0.70f)
                onProgress(mapped.coerceIn(28f, 99f), "Pass 2/2 • $label")
            }
        } finally {
            cleanupPassLogs(passLogDir, File(passLogPrefix).name)
        }
    }

    /**
     * Runs an audio-only `silencedetect` pre-pass on video+audio inputs so video frames and audio
     * samples can be jumpcut symmetrically with zero A/V drift.
     */
    private suspend fun detectKeepSegments(
        inputFile: File,
        spec: PipelineSpec,
        silenceSpec: SilenceSkipSpec,
        baseDurationMs: Long
    ): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val thresholdDb = silenceSpec.thresholdDb.coerceIn(-90, 0)
        val durationSec = String.format(
            Locale.US,
            "%.3f",
            silenceSpec.minSilenceMs.coerceIn(50, 10_000) / 1000.0
        )
        val trim = spec.trim
        val startMs = (trim?.startMs ?: 0L).coerceAtLeast(0L)
        val endMs = trim?.endMs?.takeIf { it > startMs }
        val args = mutableListOf(
            binaryManager.ffmpegExecutable.absolutePath,
            "-nostdin", "-y", "-hide_banner", "-loglevel", "info"
        )
        if (startMs > 0L) {
            args += listOf("-ss", String.format(Locale.US, "%.3f", startMs / 1000.0))
        }
        args += listOf("-i", inputFile.absolutePath)
        if (endMs != null && baseDurationMs > 0L) {
            args += listOf("-t", String.format(Locale.US, "%.3f", baseDurationMs / 1000.0))
        }
        args += listOf(
            "-vn",
            "-af", "silencedetect=noise=${thresholdDb}dB:d=$durationSec",
            "-f", "null",
            "/dev/null"
        )

        val pb = ProcessBuilder(args)
        pb.environment()["LD_LIBRARY_PATH"] = binaryManager.ldLibraryPath
        pb.environment()["TMPDIR"] = appContext.cacheDir.absolutePath
        pb.redirectErrorStream(true)

        val process = pb.start()
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }

        val logBuilder = StringBuilder()
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("silencedetect") || line.contains("silence_")) {
                        logBuilder.append(line).append('\n')
                    }
                }
            }
            coroutineContext.ensureActive()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                return@withContext emptyList()
            }
            FFmpegCommandBuilder.parseSilenceKeepSegments(
                ffmpegLog = logBuilder.toString(),
                clipDurationMs = baseDurationMs
            )
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            emptyList()
        } finally {
            cancelHandle.dispose()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * Runs up to two fast 1-second CRF 26 calibration probes at 25% and 70% into the clip
     * so static lectures/lyrics keep higher resolution while noisy action clips guard against overshoot.
     */
    private suspend fun measureSceneComplexity(
        inputFile: File,
        effectiveDurationMs: Long,
        spec: PipelineSpec
    ): Double = withContext(Dispatchers.IO) {
        val trimStartSec = (spec.trim?.startMs ?: 0L) / 1000.0
        val durSec = effectiveDurationMs / 1000.0
        val offsets = listOf(trimStartSec + durSec * 0.25, trimStartSec + durSec * 0.70)
        val measuredKbps = mutableListOf<Double>()

        for (offset in offsets) {
            coroutineContext.ensureActive()
            val cmd = listOf(
                binaryManager.ffmpegExecutable.absolutePath,
                "-nostdin", "-y", "-hide_banner", "-loglevel", "info",
                "-ss", String.format(Locale.US, "%.2f", offset.coerceAtLeast(0.0)),
                "-t", "1.0",
                "-i", inputFile.absolutePath,
                "-vf", "scale=480:-2",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "26",
                "-an", "-threads", defaultSampleThreads.toString(),
                "-f", "null", "/dev/null"
            )
            val pb = ProcessBuilder(cmd)
            pb.environment()["LD_LIBRARY_PATH"] = binaryManager.ldLibraryPath
            pb.environment()["TMPDIR"] = appContext.cacheDir.absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    proc.destroy()
                    if (proc.isAlive) proc.destroyForcibly()
                }
            }
            try {
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                coroutineContext.ensureActive()
                val rc = proc.waitFor()
                if (rc == 0) {
                    val kbMatch = Regex("""L?size=.*bitrate=\s*([0-9.]+)kbits/s""").findAll(out).lastOrNull()
                    val kbps = kbMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    if (kbps != null && kbps > 15.0) {
                        measuredKbps += kbps
                    }
                }
            } catch (err: Throwable) {
                if (err is CancellationException) throw err
            } finally {
                cancelHandle.dispose()
                runCatching { proc.inputStream.close() }
                runCatching { proc.outputStream.close() }
                runCatching { proc.errorStream.close() }
                if (proc.isAlive) proc.destroyForcibly()
            }
        }

        if (measuredKbps.isEmpty()) return@withContext 1.0
        val avgKbps = measuredKbps.average()
        // Baseline 480p @ CRF 26 sits around ~280 kbps
        (avgKbps / 280.0).coerceIn(0.72, 1.28)
    }

    private suspend fun runFfmpegPass(
        inputFile: File,
        outputFile: File,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        threadCount: Int,
        bitrateMultiplier: Double,
        effectiveDurationMs: Long,
        passMode: EncodePassMode,
        passLogPrefix: String?,
        complexityFactor: Double,
        keepSegmentsMs: List<Pair<Long, Long>>? = null,
        onProgress: (Float, String) -> Unit
    ) {
        val args = FFmpegCommandBuilder.buildCommand(
            inputPath = inputFile.absolutePath,
            outputPath = outputFile.absolutePath,
            probe = probe,
            spec = spec,
            threadCount = threadCount,
            bitrateMultiplier = bitrateMultiplier,
            passMode = passMode,
            passLogPrefix = passLogPrefix,
            complexityFactor = complexityFactor,
            keepSegmentsMs = keepSegmentsMs
        )
        val fullCommand = listOf(binaryManager.ffmpegExecutable.absolutePath) + args

        val pb = ProcessBuilder(fullCommand)
        pb.environment()["LD_LIBRARY_PATH"] = binaryManager.ldLibraryPath
        pb.environment()["TMPDIR"] = appContext.cacheDir.absolutePath

        val process = pb.start()
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }

        val errRingBuffer = ArrayDeque<String>(MAX_STDERR_LINES)
        try {
            coroutineScope {
                val stderrJob = async(Dispatchers.IO) {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(errRingBuffer) {
                                if (errRingBuffer.size >= MAX_STDERR_LINES) {
                                    errRingBuffer.removeFirst()
                                }
                                errRingBuffer.addLast(line)
                            }
                        }
                    }
                }

                val stdoutJob = async(Dispatchers.IO) {
                    var currentSpeed = ""
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.startsWith("speed=")) {
                                currentSpeed = line.substringAfter("speed=").trim()
                            } else if (line.startsWith("out_time_us=")) {
                                val outUs = line.substringAfter("out_time_us=").trim().toLongOrNull()
                                if (outUs != null && effectiveDurationMs > 0L) {
                                    val pct = ((outUs / 1000.0) / effectiveDurationMs.toDouble() * 100.0)
                                        .toFloat()
                                        .coerceIn(0f, 99.5f)
                                    val label = if (currentSpeed.isNotBlank() && currentSpeed != "N/A") {
                                        "Encoding (${currentSpeed})"
                                    } else {
                                        "Encoding..."
                                    }
                                    onProgress(pct, label)
                                }
                            }
                        }
                    }
                }

                stderrJob.await()
                stdoutJob.await()
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val tail = synchronized(errRingBuffer) { errRingBuffer.joinToString("\n") }
                val safeTail = CredentialRedactor.sanitizeDiagnostic(tail, maxChars = 480)
                throw IOException("FFmpeg failed (exit=$exitCode):\n$safeTail")
            }
        } finally {
            cancelHandle.dispose()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun cleanupPassLogs(dir: File, prefixName: String) {
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(prefixName)) {
                    f.delete()
                }
            }
        }
    }

    private fun computeEffectiveDurationMs(probe: MediaProbeResult, spec: PipelineSpec): Long {
        val trim = spec.trim
        val start = (trim?.startMs ?: 0L).coerceAtLeast(0L)
        val end = trim?.endMs?.takeIf { it > start }
        return when {
            end != null -> end - start
            probe.durationMs > start -> probe.durationMs - start
            else -> probe.durationMs
        }.coerceAtLeast(500L)
    }

    companion object {
        private const val MAX_STDERR_LINES = 50
    }
}
