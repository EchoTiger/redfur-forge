package com.fissal.forge.domain.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.Locale

data class ReleaseAssetInfo(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val publishedAt: String?,
    val selectedAsset: ReleaseAssetInfo?,
    val expectedSha256: String?
)

/**
 * Pure Kotlin semantic version comparator, ABI APK selector, and GitHub Release JSON parser.
 */
object AppReleaseComparator {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val SHA256_REGEX = Regex("""\b([0-9a-fA-F]{64})\b""")

    fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").trim()
    }

    fun isRemoteNewer(currentVersion: String, remoteTagOrVersion: String): Boolean {
        val curNormalized = normalizeVersion(currentVersion)
        val remNormalized = normalizeVersion(remoteTagOrVersion)
        if (remNormalized.isBlank()) return false

        val (curCore, curPre) = splitCoreAndPreRelease(curNormalized)
        val (remCore, remPre) = splitCoreAndPreRelease(remNormalized)

        val curNums = parseNumericCore(curCore)
        val remNums = parseNumericCore(remCore)
        val maxLen = maxOf(curNums.size, remNums.size)

        for (i in 0 until maxLen) {
            val c = curNums.getOrElse(i) { 0 }
            val r = remNums.getOrElse(i) { 0 }
            if (r != c) return r > c
        }

        // Numeric cores are equal: stable release > pre-release (e.g., 1.2.0 > 1.2.0-rc1)
        if (curPre != null && remPre == null) return true
        if (curPre == null && remPre != null) return false
        if (curPre != null && remPre != null) {
            return remPre.compareTo(curPre, ignoreCase = true) > 0
        }
        return false
    }

    fun selectBestApkAsset(
        assets: List<ReleaseAssetInfo>,
        supportedAbis: List<String>
    ): ReleaseAssetInfo? {
        val apkAssets = assets.filter {
            it.name.lowercase(Locale.US).endsWith(".apk") && it.downloadUrl.isNotBlank()
        }
        if (apkAssets.isEmpty()) return null

        for (abi in supportedAbis) {
            val abiLower = abi.trim().lowercase(Locale.US)
            if (abiLower.isBlank()) continue
            val match = apkAssets.firstOrNull { asset ->
                asset.name.lowercase(Locale.US).contains(abiLower)
            }
            if (match != null) return match
        }

        return apkAssets.firstOrNull { it.name.lowercase(Locale.US).contains("universal") }
            ?: apkAssets.firstOrNull()
    }

    fun extractExpectedSha256(releaseBody: String, assetName: String): String? {
        if (releaseBody.isBlank() || assetName.isBlank()) return null
        val targetLower = assetName.lowercase(Locale.US)
        for (line in releaseBody.lineSequence()) {
            if (line.lowercase(Locale.US).contains(targetLower)) {
                val match = SHA256_REGEX.find(line)
                if (match != null) {
                    return match.groupValues[1].lowercase(Locale.US)
                }
            }
        }
        return null
    }

    fun parseLatestReleaseJson(
        rawJson: String,
        supportedAbis: List<String>
    ): GitHubReleaseInfo? {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) return null

        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        if (root.boolVal("draft") == true) return null

        val tagName = root.str("tag_name") ?: return null
        val versionName = normalizeVersion(tagName)
        val title = root.str("name") ?: tagName
        val body = root.str("body").orEmpty()
        val htmlUrl = root.str("html_url").orEmpty()
        val publishedAt = root.str("published_at")

        val assetArr = root["assets"] as? JsonArray
        val parsedAssets = buildList {
            assetArr?.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val name = obj.str("name") ?: return@forEach
                val dl = obj.str("browser_download_url") ?: return@forEach
                val size = obj.longVal("size") ?: 0L
                add(ReleaseAssetInfo(name = name, downloadUrl = dl, sizeBytes = size))
            }
        }

        val selected = selectBestApkAsset(parsedAssets, supportedAbis)
        val expectedSha = selected?.let { extractExpectedSha256(body, it.name) }

        return GitHubReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            title = title,
            releaseNotes = body,
            htmlUrl = htmlUrl,
            publishedAt = publishedAt,
            selectedAsset = selected,
            expectedSha256 = expectedSha
        )
    }

    private fun splitCoreAndPreRelease(version: String): Pair<String, String?> {
        val withoutBuild = version.substringBefore('+')
        val dashIdx = withoutBuild.indexOf('-')
        return if (dashIdx >= 0) {
            withoutBuild.substring(0, dashIdx) to withoutBuild.substring(dashIdx + 1).ifBlank { null }
        } else {
            withoutBuild to null
        }
    }

    private fun parseNumericCore(core: String): List<Int> {
        return core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun JsonObject.str(key: String): String? {
        val el: JsonElement = this[key] ?: return null
        return (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JsonObject.longVal(key: String): Long? {
        val el: JsonElement = this[key] ?: return null
        return (el as? JsonPrimitive)?.longOrNull
    }

    private fun JsonObject.boolVal(key: String): Boolean? {
        val el: JsonElement = this[key] ?: return null
        return (el as? JsonPrimitive)?.booleanOrNull
    }
}
