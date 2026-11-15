package com.fissal.forge.engine

import com.fissal.forge.domain.logic.YtDlpJsonParser
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.DownloadSpec
import com.fissal.forge.domain.model.ResolvedUrlMetadata
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
 * Executes yt-dlp metadata inspection and isolated per-job downloads with aria2c acceleration.
 */
class YtDlpEngine(private val binaryManager: NativeBinaryManager) {

    suspend fun resolveMetadata(
        url: String,
        cancelKey: String
    ): ResolvedUrlMetadata = withContext(Dispatchers.IO) {
        binaryManager.ensureInitialized()
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--ignore-errors")
            addOption("--socket-timeout", "12")
        }

        val processId = "resolve_$cancelKey"
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                YoutubeDL.getInstance().destroyProcessById(processId)
            }
        }

        val response = try {
            YoutubeDL.getInstance().execute(request, processId)
        } finally {
            cancelHandle.dispose()
        }

        val outText = response.out.orEmpty()
        if (outText.isBlank()) {
            throw IOException(response.err?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "Empty metadata response")
        }
        YtDlpJsonParser.parse(url, outText)
    }

    suspend fun download(
        jobId: String,
        spec: DownloadSpec,
        workDir: File,
        downloadHeightOverride: Int? = null,
        onProgress: (Float, String) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        binaryManager.ensureInitialized()
        if (!workDir.exists()) workDir.mkdirs()

        val effectiveMaxHeight = listOfNotNull(spec.maxHeight, downloadHeightOverride).minOrNull()

        val request = YoutubeDLRequest(spec.url).apply {
            addOption("-o", "${workDir.absolutePath}/%(title).120B [%(id)s].%(ext)s")
            addOption("--no-mtime")
            addOption("--restrict-filenames")
            if (spec.playlistItemIndex != null) {
                addOption("--yes-playlist")
                addOption("--playlist-items", spec.playlistItemIndex.toString())
            } else if (spec.playlistSingleOnly) {
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
                    val format = if (effectiveMaxHeight != null) {
                        "bv*[height<=$effectiveMaxHeight][ext=mp4]+ba[ext=m4a]/b[height<=$effectiveMaxHeight][ext=mp4]/bv*[height<=$effectiveMaxHeight]+ba/b[height<=$effectiveMaxHeight]/b"
                    } else {
                        "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"
                    }
                    addOption("-f", format)
                    addOption("--merge-output-format", "mp4")
                }
                DownloadMode.VIDEO_BEST_MKV -> {
                    val format = if (effectiveMaxHeight != null) {
                        "bv*[height<=$effectiveMaxHeight]+ba/b[height<=$effectiveMaxHeight]/b"
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
