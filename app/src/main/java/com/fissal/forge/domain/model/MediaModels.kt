package com.fissal.forge.domain.model

import kotlin.math.abs

enum class MediaKind {
    VIDEO,
    AUDIO,
    IMAGE,
    CAMERA_RAW,
    REMOTE_URL,
    UNKNOWN
}

data class MediaProbeResult(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val extension: String,
    val mediaKind: MediaKind,
    val fileSizeBytes: Long,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val durationMs: Long = 0L,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hasAudio: Boolean = false,
    val isHdr: Boolean = false,
    val bitrateBps: Long = 0L,
    val cameraModel: String? = null
) {
    private val normalizedRotation: Int
        get() = ((rotationDegrees % 360) + 360) % 360

    val effectiveWidth: Int
        get() = if (normalizedRotation == 90 || normalizedRotation == 270) rawHeight else rawWidth

    val effectiveHeight: Int
        get() = if (normalizedRotation == 90 || normalizedRotation == 270) rawWidth else rawHeight

    val isPortrait: Boolean
        get() = effectiveHeight > effectiveWidth && effectiveWidth > 0

    val isVisual: Boolean
        get() = mediaKind == MediaKind.VIDEO ||
            mediaKind == MediaKind.IMAGE ||
            mediaKind == MediaKind.CAMERA_RAW

    val isTimeBased: Boolean
        get() = mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.AUDIO

    fun displayResolution(): String {
        return if (effectiveWidth > 0 && effectiveHeight > 0) {
            "${effectiveWidth}×${effectiveHeight}"
        } else {
            "Unknown res"
        }
    }

    fun formattedSize(): String {
        if (fileSizeBytes <= 0L) return "0 B"
        val kb = fileSizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.0f KB", kb)
            else -> "$fileSizeBytes B"
        }
    }

    fun formattedDuration(): String {
        if (durationMs <= 0L) return "0:00"
        val totalSec = abs(durationMs) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }
}
