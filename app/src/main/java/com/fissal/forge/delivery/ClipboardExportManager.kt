package com.fissal.forge.delivery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies processed media files directly onto the Android clipboard via content:// URIs
 * so users can paste straight into Discord, Messages, Telegram, or Email without browsing directories.
 */
class ClipboardExportManager(private val appContext: Context) {

    private val stagingDir: File
        get() = File(appContext.cacheDir, "clipboard_staging").apply { if (!exists()) mkdirs() }

    suspend fun copyFileToClipboard(sourceFile: File, label: String = sourceFile.name): Uri {
        val stagedFile = withContext(Dispatchers.IO) {
            pruneOldStagedFiles(keepRecent = 6)
            val safeName = sourceFile.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            val target = File(stagingDir, "${System.currentTimeMillis()}_$safeName")
            sourceFile.copyTo(target, overwrite = true)
            target
        }

        val authority = "${appContext.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(appContext, authority, stagedFile)

        withContext(Dispatchers.Main) {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(appContext.contentResolver, label, contentUri)
            clipboard.setPrimaryClip(clip)
        }
        return contentUri
    }

    fun clipboardCacheSizeBytes(): Long {
        return stagingDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    fun clearClipboardStaging() {
        stagingDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun pruneOldStagedFiles(keepRecent: Int) {
        val files = stagingDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > keepRecent) {
            files.drop(keepRecent).forEach { it.delete() }
        }
    }
}
