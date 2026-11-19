package com.fissal.forge.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fissal.forge.auth.ConnectedServiceSummary
import com.fissal.forge.auth.ServiceCatalog
import com.fissal.forge.auth.ServiceDescriptor
import kotlinx.coroutines.delay
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.SettingTopic
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.ui.components.AboutWorkshopCard
import com.fissal.forge.ui.components.AuthWebViewDialog
import com.fissal.forge.ui.components.ConnectionsSettingsCard
import com.fissal.forge.ui.components.DomainRolePill
import com.fissal.forge.ui.components.EngineDomain
import com.fissal.forge.ui.components.ForgeInfoTipButton
import com.fissal.forge.ui.components.ForgePrimaryButton
import com.fissal.forge.ui.components.RelayPrimaryButton
import com.fissal.forge.ui.components.SilenceSkipControlPanel
import com.fissal.forge.ui.components.SponsorBlockCategorySelector
import com.fissal.forge.ui.components.WorkshopDomainCard
import com.fissal.forge.ui.theme.AmberActive
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.ForgeReadoutStyle
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.VolcanicObsidian
import com.fissal.forge.update.AppUpdateManager
import com.fissal.forge.update.AppUpdatePhase
import com.fissal.forge.update.AppUpdateState
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: ForgeSettings,
    ytDlpVersion: String,
    isUpdatingYtDlp: Boolean,
    cacheSizeBytes: Long,
    appUpdateState: AppUpdateState,
    deviceCapabilities: DeviceCapabilityReport,
    siteConnections: List<ConnectedServiceSummary>,
    checkingServiceId: String?,
    onCheckAppUpdate: () -> Unit,
    onStartAppUpdate: () -> Unit,
    onCancelAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onCaptureWebViewSession: (ServiceDescriptor, String, List<String>) -> Unit,
    onImportCookiesTxt: (ServiceDescriptor, Uri) -> Unit,
    onCheckSiteConnection: (String) -> Unit,
    onRemoveSiteConnection: (String) -> Unit,
    onUpdateSettings: ((ForgeSettings) -> ForgeSettings) -> Unit,
    onUpdateYtDlp: (nightly: Boolean) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeWebViewService by remember { mutableStateOf<ServiceDescriptor?>(null) }
    var pendingImportServiceId by rememberSaveable { mutableStateOf<String?>(null) }

    val cookiesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val serviceId = pendingImportServiceId
        pendingImportServiceId = null
        val targetService = serviceId?.let { ServiceCatalog.findById(it) }
        if (uri != null && targetService != null) {
            onImportCookiesTxt(targetService, uri)
            activeWebViewService = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VolcanicObsidian)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. App Updates (GitHub Releases)
        SettingsDomainCard(
            title = "App Updates",
            subtitle = "Check GitHub Releases for newer signed builds of Redfur Forge.",
            domain = EngineDomain.RELAY,
            pillLabel = "App Updater",
            isActive = appUpdateState.phase == AppUpdatePhase.DOWNLOADING ||
                appUpdateState.phase == AppUpdatePhase.CHECKING
        ) {
            val release = appUpdateState.release
            val statusColor: Color
            val statusText: String
            when (appUpdateState.phase) {
                AppUpdatePhase.CHECKING -> {
                    statusColor = AmberActive
                    statusText = "Checking GitHub Releases..."
                }
                AppUpdatePhase.UP_TO_DATE -> {
                    statusColor = EmeraldSuccess
                    statusText = "You are on the latest release (v${appUpdateState.currentVersion})"
                }
                AppUpdatePhase.AVAILABLE -> {
                    statusColor = MasserCrimson
                    statusText = "Update available: ${release?.tagName ?: ""}"
                }
                AppUpdatePhase.DOWNLOADING -> {
                    statusColor = AmberActive
                    val dlMb = appUpdateState.downloadedBytes / (1024.0 * 1024.0)
                    val totMb = appUpdateState.totalBytes / (1024.0 * 1024.0)
                    statusText = if (appUpdateState.totalBytes > 0L) {
                        String.format(Locale.US, "Downloading %s: %.1f / %.1f MB", release?.tagName ?: "", dlMb, totMb)
                    } else {
                        String.format(Locale.US, "Downloading %s: %.1f MB", release?.tagName ?: "", dlMb)
                    }
                }
                AppUpdatePhase.READY_TO_INSTALL -> {
                    statusColor = EmeraldSuccess
                    statusText = if (appUpdateState.isSha256Verified) {
                        "SHA-256 verified and ready to install (${release?.tagName ?: ""})"
                    } else {
                        "Update APK ready to install (${release?.tagName ?: ""})"
                    }
                }
                AppUpdatePhase.NEEDS_INSTALL_PERMISSION -> {
                    statusColor = AmberActive
                    statusText = "Allow app installs in Android settings to apply ${release?.tagName ?: ""}"
                }
                AppUpdatePhase.ERROR -> {
                    statusColor = CoralError
                    statusText = appUpdateState.errorMessage ?: "Could not check for updates"
                }
                AppUpdatePhase.IDLE -> {
                    statusColor = SecundaSilver
                    statusText = "Installed version: v${appUpdateState.currentVersion}"
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Build: v${appUpdateState.currentVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CrispWhite
                )
                Surface(
                    color = statusColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = friendlyUpdatePhaseLabel(appUpdateState.phase),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )

            if (appUpdateState.phase == AppUpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { appUpdateState.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = ArcRelayCyan,
                    trackColor = SlateCard
                )
            }

            if (release != null && release.releaseNotes.isNotBlank() &&
                (appUpdateState.phase == AppUpdatePhase.AVAILABLE ||
                    appUpdateState.phase == AppUpdatePhase.DOWNLOADING ||
                    appUpdateState.phase == AppUpdatePhase.READY_TO_INSTALL ||
                    appUpdateState.phase == AppUpdatePhase.NEEDS_INSTALL_PERMISSION)
            ) {
                Text(
                    text = release.releaseNotes.trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSteel,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCheckAppUpdate,
                    enabled = appUpdateState.phase != AppUpdatePhase.CHECKING &&
                        appUpdateState.phase != AppUpdatePhase.DOWNLOADING,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (appUpdateState.phase == AppUpdatePhase.CHECKING) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SecundaSilver)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Check Updates", maxLines = 1)
                }

                when (appUpdateState.phase) {
                    AppUpdatePhase.AVAILABLE -> {
                        RelayPrimaryButton(
                            onClick = onStartAppUpdate,
                            text = "Download ${release?.tagName ?: ""}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    AppUpdatePhase.DOWNLOADING -> {
                        OutlinedButton(
                            onClick = onCancelAppUpdate,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = CoralError)
                        }
                    }
                    AppUpdatePhase.READY_TO_INSTALL, AppUpdatePhase.NEEDS_INSTALL_PERMISSION -> {
                        androidx.compose.material3.Button(
                            onClick = onInstallAppUpdate,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess, contentColor = ObsidianBg),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (appUpdateState.phase == AppUpdatePhase.NEEDS_INSTALL_PERMISSION) "Allow & Install"
                                else "Install ${release?.tagName ?: ""}",
                                maxLines = 1
                            )
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = {
                                val targetUrl = release?.htmlUrl?.ifBlank { null } ?: AppUpdateManager.RELEASES_WEB_URL
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                                }.onFailure {
                                    Toast.makeText(context, "No browser app installed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View on GitHub", maxLines = 1)
                        }
                    }
                }
            }

            SettingsSwitchRow(
                title = "Check for app updates on startup",
                subtitle = "Looks up the latest release automatically when Redfur Forge launches",
                checked = settings.autoCheckAppUpdates,
                onCheckedChange = { v -> onUpdateSettings { it.copy(autoCheckAppUpdates = v) } }
            )

            SettingsSwitchRow(
                title = "Download updates in the background",
                subtitle = "Stages verified APK updates automatically when available",
                checked = settings.autoDownloadAppUpdates,
                onCheckedChange = { v -> onUpdateSettings { it.copy(autoDownloadAppUpdates = v) } }
            )
        }

        // 2. Connected Accounts & Cookies Vault
        ConnectionsSettingsCard(
            connections = siteConnections,
            checkingServiceId = checkingServiceId,
            onConnectServiceWebView = { svc -> activeWebViewService = svc },
            onImportCookiesTxt = { svc ->
                pendingImportServiceId = svc.id
                cookiesPickerLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
            },
            onCheckConnection = onCheckSiteConnection,
            onReauthenticate = { svc -> activeWebViewService = svc },
            onRemoveConnection = onRemoveSiteConnection
        )

        // 3. Download Defaults
        SettingsDomainCard(
            title = "Download Defaults",
            subtitle = "Starting format, quality, and cleanup preferences applied to newly staged URLs.",
            domain = EngineDomain.RELAY,
            pillLabel = "Downloader"
        ) {
            Text("Preferred Format", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.defaultDownloadMode == mode,
                        onClick = { onUpdateSettings { it.copy(defaultDownloadMode = mode) } },
                        label = { Text(mode.label) }
                    )
                }
            }

            Text("Preferred Video Resolution Cap", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Best", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (h, label) ->
                    FilterChip(
                        selected = settings.defaultMaxVideoHeight == h,
                        onClick = { onUpdateSettings { it.copy(defaultMaxVideoHeight = h) } },
                        label = { Text(label) }
                    )
                }
            }

            Text("Preferred Audio Format", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("mp3", "opus", "m4a", "flac", "wav").forEach { fmt ->
                    FilterChip(
                        selected = settings.defaultAudioFormat.equals(fmt, ignoreCase = true),
                        onClick = { onUpdateSettings { it.copy(defaultAudioFormat = fmt) } },
                        label = { Text(fmt.uppercase(Locale.US)) }
                    )
                }
            }

            SettingsSwitchRow(
                title = "Multi-connection speed boost (aria2c)",
                subtitle = "Uses parallel connections to speed up large public downloads",
                checked = settings.useAria2c,
                onCheckedChange = { c -> onUpdateSettings { it.copy(useAria2c = c) } },
                infoTopic = SettingTopic.ARIA2C_ACCELERATOR,
                capabilityReport = deviceCapabilities
            )
            SettingsSwitchRow(
                title = "Download single video from playlist links",
                subtitle = "When a video URL includes a playlist ID, download only that video by default",
                checked = settings.playlistSingleOnly,
                onCheckedChange = { c -> onUpdateSettings { it.copy(playlistSingleOnly = c) } }
            )
            SettingsSwitchRow(
                title = "Remove tracking parameters from links",
                subtitle = "Strips utm_*, si, igsh, and share tags while keeping video IDs",
                checked = settings.stripUrlTracking,
                onCheckedChange = { c -> onUpdateSettings { it.copy(stripUrlTracking = c) } }
            )
            SettingsSwitchRow(
                title = "Embed media info and cover artwork",
                subtitle = "Writes title, creator, and thumbnail tags into downloaded files",
                checked = settings.embedDownloadMetadata,
                onCheckedChange = { c ->
                    onUpdateSettings { it.copy(embedDownloadMetadata = c, embedDownloadThumbnail = c) }
                }
            )

            HorizontalDivider(color = SlateOutline)

            SponsorBlockCategorySelector(
                title = "Default SponsorBlock Skip Categories",
                selectedCategories = settings.defaultSponsorBlockCategories,
                capabilityReport = deviceCapabilities,
                onToggleCategory = { cat ->
                    onUpdateSettings { prev ->
                        val next = if (cat in prev.defaultSponsorBlockCategories) {
                            prev.defaultSponsorBlockCategories - cat
                        } else {
                            prev.defaultSponsorBlockCategories + cat
                        }
                        prev.copy(defaultSponsorBlockCategories = next)
                    }
                }
            )

            HorizontalDivider(color = SlateOutline)

            SilenceSkipControlPanel(
                enabled = settings.defaultSilenceSkip.enabled,
                thresholdDb = settings.defaultSilenceSkip.thresholdDb,
                minSilenceMs = settings.defaultSilenceSkip.minSilenceMs,
                subtitle = "Automatically trim silent pauses after download completes",
                capabilityReport = deviceCapabilities,
                onToggleEnabled = { enabled ->
                    onUpdateSettings { prev ->
                        prev.copy(defaultSilenceSkip = prev.defaultSilenceSkip.copy(enabled = enabled))
                    }
                },
                onSelectThresholdDb = { db ->
                    onUpdateSettings { prev ->
                        prev.copy(defaultSilenceSkip = prev.defaultSilenceSkip.copy(enabled = true, thresholdDb = db))
                    }
                },
                onSelectMinSilenceMs = { ms ->
                    onUpdateSettings { prev ->
                        prev.copy(defaultSilenceSkip = prev.defaultSilenceSkip.copy(enabled = true, minSilenceMs = ms))
                    }
                }
            )
        }

        // 4. Studio & Compression Defaults
        SettingsDomainCard(
            title = "Studio & Compression Defaults",
            subtitle = "Starting encode, filesize, and crop defaults when opening files in Forge Studio.",
            domain = EngineDomain.FORGE,
            pillLabel = "Forge Studio"
        ) {
            SettingsHeaderWithTip(
                label = "Preferred Video Encoder",
                topic = SettingTopic.VIDEO_CODEC,
                capabilityReport = deviceCapabilities
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    VideoCodecChoice.AV1,
                    VideoCodecChoice.HEVC,
                    VideoCodecChoice.H264,
                    VideoCodecChoice.VP9
                ).forEach { codec ->
                    FilterChip(
                        selected = settings.defaultVideoCodec == codec,
                        onClick = { onUpdateSettings { it.copy(defaultVideoCodec = codec) } },
                        label = { Text(codec.label) }
                    )
                }
            }

            var crfSliderDraft by remember(settings.defaultVideoCrf) {
                mutableFloatStateOf(settings.defaultVideoCrf.toFloat())
            }
            SettingsHeaderWithTip(
                label = "Default Video Quality (CRF): ${crfSliderDraft.roundToInt()}",
                topic = SettingTopic.CRF_SLIDER,
                capabilityReport = deviceCapabilities
            )
            Slider(
                value = crfSliderDraft,
                onValueChange = { crfSliderDraft = it },
                onValueChangeFinished = {
                    val committed = crfSliderDraft.roundToInt()
                    if (committed != settings.defaultVideoCrf) {
                        onUpdateSettings { it.copy(defaultVideoCrf = committed) }
                    }
                },
                valueRange = 16f..34f
            )

            Text("Default Camera RAW Export Format", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("jpg", "png", "webp").forEach { fmt ->
                    FilterChip(
                        selected = settings.defaultImageFormat == fmt,
                        onClick = { onUpdateSettings { it.copy(defaultImageFormat = fmt) } },
                        label = { Text(fmt.uppercase()) }
                    )
                }
            }

            SettingsHeaderWithTip(
                label = "Default Target Size Limit (MB)",
                topic = SettingTopic.TARGET_FILESIZE,
                capabilityReport = deviceCapabilities
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 8, 10, 25, 50).forEach { mb ->
                    FilterChip(
                        selected = settings.defaultTargetSizeMb == mb,
                        onClick = { onUpdateSettings { it.copy(defaultTargetSizeMb = mb) } },
                        label = { Text("$mb MB") }
                    )
                }
            }

            Text("Default Crop Aspect Ratio", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AspectPreset.AUTO_CLOSEST,
                    AspectPreset.RATIO_16_9,
                    AspectPreset.RATIO_4_3,
                    AspectPreset.RATIO_1_1,
                    AspectPreset.FREEFORM
                ).forEach { preset ->
                    FilterChip(
                        selected = settings.defaultCropPreset == preset,
                        onClick = { onUpdateSettings { it.copy(defaultCropPreset = preset) } },
                        label = { Text(preset.label) }
                    )
                }
            }

            SettingsSwitchRow(
                title = "Fast half-size RAW processing by default",
                subtitle = "Halves dimensions on high-megapixel RAW sensors for 4x speed",
                checked = settings.rawHalfSizeFast,
                onCheckedChange = { c -> onUpdateSettings { it.copy(rawHalfSizeFast = c) } },
                infoTopic = SettingTopic.RAW_HALF_SIZE_FAST,
                capabilityReport = deviceCapabilities
            )
        }

        // 5. Privacy, Performance & File Naming
        SettingsDomainCard(
            title = "Privacy, Performance & Naming",
            subtitle = "Control metadata scrubbing, parallel task limits, and exported filenames.",
            domain = EngineDomain.DELIVERY,
            pillLabel = "System Policy"
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SlateCard,
                border = BorderStroke(1.dp, SlateOutline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Host Hardware: ${deviceCapabilities.compactHardwareSummary()}",
                    style = ForgeReadoutStyle,
                    color = ArcRelayCyan,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }

            SettingsSwitchRow(
                title = "Strip EXIF and GPS when copying to clipboard",
                subtitle = "Removes location tags before pasting files into chat apps",
                checked = settings.stripMetadataOnClipboard,
                onCheckedChange = { c -> onUpdateSettings { it.copy(stripMetadataOnClipboard = c) } },
                infoTopic = SettingTopic.STRIP_METADATA,
                capabilityReport = deviceCapabilities
            )
            SettingsSwitchRow(
                title = "Strip EXIF and GPS when saving to device",
                subtitle = "Removes camera and location metadata from exported gallery files",
                checked = settings.stripMetadataOnSave,
                onCheckedChange = { c -> onUpdateSettings { it.copy(stripMetadataOnSave = c) } },
                infoTopic = SettingTopic.STRIP_METADATA,
                capabilityReport = deviceCapabilities
            )

            var concurrentDownloadsDraft by remember(settings.maxConcurrentDownloads) {
                mutableFloatStateOf(settings.maxConcurrentDownloads.toFloat())
            }
            SettingsHeaderWithTip(
                label = "Simultaneous Downloads: ${concurrentDownloadsDraft.roundToInt()}",
                topic = SettingTopic.CONCURRENCY_SLOTS,
                capabilityReport = deviceCapabilities
            )
            Slider(
                value = concurrentDownloadsDraft,
                onValueChange = { concurrentDownloadsDraft = it },
                onValueChangeFinished = {
                    val committed = concurrentDownloadsDraft.roundToInt()
                    if (committed != settings.maxConcurrentDownloads) {
                        onUpdateSettings { it.copy(maxConcurrentDownloads = committed) }
                    }
                },
                valueRange = 1f..5f,
                steps = 3
            )

            SettingsHeaderWithTip(
                label = if (settings.computeSlotsOverride == 0) {
                    "Parallel Processing Tasks: Auto (${deviceCapabilities.recommendedComputeSlots} slots)"
                } else {
                    "Parallel Processing Tasks: ${settings.computeSlotsOverride} slots"
                },
                topic = SettingTopic.CONCURRENCY_SLOTS,
                capabilityReport = deviceCapabilities
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    0 to "Auto (${deviceCapabilities.recommendedComputeSlots})",
                    2 to "2 Tasks",
                    3 to "3 Tasks",
                    4 to "4 Tasks"
                ).forEach { (slots, label) ->
                    FilterChip(
                        selected = settings.computeSlotsOverride == slots,
                        onClick = { onUpdateSettings { it.copy(computeSlotsOverride = slots) } },
                        label = { Text(label) }
                    )
                }
            }

            var suffixDraft by remember { mutableStateOf(settings.filenameSuffix) }
            LaunchedEffect(settings.filenameSuffix) {
                if (settings.filenameSuffix != suffixDraft) {
                    suffixDraft = settings.filenameSuffix
                }
            }
            LaunchedEffect(suffixDraft) {
                if (suffixDraft != settings.filenameSuffix) {
                    delay(350L)
                    onUpdateSettings { it.copy(filenameSuffix = suffixDraft) }
                }
            }
            OutlinedTextField(
                value = suffixDraft,
                onValueChange = { txt -> suffixDraft = txt.take(16) },
                label = { Text("Exported Filename Suffix") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 6. Extractor Engine & Storage Cache
        SettingsDomainCard(
            title = "Extractor Engine & Cache",
            subtitle = "Keep yt-dlp extractors up to date for site support and manage temporary cache files.",
            domain = EngineDomain.DELIVERY,
            pillLabel = "Maintenance",
            isActive = isUpdatingYtDlp
        ) {
            Text(
                text = "Installed yt-dlp version: $ytDlpVersion",
                style = MaterialTheme.typography.bodyMedium,
                color = CrispWhite
            )

            SettingsSwitchRow(
                title = "Auto-update yt-dlp on startup",
                subtitle = "Updates extractors automatically on first launch and checks periodically",
                checked = settings.autoUpdateYtDlpOnLaunch,
                onCheckedChange = { enabled -> onUpdateSettings { it.copy(autoUpdateYtDlpOnLaunch = enabled) } }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ForgePrimaryButton(
                    onClick = { onUpdateYtDlp(false) },
                    text = if (isUpdatingYtDlp) "Updating..." else "Update Stable",
                    enabled = !isUpdatingYtDlp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { onUpdateYtDlp(true) },
                    enabled = !isUpdatingYtDlp,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Update Nightly")
                }
            }

            val cacheMb = cacheSizeBytes / (1024.0 * 1024.0)
            OutlinedButton(
                onClick = onClearCache,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(String.format(Locale.US, "Clear Temporary Cache (%.1f MB)", cacheMb))
            }
        }

        // Workshop About Card & Nixie Terminal Fuse Stamp
        AboutWorkshopCard(
            capabilitySummary = deviceCapabilities.compactHardwareSummary()
        )
    }

    activeWebViewService?.let { service ->
        AuthWebViewDialog(
            service = service,
            onDismiss = { activeWebViewService = null },
            onCaptureSession = { resolvedService, ua, visitedUrls ->
                activeWebViewService = null
                onCaptureWebViewSession(resolvedService, ua, visitedUrls)
            },
            onImportCookiesClick = { resolvedService ->
                pendingImportServiceId = resolvedService.id
                cookiesPickerLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
            }
        )
    }
}

private fun friendlyUpdatePhaseLabel(phase: AppUpdatePhase): String = when (phase) {
    AppUpdatePhase.IDLE -> "Ready"
    AppUpdatePhase.CHECKING -> "Checking"
    AppUpdatePhase.UP_TO_DATE -> "Up to Date"
    AppUpdatePhase.AVAILABLE -> "Available"
    AppUpdatePhase.DOWNLOADING -> "Downloading"
    AppUpdatePhase.READY_TO_INSTALL -> "Ready"
    AppUpdatePhase.NEEDS_INSTALL_PERMISSION -> "Needs Permission"
    AppUpdatePhase.ERROR -> "Error"
}

@Composable
private fun SettingsDomainCard(
    title: String,
    subtitle: String,
    domain: EngineDomain,
    pillLabel: String,
    isActive: Boolean = false,
    content: @Composable () -> Unit
) {
    WorkshopDomainCard(
        domain = domain,
        isActive = isActive,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSteel
                    )
                }
                Spacer(Modifier.width(8.dp))
                DomainRolePill(domain = domain, label = pillLabel)
            }
            HorizontalDivider(color = SlateOutline.copy(alpha = 0.65f))
            content()
        }
    }
}

@Composable
private fun SettingsHeaderWithTip(
    label: String,
    topic: SettingTopic,
    capabilityReport: DeviceCapabilityReport
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = SecundaSilver,
            modifier = Modifier.weight(1f)
        )
        ForgeInfoTipButton(
            topic = topic,
            capabilityReport = capabilityReport
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    infoTopic: SettingTopic? = null,
    capabilityReport: DeviceCapabilityReport? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = CrispWhite)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MutedSteel)
        }
        if (infoTopic != null) {
            ForgeInfoTipButton(
                topic = infoTopic,
                capabilityReport = capabilityReport
            )
        }
        Spacer(Modifier.width(6.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
