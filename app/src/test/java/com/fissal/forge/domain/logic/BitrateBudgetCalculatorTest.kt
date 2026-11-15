package com.fissal.forge.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateBudgetCalculatorTest {

    @Test
    fun eightMegabyteBudgetStaysUnderTotalBitBudget() {
        val targetBytes = 8L * 1024L * 1024L
        val durationMs = 60_000L
        val plan = BitrateBudgetCalculator.calculateVideoBudget(
            targetBytes = targetBytes,
            durationMs = durationMs,
            sourceWidth = 1920,
            sourceHeight = 1080,
            hasAudio = true,
            isHevc = false
        )
        val projectedTotalBits = (plan.videoBitrateBps + plan.audioBitrateBps) * 60L
        assertTrue(projectedTotalBits <= targetBytes * 8L)
        assertTrue(plan.audioBitrateBps in 32_000L..128_000L)
        assertNotNull(plan.targetMaxDimension) // ~1 Mbps triggers smart downscale from 1080p
        assertFalse(plan.isTightBudgetWarning)
    }

    @Test
    fun mutedAudioAllocatesZeroAudioBits() {
        val plan = BitrateBudgetCalculator.calculateVideoBudget(
            targetBytes = 10L * 1024L * 1024L,
            durationMs = 30_000L,
            sourceWidth = 1920,
            sourceHeight = 1080,
            hasAudio = false,
            isHevc = true
        )
        assertEquals(0L, plan.audioBitrateBps)
        assertTrue(plan.videoBitrateBps > 2_000_000L)
    }

    @Test
    fun ultraTightBudgetClampsSafelyAndFlagsWarning() {
        val plan = BitrateBudgetCalculator.calculateVideoBudget(
            targetBytes = 1024L * 1024L,
            durationMs = 180_000L,
            sourceWidth = 3840,
            sourceHeight = 2160,
            hasAudio = true,
            isHevc = false
        )
        assertTrue(plan.videoBitrateBps >= 45_000L)
        assertEquals(480, plan.targetMaxDimension)
        assertTrue(plan.isTightBudgetWarning)
    }

    @Test
    fun thirtyMinuteVideoUnder25MbUsesAv1AndOpusCleanly() {
        val targetBytes = 25L * 1024L * 1024L
        val plan = BitrateBudgetCalculator.calculateVideoBudget(
            targetBytes = targetBytes,
            durationMs = 1_800_000L, // 30 minutes
            sourceWidth = 1920,
            sourceHeight = 1080,
            hasAudio = true,
            isHevc = false,
            videoCodec = com.fissal.forge.domain.model.VideoCodecChoice.AV1,
            preferOpusAudio = true
        )
        assertTrue(plan.estimatedOutputBytes <= targetBytes)
        assertTrue(plan.useOpusAudio)
        assertTrue(plan.audioBitrateBps in 24_000L..48_000L)
        assertTrue(plan.videoBitrateBps >= 60_000L)
        assertEquals(480, plan.recommendedDownloadHeightCap)
        assertTrue(plan.strategySummary.contains("AV1"))
        assertTrue(plan.strategySummary.contains("Opus"))
    }

    @Test
    fun generousBudgetPreservesNativeResolutionAndFps() {
        val plan = BitrateBudgetCalculator.calculateVideoBudget(
            targetBytes = 50L * 1024L * 1024L,
            durationMs = 60_000L,
            sourceWidth = 1920,
            sourceHeight = 1080,
            hasAudio = true,
            isHevc = false,
            videoCodec = com.fissal.forge.domain.model.VideoCodecChoice.AV1,
            preferOpusAudio = true
        )
        assertEquals(null, plan.targetMaxDimension)
        assertEquals(null, plan.targetFps)
        assertEquals(false, plan.isTightBudgetWarning)
    }
}
