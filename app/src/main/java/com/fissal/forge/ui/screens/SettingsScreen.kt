package com.fissal.forge.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.ui.theme.AmberActive
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.SlateSurface
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
    onCheckAppUpdate: () -> Unit,
    onStartAppUpdate: () -> Unit,
    onCancelAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onUpdateSettings: ((ForgeSettings) -> ForgeSettings) -> Unit,
    onUpdateYtDlp: (nightly: Boolean) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // GitHub Release App Auto-Updater
        SettingsCard(title = "App Updates (GitHub Releases)") {
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
                    color = MasserCrimson,
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
                        Button(
                            onClick = onStartAppUpdate,
                            colors = ButtonDefaults.buttonColors(containerColor = MasserCrimson),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Update ${release?.tagName ?: ""}", maxLines = 1)
                        }
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
                        Button(
                            onClick = onInstallAppUpdate,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess, contentColor = CrispWhite),
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

        // Downloader Defaults
        SettingsCard(title = "Downloader Defaults") {
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
                onCheckedChange = { c -> onUpdateSettings { it.copy(useAria2c = c) } }
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
        }

        // Studio & Compression Defaults
        SettingsCard(title = "Studio, RAW & Crop Defaults") {
            Text("Default Video Codec", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
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

            Text("Default Video CRF: ${settings.defaultVideoCrf}", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
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

            Text("Default Target Filesize (MB)", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
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
                onCheckedChange = { c -> onUpdateSettings { it.copy(rawHalfSizeFast = c) } }
            )
        }

        // Privacy, Concurrency, & Filenames
        SettingsCard(title = "Privacy, Concurrency & Naming") {
            SettingsSwitchRow(
                title = "Strip EXIF / GPS when copying to clipboard",
                subtitle = "Protects location data when pasting into chat apps",
                checked = settings.stripMetadataOnClipboard,
                onCheckedChange = { c -> onUpdateSettings { it.copy(stripMetadataOnClipboard = c) } }
            )
            SettingsSwitchRow(
                title = "Strip EXIF / GPS when saving to device",
                subtitle = "Applies to saved gallery exports too",
                checked = settings.stripMetadataOnSave,
                onCheckedChange = { c -> onUpdateSettings { it.copy(stripMetadataOnSave = c) } }
            )

            Text(
                text = "Max Concurrent Downloads: ${settings.maxConcurrentDownloads}",
                style = MaterialTheme.typography.labelMedium,
                color = SecundaSilver
            )
            Slider(
                value = settings.maxConcurrentDownloads.toFloat(),
                onValueChange = { v -> onUpdateSettings { it.copy(maxConcurrentDownloads = v.roundToInt()) } },
                valueRange = 1f..5f,
                steps = 3
            )

            Text(
                text = if (settings.computeSlotsOverride == 0) "Compute Concurrency: Auto (Device RAM)" else "Compute Concurrency: ${settings.computeSlotsOverride} slots",
                style = MaterialTheme.typography.labelMedium,
                color = SecundaSilver
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Auto RAM", 2 to "2 Slots", 3 to "3 Slots", 4 to "4 Slots").forEach { (slots, label) ->
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
        SettingsCard(title = "yt-dlp Engine & Cache Maintenance") {
            Text(
                text = "yt-dlp version: $ytDlpVersion",
                style = MaterialTheme.typography.bodyMedium,
                color = CrispWhite
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onUpdateYtDlp(false) },
                    enabled = !isUpdatingYtDlp,
                    colors = ButtonDefaults.buttonColors(containerColor = MasserCrimson),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isUpdatingYtDlp) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CrispWhite)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Update Stable")
                }
                OutlinedButton(
                    onClick = { onUpdateYtDlp(true) },
                    enabled = !isUpdatingYtDlp,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Update Nightly")
                }
            }

            val cacheMb = cacheSizeBytes / (1024.0 * 1024.0)
            OutlinedButton(
                onClick = onClearCache,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(String.format("Clear Staging & Export Cache (%.1f MB)", cacheMb))
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SlateOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = CrispWhite)
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
