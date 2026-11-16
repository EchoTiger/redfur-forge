package com.fissal.forge.auth

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class ServiceDescriptor(
    val id: String,
    val displayName: String,
    val loginUrl: String,
    val hostMatchers: Set<String>,
    val cookieDomains: List<String>,
    val requiredSessionCookieHints: Set<String>,
    val brandHex: Long,
    val glyphCode: String,
    val searchKeywords: Set<String> = emptySet(),
    val isCustomDomainTemplate: Boolean = false
)

/**
 * Curated catalog of supported media platforms and custom-domain authentication descriptors.
 * Pure JVM (no android.* dependencies) so it can be exercised directly by unit tests.
 */
object ServiceCatalog {

    private val twoPartPublicSuffixLeaders = setOf(
        "co", "com", "org", "net", "edu", "gov", "ac", "ne", "or", "go", "gr", "ed"
    )

    private val hostnameLabelRegex = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    val entries: List<ServiceDescriptor> = listOf(
        ServiceDescriptor(
            id = "youtube",
            displayName = "YouTube",
            loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F",
            hostMatchers = setOf(
                "youtube.com",
                "www.youtube.com",
                "m.youtube.com",
                "music.youtube.com",
                "studio.youtube.com",
                "youtu.be",
                "youtube-nocookie.com",
                "www.youtube-nocookie.com"
            ),
            cookieDomains = listOf(
                ".youtube.com",
                ".google.com",
                "https://www.youtube.com",
                "https://m.youtube.com",
                "https://music.youtube.com",
                "https://accounts.google.com"
            ),
            requiredSessionCookieHints = setOf(
                "SID",
                "HSID",
                "SSID",
                "APISID",
                "SAPISID",
                "__Secure-1PSID",
                "__Secure-3PSID",
                "LOGIN_INFO"
            ),
            brandHex = 0xFFFF0000L,
            glyphCode = "YT",
            searchKeywords = setOf("yt", "google", "video", "shorts", "music", "members")
        ),
        ServiceDescriptor(
            id = "facebook",
            displayName = "Facebook",
            loginUrl = "https://www.facebook.com/login/",
            hostMatchers = setOf(
                "facebook.com",
                "www.facebook.com",
                "m.facebook.com",
                "mbasic.facebook.com",
                "web.facebook.com",
                "fb.watch",
                "fb.com",
                "www.fb.com"
            ),
            cookieDomains = listOf(
                ".facebook.com",
                "https://www.facebook.com",
                "https://m.facebook.com",
                "https://web.facebook.com"
            ),
            requiredSessionCookieHints = setOf("c_user", "xs"),
            brandHex = 0xFF1877F2L,
            glyphCode = "FB",
            searchKeywords = setOf("fb", "meta", "reels", "watch")
        ),
        ServiceDescriptor(
            id = "instagram",
            displayName = "Instagram",
            loginUrl = "https://www.instagram.com/accounts/login/",
            hostMatchers = setOf(
                "instagram.com",
                "www.instagram.com",
                "m.instagram.com",
                "instagr.am"
            ),
            cookieDomains = listOf(
                ".instagram.com",
                "https://www.instagram.com",
                "https://i.instagram.com"
            ),
            requiredSessionCookieHints = setOf("sessionid", "ds_user_id"),
            brandHex = 0xFFE4405FL,
            glyphCode = "IG",
            searchKeywords = setOf("ig", "insta", "reels", "stories", "meta")
        ),
        ServiceDescriptor(
            id = "twitter",
            displayName = "X / Twitter",
            loginUrl = "https://x.com/i/flow/login",
            hostMatchers = setOf(
                "x.com",
                "www.x.com",
                "mobile.x.com",
                "twitter.com",
                "www.twitter.com",
                "mobile.twitter.com",
                "t.co",
                "fxtwitter.com",
                "vxtwitter.com"
            ),
            cookieDomains = listOf(
                ".x.com",
                ".twitter.com",
                "https://x.com",
                "https://twitter.com"
            ),
            requiredSessionCookieHints = setOf("auth_token", "ct0", "twid"),
            brandHex = 0xFF14171AL,
            glyphCode = "X",
            searchKeywords = setOf("x", "twitter", "tweet", "spaces")
        ),
        ServiceDescriptor(
            id = "tiktok",
            displayName = "TikTok",
            loginUrl = "https://www.tiktok.com/login",
            hostMatchers = setOf(
                "tiktok.com",
                "www.tiktok.com",
                "m.tiktok.com",
                "vm.tiktok.com",
                "vt.tiktok.com"
            ),
            cookieDomains = listOf(
                ".tiktok.com",
                "https://www.tiktok.com",
                "https://m.tiktok.com"
            ),
            requiredSessionCookieHints = setOf("sessionid", "sessionid_ss", "sid_tt", "uid_tt"),
            brandHex = 0xFFEE1D52L,
            glyphCode = "TT",
            searchKeywords = setOf("tt", "bytedance", "douyin", "short")
        ),
        ServiceDescriptor(
            id = "twitch",
            displayName = "Twitch",
            loginUrl = "https://www.twitch.tv/login",
            hostMatchers = setOf(
                "twitch.tv",
                "www.twitch.tv",
                "m.twitch.tv",
                "clips.twitch.tv"
            ),
            cookieDomains = listOf(
                ".twitch.tv",
                "https://www.twitch.tv",
                "https://m.twitch.tv",
                "https://passport.twitch.tv"
            ),
            requiredSessionCookieHints = setOf("auth-token", "twilight-user", "persistent"),
            brandHex = 0xFF9146FFL,
            glyphCode = "TW",
            searchKeywords = setOf("stream", "vod", "clips", "sub", "subscriber")
        ),
        ServiceDescriptor(
            id = "reddit",
            displayName = "Reddit",
            loginUrl = "https://www.reddit.com/login/",
            hostMatchers = setOf(
                "reddit.com",
                "www.reddit.com",
                "old.reddit.com",
                "new.reddit.com",
                "m.reddit.com",
                "v.redd.it",
                "redd.it"
            ),
            cookieDomains = listOf(
                ".reddit.com",
                "https://www.reddit.com",
                "https://old.reddit.com"
            ),
            requiredSessionCookieHints = setOf("reddit_session", "token_v2", "loid"),
            brandHex = 0xFFFF4500L,
            glyphCode = "RD",
            searchKeywords = setOf("subreddit", "nsfw", "vreddit")
        ),
        ServiceDescriptor(
            id = "patreon",
            displayName = "Patreon",
            loginUrl = "https://www.patreon.com/login",
            hostMatchers = setOf(
                "patreon.com",
                "www.patreon.com"
            ),
            cookieDomains = listOf(
                ".patreon.com",
                "https://www.patreon.com"
            ),
            requiredSessionCookieHints = setOf("session_id"),
            brandHex = 0xFFFF424DL,
            glyphCode = "PT",
            searchKeywords = setOf("patron", "membership", "creator", "paywall")
        ),
        ServiceDescriptor(
            id = "vimeo",
            displayName = "Vimeo",
            loginUrl = "https://vimeo.com/log_in",
            hostMatchers = setOf(
                "vimeo.com",
                "www.vimeo.com",
                "player.vimeo.com"
            ),
            cookieDomains = listOf(
                ".vimeo.com",
                "https://vimeo.com",
                "https://player.vimeo.com"
            ),
            requiredSessionCookieHints = setOf("vimeo", "vuid"),
            brandHex = 0xFF1AB7EAL,
            glyphCode = "VM",
            searchKeywords = setOf("video", "private", "password", "ondemand")
        ),
        ServiceDescriptor(
            id = "soundcloud",
            displayName = "SoundCloud",
            loginUrl = "https://soundcloud.com/signin",
            hostMatchers = setOf(
                "soundcloud.com",
                "www.soundcloud.com",
                "m.soundcloud.com",
                "on.soundcloud.com",
                "snd.sc"
            ),
            cookieDomains = listOf(
                ".soundcloud.com",
                "https://soundcloud.com",
                "https://m.soundcloud.com"
            ),
            requiredSessionCookieHints = setOf("oauth_token"),
            brandHex = 0xFFFF5500L,
            glyphCode = "SC",
            searchKeywords = setOf("audio", "music", "track", "go+")
        ),
        ServiceDescriptor(
            id = "bilibili",
            displayName = "Bilibili",
            loginUrl = "https://passport.bilibili.com/login",
            hostMatchers = setOf(
                "bilibili.com",
                "www.bilibili.com",
                "m.bilibili.com",
                "b23.tv",
                "live.bilibili.com"
            ),
            cookieDomains = listOf(
                ".bilibili.com",
                "https://www.bilibili.com",
                "https://passport.bilibili.com"
            ),
            requiredSessionCookieHints = setOf("SESSDATA", "bili_jct", "DedeUserID"),
            brandHex = 0xFF00A1D6L,
            glyphCode = "BL",
            searchKeywords = setOf("b站", "b23", "anime", "bangumi")
        ),
        ServiceDescriptor(
            id = "niconico",
            displayName = "Niconico",
            loginUrl = "https://account.nicovideo.jp/login",
            hostMatchers = setOf(
                "nicovideo.jp",
                "www.nicovideo.jp",
                "sp.nicovideo.jp",
                "live.nicovideo.jp",
                "nico.ms"
            ),
            cookieDomains = listOf(
                ".nicovideo.jp",
                "https://www.nicovideo.jp",
                "https://account.nicovideo.jp"
            ),
            requiredSessionCookieHints = setOf("user_session", "user_session_secure"),
            brandHex = 0xFF252525L,
            glyphCode = "NN",
            searchKeywords = setOf("nico", "nicovideo", "japan", "vocaloid")
        ),
        ServiceDescriptor(
            id = "custom",
            displayName = "Custom Domain",
            loginUrl = "",
            hostMatchers = emptySet(),
            cookieDomains = emptyList(),
            requiredSessionCookieHints = emptySet(),
            brandHex = 0xFF607D8BL,
            glyphCode = "WEB",
            searchKeywords = setOf("custom", "domain", "site", "other", "private", "cookies"),
            isCustomDomainTemplate = true
        )
    )

    fun findById(id: String): ServiceDescriptor? {
        val normalized = id.trim()
        if (normalized.isEmpty()) return null
        entries.firstOrNull { it.id.equals(normalized, ignoreCase = true) }?.let { return it }
        if (normalized.startsWith("custom_", ignoreCase = true)) {
            return forCustomDomain(normalized.substring("custom_".length))
        }
        return null
    }

    fun forCustomDomain(rawInput: String): ServiceDescriptor? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        val hasScheme = trimmed.contains("://")
        val candidateUrl = if (hasScheme) {
            trimmed
        } else {
            "https://${trimmed.trimStart('.')}"
        }

        val parsed = runCatching { URI(candidateUrl) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = parsed.host?.lowercase()?.trimEnd('.')?.trimStart('.') ?: return null
        if (!isValidHostname(host)) return null

        val portSuffix = if (parsed.port > 0 && parsed.port != 80 && parsed.port != 443) {
            ":${parsed.port}"
        } else {
            ""
        }
        val cleanPath = parsed.rawPath?.takeIf { it.isNotBlank() && it != "/" } ?: ""
        val normalizedLoginUrl = "$scheme://$host$portSuffix$cleanPath"

        val rootDomain = computeRootDomain(host)
        val matchers = buildSet {
            add(host)
            if (rootDomain != null && rootDomain != host) {
                add(rootDomain)
            }
        }
        val glyph = host.take(2).uppercase().ifEmpty { "WB" }

        return ServiceDescriptor(
            id = "custom_$host",
            displayName = host,
            loginUrl = normalizedLoginUrl,
            hostMatchers = matchers,
            cookieDomains = listOf(".$host", "https://$host"),
            requiredSessionCookieHints = emptySet(),
            brandHex = 0xFF607D8BL,
            glyphCode = glyph,
            searchKeywords = setOf(host, "custom"),
            isCustomDomainTemplate = false
        )
    }

    fun filterServices(query: String): List<ServiceDescriptor> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { service ->
            service.displayName.lowercase().contains(q) ||
                service.id.lowercase().contains(q) ||
                service.hostMatchers.any { it.lowercase().contains(q) } ||
                service.searchKeywords.any { it.lowercase().contains(q) }
        }
    }

    fun matchUrl(url: String, customDomains: Collection<String> = emptyList()): ServiceDescriptor? {
        val host = extractHost(url) ?: return null
        val customDescriptors = customDomains.mapNotNull { forCustomDomain(it) }
        val builtInServices = entries.filterNot { it.isCustomDomainTemplate }
        val candidates = customDescriptors + builtInServices

        // Prefer exact host matches first, then subdomain suffix matches so specific domains win.
        candidates.firstOrNull { service ->
            service.hostMatchers.any { matcher -> host == matcher.lowercase() }
        }?.let { return it }

        return candidates.firstOrNull { service ->
            service.hostMatchers.any { matcher ->
                val normalized = matcher.lowercase()
                host.endsWith(".$normalized")
            }
        }
    }

    internal fun extractHost(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val parsed = runCatching { URI(withScheme) }.getOrNull()
        val host = parsed?.host?.lowercase()?.trimEnd('.')
        if (!host.isNullOrBlank()) return host

        // Fallback if URL contains unencoded query characters that reject strict URI parsing.
        val afterScheme = withScheme.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val withoutUserInfo = authority.substringAfterLast('@')
        val bareHost = withoutUserInfo.substringBefore(':').lowercase().trimEnd('.')
        return bareHost.takeIf { it.isNotBlank() && isValidHostname(it) }
    }

    private fun isValidHostname(host: String): Boolean {
        if (host.length > 253 || !host.contains('.')) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        return labels.all { label -> hostnameLabelRegex.matches(label) }
    }

    private fun computeRootDomain(host: String): String? {
        val parts = host.split('.')
        if (parts.size < 3) return null
        val last = parts.last()
        val secondLast = parts[parts.size - 2]
        val isMultiPartCcTld = last.length == 2 && secondLast in twoPartPublicSuffixLeaders
        val rootCount = if (isMultiPartCcTld && parts.size >= 4) 3 else 2
        return parts.takeLast(rootCount).joinToString(".")
    }
}
