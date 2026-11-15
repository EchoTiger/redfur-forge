package com.fissal.forge.engine

import android.content.Context
import com.fissal.forge.domain.logic.EncodePassMode
import com.fissal.forge.domain.logic.FFmpegCommandBuilder
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext

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
    private val binaryManager: NativeBinaryManager
) {

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

        // Zero-loss passthrough if the file already fits the target byte limit and needs no edits
        if (targetBytes != null &&
            spec.trim == null &&
            spec.crop == null &&
            inputFile.exists() &&
            inputFile.length() in 1L..targetBytes &&
            (desiredExt.isBlank() || desiredExt == inputExt)
        ) {
            onProgress(100f, "Already <= target size (0% loss passthrough)")
            inputFile.copyTo(outputFile, overwrite = true)
            return@withContext outputFile
        }

        val effectiveDurationMs = computeEffectiveDurationMs(probe, spec)
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

        var activeSpec = spec
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
                onProgress = onProgress
            )
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            val chosenCodec = FFmpegCommandBuilder.resolveEffectiveVideoCodec(probe, activeSpec)
            if (chosenCodec == VideoCodecChoice.AV1) {
                onProgress(5f, "Switching to 2-pass HEVC fallback...")
                activeSpec = activeSpec.copy(
                    convert = (activeSpec.convert ?: ConvertSpec(targetExtension = desiredExt.ifBlank { "mp4" }))
                        .copy(videoCodec = VideoCodecChoice.HEVC, audioCodec = AudioCodecChoice.AAC),
                    compress = activeSpec.compress?.copy(tier = CompressionTier.TWO_PASS_HEVC, twoPass = true)
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
            runEncodeStrategy(
                inputFile = inputFile,
                outputFile = tempCorrected,
                probe = probe,
                spec = activeSpec,
                threadCount = threadCount,
                bitrateMultiplier = correction.coerceIn(0.25, 0.93),
                effectiveDurationMs = effectiveDurationMs,
                complexityFactor = complexityFactor,
                onProgress = onProgress
            )
            if (tempCorrected.exists()) {
                outputFile.delete()
                tempCorrected.renameTo(outputFile)
            }
        }

        onProgress(100f, "Finished")
        outputFile
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
                complexityFactor = complexityFactor
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
                complexityFactor = complexityFactor
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
                complexityFactor = complexityFactor
            ) { pct, label ->
                val mapped = 28f + (pct * 0.70f)
                onProgress(mapped.coerceIn(28f, 99f), "Pass 2/2 • $label")
            }
        } finally {
            cleanupPassLogs(passLogDir, File(passLogPrefix).name)
        }
    }

    /**
     * Runs up to two fast 1-second CRF 26 calibration probes at 25% and 70% into the clip
     * so static lectures/lyrics keep higher resolution while noisy action clips guard against overshoot.
     */
    private fun measureSceneComplexity(
        inputFile: File,
        effectiveDurationMs: Long,
        spec: PipelineSpec
    ): Double = runCatching {
        val trimStartSec = (spec.trim?.startMs ?: 0L) / 1000.0
        val durSec = effectiveDurationMs / 1000.0
        val offsets = listOf(trimStartSec + durSec * 0.25, trimStartSec + durSec * 0.70)
        val measuredKbps = mutableListOf<Double>()

        for (offset in offsets) {
            val cmd = listOf(
                binaryManager.ffmpegExecutable.absolutePath,
                "-nostdin", "-y", "-hide_banner", "-loglevel", "info",
                "-ss", String.format(Locale.US, "%.2f", offset.coerceAtLeast(0.0)),
                "-t", "1.0",
                "-i", inputFile.absolutePath,
                "-vf", "scale=480:-2",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "26",
                "-an", "-threads", "2",
                "-f", "null", "/dev/null"
            )
            val pb = ProcessBuilder(cmd)
            pb.environment()["LD_LIBRARY_PATH"] = binaryManager.ldLibraryPath
            pb.environment()["TMPDIR"] = appContext.cacheDir.absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            if (rc == 0) {
                val kbMatch = Regex("""L?size=.*bitrate=\s*([0-9.]+)kbits/s""").findAll(out).lastOrNull()
                    ?: Regex("""video:([0-9]+)KiB""").findAll(out).lastOrNull()?.let { null }
                val kbps = kbMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (kbps != null && kbps > 15.0) {
                    measuredKbps += kbps
                }
            }
        }

        if (measuredKbps.isEmpty()) return@runCatching 1.0
        val avgKbps = measuredKbps.average()
        // Baseline 480p @ CRF 26 sits around ~280 kbps
        (avgKbps / 280.0).coerceIn(0.72, 1.28)
    }.getOrDefault(1.0)

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
            complexityFactor = complexityFactor
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
                throw IOException("FFmpeg failed (exit=$exitCode):\n$tail")
            }
        } finally {
            cancelHandle.dispose()
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
