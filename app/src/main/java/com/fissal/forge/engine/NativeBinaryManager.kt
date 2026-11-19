package com.fissal.forge.engine

import android.content.Context
import android.util.Log
import com.fissal.forge.R
import com.fissal.forge.domain.logic.AppReleaseComparator
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe singleton bootstrapper for yt-dlp, Python runtime, FFmpeg, FFprobe, and aria2c.
 */
class NativeBinaryManager(private val appContext: Context) {

    private val initMutex = Mutex()
    private val updateMutex = Mutex()
    private val activeUpdateDeferred = AtomicReference<CompletableDeferred<Unit>?>(null)

    @Volatile
    private var initialized = false

    @Volatile
    var isAria2cAvailable: Boolean = false
        private set

    private val _ytDlpVersionFlow = MutableStateFlow(readInitialVersionLabel())
    val ytDlpVersionFlow: StateFlow<String> = _ytDlpVersionFlow.asStateFlow()

    private val _isUpdatingYtDlpFlow = MutableStateFlow(false)
    val isUpdatingYtDlpFlow: StateFlow<Boolean> = _isUpdatingYtDlpFlow.asStateFlow()

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
                isAria2cAvailable = runCatching {
                    Aria2c.getInstance().init(appContext)
                    true
                }.onFailure {
                    Log.w(TAG, "Optional aria2c init skipped: ${it.message}")
                }.getOrDefault(false)
                syncBundledYtDlpIfNewer()
                initialized = true
                _ytDlpVersionFlow.value = getYtDlpVersionName()
            }
        }
    }

    /**
     * Ensures binaries are initialized and waits briefly if an OTA yt-dlp update is actively
     * replacing the binary file on disk so metadata lookups and downloads never race file writes.
     */
    suspend fun awaitReadyForExecution() {
        ensureInitialized()
        val pending = activeUpdateDeferred.get() ?: return
        withTimeoutOrNull(UPDATE_WAIT_TIMEOUT_MS) {
            pending.await()
        }
    }

    fun getYtDlpVersionName(): String {
        val prefs = appContext.getSharedPreferences(YTDLP_PREFS_NAME, Context.MODE_PRIVATE)
        val installedVersionName = YoutubeDL.getInstance().versionName(appContext)?.takeIf { it.isNotBlank() }
        val installedVersion = YoutubeDL.getInstance().version(appContext)?.takeIf { it.isNotBlank() }
        val bundledTag = readBundledYtDlpTag().takeIf { it.isNotBlank() }
        val syncedTag = prefs.getString(PREF_BUNDLED_TAG, null)?.takeIf { it.isNotBlank() }
        return installedVersionName
            ?: installedVersion
            ?: bundledTag
            ?: syncedTag
            ?: "Bundled (0.18.1)"
    }

    suspend fun updateYtDlp(nightly: Boolean): YoutubeDL.UpdateStatus? {
        ensureInitialized()
        return updateMutex.withLock {
            val gate = CompletableDeferred<Unit>()
            activeUpdateDeferred.set(gate)
            _isUpdatingYtDlpFlow.value = true
            try {
                withContext(Dispatchers.IO) {
                    val channel = if (nightly) {
                        YoutubeDL.UpdateChannel.NIGHTLY
                    } else {
                        YoutubeDL.UpdateChannel.STABLE
                    }
                    YoutubeDL.getInstance().updateYoutubeDL(appContext, channel)
                }
            } finally {
                _ytDlpVersionFlow.value = getYtDlpVersionName()
                _isUpdatingYtDlpFlow.value = false
                activeUpdateDeferred.compareAndSet(gate, null)
                gate.complete(Unit)
            }
        }
    }

    private fun readInitialVersionLabel(): String {
        val prefs = appContext.getSharedPreferences(YTDLP_PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_DLP_VERSION_NAME, null)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(PREF_DLP_VERSION, null)?.takeIf { it.isNotBlank() }
        val bundled = readBundledYtDlpTag().takeIf { it.isNotBlank() }
        return stored ?: bundled ?: "Bundled (0.18.1)"
    }

    private fun readBundledYtDlpTag(): String {
        return runCatching {
            appContext.getString(R.string.bundled_ytdlp_version).trim()
        }.getOrDefault("")
    }

    private fun syncBundledYtDlpIfNewer() {
        val bundledTag = readBundledYtDlpTag()
        if (bundledTag.isBlank()) return

        val prefs = appContext.getSharedPreferences(YTDLP_PREFS_NAME, Context.MODE_PRIVATE)
        val installedTag = prefs.getString(PREF_DLP_VERSION, null)?.trim()?.takeIf { it.isNotEmpty() }
        val lastSyncedBundled = prefs.getString(PREF_BUNDLED_TAG, null)?.trim()?.takeIf { it.isNotEmpty() }

        val ytDlpDir = File(File(appContext.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName).apply {
            if (!exists()) mkdirs()
        }
        val targetBin = File(ytDlpDir, YoutubeDL.ytdlpBin)
        val binMissingOrSmall = !targetBin.exists() || targetBin.length() < MIN_YTDLP_BINARY_BYTES

        if (!binMissingOrSmall && lastSyncedBundled == bundledTag) {
            if (installedTag == null) {
                prefs.edit()
                    .putString(PREF_DLP_VERSION, bundledTag)
                    .putString(PREF_DLP_VERSION_NAME, bundledTag)
                    .commit()
            }
            return
        }

        // Preserve user's OTA-updated yt-dlp if it is strictly newer than the APK's bundled build
        val installedIsStrictlyNewer = installedTag != null &&
            AppReleaseComparator.isRemoteNewer(currentVersion = bundledTag, remoteTagOrVersion = installedTag)

        if (installedIsStrictlyNewer && !binMissingOrSmall) {
            prefs.edit().putString(PREF_BUNDLED_TAG, bundledTag).commit()
            return
        }

        val tmpBin = File(ytDlpDir, "${YoutubeDL.ytdlpBin}.bundled.tmp")
        runCatching {
            appContext.resources.openRawResource(com.yausername.youtubedl_android.R.raw.ytdlp).use { input ->
                FileOutputStream(tmpBin).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            if (tmpBin.length() < MIN_YTDLP_BINARY_BYTES) {
                tmpBin.delete()
                return
            }
            if (!tmpBin.renameTo(targetBin)) {
                tmpBin.copyTo(targetBin, overwrite = true)
                tmpBin.delete()
            }
            prefs.edit()
                .putString(PREF_DLP_VERSION, bundledTag)
                .putString(PREF_DLP_VERSION_NAME, bundledTag)
                .putString(PREF_BUNDLED_TAG, bundledTag)
                .commit()
            Log.i(TAG, "Synced bundled yt-dlp $bundledTag")
        }.onFailure { err ->
            runCatching { tmpBin.delete() }
            Log.w(TAG, "Failed to sync bundled yt-dlp: ${err.message}")
        }
    }

    companion object {
        private const val TAG = "NativeBinaryManager"
        private const val YTDLP_PREFS_NAME = "youtubedl-android"
        private const val PREF_DLP_VERSION = "dlpVersion"
        private const val PREF_DLP_VERSION_NAME = "dlpVersionName"
        private const val PREF_BUNDLED_TAG = "redfur_bundled_ytdlp_tag"
        private const val MIN_YTDLP_BINARY_BYTES = 1_000_000L
        private const val UPDATE_WAIT_TIMEOUT_MS = 15_000L
    }
}
