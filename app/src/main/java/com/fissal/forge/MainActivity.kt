package com.fissal.forge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fissal.forge.ui.ForgeViewModel
import com.fissal.forge.ui.MainTab
import com.fissal.forge.ui.screens.HubScreen
import com.fissal.forge.ui.screens.QueueScreen
import com.fissal.forge.ui.screens.SettingsScreen
import com.fissal.forge.ui.screens.StudioEditorScreen
import com.fissal.forge.ui.theme.AmberActive
import com.fissal.forge.ui.theme.AnvilGunmetal
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.RedfurForgeTheme
import com.fissal.forge.ui.theme.RelayBevelOutline
import com.fissal.forge.ui.theme.RelaySurface
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.VolcanicObsidian
import com.fissal.forge.update.AppUpdateManager
import com.fissal.forge.update.AppUpdatePhase
import com.fissal.forge.update.AppUpdateState
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: ForgeViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            RedfurForgeTheme {
                val selectedTab by viewModel.selectedTab.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val jobs by viewModel.jobs.collectAsState()
                val metrics by viewModel.metrics.collectAsState()
                val urlBatchText by viewModel.urlBatchText.collectAsState()
                val resolvedLinks by viewModel.resolvedLinks.collectAsState()
                val studioSession by viewModel.studioSession.collectAsState()
                val ytDlpVersion by viewModel.ytDlpVersion.collectAsState()
                val isUpdatingYtDlp by viewModel.isUpdatingYtDlp.collectAsState()
                val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsState()
                val statusBanner by viewModel.statusBanner.collectAsState()
                val appUpdateState by viewModel.appUpdateState.collectAsState()
                val deviceCapabilities = viewModel.deviceCapabilities

                val snackbarHostState = remember { SnackbarHostState() }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

                val unknownSourcesLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    if (packageManager.canRequestPackageInstalls()) {
                        when (val action = viewModel.prepareAppUpdateInstall()) {
                            is AppUpdateManager.InstallAction.LaunchInstaller -> startActivity(action.intent)
                            is AppUpdateManager.InstallAction.MissingApk -> viewModel.showBanner(action.reason)
                            is AppUpdateManager.InstallAction.RequestUnknownSourcesPermission -> Unit
                        }
                    } else {
                        viewModel.showBanner("Allow install permission for Redfur Forge to finish updating")
                    }
                }

                fun launchOrRequestInstall() {
                    when (val action = viewModel.prepareAppUpdateInstall()) {
                        is AppUpdateManager.InstallAction.LaunchInstaller -> startActivity(action.intent)
                        is AppUpdateManager.InstallAction.RequestUnknownSourcesPermission ->
                            unknownSourcesLauncher.launch(action.intent)
                        is AppUpdateManager.InstallAction.MissingApk -> viewModel.showBanner(action.reason)
                    }
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                LaunchedEffect(statusBanner) {
                    val msg = statusBanner
                    if (!msg.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearStatusBanner()
                    }
                }

                LaunchedEffect(appUpdateState.phase, appUpdateState.autoInstallWhenDownloaded) {
                    if (appUpdateState.phase == AppUpdatePhase.READY_TO_INSTALL &&
                        appUpdateState.autoInstallWhenDownloaded
                    ) {
                        viewModel.consumeAutoInstallFlag()
                        launchOrRequestInstall()
                    }
                }

                val multiFilePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        uris.forEach { uri ->
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                        }
                        viewModel.openStudioForUris(uris, fromShareIntent = false)
                    }
                }

                val currentStudio = studioSession
                if (currentStudio != null) {
                    StudioEditorScreen(
                        state = currentStudio,
                        jobs = jobs,
                        deviceCapabilities = deviceCapabilities,
                        buildSpecForItem = { probe, cfg, forceCompress ->
                            viewModel.buildPipelineSpecForStudioItem(
                                probe = probe,
                                cfg = cfg,
                                forceCompressSpec = forceCompress
                            )
                        },
                        onUpdate = viewModel::updateStudio,
                        onSelectTab = viewModel::updateStudioTab,
                        onToggleSamplePreview = viewModel::toggleSamplePreviewComparison,
                        onClearInlineWatch = viewModel::clearInlineWatchJobs,
                        onSelectBatchItem = viewModel::selectStudioItem,
                        onApplySameType = viewModel::applyStudioToSameType,
                        onApplyAll = viewModel::applyStudioToAll,
                        onSelectCropPreset = viewModel::selectStudioCropPreset,
                        onExecute = viewModel::enqueueStudioSession,
                        onRunInBackground = viewModel::moveInlineStudioToBackground,
                        onClose = viewModel::closeStudioSession
                    )
                } else {
                    Scaffold(
                        containerColor = VolcanicObsidian,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = AnvilGunmetal),
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_forge_fissal_emblem),
                                            contentDescription = "Forge Fissal emblem",
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Text(
                                            text = when (selectedTab) {
                                                MainTab.FORGE -> "Redfur Forge"
                                                MainTab.QUEUE -> "Pipeline Queue"
                                                MainTab.SETTINGS -> "Forge Settings"
                                            },
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = CrispWhite
                                        )
                                    }
                                }
                            )
                        },
                        bottomBar = {
                            NavigationBar(containerColor = AnvilGunmetal) {
                                NavigationBarItem(
                                    selected = selectedTab == MainTab.FORGE,
                                    onClick = { viewModel.selectTab(MainTab.FORGE) },
                                    icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                                    label = { Text("Forge") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = EmberFlame,
                                        selectedTextColor = EmberFlame,
                                        indicatorColor = FurnaceCard,
                                        unselectedIconColor = MutedSteel,
                                        unselectedTextColor = MutedSteel
                                    )
                                )
                                NavigationBarItem(
                                    selected = selectedTab == MainTab.QUEUE,
                                    onClick = { viewModel.selectTab(MainTab.QUEUE) },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (metrics.totalActiveJobs > 0) {
                                                    Badge(
                                                        containerColor = MasserCrimson,
                                                        contentColor = ObsidianBg
                                                    ) {
                                                        Text(metrics.totalActiveJobs.toString())
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.AutoMirrored.Rounded.List, contentDescription = null)
                                        }
                                    },
                                    label = { Text("Queue") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = EmberFlame,
                                        selectedTextColor = EmberFlame,
                                        indicatorColor = FurnaceCard,
                                        unselectedIconColor = MutedSteel,
                                        unselectedTextColor = MutedSteel
                                    )
                                )
                                NavigationBarItem(
                                    selected = selectedTab == MainTab.SETTINGS,
                                    onClick = { viewModel.selectTab(MainTab.SETTINGS) },
                                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                    label = { Text("Settings") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = EmberFlame,
                                        selectedTextColor = EmberFlame,
                                        indicatorColor = FurnaceCard,
                                        unselectedIconColor = MutedSteel,
                                        unselectedTextColor = MutedSteel
                                    )
                                )
                            }
                        }
                    ) { innerPadding ->
                        Column(modifier = Modifier.padding(innerPadding)) {
                            AppUpdateBanner(
                                updateState = appUpdateState,
                                dismissedTag = settings.dismissedUpdateTag,
                                onStartUpdate = { viewModel.startAppUpdate(autoInstallAfter = true) },
                                onCancelDownload = viewModel::cancelAppUpdate,
                                onInstallNow = { launchOrRequestInstall() },
                                onDismiss = { tag -> viewModel.dismissUpdateBanner(tag) }
                            )

                            Box(modifier = Modifier.weight(1f)) {
                                when (selectedTab) {
                                    MainTab.FORGE -> HubScreen(
                                        urlBatchText = urlBatchText,
                                        resolvedLinks = resolvedLinks,
                                        jobs = jobs,
                                        metrics = metrics,
                                        settings = settings,
                                        deviceCapabilities = deviceCapabilities,
                                        onUrlTextChange = viewModel::updateUrlBatchText,
                                        onPasteClick = viewModel::pasteUrlsFromClipboard,
                                        onToggleChipExpand = viewModel::toggleLinkChipExpanded,
                                        onRetryResolveLink = viewModel::retryResolveLink,
                                        onRemoveChip = viewModel::removeResolvedLink,
                                        onUpdateChipSettings = viewModel::updateLinkChipSettings,
                                        onTogglePlaylistTrack = viewModel::togglePlaylistTrack,
                                        onSetAllPlaylistTracks = viewModel::setAllPlaylistTracks,
                                        onApplyToSameType = viewModel::applySettingsToSameType,
                                        onApplyToSameSite = viewModel::applySettingsToSameSite,
                                        onApplyToAllLinks = viewModel::applySettingsToAllLinks,
                                        onApplyQuickBatchPreset = viewModel::applyQuickBatchPreset,
                                        onQueueBatch = viewModel::queueResolvedBatch,
                                        onPickFilesClick = { multiFilePicker.launch(arrayOf("*/*")) },
                                        onOpenQueueClick = { viewModel.selectTab(MainTab.QUEUE) },
                                        onCancelAll = viewModel::cancelAllJobs,
                                        onRemovePlaylistGroup = viewModel::removePlaylistGroup
                                    )
                                    MainTab.QUEUE -> QueueScreen(
                                        jobs = jobs,
                                        metrics = metrics,
                                        onCopyJob = viewModel::copyCompletedJobToClipboard,
                                        onShareJob = viewModel::buildShareIntentForJob,
                                        onCancelJob = viewModel::cancelJob,
                                        onRetryJob = viewModel::retryJob,
                                        onRemoveJob = viewModel::removeJob,
                                        onCancelPlaylistGroup = viewModel::cancelPlaylistGroup,
                                        onRemovePlaylistGroup = viewModel::removePlaylistGroup,
                                        onRetryPlaylistGroup = viewModel::retryPlaylistGroup,
                                        onCancelAll = viewModel::cancelAllJobs,
                                        onClearFinished = viewModel::clearFinishedJobs
                                    )
                                    MainTab.SETTINGS -> SettingsScreen(
                                        settings = settings,
                                        ytDlpVersion = ytDlpVersion,
                                        isUpdatingYtDlp = isUpdatingYtDlp,
                                        cacheSizeBytes = cacheSizeBytes,
                                        appUpdateState = appUpdateState,
                                        deviceCapabilities = deviceCapabilities,
                                        onCheckAppUpdate = viewModel::checkForAppUpdates,
                                        onStartAppUpdate = { viewModel.startAppUpdate(autoInstallAfter = true) },
                                        onCancelAppUpdate = viewModel::cancelAppUpdate,
                                        onInstallAppUpdate = { launchOrRequestInstall() },
                                        onUpdateSettings = viewModel::updateSettings,
                                        onUpdateYtDlp = viewModel::updateYtDlp,
                                        onClearCache = viewModel::clearCache
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val streamUri = extractStreamUri(intent)
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (streamUri != null) {
                    viewModel.openStudioForUris(listOf(streamUri), fromShareIntent = true)
                } else if (!sharedText.isNullOrBlank()) {
                    viewModel.closeStudioSession()
                    viewModel.updateUrlBatchText(sharedText)
                    viewModel.selectTab(MainTab.FORGE)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = extractStreamUriList(intent)
                if (uris.isNotEmpty()) {
                    viewModel.openStudioForUris(uris, fromShareIntent = true)
                }
            }
        }
    }

    private fun extractStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extractStreamUriList(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }
    }
}

@Composable
private fun AppUpdateBanner(
    updateState: AppUpdateState,
    dismissedTag: String,
    onStartUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallNow: () -> Unit,
    onDismiss: (String) -> Unit
) {
    val release = updateState.release
    val visible = when (updateState.phase) {
        AppUpdatePhase.AVAILABLE -> release != null && dismissedTag != release.tagName
        AppUpdatePhase.DOWNLOADING,
        AppUpdatePhase.READY_TO_INSTALL,
        AppUpdatePhase.NEEDS_INSTALL_PERMISSION -> true
        else -> false
    }

    AnimatedVisibility(visible = visible) {
        Surface(
            color = RelaySurface,
            border = BorderStroke(1.dp, ArcRelayCyan.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = when (updateState.phase) {
                            AppUpdatePhase.READY_TO_INSTALL -> EmeraldSuccess
                            AppUpdatePhase.DOWNLOADING, AppUpdatePhase.NEEDS_INSTALL_PERMISSION -> AmberActive
                            else -> MasserCrimson
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (updateState.phase) {
                                AppUpdatePhase.AVAILABLE -> "Update ${release?.tagName.orEmpty()} available"
                                AppUpdatePhase.DOWNLOADING -> "Downloading ${release?.tagName.orEmpty()}"
                                AppUpdatePhase.READY_TO_INSTALL -> "${release?.tagName.orEmpty()} ready to install"
                                AppUpdatePhase.NEEDS_INSTALL_PERMISSION -> "Permission needed for ${release?.tagName.orEmpty()}"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = CrispWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val detail = when (updateState.phase) {
                            AppUpdatePhase.DOWNLOADING -> {
                                val dlMb = updateState.downloadedBytes / (1024.0 * 1024.0)
                                val totMb = updateState.totalBytes / (1024.0 * 1024.0)
                                if (updateState.totalBytes > 0L) {
                                    String.format(Locale.US, "%.1f / %.1f MB", dlMb, totMb)
                                } else {
                                    String.format(Locale.US, "%.1f MB", dlMb)
                                }
                            }
                            AppUpdatePhase.READY_TO_INSTALL -> "Verified SHA-256 • Tap to apply"
                            AppUpdatePhase.NEEDS_INSTALL_PERMISSION -> "Allow installs from Redfur Forge"
                            else -> release?.selectedAsset?.name ?: "GitHub Release"
                        }
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = SecundaSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    when (updateState.phase) {
                        AppUpdatePhase.AVAILABLE -> {
                            Button(
                                onClick = onStartUpdate,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ArcRelayCyan, contentColor = ObsidianBg)
                            ) {
                                Text("Update", style = MaterialTheme.typography.labelMedium)
                            }
                            if (release != null) {
                                IconButton(
                                    onClick = { onDismiss(release.tagName) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Dismiss update banner",
                                        tint = MutedSteel,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        AppUpdatePhase.DOWNLOADING -> {
                            OutlinedButton(
                                onClick = onCancelDownload,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, SlateOutline)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.labelMedium, color = CoralError)
                            }
                        }
                        AppUpdatePhase.READY_TO_INSTALL, AppUpdatePhase.NEEDS_INSTALL_PERMISSION -> {
                            Button(
                                onClick = onInstallNow,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess, contentColor = ObsidianBg)
                            ) {
                                Text(
                                    if (updateState.phase == AppUpdatePhase.NEEDS_INSTALL_PERMISSION) "Allow"
                                    else "Install",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        else -> Unit
                    }
                }

                if (updateState.phase == AppUpdatePhase.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { updateState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = ArcRelayCyan,
                        trackColor = RelayBevelOutline
                    )
                }
            }
        }
    }
}
