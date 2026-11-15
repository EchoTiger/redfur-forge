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
import androidx.exifinterface.media.ExifInterface
import com.fissal.forge.domain.logic.AspectRatioSnapper
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.homesoft.photo.libraw.LibRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

/**
 * Memory-safe, OOM-resistant image & Camera RAW processor.
 * - Uses LibRaw (half-size/fast preview + ARGB_8888 mutable bitmaps + cancel hooks).
 * - Uses BitmapRegionDecoder / inSampleSize subsampling for huge 100MP+ raster images.
 * - Supports binary-search target filesize compression & privacy EXIF stripping.
 */
class ImageRawEngine(private val appContext: Context) {

    // Prevent two 100MP+ RAW demosaics from hitting native memory concurrently
    private val rawMemorySemaphore = Semaphore(1)

    suspend fun loadEditorPreviewBitmap(
        uri: Uri,
        probe: MediaProbeResult,
        maxDimension: Int = 1440
    ): Bitmap? = withContext(Dispatchers.IO) {
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
                                downscaleIfNeeded(bmp, maxDimension)
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
                    val sample = calculateInSampleSize(opts.outWidth, opts.outHeight, maxDimension)
                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val rawBmp = appContext.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, decodeOpts)
                    } ?: return@runCatching null
                    val rotated = applyExifRotation(rawBmp, probe.rotationDegrees)
                    downscaleIfNeeded(rotated, maxDimension)
                }
                MediaKind.VIDEO -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(appContext, uri)
                        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: return@runCatching null
                        downscaleIfNeeded(frame, maxDimension)
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
        val targetExt = (spec.convert?.targetExtension ?: if (probe.mediaKind == MediaKind.CAMERA_RAW) "jpg" else probe.extension)
            .lowercase(Locale.US)
        val format = when (targetExt) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            else -> Bitmap.CompressFormat.JPEG
        }

        val maxDimLimit = spec.compress?.maxDimensionPx
        val useHalfSize = spec.compress?.rawHalfSizeFast == true ||
            (maxDimLimit != null && probe.effectiveWidth > 0 && max(probe.effectiveWidth, probe.effectiveHeight) / 2 >= maxDimLimit)

        var workingBitmap: Bitmap = try {
            if (probe.mediaKind == MediaKind.CAMERA_RAW) {
                onProgress(15f, "Demosaicing Camera RAW via LibRaw...")
                decodeCameraRawSafely(sourceUri, useHalfSize)
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

            if (maxDimLimit != null && max(workingBitmap.width, workingBitmap.height) > maxDimLimit) {
                onProgress(68f, "Scaling to max ${maxDimLimit}px...")
                workingBitmap = downscaleIfNeeded(workingBitmap, maxDimLimit)
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
        } finally {
            if (!workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        }
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
    ): Bitmap {
        val safeDecodeCap = maxDimLimit?.coerceAtMost(MAX_SAFE_RASTER_DIM) ?: MAX_SAFE_RASTER_DIM

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
            if (regionBitmap != null) return regionBitmap
        }

        // Standard downsampled decode
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val targetCap = if (crop != null) max(safeDecodeCap, 3840) else safeDecodeCap
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

        return applyExifRotation(decoded, probe.rotationDegrees)
    }

    private fun encodeToTargetBytes(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        targetBytes: Long,
        outputFile: File
    ) {
        var working = bitmap
        var createdCopy = false
        try {
            // Finer resolution stepping (0.88x) + broader quality binary search (16..96) preserves maximum sharpness under target cap
            repeat(6) {
                var low = 16
                var high = 96
                var bestBytes: ByteArray? = null

                while (low <= high) {
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
                    FileOutputStream(outputFile).use {
                        it.write(bestBytes)
                        it.flush()
                    }
                    return
                }

                // Still over budget at Q16 -> step down resolution smoothly by 0.88x
                val nextW = (working.width * 0.88f).roundToInt().coerceAtLeast(160)
                val nextH = (working.height * 0.88f).roundToInt().coerceAtLeast(160)
                val scaled = Bitmap.createScaledBitmap(working, nextW, nextH, true)
                if (createdCopy && working !== bitmap && !working.isRecycled) {
                    working.recycle()
                }
                working = scaled
                createdCopy = true
            }

            // Final fallback write at Q24 after smooth downscaling
            FileOutputStream(outputFile).use { fos ->
                working.compress(format, 24, fos)
                fos.flush()
            }
        } finally {
            if (createdCopy && working !== bitmap && !working.isRecycled) {
                working.recycle()
            }
        }
    }

    private fun encodePngToTargetBytes(bitmap: Bitmap, targetBytes: Long, outputFile: File) {
        var working = bitmap
        var createdCopy = false
        try {
            repeat(8) {
                val baos = ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val data = baos.toByteArray()
                if (data.size <= targetBytes || max(working.width, working.height) <= 240) {
                    FileOutputStream(outputFile).use {
                        it.write(data)
                        it.flush()
                    }
                    return
                }
                val nextW = (working.width * 0.78f).roundToInt().coerceAtLeast(160)
                val nextH = (working.height * 0.78f).roundToInt().coerceAtLeast(160)
                val scaled = Bitmap.createScaledBitmap(working, nextW, nextH, true)
                if (createdCopy && working !== bitmap && !working.isRecycled) {
                    working.recycle()
                }
                working = scaled
                createdCopy = true
            }
            FileOutputStream(outputFile).use { fos ->
                working.compress(Bitmap.CompressFormat.PNG, 100, fos)
                fos.flush()
            }
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

    private fun applyExifRotation(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val norm = ((rotationDegrees % 360) + 360) % 360
        if (norm == 0) return bitmap
        val matrix = Matrix().apply { postRotate(norm.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
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
        var inSampleSize = 1
        if (width <= 0 || height <= 0) return 1
        var halfW = width
        var halfH = height
        while (max(halfW, halfH) > maxDim * 2) {
            inSampleSize *= 2
            halfW = width / inSampleSize
            halfH = height / inSampleSize
        }
        return inSampleSize.coerceAtLeast(1)
    }

    companion object {
        private const val MAX_SAFE_RASTER_DIM = 4096
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
