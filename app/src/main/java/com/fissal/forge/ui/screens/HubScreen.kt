package com.fissal.forge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fissal.forge.data.settings.ForgeSettings
import com.fissal.forge.domain.logic.ParsedUrlItem
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.pipeline.OrchestratorMetrics
import com.fissal.forge.ui.theme.AmberActive
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.SlateSurface

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HubScreen(
    urlBatchText: String,
    parsedUrls: List<ParsedUrlItem>,
    settings: ForgeSettings,
    metrics: OrchestratorMetrics,
    onUrlTextChange: (String) -> Unit,
    onPasteClick: () -> Unit,
    onPickFilesClick: () -> Unit,
    onOpenQueueClick: () -> Unit,
    onQueueDownloads: (
        mode: DownloadMode,
        maxHeight: Int?,
        audioFormat: String,
        playlistSingleOnly: Boolean,
        targetSizeMb: Int?,
        copyToClipboard: Boolean,
        saveToDevice: Boolean
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedMode by remember(settings.defaultDownloadMode) { mutableStateOf(settings.defaultDownloadMode) }
    var selectedMaxHeight by remember(settings.defaultMaxVideoHeight) {
        mutableStateOf<Int?>(settings.defaultMaxVideoHeight.takeIf { it > 0 })
    }
    var selectedAudioFmt by remember(settings.defaultAudioFormat) { mutableStateOf(settings.defaultAudioFormat) }
    var playlistSingleOnly by remember(settings.playlistSingleOnly) { mutableStateOf(settings.playlistSingleOnly) }
    var targetSizeMb by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Active pipeline quick telemetry pill
        if (metrics.totalActiveJobs > 0) {
            Surface(
                color = SlateCard,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AmberActive.copy(alpha = 0.55f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenQueueClick)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = AmberActive,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${metrics.totalActiveJobs} active job(s) • Net ${metrics.activeNetworkCount}/${metrics.maxNetworkSlots} • Compute ${metrics.activeComputeSlotsUsed}/${metrics.maxComputeSlots}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrispWhite
                        )
                    }
                    Text(
                        text = "View",
                        style = MaterialTheme.typography.labelMedium,
                        color = MasserCrimson,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Batch URL Downloader Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, SlateOutline)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Batch URL Downloader",
                            style = MaterialTheme.typography.titleMedium,
                            color = CrispWhite
                        )
                        Text(
                            text = "Paste 1 or 50 links — yt-dlp + aria2c",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSteel
                        )
                    }
                    Row {
                        IconButton(onClick = onPasteClick) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste clipboard", tint = SecundaSilver)
                        }
                        if (urlBatchText.isNotEmpty()) {
                            IconButton(onClick = { onUrlTextChange("") }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Clear", tint = MutedSteel)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = urlBatchText,
                    onValueChange = onUrlTextChange,
                    placeholder = {
                        Text(
                            "Paste URLs here (one per line or mixed text)...",
                            color = MutedSteel.copy(alpha = 0.7f)
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MasserCrimson,
                        unfocusedBorderColor = SlateOutline,
                        focusedContainerColor = ObsidianBg,
                        unfocusedContainerColor = ObsidianBg,
                        focusedTextColor = CrispWhite,
                        unfocusedTextColor = CrispWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (parsedUrls.isNotEmpty()) {
                    val hasPlaylist = parsedUrls.any { it.isPlaylistLike }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${parsedUrls.size} URL${if (parsedUrls.size > 1) "s" else ""} ready" +
                                if (settings.stripUrlTracking) " • Tracking stripped" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldSuccess
                        )
                        if (hasPlaylist) {
                            Text(
                                text = if (playlistSingleOnly) "Playlist → Single clip" else "Full playlist mode",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberActive
                            )
                        }
                    }
                }

                // Mode selector chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DownloadMode.entries.forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MasserCrimson.copy(alpha = 0.22f),
                                selectedLabelColor = MasserCrimson
                            )
                        )
                    }
                }

                // Quality or Audio Format sub-chips
                if (selectedMode == DownloadMode.AUDIO_ONLY) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("mp3", "m4a", "opus", "flac", "wav").forEach { fmt ->
                            FilterChip(
                                selected = selectedAudioFmt == fmt,
                                onClick = { selectedAudioFmt = fmt },
                                label = { Text(fmt.uppercase()) }
                            )
                        }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "Best", 2160 to "4K", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (h, label) ->
                            FilterChip(
                                selected = selectedMaxHeight == h,
                                onClick = { selectedMaxHeight = h },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                // Target filesize post-compression & playlist guard
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(null to "Full Size", 8 to "Fit ≤ 8 MB", 25 to "Fit ≤ 25 MB", 50 to "Fit ≤ 50 MB").forEach { (mb, label) ->
                        FilterChip(
                            selected = targetSizeMb == mb,
                            onClick = { targetSizeMb = mb },
                            label = { Text(label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Single video only on playlist URLs",
                            style = MaterialTheme.typography.bodySmall,
                            color = CrispWhite
                        )
                        Text(
                            text = "Prevents accidentally pulling 200-video mixes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSteel
                        )
                    }
                    Switch(
                        checked = playlistSingleOnly,
                        onCheckedChange = { playlistSingleOnly = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onQueueDownloads(
                                selectedMode,
                                selectedMaxHeight,
                                selectedAudioFmt,
                                playlistSingleOnly,
                                targetSizeMb,
                                true,
                                false
                            )
                        },
                        enabled = parsedUrls.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Only")
                    }

                    Button(
                        onClick = {
                            onQueueDownloads(
                                selectedMode,
                                selectedMaxHeight,
                                selectedAudioFmt,
                                playlistSingleOnly,
                                targetSizeMb,
                                false,
                                true
                            )
                        },
                        enabled = parsedUrls.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MasserCrimson),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Queue Batch")
                    }
                }
            }
        }

        // Local Media, Video, Audio, & Camera RAW Studio Intake Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, SlateOutline)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Local Media & RAW Studio",
                    style = MaterialTheme.typography.titleMedium,
                    color = CrispWhite
                )
                Text(
                    text = "Batch convert, compress to exact MB, trim, or snap-crop (16:9 / 4:3) photos, 4K videos, audio, and 100MP+ Camera RAWs (.ARW, .CR2, .CR3, .NEF, .DNG, .RAF).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedSteel
                )

                Button(
                    onClick = onPickFilesClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SlateCard,
                        contentColor = CrispWhite
                    ),
                    border = BorderStroke(1.dp, SecundaSilver.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = SecundaSilver)
                    Spacer(Modifier.width(10.dp))
                    Text("Pick Media / RAW Files (Single or Batch)", fontWeight = FontWeight.SemiBold)
                }

                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Tip: Share any image, RAW photo, video, audio clip, or link directly from another app's Share Menu into Redfur Forge to open the Quick Studio & Copy-to-Clipboard bar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecundaSilver,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
