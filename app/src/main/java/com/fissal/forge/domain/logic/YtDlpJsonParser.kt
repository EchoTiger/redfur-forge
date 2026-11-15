package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.DetectedLinkKind
import com.fissal.forge.domain.model.PlaylistTrackItem
import com.fissal.forge.domain.model.ResolvedUrlMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.util.Locale

/**
 * Pure Kotlin parser for `yt-dlp --flat-playlist --dump-single-json` output.
 * Extracts playlist entries, audio vs video classification, duration, thumbnails, and track URLs.
 */
object YtDlpJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val AUDIO_FIRST_HOSTS = setOf(
        "soundcloud.com",
        "m.soundcloud.com",
        "bandcamp.com",
        "music.youtube.com",
        "mixcloud.com",
        "audiomack.com",
        "tunein.com",
        "open.spotify.com",
        "podcasts.apple.com"
    )

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav", "aac", "weba")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic")

    fun inferHost(url: String): String {
        return runCatching {
            URI(url).host?.lowercase(Locale.US)?.removePrefix("www.") ?: ""
        }.getOrDefault("")
    }

    fun inferContentMediaHint(url: String): DetectedLinkKind {
        val host = inferHost(url)
        val lowerPath = runCatching { URI(url).rawPath?.lowercase(Locale.US) ?: "" }.getOrDefault("")
        val ext = lowerPath.substringAfterLast('.', "")
        return when {
            ext in IMAGE_EXTENSIONS -> DetectedLinkKind.IMAGE
            ext in AUDIO_EXTENSIONS -> DetectedLinkKind.AUDIO
            AUDIO_FIRST_HOSTS.any { host == it || host.endsWith(".$it") } -> DetectedLinkKind.AUDIO
            else -> DetectedLinkKind.VIDEO
        }
    }

    fun parse(sourceUrl: String, rawJsonOutput: String): ResolvedUrlMetadata {
        val host = inferHost(sourceUrl)
        val trimmed = extractJsonObjectText(rawJsonOutput)
        if (trimmed.isBlank()) {
            return fallbackMetadata(sourceUrl, host)
        }

        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return fallbackMetadata(sourceUrl, host)

        val rootType = root.str("_type")?.lowercase(Locale.US).orEmpty()
        val entriesArr = root["entries"] as? JsonArray
        val isPlaylist = rootType == "playlist" || rootType == "multi_video" || (entriesArr != null && entriesArr.isNotEmpty())

        val rawTitle = root.str("title")
            ?: root.str("playlist_title")
            ?: root.str("fulltitle")
            ?: sourceUrl.removePrefix("https://").removePrefix("http://").take(64)

        val uploader = root.str("uploader")
            ?: root.str("channel")
            ?: root.str("artist")
            ?: root.str("creator")

        val rootThumb = pickThumbnail(root)

        if (isPlaylist && entriesArr != null) {
            val tracks = mutableListOf<PlaylistTrackItem>()
            var totalDuration = 0L
            for ((idx, elem) in entriesArr.withIndex()) {
                val obj = elem as? JsonObject ?: continue
                val title = obj.str("title") ?: obj.str("fulltitle") ?: "Track ${idx + 1}"
                // Skip YouTube deleted/private tombstone entries when they carry no playable id/url
                val id = obj.str("id") ?: "track_${idx + 1}"
                val rawEntryUrl = obj.str("webpage_url") ?: obj.str("url")
                val resolvedTrackUrl = normalizeTrackUrl(rawEntryUrl, id, host, obj.str("ie_key"))
                val dur = obj.longVal("duration")
                if (dur != null && dur > 0L) totalDuration += dur
                val trackUploader = obj.str("uploader") ?: obj.str("channel") ?: obj.str("artist") ?: uploader
                val trackThumb = pickThumbnail(obj)
                tracks += PlaylistTrackItem(
                    index = idx + 1,
                    trackId = id,
                    title = title,
                    uploader = trackUploader,
                    durationSec = dur,
                    trackUrl = resolvedTrackUrl,
                    thumbnailUrl = trackThumb,
                    selected = true
                )
            }

            val contentKind = classifyContentKind(root, host, entriesArr)
            return ResolvedUrlMetadata(
                sourceUrl = sourceUrl,
                host = host,
                title = rawTitle,
                uploader = uploader,
                thumbnailUrl = rootThumb ?: tracks.firstOrNull()?.thumbnailUrl,
                detectedKind = if (tracks.size > 1 || rootType == "playlist") DetectedLinkKind.PLAYLIST else contentKind,
                contentMediaKind = contentKind,
                durationSec = root.longVal("duration") ?: totalDuration.takeIf { it > 0L },
                approxBytes = root.longVal("filesize_approx") ?: root.longVal("filesize"),
                tracks = tracks
            )
        }

        val contentKind = classifyContentKind(root, host, null)
        return ResolvedUrlMetadata(
            sourceUrl = sourceUrl,
            host = host,
            title = rawTitle,
            uploader = uploader,
            thumbnailUrl = rootThumb,
            detectedKind = contentKind,
            contentMediaKind = contentKind,
            durationSec = root.longVal("duration"),
            approxBytes = root.longVal("filesize_approx") ?: root.longVal("filesize"),
            tracks = emptyList()
        )
    }

    private fun extractJsonObjectText(raw: String): String {
        val idx = raw.indexOf('{')
        if (idx < 0) return ""
        return raw.substring(idx).trim()
    }

    private fun normalizeTrackUrl(
        rawUrl: String?,
        id: String,
        parentHost: String,
        ieKey: String?
    ): String? {
        if (!rawUrl.isNullOrBlank() && (rawUrl.startsWith("http://") || rawUrl.startsWith("https://"))) {
            return rawUrl
        }
        val cleanId = id.trim()
        if (cleanId.isNotEmpty() && !cleanId.startsWith("track_")) {
            if (parentHost.contains("music.youtube.com")) {
                return "https://music.youtube.com/watch?v=$cleanId"
            }
            if (parentHost.contains("youtube.com") || parentHost.contains("youtu.be") ||
                ieKey.equals("Youtube", ignoreCase = true)
            ) {
                return "https://www.youtube.com/watch?v=$cleanId"
            }
        }
        return null
    }

    private fun classifyContentKind(
        root: JsonObject,
        host: String,
        entries: JsonArray?
    ): DetectedLinkKind {
        val ext = root.str("ext")?.lowercase(Locale.US).orEmpty()
        val vcodec = root.str("vcodec")?.lowercase(Locale.US)
        val acodec = root.str("acodec")?.lowercase(Locale.US)

        if (ext in IMAGE_EXTENSIONS) return DetectedLinkKind.IMAGE
        if (ext in AUDIO_EXTENSIONS || (vcodec == "none" && !acodec.isNullOrBlank() && acodec != "none")) {
            return DetectedLinkKind.AUDIO
        }
        if (AUDIO_FIRST_HOSTS.any { host == it || host.endsWith(".$it") }) {
            return DetectedLinkKind.AUDIO
        }
        if (entries != null && entries.isNotEmpty()) {
            val firstObj = entries.firstOrNull() as? JsonObject
            val firstExt = firstObj?.str("ext")?.lowercase(Locale.US).orEmpty()
            val firstVcodec = firstObj?.str("vcodec")?.lowercase(Locale.US)
            if (firstExt in AUDIO_EXTENSIONS || firstVcodec == "none") {
                return DetectedLinkKind.AUDIO
            }
        }
        return DetectedLinkKind.VIDEO
    }

    private fun pickThumbnail(obj: JsonObject): String? {
        val direct = obj.str("thumbnail")
        if (!direct.isNullOrBlank() && direct.startsWith("http")) return direct
        val thumbs = obj["thumbnails"] as? JsonArray ?: return direct
        return thumbs.mapNotNull { el ->
            (el as? JsonObject)?.str("url")?.takeIf { it.startsWith("http") }
        }.lastOrNull() ?: direct
    }

    private fun fallbackMetadata(sourceUrl: String, host: String): ResolvedUrlMetadata {
        val kind = inferContentMediaHint(sourceUrl)
        return ResolvedUrlMetadata(
            sourceUrl = sourceUrl,
            host = host,
            title = sourceUrl.removePrefix("https://").removePrefix("http://").take(64),
            detectedKind = kind,
            contentMediaKind = kind
        )
    }

    private fun JsonObject.str(key: String): String? {
        val el: JsonElement = this[key] ?: return null
        return (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JsonObject.longVal(key: String): Long? {
        val el: JsonElement = this[key] ?: return null
        val prim = el as? JsonPrimitive ?: return null
        return prim.longOrNull ?: prim.doubleOrNull?.toLong()
    }
}
