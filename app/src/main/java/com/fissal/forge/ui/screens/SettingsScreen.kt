package com.fissal.forge.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fissal.forge.auth.ConnectedServiceSummary
import com.fissal.forge.auth.ServiceDescriptor
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
    var pendingImportService by remember { mutableStateOf<ServiceDescriptor?>(null) }

    val cookiesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetService = pendingImportService
        pendingImportService = null
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
        // GitHub Release App Auto-Updater
        SettingsDomainCard(
            title = "App Updates (GitHub Releases)",
            domain = EngineDomain.RELAY,
            pillLabel = "GitHub Sync",
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
                    statusText = "Up to date (v${appUpdateState.currentVersion})"
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
                    statusText = "Verified & ready to install (${release?.tagName ?: ""})"
                }
                AppUpdatePhase.NEEDS_INSTALL_PERMISSION -> {
                    statusColor = AmberActive
                    statusText = "Grant app install permission to apply ${release?.tagName ?: ""}"
                }
                AppUpdatePhase.ERROR -> {
                    statusColor = CoralError
                    statusText = appUpdateState.errorMessage ?: "Update check failed"
                }
                AppUpdatePhase.IDLE -> {
                    statusColor = SecundaSilver
                    statusText = "Installed: v${appUpdateState.currentVersion}"
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Installed: v${appUpdateState.currentVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CrispWhite
                )
                Surface(
                    color = statusColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (appUpdateState.phase == AppUpdatePhase.IDLE) "GitHub Sync" else appUpdateState.phase.name.replace('_', ' '),
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
                    Text("Check Now")
                }

                when (appUpdateState.phase) {
                    AppUpdatePhase.AVAILABLE -> {
                        RelayPrimaryButton(
                            onClick = onStartAppUpdate,
                            text = "Update ${release?.tagName ?: ""}",
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
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Releases", maxLines = 1)
                        }
                    }
                }
            }

            SettingsSwitchRow(
                title = "Auto-check GitHub Releases on launch",
                subtitle = "Checks EchoTiger/redfur-forge for newer signed APKs at startup",
                checked = settings.autoCheckAppUpdates,
                onCheckedChange = { v -> onUpdateSettings { it.copy(autoCheckAppUpdates = v) } }
            )

            SettingsSwitchRow(
                title = "Auto-download updates in background",
                subtitle = "Stages verified matching-ABI APK automatically when a new release lands",
                checked = settings.autoDownloadAppUpdates,
                onCheckedChange = { v -> onUpdateSettings { it.copy(autoDownloadAppUpdates = v) } }
            )
        }

        // Connected Accounts & Cookies Vault (Issue #6)
        ConnectionsSettingsCard(
            connections = siteConnections,
            checkingServiceId = checkingServiceId,
            onConnectServiceWebView = { svc -> activeWebViewService = svc },
            onImportCookiesTxt = { svc ->
                pendingImportService = svc
                cookiesPickerLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
            },
            onCheckConnection = onCheckSiteConnection,
            onReauthenticate = { svc -> activeWebViewService = svc },
            onRemoveConnection = onRemoveSiteConnection
        )

        // Downloader Defaults
        SettingsDomainCard(
            title = "Downloader Defaults",
            domain = EngineDomain.RELAY,
            pillLabel = "Relay Intake"
        ) {
            Text("Default Download Mode", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.defaultDownloadMode == mode,
                        onClick = { onUpdateSettings { it.copy(defaultDownloadMode = mode) } },
                        label = { Text(mode.label) }
                    )
                }
            }

            SettingsSwitchRow(
                title = "Use aria2c multi-connection accelerator",
                subtitle = "Speeds up large HTTP downloads",
                checked = settings.useAria2c,
                onCheckedChange = { c -> onUpdateSettings { it.copy(useAria2c = c) } },
                infoTopic = SettingTopic.ARIA2C_ACCELERATOR,
                capabilityReport = deviceCapabilities
            )
            SettingsSwitchRow(
                title = "Single video guard on playlist URLs",
                subtitle = "Passes --no-playlist by default",
                checked = settings.playlistSingleOnly,
                onCheckedChange = { c -> onUpdateSettings { it.copy(playlistSingleOnly = c) } }
            )
            SettingsSwitchRow(
                title = "Strip tracking query params (utm_*, si, igsh)",
                subtitle = "Auto-cleans pasted links while keeping video IDs",
                checked = settings.stripUrlTracking,
                onCheckedChange = { c -> onUpdateSettings { it.copy(stripUrlTracking = c) } }
            )
            SettingsSwitchRow(
                title = "Embed metadata & thumbnail in downloads",
                subtitle = "Writes artist/title/cover tags via FFmpeg",
                checked = settings.embedDownloadMetadata,
                onCheckedChange = { c ->
                    onUpdateSettings { it.copy(embedDownloadMetadata = c, embedDownloadThumbnail = c) }
                }
            )

            HorizontalDivider(color = SlateOutline)

            SponsorBlockCategorySelector(
                title = "Default SponsorBlock Categories",
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
                subtitle = "Post-download FFmpeg silence removal default for new links",
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

        // Studio & Compression Defaults
        SettingsDomainCard(
            title = "Studio, RAW & Crop Defaults",
            domain = EngineDomain.FORGE,
            pillLabel = "Local Forge"
        ) {
            SettingsHeaderWithTip(
                label = "Default Video Codec",
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

            SettingsHeaderWithTip(
                label = "Default Video CRF: ${settings.defaultVideoCrf}",
                topic = SettingTopic.CRF_SLIDER,
                capabilityReport = deviceCapabilities
            )
            Slider(
                value = settings.defaultVideoCrf.toFloat(),
                onValueChange = { v -> onUpdateSettings { it.copy(defaultVideoCrf = v.roundToInt()) } },
                valueRange = 16f..34f
            )

            Text("Default RAW Export Format", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
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
                label = "Default Target Filesize (MB)",
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

            Text("Default Crop Aspect Snap", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
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
                title = "Fast half-size RAW demosaic by default",
                subtitle = "Halves dimensions on massive sensors for 4x speed",
                checked = settings.rawHalfSizeFast,
                onCheckedChange = { c -> onUpdateSettings { it.copy(rawHalfSizeFast = c) } },
                infoTopic = SettingTopic.RAW_HALF_SIZE_FAST,
                capabilityReport = deviceCapabilities
            )
        }

        // Privacy, Concurrency, & Filenames
        SettingsDomainCard(
            title = "Privacy, Concurrency & Naming",
            domain = EngineDomain.DELIVERY,
            pillLabel = "Workshop Policy"
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
                title = "Strip EXIF / GPS when copying to clipboard",
                subtitle = "Protects location data when pasting into chat apps",
                checked = settings.stripMetadataOnClipboard,
                onCheckedChange = { c -> onUpdateSettings { it.copy(stripMetadataOnClipboard = c) } },
                infoTopic = SettingTopic.STRIP_METADATA,
                capabilityReport = deviceCapabilities
            )
            SettingsSwitchRow(
                title = "Strip EXIF / GPS when saving to device",
                subtitle = "Applies to saved gallery exports too",
                checked = settings.stripMetadataOnSave,
                onCheckedChange = { c -> onUpdateSettings { it.copy(stripMetadataOnSave = c) } },
                infoTopic = SettingTopic.STRIP_METADATA,
                capabilityReport = deviceCapabilities
            )

            SettingsHeaderWithTip(
                label = "Max Concurrent Downloads: ${settings.maxConcurrentDownloads}",
                topic = SettingTopic.CONCURRENCY_SLOTS,
                capabilityReport = deviceCapabilities
            )
            Slider(
                value = settings.maxConcurrentDownloads.toFloat(),
                onValueChange = { v -> onUpdateSettings { it.copy(maxConcurrentDownloads = v.roundToInt()) } },
                valueRange = 1f..5f,
                steps = 3
            )

            SettingsHeaderWithTip(
                label = if (settings.computeSlotsOverride == 0) {
                    "Compute Concurrency: Auto (${deviceCapabilities.recommendedComputeSlots} slots)"
                } else {
                    "Compute Concurrency: ${settings.computeSlotsOverride} slots"
                },
                topic = SettingTopic.CONCURRENCY_SLOTS,
                capabilityReport = deviceCapabilities
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    0 to "Auto (${deviceCapabilities.recommendedComputeSlots})",
                    2 to "2 Slots",
                    3 to "3 Slots",
                    4 to "4 Slots"
                ).forEach { (slots, label) ->
                    FilterChip(
                        selected = settings.computeSlotsOverride == slots,
                        onClick = { onUpdateSettings { it.copy(computeSlotsOverride = slots) } },
                        label = { Text(label) }
                    )
                }
            }

            OutlinedTextField(
                value = settings.filenameSuffix,
                onValueChange = { txt -> onUpdateSettings { it.copy(filenameSuffix = txt.take(16)) } },
                label = { Text("Export Filename Suffix") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Engine Maintenance
        SettingsDomainCard(
            title = "yt-dlp Engine & Cache Maintenance",
            domain = EngineDomain.DELIVERY,
            pillLabel = "Engine Tools",
            isActive = isUpdatingYtDlp
        ) {
            Text(
                text = "yt-dlp version: $ytDlpVersion",
                style = MaterialTheme.typography.bodyMedium,
                color = CrispWhite
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
                Text(String.format("Clear Staging & Export Cache (%.1f MB)", cacheMb))
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
                pendingImportService = resolvedService
                cookiesPickerLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
            }
        )
    }
}

@Composable
private fun SettingsDomainCard(
    title: String,
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = CrispWhite,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                DomainRolePill(domain = domain, label = pillLabel)
            }
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
