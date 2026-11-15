package com.fissal.forge.domain.logic

import java.net.URI

data class ParsedUrlItem(
    val rawUrl: String,
    val cleanUrl: String,
    val isPlaylistLike: Boolean,
    val host: String
)

/**
 * Extracts, deduplicates, detects playlists, and strips tracking parameters from pasted/shared text.
 * Pure Kotlin/JDK implementation so JVM unit tests run without Android framework dependencies.
 */
object UrlBatchParser {

    private val URL_REGEX = Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)
    private val TRACKING_PARAMS = setOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        "si",
        "igsh",
        "fbclid",
        "gclid",
        "feature",
        "pp"
    )
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    fun extractUrls(rawText: String, stripTracking: Boolean): List<ParsedUrlItem> {
        if (rawText.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<ParsedUrlItem>()

        for (match in URL_REGEX.findAll(rawText)) {
            val rawUrl = match.value.trimEnd(*TRAILING_PUNCTUATION)
            if (rawUrl.length < 10) continue

            val cleanUrl = if (stripTracking) stripTrackingParams(rawUrl) else rawUrl
            if (!seen.add(cleanUrl)) continue

            val parsedUri = runCatching { URI(cleanUrl) }.getOrNull()
            val host = parsedUri?.host?.removePrefix("www.") ?: ""
            val query = parsedUri?.rawQuery ?: ""
            val path = parsedUri?.rawPath?.lowercase() ?: ""
            val isPlaylistLike = query.split("&").any {
                it.startsWith("list=", ignoreCase = true)
            } || path.contains("/playlist") || path.contains("/sets/") || path.contains("/album/")

            results += ParsedUrlItem(
                rawUrl = rawUrl,
                cleanUrl = cleanUrl,
                isPlaylistLike = isPlaylistLike,
                host = host
            )
        }
        return results
    }

    private fun stripTrackingParams(url: String): String {
        val hashSplit = url.split("#", limit = 2)
        val baseWithQuery = hashSplit[0]
        val fragment = if (hashSplit.size == 2) "#${hashSplit[1]}" else ""

        val queryIdx = baseWithQuery.indexOf('?')
        if (queryIdx == -1) return url

        val base = baseWithQuery.substring(0, queryIdx)
        val rawQuery = baseWithQuery.substring(queryIdx + 1)
        if (rawQuery.isEmpty()) return "$base$fragment"

        val kept = rawQuery.split("&").filter { pair ->
            if (pair.isBlank()) return@filter false
            val key = pair.substringBefore('=').lowercase()
            key !in TRACKING_PARAMS
        }

        return if (kept.isEmpty()) {
            "$base$fragment"
        } else {
            "$base?${kept.joinToString("&")}$fragment"
        }
    }
}
