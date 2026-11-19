package com.fissal.forge.domain.logic

import com.fissal.forge.auth.CredentialRedactor
import com.fissal.forge.domain.model.ChapterTrackItem
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
import kotlin.math.roundToLong

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

    private val URL_REGEX = Regex("""https?://\S+""")
    private val TIMESTAMP_REGEX = Regex("""(?<!\d)(?<!\d:)(\d{1,2}:\d{2}:\d{2}|\d{1,3}:\d{2})(?!\d)(?!:\d)""")
    private val PREFIX_MARKER_REGEX = Regex(
        """^(?:[-*•+>#]\s*)?(?:[\[\(]\s*)?#?\d{1,3}(?:\s*[\]\).:\-–—|])+(?:\s*[-–—|])?$"""
    )
    private val SUFFIX_CLOSER_REGEX = Regex("""^[\]\)\s.,;]*$""")
    private val RANGE_SEPARATOR_REGEX = Regex("""^[\]\)]?\s*(?:-|–|—|~|to)\s*[\[\(]?$""", RegexOption.IGNORE_CASE)
    private val LEADING_BULLET_REGEX = Regex("""^[\s\-–—|:*•+>]+""")
    private val LEADING_TRACK_NO_REGEX = Regex("""^(?:[\[\(]\s*#?\d{1,3}\s*[\]\)]|#\d{1,3}\b[\s.):\-–—|]*|\d{1,3}[.)]\s*)""")
    private val EDGE_SEPARATOR_REGEX = Regex("""^[\s\-–—|:]+|[\s\-–—|:]+$""")
    private const val DEFAULT_LAST_CHAPTER_DURATION_MS = 240_000L

    private data class TimestampMatch(val range: IntRange, val ms: Long)

    private data class RawChapterCandidate(
        val startMs: Long,
        val explicitEndMs: Long?,
        val cleanedTitle: String
    )

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
            ?: CredentialRedactor.redactUrlForDisplay(sourceUrl)
                .removePrefix("https://")
                .removePrefix("http://")
                .take(64)

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
                // Deselect YouTube deleted/private tombstone entries by default while preserving 1-based playlist indices
                val isTombstone = title.equals("[Deleted video]", ignoreCase = true) ||
                    title.equals("[Private video]", ignoreCase = true)
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
                    selected = !isTombstone
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
                tracks = tracks,
                chapters = emptyList()
            )
        }

        val contentKind = classifyContentKind(root, host, null)
        val durationSec = root.longVal("duration")
        val durationMs = root.doubleVal("duration")?.takeIf { it > 0.0 }?.let { (it * 1000.0).roundToLong() }
        val chapters = if (isPlaylist) {
            emptyList()
        } else {
            val structured = parseStructuredChapters(root, durationMs)
            if (structured.size >= 2) {
                structured
            } else {
                parseChaptersFromDescription(root.str("description"), durationSec)
            }
        }
        return ResolvedUrlMetadata(
            sourceUrl = sourceUrl,
            host = host,
            title = rawTitle,
            uploader = uploader,
            thumbnailUrl = rootThumb,
            detectedKind = contentKind,
            contentMediaKind = contentKind,
            durationSec = durationSec,
            approxBytes = root.longVal("filesize_approx") ?: root.longVal("filesize"),
            tracks = emptyList(),
            chapters = chapters
        )
    }

    /**
     * Parses chapter/tracklist entries from a single video's description text.
     * Supports leading/trailing `MM:SS` and `HH:MM:SS` timestamps as well as `start - end` ranges.
     */
    fun parseChaptersFromDescription(description: String?, durationSec: Long?): List<ChapterTrackItem> {
        if (description.isNullOrBlank()) return emptyList()
        val durationMs = durationSec?.takeIf { it > 0L }?.times(1000L)
        val candidates = mutableListOf<RawChapterCandidate>()

        for (rawLine in description.lineSequence()) {
            val cleanedLine = rawLine.replace(URL_REGEX, " ").trim()
            if (cleanedLine.isEmpty()) continue
            val parsed = parseDescriptionLine(cleanedLine) ?: continue
            if (durationMs != null && parsed.startMs >= durationMs) continue
            candidates += parsed
        }

        if (candidates.size < 2) return emptyList()

        val deduped = linkedMapOf<Long, RawChapterCandidate>()
        for (candidate in candidates.sortedBy { it.startMs }) {
            val existing = deduped[candidate.startMs]
            if (existing == null || (existing.cleanedTitle.isBlank() && candidate.cleanedTitle.isNotBlank())) {
                deduped[candidate.startMs] = candidate
            }
        }

        val ordered = deduped.values.toList()
        if (ordered.size < 2) return emptyList()

        val chapters = mutableListOf<ChapterTrackItem>()
        for (idx in ordered.indices) {
            val item = ordered[idx]
            val startMs = item.startMs
            val isLast = idx == ordered.lastIndex
            val rawEndMs = if (!isLast) {
                val nextStartMs = ordered[idx + 1].startMs
                val rangedEnd = item.explicitEndMs?.let { minOf(it, nextStartMs) } ?: nextStartMs
                if (durationMs != null) rangedEnd.coerceAtMost(durationMs) else rangedEnd
            } else {
                when {
                    durationMs != null && durationMs > startMs -> durationMs
                    item.explicitEndMs != null && item.explicitEndMs > startMs -> item.explicitEndMs
                    else -> startMs + DEFAULT_LAST_CHAPTER_DURATION_MS
                }
            }
            if (rawEndMs <= startMs) continue
            val outIdx = chapters.size
            val title = item.cleanedTitle.ifBlank { "Track ${outIdx + 1}" }
            chapters += ChapterTrackItem(
                index = outIdx + 1,
                title = title,
                startMs = startMs,
                endMs = rawEndMs,
                selected = true
            )
        }

        return if (chapters.size >= 2) chapters else emptyList()
    }

    private fun parseStructuredChapters(root: JsonObject, durationMs: Long?): List<ChapterTrackItem> {
        val chaptersArr = root["chapters"] as? JsonArray ?: return emptyList()
        val chapters = mutableListOf<ChapterTrackItem>()
        for (elem in chaptersArr) {
            val obj = elem as? JsonObject ?: continue
            val startSec = obj.doubleVal("start_time") ?: continue
            val endSec = obj.doubleVal("end_time") ?: continue
            var startMs = (startSec * 1000.0).roundToLong().coerceAtLeast(0L)
            var endMs = (endSec * 1000.0).roundToLong().coerceAtLeast(0L)
            if (durationMs != null && durationMs > 0L) {
                startMs = startMs.coerceIn(0L, durationMs)
                endMs = endMs.coerceIn(0L, durationMs)
            }
            if (endMs <= startMs) continue
            val outIdx = chapters.size
            val title = obj.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: "Chapter ${outIdx + 1}"
            chapters += ChapterTrackItem(
                index = outIdx + 1,
                title = title,
                startMs = startMs,
                endMs = endMs,
                selected = true
            )
        }
        return chapters
    }

    private fun parseDescriptionLine(line: String): RawChapterCandidate? {
        val matches = TIMESTAMP_REGEX.findAll(line).mapNotNull { mr ->
            val ms = parseTimestampMs(mr.groupValues[1]) ?: return@mapNotNull null
            TimestampMatch(mr.range, ms)
        }.toList()

        return when (matches.size) {
            1 -> parseSingleTimestampLine(line, matches[0])
            2 -> parseRangeTimestampLine(line, matches[0], matches[1])
            else -> null
        }
    }

    private fun parseSingleTimestampLine(line: String, match: TimestampMatch): RawChapterCandidate? {
        val before = line.substring(0, match.range.first)
        val after = line.substring(match.range.last + 1)
        return when {
            isPrefixOnlyMarker(before) -> {
                val stripped = stripMatchingLeadingCloser(before, after)
                RawChapterCandidate(match.ms, null, cleanChapterTitle(stripped))
            }
            isSuffixEmptyOrCloser(after) -> {
                val stripped = stripMatchingTrailingOpener(before, after)
                RawChapterCandidate(match.ms, null, cleanChapterTitle(stripped))
            }
            else -> null
        }
    }

    private fun parseRangeTimestampLine(
        line: String,
        startMatch: TimestampMatch,
        endMatch: TimestampMatch
    ): RawChapterCandidate? {
        if (endMatch.ms <= startMatch.ms) return null
        val gap = line.substring(startMatch.range.last + 1, endMatch.range.first).trim()
        if (!RANGE_SEPARATOR_REGEX.matches(gap)) return null

        val before = line.substring(0, startMatch.range.first)
        val after = line.substring(endMatch.range.last + 1)
        return when {
            isPrefixOnlyMarker(before) -> {
                val stripped = stripMatchingLeadingCloser(before, after)
                RawChapterCandidate(startMatch.ms, endMatch.ms, cleanChapterTitle(stripped))
            }
            isSuffixEmptyOrCloser(after) -> {
                val stripped = stripMatchingTrailingOpener(before, after)
                RawChapterCandidate(startMatch.ms, endMatch.ms, cleanChapterTitle(stripped))
            }
            else -> null
        }
    }

    private fun parseTimestampMs(token: String): Long? {
        val parts = token.split(':')
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toLongOrNull() ?: return null
                if (h !in 0..99 || m !in 0..59 || s !in 0..59) return null
                ((h * 3600L) + (m * 60L) + s) * 1000L
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toLongOrNull() ?: return null
                if (m !in 0..199 || s !in 0..59) return null
                ((m * 60L) + s) * 1000L
            }
            else -> null
        }
    }

    private fun isPrefixOnlyMarker(before: String): Boolean {
        val b = before.trim().trimEnd('[', '(').trim()
        if (b.isEmpty()) return true
        if (b.length == 1 && b[0] in charArrayOf('-', '*', '•', '+', '>')) return true
        return PREFIX_MARKER_REGEX.matches(b)
    }

    private fun isSuffixEmptyOrCloser(after: String): Boolean {
        return SUFFIX_CLOSER_REGEX.matches(after.trim())
    }

    private fun stripMatchingLeadingCloser(before: String, after: String): String {
        val bTrim = before.trimEnd()
        val aTrim = after.trimStart()
        return when {
            bTrim.endsWith("[") && aTrim.startsWith("]") -> aTrim.drop(1)
            bTrim.endsWith("(") && aTrim.startsWith(")") -> aTrim.drop(1)
            else -> after
        }
    }

    private fun stripMatchingTrailingOpener(before: String, after: String): String {
        val bTrim = before.trimEnd()
        val aTrim = after.trimStart()
        return when {
            bTrim.endsWith("(") && aTrim.startsWith(")") -> bTrim.dropLast(1)
            bTrim.endsWith("[") && aTrim.startsWith("]") -> bTrim.dropLast(1)
            else -> before
        }
    }

    private fun cleanChapterTitle(raw: String): String {
        var s = raw.trim()
        s = s.replace(LEADING_BULLET_REGEX, "")
        s = s.replace(LEADING_TRACK_NO_REGEX, "")
        s = s.replace(EDGE_SEPARATOR_REGEX, "").trim()
        return s
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
        val safeTitle = CredentialRedactor.redactUrlForDisplay(sourceUrl)
            .removePrefix("https://")
            .removePrefix("http://")
            .take(64)
        return ResolvedUrlMetadata(
            sourceUrl = sourceUrl,
            host = host,
            title = safeTitle,
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

    private fun JsonObject.doubleVal(key: String): Double? {
        val el: JsonElement = this[key] ?: return null
        val prim = el as? JsonPrimitive ?: return null
        val value = prim.doubleOrNull ?: prim.longOrNull?.toDouble() ?: return null
        return value.takeIf { it.isFinite() }
    }
}
