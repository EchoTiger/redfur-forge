package com.fissal.forge.auth

import java.io.IOException

/**
 * Raised when yt-dlp reports an authentication, private-video, age-gate, or membership block.
 */
class AuthRequiredException(
    val service: ServiceDescriptor?,
    val targetUrl: String,
    val userFacingReason: String,
    val isPaywallOrSubscription: Boolean = false,
    cause: Throwable? = null
) : IOException(userFacingReason, cause)

/**
 * Classifies raw yt-dlp stderr lines into contextual [AuthRequiredException] instances
 * without leaking URL query parameters, cookies, or tokens into user-facing text.
 */
object AuthErrorClassifier {

    private val loginWordBoundaryRegex = Regex("""\blog\s+in\b""", RegexOption.IGNORE_CASE)

    private val paywallSubstrings = listOf(
        "members-only",
        "members only",
        "channel's members",
        "join this channel",
        "subscriber",
        "patron",
        "paywall",
        "purchase required",
        "requires purchase",
        "rent or buy",
        "paid content"
    )

    private val authSubstrings = listOf(
        "sign in to confirm",
        "sign in to view",
        "please sign in",
        "login required",
        "must be logged in",
        "you need to log in",
        "private video",
        "this video is private",
        "age-restricted",
        "age restricted",
        "confirm your age",
        "use --cookies",
        "--cookies-from-browser",
        "http error 401",
        "http error 403",
        "not available without authentication",
        "requested content is not available"
    )

    private val ageGateSubstrings = listOf(
        "age-restricted",
        "age restricted",
        "confirm your age"
    )

    private val privateVideoSubstrings = listOf(
        "private video",
        "this video is private"
    )

    fun classifyYtDlpError(
        url: String,
        rawStderr: String?,
        customDomains: Collection<String> = emptyList()
    ): AuthRequiredException? {
        if (rawStderr.isNullOrBlank()) return null

        val lower = rawStderr.lowercase()
        val isPaywall = paywallSubstrings.any { lower.contains(it) }
        val isAuthError = isPaywall ||
            authSubstrings.any { lower.contains(it) } ||
            loginWordBoundaryRegex.containsMatchIn(rawStderr)

        if (!isAuthError) return null

        val service = ServiceCatalog.matchUrl(url, customDomains)
        val siteLabel = service?.displayName ?: ServiceCatalog.extractHost(url) ?: "this site"

        val userFacingReason = when {
            isPaywall ->
                "$siteLabel requires an active membership or subscription to access this content. Connect your $siteLabel account in Sites & Cookies."
            ageGateSubstrings.any { lower.contains(it) } ->
                "$siteLabel requires sign-in to confirm your age before downloading this content. Connect your $siteLabel account in Sites & Cookies."
            privateVideoSubstrings.any { lower.contains(it) } ->
                "$siteLabel marked this media private and requires an authorized session. Connect your $siteLabel account in Sites & Cookies."
            else ->
                "$siteLabel requires authentication to access this media. Connect your $siteLabel account in Sites & Cookies and retry."
        }

        return AuthRequiredException(
            service = service,
            targetUrl = url,
            userFacingReason = userFacingReason,
            isPaywallOrSubscription = isPaywall
        )
    }
}
