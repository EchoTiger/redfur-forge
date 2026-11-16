package com.fissal.forge.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedactorTest {

    @Test
    fun redactsUrlUserinfoAndSensitiveQueryParametersWhileKeepingPublicIds() {
        val sensitive = "https://alice:s3cr3tPass@media.example.com/watch?v=dQw4w9WgXcQ&token=eyJhbGc123&X-Amz-Signature=deadbeef99&list=PL42#t=15"
        val redacted = CredentialRedactor.redactUrlForDisplay(sensitive)

        assertFalse(redacted.contains("alice"))
        assertFalse(redacted.contains("s3cr3tPass"))
        assertFalse(redacted.contains("eyJhbGc123"))
        assertFalse(redacted.contains("deadbeef99"))
        assertEquals(
            "https://[REDACTED]@media.example.com/watch?v=dQw4w9WgXcQ&token=[REDACTED]&X-Amz-Signature=[REDACTED]&list=PL42#t=15",
            redacted
        )
    }

    @Test
    fun leavesSafePublicUrlUnchanged() {
        val clean = "https://www.youtube.com/watch?v=abc123&list=PL99"
        assertEquals(clean, CredentialRedactor.redactUrlForDisplay(clean))
    }

    @Test
    fun sanitizesHeadersTokensPathsNetscapeLinesAndTruncatesCleanly() {
        val noisyStderr = """
            ERROR: yt-dlp failed with --cookies /data/user/0/com.fissal.forge/files/auth_tmp/cookies_abc.txt
            Cookie: SESSIONID=supersecretcookie999; SAPISID=xyz123
            Set-Cookie: __Secure-1PSID=topsecret
            Authorization: Bearer ghp_verySecretBearerTokenValue12345
            .youtube.com	TRUE	/	TRUE	1893456000	SID	secret_sid_value_here
            Fetching https://cdn.example.com/seg.ts?auth_token=secretTok&v=9
        """.trimIndent()

        val cleaned = CredentialRedactor.sanitizeDiagnostic(noisyStderr, maxChars = 500)
        assertFalse(cleaned.contains("supersecretcookie999"))
        assertFalse(cleaned.contains("topsecret"))
        assertFalse(cleaned.contains("ghp_verySecretBearerTokenValue12345"))
        assertFalse(cleaned.contains("secret_sid_value_here"))
        assertFalse(cleaned.contains("secretTok"))
        assertFalse(cleaned.contains("auth_tmp/cookies_abc.txt"))
        assertTrue(cleaned.contains("[REDACTED]"))
        assertTrue(cleaned.contains("[REDACTED_AUTH_PATH]"))
        assertTrue(cleaned.contains("[REDACTED_TOKEN]"))
        assertTrue(cleaned.contains("[REDACTED_COOKIE_LINE]"))
        assertTrue(cleaned.contains("v=9"))

        val truncated = CredentialRedactor.sanitizeDiagnostic("A".repeat(400), maxChars = 64)
        assertEquals(64, truncated.length)
        assertTrue(truncated.endsWith("..."))
    }
}
