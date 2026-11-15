package com.fissal.forge.engine

import android.content.Context
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thread-safe singleton bootstrapper for yt-dlp, Python runtime, FFmpeg, FFprobe, and aria2c.
 */
class NativeBinaryManager(private val appContext: Context) {

    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    val nativeBinDir: File
        get() = File(appContext.applicationInfo.nativeLibraryDir)

    val ffmpegExecutable: File
        get() = File(nativeBinDir, "libffmpeg.so")

    val ffprobeExecutable: File
        get() = File(nativeBinDir, "libffprobe.so")

    val ldLibraryPath: String
        get() {
            val packagesDir = File(File(appContext.noBackupFilesDir, YoutubeDL.baseName), "packages")
            return listOf("ffmpeg", "python", "aria2c")
                .joinToString(":") { File(packagesDir, "$it/usr/lib").absolutePath }
        }

    suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
                runCatching { Aria2c.getInstance().init(appContext) }
                    .onFailure { Log.w(TAG, "Optional aria2c init skipped: ${it.message}") }
                initialized = true
            }
        }
    }

    fun getYtDlpVersionName(): String {
        return YoutubeDL.getInstance().versionName(appContext)
            ?: YoutubeDL.getInstance().version(appContext)
            ?: "Bundled (0.18.1)"
    }

    suspend fun updateYtDlp(nightly: Boolean): YoutubeDL.UpdateStatus? {
        ensureInitialized()
        return withContext(Dispatchers.IO) {
            val channel = if (nightly) {
                YoutubeDL.UpdateChannel.NIGHTLY
            } else {
                YoutubeDL.UpdateChannel.STABLE
            }
            YoutubeDL.getInstance().updateYoutubeDL(appContext, channel)
        }
    }

    companion object {
        private const val TAG = "NativeBinaryManager"
    }
}
