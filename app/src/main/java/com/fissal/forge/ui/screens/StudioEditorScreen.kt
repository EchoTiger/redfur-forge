package com.fissal.forge.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.local.JobStatus
import com.fissal.forge.domain.logic.AspectRatioSnapper
import com.fissal.forge.domain.logic.BitrateBudgetCalculator
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.EstimatedSizeResult
import com.fissal.forge.domain.logic.FilesizeEstimator
import com.fissal.forge.domain.logic.SettingTopic
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.ui.StudioItemConfig
import com.fissal.forge.ui.StudioSessionState
import com.fissal.forge.ui.StudioToolTab
import com.fissal.forge.ui.components.CropCanvasOverlay
import com.fissal.forge.ui.components.DomainRolePill
import com.fissal.forge.ui.components.EmojiPresetStrip
import com.fissal.forge.ui.components.EngineDomain
import com.fissal.forge.ui.components.ForgeInfoTipButton
import com.fissal.forge.ui.components.ForgePrimaryButton
import com.fissal.forge.ui.components.LiveSizeEstimateBadge
import com.fissal.forge.ui.components.SilenceSkipControlPanel
import com.fissal.forge.ui.components.TrimRangePreviewPlayer
import com.fissal.forge.ui.components.WorkshopDomainCard
import com.fissal.forge.ui.theme.AnvilGunmetal
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.ForgeBevelOutline
import com.fissal.forge.ui.theme.ForgeGold
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.RelayCard
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.VolcanicObsidian
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val SampleActiveBadgeBg = Color(0xFFC43419)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudioEditorScreen(
    state: StudioSessionState,
    jobs: List<ForgeJobEntity>,
    presets: List<com.fissal.forge.data.local.FormatPresetEntity>,
    deviceCapabilities: DeviceCapabilityReport,
    buildSpecForItem: (probe: MediaProbeResult, cfg: StudioItemConfig, forceCompressSpec: Boolean) -> PipelineSpec,
    onUpdate: ((StudioItemConfig) -> StudioItemConfig) -> Unit,
    onToggleSection: (StudioToolTab, Boolean) -> Unit,
    onToggleSamplePreview: () -> Unit,
    onOpenSampleLab: () -> Unit,
    onApplyPreset: (com.fissal.forge.data.local.FormatPresetEntity) -> Unit,
    onOpenSavePresetDialog: (suggestedName: String, spec: PipelineSpec) -> Unit,
    onTogglePresetDefault: (com.fissal.forge.data.local.FormatPresetEntity) -> Unit,
    onDeletePreset: (com.fissal.forge.data.local.FormatPresetEntity) -> Unit,
    onClearInlineWatch: () -> Unit,
    onSelectBatchItem: (Int) -> Unit,
    onApplySameType: () -> Unit,
    onApplyAll: () -> Unit,
    onSelectCropPreset: (AspectPreset) -> Unit,
    onExecute: (saveToDevice: Boolean, copyToClipboard: Boolean) -> Unit,
    onRunInBackground: () -> Unit,
    onClose: () -> Unit,
    workflowDock: @Composable () -> Unit = {}
) {
    BackHandler(onBack = onClose)
    val primary = state.primaryItem

    val activeSpec = remember(primary, state.activeConfig) {
        buildSpecForItem(primary, state.activeConfig, false)
    }
    val activeEstimate = remember(primary, activeSpec, state.sampleCalibration) {
        FilesizeEstimator.estimateLocalPipeline(primary, activeSpec, state.sampleCalibration)
    }
    val compressPreviewSpec = remember(primary, state.activeConfig) {
        buildSpecForItem(primary, state.activeConfig, true)
    }
    val compressPreviewEstimate = remember(primary, compressPreviewSpec, state.sampleCalibration) {
        FilesizeEstimator.estimateLocalPipeline(primary, compressPreviewSpec, state.sampleCalibration)
    }
    val batchTotalEstimate = remember(
        state.items,
        state.itemConfigs,
        state.selectedItemIndex,
        state.sampleCalibration
    ) {
        if (!state.isBatch) {
            null
        } else {
            val perItem = state.items.mapIndexed { idx, probe ->
                val cfg = state.itemConfigs[idx]
                val cal = if (idx == state.selectedItemIndex) state.sampleCalibration else null
                FilesizeEstimator.estimateLocalPipeline(
                    probe = probe,
                    spec = buildSpecForItem(probe, cfg, false),
                    sampleCalibration = cal
                )
            }
            val totalEst = perItem.sumOf { it.estimatedBytes }
            val totalMin = perItem.sumOf { it.minBytes }
            val totalMax = perItem.sumOf { it.maxBytes }
            val totalSrc = perItem.mapNotNull { it.sourceBytes }.takeIf { it.size == perItem.size }?.sum()
            val delta = totalSrc?.takeIf { it > 0L }?.let { src ->
                (((totalEst - src).toDouble() / src.toDouble()) * 100.0).roundToInt()
            }
            EstimatedSizeResult(
                estimatedBytes = totalEst,
                minBytes = totalMin,
                maxBytes = totalMax,
                sourceBytes = totalSrc,
                deltaPercent = delta,
                isCalibratedFromSample = state.sampleCalibration != null
            )
        }
    }

    val watchedJob = if (state.inlineWatchJobIds.isNotEmpty()) {
        jobs.firstOrNull { it.id == state.inlineWatchJobIds.first() }
    } else {
        null
    }

    Scaffold(
        containerColor = VolcanicObsidian,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AnvilGunmetal),
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (state.isBatch) "Batch Studio (${state.items.size} files)" else "Forge Studio",
                                style = MaterialTheme.typography.titleMedium,
                                color = CrispWhite
                            )
                            DomainRolePill(domain = EngineDomain.FORGE, label = "Local Editor")
                        }
                        Text(
                            text = if (state.cameFromShareIntent) "Shared from another app" else primary.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSteel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close Forge Studio", tint = CrispWhite)
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = AnvilGunmetal,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, ForgeBevelOutline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LiveSizeEstimateBadge(
                            estimate = activeEstimate,
                            domain = EngineDomain.FORGE,
                            prefix = if (state.isBatch) "Active" else "Est. Output"
                        )
                        if (batchTotalEstimate != null) {
                            LiveSizeEstimateBadge(
                                estimate = batchTotalEstimate,
                                domain = EngineDomain.DELIVERY,
                                prefix = "Batch"
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onExecute(false, true) },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SlateOutline),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = SecundaSilver)
                            Spacer(Modifier.width(4.dp))
                            Text("Copy Clip", maxLines = 1, color = CrispWhite)
                        }

                        OutlinedButton(
                            onClick = { onExecute(true, false) },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SlateOutline),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                            modifier = Modifier.weight(0.9f)
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp), tint = SecundaSilver)
                            Spacer(Modifier.width(4.dp))
                            Text("Save", maxLines = 1, color = CrispWhite)
                        }

                        ForgePrimaryButton(
                            onClick = { onExecute(true, true) },
                            text = "Save & Copy",
                            modifier = Modifier.weight(1.25f)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            workflowDock()

            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Detected Media Summary Header Card
                    WorkshopDomainCard(
                        domain = EngineDomain.FORGE,
                        isActive = false,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(VolcanicObsidian),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    state.isLoadingPreview -> CircularProgressIndicator(
                                        color = EmberFlame,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                    state.previewBitmap != null && !state.previewBitmap.isRecycled -> Image(
                                        bitmap = state.previewBitmap.asImageBitmap(),
                                        contentDescription = "Media preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    else -> Text(
                                        text = primary.extension.uppercase().ifBlank { primary.mediaKind.name },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SecundaSilver
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = primary.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = CrispWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val metaParts = buildList {
                                    add(if (primary.mediaKind == MediaKind.CAMERA_RAW) "RAW (${primary.extension.uppercase()})" else primary.extension.uppercase())
                                    if (primary.effectiveWidth > 0) add(primary.displayResolution())
                                    if (primary.durationMs > 0L) add(primary.formattedDuration())
                                    add(primary.formattedSize())
                                }
                                Text(
                                    text = metaParts.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SecundaSilver
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    LiveSizeEstimateBadge(
                                        estimate = activeEstimate,
                                        domain = EngineDomain.FORGE,
                                        prefix = "Out"
                                    )
                                    if (primary.cameraModel != null) {
                                        Text(
                                            text = primary.cameraModel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MutedSteel,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.isBatch) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Batch Queue (${state.selectedItemIndex + 1}/${state.items.size}) — customize individually or copy across files",
                                style = MaterialTheme.typography.labelSmall,
                                color = SecundaSilver
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                state.items.forEachIndexed { idx, item ->
                                    FilterChip(
                                        selected = idx == state.selectedItemIndex,
                                        onClick = { onSelectBatchItem(idx) },
                                        label = {
                                            Text(
                                                text = "${idx + 1}. ${item.displayName.take(18)}",
                                                maxLines = 1
                                            )
                                        }
                                    )
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = false,
                                    onClick = onApplySameType,
                                    label = { Text("Apply to All ${primary.mediaKind.name.lowercase()}") }
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = onApplyAll,
                                    label = { Text("Apply to All Files") }
                                )
                            }
                        }
                    }

                    if (primary.isVisual) {
                        OutlinedButton(
                            onClick = onOpenSampleLab,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.72f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Compare,
                                contentDescription = null,
                                tint = EmberFlame,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Compare in Sample Frame Lab",
                                style = MaterialTheme.typography.labelLarge,
                                color = CrispWhite
                            )
                        }
                    }

                    EmojiPresetStrip(
                        mediaKind = primary.mediaKind,
                        presets = presets,
                        onApplyPreset = onApplyPreset,
                        onSaveCurrentAsPreset = {
                            val defaultTitle = if (activeSpec.compress?.mode == CompressMode.TARGET_BYTES &&
                                activeSpec.compress.targetBytes != null
                            ) {
                                "${state.targetExtension.uppercase()} ≤ ${state.targetMbText}MB"
                            } else {
                                "${state.targetExtension.uppercase()} Custom"
                            }
                            onOpenSavePresetDialog(defaultTitle, activeSpec)
                        },
                        onToggleDefaultForKind = onTogglePresetDefault,
                        onDeletePreset = onDeletePreset,
                        actionLabel = "Save Preset"
                    )

                    // Active Stages Overview Strip (quick tap toggles)
                    val convertActive = state.convertEnabled || primary.mediaKind == MediaKind.CAMERA_RAW
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StageQuickPill(
                            label = if (primary.mediaKind == MediaKind.CAMERA_RAW) "Convert (Required)" else "Convert",
                            icon = Icons.Rounded.Transform,
                            enabled = convertActive,
                            lockedOn = primary.mediaKind == MediaKind.CAMERA_RAW,
                            onClick = { onToggleSection(StudioToolTab.CONVERT, !state.convertEnabled) }
                        )
                        StageQuickPill(
                            label = "Compress",
                            icon = Icons.Rounded.Compress,
                            enabled = state.compressEnabled,
                            onClick = { onToggleSection(StudioToolTab.COMPRESS, !state.compressEnabled) }
                        )
                        if (primary.isTimeBased) {
                            StageQuickPill(
                                label = "Trim & Audio",
                                icon = Icons.Rounded.ContentCut,
                                enabled = state.trimEnabled,
                                onClick = { onToggleSection(StudioToolTab.TRIM, !state.trimEnabled) }
                            )
                        }
                        if (primary.isVisual) {
                            StageQuickPill(
                                label = "Crop",
                                icon = Icons.Rounded.Crop,
                                enabled = state.cropEnabled,
                                onClick = { onToggleSection(StudioToolTab.CROP, !state.cropEnabled) }
                            )
                        }
                    }

                    // 1. Collapsible Convert Format Card
                    CollapsibleStudioSectionCard(
                        title = "Convert Format",
                        icon = Icons.Rounded.Transform,
                        isEnabled = convertActive,
                        switchEnabled = primary.mediaKind != MediaKind.CAMERA_RAW,
                        statusSummary = if (convertActive) {
                            val codecPart = if (primary.mediaKind == MediaKind.VIDEO && state.targetExtension in setOf("mp4", "mkv", "mov")) {
                                " (${state.videoCodec.label})"
                            } else {
                                ""
                            }
                            "Active • Output ${state.targetExtension.uppercase()}$codecPart"
                        } else {
                            "Off — original ${primary.extension.uppercase().ifBlank { "file" }} format will be kept"
                        },
                        onToggleEnabled = { checked -> onToggleSection(StudioToolTab.CONVERT, checked) }
                    ) {
                        ConvertPanelBody(state = state, deviceCapabilities = deviceCapabilities, onUpdate = onUpdate)
                    }

                    // 2. Collapsible Compress & Reduce Size Card
                    CollapsibleStudioSectionCard(
                        title = "Compress & Reduce Size",
                        icon = Icons.Rounded.Compress,
                        isEnabled = state.compressEnabled,
                        statusSummary = if (state.compressEnabled) {
                            if (state.compressMode == CompressMode.TARGET_BYTES) {
                                "Active • Target ≤ ${state.targetMbText.ifBlank { "8" }} MB"
                            } else if (primary.mediaKind == MediaKind.IMAGE || primary.mediaKind == MediaKind.CAMERA_RAW) {
                                "Active • Quality ${state.imageQuality}%"
                            } else {
                                "Active • Video CRF ${state.videoCrf}"
                            }
                        } else {
                            "Off — original file size and quality will be kept"
                        },
                        onToggleEnabled = { checked -> onToggleSection(StudioToolTab.COMPRESS, checked) }
                    ) {
                        CompressPanelBody(
                            state = state,
                            previewEstimate = compressPreviewEstimate,
                            deviceCapabilities = deviceCapabilities,
                            onToggleSamplePreview = onToggleSamplePreview,
                            onOpenSampleLab = onOpenSampleLab,
                            onUpdate = onUpdate
                        )
                    }

                    // 3. Collapsible Trim & Audio Card (Video / Audio)
                    if (primary.isTimeBased) {
                        val totalMs = primary.durationMs.coerceAtLeast(1000L)
                        val startMs = state.trimStartMs.coerceIn(0L, totalMs - 500L)
                        val endMs = state.trimEndMs.takeIf { it > startMs }?.coerceAtMost(totalMs) ?: totalMs
                        CollapsibleStudioSectionCard(
                            title = "Trim & Audio",
                            icon = Icons.Rounded.ContentCut,
                            isEnabled = state.trimEnabled,
                            statusSummary = if (state.trimEnabled) {
                                "Active • ${formatMs(startMs)} → ${formatMs(endMs)} (${formatMs(endMs - startMs)})"
                            } else {
                                "Off — full ${primary.formattedDuration().ifBlank { "media" }} clip and audio will be kept"
                            },
                            onToggleEnabled = { checked -> onToggleSection(StudioToolTab.TRIM, checked) }
                        ) {
                            TrimPanelBody(state = state, deviceCapabilities = deviceCapabilities, onUpdate = onUpdate)
                        }
                    }

                    // 4. Collapsible Crop & Aspect Ratio Card (Visual media)
                    if (primary.isVisual) {
                        CollapsibleStudioSectionCard(
                            title = "Crop & Aspect Ratio",
                            icon = Icons.Rounded.Crop,
                            isEnabled = state.cropEnabled,
                            statusSummary = if (state.cropEnabled) {
                                "Active • ${state.cropPreset.label} framing"
                            } else {
                                "Off — original framing will be kept"
                            },
                            onToggleEnabled = { checked -> onToggleSection(StudioToolTab.CROP, checked) }
                        ) {
                            CropPanelBody(state = state, onUpdate = onUpdate, onSelectCropPreset = onSelectCropPreset)
                        }
                    }

                    // Privacy & Metadata Strip
                    WorkshopDomainCard(
                        domain = EngineDomain.DELIVERY,
                        isActive = false,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Strip Location & Device Metadata",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CrispWhite
                                )
                                Text(
                                    text = "Removes EXIF, GPS, and camera info before saving or copying",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSteel
                                )
                            }
                            ForgeInfoTipButton(
                                topic = SettingTopic.STRIP_METADATA,
                                capabilityReport = deviceCapabilities
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = state.stripMetadata,
                                onCheckedChange = { checked -> onUpdate { it.copy(stripMetadata = checked) } }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }

                // Inline progress overlay when running Copy / Save & Copy
                if (watchedJob != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ObsidianBg.copy(alpha = 0.88f)),
                        contentAlignment = Alignment.Center
                    ) {
                        WorkshopDomainCard(
                            domain = if (watchedJob.statusEnum == JobStatus.COMPLETED) {
                                EngineDomain.DELIVERY
                            } else {
                                EngineDomain.FORGE
                            },
                            isActive = watchedJob.isActive,
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                when (watchedJob.statusEnum) {
                                    JobStatus.COMPLETED -> {
                                        Icon(
                                            Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = EmeraldSuccess,
                                            modifier = Modifier.size(44.dp)
                                        )
                                        Text(
                                            text = "Ready to Paste",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = CrispWhite
                                        )
                                        Text(
                                            text = watchedJob.statusText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SecundaSilver
                                        )
                                        ForgePrimaryButton(
                                            onClick = onClose,
                                            text = "Done",
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    JobStatus.FAILED, JobStatus.CANCELED -> {
                                        Icon(
                                            Icons.Rounded.ErrorOutline,
                                            contentDescription = null,
                                            tint = CoralError,
                                            modifier = Modifier.size(44.dp)
                                        )
                                        Text(
                                            text = watchedJob.statusText,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = CrispWhite
                                        )
                                        if (!watchedJob.errorMessage.isNullOrBlank()) {
                                            Text(
                                                text = watchedJob.errorMessage,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = CoralError
                                            )
                                        }
                                        ForgePrimaryButton(
                                            onClick = onClearInlineWatch,
                                            text = "Back to Studio",
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    else -> {
                                        DomainRolePill(domain = EngineDomain.FORGE, label = "Processing")
                                        Text(
                                            text = "Forging Media...",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = CrispWhite
                                        )
                                        Text(
                                            text = watchedJob.statusText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SecundaSilver
                                        )
                                        if (watchedJob.progress < 0f) {
                                            LinearProgressIndicator(
                                                color = MoltenEmber,
                                                trackColor = SlateCard,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                            )
                                        } else {
                                            LinearProgressIndicator(
                                                progress = { (watchedJob.progress / 100f).coerceIn(0f, 1f) },
                                                color = MoltenEmber,
                                                trackColor = SlateCard,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = onRunInBackground,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Continue in Background")
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

@Composable
private fun StageQuickPill(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    lockedOn: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        color = if (enabled) FurnaceCard else AnvilGunmetal,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = if (enabled) 1.35.dp else 1.dp,
            color = if (enabled) EmeraldSuccess.copy(alpha = 0.72f) else ForgeBevelOutline
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !lockedOn, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) EmberFlame else MutedSteel,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) CrispWhite else MutedSteel
            )
            if (enabled) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(EmeraldSuccess)
                )
            }
        }
    }
}

@Composable
private fun CollapsibleStudioSectionCard(
    title: String,
    icon: ImageVector,
    isEnabled: Boolean,
    statusSummary: String,
    switchEnabled: Boolean = true,
    onToggleEnabled: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    WorkshopDomainCard(
        domain = if (isEnabled) EngineDomain.FORGE else EngineDomain.DELIVERY,
        isActive = false,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = switchEnabled) { onToggleEnabled(!isEnabled) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = if (isEnabled) FurnaceCard else SlateCard,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isEnabled) EmberFlame.copy(alpha = 0.6f) else SlateOutline
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isEnabled) EmberFlame else MutedSteel,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEnabled) CrispWhite else SecundaSilver
                        )
                        Text(
                            text = statusSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isEnabled) EmeraldSuccess else MutedSteel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = isEnabled,
                    enabled = switchEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            AnimatedVisibility(
                visible = isEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = ForgeBevelOutline)
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConvertPanelBody(
    state: StudioSessionState,
    deviceCapabilities: DeviceCapabilityReport,
    onUpdate: ((StudioItemConfig) -> StudioItemConfig) -> Unit
) {
    val kind = state.primaryItem.mediaKind
    val formats = when (kind) {
        MediaKind.IMAGE, MediaKind.CAMERA_RAW -> listOf("jpg", "png", "webp")
        MediaKind.AUDIO -> listOf("mp3", "m4a", "opus", "flac", "wav")
        else -> listOf("mp4", "mkv", "webm", "gif", "mp3", "m4a")
    }

    Text("Target Output Format", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        formats.forEach { ext ->
            FilterChip(
                selected = state.targetExtension == ext,
                onClick = {
                    val nextVideoCodec = when (ext) {
                        "webm" -> if (state.videoCodec == VideoCodecChoice.AV1) VideoCodecChoice.AV1 else VideoCodecChoice.VP9
                        "mp4" -> if (state.videoCodec == VideoCodecChoice.VP9) VideoCodecChoice.H264 else state.videoCodec
                        else -> state.videoCodec
                    }
                    val nextAudioCodec = when (ext) {
                        "mp3" -> AudioCodecChoice.MP3
                        "opus", "webm" -> AudioCodecChoice.OPUS
                        "flac" -> AudioCodecChoice.FLAC
                        "wav" -> AudioCodecChoice.WAV
                        else -> AudioCodecChoice.AAC
                    }
                    onUpdate {
                        it.copy(
                            convertEnabled = true,
                            targetExtension = ext,
                            videoCodec = nextVideoCodec,
                            audioCodec = nextAudioCodec
                        )
                    }
                },
                label = { Text(ext.uppercase()) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MoltenEmber.copy(alpha = 0.22f),
                    selectedLabelColor = EmberFlame
                )
            )
        }
    }

    if (kind == MediaKind.VIDEO && state.targetExtension in setOf("mp4", "mkv", "mov", "webm")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Video Encoder", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            ForgeInfoTipButton(topic = SettingTopic.VIDEO_CODEC, capabilityReport = deviceCapabilities)
        }
        val codecOptions = if (state.targetExtension == "webm") {
            listOf(VideoCodecChoice.AV1, VideoCodecChoice.VP9)
        } else {
            listOf(
                VideoCodecChoice.AV1,
                VideoCodecChoice.HEVC,
                VideoCodecChoice.H264,
                VideoCodecChoice.COPY
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            codecOptions.forEach { codec ->
                FilterChip(
                    selected = state.videoCodec == codec,
                    onClick = { onUpdate { it.copy(convertEnabled = true, videoCodec = codec) } },
                    label = { Text(codec.label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompressPanelBody(
    state: StudioSessionState,
    previewEstimate: EstimatedSizeResult,
    deviceCapabilities: DeviceCapabilityReport,
    onToggleSamplePreview: () -> Unit,
    onOpenSampleLab: () -> Unit,
    onUpdate: ((StudioItemConfig) -> StudioItemConfig) -> Unit
) {
    val primary = state.primaryItem

    if (primary.isVisual) {
        val origBmp = state.originalSampleBitmap ?: state.previewBitmap
        val compBmp = state.compressedSampleBitmap
        val hasCompressedSample = compBmp != null && !compBmp.isRecycled
        val showingCompressed = state.showCompressedSample && hasCompressedSample
        val displayBmp = if (showingCompressed) compBmp else origBmp

        Surface(
            color = FurnaceCard,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (showingCompressed) ForgeGold.copy(alpha = 0.72f) else ForgeBevelOutline
            )
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 164.dp, max = 236.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(VolcanicObsidian)
                        .clickable(
                            enabled = hasCompressedSample,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToggleSamplePreview
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (displayBmp != null && !displayBmp.isRecycled) {
                        Image(
                            bitmap = displayBmp.asImageBitmap(),
                            contentDescription = if (showingCompressed) "Compressed sample frame" else "Original frame",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 164.dp, max = 236.dp)
                        )
                    } else if (state.isLoadingPreview) {
                        CircularProgressIndicator(
                            color = EmberFlame,
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Text(
                            text = "Preview unavailable",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedSteel
                        )
                    }

                    Surface(
                        color = if (showingCompressed) SampleActiveBadgeBg else RelayCard.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (showingCompressed) ForgeGold.copy(alpha = 0.85f) else ArcRelayCyan.copy(alpha = 0.65f)
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = if (showingCompressed) {
                                "SAMPLE • ${previewEstimate.formattedWithDelta()}"
                            } else if (primary.fileSizeBytes > 0L) {
                                "ORIG • ${primary.formattedSize()}"
                            } else {
                                "ORIG"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = CrispWhite,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        color = AnvilGunmetal.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ForgeBevelOutline),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (state.isGeneratingSample) {
                                CircularProgressIndicator(
                                    color = ForgeGold,
                                    modifier = Modifier.size(11.dp),
                                    strokeWidth = 1.8.dp
                                )
                                Text(
                                    text = "Calibrating...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ForgeGold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Compare,
                                    contentDescription = null,
                                    tint = if (hasCompressedSample) EmberFlame else MutedSteel,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (hasCompressedSample) "Tap to flip" else "Preparing sample",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SecundaSilver
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showingCompressed) {
                            "Showing compressed sample (tap to compare original)"
                        } else if (hasCompressedSample) {
                            "Showing original frame (tap to compare compressed)"
                        } else {
                            "Live sample updates as compression settings change"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSteel,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    LiveSizeEstimateBadge(
                        estimate = previewEstimate,
                        domain = EngineDomain.FORGE
                    )
                }

                OutlinedButton(
                    onClick = onOpenSampleLab,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.55f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Compare,
                        contentDescription = null,
                        tint = EmberFlame,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Compare Multiple Configs in Sample Lab",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmberFlame
                    )
                }
            }
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.compressMode == CompressMode.TARGET_BYTES,
            onClick = { onUpdate { it.copy(compressEnabled = true, compressMode = CompressMode.TARGET_BYTES) } },
            label = { Text("Exact Target Size (MB)") }
        )
        FilterChip(
            selected = state.compressMode == CompressMode.QUALITY,
            onClick = { onUpdate { it.copy(compressEnabled = true, compressMode = CompressMode.QUALITY) } },
            label = { Text("Quality Slider") }
        )
    }

    if (state.compressMode == CompressMode.TARGET_BYTES) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Target Size Presets", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            ForgeInfoTipButton(topic = SettingTopic.TARGET_FILESIZE, capabilityReport = deviceCapabilities)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("2", "8", "10", "25", "50", "100").forEach { mb ->
                FilterChip(
                    selected = state.targetMbText == mb,
                    onClick = { onUpdate { it.copy(compressEnabled = true, targetMbText = mb) } },
                    label = { Text("$mb MB") }
                )
            }
        }

        OutlinedTextField(
            value = state.targetMbText,
            onValueChange = { txt -> onUpdate { it.copy(compressEnabled = true, targetMbText = txt) } },
            label = { Text("Custom Target Size (MB)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (primary.mediaKind == MediaKind.VIDEO) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Compression Engine",
                    style = MaterialTheme.typography.labelMedium,
                    color = SecundaSilver,
                    modifier = Modifier.weight(1f)
                )
                ForgeInfoTipButton(topic = SettingTopic.TWO_PASS_ENCODING, capabilityReport = deviceCapabilities)
                ForgeInfoTipButton(topic = SettingTopic.VIDEO_CODEC, capabilityReport = deviceCapabilities)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompressionTier.entries.forEach { tier ->
                    FilterChip(
                        selected = state.compressionTier == tier,
                        onClick = { onUpdate { it.copy(compressEnabled = true, compressionTier = tier) } },
                        label = { Text(tier.label) }
                    )
                }
            }
        }

        val targetBytes = state.targetMbText.toDoubleOrNull()?.let { (it * 1024.0 * 1024.0).roundToLong() }
        if (targetBytes != null && primary.mediaKind == MediaKind.VIDEO && primary.durationMs > 0L) {
            val trimDur = if (state.trimEnabled && state.trimEndMs > state.trimStartMs) {
                state.trimEndMs - state.trimStartMs
            } else {
                primary.durationMs
            }
            val previewCodec = if (state.convertEnabled) {
                state.videoCodec
            } else {
                when (state.compressionTier) {
                    CompressionTier.PERCEPTUAL_AV1 -> VideoCodecChoice.AV1
                    CompressionTier.TWO_PASS_HEVC -> VideoCodecChoice.HEVC
                    CompressionTier.COMPATIBLE_H264 -> VideoCodecChoice.H264
                }
            }
            val plan = BitrateBudgetCalculator.calculateVideoBudget(
                targetBytes = targetBytes,
                durationMs = trimDur,
                sourceWidth = primary.effectiveWidth,
                sourceHeight = primary.effectiveHeight,
                hasAudio = primary.hasAudio && !state.muteAudio,
                isHevc = previewCodec == VideoCodecChoice.HEVC,
                videoCodec = previewCodec,
                preferOpusAudio = !state.convertEnabled && state.compressionTier == CompressionTier.PERCEPTUAL_AV1
            )
            Text(
                text = "Planned bitrate: ${plan.strategySummary}",
                style = MaterialTheme.typography.bodySmall,
                color = if (plan.isTightBudgetWarning) CoralError else EmeraldSuccess
            )
        }
    } else {
        if (primary.mediaKind == MediaKind.IMAGE || primary.mediaKind == MediaKind.CAMERA_RAW) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Image Quality: ${state.imageQuality}%", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
                ForgeInfoTipButton(topic = SettingTopic.CRF_SLIDER, capabilityReport = deviceCapabilities)
            }
            Slider(
                value = state.imageQuality.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(compressEnabled = true, imageQuality = v.roundToInt()) } },
                valueRange = 35f..98f
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quality Level (CRF — lower means sharper): ${state.videoCrf}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SecundaSilver,
                    modifier = Modifier.weight(1f)
                )
                ForgeInfoTipButton(topic = SettingTopic.CRF_SLIDER, capabilityReport = deviceCapabilities)
            }
            Slider(
                value = state.videoCrf.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(compressEnabled = true, videoCrf = v.roundToInt()) } },
                valueRange = 16f..36f
            )
        }
    }

    if (primary.isVisual) {
        Text("Maximum Resolution Cap", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Original", 2560 to "2560px", 1920 to "1080p", 1280 to "720p", 854 to "480p").forEach { (dim, label) ->
                FilterChip(
                    selected = state.maxDimensionPx == dim,
                    onClick = { onUpdate { it.copy(compressEnabled = true, maxDimensionPx = dim) } },
                    label = { Text(label) }
                )
            }
        }
    }

    if (primary.mediaKind == MediaKind.CAMERA_RAW) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Fast Half-Size RAW Processing", style = MaterialTheme.typography.bodySmall, color = CrispWhite)
                Text("4x faster with low memory usage on 50MP+ sensors", style = MaterialTheme.typography.labelSmall, color = MutedSteel)
            }
            ForgeInfoTipButton(topic = SettingTopic.RAW_HALF_SIZE_FAST, capabilityReport = deviceCapabilities)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = state.rawHalfSizeFast,
                onCheckedChange = { checked -> onUpdate { it.copy(compressEnabled = true, rawHalfSizeFast = checked) } }
            )
        }
    }
}

@Composable
private fun TrimPanelBody(
    state: StudioSessionState,
    deviceCapabilities: DeviceCapabilityReport,
    onUpdate: ((StudioItemConfig) -> StudioItemConfig) -> Unit
) {
    val totalMs = state.primaryItem.durationMs.coerceAtLeast(1000L)
    val startMs = state.trimStartMs.coerceIn(0L, totalMs - 500L)
    val endMs = state.trimEndMs.takeIf { it > startMs }?.coerceAtMost(totalMs) ?: totalMs

    TrimRangePreviewPlayer(
        probe = state.primaryItem,
        trimStartMs = startMs,
        trimEndMs = endMs,
        muteAudio = state.muteAudio
    )

    Text(
        text = "Selected Range: ${formatMs(startMs)} → ${formatMs(endMs)} (Clip ${formatMs(endMs - startMs)})",
        style = MaterialTheme.typography.labelMedium,
        color = SecundaSilver
    )

    RangeSlider(
        value = startMs.toFloat()..endMs.toFloat(),
        onValueChange = { range ->
            val s = range.start.toLong().coerceIn(0L, totalMs - 500L)
            val e = range.endInclusive.toLong().coerceIn(s + 500L, totalMs)
            onUpdate { it.copy(trimEnabled = true, trimStartMs = s, trimEndMs = e) }
        },
        valueRange = 0f..totalMs.toFloat()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(
            onClick = {
                val s = (startMs - 500L).coerceAtLeast(0L)
                onUpdate { it.copy(trimEnabled = true, trimStartMs = s) }
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) { Text("Start -0.5s") }

        OutlinedButton(
            onClick = {
                val s = (startMs + 500L).coerceAtMost(endMs - 500L)
                onUpdate { it.copy(trimEnabled = true, trimStartMs = s) }
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) { Text("Start +0.5s") }

        OutlinedButton(
            onClick = {
                val e = (endMs - 500L).coerceAtLeast(startMs + 500L)
                onUpdate { it.copy(trimEnabled = true, trimEndMs = e) }
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) { Text("End -0.5s") }

        OutlinedButton(
            onClick = {
                val e = (endMs + 500L).coerceAtMost(totalMs)
                onUpdate { it.copy(trimEnabled = true, trimEndMs = e) }
            },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) { Text("End +0.5s") }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Fast Keyframe Copy Cut", style = MaterialTheme.typography.bodySmall, color = CrispWhite)
            Text("Instant trim without re-encoding when no format/compression edits are active", style = MaterialTheme.typography.labelSmall, color = MutedSteel)
        }
        ForgeInfoTipButton(topic = SettingTopic.FAST_KEYFRAME_TRIM, capabilityReport = deviceCapabilities)
        Spacer(Modifier.width(6.dp))
        Switch(
            checked = state.fastKeyframeCopy,
            onCheckedChange = { checked -> onUpdate { it.copy(trimEnabled = true, fastKeyframeCopy = checked) } }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Smooth Fade In & Fade Out", style = MaterialTheme.typography.bodySmall, color = CrispWhite)
        Switch(
            checked = state.fadeInOut,
            onCheckedChange = { checked -> onUpdate { it.copy(trimEnabled = true, fadeInOut = checked) } }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Mute Audio Track", style = MaterialTheme.typography.bodySmall, color = CrispWhite)
        Switch(
            checked = state.muteAudio,
            onCheckedChange = { checked -> onUpdate { it.copy(trimEnabled = true, muteAudio = checked) } }
        )
    }

    val hasAudioOutput = state.primaryItem.mediaKind == MediaKind.AUDIO ||
        (state.primaryItem.hasAudio &&
            !state.muteAudio &&
            state.audioCodec != AudioCodecChoice.MUTE &&
            state.targetExtension.lowercase() != "gif")

    SilenceSkipControlPanel(
        enabled = state.silenceSkipEnabled,
        thresholdDb = state.silenceThresholdDb,
        minSilenceMs = state.silenceMinDurationMs,
        subtitle = if (state.primaryItem.mediaKind == MediaKind.VIDEO &&
            state.targetExtension.lowercase() !in setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav")
        ) {
            "Cuts silent pauses cleanly while keeping audio and video in sync"
        } else {
            "Tightens quiet pauses cleanly without clipping speech tails"
        },
        audioAvailable = hasAudioOutput,
        capabilityReport = deviceCapabilities,
        onToggleEnabled = { checked ->
            onUpdate { it.copy(trimEnabled = true, silenceSkipEnabled = checked) }
        },
        onSelectThresholdDb = { db ->
            onUpdate { it.copy(trimEnabled = true, silenceSkipEnabled = true, silenceThresholdDb = db) }
        },
        onSelectMinSilenceMs = { ms ->
            onUpdate { it.copy(trimEnabled = true, silenceSkipEnabled = true, silenceMinDurationMs = ms) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropPanelBody(
    state: StudioSessionState,
    onUpdate: ((StudioItemConfig) -> StudioItemConfig) -> Unit,
    onSelectCropPreset: (AspectPreset) -> Unit
) {
    val primary = state.primaryItem
    val autoClosest = AspectRatioSnapper.closestDefaultPreset(primary.effectiveWidth, primary.effectiveHeight)
    Text(
        text = "Suggested native ratio: ${autoClosest.label}",
        style = MaterialTheme.typography.labelSmall,
        color = EmeraldSuccess
    )

    CropCanvasOverlay(
        bitmap = state.previewBitmap,
        effectiveWidth = primary.effectiveWidth,
        effectiveHeight = primary.effectiveHeight,
        preset = state.cropPreset,
        rect = state.cropRect,
        onRectChange = { newRect ->
            onUpdate { it.copy(cropEnabled = true, cropRect = newRect) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
    )

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val presets = listOf(
            AspectPreset.AUTO_CLOSEST,
            AspectPreset.RATIO_16_9,
            AspectPreset.RATIO_4_3,
            AspectPreset.RATIO_1_1,
            AspectPreset.RATIO_9_16,
            AspectPreset.RATIO_3_4,
            AspectPreset.RATIO_3_2,
            AspectPreset.FREEFORM
        )
        presets.forEach { preset ->
            FilterChip(
                selected = state.cropPreset == preset,
                onClick = { onSelectCropPreset(preset) },
                label = {
                    Text(if (preset == AspectPreset.AUTO_CLOSEST) "Closest" else preset.label)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MoltenEmber.copy(alpha = 0.22f),
                    selectedLabelColor = EmberFlame
                )
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    val tenths = (ms % 1000L) / 100L
    return String.format(Locale.US, "%d:%02d.%d", min, sec, tenths)
}
