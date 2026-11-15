package com.fissal.forge.engine

import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.DownloadSpec
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Executes yt-dlp downloads inside an isolated per-job working directory with optional aria2c acceleration.
 */
class YtDlpEngine(private val binaryManager: NativeBinaryManager) {

    suspend fun download(
        jobId: String,
        spec: DownloadSpec,
        workDir: File,
        onProgress: (Float, String) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        binaryManager.ensureInitialized()
        if (!workDir.exists()) workDir.mkdirs()

        val request = YoutubeDLRequest(spec.url).apply {
            addOption("-o", "${workDir.absolutePath}/%(title).120B [%(id)s].%(ext)s")
            addOption("--no-mtime")
            addOption("--restrict-filenames")
            if (spec.playlistSingleOnly) {
                addOption("--no-playlist")
            } else {
                addOption("--yes-playlist")
            }

            if (spec.useAria2c) {
                addOption("--downloader", "libaria2c.so")
                addOption("--external-downloader-args", "aria2c:-x 8 -k 1M")
            }

            when (spec.mode) {
                DownloadMode.VIDEO_MP4 -> {
                    val h = spec.maxHeight
                    val format = if (h != null) {
                        "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]/b"
                    } else {
                        "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"
                    }
                    addOption("-f", format)
                    addOption("--merge-output-format", "mp4")
                }
                DownloadMode.VIDEO_BEST_MKV -> {
                    val h = spec.maxHeight
                    val format = if (h != null) {
                        "bv*[height<=$h]+ba/b[height<=$h]/b"
                    } else {
                        "bv*+ba/b"
                    }
                    addOption("-f", format)
                    addOption("--merge-output-format", "mkv")
                }
                DownloadMode.AUDIO_ONLY -> {
                    addOption("-x")
                    addOption("--audio-format", spec.audioFormat)
                    addOption("--audio-quality", "0")
                }
            }

            if (spec.embedMetadata) {
                addOption("--embed-metadata")
            }
            if (spec.embedThumbnail && spec.mode != DownloadMode.VIDEO_BEST_MKV) {
                addOption("--embed-thumbnail")
            }
            if (spec.sponsorBlock) {
                addOption("--sponsorblock-remove", "sponsor")
            }
        }

        val processId = "ytdlp_$jobId"
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                YoutubeDL.getInstance().destroyProcessById(processId)
            }
        }

        try {
            YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                val pct = if (progress >= 0f) progress.coerceIn(0f, 99.5f) else 0f
                val etaStr = if (etaInSeconds > 0L) " • ETA ${etaInSeconds}s" else ""
                val status = if (line.contains("[Merger]", ignoreCase = true) || line.contains("[ExtractAudio]", ignoreCase = true)) {
                    "Muxing streams..."
                } else {
                    "Downloading ${pct.toInt()}%$etaStr"
                }
                onProgress(pct, status)
            }
        } finally {
            cancelHandle.dispose()
        }

        val downloadedFiles = workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".aria2") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

        if (downloadedFiles.isEmpty()) {
            throw IOException("yt-dlp completed but produced no output file in ${workDir.name}")
        }
        downloadedFiles
    }
}
