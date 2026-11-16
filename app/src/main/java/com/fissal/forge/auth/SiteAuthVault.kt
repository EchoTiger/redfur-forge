package com.fissal.forge.auth

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ConnectionHealthStatus {
    CONNECTED,
    EXPIRED_OR_NEEDS_REAUTH
}

data class ConnectedServiceSummary(
    val serviceId: String,
    val service: ServiceDescriptor,
    val accountBadge: String?,
    val cookieCount: Int,
    val status: ConnectionHealthStatus,
    val statusMessage: String,
    val savedAtMs: Long,
    val lastCheckedAtMs: Long?
)

/**
 * Android Keystore-backed encrypted vault for site session cookies and matched User-Agent headers.
 * Plaintext cookies are never written to SharedPreferences, Room, cache directories, exports, or logs,
 * and materialized Netscape cookie files exist strictly under `filesDir/auth_tmp/` with `0600` mode
 * for the duration of a single yt-dlp invocation.
 */
class SiteAuthVault(context: Context) {

    @Serializable
    internal data class VaultSessionPayload(
        val serviceId: String,
        val customDomain: String? = null,
        val customLoginUrl: String? = null,
        val userAgent: String = "",
        val cookies: List<NetscapeCookieRecord>,
        val accountBadge: String? = null,
        val savedAtMs: Long = System.currentTimeMillis(),
        val lastCheckedAtMs: Long? = null,
        val lastCheckOk: Boolean = true,
        val lastCheckMessage: String = "Session encrypted locally"
    )

    private val appContext = context.applicationContext
    private val vaultDir = File(appContext.filesDir, "auth_vault")
    private val tmpDir = File(appContext.filesDir, "auth_tmp")
    private val vaultMutex = Mutex()
    private val sweepMutex = Mutex()
    @Volatile
    private var initialSweepDone = false
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _connectionsFlow = MutableStateFlow<List<ConnectedServiceSummary>>(emptyList())
    val connectionsFlow: StateFlow<List<ConnectedServiceSummary>> = _connectionsFlow.asStateFlow()

    init {
        ensurePrivateDirectories()
        _connectionsFlow.value = runCatching { scanSummariesLocked() }.getOrDefault(emptyList())
    }

    suspend fun sweepStaleTempCookieFiles() {
        ensureInitialSweepDone()
    }

    private suspend fun ensureInitialSweepDone() {
        if (initialSweepDone) return
        sweepMutex.withLock {
            if (!initialSweepDone) {
                withContext(Dispatchers.IO + NonCancellable) {
                    ensurePrivateDirectories()
                    if (tmpDir.exists()) {
                        tmpDir.walkBottomUp().forEach { entry ->
                            if (entry.isFile) {
                                wipeAndDelete(entry)
                            } else if (entry != tmpDir) {
                                runCatching { entry.delete() }
                            }
                        }
                    }
                    vaultMutex.withLock {
                        vaultDir.listFiles()?.forEach { file ->
                            when {
                                file.isFile && file.name.endsWith(".tmp") -> wipeAndDelete(file)
                                file.isFile && file.name.endsWith(".enc") -> enforceOwnerOnlyPermissions(file)
                            }
                        }
                    }
                }
                initialSweepDone = true
            }
        }
    }

    suspend fun saveWebViewSession(
        service: ServiceDescriptor,
        userAgent: String,
        extraUrls: List<String> = emptyList()
    ): ConnectedServiceSummary = withContext(Dispatchers.IO) {
        val resolvedService = resolveTargetService(service, extraUrls)
            ?: throw IOException("Enter a valid domain before saving a custom site session.")

        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val captured = LinkedHashMap<Triple<String, String, String>, NetscapeCookieRecord>()
        val wildcardTargets = resolvedService.cookieDomains.filter { it.startsWith(".") }
        val urlTargets = (
            resolvedService.cookieDomains.filterNot { it.startsWith(".") } +
                listOf(resolvedService.loginUrl) +
                extraUrls
            ).map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        for (domainPattern in wildcardTargets) {
            val bare = domainPattern.trimStart('.').lowercase()
            if (bare.isEmpty()) continue
            val primaryProbe = "https://$bare"
            var header = cookieManager.getCookie(primaryProbe)
            if (header.isNullOrBlank()) {
                header = cookieManager.getCookie("https://www.$bare")
            }
            if (!header.isNullOrBlank()) {
                val records = CookieNetscapeSerializer.parseCookieHeader(
                    cookieHeader = header,
                    domain = domainPattern,
                    secure = true
                )
                for (rec in records) {
                    captured[Triple(rec.domain.lowercase(), rec.path, rec.name)] = rec
                }
            }
        }

        for (urlTarget in urlTargets) {
            val host = ServiceCatalog.extractHost(urlTarget) ?: continue
            val isHttps = urlTarget.lowercase().startsWith("https://")
            val header = cookieManager.getCookie(urlTarget)
            if (header.isNullOrBlank()) continue

            val records = CookieNetscapeSerializer.parseCookieHeader(
                cookieHeader = header,
                domain = host,
                secure = isHttps
            )
            for (rec in records) {
                val alreadyCoveredByWildcard = captured.values.any { existing ->
                    existing.name == rec.name &&
                        existing.value == rec.value &&
                        CookieNetscapeSerializer.matchesDomain(
                            cookieDomain = existing.domain,
                            includeSubdomains = existing.includeSubdomains,
                            targetHostOrDomain = host
                        )
                }
                if (!alreadyCoveredByWildcard) {
                    captured[Triple(rec.domain.lowercase(), rec.path, rec.name)] = rec
                }
            }
        }

        val cookies = captured.values.toList()
        if (cookies.isEmpty()) {
            throw IOException(
                "No session cookies captured for ${resolvedService.displayName}. Please complete sign-in first."
            )
        }

        val nowMs = System.currentTimeMillis()
        val hasSession = CookieNetscapeSerializer.hasAuthenticatedSession(cookies, resolvedService, nowMs / 1000L)
        val badge = CookieNetscapeSerializer.extractAccountOrDomainBadge(cookies, resolvedService)
        val payload = VaultSessionPayload(
            serviceId = resolvedService.id,
            customDomain = if (resolvedService.id.startsWith("custom_")) resolvedService.displayName else null,
            customLoginUrl = if (resolvedService.id.startsWith("custom_")) resolvedService.loginUrl else null,
            userAgent = userAgent.trim(),
            cookies = cookies,
            accountBadge = badge,
            savedAtMs = nowMs,
            lastCheckedAtMs = nowMs,
            lastCheckOk = hasSession,
            lastCheckMessage = if (hasSession) {
                "Session encrypted locally"
            } else {
                "Cookies captured — sign-in cookie not detected yet"
            }
        )

        vaultMutex.withLock {
            writePayloadLocked(payload)
            _connectionsFlow.value = scanSummariesLocked()
        }
        toSummary(payload, nowMs / 1000L)
    }

    suspend fun importCookiesTxt(
        service: ServiceDescriptor,
        uri: Uri,
        defaultUserAgent: String
    ): Result<ConnectedServiceSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val rawBytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMPORT_FILE_BYTES) {
                        throw IOException("Cookies file exceeds 4 MB safety limit")
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: throw IOException("Unable to open selected cookies file")

            val rawText = try {
                rawBytes.toString(Charsets.UTF_8)
            } finally {
                rawBytes.fill(0)
            }

            val nowEpochSec = System.currentTimeMillis() / 1000L
            val parsed = CookieNetscapeSerializer.parseNetscapeCookiesTxt(
                rawText = rawText,
                nowEpochSec = nowEpochSec
            )

            if (parsed.cookies.isEmpty()) {
                throw IOException(
                    "No valid active cookies found in file (${parsed.skippedExpiredCount} expired, ${parsed.skippedMalformedCount} invalid lines skipped)."
                )
            }

            val resolvedService = if (service.isCustomDomainTemplate) {
                val primaryDomain = parsed.cookies
                    .asSequence()
                    .map { it.domain.trim().trimStart('.').lowercase() }
                    .filter { it.contains('.') }
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                    ?: throw IOException("Could not infer a valid domain from imported cookies.")
                ServiceCatalog.forCustomDomain(primaryDomain)
                    ?: throw IOException("Imported cookies did not contain a valid custom domain.")
            } else {
                service
            }

            val allowedTargets = buildSet {
                resolvedService.hostMatchers.forEach { add(it.lowercase()) }
                resolvedService.cookieDomains.forEach { entry ->
                    val host = if (entry.contains("://")) {
                        ServiceCatalog.extractHost(entry)
                    } else {
                        entry.trim().trimStart('.').lowercase()
                    }
                    if (!host.isNullOrBlank()) add(host)
                }
            }

            val matchedCookies = parsed.cookies.filter { record ->
                allowedTargets.any { target ->
                    CookieNetscapeSerializer.matchesDomain(
                        cookieDomain = record.domain,
                        includeSubdomains = record.includeSubdomains,
                        targetHostOrDomain = target
                    )
                }
            }

            if (matchedCookies.isEmpty()) {
                throw IOException(
                    "No active ${resolvedService.displayName} cookies matched (${parsed.skippedExpiredCount} expired, ${parsed.skippedMalformedCount} invalid lines skipped)."
                )
            }

            val nowMs = System.currentTimeMillis()
            val hasSession = CookieNetscapeSerializer.hasAuthenticatedSession(
                cookies = matchedCookies,
                service = resolvedService,
                nowEpochSec = nowMs / 1000L
            )
            val badge = CookieNetscapeSerializer.extractAccountOrDomainBadge(matchedCookies, resolvedService)
            val payload = VaultSessionPayload(
                serviceId = resolvedService.id,
                customDomain = if (resolvedService.id.startsWith("custom_")) resolvedService.displayName else null,
                customLoginUrl = if (resolvedService.id.startsWith("custom_")) resolvedService.loginUrl else null,
                userAgent = defaultUserAgent.trim(),
                cookies = matchedCookies,
                accountBadge = badge,
                savedAtMs = nowMs,
                lastCheckedAtMs = nowMs,
                lastCheckOk = hasSession,
                lastCheckMessage = if (hasSession) {
                    "Imported & encrypted locally"
                } else {
                    "Imported cookies — sign-in cookie hint missing"
                }
            )

            vaultMutex.withLock {
                writePayloadLocked(payload)
                _connectionsFlow.value = scanSummariesLocked()
            }
            toSummary(payload, nowMs / 1000L)
        }
    }

    suspend fun checkConnection(serviceId: String): ConnectedServiceSummary? = withContext(Dispatchers.IO) {
        val initialPayload = vaultMutex.withLock {
            readPayloadLocked(serviceId)
        } ?: return@withContext null

        val service = resolveServiceFromPayload(initialPayload)
        val nowMs = System.currentTimeMillis()
        val nowEpochSec = nowMs / 1000L
        val activeCookies = initialPayload.cookies.filter {
            it.expiresEpochSec == 0L || it.expiresEpochSec >= nowEpochSec
        }
        val hasSession = CookieNetscapeSerializer.hasAuthenticatedSession(
            cookies = activeCookies,
            service = service,
            nowEpochSec = nowEpochSec
        )

        val (checkOk, checkMessage) = when {
            activeCookies.isEmpty() -> false to "Saved cookies expired — reconnect required"
            !hasSession -> false to "Session cookie missing or expired — please sign in again"
            else -> probeLoginEndpoint(service, activeCookies, initialPayload.userAgent)
        }

        val updated = vaultMutex.withLock {
            val latest = readPayloadLocked(serviceId) ?: return@withLock null
            val merged = latest.copy(
                lastCheckedAtMs = nowMs,
                lastCheckOk = checkOk,
                lastCheckMessage = checkMessage
            )
            writePayloadLocked(merged)
            _connectionsFlow.value = scanSummariesLocked()
            merged
        } ?: return@withContext null

        toSummary(updated, nowEpochSec)
    }

    suspend fun removeConnection(serviceId: String) = withContext(Dispatchers.IO) {
        val existingPayload = vaultMutex.withLock {
            val payload = readPayloadLocked(serviceId)
            val file = safeFileForService(serviceId)
            if (file.exists()) {
                wipeAndDelete(file)
            }
            _connectionsFlow.value = scanSummariesLocked()
            payload
        }

        val service = existingPayload?.let { resolveServiceFromPayload(it) }
            ?: ServiceCatalog.findById(serviceId)

        runCatching {
            val cookieManager = CookieManager.getInstance()
            val expirySuffix = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
            val cookieDomainsAndNames = existingPayload?.cookies.orEmpty()
            val candidateUrls = buildSet {
                service?.loginUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
                service?.cookieDomains.orEmpty().forEach { entry ->
                    if (entry.startsWith("http://") || entry.startsWith("https://")) {
                        add(entry)
                    } else {
                        val bare = entry.trimStart('.')
                        if (bare.isNotBlank()) add("https://$bare")
                    }
                }
                cookieDomainsAndNames.forEach { rec ->
                    val bare = rec.domain.trimStart('.')
                    if (bare.isNotBlank()) add("https://$bare")
                }
            }

            for (cookie in cookieDomainsAndNames) {
                val cleanPath = cookie.path.ifBlank { "/" }
                val bareDomain = cookie.domain.trimStart('.')
                val targetUrl = "https://$bareDomain"
                val secureAttr = if (cookie.secure ||
                    cookie.name.startsWith("__Secure-", ignoreCase = true) ||
                    cookie.name.startsWith("__Host-", ignoreCase = true) ||
                    targetUrl.startsWith("https://", ignoreCase = true)
                ) {
                    "; Secure"
                } else {
                    ""
                }
                cookieManager.setCookie(targetUrl, "${cookie.name}=; $expirySuffix; Path=$cleanPath")
                cookieManager.setCookie(targetUrl, "${cookie.name}=; $expirySuffix; Path=$cleanPath$secureAttr")
                if (cookie.includeSubdomains || cookie.domain.startsWith(".")) {
                    cookieManager.setCookie(
                        targetUrl,
                        "${cookie.name}=; $expirySuffix; Domain=.$bareDomain; Path=$cleanPath"
                    )
                    cookieManager.setCookie(
                        targetUrl,
                        "${cookie.name}=; $expirySuffix; Domain=.$bareDomain; Path=$cleanPath$secureAttr"
                    )
                }
                candidateUrls.forEach { url ->
                    val urlSecure = if (url.startsWith("https://", ignoreCase = true)) "; Secure" else ""
                    cookieManager.setCookie(url, "${cookie.name}=; $expirySuffix; Path=$cleanPath")
                    if (urlSecure.isNotEmpty()) {
                        cookieManager.setCookie(url, "${cookie.name}=; $expirySuffix; Path=$cleanPath$urlSecure")
                    }
                }
            }
            // Purge any remaining live WebView cookies on the service's domains even if vault file was absent.
            for (url in candidateUrls) {
                val liveHeader = cookieManager.getCookie(url) ?: continue
                val host = ServiceCatalog.extractHost(url) ?: continue
                val urlSecure = if (url.startsWith("https://", ignoreCase = true)) "; Secure" else ""
                val parsed = CookieNetscapeSerializer.parseCookieHeader(liveHeader, host)
                for (liveCookie in parsed) {
                    cookieManager.setCookie(url, "${liveCookie.name}=; $expirySuffix; Path=/")
                    cookieManager.setCookie(url, "${liveCookie.name}=; $expirySuffix; Domain=.$host; Path=/")
                    if (urlSecure.isNotEmpty()) {
                        cookieManager.setCookie(url, "${liveCookie.name}=; $expirySuffix; Path=/$urlSecure")
                        cookieManager.setCookie(url, "${liveCookie.name}=; $expirySuffix; Domain=.$host; Path=/$urlSecure")
                    }
                }
            }
            cookieManager.flush()
        }
    }

    fun connectedCustomDomains(): Set<String> =
        _connectionsFlow.value
            .asSequence()
            .filter { it.serviceId.startsWith("custom_") }
            .map { it.service.displayName }
            .toSet()

    suspend fun <T> withMaterializedCookies(
        url: String,
        explicitConnectionId: String? = null,
        block: suspend (cookieFile: File?, userAgent: String?) -> T
    ): T {
        ensureInitialSweepDone()
        val targetServiceId = withContext(Dispatchers.IO) {
            if (!explicitConnectionId.isNullOrBlank()) {
                explicitConnectionId.trim()
            } else {
                ServiceCatalog.matchUrl(url, connectedCustomDomains())?.id
            }
        }

        if (targetServiceId.isNullOrBlank()) {
            return block(null, null)
        }

        val payload = withContext(Dispatchers.IO) {
            vaultMutex.withLock {
                readPayloadLocked(targetServiceId)
            }
        } ?: return block(null, null)

        val nowEpochSec = System.currentTimeMillis() / 1000L
        val activeCookies = payload.cookies.filter {
            it.expiresEpochSec == 0L || it.expiresEpochSec >= nowEpochSec
        }
        if (activeCookies.isEmpty()) {
            return block(null, null)
        }

        var tempCookieFile: File? = null
        return try {
            tempCookieFile = withContext(Dispatchers.IO + NonCancellable) {
                ensurePrivateDirectories()
                val file = createOwnerOnlyFile(tmpDir, prefix = "cookies_", suffix = ".txt")
                val serialized = CookieNetscapeSerializer.serialize(activeCookies)
                val bytes = serialized.toByteArray(Charsets.UTF_8)
                try {
                    file.writeBytes(bytes)
                    enforceOwnerOnlyPermissions(file)
                } catch (t: Throwable) {
                    wipeAndDelete(file)
                    throw t
                } finally {
                    bytes.fill(0)
                }
                file
            }
            currentCoroutineContext().ensureActive()
            block(tempCookieFile, payload.userAgent.ifBlank { null })
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                tempCookieFile?.let { wipeAndDelete(it) }
            }
        }
    }

    private fun probeLoginEndpoint(
        service: ServiceDescriptor,
        activeCookies: List<NetscapeCookieRecord>,
        userAgent: String
    ): Pair<Boolean, String> {
        val probeUrlString = service.loginUrl.ifBlank {
            service.hostMatchers.firstOrNull()?.let { "https://$it" } ?: return true to "Saved locally"
        }
        val probeUri = runCatching { URI(probeUrlString) }.getOrNull() ?: return true to "Saved locally"
        val probeHost = probeUri.host?.lowercase() ?: return true to "Saved locally"
        val probeScheme = probeUri.scheme?.lowercase() ?: "https"
        val probePath = probeUri.rawPath?.ifEmpty { "/" } ?: "/"

        val cookieHeader = activeCookies
            .asSequence()
            .filter { rec ->
                CookieNetscapeSerializer.matchesDomain(rec.domain, rec.includeSubdomains, probeHost) &&
                    (!rec.secure || probeScheme == "https") &&
                    probePath.startsWith(rec.path.ifEmpty { "/" })
            }
            .joinToString("; ") { "${it.name}=${it.value}" }

        return try {
            val code = executeProbeRequest(probeUrlString, "HEAD", cookieHeader, userAgent).let { status ->
                if (status == 405) {
                    executeProbeRequest(probeUrlString, "GET", cookieHeader, userAgent)
                } else {
                    status
                }
            }
            when (code) {
                in 200..399 -> true to "Session verified (HTTP $code)"
                401 -> false to "Site rejected saved session (HTTP 401) — reconnect required"
                403, 429 -> true to "Cookies active locally (WAF HTTP $code on login check)"
                else -> true to "Session saved locally (probe HTTP $code)"
            }
        } catch (_: IOException) {
            true to "Offline — local session intact"
        }
    }

    private fun executeProbeRequest(
        probeUrl: String,
        method: String,
        cookieHeader: String,
        userAgent: String
    ): Int {
        val conn = (URL(probeUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            if (userAgent.isNotBlank()) {
                setRequestProperty("User-Agent", userAgent)
            }
            if (cookieHeader.isNotBlank()) {
                setRequestProperty("Cookie", cookieHeader)
            }
        }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveTargetService(
        service: ServiceDescriptor,
        extraUrls: List<String>
    ): ServiceDescriptor? {
        if (!service.isCustomDomainTemplate) return service
        val candidate = extraUrls.firstOrNull { it.isNotBlank() } ?: return null
        return ServiceCatalog.forCustomDomain(candidate)
    }

    private fun resolveServiceFromPayload(payload: VaultSessionPayload): ServiceDescriptor {
        ServiceCatalog.findById(payload.serviceId)?.let { return it }
        payload.customLoginUrl?.takeIf { it.isNotBlank() }?.let { url ->
            ServiceCatalog.forCustomDomain(url)?.let { return it }
        }
        payload.customDomain?.let { ServiceCatalog.forCustomDomain(it) }?.let { return it }
        return ServiceDescriptor(
            id = payload.serviceId,
            displayName = payload.customDomain ?: payload.serviceId,
            loginUrl = "",
            hostMatchers = emptySet(),
            cookieDomains = emptyList(),
            requiredSessionCookieHints = emptySet(),
            brandHex = 0xFF607D8BL,
            glyphCode = payload.serviceId.take(2).uppercase().ifEmpty { "WB" }
        )
    }

    private fun toSummary(
        payload: VaultSessionPayload,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L
    ): ConnectedServiceSummary {
        val service = resolveServiceFromPayload(payload)
        val activeCookies = payload.cookies.filter {
            it.expiresEpochSec == 0L || it.expiresEpochSec >= nowEpochSec
        }
        val hasSession = CookieNetscapeSerializer.hasAuthenticatedSession(
            cookies = activeCookies,
            service = service,
            nowEpochSec = nowEpochSec
        )
        val healthy = activeCookies.isNotEmpty() && hasSession && payload.lastCheckOk
        val status = if (healthy) {
            ConnectionHealthStatus.CONNECTED
        } else {
            ConnectionHealthStatus.EXPIRED_OR_NEEDS_REAUTH
        }
        val message = when {
            activeCookies.isEmpty() -> "Saved cookies expired — reconnect required"
            !hasSession && payload.lastCheckOk -> "Session cookie missing or expired — please sign in again"
            else -> payload.lastCheckMessage
        }
        return ConnectedServiceSummary(
            serviceId = payload.serviceId,
            service = service,
            accountBadge = payload.accountBadge
                ?: CookieNetscapeSerializer.extractAccountOrDomainBadge(activeCookies, service),
            cookieCount = activeCookies.size,
            status = status,
            statusMessage = message,
            savedAtMs = payload.savedAtMs,
            lastCheckedAtMs = payload.lastCheckedAtMs
        )
    }

    private fun scanSummariesLocked(): List<ConnectedServiceSummary> {
        ensurePrivateDirectories()
        val files = vaultDir.listFiles { file ->
            file.isFile && file.name.endsWith(".enc")
        } ?: return emptyList()

        val nowEpochSec = System.currentTimeMillis() / 1000L
        val results = mutableListOf<ConnectedServiceSummary>()
        for (file in files) {
            val payload = readPayloadFromFile(file)
            if (payload != null) {
                results += toSummary(payload, nowEpochSec)
            }
        }
        return results.sortedByDescending { it.savedAtMs }
    }

    private fun readPayloadLocked(serviceId: String): VaultSessionPayload? {
        val file = safeFileForService(serviceId)
        if (!file.isFile) return null
        return readPayloadFromFile(file)
    }

    private fun readPayloadFromFile(file: File): VaultSessionPayload? {
        return try {
            val envelope = file.readBytes()
            val plaintext = decryptEnvelope(envelope)
            try {
                val jsonText = plaintext.toString(Charsets.UTF_8)
                json.decodeFromString<VaultSessionPayload>(jsonText)
            } finally {
                plaintext.fill(0)
            }
        } catch (_: AEADBadTagException) {
            wipeAndDelete(file)
            null
        } catch (_: UnrecoverableKeyException) {
            wipeAndDelete(file)
            null
        } catch (_: InvalidKeyException) {
            wipeAndDelete(file)
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: SerializationException) {
            wipeAndDelete(file)
            null
        } catch (_: IllegalArgumentException) {
            wipeAndDelete(file)
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun writePayloadLocked(payload: VaultSessionPayload) {
        ensurePrivateDirectories()
        val targetFile = safeFileForService(payload.serviceId)
        val tempFile = createOwnerOnlyFile(vaultDir, prefix = "${targetFile.nameWithoutExtension}_", suffix = ".tmp")
        val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        try {
            val encrypted = encryptEnvelope(plaintext)
            tempFile.writeBytes(encrypted)
            enforceOwnerOnlyPermissions(tempFile)
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeBytes(encrypted)
                enforceOwnerOnlyPermissions(targetFile)
                wipeAndDelete(tempFile)
            } else {
                enforceOwnerOnlyPermissions(targetFile)
            }
        } finally {
            plaintext.fill(0)
            if (tempFile.exists()) {
                wipeAndDelete(tempFile)
            }
        }
    }

    private fun encryptEnvelope(plaintext: ByteArray): ByteArray {
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        if (iv == null || iv.size !in 12..16) {
            throw GeneralSecurityException("Invalid GCM IV generated by Keystore")
        }
        val ciphertext = cipher.doFinal(plaintext)
        val envelope = ByteArray(2 + iv.size + ciphertext.size)
        envelope[0] = ENVELOPE_VERSION
        envelope[1] = iv.size.toByte()
        System.arraycopy(iv, 0, envelope, 2, iv.size)
        System.arraycopy(ciphertext, 0, envelope, 2 + iv.size, ciphertext.size)
        return envelope
    }

    private fun decryptEnvelope(envelope: ByteArray): ByteArray {
        if (envelope.size < 4 || envelope[0] != ENVELOPE_VERSION) {
            throw IllegalArgumentException("Unsupported vault envelope version")
        }
        val ivLen = envelope[1].toInt() and 0xFF
        if (ivLen !in 12..16 || envelope.size <= 2 + ivLen) {
            throw IllegalArgumentException("Malformed vault envelope IV")
        }
        val iv = envelope.copyOfRange(2, 2 + ivLen)
        val ciphertext = envelope.copyOfRange(2 + ivLen, envelope.size)
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun safeFileForService(serviceId: String): File {
        val normalized = serviceId.trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace("..", "_")
            .takeIf { it.isNotBlank() && it.length <= 96 }
            ?: "svc_${sha256Hex(serviceId).take(24)}"
        val candidate = File(vaultDir, "$normalized.enc")
        val canonicalDir = vaultDir.canonicalPath
        val canonicalTarget = candidate.canonicalPath
        if (!canonicalTarget.startsWith(canonicalDir + File.separator)) {
            throw SecurityException("Invalid service storage path")
        }
        return candidate
    }

    private fun ensurePrivateDirectories() {
        listOf(vaultDir, tmpDir).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
            runCatching {
                Files.setPosixFilePermissions(
                    dir.toPath(),
                    PosixFilePermissions.fromString("rwx------")
                )
            }
        }
    }

    private fun createOwnerOnlyFile(dir: File, prefix: String, suffix: String): File {
        val target = File(dir, "$prefix${UUID.randomUUID()}$suffix")
        val createdPosix = runCatching {
            Files.createFile(
                target.toPath(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            )
            true
        }.getOrDefault(false)

        if (!createdPosix && !target.exists()) {
            target.createNewFile()
        }
        enforceOwnerOnlyPermissions(target)
        return target
    }

    private fun enforceOwnerOnlyPermissions(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                PosixFilePermissions.fromString("rw-------")
            )
        }
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    private fun wipeAndDelete(file: File) {
        runCatching {
            if (file.isFile) {
                val len = file.length()
                if (len > 0L) {
                    val zeros = ByteArray(minOf(len, 8192L).toInt())
                    file.outputStream().use { out ->
                        var remaining = len
                        while (remaining > 0L) {
                            val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                            out.write(zeros, 0, chunk)
                            remaining -= chunk
                        }
                        out.fd.sync()
                    }
                }
                file.delete()
            }
        }.onFailure {
            runCatching { file.delete() }
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "redfur_forge_site_auth_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION: Byte = 1
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val MAX_IMPORT_FILE_BYTES = 4 * 1024 * 1024
        private const val PROBE_TIMEOUT_MS = 4000
    }
}
