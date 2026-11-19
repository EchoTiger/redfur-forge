package com.fissal.forge.delivery

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Atomically exports processed outputs into scoped MediaStore collections (Movies/Pictures/Music/Downloads).
 */
class MediaStoreExporter(private val appContext: Context) {

    data class SavedOutput(
        val contentUri: Uri,
        val mimeType: String,
        val displayName: String
    )

    suspend fun exportToDevice(sourceFile: File, desiredName: String): SavedOutput = withContext(Dispatchers.IO) {
        val ext = desiredName.substringAfterLast('.', sourceFile.extension).lowercase(Locale.US)
        val mimeType = extensionToMime(ext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, baseDir) = when {
                mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
                mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
                mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, desiredName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/RedfurForge")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = appContext.contentResolver
            val insertedUri = resolver.insert(collection, values)
                ?: throw IOException("MediaStore rejected insert for $desiredName")

            try {
                resolver.openOutputStream(insertedUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, bufferSize = 256 * 1024)
                    }
                    out.flush()
                } ?: throw IOException("Unable to open MediaStore output stream for $insertedUri")

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(insertedUri, values, null, null)
                SavedOutput(insertedUri, mimeType, desiredName)
            } catch (t: Throwable) {
                runCatching { resolver.delete(insertedUri, null, null) }
                throw t
            }
        } else {
            val hasLegacyWrite = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasLegacyWrite) {
                throw IOException("Storage permission is required on Android 8–9 to save files to shared folders")
            }
            @Suppress("DEPRECATION")
            val rootDir = when {
                mimeType.startsWith("video/") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                mimeType.startsWith("image/") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                mimeType.startsWith("audio/") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
            val forgeDir = File(rootDir, "RedfurForge").apply { if (!exists()) mkdirs() }
            val destFile = resolveUniqueFile(forgeDir, desiredName)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { out ->
                    input.copyTo(out, bufferSize = 256 * 1024)
                    out.flush()
                }
            }
            MediaScannerConnection.scanFile(appContext, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
            SavedOutput(Uri.fromFile(destFile), mimeType, destFile.name)
        }
    }

    private fun resolveUniqueFile(dir: File, desiredName: String): File {
        var candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate
        val dot = desiredName.lastIndexOf('.')
        val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val ext = if (dot > 0) desiredName.substring(dot) else ""
        var counter = 1
        while (candidate.exists() && counter < 10_000) {
            candidate = File(dir, "${base}_$counter$ext")
            counter++
        }
        return candidate
    }

    fun extensionToMime(ext: String): String = when (ext.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
