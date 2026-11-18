package com.fissal.forge.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.domain.logic.BitrateBudgetCalculator
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.FilesizeEstimator
import com.fissal.forge.domain.logic.SettingTopic
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.DetectedLinkKind
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.LinkItemSettings
import com.fissal.forge.domain.model.LinkResolveState
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.pipeline.OrchestratorMetrics
import com.fissal.forge.ui.components.DomainRolePill
import com.fissal.forge.ui.components.EngineDomain
import com.fissal.forge.ui.components.ForgeInfoTipButton
import com.fissal.forge.ui.components.ForgePrimaryButton
import com.fissal.forge.ui.components.LiveSizeEstimateBadge
import com.fissal.forge.ui.components.RelayPrimaryButton
import com.fissal.forge.ui.components.SilenceSkipControlPanel
import com.fissal.forge.ui.components.SponsorBlockCategorySelector
import com.fissal.forge.ui.components.WorkshopDomainCard
import com.fissal.forge.ui.theme.AmberActive
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.ForgeBevelOutline
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.RelayBevelOutline
import com.fissal.forge.ui.theme.RelayCard
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.SlateSurface
import com.fissal.forge.ui.theme.VolcanicObsidian
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HubScreen(
    isDownloadWorkspaceOpen: Boolean,
    urlBatchText: String,
    resolvedLinks: List<ResolvedLinkChipItem>,
    metrics: OrchestratorMetrics,
    settings: ForgeSettings,
    deviceCapabilities: DeviceCapabilityReport,
    isUpdatingYtDlp: Boolean,
    onOpenDownloadWorkspace: () -> Unit,
    onCloseDownloadWorkspace: () -> Unit,
    onUrlTextChange: (String) -> Unit,
    onPasteClick: () -> Unit,
    onToggleChipExpand: (String) -> Unit,
    onRetryResolveLink: (String) -> Unit,
    onRemoveChip: (String) -> Unit,
    onClearAllResolvedLinks: () -> Unit,
    onUpdateChipSettings: (String, (LinkItemSettings) -> LinkItemSettings) -> Unit,
    onTogglePlaylistTrack: (String, Int) -> Unit,
    onSetAllPlaylistTracks: (String, Boolean) -> Unit,
    onSetSplitByChapters: (String, Boolean) -> Unit,
    onToggleChapterTrack: (String, Int) -> Unit,
    onSetAllChapterTracks: (String, Boolean) -> Unit,
    onSetBulkChapterMode: (String, DownloadMode, String?) -> Unit,
    onToggleChapterTrackModeOverride: (String, Int) -> Unit,
    onSetChapterTrackAudioFormat: (String, Int, String) -> Unit,
    onApplyToSameType: (String) -> Unit,
    onApplyToSameSite: (String) -> Unit,
    onApplyToAllLinks: (String) -> Unit,
    onApplyQuickBatchPreset: ((LinkItemSettings) -> LinkItemSettings) -> Unit,
    onQueueBatch: (copyToClipboard: Boolean, saveToDevice: Boolean) -> Unit,
    onPickFilesClick: () -> Unit,
    onOpenSampleLabPicker: () -> Unit,
    onPromptSignInForChip: (String) -> Unit
) {
    val totalQueuedItems = remember(resolvedLinks) {
        resolvedLinks.sumOf { chip ->
            when {
                chip.effectivePlaylistActive && chip.tracks.isNotEmpty() -> chip.selectedTrackCount
                !chip.effectivePlaylistActive && chip.settings.splitByChapters && chip.chapters.size >= 2 ->
                    chip.selectedChapterCount
                else -> 1
            }
        }
    }

    val batchEstimate = remember(resolvedLinks, settings.defaultVideoCrf) {
        FilesizeEstimator.estimateBatchLinks(resolvedLinks, settings.defaultVideoCrf)
    }

    if (!isDownloadWorkspaceOpen) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(VolcanicObsidian),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "home_header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Choose a Workflow",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = CrispWhite
                    )
                    Text(
                        text = "Download media from links or edit local files on device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecundaSilver
                    )
                }
            }

            // Primary Hero Button 1: Download Media
            item(key = "hero_download_media") {
                WorkshopDomainCard(
                    domain = EngineDomain.RELAY,
                    isActive = isUpdatingYtDlp || metrics.activeNetworkCount > 0,
                    onClick = onOpenDownloadWorkspace,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                color = RelayCard,
                                border = BorderStroke(1.25.dp, ArcRelayCyan.copy(alpha = 0.65f)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.size(58.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.CloudDownload,
                                        contentDescription = null,
                                        tint = ArcRelayCyan,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Download Media",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = CrispWhite,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                    DomainRolePill(
                                        domain = EngineDomain.RELAY,
                                        label = "Links",
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                }
                                Text(
                                    text = "Save videos, music, playlists, and chapters from YouTube, SoundCloud, TikTok, and 1,000+ supported sites.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SecundaSilver
                                )
                            }
                        }

                        if (resolvedLinks.isNotEmpty()) {
                            Surface(
                                color = ArcRelayCyan.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, ArcRelayCyan.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "${resolvedLinks.size} link${if (resolvedLinks.size == 1) "" else "s"} staged and ready",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ArcRelayCyan,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        RelayPrimaryButton(
                            onClick = onOpenDownloadWorkspace,
                            text = if (resolvedLinks.isNotEmpty()) {
                                "Open Link Downloader (${resolvedLinks.size})"
                            } else {
                                "Open Link Downloader"
                            },
                            icon = Icons.Rounded.Download,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Primary Hero Button 2: Forge Studio
            item(key = "hero_forge_studio") {
                WorkshopDomainCard(
                    domain = EngineDomain.FORGE,
                    isActive = metrics.activeComputeSlotsUsed > 0,
                    onClick = onPickFilesClick,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                color = FurnaceCard,
                                border = BorderStroke(1.25.dp, EmberFlame.copy(alpha = 0.65f)),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.size(58.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Tune,
                                        contentDescription = null,
                                        tint = EmberFlame,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Forge Studio",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = CrispWhite,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                    DomainRolePill(
                                        domain = EngineDomain.FORGE,
                                        label = "Local Edit",
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                }
                                Text(
                                    text = "Convert formats, shrink to an exact MB cap (AV1/HEVC/Opus), trim with live preview, and crop photos or Camera RAWs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SecundaSilver
                                )
                            }
                        }

                        ForgePrimaryButton(
                            onClick = onPickFilesClick,
                            text = "Open Forge Studio",
                            icon = Icons.Rounded.FolderOpen,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Secondary Workshop Utility: Sample Frame Lab
            item(key = "sample_lab_shortcut") {
                WorkshopDomainCard(
                    domain = EngineDomain.DELIVERY,
                    isActive = false,
                    onClick = onOpenSampleLabPicker,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = SlateCard,
                            border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Compare,
                                    contentDescription = null,
                                    tint = EmberFlame,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Sample Frame Lab",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = CrispWhite
                            )
                            Text(
                                text = "Test multiple codecs & sizes side-by-side before full encoding",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSteel
                            )
                        }
                        Text(
                            text = "Launch",
                            style = MaterialTheme.typography.labelLarge,
                            color = EmberFlame,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        return
    }

    BackHandler(onBack = onCloseDownloadWorkspace)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(VolcanicObsidian),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Downloader Navigation Bar
        item(key = "downloader_nav") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCloseDownloadWorkspace,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SlateOutline),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = SecundaSilver,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Forge Home", style = MaterialTheme.typography.labelLarge, color = SecundaSilver)
                }

                OutlinedButton(
                    onClick = onPickFilesClick,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ForgeBevelOutline),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = EmberFlame,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Forge Studio", style = MaterialTheme.typography.labelLarge, color = CrispWhite)
                }
            }
        }

        // 2. Link Input & Quick Presets (Arc-Relay Domain)
        item(key = "batch_url_input") {
            WorkshopDomainCard(
                domain = EngineDomain.RELAY,
                isActive = resolvedLinks.any { it.resolveState == LinkResolveState.RESOLVING },
                contentPadding = PaddingValues(16.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Link Downloader",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CrispWhite,
                                    fontWeight = FontWeight.SemiBold
                                )
                                DomainRolePill(domain = EngineDomain.RELAY, label = "Auto-Resolve")
                            }
                            Text(
                                text = "Resolvable links auto-clear from the box and stay staged below",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedSteel
                            )
                        }
                        if (resolvedLinks.isNotEmpty()) {
                            Surface(
                                color = ArcRelayCyan.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, ArcRelayCyan.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "$totalQueuedItems item${if (totalQueuedItems == 1) "" else "s"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ArcRelayCyan,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = urlBatchText,
                        onValueChange = onUrlTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        placeholder = {
                            Text(
                                "Paste 1 or more links (YouTube, playlists, SoundCloud, TikTok, direct URLs)...",
                                color = MutedSteel
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onPasteClick) {
                                    Icon(
                                        Icons.Rounded.ContentPaste,
                                        contentDescription = "Paste from clipboard",
                                        tint = ArcRelayCyan
                                    )
                                }
                                if (urlBatchText.isNotEmpty()) {
                                    IconButton(onClick = { onUrlTextChange("") }) {
                                        Icon(
                                            Icons.Rounded.Clear,
                                            contentDescription = "Clear text box",
                                            tint = MutedSteel
                                        )
                                    }
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ArcRelayCyan,
                            unfocusedBorderColor = RelayBevelOutline,
                            focusedTextColor = CrispWhite,
                            unfocusedTextColor = CrispWhite,
                            cursorColor = ArcRelayCyan
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    if (resolvedLinks.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Quick Format Presets",
                                style = MaterialTheme.typography.labelSmall,
                                color = SecundaSilver
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                QuickPresetPill("All MP4 1080p") {
                                    onApplyQuickBatchPreset {
                                        it.copy(
                                            mode = DownloadMode.VIDEO_MP4,
                                            maxHeight = 1080,
                                            targetSizeMb = null,
                                            scopeBadge = "Batch Preset"
                                        )
                                    }
                                }
                                QuickPresetPill("All Best MKV") {
                                    onApplyQuickBatchPreset {
                                        it.copy(
                                            mode = DownloadMode.VIDEO_BEST_MKV,
                                            maxHeight = null,
                                            targetSizeMb = null,
                                            scopeBadge = "Batch Preset"
                                        )
                                    }
                                }
                                QuickPresetPill("All Audio MP3") {
                                    onApplyQuickBatchPreset {
                                        it.copy(
                                            mode = DownloadMode.AUDIO_ONLY,
                                            audioFormat = "mp3",
                                            targetSizeMb = null,
                                            scopeBadge = "Batch Preset"
                                        )
                                    }
                                }
                                QuickPresetPill("AV1 ≤ 25 MB") {
                                    onApplyQuickBatchPreset {
                                        it.copy(
                                            mode = DownloadMode.VIDEO_MP4,
                                            targetSizeMb = 25.0,
                                            compressionTier = CompressionTier.PERCEPTUAL_AV1,
                                            scopeBadge = "AV1 ≤ 25MB"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Staged Resolved Media List
        if (resolvedLinks.isNotEmpty()) {
            item(key = "staged_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Staged Media (${resolvedLinks.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = CrispWhite
                    )
                    TextButton(
                        onClick = onClearAllResolvedLinks,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            tint = MutedSteel,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Clear Staged", style = MaterialTheme.typography.labelMedium, color = MutedSteel)
                    }
                }
            }

            items(resolvedLinks, key = { it.cleanUrl }) { chip ->
                ResolvedLinkChipCard(
                    chip = chip,
                    defaultTargetMb = settings.defaultTargetSizeMb,
                    defaultVideoCrf = settings.defaultVideoCrf,
                    deviceCapabilities = deviceCapabilities,
                    onToggleExpand = { onToggleChipExpand(chip.cleanUrl) },
                    onRetryResolve = { onRetryResolveLink(chip.cleanUrl) },
                    onPromptSignIn = { onPromptSignInForChip(chip.cleanUrl) },
                    onRemove = { onRemoveChip(chip.cleanUrl) },
                    onUpdateSettings = { transform -> onUpdateChipSettings(chip.cleanUrl, transform) },
                    onTogglePlaylistTrack = { idx -> onTogglePlaylistTrack(chip.cleanUrl, idx) },
                    onSetAllPlaylistTracks = { selected -> onSetAllPlaylistTracks(chip.cleanUrl, selected) },
                    onSetSplitByChapters = { enabled -> onSetSplitByChapters(chip.cleanUrl, enabled) },
                    onToggleChapterTrack = { idx -> onToggleChapterTrack(chip.cleanUrl, idx) },
                    onSetAllChapterTracks = { selected -> onSetAllChapterTracks(chip.cleanUrl, selected) },
                    onSetBulkChapterMode = { mode, fmt -> onSetBulkChapterMode(chip.cleanUrl, mode, fmt) },
                    onToggleChapterModeOverride = { idx -> onToggleChapterTrackModeOverride(chip.cleanUrl, idx) },
                    onSetChapterAudioFormat = { idx, fmt -> onSetChapterTrackAudioFormat(chip.cleanUrl, idx, fmt) },
                    onApplyToSameType = { onApplyToSameType(chip.cleanUrl) },
                    onApplyToSameSite = { onApplyToSameSite(chip.cleanUrl) },
                    onApplyToAllLinks = { onApplyToAllLinks(chip.cleanUrl) }
                )
            }

            // 4. Batch Queue Action Bar
            item(key = "batch_actions") {
                WorkshopDomainCard(
                    domain = EngineDomain.RELAY,
                    isActive = false,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val hasCustomCap = resolvedLinks.any { (it.settings.targetSizeMb ?: 0.0) > 0.0 }
                        val hasChapterSplit = resolvedLinks.any { it.settings.splitByChapters && it.chapters.size >= 2 }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buildString {
                                    append("Ready: ${resolvedLinks.size} link${if (resolvedLinks.size == 1) "" else "s"} -> ")
                                    append("$totalQueuedItems task${if (totalQueuedItems == 1) "" else "s"}")
                                    if (hasCustomCap || hasChapterSplit) append(" • Includes Post-Processing")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = SecundaSilver,
                                modifier = Modifier.weight(1f)
                            )
                            if (batchEstimate != null) {
                                Spacer(Modifier.width(8.dp))
                                LiveSizeEstimateBadge(
                                    estimate = batchEstimate,
                                    domain = EngineDomain.RELAY,
                                    prefix = "Est."
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val canQueue = totalQueuedItems > 0
                            OutlinedButton(
                                onClick = { onQueueBatch(true, false) },
                                enabled = canQueue,
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp),
                                border = BorderStroke(1.dp, if (canQueue) RelayBevelOutline else SlateOutline),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = SecundaSilver)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy Clip", maxLines = 1)
                            }
                            RelayPrimaryButton(
                                onClick = { onQueueBatch(false, true) },
                                enabled = canQueue,
                                text = if (hasCustomCap || hasChapterSplit) {
                                    "Download & Forge ($totalQueuedItems)"
                                } else {
                                    "Start Download ($totalQueuedItems)"
                                },
                                icon = Icons.Rounded.Download,
                                modifier = Modifier.weight(1.45f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPresetPill(label: String, onClick: () -> Unit) {
    Surface(
        color = SlateCard,
        border = BorderStroke(1.dp, SlateOutline),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = SecundaSilver,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResolvedLinkChipCard(
    chip: ResolvedLinkChipItem,
    defaultTargetMb: Int,
    defaultVideoCrf: Int,
    deviceCapabilities: DeviceCapabilityReport,
    onToggleExpand: () -> Unit,
    onRetryResolve: () -> Unit,
    onPromptSignIn: () -> Unit,
    onRemove: () -> Unit,
    onUpdateSettings: ((LinkItemSettings) -> LinkItemSettings) -> Unit,
    onTogglePlaylistTrack: (Int) -> Unit,
    onSetAllPlaylistTracks: (Boolean) -> Unit,
    onSetSplitByChapters: (Boolean) -> Unit,
    onToggleChapterTrack: (Int) -> Unit,
    onSetAllChapterTracks: (Boolean) -> Unit,
    onSetBulkChapterMode: (DownloadMode, String?) -> Unit,
    onToggleChapterModeOverride: (Int) -> Unit,
    onSetChapterAudioFormat: (Int, String) -> Unit,
    onApplyToSameType: () -> Unit,
    onApplyToSameSite: () -> Unit,
    onApplyToAllLinks: () -> Unit
) {
    val isPlaylistChip = chip.effectivePlaylistActive

    val kindColor = when (chip.detectedKind) {
        DetectedLinkKind.AUDIO -> Color(0xFF4FD1C5)
        DetectedLinkKind.PLAYLIST -> AmberActive
        DetectedLinkKind.IMAGE -> EmeraldSuccess
        DetectedLinkKind.VIDEO -> ArcRelayCyan
    }

    val chipEstimate = remember(chip, defaultVideoCrf) {
        FilesizeEstimator.estimateLinkChip(chip, defaultVideoCrf)
    }

    WorkshopDomainCard(
        domain = EngineDomain.RELAY,
        isActive = chip.resolveState == LinkResolveState.RESOLVING,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Top Resolved Chip Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Thumbnail Preview or Detected Media Type Icon
                Surface(
                    color = SlateCard,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SlateOutline),
                    modifier = Modifier.size(width = 64.dp, height = 48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (!chip.thumbnailUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = chip.thumbnailUrl,
                                contentDescription = chip.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = when (chip.detectedKind) {
                                    DetectedLinkKind.AUDIO -> Icons.Rounded.MusicNote
                                    DetectedLinkKind.PLAYLIST -> Icons.AutoMirrored.Rounded.QueueMusic
                                    DetectedLinkKind.IMAGE -> Icons.Rounded.Image
                                    DetectedLinkKind.VIDEO -> Icons.Rounded.Videocam
                                },
                                contentDescription = null,
                                tint = kindColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Metadata Badges + Title + Format Summary
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BadgePill(
                            text = if (chip.tracks.isNotEmpty()) "${chip.detectedKind.label} (${chip.tracks.size})"
                            else chip.detectedKind.label,
                            bg = kindColor.copy(alpha = 0.16f),
                            fg = kindColor
                        )
                        if (chip.host.isNotBlank()) {
                            BadgePill(text = chip.host, bg = SlateCard, fg = SecundaSilver)
                        }
                        chip.formattedDuration()?.let { dur ->
                            BadgePill(text = dur, bg = SlateCard, fg = CrispWhite)
                        }
                        chip.settings.scopeBadge?.let { scope ->
                            BadgePill(
                                text = scope,
                                bg = ArcRelayCyan.copy(alpha = 0.16f),
                                fg = ArcRelayCyan
                            )
                        }
                        if (!isPlaylistChip && chip.chapters.size >= 2) {
                            BadgePill(
                                text = "${chip.chapters.size} Chapters Detected",
                                bg = MoltenEmber.copy(alpha = 0.18f),
                                fg = EmberFlame
                            )
                        }
                        if (!chip.authRequiredReason.isNullOrBlank()) {
                            BadgePill(
                                text = "Sign In Required",
                                bg = CoralError.copy(alpha = 0.18f),
                                fg = CoralError
                            )
                        }
                        if (chipEstimate != null) {
                            LiveSizeEstimateBadge(
                                estimate = chipEstimate,
                                domain = if ((chip.settings.targetSizeMb ?: 0.0) > 0.0) {
                                    EngineDomain.FORGE
                                } else {
                                    EngineDomain.RELAY
                                }
                            )
                        }
                    }

                    Text(
                        text = chip.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = CrispWhite,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    val summaryPrefix = buildString {
                        if (!chip.uploader.isNullOrBlank()) {
                            append(chip.uploader)
                            append(" • ")
                        }
                        append(chip.settings.compactSummary())
                        if (isPlaylistChip && !chip.settings.playlistSingleOnly && chip.tracks.isNotEmpty()) {
                            append(" • ${chip.selectedTrackCount}/${chip.tracks.size} songs")
                        } else if (!isPlaylistChip && chip.settings.splitByChapters && chip.chapters.size >= 2) {
                            val selectedCh = chip.chapters.count { it.selected }
                            append(" • $selectedCh/${chip.chapters.size} chapters")
                        }
                    }
                    Text(
                        text = summaryPrefix,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chip.resolveState == LinkResolveState.FAILED) CoralError else MutedSteel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Resolve Status & Actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (chip.resolveState) {
                        LinkResolveState.RESOLVING -> CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                            color = AmberActive
                        )
                        LinkResolveState.FAILED -> IconButton(
                            onClick = onRetryResolve,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Retry metadata resolve",
                                tint = CoralError,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        LinkResolveState.RESOLVED -> Unit
                    }

                    IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (chip.isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Configure link",
                            tint = SecundaSilver
                        )
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remove link",
                            tint = MutedSteel,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (!chip.authRequiredReason.isNullOrBlank() ||
                (chip.resolveState == LinkResolveState.FAILED && !chip.errorHint.isNullOrBlank())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (!chip.authRequiredReason.isNullOrBlank()) Icons.Rounded.Lock else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = CoralError,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = chip.authRequiredReason ?: "Metadata fallback active: ${chip.errorHint}",
                            style = MaterialTheme.typography.labelSmall,
                            color = CoralError,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!chip.authRequiredReason.isNullOrBlank()) {
                        Button(
                            onClick = onPromptSignIn,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MoltenEmber,
                                contentColor = CrispWhite
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Sign In to Unlock",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // Expanded Per-Chip Options, Playlist Song Visualizer, Target Filesize & Bulk Scope Rules
            AnimatedVisibility(visible = chip.isExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = SlateOutline)

                    // A. Playlist Song Visualizer & Track Selection
                    if (isPlaylistChip) {
                        Surface(
                            color = SlateCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SlateOutline)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Playlist Mode",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SecundaSilver
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = !chip.settings.playlistSingleOnly,
                                        onClick = { onUpdateSettings { it.copy(playlistSingleOnly = false) } },
                                        label = {
                                            Text(
                                                if (chip.tracks.isNotEmpty()) "Full Playlist (${chip.tracks.size})"
                                                else "Full Playlist"
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MasserCrimson.copy(alpha = 0.22f),
                                            selectedLabelColor = CrispWhite
                                        )
                                    )
                                    FilterChip(
                                        selected = chip.settings.playlistSingleOnly,
                                        onClick = { onUpdateSettings { it.copy(playlistSingleOnly = true) } },
                                        label = { Text("Single Item Only") }
                                    )
                                }

                                if (!chip.settings.playlistSingleOnly && chip.tracks.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Tracks: ${chip.selectedTrackCount} / ${chip.tracks.size} selected",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CrispWhite
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(
                                                onClick = { onSetAllPlaylistTracks(true) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("Select All", style = MaterialTheme.typography.labelSmall, color = SecundaSilver)
                                            }
                                            TextButton(
                                                onClick = { onSetAllPlaylistTracks(false) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("Clear", style = MaterialTheme.typography.labelSmall, color = MutedSteel)
                                            }
                                        }
                                    }

                                    // Scrollable bounded track checklist inside chip
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 260.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        chip.tracks.forEach { track ->
                                            val trackEstimate = remember(chip, track) {
                                                FilesizeEstimator.estimatePlaylistTrack(chip, track)
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(SlateSurface)
                                                    .clickable { onTogglePlaylistTrack(track.index) }
                                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Checkbox(
                                                    checked = track.selected,
                                                    onCheckedChange = { onTogglePlaylistTrack(track.index) },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = ArcRelayCyan,
                                                        uncheckedColor = MutedSteel
                                                    ),
                                                    modifier = Modifier.size(28.dp)
                                                )
                                                Text(
                                                    text = "#${track.index}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = SecundaSilver
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = track.title,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = CrispWhite,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    val meta = listOfNotNull(track.uploader, track.formattedDuration())
                                                        .joinToString(" • ")
                                                    if (meta.isNotBlank()) {
                                                        Text(
                                                            text = meta,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MutedSteel,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                                if (trackEstimate != null) {
                                                    BadgePill(
                                                        text = trackEstimate.formattedEstimate(),
                                                        bg = RelayCard,
                                                        fg = ArcRelayCyan
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (!chip.settings.playlistSingleOnly) {
                                    Text(
                                        text = if (chip.resolveState == LinkResolveState.RESOLVING)
                                            "Fetching playlist tracklist..."
                                        else
                                            "Full playlist will fan out into per-song jobs when queued.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MutedSteel
                                    )
                                }
                            }
                        }
                    }

                    // A2. Interactive Chapter Chopper (Single Video Multi-Track Splitting)
                    if (!isPlaylistChip && chip.chapters.size >= 2) {
                        val selectedChaptersCount = chip.chapters.count { it.selected }
                        Surface(
                            color = SlateCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (chip.settings.splitByChapters) MoltenEmber.copy(alpha = 0.7f) else RelayBevelOutline
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Split into Individual Tracks",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = CrispWhite
                                        )
                                        Text(
                                            text = if (chip.settings.splitByChapters) {
                                                "${chip.chapters.size} chapters • downloads once & slices $selectedChaptersCount track${if (selectedChaptersCount == 1) "" else "s"} locally"
                                            } else {
                                                "${chip.chapters.size} chapters detected • turn on to split by timestamp"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MutedSteel
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Switch(
                                        checked = chip.settings.splitByChapters,
                                        onCheckedChange = onSetSplitByChapters
                                    )
                                }

                                if (chip.settings.splitByChapters) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$selectedChaptersCount/${chip.chapters.size} selected",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SecundaSilver
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(
                                                onClick = { onSetAllChapterTracks(true) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("Select All", style = MaterialTheme.typography.labelSmall)
                                            }
                                            TextButton(
                                                onClick = { onSetAllChapterTracks(false) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("Clear", style = MaterialTheme.typography.labelSmall, color = MutedSteel)
                                            }
                                        }
                                    }

                                    // Bulk mode overrides across all chapters
                                    val allVideoSelected = chip.chapters.all {
                                        (it.customSettings?.mode ?: chip.settings.mode) != DownloadMode.AUDIO_ONLY
                                    }
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        FilterChip(
                                            selected = allVideoSelected,
                                            onClick = { onSetBulkChapterMode(DownloadMode.VIDEO_MP4, null) },
                                            label = { Text("Keep Video + Audio") }
                                        )
                                        listOf("mp3", "opus", "flac").forEach { fmt ->
                                            val allThisAudio = chip.chapters.all {
                                                (it.customSettings?.mode ?: chip.settings.mode) == DownloadMode.AUDIO_ONLY &&
                                                    (it.customSettings?.audioFormat ?: chip.settings.audioFormat).equals(fmt, ignoreCase = true)
                                            }
                                            FilterChip(
                                                selected = allThisAudio,
                                                onClick = { onSetBulkChapterMode(DownloadMode.AUDIO_ONLY, fmt) },
                                                label = { Text("Audio ${fmt.uppercase()}") }
                                            )
                                        }
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 240.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        chip.chapters.forEach { chapter ->
                                            val chMode = chapter.customSettings?.mode ?: chip.settings.mode
                                            val chAudioMode = chMode == DownloadMode.AUDIO_ONLY
                                            val chAudioFmt = (chapter.customSettings?.audioFormat ?: chip.settings.audioFormat).lowercase()
                                            val chEstimate = remember(chip, chapter) {
                                                FilesizeEstimator.estimateChapterTrack(chip, chapter)
                                            }
                                            Surface(
                                                color = if (chapter.selected) VolcanicObsidian else SlateSurface,
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (chapter.selected) SlateOutline else SlateOutline.copy(alpha = 0.45f)
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onToggleChapterTrack(chapter.index) }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Checkbox(
                                                            checked = chapter.selected,
                                                            onCheckedChange = { onToggleChapterTrack(chapter.index) },
                                                            modifier = Modifier.size(30.dp),
                                                            colors = CheckboxDefaults.colors(checkedColor = MoltenEmber)
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = "${chapter.index.toString().padStart(2, '0')}. ${chapter.title}",
                                                                style = MaterialTheme.typography.labelMedium,
                                                                color = if (chapter.selected) CrispWhite else MutedSteel,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = chapter.formattedRange(),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = SecundaSilver
                                                            )
                                                        }
                                                        if (chEstimate != null) {
                                                            Spacer(Modifier.width(6.dp))
                                                            BadgePill(
                                                                text = chEstimate.formattedEstimate(),
                                                                bg = RelayCard,
                                                                fg = ArcRelayCyan
                                                            )
                                                        }
                                                    }

                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(start = 36.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Surface(
                                                            color = if (chAudioMode) ArcRelayCyan.copy(alpha = 0.16f) else MoltenEmber.copy(alpha = 0.18f),
                                                            shape = RoundedCornerShape(6.dp),
                                                            modifier = Modifier.clickable {
                                                                onToggleChapterModeOverride(chapter.index)
                                                            }
                                                        ) {
                                                            Text(
                                                                text = if (chAudioMode) "Audio" else "Video + Audio",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = if (chAudioMode) ArcRelayCyan else EmberFlame,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                            )
                                                        }
                                                        if (chAudioMode) {
                                                            listOf("mp3", "opus", "flac").forEach { fmt ->
                                                                val fmtActive = chAudioFmt == fmt
                                                                Surface(
                                                                    color = if (fmtActive) ArcRelayCyan.copy(alpha = 0.24f) else SlateCard,
                                                                    shape = RoundedCornerShape(6.dp),
                                                                    border = BorderStroke(
                                                                        1.dp,
                                                                        if (fmtActive) ArcRelayCyan else SlateOutline
                                                                    ),
                                                                    modifier = Modifier.clickable {
                                                                        onSetChapterAudioFormat(chapter.index, fmt)
                                                                    }
                                                                ) {
                                                                    Text(
                                                                        text = fmt.uppercase(),
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        color = if (fmtActive) CrispWhite else MutedSteel,
                                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                            }
                        }
                    }

                    // B. Download Mode & Output Format Controls
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Output Format & Quality",
                            style = MaterialTheme.typography.labelMedium,
                            color = SecundaSilver
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DownloadMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = chip.settings.mode == mode,
                                    onClick = { onUpdateSettings { it.copy(mode = mode) } },
                                    label = { Text(mode.label) }
                                )
                            }
                        }

                        if (chip.settings.mode == DownloadMode.AUDIO_ONLY) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("mp3", "opus", "m4a", "flac", "wav").forEach { fmt ->
                                    FilterChip(
                                        selected = chip.settings.audioFormat.equals(fmt, ignoreCase = true),
                                        onClick = { onUpdateSettings { it.copy(audioFormat = fmt) } },
                                        label = { Text(fmt.uppercase()) }
                                    )
                                }
                            }
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(null to "Best", 1080 to "1080p", 720 to "720p", 480 to "480p")
                                    .forEach { (h, label) ->
                                        FilterChip(
                                            selected = chip.settings.maxHeight == h,
                                            onClick = { onUpdateSettings { it.copy(maxHeight = h) } },
                                            label = { Text(label) }
                                        )
                                    }
                            }
                        }

                        FilterChip(
                            selected = chip.settings.embedMetadata,
                            onClick = { onUpdateSettings { it.copy(embedMetadata = !it.embedMetadata) } },
                            label = { Text("Embed Metadata") }
                        )

                        SponsorBlockCategorySelector(
                            selectedCategories = chip.settings.effectiveSponsorBlockCategories,
                            capabilityReport = deviceCapabilities,
                            onToggleCategory = { category ->
                                onUpdateSettings { prev ->
                                    val current = prev.effectiveSponsorBlockCategories
                                    val next = if (category in current) {
                                        current - category
                                    } else {
                                        current + category
                                    }
                                    prev.copy(
                                        sponsorBlockCategories = next,
                                        sponsorBlock = next.isNotEmpty()
                                    )
                                }
                            }
                        )

                        SilenceSkipControlPanel(
                            enabled = chip.settings.silenceSkip.enabled,
                            thresholdDb = chip.settings.silenceSkip.thresholdDb,
                            minSilenceMs = chip.settings.silenceSkip.minSilenceMs,
                            subtitle = "Detects and cuts long dead-air pauses cleanly with matched A/V sync",
                            audioAvailable = chip.detectedKind != DetectedLinkKind.IMAGE,
                            capabilityReport = deviceCapabilities,
                            onToggleEnabled = { enabled ->
                                onUpdateSettings { prev ->
                                    prev.copy(silenceSkip = prev.silenceSkip.copy(enabled = enabled))
                                }
                            },
                            onSelectThresholdDb = { db ->
                                onUpdateSettings { prev ->
                                    prev.copy(silenceSkip = prev.silenceSkip.copy(thresholdDb = db))
                                }
                            },
                            onSelectMinSilenceMs = { ms ->
                                onUpdateSettings { prev ->
                                    prev.copy(silenceSkip = prev.silenceSkip.copy(minSilenceMs = ms))
                                }
                            }
                        )
                    }

                    // C. Perceptual Target Filesize Engine (Molten Forge Post-Download Stage)
                    Surface(
                        color = FurnaceCard,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ForgeBevelOutline)
                    ) {
                        var customMbText by remember(chip.cleanUrl, chip.settings.targetSizeMb) {
                            mutableStateOf(
                                chip.settings.targetSizeMb?.let { mb ->
                                    if (mb % 1.0 == 0.0) mb.toInt().toString() else mb.toString()
                                }.orEmpty()
                            )
                        }

                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    DomainRolePill(domain = EngineDomain.FORGE, label = "Size Cap")
                                    Text(
                                        text = "Compress to Target Size",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CrispWhite
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (chip.settings.targetSizeMb != null) {
                                        Text(
                                            text = "Active",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EmeraldSuccess
                                        )
                                    }
                                    ForgeInfoTipButton(
                                        topic = SettingTopic.TARGET_FILESIZE,
                                        capabilityReport = deviceCapabilities
                                    )
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = chip.settings.targetSizeMb == null,
                                    onClick = {
                                        customMbText = ""
                                        onUpdateSettings { it.copy(targetSizeMb = null) }
                                    },
                                    label = { Text("Off") }
                                )
                                listOf(8, 10, 25, 50, 100).forEach { mb ->
                                    val selected = chip.settings.targetSizeMb == mb.toDouble()
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            customMbText = mb.toString()
                                            onUpdateSettings { it.copy(targetSizeMb = mb.toDouble()) }
                                        },
                                        label = {
                                            Text(if (mb == defaultTargetMb) "${mb}MB ★" else "${mb}MB")
                                        }
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = customMbText,
                                onValueChange = { raw ->
                                    customMbText = raw
                                    val parsed = raw.toDoubleOrNull()
                                    if (raw.isBlank()) {
                                        onUpdateSettings { it.copy(targetSizeMb = null) }
                                    } else if (parsed != null && parsed > 0.0) {
                                        onUpdateSettings { it.copy(targetSizeMb = parsed) }
                                    }
                                },
                                label = { Text("Custom Target (MB)") },
                                placeholder = { Text("e.g. 25 for 30min clip") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MasserCrimson,
                                    unfocusedBorderColor = SlateOutline,
                                    focusedTextColor = CrispWhite,
                                    unfocusedTextColor = CrispWhite
                                )
                            )

                            val activeTargetMb = chip.settings.targetSizeMb
                            if (activeTargetMb != null && activeTargetMb > 0.0 &&
                                chip.settings.mode != DownloadMode.AUDIO_ONLY
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Perceptual Compression Engine",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SecundaSilver,
                                        modifier = Modifier.weight(1f)
                                    )
                                    ForgeInfoTipButton(
                                        topic = SettingTopic.TWO_PASS_ENCODING,
                                        capabilityReport = deviceCapabilities
                                    )
                                    ForgeInfoTipButton(
                                        topic = SettingTopic.VIDEO_CODEC,
                                        capabilityReport = deviceCapabilities
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CompressionTier.entries.forEach { tier ->
                                        FilterChip(
                                            selected = chip.settings.compressionTier == tier,
                                            onClick = { onUpdateSettings { it.copy(compressionTier = tier) } },
                                            label = { Text(tier.label) }
                                        )
                                    }
                                }

                                // Live Bitrate & Quality Strategy Preview
                                val previewDurationMs = ((chip.durationSec ?: 1800L) * 1000L).coerceAtLeast(1000L)
                                val previewCodec = when (chip.settings.compressionTier) {
                                    CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                                    CompressionTier.TWO_PASS_HEVC -> VideoCodecChoice.HEVC
                                    CompressionTier.COMPATIBLE_H264 -> VideoCodecChoice.H264
                                }
                                val plan = remember(
                                    activeTargetMb,
                                    previewDurationMs,
                                    chip.settings.compressionTier
                                ) {
                                    BitrateBudgetCalculator.calculateVideoBudget(
                                        targetBytes = (activeTargetMb * 1024.0 * 1024.0).roundToLong(),
                                        durationMs = previewDurationMs,
                                        sourceWidth = 1920,
                                        sourceHeight = 1080,
                                        hasAudio = true,
                                        isHevc = previewCodec == VideoCodecChoice.HEVC,
                                        videoCodec = previewCodec,
                                        preferOpusAudio = chip.settings.compressionTier == CompressionTier.PERCEPTUAL_AV1
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = if (plan.isTightBudgetWarning) AmberActive else EmeraldSuccess,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (chip.durationSec != null) {
                                            plan.strategySummary
                                        } else {
                                            "${plan.strategySummary} (at 30m ref)"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SecundaSilver
                                    )
                                }
                            }
                        }
                    }

                    // D. Bulk Propagation Across Similar File Types / Sites / All
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Apply These Settings to Batch",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecundaSilver
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = onApplyToSameType,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, MasserCrimson.copy(alpha = 0.55f))
                            ) {
                                Text(
                                    text = "All ${chip.contentMediaKind.label}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CrispWhite
                                )
                            }
                            if (chip.host.isNotBlank()) {
                                OutlinedButton(
                                    onClick = onApplyToSameSite,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    border = BorderStroke(1.dp, SecundaSilver.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "All on ${chip.host}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SecundaSilver
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onApplyToAllLinks,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, SlateOutline)
                            ) {
                                Text(
                                    text = "All Staged Links",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MutedSteel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgePill(text: String, bg: Color, fg: Color) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}
