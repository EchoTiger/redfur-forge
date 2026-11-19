package com.fissal.forge.engine

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.homesoft.photo.libraw.LibRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Inspects local content:// or file:// media and Camera RAW files without risking OOM allocations.
 */
class MediaProbeEngine(
    private val appContext: Context,
    private val binaryManager: NativeBinaryManager
) {

    suspend fun probeUri(uri: Uri): MediaProbeResult = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri)
        val mimeType = resolveMimeType(uri, displayName)
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.US)
            .ifBlank { mimeToExtension(mimeType) }
        val fileSize = resolveFileSize(uri)
        val kind = classifyKind(extension, mimeType)

        when (kind) {
            MediaKind.CAMERA_RAW -> probeCameraRaw(uri, displayName, mimeType, extension, fileSize)
            MediaKind.IMAGE -> probeRasterImage(uri, displayName, mimeType, extension, fileSize)
            MediaKind.VIDEO, MediaKind.AUDIO -> probeVideoOrAudio(uri, displayName, mimeType, extension, fileSize, kind)
            else -> MediaProbeResult(
                uriString = uri.toString(),
                displayName = displayName,
                mimeType = mimeType,
                extension = extension,
                mediaKind = kind,
                fileSizeBytes = fileSize
            )
        }
    }

    suspend fun probeLocalFile(file: File, sourceUriLabel: String? = null): MediaProbeResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.fromFile(file)
            val ext = file.extension.lowercase(Locale.US)
            val mime = extensionToMime(ext)
            val kind = classifyKind(ext, mime)
            when (kind) {
                MediaKind.CAMERA_RAW -> probeCameraRaw(uri, file.name, mime, ext, file.length())
                MediaKind.IMAGE -> probeRasterImage(uri, file.name, mime, ext, file.length())
                MediaKind.VIDEO, MediaKind.AUDIO -> probeLocalFileWithFfprobe(
                    file = file,
                    uriString = sourceUriLabel ?: uri.toString(),
                    displayName = file.name,
                    mimeType = mime,
                    extension = ext,
                    initialKind = kind
                )
                else -> MediaProbeResult(
                    uriString = sourceUriLabel ?: uri.toString(),
                    displayName = file.name,
                    mimeType = mime,
                    extension = ext,
                    mediaKind = kind,
                    fileSizeBytes = file.length()
                )
            }
        }

    suspend fun stageUriToTempFile(uri: Uri, targetDir: File, prefix: String = "input"): File =
        withContext(Dispatchers.IO) {
            if (!targetDir.exists()) targetDir.mkdirs()
            if (uri.scheme == "file" && uri.path != null) {
                val direct = File(uri.path!!)
                if (direct.exists() && direct.canRead()) return@withContext direct
            }

            val displayName = resolveDisplayName(uri)
            val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
                .ifBlank { mimeToExtension(resolveMimeType(uri, displayName)) }
                .ifBlank { "bin" }

            val estimatedSize = resolveFileSize(uri)
            ensureStorageAvailable(targetDir, (estimatedSize * 2L) + MIN_FREE_HEADROOM_BYTES)

            val safeBase = displayName.substringBeforeLast('.')
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .take(48)
                .ifBlank { prefix }
            val stagedFile = File(targetDir, "${prefix}_${System.currentTimeMillis()}_${safeBase}.$ext")

            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(stagedFile).use { output ->
                        val buffer = ByteArray(STREAM_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                } ?: throw IOException("Unable to open input stream for $uri")
            } catch (t: Throwable) {
                runCatching { stagedFile.delete() }
                throw t
            }

            stagedFile
        }

    fun ensureStorageAvailable(dir: File, requiredBytes: Long) {
        if (!dir.exists()) dir.mkdirs()
        val stat = StatFs(dir.absolutePath)
        val available = stat.availableBytes
        if (available < requiredBytes) {
            val needMb = requiredBytes / (1024L * 1024L)
            val availMb = available / (1024L * 1024L)
            throw IOException("Insufficient cache storage: need ~${needMb}MB, only ${availMb}MB free")
        }
    }

    private fun probeCameraRaw(
        uri: Uri,
        displayName: String,
        mimeType: String,
        extension: String,
        fileSize: Long
    ): MediaProbeResult {
        var width = 0
        var height = 0
        var rotation = 0

        runCatching {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                LibRaw().use { raw ->
                    if (raw.openFd(pfd.fd) == 0) {
                        width = raw.width
                        height = raw.height
                        rotation = LibRaw.toDegrees(raw.orientation)
                    }
                }
            }
        }

        // Also check ExifInterface for camera model if accessible
        var cameraModel: String? = null
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                cameraModel = listOfNotNull(make, model).joinToString(" ").ifBlank { null }
                if (rotation == 0) {
                    rotation = exifOrientationToDegrees(
                        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    )
                }
            }
        }

        return MediaProbeResult(
            uriString = uri.toString(),
            displayName = displayName,
            mimeType = mimeType,
            extension = extension,
            mediaKind = MediaKind.CAMERA_RAW,
            fileSizeBytes = fileSize,
            rawWidth = width,
            rawHeight = height,
            rotationDegrees = rotation,
            cameraModel = cameraModel
        )
    }

    private fun probeRasterImage(
        uri: Uri,
        displayName: String,
        mimeType: String,
        extension: String,
        fileSize: Long
    ): MediaProbeResult {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
        var rotation = 0
        var cameraModel: String? = null
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                rotation = exifOrientationToDegrees(
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                )
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                cameraModel = listOfNotNull(make, model).joinToString(" ").ifBlank { null }
            }
        }

        return MediaProbeResult(
            uriString = uri.toString(),
            displayName = displayName,
            mimeType = mimeType,
            extension = extension,
            mediaKind = MediaKind.IMAGE,
            fileSizeBytes = fileSize,
            rawWidth = opts.outWidth.coerceAtLeast(0),
            rawHeight = opts.outHeight.coerceAtLeast(0),
            rotationDegrees = rotation,
            cameraModel = cameraModel
        )
    }

    private suspend fun probeVideoOrAudio(
        uri: Uri,
        displayName: String,
        mimeType: String,
        extension: String,
        fileSize: Long,
        initialKind: MediaKind
    ): MediaProbeResult {
        // Fast metadata probe via MediaMetadataRetriever first; enrich via ffprobe if local or staged
        if (uri.scheme == "file" && uri.path != null) {
            val file = File(uri.path!!)
            if (file.exists()) {
                return probeLocalFileWithFfprobe(file, uri.toString(), displayName, mimeType, extension, initialKind)
            }
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val resolvedKind = when {
                hasVideo || width > 0 -> MediaKind.VIDEO
                hasAudio || initialKind == MediaKind.AUDIO -> MediaKind.AUDIO
                else -> initialKind
            }

            MediaProbeResult(
                uriString = uri.toString(),
                displayName = displayName,
                mimeType = mimeType,
                extension = extension,
                mediaKind = resolvedKind,
                fileSizeBytes = fileSize,
                rawWidth = width,
                rawHeight = height,
                rotationDegrees = rot,
                durationMs = durationMs,
                hasAudio = hasAudio,
                bitrateBps = bitrate
            )
        } catch (_: Throwable) {
            // Fallback to staging + ffprobe for containers MediaMetadataRetriever cannot read (e.g. certain MKV/WebM/OPUS)
            val tempDir = File(appContext.cacheDir, "probe_staging")
            val staged = try {
                stageUriToTempFile(uri, tempDir, "probe")
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
            try {
                if (staged != null) {
                    probeLocalFileWithFfprobe(staged, uri.toString(), displayName, mimeType, extension, initialKind)
                } else {
                    MediaProbeResult(uri.toString(), displayName, mimeType, extension, initialKind, fileSize)
                }
            } finally {
                staged?.delete()
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun probeLocalFileWithFfprobe(
        file: File,
        uriString: String,
        displayName: String,
        mimeType: String,
        extension: String,
        initialKind: MediaKind
    ): MediaProbeResult {
        binaryManager.ensureInitialized()
        val ffprobe = binaryManager.ffprobeExecutable
        if (!ffprobe.exists()) {
            return MediaProbeResult(uriString, displayName, mimeType, extension, initialKind, file.length())
        }

        val cmd = listOf(
            ffprobe.absolutePath,
            "-v", "quiet",
            "-print_format", "json",
            "-show_format",
            "-show_streams",
            file.absolutePath
        )
        val pb = ProcessBuilder(cmd)
        pb.environment()["LD_LIBRARY_PATH"] = binaryManager.ldLibraryPath

        val proc = runCatching { pb.start() }.getOrNull()
        val cancelHandle = if (proc != null) {
            currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    proc.destroy()
                    if (proc.isAlive) proc.destroyForcibly()
                }
            }
        } else {
            null
        }
        val jsonText = if (proc != null) {
            try {
                withTimeoutOrNull(10_000L) {
                    coroutineScope {
                        val stderrDrain = async(Dispatchers.IO) {
                            val drainBuf = ByteArray(4096)
                            runCatching {
                                proc.errorStream.use { err ->
                                    while (err.read(drainBuf) != -1) {
                                        // Drain stderr concurrently so ffprobe never blocks on full pipe buffer
                                    }
                                }
                            }
                        }
                        val out = proc.inputStream.bufferedReader().use { it.readText() }
                        currentCoroutineContext().ensureActive()
                        proc.waitFor()
                        stderrDrain.await()
                        out
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            } finally {
                cancelHandle?.dispose()
                runCatching { proc.inputStream.close() }
                runCatching { proc.outputStream.close() }
                runCatching { proc.errorStream.close() }
                if (proc.isAlive) proc.destroyForcibly()
            }
        } else {
            null
        }

        if (jsonText.isNullOrBlank()) {
            return MediaProbeResult(uriString, displayName, mimeType, extension, initialKind, file.length())
        }

        return runCatching {
            val root = JSONObject(jsonText)
            val format = root.optJSONObject("format")
            val durationMs = ((format?.optString("duration")?.toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
            val bitrate = format?.optString("bit_rate")?.toLongOrNull() ?: 0L
            val streams = root.optJSONArray("streams")

            var vCodec: String? = null
            var aCodec: String? = null
            var width = 0
            var height = 0
            var rotation = 0
            var isHdr = false
            var hasAudio = false

            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    when (s.optString("codec_type")) {
                        "video" -> if (vCodec == null && s.optJSONObject("disposition")?.optInt("attached_pic") != 1) {
                            vCodec = s.optString("codec_name").ifBlank { null }
                            width = s.optInt("width", 0)
                            height = s.optInt("height", 0)
                            val tagsRot = s.optJSONObject("tags")?.optString("rotate")?.toIntOrNull()
                            val sideRot = extractSideDataRotation(s)
                            val rawRot = tagsRot ?: sideRot ?: 0
                            rotation = ((rawRot % 360) + 360) % 360
                            val transfer = s.optString("color_transfer").lowercase(Locale.US)
                            isHdr = transfer.contains("smpte2084") || transfer.contains("arib-std-b67")
                        }
                        "audio" -> {
                            hasAudio = true
                            if (aCodec == null) aCodec = s.optString("codec_name").ifBlank { null }
                        }
                    }
                }
            }

            val resolvedKind = when {
                vCodec != null && width > 0 -> MediaKind.VIDEO
                hasAudio -> MediaKind.AUDIO
                else -> initialKind
            }

            MediaProbeResult(
                uriString = uriString,
                displayName = displayName,
                mimeType = mimeType,
                extension = extension,
                mediaKind = resolvedKind,
                fileSizeBytes = file.length(),
                rawWidth = width,
                rawHeight = height,
                rotationDegrees = rotation,
                durationMs = durationMs,
                videoCodec = vCodec,
                audioCodec = aCodec,
                hasAudio = hasAudio,
                isHdr = isHdr,
                bitrateBps = bitrate
            )
        }.getOrElse {
            MediaProbeResult(uriString, displayName, mimeType, extension, initialKind, file.length())
        }
    }

    private fun extractSideDataRotation(streamObj: JSONObject): Int? {
        val sideList = streamObj.optJSONArray("side_data_list") ?: return null
        for (i in 0 until sideList.length()) {
            val item = sideList.optJSONObject(i) ?: continue
            if (item.has("rotation")) {
                return item.optInt("rotation")
            }
        }
        return null
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path ?: "media").name
        }
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "media_${System.currentTimeMillis()}" }
            ?: "media_${System.currentTimeMillis()}"
    }

    private fun resolveFileSize(uri: Uri): Long {
        if (uri.scheme == "file" && uri.path != null) {
            return File(uri.path!!).length()
        }
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        return cursor.getLong(idx)
                    }
                }
            }
        return appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
    }

    private fun resolveMimeType(uri: Uri, displayName: String): String {
        val resolverType = runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
        if (!resolverType.isNullOrBlank() && resolverType != "application/octet-stream") {
            return resolverType
        }
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        return extensionToMime(ext)
    }

    private fun classifyKind(extension: String, mimeType: String): MediaKind {
        val ext = extension.lowercase(Locale.US)
        val mime = mimeType.lowercase(Locale.US)
        return when {
            ext in RAW_EXTENSIONS -> MediaKind.CAMERA_RAW
            ext in VIDEO_EXTENSIONS || mime.startsWith("video/") -> MediaKind.VIDEO
            ext in AUDIO_EXTENSIONS || mime.startsWith("audio/") -> MediaKind.AUDIO
            ext in IMAGE_EXTENSIONS || mime.startsWith("image/") -> MediaKind.IMAGE
            else -> MediaKind.UNKNOWN
        }
    }

    private fun exifOrientationToDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    private fun mimeToExtension(mime: String): String = when (mime.lowercase(Locale.US)) {
        "video/mp4" -> "mp4"
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/x-adobe-dng" -> "dng"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/ogg" -> "opus"
        "audio/flac" -> "flac"
        "audio/wav" -> "wav"
        else -> ""
    }

    private fun extensionToMime(ext: String): String = when (ext.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "dng" -> "image/x-adobe-dng"
        in RAW_EXTENSIONS -> "image/x-raw"
        else -> "application/octet-stream"
    }

    companion object {
        private const val STREAM_BUFFER_BYTES = 256 * 1024
        private const val MIN_FREE_HEADROOM_BYTES = 64L * 1024L * 1024L

        val RAW_EXTENSIONS = setOf(
            "dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2",
            "raf", "rw2", "orf", "pef", "3fr", "iiq", "erf", "kdc", "mos", "raw"
        )
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "mts", "3gp", "flv")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "wma", "alac")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")
    }
}
