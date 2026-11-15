package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.VideoCodecChoice
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

data class VideoBudgetPlan(
    val videoBitrateBps: Long,
    val audioBitrateBps: Long,
    val maxrateBps: Long,
    val bufsizeBps: Long,
    val targetMaxDimension: Int?,
    val targetFps: Int? = null,
    val enableLightDenoise: Boolean = false,
    val recommendedDownloadHeightCap: Int? = null,
    val estimatedOutputBytes: Long = 0L,
    val useOpusAudio: Boolean = false,
    val isTightBudgetWarning: Boolean = false,
    val strategySummary: String = ""
)

/**
 * Packet-aware & perceptual bitrate budget calculator.
 * Balances AV1 / HEVC / VP9 / H.264 coding efficiency against Opus psychoacoustic audio allocation,
 * adaptive resolution scaling, framerate stepping, and gentle temporal denoise so extreme targets
 * (e.g., a 30-minute video under 25 MB) stay crisp without exceeding their byte cap.
 */
object BitrateBudgetCalculator {

    private val AAC_AUDIO_TIERS = longArrayOf(128_000L, 96_000L, 64_000L, 48_000L, 32_000L)
    private val OPUS_AUDIO_TIERS = longArrayOf(96_000L, 64_000L, 48_000L, 32_000L, 24_000L)

    fun calculateVideoBudget(
        targetBytes: Long,
        durationMs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        hasAudio: Boolean,
        isHevc: Boolean,
        videoCodec: VideoCodecChoice = if (isHevc) VideoCodecChoice.HEVC else VideoCodecChoice.H264,
        preferOpusAudio: Boolean = false,
        complexityFactor: Double = 1.0
    ): VideoBudgetPlan {
        val safeTargetBytes = targetBytes.coerceAtLeast(32L * 1024L)
        val durationSec = (durationMs / 1000.0).coerceAtLeast(0.5)

        // Exact muxer reserve: moov/stbl sample tables (~450 B/sec) + container header + 2.5% VBR guard
        val containerReserveBytes = (32_768L + (durationSec * 450.0).roundToLong())
            .coerceAtMost(safeTargetBytes / 8L)
        val rawPayloadBudget = (safeTargetBytes - containerReserveBytes).coerceAtLeast(16L * 1024L)
        val gatedBytes = ((rawPayloadBudget * 975L) / 1000L)
            .coerceAtMost((safeTargetBytes * 97L) / 100L)
        val usableBits = gatedBytes * 8L
        val totalBps = (usableBits.toDouble() / durationSec).roundToLong().coerceAtLeast(48_000L)

        val audioBitrateBps = if (!hasAudio) {
            0L
        } else if (preferOpusAudio) {
            // Opus sounds transparent at 32-48 kbps; allow slightly higher audio share on tight budgets
            val maxAudioShare = ((totalBps * 30L) / 100L).coerceAtLeast(24_000L)
            OPUS_AUDIO_TIERS.firstOrNull { it <= maxAudioShare }
                ?: 24_000L.coerceAtMost(totalBps / 3L).coerceAtLeast(16_000L)
        } else {
            val maxAudioShare = (totalBps * 22L) / 100L
            AAC_AUDIO_TIERS.firstOrNull { it <= maxAudioShare }
                ?: 24_000L.coerceAtMost(totalBps / 4L).coerceAtLeast(16_000L)
        }

        val videoBitrateBps = (totalBps - audioBitrateBps).coerceAtLeast(45_000L)
        val maxrateBps = (videoBitrateBps * 115L) / 100L
        val bufsizeBps = videoBitrateBps * 2L

        // Perceptual codec efficiency relative to H.264 1-pass baseline
        val codecEfficiency = when (videoCodec) {
            VideoCodecChoice.AV1 -> 0.50
            VideoCodecChoice.HEVC, VideoCodecChoice.VP9 -> 0.68
            else -> if (isHevc) 0.68 else 1.0
        }
        val clampedComplexity = complexityFactor.coerceIn(0.65, 1.35)
        val equivalentH264Bps = (videoBitrateBps.toDouble() / (codecEfficiency * clampedComplexity)).roundToLong()

        val desiredMaxDim = when {
            equivalentH264Bps < 135_000L -> 480
            equivalentH264Bps < 340_000L -> 640
            equivalentH264Bps < 820_000L -> 854
            equivalentH264Bps < 2_200_000L -> 1280
            else -> null
        }

        val srcMaxDim = max(sourceWidth, sourceHeight)
        val targetMaxDimension = if (desiredMaxDim != null && (srcMaxDim <= 0 || srcMaxDim > desiredMaxDim)) {
            desiredMaxDim
        } else {
            null
        }

        val targetFps = when {
            equivalentH264Bps < 185_000L -> 24
            equivalentH264Bps < 480_000L -> 30
            else -> null
        }

        val effDimW = (targetMaxDimension ?: srcMaxDim.takeIf { it > 0 } ?: 1280)
        val effDimH = ((effDimW * 9.0) / 16.0).roundToLong().coerceAtLeast(180L)
        val assumedFps = (targetFps ?: 30).toDouble()
        val bppf = videoBitrateBps.toDouble() / (effDimW.toDouble() * effDimH.toDouble() * assumedFps)
        val enableLightDenoise = bppf < 0.042

        // Recommend downloading a clean mezzanine height (never multi-GB 4K when target is tiny)
        val recommendedDownloadHeightCap = when {
            desiredMaxDim != null && desiredMaxDim <= 640 -> 480
            desiredMaxDim != null && desiredMaxDim <= 1280 -> 720
            else -> null
        }

        val estimatedOutputBytes = (((videoBitrateBps + audioBitrateBps).toDouble() * durationSec) / 8.0)
            .roundToLong() + containerReserveBytes

        val codecLabel = when (videoCodec) {
            VideoCodecChoice.AV1 -> "AV1"
            VideoCodecChoice.HEVC -> "HEVC"
            VideoCodecChoice.VP9 -> "VP9"
            else -> if (isHevc) "HEVC" else "H.264"
        }
        val audioLabel = if (!hasAudio) "Muted" else if (preferOpusAudio) "${audioBitrateBps / 1000}k Opus" else "${audioBitrateBps / 1000}k AAC"
        val resTag = when (targetMaxDimension) {
            1280 -> "720p"
            854 -> "480p"
            640 -> "360p"
            480 -> "270p"
            else -> "Orig"
        }
        val fpsTag = targetFps?.let { "${it}fps" } ?: ""
        val estMb = estimatedOutputBytes / (1024.0 * 1024.0)
        val summary = String.format(
            Locale.US,
            "%s • %s%s • %dk video + %s -> ~%.1f MB",
            codecLabel,
            resTag,
            fpsTag,
            videoBitrateBps / 1000,
            audioLabel,
            estMb
        )

        return VideoBudgetPlan(
            videoBitrateBps = videoBitrateBps,
            audioBitrateBps = audioBitrateBps,
            maxrateBps = maxrateBps,
            bufsizeBps = bufsizeBps,
            targetMaxDimension = targetMaxDimension,
            targetFps = targetFps,
            enableLightDenoise = enableLightDenoise,
            recommendedDownloadHeightCap = recommendedDownloadHeightCap,
            estimatedOutputBytes = estimatedOutputBytes.coerceAtMost(safeTargetBytes),
            useOpusAudio = preferOpusAudio && hasAudio,
            isTightBudgetWarning = equivalentH264Bps < 115_000L,
            strategySummary = summary
        )
    }

    fun calculateAudioBitrateBps(targetBytes: Long, durationMs: Long): Long {
        val safeTargetBytes = targetBytes.coerceAtLeast(16L * 1024L)
        val usableBits = (safeTargetBytes * 8L * 95L) / 100L
        val durationSec = (durationMs / 1000.0).coerceAtLeast(0.5)
        return (usableBits / durationSec).toLong().coerceIn(24_000L, 320_000L)
    }
}
