package com.fissal.forge.auth

import java.util.Locale

/**
 * Pure JVM sanitizer stripping cookies, auth tokens, bearer credentials, sensitive signed URL
 * parameters, and internal `auth_tmp` / `auth_vault` filesystem paths from UI banners,
 * Room error columns, and exception diagnostics.
 */
object CredentialRedactor {

    private val SENSITIVE_QUERY_KEYS = setOf(
        "token",
        "access_token",
        "refresh_token",
        "id_token",
        "auth",
        "auth_token",
        "session",
        "sessionid",
        "session_id",
        "sid",
        "key",
        "api_key",
        "apikey",
        "secret",
        "password",
        "passwd",
        "sig",
        "signature",
        "x-amz-signature",
        "x-amz-credential",
        "x-amz-security-token",
        "hdnea",
        "oauth_token",
        "jwt"
    )

    private val URL_REGEX = Regex("""https?://[^\s"')>\]]+""", RegexOption.IGNORE_CASE)
    private val COOKIE_HEADER_REGEX = Regex(
        """(?im)\b(set-cookie|cookie)\s*:\s*[^\r\n]+"""
    )
    private val AUTH_HEADER_REGEX = Regex(
        """(?im)\b(authorization)\s*:\s*[^\r\n]+"""
    )
    private val BEARER_TOKEN_REGEX = Regex(
        """(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}"""
    )
    private val COOKIES_ARG_REGEX = Regex(
        """(?i)(--cookies(?:-from-browser)?)(?:\s+|=)(?:"[^"]*"|'[^']*'|[^\s"']+)"""
    )
    private val AUTH_DIR_PATH_REGEX = Regex(
        """[^\s"':]*(?:[/\\])(?:auth_tmp|auth_vault)(?:(?:[/\\])[^\s"']*)?"""
    )
    private val NETSCAPE_LINE_REGEX = Regex(
        """(?m)^\.?[^\t\r\n]+\t(?:TRUE|FALSE)\t[^\t\r\n]*\t(?:TRUE|FALSE)\t\d+\t[^\t\r\n]+\t[^\r\n]*$""",
        RegexOption.IGNORE_CASE
    )
    private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")

    /**
     * Masks embedded URL credentials (`user:pass@`) and sensitive query parameters while leaving
     * public media identifiers (`v=`, `list=`, etc.) readable for display.
     */
    fun redactUrlForDisplay(rawUrl: String): String {
        if (rawUrl.isBlank()) return rawUrl
        val hashIdx = rawUrl.indexOf('#')
        val mainPart = if (hashIdx >= 0) rawUrl.substring(0, hashIdx) else rawUrl
        val fragment = if (hashIdx >= 0) rawUrl.substring(hashIdx) else ""

        val queryIdx = mainPart.indexOf('?')
        val authorityAndPath = if (queryIdx >= 0) mainPart.substring(0, queryIdx) else mainPart
        val rawQuery = if (queryIdx >= 0) mainPart.substring(queryIdx + 1) else null

        val redactedAuthorityPath = redactUserInfo(authorityAndPath)
        val redactedQuery = rawQuery?.let { query ->
            if (query.isEmpty()) {
                ""
            } else {
                query.split('&').joinToString("&") { pair ->
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx <= 0) {
                        pair
                    } else {
                        val key = pair.substring(0, eqIdx)
                        if (key.lowercase(Locale.US) in SENSITIVE_QUERY_KEYS) {
                            "$key=[REDACTED]"
                        } else {
                            pair
                        }
                    }
                }
            }
        }

        return buildString {
            append(redactedAuthorityPath)
            if (redactedQuery != null) {
                append('?').append(redactedQuery)
            }
            append(fragment)
        }
    }

    /**
     * Strips cookie lines, headers, bearer tokens, auth file paths, and signed URL secrets from
     * diagnostic strings and bounds output length for UI banners / Room persistence.
     */
    fun sanitizeDiagnostic(rawMessage: String?, maxChars: Int = 320): String {
        if (rawMessage.isNullOrBlank()) return ""
        var text = rawMessage
        text = COOKIE_HEADER_REGEX.replace(text) { mr -> "${mr.groupValues[1]}: [REDACTED]" }
        text = AUTH_HEADER_REGEX.replace(text) { mr -> "${mr.groupValues[1]}: [REDACTED_TOKEN]" }
        text = BEARER_TOKEN_REGEX.replace(text) { mr -> "${mr.groupValues[1]} [REDACTED_TOKEN]" }
        text = COOKIES_ARG_REGEX.replace(text) { mr -> "${mr.groupValues[1]} [REDACTED_AUTH_PATH]" }
        text = AUTH_DIR_PATH_REGEX.replace(text, "[REDACTED_AUTH_PATH]")
        text = NETSCAPE_LINE_REGEX.replace(text, "[REDACTED_COOKIE_LINE]")
        text = URL_REGEX.replace(text) { mr -> redactUrlForDisplay(mr.value) }
        text = MULTI_WHITESPACE_REGEX.replace(text, " ").trim()

        val safeMax = maxChars.coerceAtLeast(16)
        return if (text.length <= safeMax) {
            text
        } else {
            text.take((safeMax - 3).coerceAtLeast(1)).trimEnd() + "..."
        }
    }

    private fun redactUserInfo(authorityAndPath: String): String {
        val schemeSepIdx = authorityAndPath.indexOf("://")
        val authorityStart = if (schemeSepIdx >= 0) schemeSepIdx + 3 else 0
        val slashIdx = authorityAndPath.indexOf('/', authorityStart)
        val authorityEnd = if (slashIdx >= 0) slashIdx else authorityAndPath.length
        val atIdx = authorityAndPath.indexOf('@', authorityStart)
        if (atIdx < 0 || atIdx >= authorityEnd) {
            return authorityAndPath
        }
        return authorityAndPath.substring(0, authorityStart) +
            "[REDACTED]@" +
            authorityAndPath.substring(atIdx + 1)
    }
}
