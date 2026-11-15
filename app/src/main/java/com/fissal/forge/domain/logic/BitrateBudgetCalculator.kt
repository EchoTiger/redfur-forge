package com.fissal.forge.domain.logic

import kotlin.math.max

data class VideoBudgetPlan(
    val videoBitrateBps: Long,
    val audioBitrateBps: Long,
    val maxrateBps: Long,
    val bufsizeBps: Long,
    val targetMaxDimension: Int?,
    val isTightBudgetWarning: Boolean
)

/**
 * Calculates container-overhead-aware video/audio bitrates and smart resolution downscaling
 * so target-filesize reductions (e.g., 8 MB Discord, 25 MB Email) hit their byte cap cleanly.
 */
object BitrateBudgetCalculator {

    private val AUDIO_TIERS = longArrayOf(128_000L, 96_000L, 64_000L, 48_000L, 32_000L)

    fun calculateVideoBudget(
        targetBytes: Long,
        durationMs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        hasAudio: Boolean,
        isHevc: Boolean
    ): VideoBudgetPlan {
        val safeTargetBytes = targetBytes.coerceAtLeast(32L * 1024L)
        // Reserve 4% for MP4/MKV muxer overhead & VBR variance
        val usableBits = (safeTargetBytes * 8L * 96L) / 100L
        val durationSec = (durationMs / 1000.0).coerceAtLeast(0.5)
        val totalBps = (usableBits / durationSec).toLong().coerceAtLeast(64_000L)

        val audioBitrateBps = if (!hasAudio) {
            0L
        } else {
            val maxAudioShare = (totalBps * 22L) / 100L
            AUDIO_TIERS.firstOrNull { it <= maxAudioShare } ?: 24_000L.coerceAtMost(totalBps / 4L)
        }

        val videoBitrateBps = (totalBps - audioBitrateBps).coerceAtLeast(45_000L)
        val maxrateBps = (videoBitrateBps * 115L) / 100L
        val bufsizeBps = videoBitrateBps * 2L

        val scaleFactor = if (isHevc) 0.72 else 1.0
        val desiredMaxDim = when {
            videoBitrateBps < (220_000L * scaleFactor).toLong() -> 480
            videoBitrateBps < (450_000L * scaleFactor).toLong() -> 640
            videoBitrateBps < (950_000L * scaleFactor).toLong() -> 854
            videoBitrateBps < (2_200_000L * scaleFactor).toLong() -> 1280
            else -> null
        }

        val srcMaxDim = max(sourceWidth, sourceHeight)
        val targetMaxDimension = if (desiredMaxDim != null && (srcMaxDim <= 0 || srcMaxDim > desiredMaxDim)) {
            desiredMaxDim
        } else {
            null
        }

        return VideoBudgetPlan(
            videoBitrateBps = videoBitrateBps,
            audioBitrateBps = audioBitrateBps,
            maxrateBps = maxrateBps,
            bufsizeBps = bufsizeBps,
            targetMaxDimension = targetMaxDimension,
            isTightBudgetWarning = videoBitrateBps < 150_000L
        )
    }

    fun calculateAudioBitrateBps(targetBytes: Long, durationMs: Long): Long {
        val safeTargetBytes = targetBytes.coerceAtLeast(16L * 1024L)
        val usableBits = (safeTargetBytes * 8L * 95L) / 100L
        val durationSec = (durationMs / 1000.0).coerceAtLeast(0.5)
        return (usableBits / durationSec).toLong().coerceIn(24_000L, 320_000L)
    }
}
