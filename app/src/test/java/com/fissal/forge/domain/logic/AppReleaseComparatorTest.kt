package com.fissal.forge.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseComparatorTest {

    @Test
    fun comparesSemanticAndMultiDigitVersionsAccurately() {
        assertTrue(AppReleaseComparator.isRemoteNewer("1.1.0", "v1.2.0"))
        assertTrue(AppReleaseComparator.isRemoteNewer("1.2.0", "1.10.0"))
        assertTrue(AppReleaseComparator.isRemoteNewer("1.2.0-rc1", "v1.2.0"))
        assertFalse(AppReleaseComparator.isRemoteNewer("1.2.0", "v1.2.0"))
        assertFalse(AppReleaseComparator.isRemoteNewer("1.2.0", "1.1.9"))
        assertFalse(AppReleaseComparator.isRemoteNewer("1.2.0", "v1.2.0-beta1"))
    }

    @Test
    fun selectsMatchingAbiAssetAndFallsBackToUniversal() {
        val assets = listOf(
            ReleaseAssetInfo("redfur-forge-v1.2.0-armeabi-v7a.apk", "https://example.invalid/v7a.apk", 63_000_000L),
            ReleaseAssetInfo("redfur-forge-v1.2.0-arm64-v8a.apk", "https://example.invalid/arm64.apk", 73_000_000L),
            ReleaseAssetInfo("redfur-forge-v1.2.0-universal.apk", "https://example.invalid/uni.apk", 225_000_000L)
        )

        val pixelChoice = AppReleaseComparator.selectBestApkAsset(assets, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("redfur-forge-v1.2.0-arm64-v8a.apk", pixelChoice?.name)

        val x86Fallback = AppReleaseComparator.selectBestApkAsset(assets, listOf("x86_64"))
        assertEquals("redfur-forge-v1.2.0-universal.apk", x86Fallback?.name)
    }

    @Test
    fun parsesGitHubReleaseJsonAndExtractsAssetSpecificSha256() {
        val sampleJson = """
            {
              "tag_name": "v1.2.0",
              "name": "Redfur Forge v1.2.0",
              "draft": false,
              "published_at": "2026-11-15T21:00:00Z",
              "html_url": "https://github.com/EchoTiger/redfur-forge/releases/tag/v1.2.0",
              "body": "## Highlights\n- Automatic self-updater\n\n### SHA-256 Checksums\n- `redfur-forge-v1.2.0-arm64-v8a.apk`: `0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef`\n- `redfur-forge-v1.2.0-universal.apk`: `ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff`",
              "assets": [
                {
                  "name": "redfur-forge-v1.2.0-arm64-v8a.apk",
                  "size": 73250000,
                  "browser_download_url": "https://github.com/EchoTiger/redfur-forge/releases/download/v1.2.0/redfur-forge-v1.2.0-arm64-v8a.apk"
                },
                {
                  "name": "redfur-forge-v1.2.0-universal.apk",
                  "size": 235250000,
                  "browser_download_url": "https://github.com/EchoTiger/redfur-forge/releases/download/v1.2.0/redfur-forge-v1.2.0-universal.apk"
                }
              ]
            }
        """.trimIndent()

        val info = AppReleaseComparator.parseLatestReleaseJson(sampleJson, listOf("arm64-v8a"))
        assertNotNull(info)
        assertEquals("v1.2.0", info?.tagName)
        assertEquals("1.2.0", info?.versionName)
        assertEquals("redfur-forge-v1.2.0-arm64-v8a.apk", info?.selectedAsset?.name)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            info?.expectedSha256
        )

        val draftJson = """{"tag_name":"v1.2.1","draft":true,"assets":[]}"""
        assertNull(AppReleaseComparator.parseLatestReleaseJson(draftJson, listOf("arm64-v8a")))
    }
}
