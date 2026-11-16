package com.fissal.forge.domain.logic

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.StatFs
import java.util.Locale
import kotlin.math.max

/**
 * Severity level for hardware-aware setting guidance.
 */
enum class AdvisorySeverity {
    INFO,
    CAUTION,
    WARNING
}

/**
 * Pipeline and settings controls for which [DeviceCapabilityAdvisor] can surface guidance.
 */
enum class SettingTopic {
    CRF_SLIDER,
    TARGET_FILESIZE,
    VIDEO_CODEC,
    TWO_PASS_ENCODING,
    FAST_KEYFRAME_TRIM,
    ARIA2C_ACCELERATOR,
    RAW_HALF_SIZE_FAST,
    STRIP_METADATA,
    SPONSOR_BLOCK,
    DEAD_AIR_SKIP,
    CONCURRENCY_SLOTS
}

/**
 * Pure JVM snapshot of device memory, CPU, storage, and hardware video encoder capabilities.
 */
data class DeviceHardwareSnapshot(
    val totalRamBytes: Long,
    val heapMemoryClassMb: Int,
    val largeHeapMemoryClassMb: Int,
    val isLowRamDevice: Boolean,
    val cpuCores: Int,
    val freeCacheStorageBytes: Long,
    val hasHwAv1Encoder: Boolean = false,
    val hasHwHevcEncoder: Boolean = false,
    val hasHwH264Encoder: Boolean = true
)

/**
 * Contextual guidance card/badge tied to a specific [SettingTopic].
 */
data class DeviceAdvisory(
    val topic: SettingTopic,
    val severity: AdvisorySeverity,
    val shortBadge: String,
    val message: String
)

/**
 * Evaluated runtime budgets and per-topic advisories derived from [DeviceHardwareSnapshot].
 */
data class DeviceCapabilityReport(
    val snapshot: DeviceHardwareSnapshot,
    val recommendedComputeSlots: Int,
    val recommendedMaxNetworkSlots: Int,
    val recommendedFfmpegThreads: Int,
    val maxSafeRasterDimensionPx: Int,
    val recommendRawHalfSizeByDefault: Boolean,
    val advisories: List<DeviceAdvisory>
) {
    /**
     * Returns the first [DeviceAdvisory] matching [topic], or `null` if none applies.
     */
    fun advisoryFor(topic: SettingTopic): DeviceAdvisory? =
        advisories.firstOrNull { it.topic == topic }

    /**
     * Formats a compact one-line hardware summary suitable for settings headers.
     */
    fun compactHardwareSummary(): String {
        val ramGb = snapshot.totalRamBytes.coerceAtLeast(0L) / BYTES_PER_GIB
        val ramLabel = String.format(Locale.US, "%.1f GB RAM", ramGb)
        val coreLabel = if (snapshot.cpuCores == 1) "1 core" else "${snapshot.cpuCores} cores"
        val heapMb = snapshot.largeHeapMemoryClassMb.coerceAtLeast(snapshot.heapMemoryClassMb)
        val heapLabel = "${heapMb}MB heap"
        val hwCodecs = buildList {
            if (snapshot.hasHwAv1Encoder) add("AV1")
            if (snapshot.hasHwHevcEncoder) add("HEVC")
        }
        val codecLabel = when {
            hwCodecs.isNotEmpty() -> "HW ${hwCodecs.joinToString("/")}"
            snapshot.hasHwH264Encoder -> "HW H.264"
            else -> "SW Encode"
        }
        return "$ramLabel • $coreLabel • $heapLabel • $codecLabel"
    }

    private companion object {
        const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0
    }
}

/**
 * Result of a free scratch-storage check prior to staging, decoding, or chapter splitting.
 */
data class StoragePreflightResult(
    val sufficient: Boolean,
    val requiredBytes: Long,
    val availableBytes: Long,
    val userFacingMessage: String? = null
)

/**
 * Pure domain evaluator for safe concurrency, thread counts, raster ceilings, and setting
 * advisories, plus an Android [profileDevice] entry point for runtime inspection.
 */
object DeviceCapabilityAdvisor {

    const val GL_SAFE_MAX_TEXTURE_PX = 4096
    const val DEFAULT_MAX_INTERMEDIATE_PIXELS = 16_777_216L // ~64 MB ARGB_8888
    const val LOW_RAM_MAX_INTERMEDIATE_PIXELS = 8_388_608L // ~32 MB ARGB_8888

    private const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0
    private const val BYTES_PER_MIB = 1024L * 1024L
    private const val LOW_CACHE_STORAGE_BYTES = 1_500_000_000L

    /**
     * Computes a power-of-2 `BitmapFactory.Options.inSampleSize` that bounds decoded dimensions
     * cleanly below both [maxSafeTexturePx] (`GL_MAX_TEXTURE_SIZE`) and [maxIntermediatePixels]
     * so 50MP–108MP RAWs and 8K frames never exhaust native heap or trigger `RecordingCanvas` OOMs.
     */
    fun calculateSafeInSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        targetMaxDimPx: Int,
        maxSafeTexturePx: Int = GL_SAFE_MAX_TEXTURE_PX,
        maxIntermediatePixels: Long = DEFAULT_MAX_INTERMEDIATE_PIXELS
    ): Int {
        if (rawWidth <= 0 || rawHeight <= 0) return 1
        val targetDoubleDim = targetMaxDimPx.coerceAtLeast(1).toLong() * 2L
        val textureCap = maxSafeTexturePx.coerceAtLeast(64).toLong()
        val pixelCap = maxIntermediatePixels.coerceAtLeast(4096L)

        var inSampleSize = 1
        while (inSampleSize < 1024) {
            val sampledW = (rawWidth / inSampleSize).toLong().coerceAtLeast(1L)
            val sampledH = (rawHeight / inSampleSize).toLong().coerceAtLeast(1L)
            val maxSide = max(sampledW, sampledH)
            val pixelCount = sampledW * sampledH
            val exceedsTarget = maxSide > targetDoubleDim
            val exceedsTexture = maxSide > textureCap
            val exceedsPixels = pixelCount > pixelCap
            if (!exceedsTarget && !exceedsTexture && !exceedsPixels) {
                break
            }
            if (sampledW <= 1L && sampledH <= 1L) {
                break
            }
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * Derives a safe Comparator `ImageBitmap` LruCache budget in KB from the device's heap class.
     */
    fun computeComparatorCacheBudgetKb(report: DeviceCapabilityReport): Int {
        val heapMb = report.snapshot.largeHeapMemoryClassMb
            .coerceAtLeast(report.snapshot.heapMemoryClassMb)
            .coerceAtLeast(128)
        return if (report.snapshot.isLowRamDevice) {
            ((heapMb * 1024) / 10).coerceIn(16 * 1024, 24 * 1024)
        } else {
            ((heapMb * 1024) / 8).coerceIn(24 * 1024, 48 * 1024)
        }
    }

    /**
     * Bounds Sample Lab preview dimensions safely inside the device's raster and GL texture ceiling.
     */
    fun computeSamplePreviewMaxDimPx(
        report: DeviceCapabilityReport,
        requestedMaxDimPx: Int = 1920
    ): Int {
        val deviceCeiling = minOf(
            report.maxSafeRasterDimensionPx,
            GL_SAFE_MAX_TEXTURE_PX,
            if (report.snapshot.isLowRamDevice) 1440 else GL_SAFE_MAX_TEXTURE_PX
        ).coerceAtLeast(16)
        return requestedMaxDimPx.coerceAtLeast(16).coerceAtMost(deviceCeiling)
    }

    /**
     * Computes conservative FFmpeg thread count for micro-sample extraction and Sample Lab renders.
     */
    fun computeSampleFfmpegThreads(report: DeviceCapabilityReport): Int {
        val ceiling = if (report.snapshot.isLowRamDevice) 2 else 4
        return (report.recommendedFfmpegThreads / 2).coerceIn(1, ceiling)
    }

    /**
     * Clamps user-configured network download concurrency to hardware limits on low-RAM devices.
     */
    fun effectiveNetworkSlots(report: DeviceCapabilityReport, requestedSlots: Int): Int {
        val clamped = requestedSlots.coerceIn(1, 5)
        return if (report.snapshot.isLowRamDevice) {
            clamped.coerceAtMost(report.recommendedMaxNetworkSlots.coerceAtLeast(1))
        } else {
            clamped
        }
    }

    /**
     * Resolves active compute concurrency slots from user override + device capability ceiling.
     */
    fun effectiveComputeSlots(report: DeviceCapabilityReport, computeSlotsOverride: Int): Int {
        val base = if (computeSlotsOverride > 0) {
            computeSlotsOverride.coerceIn(1, 4)
        } else {
            report.recommendedComputeSlots
        }
        return if (report.snapshot.isLowRamDevice) {
            base.coerceAtMost(report.recommendedComputeSlots.coerceAtLeast(1))
        } else {
            base
        }
    }

    /**
     * Evaluates whether [availableBytes] of scratch storage comfortably covers staging, intermediate
     * 2-pass/chapter files, and output muxing before launching heavy work.
     */
    fun evaluateStoragePreflight(
        availableBytes: Long,
        estimatedInputBytes: Long = 0L,
        chapterOrBatchCount: Int = 1,
        hasTransformStage: Boolean = true,
        twoPass: Boolean = false,
        isSampleLab: Boolean = false,
        alreadyStagedOnDisk: Boolean = false
    ): StoragePreflightResult {
        val safeAvail = availableBytes.coerceAtLeast(0L)
        val safeInput = estimatedInputBytes.coerceAtLeast(0L)
        val requiredBytes = when {
            isSampleLab -> maxOf(128L * BYTES_PER_MIB, safeInput + 64L * BYTES_PER_MIB)
            alreadyStagedOnDisk && chapterOrBatchCount >= 2 -> {
                256L * BYTES_PER_MIB +
                    (safeInput * 1.4).toLong() +
                    (chapterOrBatchCount.coerceAtMost(64) * 4L * BYTES_PER_MIB)
            }
            alreadyStagedOnDisk -> {
                val mult = if (twoPass && hasTransformStage) 1.4 else 1.1
                192L * BYTES_PER_MIB + (safeInput * mult).toLong()
            }
            chapterOrBatchCount >= 2 -> {
                384L * BYTES_PER_MIB +
                    (safeInput * 2.4).toLong() +
                    (chapterOrBatchCount.coerceAtMost(64) * 4L * BYTES_PER_MIB)
            }
            else -> {
                val mult = when {
                    twoPass && hasTransformStage -> 2.2
                    hasTransformStage -> 1.8
                    else -> 1.2
                }
                256L * BYTES_PER_MIB + (safeInput * mult).toLong()
            }
        }

        val sufficient = safeAvail >= requiredBytes
        val msg = if (sufficient) {
            null
        } else {
            val reqMb = (requiredBytes / BYTES_PER_MIB).coerceAtLeast(1L)
            val availMb = safeAvail / BYTES_PER_MIB
            "Insufficient scratch storage: need ~${reqMb} MB free cache space, only ${availMb} MB available. Clear staging cache in Settings and retry."
        }
        return StoragePreflightResult(
            sufficient = sufficient,
            requiredBytes = requiredBytes,
            availableBytes = safeAvail,
            userFacingMessage = msg
        )
    }

    /**
     * Evaluates safe execution limits and user-facing advisories from a pure [DeviceHardwareSnapshot].
     */
    fun evaluate(snapshot: DeviceHardwareSnapshot): DeviceCapabilityReport {
        val totalRamGb = snapshot.totalRamBytes.coerceAtLeast(0L) / BYTES_PER_GIB

        val recommendedComputeSlots = when {
            snapshot.isLowRamDevice || totalRamGb <= 6.2 -> 2
            totalRamGb <= 9.2 -> 3
            else -> 4
        }

        val recommendedMaxNetworkSlots = when {
            snapshot.isLowRamDevice || totalRamGb < 4.5 -> 2
            totalRamGb >= 10.0 -> 4
            else -> 3
        }

        val baseThreads = (snapshot.cpuCores - 2).coerceIn(2, 6)
        val recommendedFfmpegThreads = if (snapshot.isLowRamDevice) {
            baseThreads.coerceAtMost(3)
        } else {
            baseThreads
        }

        val maxSafeRasterDimensionPx = when {
            snapshot.isLowRamDevice || totalRamGb < 5.0 -> 2560
            totalRamGb >= 11.5 -> 6144
            else -> 4096
        }

        val recommendRawHalfSizeByDefault =
            totalRamGb < 8.0 || snapshot.largeHeapMemoryClassMb < 384 || snapshot.isLowRamDevice

        val advisories = buildAdvisories(
            snapshot = snapshot,
            totalRamGb = totalRamGb,
            recommendedComputeSlots = recommendedComputeSlots,
            recommendedMaxNetworkSlots = recommendedMaxNetworkSlots,
            recommendedFfmpegThreads = recommendedFfmpegThreads,
            maxSafeRasterDimensionPx = maxSafeRasterDimensionPx,
            recommendRawHalfSizeByDefault = recommendRawHalfSizeByDefault
        )

        return DeviceCapabilityReport(
            snapshot = snapshot,
            recommendedComputeSlots = recommendedComputeSlots,
            recommendedMaxNetworkSlots = recommendedMaxNetworkSlots,
            recommendedFfmpegThreads = recommendedFfmpegThreads,
            maxSafeRasterDimensionPx = maxSafeRasterDimensionPx,
            recommendRawHalfSizeByDefault = recommendRawHalfSizeByDefault,
            advisories = advisories
        )
    }

    /**
     * Profiles the active Android device using [ActivityManager], [StatFs], and [MediaCodecList],
     * then delegates budget calculation to [evaluate].
     */
    fun profileDevice(context: Context): DeviceCapabilityReport {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val totalRamBytes = memoryInfo.totalMem.takeIf { it > 0L } ?: Runtime.getRuntime().maxMemory()
        val heapMemoryClassMb = activityManager?.memoryClass ?: 128
        val largeHeapMemoryClassMb = activityManager?.largeMemoryClass ?: heapMemoryClassMb
        val isLowRamDevice = activityManager?.isLowRamDevice ?: false
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freeCacheStorageBytes = runCatching {
            StatFs(context.cacheDir.absolutePath).availableBytes
        }.getOrDefault(0L)

        val encoderCaps = detectHardwareEncoders()

        return evaluate(
            DeviceHardwareSnapshot(
                totalRamBytes = totalRamBytes,
                heapMemoryClassMb = heapMemoryClassMb,
                largeHeapMemoryClassMb = largeHeapMemoryClassMb,
                isLowRamDevice = isLowRamDevice,
                cpuCores = cpuCores,
                freeCacheStorageBytes = freeCacheStorageBytes,
                hasHwAv1Encoder = encoderCaps.hasAv1,
                hasHwHevcEncoder = encoderCaps.hasHevc,
                hasHwH264Encoder = encoderCaps.hasH264
            )
        )
    }

    private fun buildAdvisories(
        snapshot: DeviceHardwareSnapshot,
        totalRamGb: Double,
        recommendedComputeSlots: Int,
        recommendedMaxNetworkSlots: Int,
        recommendedFfmpegThreads: Int,
        maxSafeRasterDimensionPx: Int,
        recommendRawHalfSizeByDefault: Boolean
    ): List<DeviceAdvisory> {
        val advisories = mutableListOf<DeviceAdvisory>()
        val ramLabel = String.format(Locale.US, "%.1f GB RAM", totalRamGb)

        advisories += buildVideoCodecAdvisory(snapshot)
        advisories += buildRawHalfSizeAdvisory(
            snapshot = snapshot,
            ramLabel = ramLabel,
            maxSafeRasterDimensionPx = maxSafeRasterDimensionPx,
            recommendHalfSize = recommendRawHalfSizeByDefault
        )

        if (snapshot.freeCacheStorageBytes < LOW_CACHE_STORAGE_BYTES) {
            val freeGbLabel = String.format(
                Locale.US,
                "%.2f GB",
                snapshot.freeCacheStorageBytes.coerceAtLeast(0L) / 1_000_000_000.0
            )
            advisories += DeviceAdvisory(
                topic = SettingTopic.TWO_PASS_ENCODING,
                severity = AdvisorySeverity.WARNING,
                shortBadge = "Low Cache Space",
                message = "Only $freeGbLabel free cache storage (< 1.5 GB) for staging and 2-pass log files; clear cache before long dual-pass encodes."
            )
            advisories += DeviceAdvisory(
                topic = SettingTopic.TARGET_FILESIZE,
                severity = AdvisorySeverity.WARNING,
                shortBadge = "Low Staging Space",
                message = "Only $freeGbLabel free cache storage (< 1.5 GB) for intermediate target-filesize staging and muxing."
            )
        } else {
            advisories += DeviceAdvisory(
                topic = SettingTopic.TWO_PASS_ENCODING,
                severity = if (snapshot.isLowRamDevice || snapshot.cpuCores < 6) {
                    AdvisorySeverity.CAUTION
                } else {
                    AdvisorySeverity.INFO
                },
                shortBadge = "2x Encode Time",
                message = "Two-pass encoding improves target-filesize accuracy at roughly double the CPU load and processing time."
            )
        }

        advisories += DeviceAdvisory(
            topic = SettingTopic.ARIA2C_ACCELERATOR,
            severity = if (snapshot.isLowRamDevice) AdvisorySeverity.CAUTION else AdvisorySeverity.INFO,
            shortBadge = "Max $recommendedMaxNetworkSlots Net Slots",
            message = "Multi-connection aria2c acceleration increases per-job socket memory; limit active network slots to $recommendedMaxNetworkSlots on this device."
        )

        advisories += DeviceAdvisory(
            topic = SettingTopic.CONCURRENCY_SLOTS,
            severity = if (snapshot.isLowRamDevice) AdvisorySeverity.CAUTION else AdvisorySeverity.INFO,
            shortBadge = "$recommendedComputeSlots Compute Slots",
            message = "Recommend $recommendedComputeSlots compute slot(s) and $recommendedFfmpegThreads FFmpeg threads based on $ramLabel and ${snapshot.cpuCores} CPU cores."
        )

        return advisories
    }

    private fun buildVideoCodecAdvisory(snapshot: DeviceHardwareSnapshot): DeviceAdvisory {
        val hwFallbacks = buildList {
            if (snapshot.hasHwHevcEncoder) add("HEVC")
            if (snapshot.hasHwH264Encoder) add("H.264")
        }
        val hwAvailabilityNote = if (hwFallbacks.isNotEmpty()) {
            "Hardware ${hwFallbacks.joinToString("/")} encoding is available."
        } else {
            "No hardware video encoders detected; fallback runs in software."
        }

        return if (!snapshot.hasHwAv1Encoder || snapshot.cpuCores < 8) {
            DeviceAdvisory(
                topic = SettingTopic.VIDEO_CODEC,
                severity = if (snapshot.isLowRamDevice || snapshot.cpuCores < 6) {
                    AdvisorySeverity.WARNING
                } else {
                    AdvisorySeverity.CAUTION
                },
                shortBadge = if (!snapshot.hasHwAv1Encoder) "SVT-AV1 = CPU Load" else "Limited Cores",
                message = "SVT-AV1 software encoding imposes high CPU load on 1080p+/4K clips (${snapshot.cpuCores} cores). $hwAvailabilityNote"
            )
        } else {
            val hevcNote = if (snapshot.hasHwHevcEncoder) "HEVC and H.264" else "H.264"
            DeviceAdvisory(
                topic = SettingTopic.VIDEO_CODEC,
                severity = AdvisorySeverity.INFO,
                shortBadge = "HW AV1 Ready",
                message = "Hardware AV1 encoding is supported alongside $hevcNote for efficient 1080p+/4K compression."
            )
        }
    }

    private fun buildRawHalfSizeAdvisory(
        snapshot: DeviceHardwareSnapshot,
        ramLabel: String,
        maxSafeRasterDimensionPx: Int,
        recommendHalfSize: Boolean
    ): DeviceAdvisory {
        return if (recommendHalfSize) {
            DeviceAdvisory(
                topic = SettingTopic.RAW_HALF_SIZE_FAST,
                severity = if (snapshot.isLowRamDevice) AdvisorySeverity.WARNING else AdvisorySeverity.CAUTION,
                shortBadge = "Half-Size Default",
                message = "50MP+ full-size RAW demosaic buffers can pressure ${snapshot.largeHeapMemoryClassMb}MB large heap ($ramLabel). Half-size keeps rasters safer under ${maxSafeRasterDimensionPx}px."
            )
        } else {
            DeviceAdvisory(
                topic = SettingTopic.RAW_HALF_SIZE_FAST,
                severity = AdvisorySeverity.INFO,
                shortBadge = "Full Demosaic OK",
                message = "$ramLabel and ${snapshot.largeHeapMemoryClassMb}MB large heap can handle 50MP+ RAW demosaic; half-size remains optional for faster batch exports."
            )
        }
    }

    private data class EncoderCapabilities(
        val hasAv1: Boolean,
        val hasHevc: Boolean,
        val hasH264: Boolean
    )

    private fun detectHardwareEncoders(): EncoderCapabilities {
        var hasAv1 = false
        var hasHevc = false
        var hasH264 = false

        val scanned = runCatching {
            val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (info in codecInfos) {
                if (!info.isEncoder) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly) {
                    continue
                }
                val lowerName = info.name.lowercase(Locale.US)
                if (lowerName.startsWith("c2.android.") ||
                    lowerName.startsWith("c2.google.") ||
                    lowerName.startsWith("omx.google.") ||
                    lowerName.startsWith("omx.ffmpeg.")
                ) {
                    continue
                }
                for (mime in info.supportedTypes) {
                    when (mime.lowercase(Locale.US)) {
                        "video/av01" -> hasAv1 = true
                        "video/hevc" -> hasHevc = true
                        "video/avc" -> hasH264 = true
                    }
                }
            }
        }.isSuccess

        return EncoderCapabilities(
            hasAv1 = hasAv1,
            hasHevc = hasHevc,
            hasH264 = if (scanned) hasH264 else true
        )
    }
}
