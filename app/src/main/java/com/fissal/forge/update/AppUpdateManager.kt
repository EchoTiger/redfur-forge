package com.fissal.forge.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.fissal.forge.BuildConfig
import com.fissal.forge.domain.logic.AppReleaseComparator
import com.fissal.forge.domain.logic.GitHubReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

enum class AppUpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    NEEDS_INSTALL_PERMISSION,
    ERROR
}

data class AppUpdateState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val release: GitHubReleaseInfo? = null,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val readyApkPath: String? = null,
    val errorMessage: String? = null,
    val autoInstallWhenDownloaded: Boolean = false
)

/**
 * Checks public GitHub Releases for newer Redfur Forge APKs matching the device's ABI,
 * streams downloads with live progress + SHA-256 digest verification, and hands off
 * cleanly to Android PackageInstaller via ForgeFileProvider.
 */
class AppUpdateManager(private val appContext: Context) {

    sealed interface InstallAction {
        data class LaunchInstaller(val intent: Intent) : InstallAction
        data class RequestUnknownSourcesPermission(val intent: Intent) : InstallAction
        data class MissingApk(val reason: String) : InstallAction
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    val updatesDir: File
        get() = File(appContext.cacheDir, "updates").apply { mkdirs() }

    fun checkForUpdates(autoDownload: Boolean, userInitiated: Boolean) {
        val currentPhase = _state.value.phase
        if (currentPhase == AppUpdatePhase.DOWNLOADING) return
        if (checkJob?.isActive == true) return

        checkJob = scope.launch {
            _state.update {
                it.copy(
                    phase = AppUpdatePhase.CHECKING,
                    errorMessage = null
                )
            }

            runCatching {
                val jsonBody = fetchLatestReleaseJson()
                val release = AppReleaseComparator.parseLatestReleaseJson(
                    rawJson = jsonBody,
                    supportedAbis = Build.SUPPORTED_ABIS.toList()
                ) ?: throw IOException("No published GitHub release found yet")

                if (!AppReleaseComparator.isRemoteNewer(BuildConfig.VERSION_NAME, release.versionName)) {
                    clearStaleApks(exceptName = null)
                    _state.value = AppUpdateState(
                        phase = AppUpdatePhase.UP_TO_DATE,
                        currentVersion = BuildConfig.VERSION_NAME,
                        release = release
                    )
                    return@runCatching
                }

                val asset = release.selectedAsset
                    ?: throw IOException("Release ${release.tagName} has no downloadable APK asset")

                val safeName = sanitizeApkFileName(asset.name)
                val cachedApk = File(updatesDir, safeName)
                val cachedValid = cachedApk.exists() &&
                    (asset.sizeBytes <= 0L || cachedApk.length() == asset.sizeBytes) &&
                    verifySha256(cachedApk, release.expectedSha256)

                if (cachedValid) {
                    _state.value = AppUpdateState(
                        phase = AppUpdatePhase.READY_TO_INSTALL,
                        currentVersion = BuildConfig.VERSION_NAME,
                        release = release,
                        progress = 1f,
                        downloadedBytes = cachedApk.length(),
                        totalBytes = cachedApk.length(),
                        readyApkPath = cachedApk.absolutePath,
                        autoInstallWhenDownloaded = false
                    )
                } else {
                    _state.value = AppUpdateState(
                        phase = AppUpdatePhase.AVAILABLE,
                        currentVersion = BuildConfig.VERSION_NAME,
                        release = release,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = asset.sizeBytes,
                        readyApkPath = null,
                        autoInstallWhenDownloaded = false
                    )
                    if (autoDownload) {
                        startDownloadInternal(release = release, autoInstallAfter = false)
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                if (userInitiated) {
                    _state.update {
                        it.copy(
                            phase = AppUpdatePhase.ERROR,
                            errorMessage = err.message ?: "Update check failed"
                        )
                    }
                } else {
                    // Silent background launch check shouldn't flash errors offline
                    _state.update {
                        it.copy(phase = AppUpdatePhase.IDLE, errorMessage = null)
                    }
                }
            }
        }
    }

    fun downloadUpdate(autoInstallAfter: Boolean) {
        val current = _state.value
        val release = current.release ?: run {
            checkForUpdates(autoDownload = true, userInitiated = true)
            return
        }

        if (current.phase == AppUpdatePhase.READY_TO_INSTALL ||
            current.phase == AppUpdatePhase.NEEDS_INSTALL_PERMISSION
        ) {
            if (autoInstallAfter) {
                _state.update { it.copy(autoInstallWhenDownloaded = true) }
            }
            return
        }

        if (downloadJob?.isActive == true) {
            if (autoInstallAfter) {
                _state.update { it.copy(autoInstallWhenDownloaded = true) }
            }
            return
        }

        downloadJob = scope.launch {
            startDownloadInternal(release = release, autoInstallAfter = autoInstallAfter)
        }
    }

    private suspend fun startDownloadInternal(
        release: GitHubReleaseInfo,
        autoInstallAfter: Boolean
    ) {
        val asset = release.selectedAsset ?: run {
            _state.update {
                it.copy(phase = AppUpdatePhase.ERROR, errorMessage = "Release has no matching APK asset")
            }
            return
        }

        val targetName = sanitizeApkFileName(asset.name)
        val finalFile = File(updatesDir, targetName)
        val partFile = File(updatesDir, "$targetName.part")
        partFile.delete()

        _state.update {
            it.copy(
                phase = AppUpdatePhase.DOWNLOADING,
                release = release,
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = asset.sizeBytes,
                errorMessage = null,
                autoInstallWhenDownloaded = autoInstallAfter
            )
        }

        var conn: HttpURLConnection? = null
        try {
            conn = openRedirectAwareConnection(asset.downloadUrl, acceptHeader = "*/*")
            val statusCode = conn.responseCode
            if (statusCode !in 200..299) {
                throw IOException("GitHub asset download failed (HTTP $statusCode)")
            }

            val headerLen = conn.contentLengthLong
            val expectedTotal = when {
                asset.sizeBytes > 0L -> asset.sizeBytes
                headerLen > 0L -> headerLen
                else -> 0L
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastEmitMs = 0L
            val buffer = ByteArray(32 * 1024)

            conn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 100L) {
                            lastEmitMs = now
                            val frac = if (expectedTotal > 0L) {
                                (downloaded.toFloat() / expectedTotal.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            _state.update {
                                it.copy(
                                    phase = AppUpdatePhase.DOWNLOADING,
                                    progress = frac,
                                    downloadedBytes = downloaded,
                                    totalBytes = expectedTotal
                                )
                            }
                        }
                    }
                    output.fd.sync()
                }
            }

            if (asset.sizeBytes > 0L && downloaded != asset.sizeBytes) {
                throw IOException("Incomplete download ($downloaded / ${asset.sizeBytes} bytes)")
            }

            val expectedSha = release.expectedSha256
            if (!expectedSha.isNullOrBlank()) {
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    throw IOException("APK SHA-256 verification failed")
                }
            }

            clearStaleApks(exceptName = targetName)
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            _state.update {
                it.copy(
                    phase = AppUpdatePhase.READY_TO_INSTALL,
                    progress = 1f,
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    readyApkPath = finalFile.absolutePath,
                    errorMessage = null
                )
            }
        } catch (ce: CancellationException) {
            partFile.delete()
            _state.update {
                it.copy(
                    phase = if (it.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
                    progress = 0f,
                    downloadedBytes = 0L,
                    autoInstallWhenDownloaded = false
                )
            }
            throw ce
        } catch (err: Throwable) {
            partFile.delete()
            _state.update {
                it.copy(
                    phase = AppUpdatePhase.ERROR,
                    progress = 0f,
                    downloadedBytes = 0L,
                    errorMessage = err.message ?: "Download failed",
                    autoInstallWhenDownloaded = false
                )
            }
        } finally {
            conn?.disconnect()
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.update {
            it.copy(
                phase = if (it.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
                progress = 0f,
                downloadedBytes = 0L,
                autoInstallWhenDownloaded = false
            )
        }
    }

    fun consumeAutoInstallFlag() {
        _state.update { it.copy(autoInstallWhenDownloaded = false) }
    }

    fun prepareInstallIntent(): InstallAction {
        val apkPath = _state.value.readyApkPath
        val apkFile = apkPath?.let { File(it) }
        if (apkFile == null || !apkFile.exists()) {
            _state.update {
                it.copy(
                    phase = if (it.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
                    readyApkPath = null
                )
            }
            return InstallAction.MissingApk("Downloaded APK was cleared from cache — tap Update to re-download")
        }

        if (!appContext.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(phase = AppUpdatePhase.NEEDS_INSTALL_PERMISSION) }
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            )
            return InstallAction.RequestUnknownSourcesPermission(permIntent)
        }

        _state.update { it.copy(phase = AppUpdatePhase.READY_TO_INSTALL) }
        val apkUri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return InstallAction.LaunchInstaller(installIntent)
    }

    fun clearCachedApks(): Long {
        if (_state.value.phase == AppUpdatePhase.DOWNLOADING) return 0L
        var freed = 0L
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.endsWith(".part")) {
                val len = file.length()
                if (file.delete()) freed += len
            }
        }
        if (_state.value.phase == AppUpdatePhase.READY_TO_INSTALL ||
            _state.value.phase == AppUpdatePhase.NEEDS_INSTALL_PERMISSION
        ) {
            _state.update {
                it.copy(
                    phase = if (it.release != null) AppUpdatePhase.AVAILABLE else AppUpdatePhase.IDLE,
                    readyApkPath = null,
                    downloadedBytes = 0L,
                    progress = 0f
                )
            }
        }
        return freed
    }

    private fun clearStaleApks(exceptName: String?) {
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != exceptName && !file.name.endsWith(".part")) {
                file.delete()
            }
        }
    }

    private fun fetchLatestReleaseJson(): String {
        val conn = openRedirectAwareConnection(
            urlString = LATEST_RELEASE_API,
            acceptHeader = "application/vnd.github+json"
        )
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("GitHub Releases API returned HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openRedirectAwareConnection(
        urlString: String,
        acceptHeader: String?
    ): HttpURLConnection {
        var currentUrl = urlString
        for (hop in 0 until MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "RedfurForge/${BuildConfig.VERSION_NAME} (Android)")
                if (acceptHeader != null && currentUrl.contains("api.github.com", ignoreCase = true)) {
                    setRequestProperty("Accept", acceptHeader)
                }
            }

            val code = conn.responseCode
            if (code in REDIRECT_CODES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("HTTP $code redirect missing Location header")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IOException("Too many redirects while fetching $urlString")
    }

    private fun verifySha256(file: File, expectedHex: String?): Boolean {
        if (expectedHex.isNullOrBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex.lowercase(Locale.US), ignoreCase = true)
    }

    private fun sanitizeApkFileName(raw: String): String {
        val base = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (base.lowercase(Locale.US).endsWith(".apk")) base else "$base.apk"
    }

    companion object {
        const val GITHUB_OWNER = "EchoTiger"
        const val GITHUB_REPO = "redfur-forge"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        const val RELEASES_WEB_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"

        private const val MAX_REDIRECTS = 6
        private val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308
        )
    }
}
