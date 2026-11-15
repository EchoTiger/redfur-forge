package com.fissal.forge.engine

import android.content.Context
import com.fissal.forge.domain.logic.FFmpegCommandBuilder
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Wraps bundled libffmpeg.so CLI execution with streaming progress reporting,
 * bounded stderr ring buffer, instant coroutine cancellation, and automatic target-byte fallback pass.
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

        val effectiveDurationMs = computeEffectiveDurationMs(probe, spec)
        runFfmpegPass(
            inputFile = inputFile,
            outputFile = outputFile,
            probe = probe,
            spec = spec,
            threadCount = threadCount,
            bitrateMultiplier = 1.0,
            effectiveDurationMs = effectiveDurationMs,
            onProgress = onProgress
        )

        // Guarantee target filesize ceiling with a corrective pass if VBR mux slightly overshot
        val targetBytes = if (spec.compress?.mode == CompressMode.TARGET_BYTES) spec.compress.targetBytes else null
        if (targetBytes != null && outputFile.exists() && outputFile.length() > targetBytes) {
            val actualBytes = outputFile.length().coerceAtLeast(1L)
            val correction = (targetBytes.toDouble() / actualBytes.toDouble()) * 0.92
            val tempCorrected = File(outputFile.parentFile, "retry_${outputFile.name}")
            onProgress(10f, "Refining bitrate to fit target size...")
            runFfmpegPass(
                inputFile = inputFile,
                outputFile = tempCorrected,
                probe = probe,
                spec = spec,
                threadCount = threadCount,
                bitrateMultiplier = correction.coerceIn(0.25, 0.90),
                effectiveDurationMs = effectiveDurationMs,
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

    private suspend fun runFfmpegPass(
        inputFile: File,
        outputFile: File,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        threadCount: Int,
        bitrateMultiplier: Double,
        effectiveDurationMs: Long,
        onProgress: (Float, String) -> Unit
    ) {
        val args = FFmpegCommandBuilder.buildCommand(
            inputPath = inputFile.absolutePath,
            outputPath = outputFile.absolutePath,
            probe = probe,
            spec = spec,
            threadCount = threadCount,
            bitrateMultiplier = bitrateMultiplier
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
