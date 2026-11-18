package com.fissal.forge.domain.model

import com.fissal.forge.auth.CredentialRedactor
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * High-level media classification inferred before or after link resolution.
 */
@Serializable
enum class DetectedLinkKind(val label: String) {
    VIDEO("Video"),
    AUDIO("Audio"),
    PLAYLIST("Playlist"),
    IMAGE("Image")
}

@Serializable
enum class LinkResolveState {
    RESOLVING,
    RESOLVED,
    FAILED
}

@Serializable
data class PlaylistTrackItem(
    val index: Int,
    val trackId: String,
    val title: String,
    val uploader: String? = null,
    val durationSec: Long? = null,
    val trackUrl: String? = null,
    val thumbnailUrl: String? = null,
    val selected: Boolean = true
) {
    fun formattedDuration(): String? {
        val total = durationSec ?: return null
        if (total <= 0L) return null
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
}

/**
 * Chapter segment entry extracted from resolved media metadata for selective split downloads.
 */
@Serializable
data class ChapterTrackItem(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val selected: Boolean = true,
    val customSettings: LinkItemSettings? = null
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)

    fun formattedRange(): String = "${formatChapterTimestamp(startMs)} - ${formatChapterTimestamp(endMs)}"
}

private fun formatChapterTimestamp(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

/**
 * Resolved metadata returned by YtDlpEngine.resolveMetadata(--flat-playlist --dump-single-json).
 */
@Serializable
data class ResolvedUrlMetadata(
    val sourceUrl: String,
    val host: String,
    val title: String,
    val uploader: String? = null,
    val thumbnailUrl: String? = null,
    val detectedKind: DetectedLinkKind = DetectedLinkKind.VIDEO,
    val contentMediaKind: DetectedLinkKind = DetectedLinkKind.VIDEO,
    val durationSec: Long? = null,
    val approxBytes: Long? = null,
    val tracks: List<PlaylistTrackItem> = emptyList(),
    val chapters: List<ChapterTrackItem> = emptyList()
)

@Serializable
data class LinkItemSettings(
    val mode: DownloadMode = DownloadMode.VIDEO_MP4,
    val maxHeight: Int? = 1080,
    val audioFormat: String = "mp3",
    val playlistSingleOnly: Boolean = true,
    val targetSizeMb: Double? = null,
    val compressionTier: CompressionTier = CompressionTier.PERCEPTUAL_AV1,
    val sponsorBlock: Boolean = false,
    val embedMetadata: Boolean = true,
    val stripMetadata: Boolean = false,
    val scopeBadge: String? = null,
    val sponsorBlockCategories: Set<SponsorBlockCategory> = emptySet(),
    val siteConnectionId: String? = null,
    val splitByChapters: Boolean = false,
    val silenceSkip: SilenceSkipSpec = SilenceSkipSpec()
) {
    /**
     * Resolves active SponsorBlock categories, preserving legacy boolean [sponsorBlock] behavior
     * when explicit [sponsorBlockCategories] have not been chosen.
     */
    val effectiveSponsorBlockCategories: Set<SponsorBlockCategory>
        get() = when {
            sponsorBlockCategories.isNotEmpty() -> sponsorBlockCategories
            sponsorBlock -> setOf(SponsorBlockCategory.SPONSOR)
            else -> emptySet()
        }

    fun compactSummary(): String {
        val parts = mutableListOf<String>()
        parts += when (mode) {
            DownloadMode.AUDIO_ONLY -> "Audio ${audioFormat.uppercase()}"
            DownloadMode.VIDEO_BEST_MKV -> maxHeight?.let { "MKV ${it}p" } ?: "MKV Best"
            DownloadMode.VIDEO_MP4 -> maxHeight?.let { "MP4 ${it}p" } ?: "MP4 Best"
        }
        val mb = targetSizeMb
        if (mb != null && mb > 0.0) {
            val tierTag = when (compressionTier) {
                CompressionTier.PERCEPTUAL_AV1 -> "AV1"
                CompressionTier.TWO_PASS_HEVC -> "HEVC"
                CompressionTier.COMPATIBLE_H264 -> "H.264"
            }
            val mbLabel = if (mb % 1.0 == 0.0) mb.toInt().toString() else String.format("%.1f", mb)
            parts += if (mode == DownloadMode.AUDIO_ONLY) "≤ ${mbLabel}MB" else "$tierTag ≤ ${mbLabel}MB"
        }
        if (splitByChapters) parts += "Chapters"
        if (silenceSkip.enabled) parts += "Silence Skip"
        return parts.joinToString(" • ")
    }
}

@Serializable
data class ResolvedLinkChipItem(
    val cleanUrl: String,
    val host: String,
    val isPlaylistLikeUrl: Boolean = false,
    val resolveState: LinkResolveState = LinkResolveState.RESOLVING,
    val title: String = CredentialRedactor.redactUrlForDisplay(cleanUrl)
        .removePrefix("https://")
        .removePrefix("http://")
        .take(60),
    val uploader: String? = null,
    val thumbnailUrl: String? = null,
    val detectedKind: DetectedLinkKind = DetectedLinkKind.VIDEO,
    val contentMediaKind: DetectedLinkKind = DetectedLinkKind.VIDEO,
    val durationSec: Long? = null,
    val approxBytes: Long? = null,
    val tracks: List<PlaylistTrackItem> = emptyList(),
    val settings: LinkItemSettings = LinkItemSettings(),
    val isExpanded: Boolean = false,
    val errorHint: String? = null,
    val userCustomized: Boolean = false,
    val isCommitted: Boolean = false,
    val chapters: List<ChapterTrackItem> = emptyList(),
    val authRequiredReason: String? = null,
    val authIsPaywall: Boolean = false,
    val authServiceId: String? = null
) {
    val selectedTrackCount: Int
        get() = tracks.count { it.selected }

    val selectedChapterCount: Int
        get() = chapters.count { it.selected }

    val effectivePlaylistActive: Boolean
        get() = !settings.playlistSingleOnly && (tracks.isNotEmpty() || detectedKind == DetectedLinkKind.PLAYLIST || isPlaylistLikeUrl)

    fun formattedDuration(): String? {
        val total = durationSec ?: return null
        if (total <= 0L) return null
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
}
