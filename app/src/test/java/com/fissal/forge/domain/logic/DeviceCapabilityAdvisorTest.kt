package com.fissal.forge.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityAdvisorTest {

    private val gib = 1024L * 1024L * 1024L

    @Test
    fun evaluatesLowRamDeviceSafely() {
        val snapshot = DeviceHardwareSnapshot(
            totalRamBytes = 4L * gib,
            heapMemoryClassMb = 192,
            largeHeapMemoryClassMb = 256,
            isLowRamDevice = true,
            cpuCores = 4,
            freeCacheStorageBytes = 6L * gib,
            hasHwAv1Encoder = false,
            hasHwHevcEncoder = false,
            hasHwH264Encoder = true
        )

        val report = DeviceCapabilityAdvisor.evaluate(snapshot)
        assertEquals(2, report.recommendedComputeSlots)
        assertEquals(2, report.recommendedMaxNetworkSlots)
        assertEquals(2, report.recommendedFfmpegThreads)
        assertEquals(2560, report.maxSafeRasterDimensionPx)
        assertTrue(report.recommendRawHalfSizeByDefault)

        val codecAdvisory = report.advisoryFor(SettingTopic.VIDEO_CODEC)
        assertNotNull(codecAdvisory)
        assertEquals(AdvisorySeverity.WARNING, codecAdvisory?.severity)
        assertTrue(codecAdvisory?.message?.contains("SVT-AV1") == true)
        assertTrue(codecAdvisory?.message?.contains("H.264") == true)

        val rawAdvisory = report.advisoryFor(SettingTopic.RAW_HALF_SIZE_FAST)
        assertNotNull(rawAdvisory)
        assertEquals(AdvisorySeverity.WARNING, rawAdvisory?.severity)
        assertTrue(rawAdvisory?.message?.contains("50MP+") == true)
        assertTrue(rawAdvisory?.message?.contains("256MB") == true)

        assertEquals(
            AdvisorySeverity.CAUTION,
            report.advisoryFor(SettingTopic.TWO_PASS_ENCODING)?.severity
        )
        assertEquals(
            AdvisorySeverity.CAUTION,
            report.advisoryFor(SettingTopic.CONCURRENCY_SLOTS)?.severity
        )
        assertEquals("4.0 GB RAM • 4 cores • 256MB heap • HW H.264", report.compactHardwareSummary())
    }

    @Test
    fun capsFfmpegThreadsAtThreeOnEightCoreLowRamDevice() {
        val snapshot = DeviceHardwareSnapshot(
            totalRamBytes = 6L * gib,
            heapMemoryClassMb = 256,
            largeHeapMemoryClassMb = 384,
            isLowRamDevice = true,
            cpuCores = 8,
            freeCacheStorageBytes = 4L * gib
        )

        val report = DeviceCapabilityAdvisor.evaluate(snapshot)
        assertEquals(2, report.recommendedComputeSlots)
        assertEquals(2, report.recommendedMaxNetworkSlots)
        assertEquals(3, report.recommendedFfmpegThreads)
        assertEquals(2560, report.maxSafeRasterDimensionPx)
        assertTrue(report.recommendRawHalfSizeByDefault)
    }

    @Test
    fun evaluatesMidRangeEightGbDevice() {
        val snapshot = DeviceHardwareSnapshot(
            totalRamBytes = 8L * gib,
            heapMemoryClassMb = 256,
            largeHeapMemoryClassMb = 512,
            isLowRamDevice = false,
            cpuCores = 8,
            freeCacheStorageBytes = 12L * gib,
            hasHwAv1Encoder = false,
            hasHwHevcEncoder = true,
            hasHwH264Encoder = true
        )

        val report = DeviceCapabilityAdvisor.evaluate(snapshot)
        assertEquals(3, report.recommendedComputeSlots)
        assertEquals(3, report.recommendedMaxNetworkSlots)
        assertEquals(6, report.recommendedFfmpegThreads)
        assertEquals(4096, report.maxSafeRasterDimensionPx)
        assertFalse(report.recommendRawHalfSizeByDefault)
        assertEquals("8.0 GB RAM • 8 cores • 512MB heap • HW HEVC", report.compactHardwareSummary())

        val codecAdvisory = report.advisoryFor(SettingTopic.VIDEO_CODEC)
        assertNotNull(codecAdvisory)
        assertEquals(AdvisorySeverity.CAUTION, codecAdvisory?.severity)
        assertTrue(codecAdvisory?.message?.contains("SVT-AV1") == true)
        assertTrue(codecAdvisory?.message?.contains("HEVC") == true)

        assertEquals(AdvisorySeverity.INFO, report.advisoryFor(SettingTopic.TWO_PASS_ENCODING)?.severity)
        assertEquals(AdvisorySeverity.INFO, report.advisoryFor(SettingTopic.ARIA2C_ACCELERATOR)?.severity)
        assertEquals(AdvisorySeverity.INFO, report.advisoryFor(SettingTopic.CONCURRENCY_SLOTS)?.severity)
        assertNull(report.advisoryFor(SettingTopic.TARGET_FILESIZE))
    }

    @Test
    fun evaluatesFlagshipTwelveGbDeviceWithHwAv1AndHevc() {
        val snapshot = DeviceHardwareSnapshot(
            totalRamBytes = 12L * gib,
            heapMemoryClassMb = 384,
            largeHeapMemoryClassMb = 512,
            isLowRamDevice = false,
            cpuCores = 8,
            freeCacheStorageBytes = 32L * gib,
            hasHwAv1Encoder = true,
            hasHwHevcEncoder = true,
            hasHwH264Encoder = true
        )

        val report = DeviceCapabilityAdvisor.evaluate(snapshot)
        assertEquals(4, report.recommendedComputeSlots)
        assertEquals(4, report.recommendedMaxNetworkSlots)
        assertEquals(6, report.recommendedFfmpegThreads)
        assertEquals(6144, report.maxSafeRasterDimensionPx)
        assertFalse(report.recommendRawHalfSizeByDefault)
        assertEquals("12.0 GB RAM • 8 cores • 512MB heap • HW AV1/HEVC", report.compactHardwareSummary())
        assertEquals(AdvisorySeverity.INFO, report.advisoryFor(SettingTopic.VIDEO_CODEC)?.severity)
        assertEquals(AdvisorySeverity.INFO, report.advisoryFor(SettingTopic.RAW_HALF_SIZE_FAST)?.severity)
    }

    @Test
    fun emitsStorageWarningsBelowOnePointFiveGigabytes() {
        val snapshot = DeviceHardwareSnapshot(
            totalRamBytes = 8L * gib,
            heapMemoryClassMb = 256,
            largeHeapMemoryClassMb = 256,
            isLowRamDevice = false,
            cpuCores = 8,
            freeCacheStorageBytes = 900_000_000L,
            hasHwAv1Encoder = false,
            hasHwHevcEncoder = true,
            hasHwH264Encoder = true
        )

        val report = DeviceCapabilityAdvisor.evaluate(snapshot)
        assertTrue(report.recommendRawHalfSizeByDefault)

        val twoPass = report.advisoryFor(SettingTopic.TWO_PASS_ENCODING)
        assertNotNull(twoPass)
        assertEquals(AdvisorySeverity.WARNING, twoPass?.severity)
        assertTrue(twoPass?.message?.contains("1.5 GB") == true)

        val targetSize = report.advisoryFor(SettingTopic.TARGET_FILESIZE)
        assertNotNull(targetSize)
        assertEquals(AdvisorySeverity.WARNING, targetSize?.severity)
        assertTrue(targetSize?.message?.contains("1.5 GB") == true)
    }
}
