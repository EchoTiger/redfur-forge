package com.fissal.forge.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fissal.forge.auth.CookieNetscapeSerializer
import com.fissal.forge.auth.NetscapeCookieRecord
import com.fissal.forge.auth.ServiceCatalog
import com.fissal.forge.auth.ServiceDescriptor
import com.fissal.forge.ui.theme.AmberActive
import com.fissal.forge.ui.theme.AnvilGunmetal
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.ForgeGold
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.RelayAbyss
import com.fissal.forge.ui.theme.RelayBevelOutline
import com.fissal.forge.ui.theme.RelayCard
import com.fissal.forge.ui.theme.RelaySurface
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.VolcanicObsidian
import java.net.URI

private const val MAX_VISITED_ORIGIN_URLS = 24

/**
 * Distinct rounded platform badge combining each service's [ServiceDescriptor.brandHex],
 * platform iconography, and compact [ServiceDescriptor.glyphCode] monogram.
 */
@Composable
fun ServiceBrandIcon(
    service: ServiceDescriptor,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier
) {
    val rawBrandColor = Color(service.brandHex)
    val isDarkBrand = rawBrandColor.luminance() < 0.06f

    val shape = RoundedCornerShape((size.value * 0.28f).dp)
    val containerBrush = if (isDarkBrand) {
        Brush.verticalGradient(
            listOf(
                rawBrandColor.copy(alpha = 0.96f),
                ObsidianBg.copy(alpha = 0.96f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                rawBrandColor.copy(alpha = 0.30f),
                rawBrandColor.copy(alpha = 0.14f)
            )
        )
    }
    val borderColor = if (isDarkBrand) {
        SecundaSilver.copy(alpha = 0.55f)
    } else {
        rawBrandColor.copy(alpha = 0.78f)
    }
    val iconTint = if (isDarkBrand) {
        CrispWhite
    } else {
        lerp(rawBrandColor, CrispWhite, 0.38f)
    }

    val glyphCode = service.glyphCode.trim().take(3)
    val iconVector = brandIconFor(service)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerBrush)
            .border(width = 1.dp, color = borderColor, shape = shape)
    ) {
        if (size < 26.dp) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .size(size * 0.60f)
                    .align(Alignment.Center)
            )
        } else {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = size * 0.12f, top = size * 0.11f)
                    .size(size * 0.46f)
            )

            if (glyphCode.isNotEmpty()) {
                val glyphFontSizeSp = (size.value * 0.24f).coerceIn(7.5f, 12f).sp
                Text(
                    text = glyphCode,
                    color = CrispWhite,
                    fontSize = glyphFontSizeSp,
                    lineHeight = glyphFontSizeSp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = size * 0.10f, bottom = size * 0.08f)
                )
            }
        }
    }
}

private fun brandIconFor(service: ServiceDescriptor): ImageVector = when (service.id.lowercase()) {
    "youtube" -> Icons.Rounded.PlayArrow
    "facebook" -> Icons.Rounded.Public
    "instagram" -> Icons.Rounded.PhotoCamera
    "twitter" -> Icons.Rounded.Tag
    "tiktok" -> Icons.Rounded.MusicNote
    "twitch" -> Icons.Rounded.LiveTv
    "reddit" -> Icons.Rounded.Forum
    "patreon" -> Icons.Rounded.Favorite
    "vimeo" -> Icons.Rounded.Movie
    "soundcloud" -> Icons.Rounded.GraphicEq
    "bilibili" -> Icons.Rounded.SmartDisplay
    "niconico" -> Icons.Rounded.Videocam
    else -> Icons.Rounded.Language
}

/**
 * Fullscreen/wide embedded login WebView dialog for authenticating a site and capturing its
 * session cookies + sanitized User-Agent directly into the local encrypted Keystore vault.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuthWebViewDialog(
    service: ServiceDescriptor,
    onDismiss: () -> Unit,
    onCaptureSession: (resolvedService: ServiceDescriptor, userAgent: String, visitedUrls: List<String>) -> Unit,
    onImportCookiesClick: (resolvedService: ServiceDescriptor) -> Unit
) {
    key(service.id) {
        val context = LocalContext.current
        val focusManager = LocalFocusManager.current

        var activeResolvedService by remember(service.id) { mutableStateOf(service) }
        var customDomainInput by remember(service.id) { mutableStateOf("") }
        var customDomainError by remember(service.id) { mutableStateOf<String?>(null) }

        var loadedTargetUrl by remember(service.id) { mutableStateOf(service.loginUrl) }
        var currentPageUrl by remember(service.id) { mutableStateOf(service.loginUrl.ifBlank { null }) }
        var isLoadingPage by remember(service.id) { mutableStateOf(service.loginUrl.isNotBlank()) }
        var loadProgress by remember(service.id) { mutableIntStateOf(0) }
        var sessionDetected by remember(service.id) { mutableStateOf(false) }
        var canGoBack by remember(service.id) { mutableStateOf(false) }

        var sanitizedUserAgent by remember(service.id) {
            mutableStateOf(
                sanitizeWebViewUserAgent(
                    runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
                )
            )
        }

        val visitedOriginUrls = remember(service.id) { mutableStateListOf<String>() }
        var webViewRef by remember(service.id) { mutableStateOf<WebView?>(null) }

        fun recordVisitedOrigin(rawUrl: String?) {
            val origin = rawUrl?.let { extractOriginUrl(it) } ?: return
            if (origin !in visitedOriginUrls) {
                if (visitedOriginUrls.size >= MAX_VISITED_ORIGIN_URLS) {
                    visitedOriginUrls.removeAt(0)
                }
                visitedOriginUrls += origin
            }
        }

        fun navigateToCustomDomain() {
            val resolved = ServiceCatalog.forCustomDomain(customDomainInput)
            if (resolved == null) {
                customDomainError = "Enter a valid website domain (for example, members.example.com)."
                return
            }
            customDomainError = null
            focusManager.clearFocus()
            activeResolvedService = resolved
            loadedTargetUrl = resolved.loginUrl
            currentPageUrl = resolved.loginUrl
            isLoadingPage = true
            sessionDetected = detectLiveWebViewSession(resolved, visitedOriginUrls)
            webViewRef?.loadUrl(resolved.loginUrl)
        }

        fun resolveEffectiveService(): ServiceDescriptor? {
            if (!activeResolvedService.isCustomDomainTemplate) return activeResolvedService
            ServiceCatalog.forCustomDomain(customDomainInput)?.let { return it }
            currentPageUrl?.let { ServiceCatalog.forCustomDomain(it) }?.let { return it }
            visitedOriginUrls.lastOrNull()?.let { ServiceCatalog.forCustomDomain(it) }?.let { return it }
            return null
        }

        DisposableEffect(webViewRef) {
            val activeView = webViewRef
            onDispose {
                if (activeView != null) {
                    activeView.webViewClient = WebViewClient()
                    activeView.webChromeClient = null
                    activeView.stopLoading()
                    activeView.loadUrl("about:blank")
                    activeView.clearHistory()
                    activeView.clearCache(true)
                    activeView.removeAllViews()
                    activeView.destroy()
                }
            }
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 10.dp)
                    .imePadding(),
                shape = RoundedCornerShape(20.dp),
                color = VolcanicObsidian,
                contentColor = CrispWhite,
                border = BorderStroke(1.dp, RelayBevelOutline)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header bar
                    Surface(
                        color = RelaySurface,
                        border = BorderStroke(1.dp, RelayBevelOutline.copy(alpha = 0.6f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ServiceBrandIcon(
                                    service = activeResolvedService,
                                    size = 34.dp
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Sign In • ${activeResolvedService.displayName}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = CrispWhite,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val hostLabel = currentPageUrl
                                        ?.let { ServiceCatalog.extractHost(it) }
                                        ?: activeResolvedService.hostMatchers.firstOrNull()
                                        ?: "Enter a domain below"
                                    Text(
                                        text = hostLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SecundaSilver,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (sessionDetected) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = EmeraldSuccess.copy(alpha = 0.16f),
                                        border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.55f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                tint = EmeraldSuccess,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = "Session Detected",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = EmeraldSuccess,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                } else {
                                    DomainRolePill(
                                        domain = EngineDomain.RELAY,
                                        label = "Vault Login"
                                    )
                                }

                                if (canGoBack) {
                                    IconButton(
                                        onClick = { webViewRef?.goBack() },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                            contentDescription = "WebView back",
                                            tint = SecundaSilver,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { webViewRef?.reload() },
                                    enabled = loadedTargetUrl.isNotBlank(),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = "Reload page",
                                        tint = if (loadedTargetUrl.isNotBlank()) ArcRelayCyan else MutedSteel,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Close sign-in dialog",
                                        tint = SecundaSilver,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            // Privacy banner
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = RelayCard,
                                border = BorderStroke(1.dp, ArcRelayCyan.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = ArcRelayCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Your sign-in session stays encrypted locally on this device so Redfur Forge can fetch your private/subscriber media.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CrispWhite
                                    )
                                }
                            }

                            // Inline custom domain bar when template mode is active
                            if (service.isCustomDomainTemplate) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = customDomainInput,
                                        onValueChange = {
                                            customDomainInput = it
                                            if (customDomainError != null) customDomainError = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        placeholder = {
                                            Text(
                                                text = "members.example.com",
                                                color = MutedSteel,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Go
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onGo = { navigateToCustomDomain() }
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = ArcRelayCyan,
                                            unfocusedBorderColor = RelayBevelOutline,
                                            focusedTextColor = CrispWhite,
                                            unfocusedTextColor = CrispWhite,
                                            cursorColor = ArcRelayCyan,
                                            focusedContainerColor = RelayAbyss,
                                            unfocusedContainerColor = RelayAbyss
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )

                                    RelayPrimaryButton(
                                        onClick = { navigateToCustomDomain() },
                                        text = "Go"
                                    )
                                }

                                if (customDomainError != null) {
                                    Text(
                                        text = customDomainError.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CoralError
                                    )
                                }
                            }
                        }
                    }

                    if (isLoadingPage) {
                        LinearProgressIndicator(
                            progress = { (loadProgress.coerceIn(5, 100)) / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = ArcRelayCyan,
                            trackColor = SlateCard
                        )
                    }

                    // Embedded Android WebView
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(VolcanicObsidian)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )

                                    val cleanUa = sanitizeWebViewUserAgent(settings.userAgentString.orEmpty())
                                    if (cleanUa.isNotEmpty()) {
                                        settings.userAgentString = cleanUa
                                        sanitizedUserAgent = cleanUa
                                    }
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true

                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            loadProgress = newProgress
                                            isLoadingPage = newProgress in 1..99
                                        }
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val scheme = request?.url?.scheme?.lowercase() ?: return false
                                            return scheme != "http" && scheme != "https"
                                        }

                                        override fun onPageStarted(
                                            view: WebView?,
                                            url: String?,
                                            favicon: Bitmap?
                                        ) {
                                            if (!url.isNullOrBlank() && url != "about:blank") {
                                                currentPageUrl = url
                                                isLoadingPage = true
                                                recordVisitedOrigin(url)
                                                canGoBack = view?.canGoBack() == true
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            isLoadingPage = false
                                            loadProgress = 100
                                            canGoBack = view?.canGoBack() == true
                                            if (!url.isNullOrBlank() && url != "about:blank") {
                                                currentPageUrl = url
                                                recordVisitedOrigin(url)
                                                if (service.isCustomDomainTemplate &&
                                                    activeResolvedService.isCustomDomainTemplate
                                                ) {
                                                    ServiceCatalog.forCustomDomain(url)?.let { resolved ->
                                                        activeResolvedService = resolved
                                                    }
                                                }
                                            }
                                            val probeService = resolveEffectiveService() ?: activeResolvedService
                                            sessionDetected = detectLiveWebViewSession(
                                                service = probeService,
                                                visitedUrls = visitedOriginUrls.toList()
                                            )
                                        }
                                    }

                                    if (service.loginUrl.isNotBlank()) {
                                        loadUrl(service.loginUrl)
                                    } else {
                                        loadUrl("about:blank")
                                    }
                                }.also { created ->
                                    webViewRef = created
                                }
                            }
                        )

                        if (service.isCustomDomainTemplate && loadedTargetUrl.isBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(VolcanicObsidian)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = RelaySurface,
                                    border = BorderStroke(1.dp, RelayBevelOutline)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(18.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Language,
                                            contentDescription = null,
                                            tint = ArcRelayCyan,
                                            modifier = Modifier.size(34.dp)
                                        )
                                        Text(
                                            text = "Enter a Custom Site Domain",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = CrispWhite
                                        )
                                        Text(
                                            text = "Type a domain like members.example.com above and tap Go to open its sign-in page, or import an exported cookies.txt file below.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SecundaSilver
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Action footer
                    Surface(
                        color = RelaySurface,
                        border = BorderStroke(1.dp, RelayBevelOutline.copy(alpha = 0.6f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RelayPrimaryButton(
                                onClick = {
                                    val resolved = resolveEffectiveService()
                                    if (resolved == null) {
                                        customDomainError = "Enter a valid site domain and tap Go before capturing."
                                        return@RelayPrimaryButton
                                    }
                                    currentPageUrl?.let { recordVisitedOrigin(it) }
                                    val finalUa = webViewRef?.settings?.userAgentString
                                        ?.let { sanitizeWebViewUserAgent(it) }
                                        ?.takeIf { it.isNotEmpty() }
                                        ?: sanitizedUserAgent
                                    onCaptureSession(resolved, finalUa, visitedOriginUrls.toList())
                                },
                                text = "I'm Signed In — Capture Session",
                                icon = Icons.Rounded.VerifiedUser,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val target = resolveEffectiveService() ?: activeResolvedService
                                        onImportCookiesClick(target)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, RelayBevelOutline),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FolderOpen,
                                        contentDescription = null,
                                        tint = ArcRelayCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Import cookies.txt",
                                        color = CrispWhite,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                OutlinedButton(
                                    onClick = onDismiss,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, SlateOutline),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "Cancel",
                                        color = SecundaSilver,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Contextual recovery dialog shown when a Relay download hits a private, age-gated, or
 * members-only barrier, guiding the user through local WebView sign-in or `cookies.txt` import.
 */
@Composable
fun AuthRequiredPromptDialog(
    service: ServiceDescriptor?,
    targetUrl: String,
    reason: String,
    isPaywallOrSubscription: Boolean,
    onDismiss: () -> Unit,
    onSignInWebView: (ServiceDescriptor) -> Unit,
    onImportCookiesTxt: (ServiceDescriptor) -> Unit,
    onOpenExternalApp: (String) -> Unit
) {
    val resolvedService = remember(service, targetUrl) {
        service
            ?: ServiceCatalog.matchUrl(targetUrl)
            ?: ServiceCatalog.forCustomDomain(targetUrl)
            ?: ServiceCatalog.findById("custom")
            ?: ServiceCatalog.entries.last()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = AnvilGunmetal,
            contentColor = CrispWhite,
            border = BorderStroke(1.dp, RelayBevelOutline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ServiceBrandIcon(
                        service = resolvedService,
                        size = 42.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        DomainRolePill(
                            domain = EngineDomain.RELAY,
                            label = if (isPaywallOrSubscription) "Members / Subscription" else "Sign-In Required"
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Sign In to ${resolvedService.displayName}",
                            style = MaterialTheme.typography.titleLarge,
                            color = CrispWhite
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss dialog",
                            tint = SecundaSilver
                        )
                    }
                }

                if (isPaywallOrSubscription) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = FurnaceCard,
                        border = BorderStroke(1.dp, ForgeGold.copy(alpha = 0.55f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = AmberActive,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Members-only or paid subscription content detected. Sign in with an account that holds active access.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ForgeGold
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = RelayCard,
                    border = BorderStroke(1.dp, RelayBevelOutline)
                ) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CrispWhite,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SlateCard,
                    border = BorderStroke(1.dp, SlateOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "WHY SIGN IN INSIDE REDFUR FORGE?",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            color = ArcRelayCyan
                        )
                        Text(
                            text = "Android app sandboxing blocks apps from reading login cookies stored inside standalone apps like YouTube, Facebook, or Patreon. Signing in once inside Redfur Forge (or importing cookies.txt) keeps your session encrypted locally on this device so downloads work privately.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecundaSilver
                        )
                    }
                }

                RelayPrimaryButton(
                    onClick = { onSignInWebView(resolvedService) },
                    text = "Sign In via Redfur Forge",
                    icon = Icons.Rounded.VerifiedUser,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = { onImportCookiesTxt(resolvedService) },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, RelayBevelOutline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrispWhite),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = ArcRelayCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Import cookies.txt", maxLines = 1)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onOpenExternalApp(targetUrl) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SlateOutline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SecundaSilver),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Open in App / Browser",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Dismiss", color = MutedSteel, maxLines = 1)
                    }
                }
            }
        }
    }
}

internal fun sanitizeWebViewUserAgent(rawUserAgent: String): String {
    if (rawUserAgent.isBlank()) return ""
    return rawUserAgent
        .replace(Regex(";\\s*wv\\b"), "")
        .replace(Regex("\\bVersion/\\d+\\.\\d+\\s*"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

private fun extractOriginUrl(rawUrl: String): String? {
    val parsed = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = parsed.host?.lowercase()?.trimEnd('.') ?: return null
    if (host.isBlank()) return null
    val portSuffix = if (parsed.port > 0 &&
        !(scheme == "http" && parsed.port == 80) &&
        !(scheme == "https" && parsed.port == 443)
    ) {
        ":${parsed.port}"
    } else {
        ""
    }
    return "$scheme://$host$portSuffix"
}

private fun detectLiveWebViewSession(
    service: ServiceDescriptor,
    visitedUrls: List<String>
): Boolean {
    if (service.isCustomDomainTemplate) return false
    val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return false
    val records = mutableListOf<NetscapeCookieRecord>()

    val probeUrls = buildList {
        service.cookieDomains.forEach { entry ->
            val trimmed = entry.trim()
            when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> add(trimmed)
                trimmed.isNotEmpty() -> {
                    val bare = trimmed.trimStart('.')
                    if (bare.isNotEmpty()) {
                        add("https://$bare")
                        add("https://www.$bare")
                    }
                }
            }
        }
        if (service.loginUrl.isNotBlank()) add(service.loginUrl)
        addAll(visitedUrls)
    }.distinct()

    for (probeUrl in probeUrls) {
        val header = runCatching { cookieManager.getCookie(probeUrl) }.getOrNull()
        if (header.isNullOrBlank()) continue
        val host = ServiceCatalog.extractHost(probeUrl) ?: continue
        val secure = probeUrl.startsWith("https://", ignoreCase = true)
        records += CookieNetscapeSerializer.parseCookieHeader(
            cookieHeader = header,
            domain = host,
            secure = secure
        )
    }

    return CookieNetscapeSerializer.hasAuthenticatedSession(
        cookies = records,
        service = service
    )
}
