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

    @Test
    fun recognizesV2ReleaseAsNewerForPriorWavesAndExtractsChecksum() {
        assertTrue(AppReleaseComparator.isRemoteNewer("1.2.0", "v2.0.0"))
        assertTrue(AppReleaseComparator.isRemoteNewer("1.3.0", "v2.0.0"))
        assertTrue(AppReleaseComparator.isRemoteNewer("1.4.0", "v2.0.0"))
        assertTrue(AppReleaseComparator.isRemoteNewer("1.5.0", "v2.0.0"))
        assertFalse(AppReleaseComparator.isRemoteNewer("2.0.0", "v2.0.0"))

        val v2ShaArm64 = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
        val v2ShaUni = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        val v2Json = """
            {
              "tag_name": "v2.0.0",
              "name": "Redfur Forge v2.0.0",
              "draft": false,
              "html_url": "https://github.com/EchoTiger/redfur-forge/releases/tag/v2.0.0",
              "body": "## Redfur Forge v2.0.0\n\n### SHA-256 Checksums\n- `redfur-forge-v2.0.0-arm64-v8a.apk`: `$v2ShaArm64`\n- `redfur-forge-v2.0.0-universal.apk`: `$v2ShaUni`",
              "assets": [
                {
                  "name": "redfur-forge-v2.0.0-arm64-v8a.apk",
                  "size": 74000000,
                  "browser_download_url": "https://github.com/EchoTiger/redfur-forge/releases/download/v2.0.0/redfur-forge-v2.0.0-arm64-v8a.apk"
                },
                {
                  "name": "redfur-forge-v2.0.0-universal.apk",
                  "size": 236000000,
                  "browser_download_url": "https://github.com/EchoTiger/redfur-forge/releases/download/v2.0.0/redfur-forge-v2.0.0-universal.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppReleaseComparator.parseLatestReleaseJson(v2Json, listOf("arm64-v8a", "armeabi-v7a"))
        assertNotNull(parsed)
        assertEquals("v2.0.0", parsed?.tagName)
        assertEquals("2.0.0", parsed?.versionName)
        assertEquals("redfur-forge-v2.0.0-arm64-v8a.apk", parsed?.selectedAsset?.name)
        assertEquals(v2ShaArm64, parsed?.expectedSha256)
    }
}
