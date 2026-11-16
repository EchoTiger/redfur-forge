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

    @Test
    fun calculateSafeInSampleSizeBounds50Mp108MpAnd8KWithinTextureAndPixelLimits() {
        // 50MP RAW/raster at 1920 target -> 8688 / 4 = 2172px, 1448px (~3.1M pixels)
        val sample50Mp = DeviceCapabilityAdvisor.calculateSafeInSampleSize(
            rawWidth = 8688,
            rawHeight = 5792,
            targetMaxDimPx = 1920
        )
        assertEquals(4, sample50Mp)
        assertTrue(8688 / sample50Mp <= DeviceCapabilityAdvisor.GL_SAFE_MAX_TEXTURE_PX)
        assertTrue((8688 / sample50Mp).toLong() * (5792 / sample50Mp).toLong() <= DeviceCapabilityAdvisor.DEFAULT_MAX_INTERMEDIATE_PIXELS)

        // 108MP raster on low-RAM device (maxSafeTexturePx=2560, LOW_RAM pixels<=8.38M)
        val sample108MpLowRam = DeviceCapabilityAdvisor.calculateSafeInSampleSize(
            rawWidth = 12000,
            rawHeight = 9000,
            targetMaxDimPx = 3840,
            maxSafeTexturePx = 2560,
            maxIntermediatePixels = DeviceCapabilityAdvisor.LOW_RAM_MAX_INTERMEDIATE_PIXELS
        )
        assertEquals(8, sample108MpLowRam)
        assertTrue(12000 / sample108MpLowRam <= 2560)
        assertTrue(
            (12000 / sample108MpLowRam).toLong() * (9000 / sample108MpLowRam).toLong() <=
                DeviceCapabilityAdvisor.LOW_RAM_MAX_INTERMEDIATE_PIXELS
        )

        // 8K raster at 3840 target -> inSampleSize 2 keeps 3840x2560 <= 4096 and <= 16.77M px
        val sample8K = DeviceCapabilityAdvisor.calculateSafeInSampleSize(
            rawWidth = 7680,
            rawHeight = 5120,
            targetMaxDimPx = 3840
        )
        assertEquals(2, sample8K)
        assertTrue(7680 / sample8K <= DeviceCapabilityAdvisor.GL_SAFE_MAX_TEXTURE_PX)
        assertTrue((7680 / sample8K).toLong() * (5120 / sample8K).toLong() <= DeviceCapabilityAdvisor.DEFAULT_MAX_INTERMEDIATE_PIXELS)

        // Zero or negative bounds safely return 1
        assertEquals(1, DeviceCapabilityAdvisor.calculateSafeInSampleSize(0, 0, 1920))
    }

    @Test
    fun derivesComparatorBudgetPreviewDimThreadsAndEffectiveSlotsFromReport() {
        val lowRamReport = DeviceCapabilityAdvisor.evaluate(
            DeviceHardwareSnapshot(
                totalRamBytes = 4L * gib,
                heapMemoryClassMb = 128,
                largeHeapMemoryClassMb = 256,
                isLowRamDevice = true,
                cpuCores = 4,
                freeCacheStorageBytes = 6L * gib
            )
        )
        assertEquals(24 * 1024, DeviceCapabilityAdvisor.computeComparatorCacheBudgetKb(lowRamReport))
        assertEquals(1440, DeviceCapabilityAdvisor.computeSamplePreviewMaxDimPx(lowRamReport, 1920))
        assertEquals(1, DeviceCapabilityAdvisor.computeSampleFfmpegThreads(lowRamReport))
        assertEquals(2, DeviceCapabilityAdvisor.effectiveNetworkSlots(lowRamReport, 5))
        assertEquals(2, DeviceCapabilityAdvisor.effectiveComputeSlots(lowRamReport, 4))

        val flagshipReport = DeviceCapabilityAdvisor.evaluate(
            DeviceHardwareSnapshot(
                totalRamBytes = 12L * gib,
                heapMemoryClassMb = 384,
                largeHeapMemoryClassMb = 512,
                isLowRamDevice = false,
                cpuCores = 8,
                freeCacheStorageBytes = 32L * gib,
                hasHwAv1Encoder = true,
                hasHwHevcEncoder = true
            )
        )
        assertEquals(48 * 1024, DeviceCapabilityAdvisor.computeComparatorCacheBudgetKb(flagshipReport))
        assertEquals(1920, DeviceCapabilityAdvisor.computeSamplePreviewMaxDimPx(flagshipReport, 1920))
        assertEquals(4096, DeviceCapabilityAdvisor.computeSamplePreviewMaxDimPx(flagshipReport, 6000))
        assertEquals(3, DeviceCapabilityAdvisor.computeSampleFfmpegThreads(flagshipReport))
        assertEquals(5, DeviceCapabilityAdvisor.effectiveNetworkSlots(flagshipReport, 5))
        assertEquals(4, DeviceCapabilityAdvisor.effectiveComputeSlots(flagshipReport, 0))
        assertEquals(3, DeviceCapabilityAdvisor.effectiveComputeSlots(flagshipReport, 3))
    }

    @Test
    fun evaluatesStoragePreflightForChapterChopsAndSampleLab() {
        val mib = 1024L * 1024L
        val lowDiskChapters = DeviceCapabilityAdvisor.evaluateStoragePreflight(
            availableBytes = 300L * mib,
            estimatedInputBytes = 100L * mib,
            chapterOrBatchCount = 12,
            hasTransformStage = true,
            twoPass = true
        )
        assertFalse(lowDiskChapters.sufficient)
        assertNotNull(lowDiskChapters.userFacingMessage)
        assertTrue(lowDiskChapters.userFacingMessage!!.contains("Insufficient scratch storage"))

        val ampleChapters = DeviceCapabilityAdvisor.evaluateStoragePreflight(
            availableBytes = 2048L * mib,
            estimatedInputBytes = 100L * mib,
            chapterOrBatchCount = 12,
            hasTransformStage = true,
            twoPass = true
        )
        assertTrue(ampleChapters.sufficient)
        assertNull(ampleChapters.userFacingMessage)

        val lowDiskSample = DeviceCapabilityAdvisor.evaluateStoragePreflight(
            availableBytes = 80L * mib,
            estimatedInputBytes = 40L * mib,
            isSampleLab = true
        )
        assertFalse(lowDiskSample.sufficient)
        assertNotNull(lowDiskSample.userFacingMessage)

        val zeroFreeResult = DeviceCapabilityAdvisor.evaluateStoragePreflight(
            availableBytes = 0L,
            estimatedInputBytes = 10L * mib,
            hasTransformStage = true
        )
        assertFalse(zeroFreeResult.sufficient)
        assertNotNull(zeroFreeResult.userFacingMessage)

        val postDownloadChaptersLow = DeviceCapabilityAdvisor.evaluateStoragePreflight(
            availableBytes = 400L * mib,
            estimatedInputBytes = 300L * mib,
            chapterOrBatchCount = 10,
            hasTransformStage = true,
            twoPass = true,
            alreadyStagedOnDisk = true
        )
        assertFalse(postDownloadChaptersLow.sufficient)

        val postDownloadChaptersOk = DeviceCapabilityAdvisor.evaluateStoragePreflight(
            availableBytes = 900L * mib,
            estimatedInputBytes = 300L * mib,
            chapterOrBatchCount = 10,
            hasTransformStage = true,
            twoPass = true,
            alreadyStagedOnDisk = true
        )
        assertTrue(postDownloadChaptersOk.sufficient)

        val ampleSample = DeviceCapabilityAdvisor.evaluateStoragePreflight(
            availableBytes = 512L * mib,
            estimatedInputBytes = 40L * mib,
            isSampleLab = true
        )
        assertTrue(ampleSample.sufficient)
    }
}
