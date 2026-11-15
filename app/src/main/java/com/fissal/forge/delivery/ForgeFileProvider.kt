package com.fissal.forge.delivery

import android.net.Uri
import androidx.core.content.FileProvider
import java.util.Locale

/**
 * Custom FileProvider ensuring rich MIME types are always exposed cleanly to receiving apps
 * during Android clipboard content:// pastes and Share-Out intents.
 */
class ForgeFileProvider : FileProvider() {

    override fun getType(uri: Uri): String? {
        val ext = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?: return super.getType(uri)

        return when (ext) {
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
            else -> super.getType(uri)
        }
    }
}
