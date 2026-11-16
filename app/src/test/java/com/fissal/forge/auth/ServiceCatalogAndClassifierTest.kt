package com.fissal.forge.auth

import com.fissal.forge.domain.model.DownloadSpec
import com.fissal.forge.domain.model.SponsorBlockCategory
import com.fissal.forge.engine.YtDlpEngine
import com.fissal.forge.ui.components.buildDropdownOptions
import com.fissal.forge.ui.components.sanitizeWebViewUserAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCatalogAndClassifierTest {

    @Test
    fun entries_containsAllThirteenCuratedServices() {
        val expectedIds = listOf(
            "youtube",
            "facebook",
            "instagram",
            "twitter",
            "tiktok",
            "twitch",
            "reddit",
            "patreon",
            "vimeo",
            "soundcloud",
            "bilibili",
            "niconico",
            "custom"
        )
        assertEquals(13, ServiceCatalog.entries.size)
        assertEquals(expectedIds, ServiceCatalog.entries.map { it.id })

        val template = ServiceCatalog.findById("custom")
        assertNotNull(template)
        assertTrue(template!!.isCustomDomainTemplate)

        ServiceCatalog.entries.filterNot { it.isCustomDomainTemplate }.forEach { entry ->
            assertTrue("Expected https loginUrl for ${entry.id}", entry.loginUrl.startsWith("https://"))
            assertTrue("Expected hostMatchers for ${entry.id}", entry.hostMatchers.isNotEmpty())
            assertTrue("Expected cookieDomains for ${entry.id}", entry.cookieDomains.isNotEmpty())
        }
    }

    @Test
    fun filterServices_matchesByNameIdHostAndKeywords() {
        assertEquals(13, ServiceCatalog.filterServices("").size)
        assertEquals(13, ServiceCatalog.filterServices("   ").size)

        assertEquals(listOf("youtube"), ServiceCatalog.filterServices("youtu.be").map { it.id })
        assertEquals(listOf("facebook"), ServiceCatalog.filterServices("fb.watch").map { it.id })
        assertEquals(listOf("twitter"), ServiceCatalog.filterServices("TWITTER").map { it.id })
        assertEquals(listOf("patreon"), ServiceCatalog.filterServices("paywall").map { it.id })
        assertEquals(listOf("bilibili"), ServiceCatalog.filterServices("anime").map { it.id })
    }

    @Test
    fun forCustomDomain_normalizesUrlsAndRejectsInvalidInputs() {
        val fromFullUrl = ServiceCatalog.forCustomDomain("https://Members.Example.com/login?next=/vid")
        assertNotNull(fromFullUrl)
        assertEquals("custom_members.example.com", fromFullUrl!!.id)
        assertEquals("members.example.com", fromFullUrl.displayName)
        assertEquals("https://members.example.com/login", fromFullUrl.loginUrl)
        assertEquals(setOf("members.example.com", "example.com"), fromFullUrl.hostMatchers)
        assertEquals(listOf(".members.example.com", "https://members.example.com"), fromFullUrl.cookieDomains)
        assertFalse(fromFullUrl.isCustomDomainTemplate)

        val fromBareHost = ServiceCatalog.forCustomDomain("  members.example.com ")
        assertNotNull(fromBareHost)
        assertEquals("https://members.example.com", fromBareHost!!.loginUrl)

        val viaFindById = ServiceCatalog.findById("custom_members.example.com")
        assertNotNull(viaFindById)
        assertEquals("members.example.com", viaFindById!!.displayName)

        assertNull(ServiceCatalog.forCustomDomain(""))
        assertNull(ServiceCatalog.forCustomDomain("   "))
        assertNull(ServiceCatalog.forCustomDomain("notadomain"))
        assertNull(ServiceCatalog.forCustomDomain("javascript:alert(1)"))
        assertNull(ServiceCatalog.forCustomDomain("ftp://files.example.com"))
    }

    @Test
    fun matchUrl_resolvesSubdomainsShortLinksCustomDomainsAndAvoidsFalsePositives() {
        assertEquals("youtube", ServiceCatalog.matchUrl("https://youtu.be/dQw4w9WgXcQ")?.id)
        assertEquals("youtube", ServiceCatalog.matchUrl("https://music.youtube.com/watch?v=123")?.id)
        assertEquals("facebook", ServiceCatalog.matchUrl("https://fb.watch/abc123/")?.id)
        assertEquals("facebook", ServiceCatalog.matchUrl("https://m.facebook.com/story.php?id=1")?.id)
        assertEquals("twitter", ServiceCatalog.matchUrl("https://x.com/user/status/1")?.id)
        assertEquals("twitter", ServiceCatalog.matchUrl("https://t.co/xyz")?.id)
        assertEquals("reddit", ServiceCatalog.matchUrl("https://redd.it/abcdef")?.id)
        assertEquals("patreon", ServiceCatalog.matchUrl("https://media.patreon.com/post/1")?.id)
        assertEquals("bilibili", ServiceCatalog.matchUrl("https://b23.tv/abc")?.id)

        // Subdomain suffix must match cleanly on dot boundary, never substring collisions.
        assertNull(ServiceCatalog.matchUrl("https://evilx.com/video/1"))
        assertNull(ServiceCatalog.matchUrl("https://myyoutube.com/watch?v=1"))

        val customMatched = ServiceCatalog.matchUrl(
            url = "https://cdn.members.example.com/hls/master.m3u8?token=secret",
            customDomains = listOf("members.example.com")
        )
        assertNotNull(customMatched)
        assertEquals("custom_members.example.com", customMatched!!.id)
    }

    @Test
    fun authErrorClassifier_distinguishesLoginVsPaywallWithoutLeakingQueryParams() {
        val secretUrl = "https://www.youtube.com/watch?v=abc123&token=TOP_SECRET_QUERY_VAL"

        val botGate = AuthErrorClassifier.classifyYtDlpError(
            url = secretUrl,
            rawStderr = "ERROR: [youtube] abc123: Sign in to confirm you're not a bot. Use --cookies-from-browser"
        )
        assertNotNull(botGate)
        assertEquals("youtube", botGate!!.service?.id)
        assertFalse(botGate.isPaywallOrSubscription)
        assertTrue(botGate.userFacingReason.contains("YouTube"))
        assertFalse(botGate.userFacingReason.contains("TOP_SECRET_QUERY_VAL"))
        assertFalse(botGate.userFacingReason.contains("v=abc123"))

        val membersOnly = AuthErrorClassifier.classifyYtDlpError(
            url = secretUrl,
            rawStderr = "ERROR: [youtube] abc123: Join this channel to get access to members-only content like this video"
        )
        assertNotNull(membersOnly)
        assertTrue(membersOnly!!.isPaywallOrSubscription)
        assertTrue(membersOnly.userFacingReason.contains("membership", ignoreCase = true))

        val patreonPaywall = AuthErrorClassifier.classifyYtDlpError(
            url = "https://www.patreon.com/posts/exclusive-123?auth=secret_param",
            rawStderr = "ERROR: This video is only available for patrons. HTTP Error 403: Forbidden"
        )
        assertNotNull(patreonPaywall)
        assertEquals("patreon", patreonPaywall!!.service?.id)
        assertTrue(patreonPaywall.isPaywallOrSubscription)
        assertFalse(patreonPaywall.userFacingReason.contains("secret_param"))

        val customPrivate = AuthErrorClassifier.classifyYtDlpError(
            url = "https://members.example.com/watch/42?key=do_not_leak",
            rawStderr = "ERROR: HTTP Error 401: Unauthorized - Login required",
            customDomains = listOf("members.example.com")
        )
        assertNotNull(customPrivate)
        assertEquals("custom_members.example.com", customPrivate!!.service?.id)
        assertFalse(customPrivate.isPaywallOrSubscription)
        assertTrue(customPrivate.userFacingReason.contains("members.example.com"))
        assertFalse(customPrivate.userFacingReason.contains("do_not_leak"))

        val plainNetworkFailure = AuthErrorClassifier.classifyYtDlpError(
            url = secretUrl,
            rawStderr = "ERROR: Unable to download webpage: <urlopen error timed out>"
        )
        assertNull(plainNetworkFailure)
    }

    @Test
    fun buildSponsorBlockRemoveValue_handlesEmptyLegacySingleAndCatalogOrderedMulti() {
        val disabled = DownloadSpec(url = "https://www.youtube.com/watch?v=1")
        assertNull(YtDlpEngine.buildSponsorBlockRemoveValue(disabled))

        val legacyFlagOnly = DownloadSpec(
            url = "https://www.youtube.com/watch?v=1",
            sponsorBlock = true
        )
        assertEquals("sponsor", YtDlpEngine.buildSponsorBlockRemoveValue(legacyFlagOnly))

        val singleExplicit = DownloadSpec(
            url = "https://www.youtube.com/watch?v=1",
            sponsorBlockCategories = setOf(SponsorBlockCategory.SELFPROMO)
        )
        assertEquals("selfpromo", YtDlpEngine.buildSponsorBlockRemoveValue(singleExplicit))

        val multiReverseOrder = DownloadSpec(
            url = "https://www.youtube.com/watch?v=1",
            sponsorBlock = true,
            sponsorBlockCategories = linkedSetOf(
                SponsorBlockCategory.OUTRO,
                SponsorBlockCategory.INTRO,
                SponsorBlockCategory.SPONSOR
            )
        )
        assertEquals("sponsor,intro,outro", YtDlpEngine.buildSponsorBlockRemoveValue(multiReverseOrder))
    }

    @Test
    fun sanitizeWebViewUserAgentStripsWebViewTokensWithOrWithoutSpace() {
        val withSpace = "Mozilla/5.0 (Linux; Android 15; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36"
        val cleanedSpace = sanitizeWebViewUserAgent(withSpace)
        assertFalse(cleanedSpace.contains("wv"))
        assertFalse(cleanedSpace.contains("Version/4.0"))
        assertTrue(cleanedSpace.contains("Chrome/131.0.0.0"))

        val noSpace = "Mozilla/5.0 (Linux; Android 14; K;wv) AppleWebKit/537.36 Version/4.0 Chrome/130.0.0.0"
        val cleanedNoSpace = sanitizeWebViewUserAgent(noSpace)
        assertFalse(cleanedNoSpace.contains(";wv"))
        assertFalse(cleanedNoSpace.contains("Version/4.0"))
        assertEquals("Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 Chrome/130.0.0.0", cleanedNoSpace)
    }

    @Test
    fun buildDropdownOptionsSuppressesCustomDomainDuplicateForBuiltInHosts() {
        val youtubeOptions = buildDropdownOptions("youtube.com", connectedIds = setOf("youtube"))
        assertTrue(youtubeOptions.any { it.service.id == "youtube" && it.isConnected })
        assertFalse(youtubeOptions.any { it.service.id == "custom_youtube.com" })

        val patreonOptions = buildDropdownOptions("www.patreon.com", connectedIds = emptySet())
        assertFalse(patreonOptions.any { it.service.id == "custom_www.patreon.com" })

        val customOptions = buildDropdownOptions("members.example.org", connectedIds = emptySet())
        assertTrue(customOptions.any { it.service.id == "custom_members.example.org" && it.title.contains("members.example.org") })
    }
}
