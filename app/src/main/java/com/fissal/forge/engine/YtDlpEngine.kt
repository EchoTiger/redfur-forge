package com.fissal.forge.engine

import com.fissal.forge.auth.AuthErrorClassifier
import com.fissal.forge.auth.CredentialRedactor
import com.fissal.forge.auth.SiteAuthVault
import com.fissal.forge.domain.logic.YtDlpJsonParser
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.DownloadSpec
import com.fissal.forge.domain.model.ResolvedUrlMetadata
import com.fissal.forge.domain.model.SponsorBlockCategory
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
 * Executes yt-dlp metadata inspection and isolated per-job downloads with aria2c acceleration
 * and ephemeral Keystore-backed cookie materialization.
 */
class YtDlpEngine(
    private val binaryManager: NativeBinaryManager,
    private val authVault: SiteAuthVault? = null
) {

    suspend fun resolveMetadata(
        url: String,
        cancelKey: String,
        siteConnectionId: String? = null
    ): ResolvedUrlMetadata = withContext(Dispatchers.IO) {
        binaryManager.ensureInitialized()
        withAuthCookies(url, siteConnectionId) { cookieFile, userAgent ->
            val request = YoutubeDLRequest(url).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-warnings")
                addOption("--ignore-errors")
                addOption("--socket-timeout", "12")
                if (cookieFile != null) {
                    addOption("--cookies", cookieFile.absolutePath)
                    userAgent?.takeIf { it.isNotBlank() }?.let { addOption("--user-agent", it) }
                }
            }

            val processId = "resolve_$cancelKey"
            val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    YoutubeDL.getInstance().destroyProcessById(processId)
                }
            }

            val response = try {
                YoutubeDL.getInstance().execute(request, processId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val combinedErr = listOfNotNull(t.message, t.cause?.message).joinToString("\n")
                AuthErrorClassifier.classifyYtDlpError(
                    url = url,
                    rawStderr = combinedErr,
                    customDomains = authVault?.connectedCustomDomains().orEmpty()
                )?.let { throw it }
                val safeMsg = CredentialRedactor.sanitizeDiagnostic(
                    t.message ?: t::class.java.simpleName,
                    maxChars = 280
                ).ifBlank { "Metadata lookup failed" }
                throw IOException(safeMsg, t)
            } finally {
                cancelHandle.dispose()
            }

            val outText = response.out.orEmpty()
            if (outText.isBlank()) {
                AuthErrorClassifier.classifyYtDlpError(
                    url = url,
                    rawStderr = response.err,
                    customDomains = authVault?.connectedCustomDomains().orEmpty()
                )?.let { throw it }
                val safeErr = response.err?.lineSequence()?.firstOrNull { it.isNotBlank() }
                    ?.let { CredentialRedactor.sanitizeDiagnostic(it, maxChars = 240) }
                    ?.takeIf { it.isNotBlank() }
                    ?: "Empty metadata response"
                throw IOException(safeErr)
            }
            YtDlpJsonParser.parse(url, outText)
        }
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

        withAuthCookies(spec.url, spec.siteConnectionId) { cookieFile, userAgent ->
            val request = YoutubeDLRequest(spec.url).apply {
                addOption("-o", "${workDir.absolutePath}/%(title).120B [%(id)s].%(ext)s")
                addOption("--no-mtime")
                addOption("--restrict-filenames")
                if (cookieFile != null) {
                    addOption("--cookies", cookieFile.absolutePath)
                    userAgent?.takeIf { it.isNotBlank() }?.let { addOption("--user-agent", it) }
                }

                if (spec.playlistItemIndex != null) {
                    addOption("--yes-playlist")
                    addOption("--playlist-items", spec.playlistItemIndex.toString())
                } else if (spec.playlistSingleOnly) {
                    addOption("--no-playlist")
                } else {
                    addOption("--yes-playlist")
                }

                // Avoid external aria2c when authenticated cookies are active so yt-dlp handles headers/redirects natively.
                if (spec.useAria2c && cookieFile == null) {
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
                buildSponsorBlockRemoveValue(spec)?.let { removeCategories ->
                    addOption("--sponsorblock-remove", removeCategories)
                }
            }

            val processId = "ytdlp_$jobId"
            val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    YoutubeDL.getInstance().destroyProcessById(processId)
                }
            }

            val recentLines = ArrayDeque<String>(MAX_CAPTURED_LOG_LINES)
            val response = try {
                YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                    if (line.isNotBlank()) {
                        synchronized(recentLines) {
                            if (recentLines.size >= MAX_CAPTURED_LOG_LINES) {
                                recentLines.removeFirst()
                            }
                            recentLines.addLast(line)
                        }
                    }
                    val pct = if (progress >= 0f) progress.coerceIn(0f, 99.5f) else 0f
                    val etaStr = if (etaInSeconds > 0L) " • ETA ${etaInSeconds}s" else ""
                    val status = if (line.contains("[Merger]", ignoreCase = true) || line.contains("[ExtractAudio]", ignoreCase = true)) {
                        "Muxing streams..."
                    } else {
                        "Downloading ${pct.toInt()}%$etaStr"
                    }
                    onProgress(pct, status)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val captured = synchronized(recentLines) { recentLines.toList() }
                val combinedErr = (listOfNotNull(t.message, t.cause?.message) + captured).joinToString("\n")
                AuthErrorClassifier.classifyYtDlpError(
                    url = spec.url,
                    rawStderr = combinedErr,
                    customDomains = authVault?.connectedCustomDomains().orEmpty()
                )?.let { throw it }
                val safeMsg = CredentialRedactor.sanitizeDiagnostic(
                    combinedErr.ifBlank { t.message ?: t::class.java.simpleName },
                    maxChars = 320
                ).ifBlank { "Download failed" }
                throw IOException(safeMsg, t)
            } finally {
                cancelHandle.dispose()
            }

            val downloadedFiles = workDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".aria2") }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            if (downloadedFiles.isEmpty()) {
                val captured = synchronized(recentLines) { recentLines.toList() }
                val combinedErr = (listOfNotNull(response.err) + captured).joinToString("\n")
                AuthErrorClassifier.classifyYtDlpError(
                    url = spec.url,
                    rawStderr = combinedErr,
                    customDomains = authVault?.connectedCustomDomains().orEmpty()
                )?.let { throw it }
                throw IOException("yt-dlp completed but produced no output file in ${workDir.name}")
            }
            downloadedFiles
        }
    }

    private suspend fun <T> withAuthCookies(
        url: String,
        siteConnectionId: String?,
        block: suspend (cookieFile: File?, userAgent: String?) -> T
    ): T {
        val vault = authVault ?: return block(null, null)
        return vault.withMaterializedCookies(url, siteConnectionId, block)
    }

    companion object {
        private const val MAX_CAPTURED_LOG_LINES = 32

        fun buildSponsorBlockRemoveValue(spec: DownloadSpec): String? {
            val categories = spec.effectiveSponsorBlockCategories
            if (categories.isEmpty()) return null
            return SponsorBlockCategory.entries
                .filter { it in categories }
                .joinToString(",") { it.ytDlpKey }
        }
    }
}
