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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.fissal.forge.ui.ForgeViewModel
import com.fissal.forge.ui.MainTab
import com.fissal.forge.ui.screens.HubScreen
import com.fissal.forge.ui.screens.QueueScreen
import com.fissal.forge.ui.screens.SettingsScreen
import com.fissal.forge.ui.screens.StudioEditorScreen
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.RedfurForgeTheme
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateSurface

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

                val snackbarHostState = remember { SnackbarHostState() }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

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
                        onUpdate = viewModel::updateStudio,
                        onSelectTab = viewModel::updateStudioTab,
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
                        containerColor = ObsidianBg,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateSurface),
                                title = {
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
                            )
                        },
                        bottomBar = {
                            NavigationBar(containerColor = SlateSurface) {
                                NavigationBarItem(
                                    selected = selectedTab == MainTab.FORGE,
                                    onClick = { viewModel.selectTab(MainTab.FORGE) },
                                    icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                                    label = { Text("Forge") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MasserCrimson,
                                        selectedTextColor = MasserCrimson,
                                        indicatorColor = SlateCard,
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
                                                    Badge(containerColor = MasserCrimson) {
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
                                        selectedIconColor = MasserCrimson,
                                        selectedTextColor = MasserCrimson,
                                        indicatorColor = SlateCard,
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
                                        selectedIconColor = MasserCrimson,
                                        selectedTextColor = MasserCrimson,
                                        indicatorColor = SlateCard,
                                        unselectedIconColor = MutedSteel,
                                        unselectedTextColor = MutedSteel
                                    )
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            when (selectedTab) {
                                MainTab.FORGE -> HubScreen(
                                    urlBatchText = urlBatchText,
                                    resolvedLinks = resolvedLinks,
                                    jobs = jobs,
                                    metrics = metrics,
                                    settings = settings,
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
