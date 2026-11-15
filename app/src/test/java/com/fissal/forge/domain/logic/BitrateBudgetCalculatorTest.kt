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
}
