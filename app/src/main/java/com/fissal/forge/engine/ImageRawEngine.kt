package com.fissal.forge.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.exifinterface.media.ExifInterface
import com.fissal.forge.domain.logic.AspectRatioSnapper
import com.fissal.forge.domain.logic.DeviceCapabilityAdvisor
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.SampleCalibrationRatio
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.homesoft.photo.libraw.LibRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class ImageSamplePreviewResult(
    val originalBitmap: Bitmap,
    val compressedBitmap: Bitmap,
    val calibration: SampleCalibrationRatio
)

/**
 * Memory-safe, OOM-resistant image & Camera RAW processor.
 * - Uses LibRaw (half-size/fast preview + ARGB_8888 mutable bitmaps + cancel hooks).
 * - Uses BitmapRegionDecoder / inSampleSize subsampling for huge 100MP+ raster images.
 * - Supports binary-search target filesize compression & privacy EXIF stripping.
 */
class ImageRawEngine(
    private val appContext: Context,
    private val deviceCapabilities: DeviceCapabilityReport = DeviceCapabilityAdvisor.profileDevice(appContext)
) {

    // Prevent two 100MP+ RAW demosaics from hitting native memory concurrently
    private val rawMemorySemaphore = Semaphore(1)

    private val maxSafeRasterDim: Int
        get() = min(MAX_SAFE_RASTER_DIM, deviceCapabilities.maxSafeRasterDimensionPx)

    private val maxIntermediatePixels: Long
        get() = if (deviceCapabilities.snapshot.isLowRamDevice) {
            DeviceCapabilityAdvisor.LOW_RAM_MAX_INTERMEDIATE_PIXELS
        } else {
            DeviceCapabilityAdvisor.DEFAULT_MAX_INTERMEDIATE_PIXELS
        }

    suspend fun loadEditorPreviewBitmap(
        uri: Uri,
        probe: MediaProbeResult,
        maxDimension: Int = 1440
    ): Bitmap? = withContext(Dispatchers.IO) {
        val safeMaxDim = min(maxDimension.coerceAtLeast(16), maxSafeRasterDim)
        runCatching {
            when (probe.mediaKind) {
                MediaKind.CAMERA_RAW -> rawMemorySemaphore.withPermit {
                    appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        LibRaw().use { raw ->
                            val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
                                if (cause is CancellationException) raw.setCancelFlag()
                            }
                            try {
                                if (raw.openFd(pfd.fd) != 0) return@withPermit null
                                raw.setCameraWhiteBalance(true)
                                raw.setAutoBrightness(true)
                                raw.setOutputColorSpace(LibRaw.COLORSPACE_SRGB)
                                raw.setQuality(0) // Fast linear demosaic for instant preview
                                raw.setHalfSize(true)
                                if (raw.dcrawProcess() != 0) return@withPermit null
                                val bmp = raw.getMutableBitmap(Bitmap.Config.ARGB_8888) ?: return@withPermit null
                                downscaleIfNeeded(bmp, safeMaxDim)
                            } finally {
                                cancelHandle.dispose()
                            }
                        }
                    }
                }
                MediaKind.IMAGE -> {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    appContext.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    val sample = calculateInSampleSize(opts.outWidth, opts.outHeight, safeMaxDim)
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val rawBmp = appContext.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, decodeOpts)
                    } ?: return@runCatching null
                    rotateAndBoundBitmap(rawBmp, probe.rotationDegrees, safeMaxDim)
                }
                MediaKind.VIDEO -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(appContext, uri)
                        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: return@runCatching null
                        downscaleIfNeeded(frame, safeMaxDim)
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
                else -> null
            }
        }.getOrNull()
    }

    suspend fun processImageOrRaw(
        sourceUri: Uri,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        outputFile: File,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        onProgress(5f, "Allocating image decode pipeline...")
        val targetParent = (outputFile.parentFile ?: appContext.cacheDir).apply { mkdirs() }
        val availableBytes = runCatching { StatFs(targetParent.absolutePath).availableBytes }.getOrDefault(-1L)
        if (availableBytes >= 0L) {
            val preflight = DeviceCapabilityAdvisor.evaluateStoragePreflight(
                availableBytes = availableBytes,
                estimatedInputBytes = probe.fileSizeBytes.coerceAtMost(64L * 1024L * 1024L),
                hasTransformStage = true,
                isSampleLab = false
            )
            if (!preflight.sufficient) {
                throw IOException(preflight.userFacingMessage ?: "Insufficient scratch storage")
            }
        }

        val targetExt = (spec.convert?.targetExtension ?: if (probe.mediaKind == MediaKind.CAMERA_RAW) "jpg" else probe.extension)
            .lowercase(Locale.US)
        val format = resolveCompressFormat(targetExt)

        val maxDimLimit = spec.compress?.maxDimensionPx
        val useHalfSize = spec.compress?.rawHalfSizeFast == true ||
            deviceCapabilities.recommendRawHalfSizeByDefault ||
            (probe.effectiveWidth <= 0 && deviceCapabilities.snapshot.largeHeapMemoryClassMb < 512) ||
            (maxDimLimit != null && probe.effectiveWidth > 0 && max(probe.effectiveWidth, probe.effectiveHeight) / 2 >= maxDimLimit)

        var workingBitmap: Bitmap = try {
            if (probe.mediaKind == MediaKind.CAMERA_RAW) {
                onProgress(15f, "Demosaicing Camera RAW via LibRaw...")
                try {
                    decodeCameraRawSafely(sourceUri, useHalfSize)
                } catch (oom: OutOfMemoryError) {
                    if (!useHalfSize) {
                        System.gc()
                        onProgress(20f, "Retrying RAW demosaic in half-size mode...")
                        decodeCameraRawSafely(sourceUri, halfSize = true)
                    } else {
                        throw oom
                    }
                }
            } else {
                onProgress(15f, "Decoding image safely...")
                decodeRasterSafely(sourceUri, probe, spec.crop, maxDimLimit)
            }
        } catch (oom: OutOfMemoryError) {
            System.gc()
            throw IOException("Image exceeded safe memory bounds even after subsampling: ${oom.message}", oom)
        }

        try {
            // For RAW (or if raster wasn't region-cropped yet), apply crop cleanly on upright decoded pixels
            if (spec.crop != null && (probe.mediaKind == MediaKind.CAMERA_RAW || probe.rotationDegrees != 0)) {
                onProgress(55f, "Cropping to ${spec.crop.preset.label}...")
                val px = AspectRatioSnapper.toEvenPixelCrop(spec.crop.rect, workingBitmap.width, workingBitmap.height)
                if (px.width < workingBitmap.width || px.height < workingBitmap.height) {
                    val cropped = Bitmap.createBitmap(workingBitmap, px.x, px.y, px.width, px.height)
                    if (cropped !== workingBitmap) {
                        workingBitmap.recycle()
                        workingBitmap = cropped
                    }
                }
            }

            val safeWorkingCap = maxDimLimit?.coerceAtMost(maxSafeRasterDim) ?: maxSafeRasterDim
            if (max(workingBitmap.width, workingBitmap.height) > safeWorkingCap) {
                onProgress(68f, "Scaling to max ${safeWorkingCap}px...")
                workingBitmap = downscaleIfNeeded(workingBitmap, safeWorkingCap)
            }

            onProgress(78f, "Encoding ${targetExt.uppercase(Locale.US)}...")
            val compress = spec.compress
            val targetBytes = if (compress?.mode == CompressMode.TARGET_BYTES) compress.targetBytes else null
            val defaultQuality = (compress?.imageQuality ?: 90).coerceIn(15, 100)

            if (targetBytes != null && format != Bitmap.CompressFormat.PNG) {
                encodeToTargetBytes(workingBitmap, format, targetBytes, outputFile)
            } else if (targetBytes != null && format == Bitmap.CompressFormat.PNG) {
                encodePngToTargetBytes(workingBitmap, targetBytes, outputFile)
            } else {
                FileOutputStream(outputFile).use { fos ->
                    if (!workingBitmap.compress(format, defaultQuality, fos)) {
                        throw IOException("Failed to compress image to ${outputFile.name}")
                    }
                    fos.flush()
                }
            }

            if (format == Bitmap.CompressFormat.JPEG && !spec.delivery.stripMetadata) {
                copySafeExifMetadata(sourceUri, outputFile)
            }

            onProgress(100f, "Complete")
            outputFile
        } catch (oom: OutOfMemoryError) {
            System.gc()
            throw IOException("Image exceeded safe memory bounds during processing: ${oom.message}", oom)
        } finally {
            if (!workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        }
    }

    /**
     * Decodes an upright, memory-bounded reference bitmap for Sample Lab sessions, applying
     * [crop] and downscaling to [maxDimension] without holding full 100MP+ rasters in heap.
     */
    suspend fun decodeReferenceSourceBitmap(
        uri: Uri,
        probe: MediaProbeResult,
        crop: CropSpec?,
        maxDimension: Int = 2048
    ): Bitmap = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val safeCap = DeviceCapabilityAdvisor.computeSamplePreviewMaxDimPx(deviceCapabilities, maxDimension)
        val useHalfSize = probe.mediaKind == MediaKind.CAMERA_RAW && (
            deviceCapabilities.recommendRawHalfSizeByDefault ||
                probe.effectiveWidth <= 0 ||
                max(probe.effectiveWidth, probe.effectiveHeight) / 2 >= safeCap
            )

        var workingBitmap: Bitmap = try {
            if (probe.mediaKind == MediaKind.CAMERA_RAW) {
                decodeCameraRawSafely(uri, halfSize = useHalfSize)
            } else {
                val (decoded, alreadyCropped) = decodeRasterWithCropFlag(
                    uri = uri,
                    probe = probe,
                    crop = crop,
                    maxDimLimit = safeCap,
                    allowHighResCropHeadroom = false
                )
                if (crop != null && !alreadyCropped) {
                    applyCropSafely(decoded, crop)
                } else {
                    decoded
                }
            }
        } catch (oom: OutOfMemoryError) {
            System.gc()
            throw IOException("Reference image exceeded safe memory bounds: ${oom.message}", oom)
        }

        try {
            coroutineContext.ensureActive()
            if (crop != null && probe.mediaKind == MediaKind.CAMERA_RAW) {
                workingBitmap = applyCropSafely(workingBitmap, crop)
            }
            workingBitmap = downscaleIfNeeded(workingBitmap, safeCap)
            workingBitmap
        } catch (err: Throwable) {
            if (!workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            throw err
        }
    }

    /**
     * Non-destructively encodes a Sample Lab candidate directly to [outputFile] from
     * [uprightReferenceBitmap] (never mutating or recycling caller's bitmap), honoring target
     * `maxDimensionPx` / `rawHalfSizeFast` scaling and Quality or Target-Bytes encoding.
     */
    suspend fun renderImageCandidateToFile(
        uprightReferenceBitmap: Bitmap,
        probe: MediaProbeResult,
        spec: PipelineSpec,
        outputFile: File,
        sampleWorkMaxDim: Int = 1440
    ): SampleCalibrationRatio = withContext(Dispatchers.IO) {
        if (uprightReferenceBitmap.isRecycled) {
            throw IOException("Reference bitmap has already been recycled")
        }
        coroutineContext.ensureActive()

        val targetExt = (spec.convert?.targetExtension ?: if (probe.mediaKind == MediaKind.CAMERA_RAW) "jpg" else probe.extension)
            .lowercase(Locale.US)
        val format = resolveCompressFormat(targetExt)
        val safeWorkMax = sampleWorkMaxDim.coerceAtLeast(16)
        val geometry = computeImageSampleGeometry(
            probe = probe,
            spec = spec,
            fallbackW = uprightReferenceBitmap.width,
            fallbackH = uprightReferenceBitmap.height,
            curSampleMax = max(uprightReferenceBitmap.width, uprightReferenceBitmap.height),
            sampleWorkMaxDim = safeWorkMax
        )

        var workingTemp: Bitmap? = null
        try {
            val curMax = max(uprightReferenceBitmap.width, uprightReferenceBitmap.height).coerceAtLeast(1)
            val working: Bitmap = if (geometry.targetSampleMax < curMax) {
                val s = geometry.targetSampleMax.toFloat() / curMax.toFloat()
                val sw = ((uprightReferenceBitmap.width * s).roundToInt() and 1.inv()).coerceIn(2, safeWorkMax)
                val sh = ((uprightReferenceBitmap.height * s).roundToInt() and 1.inv()).coerceIn(2, safeWorkMax)
                Bitmap.createScaledBitmap(uprightReferenceBitmap, sw, sh, true).also {
                    if (it !== uprightReferenceBitmap) workingTemp = it
                }
            } else {
                uprightReferenceBitmap
            }

            coroutineContext.ensureActive()

            val samplePixels = (working.width.toDouble() * working.height.toDouble()).coerceAtLeast(1.0)
            val areaScaleToFinal = (geometry.fullOutputPixels / samplePixels).coerceAtLeast(0.05)

            val refBaos = ByteArrayOutputStream()
            working.compress(Bitmap.CompressFormat.JPEG, 95, refBaos)
            val originalRefBytes = refBaos.size().toLong().coerceAtLeast(1L)

            coroutineContext.ensureActive()

            outputFile.parentFile?.mkdirs()
            val compress = spec.compress
            val targetBytes = if (compress?.mode == CompressMode.TARGET_BYTES) compress.targetBytes else null
            if (targetBytes != null) {
                val scaledSampleTarget = (targetBytes.toDouble() / areaScaleToFinal).roundToLong().coerceAtLeast(1024L)
                val encodedBytes = if (format == Bitmap.CompressFormat.PNG) {
                    encodePngToTargetByteArray(working, scaledSampleTarget, minDimFloor = 96, stopDim = 120)
                } else {
                    encodeToTargetByteArray(working, format, scaledSampleTarget, minDimFloor = 96)
                }
                FileOutputStream(outputFile).use { fos ->
                    fos.write(encodedBytes)
                    fos.flush()
                }
            } else {
                val quality = (compress?.imageQuality ?: 90).coerceIn(15, 100)
                FileOutputStream(outputFile).use { fos ->
                    if (!working.compress(format, quality, fos)) {
                        throw IOException("Failed to encode image candidate ($targetExt)")
                    }
                    fos.flush()
                }
            }

            val compressedBytes = outputFile.length().coerceAtLeast(1L)
            SampleCalibrationRatio(
                referenceBytes = originalRefBytes,
                compressedBytes = compressedBytes,
                areaScaleToFinal = areaScaleToFinal
            )
        } catch (err: Throwable) {
            runCatching { outputFile.delete() }
            throw err
        } finally {
            workingTemp?.let { bmp ->
                if (bmp !== uprightReferenceBitmap && !bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    /**
     * Generates an authentic in-memory Before/After preview pair and sample calibration ratio
     * without mutating or recycling the caller's [previewBitmap].
     */
    suspend fun generateImageSamplePreview(
        previewBitmap: Bitmap,
        probe: MediaProbeResult,
        spec: PipelineSpec
    ): ImageSamplePreviewResult = withContext(Dispatchers.IO) {
        if (previewBitmap.isRecycled) {
            throw IOException("Preview bitmap has already been recycled")
        }
        coroutineContext.ensureActive()

        val targetExt = (spec.convert?.targetExtension ?: if (probe.mediaKind == MediaKind.CAMERA_RAW) "jpg" else probe.extension)
            .lowercase(Locale.US)
        val format = resolveCompressFormat(targetExt)

        val geometry = computeImageSampleGeometry(
            probe = probe,
            spec = spec,
            fallbackW = previewBitmap.width,
            fallbackH = previewBitmap.height,
            curSampleMax = max(previewBitmap.width, previewBitmap.height),
            sampleWorkMaxDim = 1440
        )
        val reqMaxDim = spec.compress?.maxDimensionPx?.takeIf { it > 0 }

        var croppedTemp: Bitmap? = null
        var workingTemp: Bitmap? = null
        var originalOut: Bitmap? = null
        var compressedOut: Bitmap? = null

        try {
            val postCropSource: Bitmap = if (spec.crop != null) {
                val px = AspectRatioSnapper.toEvenPixelCrop(spec.crop.rect, previewBitmap.width, previewBitmap.height)
                if (px.width < previewBitmap.width || px.height < previewBitmap.height) {
                    Bitmap.createBitmap(previewBitmap, px.x, px.y, px.width, px.height).also {
                        croppedTemp = it
                    }
                } else {
                    previewBitmap
                }
            } else {
                previewBitmap
            }

            val previewCurMax = max(postCropSource.width, postCropSource.height).coerceAtLeast(1)
            val targetPreviewMax = if (reqMaxDim != null && reqMaxDim < previewCurMax) {
                reqMaxDim.coerceIn(120, 1440)
            } else {
                previewCurMax.coerceAtMost(1440)
            }

            val working: Bitmap = if (targetPreviewMax < previewCurMax) {
                val s = targetPreviewMax.toFloat() / previewCurMax.toFloat()
                val sw = ((postCropSource.width * s).roundToInt() and 1.inv()).coerceIn(2, 1440)
                val sh = ((postCropSource.height * s).roundToInt() and 1.inv()).coerceIn(2, 1440)
                Bitmap.createScaledBitmap(postCropSource, sw, sh, true).also {
                    workingTemp = it
                }
            } else if (postCropSource === previewBitmap) {
                previewBitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                    workingTemp = it
                }
            } else {
                postCropSource
            }

            coroutineContext.ensureActive()

            val samplePixels = (working.width.toDouble() * working.height.toDouble()).coerceAtLeast(1.0)
            val areaScaleToFinal = (geometry.fullOutputPixels / samplePixels).coerceAtLeast(0.05)

            // Reference baseline at JPEG Q95 on the exact sample geometry
            val refBaos = ByteArrayOutputStream()
            working.compress(Bitmap.CompressFormat.JPEG, 95, refBaos)
            val originalRefBytes = refBaos.size().toLong().coerceAtLeast(1L)

            coroutineContext.ensureActive()

            val compress = spec.compress
            val targetBytes = if (compress?.mode == CompressMode.TARGET_BYTES) compress.targetBytes else null
            val compressedBytes: ByteArray = if (targetBytes != null) {
                val scaledSampleTarget = (targetBytes.toDouble() / areaScaleToFinal).roundToLong().coerceAtLeast(1024L)
                if (format == Bitmap.CompressFormat.PNG) {
                    encodePngToTargetByteArray(working, scaledSampleTarget, minDimFloor = 96, stopDim = 120)
                } else {
                    encodeToTargetByteArray(working, format, scaledSampleTarget, minDimFloor = 96)
                }
            } else {
                val quality = (compress?.imageQuality ?: 90).coerceIn(15, 100)
                val outBaos = ByteArrayOutputStream()
                if (!working.compress(format, quality, outBaos)) {
                    throw IOException("Failed to encode image sample preview ($targetExt)")
                }
                outBaos.toByteArray()
            }

            coroutineContext.ensureActive()

            compressedOut = BitmapFactory.decodeByteArray(
                compressedBytes,
                0,
                compressedBytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ) ?: working.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IOException("Failed to decode compressed sample preview")

            originalOut = working.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IOException("Failed to copy original sample preview")

            ImageSamplePreviewResult(
                originalBitmap = originalOut,
                compressedBitmap = compressedOut,
                calibration = SampleCalibrationRatio(
                    referenceBytes = originalRefBytes,
                    compressedBytes = compressedBytes.size.toLong().coerceAtLeast(1L),
                    areaScaleToFinal = areaScaleToFinal
                )
            )
        } catch (err: Throwable) {
            originalOut?.let { if (!it.isRecycled) it.recycle() }
            compressedOut?.let { if (!it.isRecycled) it.recycle() }
            throw err
        } finally {
            workingTemp?.let { bmp ->
                if (bmp !== previewBitmap && bmp !== originalOut && bmp !== compressedOut && !bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            croppedTemp?.let { bmp ->
                if (bmp !== previewBitmap && bmp !== originalOut && bmp !== compressedOut && bmp !== workingTemp && !bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    private data class ImageSampleGeometry(
        val fullOutputPixels: Double,
        val targetSampleMax: Int
    )

    private fun computeImageSampleGeometry(
        probe: MediaProbeResult,
        spec: PipelineSpec,
        fallbackW: Int,
        fallbackH: Int,
        curSampleMax: Int,
        sampleWorkMaxDim: Int
    ): ImageSampleGeometry {
        val fullSrcW = probe.effectiveWidth.takeIf { it > 0 } ?: fallbackW
        val fullSrcH = probe.effectiveHeight.takeIf { it > 0 } ?: fallbackH
        val fullCrop = if (spec.crop != null && fullSrcW > 1 && fullSrcH > 1) {
            AspectRatioSnapper.toEvenPixelCrop(spec.crop.rect, fullSrcW, fullSrcH)
        } else {
            null
        }
        val fullPostCropW = fullCrop?.width ?: fullSrcW
        val fullPostCropH = fullCrop?.height ?: fullSrcH
        val rawHalf = probe.mediaKind == MediaKind.CAMERA_RAW && spec.compress?.rawHalfSizeFast == true
        val postHalfW = if (rawHalf) (fullPostCropW / 2.0) else fullPostCropW.toDouble()
        val postHalfH = if (rawHalf) (fullPostCropH / 2.0) else fullPostCropH.toDouble()
        val postHalfMax = max(postHalfW, postHalfH).coerceAtLeast(1.0)
        val reqMaxDim = spec.compress?.maxDimensionPx?.takeIf { it > 0 }
        val finalScale = if (reqMaxDim != null && postHalfMax > reqMaxDim) {
            reqMaxDim.toDouble() / postHalfMax
        } else {
            1.0
        }
        val fullFinalW = (postHalfW * finalScale).coerceAtLeast(2.0)
        val fullFinalH = (postHalfH * finalScale).coerceAtLeast(2.0)
        val fullOutputPixels = fullFinalW * fullFinalH

        val desiredFinalMax = if (reqMaxDim != null) {
            min(postHalfMax, reqMaxDim.toDouble())
        } else {
            postHalfMax
        }
        val targetSampleMax = min(
            sampleWorkMaxDim.toDouble(),
            min(curSampleMax.coerceAtLeast(2).toDouble(), desiredFinalMax)
        ).roundToInt().coerceAtLeast(2)

        return ImageSampleGeometry(
            fullOutputPixels = fullOutputPixels,
            targetSampleMax = targetSampleMax
        )
    }

    private fun applyCropSafely(source: Bitmap, crop: CropSpec): Bitmap {
        val px = AspectRatioSnapper.toEvenPixelCrop(crop.rect, source.width, source.height)
        if (px.width >= source.width && px.height >= source.height) return source
        val cropped = Bitmap.createBitmap(source, px.x, px.y, px.width, px.height)
        if (cropped !== source) {
            source.recycle()
        }
        return cropped
    }

    private suspend fun decodeCameraRawSafely(uri: Uri, halfSize: Boolean): Bitmap {
        return rawMemorySemaphore.withPermit {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                LibRaw().use { raw ->
                    val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
                        if (cause is CancellationException) raw.setCancelFlag()
                    }
                    try {
                        val rcOpen = raw.openFd(pfd.fd)
                        if (rcOpen != 0) throw IOException("LibRaw failed to open RAW file (code=$rcOpen)")
                        raw.setCameraWhiteBalance(true)
                        raw.setAutoBrightness(true)
                        raw.setOutputColorSpace(LibRaw.COLORSPACE_SRGB)
                        raw.setQuality(if (halfSize) 1 else 3)
                        raw.setHalfSize(halfSize)
                        val rcProc = raw.dcrawProcess()
                        if (rcProc != 0) throw IOException("LibRaw demosaic failed (code=$rcProc)")
                        raw.getMutableBitmap(Bitmap.Config.ARGB_8888)
                            ?: throw IOException("LibRaw produced a null bitmap")
                    } finally {
                        cancelHandle.dispose()
                    }
                }
            } ?: throw IOException("Unable to open RAW file descriptor for $uri")
        }
    }

    private fun decodeRasterSafely(
        uri: Uri,
        probe: MediaProbeResult,
        crop: CropSpec?,
        maxDimLimit: Int?
    ): Bitmap = decodeRasterWithCropFlag(
        uri = uri,
        probe = probe,
        crop = crop,
        maxDimLimit = maxDimLimit,
        allowHighResCropHeadroom = true
    ).first

    private fun decodeRasterWithCropFlag(
        uri: Uri,
        probe: MediaProbeResult,
        crop: CropSpec?,
        maxDimLimit: Int?,
        allowHighResCropHeadroom: Boolean = true
    ): Pair<Bitmap, Boolean> {
        val safeDecodeCap = maxDimLimit?.coerceAtMost(maxSafeRasterDim) ?: maxSafeRasterDim

        // Fast memory-saving BitmapRegionDecoder path when cropping huge unrotated JPEGs/PNGs/WebPs
        if (crop != null && probe.rotationDegrees == 0 && probe.rawWidth > 0 && probe.rawHeight > 0 &&
            probe.extension in REGION_DECODABLE_EXTENSIONS
        ) {
            val px = AspectRatioSnapper.toEvenPixelCrop(crop.rect, probe.rawWidth, probe.rawHeight)
            val regionRect = Rect(px.x, px.y, px.x + px.width, px.y + px.height)
            val regionSample = calculateInSampleSize(px.width, px.height, safeDecodeCap)
            val regionBitmap = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BitmapRegionDecoder.newInstance(stream)
                    } else {
                        @Suppress("DEPRECATION")
                        BitmapRegionDecoder.newInstance(stream, false)
                    }
                    try {
                        decoder?.decodeRegion(
                            regionRect,
                            BitmapFactory.Options().apply {
                                inSampleSize = regionSample
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                        )
                    } finally {
                        decoder?.recycle()
                    }
                }
            }.getOrNull()
            if (regionBitmap != null) return downscaleIfNeeded(regionBitmap, safeDecodeCap) to true
        }

        // Standard downsampled decode
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val targetCap = if (crop != null && allowHighResCropHeadroom) {
            max(safeDecodeCap, maxSafeRasterDim).coerceAtMost(maxSafeRasterDim)
        } else {
            safeDecodeCap
        }
        val sample = calculateInSampleSize(opts.outWidth, opts.outHeight, targetCap)

        val decoded = appContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } ?: throw IOException("Failed to decode raster image $uri")

        return rotateAndBoundBitmap(decoded, probe.rotationDegrees, targetCap) to false
    }

    private fun resolveCompressFormat(targetExt: String): Bitmap.CompressFormat = when (targetExt) {
        "png" -> Bitmap.CompressFormat.PNG
        "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        else -> Bitmap.CompressFormat.JPEG
    }

    private suspend fun encodeToTargetBytes(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        targetBytes: Long,
        outputFile: File
    ) {
        val bytes = encodeToTargetByteArray(bitmap, format, targetBytes, minDimFloor = 160)
        FileOutputStream(outputFile).use { fos ->
            fos.write(bytes)
            fos.flush()
        }
    }

    private suspend fun encodeToTargetByteArray(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        targetBytes: Long,
        minDimFloor: Int
    ): ByteArray {
        var working = bitmap
        var createdCopy = false
        try {
            // Finer resolution stepping (0.88x) + broader quality binary search (16..96) preserves maximum sharpness under target cap
            repeat(6) {
                currentCoroutineContext().ensureActive()
                var low = 16
                var high = 96
                var bestBytes: ByteArray? = null

                while (low <= high) {
                    currentCoroutineContext().ensureActive()
                    val mid = (low + high) / 2
                    val baos = ByteArrayOutputStream()
                    working.compress(format, mid, baos)
                    val bytes = baos.toByteArray()
                    if (bytes.size <= targetBytes) {
                        bestBytes = bytes
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }

                if (bestBytes != null) {
                    return bestBytes
                }

                // Still over budget at Q16 -> step down resolution smoothly by 0.88x without upscaling thin strips
                val safeFloorW = min(minDimFloor, working.width).coerceAtLeast(1)
                val safeFloorH = min(minDimFloor, working.height).coerceAtLeast(1)
                val nextW = (working.width * 0.88f).roundToInt().coerceAtLeast(safeFloorW)
                val nextH = (working.height * 0.88f).roundToInt().coerceAtLeast(safeFloorH)
                if (nextW == working.width && nextH == working.height) {
                    val fallback = ByteArrayOutputStream()
                    working.compress(format, 16, fallback)
                    return fallback.toByteArray()
                }
                val scaled = Bitmap.createScaledBitmap(working, nextW, nextH, true)
                if (createdCopy && working !== bitmap && !working.isRecycled) {
                    working.recycle()
                }
                working = scaled
                createdCopy = true
            }

            // Final fallback at Q24 after smooth downscaling
            currentCoroutineContext().ensureActive()
            val fallback = ByteArrayOutputStream()
            working.compress(format, 24, fallback)
            return fallback.toByteArray()
        } finally {
            if (createdCopy && working !== bitmap && !working.isRecycled) {
                working.recycle()
            }
        }
    }

    private suspend fun encodePngToTargetBytes(bitmap: Bitmap, targetBytes: Long, outputFile: File) {
        val bytes = encodePngToTargetByteArray(bitmap, targetBytes, minDimFloor = 160, stopDim = 240)
        FileOutputStream(outputFile).use { fos ->
            fos.write(bytes)
            fos.flush()
        }
    }

    private suspend fun encodePngToTargetByteArray(
        bitmap: Bitmap,
        targetBytes: Long,
        minDimFloor: Int,
        stopDim: Int
    ): ByteArray {
        var working = bitmap
        var createdCopy = false
        try {
            repeat(8) {
                currentCoroutineContext().ensureActive()
                val baos = ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val data = baos.toByteArray()
                if (data.size <= targetBytes || max(working.width, working.height) <= stopDim) {
                    return data
                }
                val safeFloorW = min(minDimFloor, working.width).coerceAtLeast(1)
                val safeFloorH = min(minDimFloor, working.height).coerceAtLeast(1)
                val nextW = (working.width * 0.78f).roundToInt().coerceAtLeast(safeFloorW)
                val nextH = (working.height * 0.78f).roundToInt().coerceAtLeast(safeFloorH)
                if (nextW == working.width && nextH == working.height) {
                    return data
                }
                val scaled = Bitmap.createScaledBitmap(working, nextW, nextH, true)
                if (createdCopy && working !== bitmap && !working.isRecycled) {
                    working.recycle()
                }
                working = scaled
                createdCopy = true
            }
            currentCoroutineContext().ensureActive()
            val fallback = ByteArrayOutputStream()
            working.compress(Bitmap.CompressFormat.PNG, 100, fallback)
            return fallback.toByteArray()
        } finally {
            if (createdCopy && working !== bitmap && !working.isRecycled) {
                working.recycle()
            }
        }
    }

    private fun copySafeExifMetadata(sourceUri: Uri, destFile: File) {
        runCatching {
            val srcTags = appContext.contentResolver.openInputStream(sourceUri)?.use { stream ->
                val src = ExifInterface(stream)
                SAFE_EXIF_TAGS.associateWith { tag -> src.getAttribute(tag) }
            } ?: return
            val dst = ExifInterface(destFile.absolutePath)
            srcTags.forEach { (k, v) -> if (v != null) dst.setAttribute(k, v) }
            // Reset orientation to NORMAL since pixels are already rotated upright
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.saveAttributes()
        }
    }

    private fun rotateAndBoundBitmap(bitmap: Bitmap, rotationDegrees: Int, maxDimension: Int): Bitmap {
        val norm = ((rotationDegrees % 360) + 360) % 360
        if (norm == 0) {
            return downscaleIfNeeded(bitmap, maxDimension)
        }
        val rotatedW = if (norm == 90 || norm == 270) bitmap.height else bitmap.width
        val rotatedH = if (norm == 90 || norm == 270) bitmap.width else bitmap.height
        val maxRotatedSide = max(rotatedW, rotatedH)
        val scale = if (maxDimension > 0 && maxRotatedSide > maxDimension) {
            maxDimension.toFloat() / maxRotatedSide.toFloat()
        } else {
            1f
        }
        val matrix = Matrix().apply {
            postRotate(norm.toFloat())
            if (scale < 1f) {
                postScale(scale, scale)
            }
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return downscaleIfNeeded(transformed, maxDimension)
    }

    private fun downscaleIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= maxDimension || maxDimension <= 0) return bitmap
        val scale = maxDimension.toFloat() / maxSide.toFloat()
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(2) and 1.inv()
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(2) and 1.inv()
        val resized = Bitmap.createScaledBitmap(bitmap, w.coerceAtLeast(2), h.coerceAtLeast(2), true)
        if (resized !== bitmap) bitmap.recycle()
        return resized
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        return DeviceCapabilityAdvisor.calculateSafeInSampleSize(
            rawWidth = width,
            rawHeight = height,
            targetMaxDimPx = maxDim,
            maxSafeTexturePx = maxSafeRasterDim,
            maxIntermediatePixels = maxIntermediatePixels
        )
    }

    companion object {
        private const val MAX_SAFE_RASTER_DIM = DeviceCapabilityAdvisor.GL_SAFE_MAX_TEXTURE_PX
        private val REGION_DECODABLE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val SAFE_EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH
        )
    }
}
