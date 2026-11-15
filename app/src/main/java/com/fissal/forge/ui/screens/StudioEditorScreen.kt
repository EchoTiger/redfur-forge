package com.fissal.forge.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.local.JobStatus
import com.fissal.forge.domain.logic.AspectRatioSnapper
import com.fissal.forge.domain.logic.BitrateBudgetCalculator
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.ui.StudioSessionState
import com.fissal.forge.ui.StudioToolTab
import com.fissal.forge.ui.components.CropCanvasOverlay
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.SlateSurface
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StudioEditorScreen(
    state: StudioSessionState,
    jobs: List<ForgeJobEntity>,
    onUpdate: ((StudioSessionState) -> StudioSessionState) -> Unit,
    onSelectCropPreset: (AspectPreset) -> Unit,
    onExecute: (saveToDevice: Boolean, copyToClipboard: Boolean) -> Unit,
    onRunInBackground: () -> Unit,
    onClose: () -> Unit
) {
    val primary = state.primaryItem
    val availableTabs = remember(primary.mediaKind) {
        buildList {
            add(StudioToolTab.CONVERT)
            add(StudioToolTab.COMPRESS)
            if (primary.isTimeBased) add(StudioToolTab.TRIM)
            if (primary.isVisual) add(StudioToolTab.CROP)
        }
    }

    // Inline quick completion watch sheet for Share -> Copy / Save & Copy workflows
    val watchedJob = if (state.inlineWatchJobIds.isNotEmpty()) {
        jobs.firstOrNull { it.id == state.inlineWatchJobIds.first() }
    } else {
        null
    }

    Scaffold(
        containerColor = ObsidianBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateSurface),
                title = {
                    Column {
                        Text(
                            text = if (state.isBatch) "Batch Studio (${state.items.size} files)" else "Quick Forge Studio",
                            style = MaterialTheme.typography.titleMedium,
                            color = CrispWhite
                        )
                        Text(
                            text = if (state.cameFromShareIntent) "Opened from Android Share Menu" else primary.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSteel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close Studio", tint = CrispWhite)
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = SlateSurface,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, SlateOutline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onExecute(false, true) },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy Only", maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = { onExecute(true, false) },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save", maxLines = 1)
                    }

                    Button(
                        onClick = { onExecute(true, true) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MasserCrimson),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier.weight(1.25f)
                    ) {
                        Text("Save & Copy", fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Detected Media Summary Header Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, SlateOutline)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianBg),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                state.isLoadingPreview -> CircularProgressIndicator(
                                    color = MasserCrimson,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp
                                )
                                state.previewBitmap != null && !state.previewBitmap.isRecycled -> Image(
                                    bitmap = state.previewBitmap.asImageBitmap(),
                                    contentDescription = "Preview",
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

                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                            if (primary.cameraModel != null) {
                                Text(
                                    text = primary.cameraModel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSteel
                                )
                            }
                        }
                    }
                }

                // Combinable 4-Tool Selector Chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableTabs.forEach { tab ->
                        val isEnabled = when (tab) {
                            StudioToolTab.CONVERT -> state.convertEnabled
                            StudioToolTab.COMPRESS -> state.compressEnabled
                            StudioToolTab.TRIM -> state.trimEnabled
                            StudioToolTab.CROP -> state.cropEnabled
                        }
                        val isSelected = state.activeTab == tab
                        val icon = when (tab) {
                            StudioToolTab.CONVERT -> Icons.Rounded.Transform
                            StudioToolTab.COMPRESS -> Icons.Rounded.Compress
                            StudioToolTab.TRIM -> Icons.Rounded.ContentCut
                            StudioToolTab.CROP -> Icons.Rounded.Crop
                        }
                        val label = when (tab) {
                            StudioToolTab.CONVERT -> "Convert"
                            StudioToolTab.COMPRESS -> "Compress"
                            StudioToolTab.TRIM -> "Trim"
                            StudioToolTab.CROP -> "Crop"
                        }

                        Surface(
                            color = if (isSelected) SlateCard else SlateSurface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = when {
                                    isSelected -> MasserCrimson
                                    isEnabled -> EmeraldSuccess.copy(alpha = 0.6f)
                                    else -> SlateOutline
                                }
                            ),
                            modifier = Modifier.clickable {
                                onUpdate { cur ->
                                    when (tab) {
                                        StudioToolTab.CONVERT -> cur.copy(activeTab = tab, convertEnabled = true)
                                        StudioToolTab.COMPRESS -> cur.copy(activeTab = tab, compressEnabled = true)
                                        StudioToolTab.TRIM -> cur.copy(activeTab = tab, trimEnabled = true)
                                        StudioToolTab.CROP -> cur.copy(activeTab = tab, cropEnabled = true)
                                    }
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) MasserCrimson else SecundaSilver,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(label, style = MaterialTheme.typography.labelMedium, color = CrispWhite)
                                if (isEnabled) {
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
                }

                // Active Tool Editor Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, SlateOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (state.activeTab) {
                            StudioToolTab.CONVERT -> ConvertPanel(state, onUpdate)
                            StudioToolTab.COMPRESS -> CompressPanel(state, onUpdate)
                            StudioToolTab.TRIM -> TrimPanel(state, onUpdate)
                            StudioToolTab.CROP -> CropPanel(state, onUpdate, onSelectCropPreset)
                        }
                    }
                }

                // Privacy & Metadata Nuance Strip
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SlateOutline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Strip EXIF / GPS / Device Metadata",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CrispWhite
                            )
                            Text(
                                text = "Clean privacy metadata before saving or pasting",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSteel
                            )
                        }
                        Switch(
                            checked = state.stripMetadata,
                            onCheckedChange = { checked -> onUpdate { it.copy(stripMetadata = checked) } }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            // Inline progress overlay when running Copy / Save & Copy from Share Menu
            if (watchedJob != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ObsidianBg.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, SlateOutline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
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
                                        text = "Ready to Paste!",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = CrispWhite
                                    )
                                    Text(
                                        text = watchedJob.statusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SecundaSilver
                                    )
                                    Button(
                                        onClick = onClose,
                                        colors = ButtonDefaults.buttonColors(containerColor = MasserCrimson),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Done")
                                    }
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
                                    Button(
                                        onClick = { onUpdate { it.copy(inlineWatchJobIds = emptyList()) } },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Back to Editor")
                                    }
                                }
                                else -> {
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
                                    LinearProgressIndicator(
                                        progress = { (watchedJob.progress / 100f).coerceIn(0f, 1f) },
                                        color = MasserCrimson,
                                        trackColor = SlateCard,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                    OutlinedButton(
                                        onClick = onRunInBackground,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Run in Background")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConvertPanel(
    state: StudioSessionState,
    onUpdate: ((StudioSessionState) -> StudioSessionState) -> Unit
) {
    val kind = state.primaryItem.mediaKind
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Format Conversion", style = MaterialTheme.typography.titleSmall, color = CrispWhite)
        Switch(
            checked = state.convertEnabled || kind == MediaKind.CAMERA_RAW,
            enabled = kind != MediaKind.CAMERA_RAW,
            onCheckedChange = { enabled -> onUpdate { it.copy(convertEnabled = enabled) } }
        )
    }

    val formats = when (kind) {
        MediaKind.IMAGE, MediaKind.CAMERA_RAW -> listOf("jpg", "png", "webp")
        MediaKind.AUDIO -> listOf("mp3", "m4a", "opus", "flac", "wav")
        else -> listOf("mp4", "mkv", "webm", "gif", "mp3", "m4a")
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        formats.forEach { ext ->
            FilterChip(
                selected = state.targetExtension == ext,
                onClick = {
                    val nextVideoCodec = when (ext) {
                        "webm" -> VideoCodecChoice.VP9
                        "mp4" -> if (state.videoCodec == VideoCodecChoice.VP9) VideoCodecChoice.H264 else state.videoCodec
                        else -> state.videoCodec
                    }
                    val nextAudioCodec = when (ext) {
                        "mp3" -> AudioCodecChoice.MP3
                        "opus" -> AudioCodecChoice.OPUS
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
                    selectedContainerColor = MasserCrimson.copy(alpha = 0.22f),
                    selectedLabelColor = MasserCrimson
                )
            )
        }
    }

    if (kind == MediaKind.VIDEO && state.targetExtension in setOf("mp4", "mkv", "mov")) {
        Text("Video Codec", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(VideoCodecChoice.H264, VideoCodecChoice.HEVC, VideoCodecChoice.COPY).forEach { codec ->
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
private fun CompressPanel(
    state: StudioSessionState,
    onUpdate: ((StudioSessionState) -> StudioSessionState) -> Unit
) {
    val primary = state.primaryItem
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Compress & Filesize Reducer", style = MaterialTheme.typography.titleSmall, color = CrispWhite)
        Switch(
            checked = state.compressEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(compressEnabled = enabled) } }
        )
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.compressMode == CompressMode.TARGET_BYTES,
            onClick = { onUpdate { it.copy(compressEnabled = true, compressMode = CompressMode.TARGET_BYTES) } },
            label = { Text("Exact Target MB") }
        )
        FilterChip(
            selected = state.compressMode == CompressMode.QUALITY,
            onClick = { onUpdate { it.copy(compressEnabled = true, compressMode = CompressMode.QUALITY) } },
            label = { Text("Quality Slider") }
        )
    }

    if (state.compressMode == CompressMode.TARGET_BYTES) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("2", "8", "10", "25", "50").forEach { mb ->
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

        val targetBytes = state.targetMbText.toDoubleOrNull()?.let { (it * 1024.0 * 1024.0).roundToLong() }
        if (targetBytes != null && primary.mediaKind == MediaKind.VIDEO && primary.durationMs > 0L) {
            val trimDur = if (state.trimEnabled && state.trimEndMs > state.trimStartMs) {
                state.trimEndMs - state.trimStartMs
            } else {
                primary.durationMs
            }
            val plan = BitrateBudgetCalculator.calculateVideoBudget(
                targetBytes = targetBytes,
                durationMs = trimDur,
                sourceWidth = primary.effectiveWidth,
                sourceHeight = primary.effectiveHeight,
                hasAudio = primary.hasAudio && !state.muteAudio,
                isHevc = state.videoCodec == VideoCodecChoice.HEVC
            )
            val autoRes = plan.targetMaxDimension?.let { " • Auto ${it}p" } ?: ""
            Text(
                text = "Auto budget: Video ${plan.videoBitrateBps / 1000} kbps • Audio ${plan.audioBitrateBps / 1000} kbps$autoRes",
                style = MaterialTheme.typography.bodySmall,
                color = if (plan.isTightBudgetWarning) CoralError else EmeraldSuccess
            )
        }
    } else {
        if (primary.mediaKind == MediaKind.IMAGE || primary.mediaKind == MediaKind.CAMERA_RAW) {
            Text("Image Quality: ${state.imageQuality}%", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            Slider(
                value = state.imageQuality.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(compressEnabled = true, imageQuality = v.roundToInt()) } },
                valueRange = 35f..98f
            )
        } else {
            Text("Video CRF (lower = higher quality): ${state.videoCrf}", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
            Slider(
                value = state.videoCrf.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(compressEnabled = true, videoCrf = v.roundToInt()) } },
                valueRange = 16f..36f
            )
        }
    }

    if (primary.isVisual) {
        Text("Max Dimension Cap", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
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
                Text("Fast Half-Size RAW Demosaic", style = MaterialTheme.typography.bodySmall, color = CrispWhite)
                Text("4x faster & ultra-low RAM for 60MP–100MP sensors", style = MaterialTheme.typography.labelSmall, color = MutedSteel)
            }
            Switch(
                checked = state.rawHalfSizeFast,
                onCheckedChange = { checked -> onUpdate { it.copy(compressEnabled = true, rawHalfSizeFast = checked) } }
            )
        }
    }
}

@Composable
private fun TrimPanel(
    state: StudioSessionState,
    onUpdate: ((StudioSessionState) -> StudioSessionState) -> Unit
) {
    val totalMs = state.primaryItem.durationMs.coerceAtLeast(1000L)
    val startMs = state.trimStartMs.coerceIn(0L, totalMs - 500L)
    val endMs = state.trimEndMs.takeIf { it > startMs }?.coerceAtMost(totalMs) ?: totalMs

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Trim & Audio Controls", style = MaterialTheme.typography.titleSmall, color = CrispWhite)
        Switch(
            checked = state.trimEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(trimEnabled = enabled) } }
        )
    }

    Text(
        text = "Range: ${formatMs(startMs)} → ${formatMs(endMs)} (Clip ${formatMs(endMs - startMs)})",
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
            Text("Zero quality loss & near-instant when not re-encoding", style = MaterialTheme.typography.labelSmall, color = MutedSteel)
        }
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
        Text("Smooth Fade In / Fade Out", style = MaterialTheme.typography.bodySmall, color = CrispWhite)
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropPanel(
    state: StudioSessionState,
    onUpdate: ((StudioSessionState) -> StudioSessionState) -> Unit,
    onSelectCropPreset: (AspectPreset) -> Unit
) {
    val primary = state.primaryItem
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Crop & Aspect Snap", style = MaterialTheme.typography.titleSmall, color = CrispWhite)
            val autoClosest = AspectRatioSnapper.closestDefaultPreset(primary.effectiveWidth, primary.effectiveHeight)
            Text(
                text = "Closest canonical default: ${autoClosest.label}",
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldSuccess
            )
        }
        Switch(
            checked = state.cropEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(cropEnabled = enabled) } }
        )
    }

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
                    selectedContainerColor = MasserCrimson.copy(alpha = 0.22f),
                    selectedLabelColor = MasserCrimson
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
    return String.format("%d:%02d.%d", min, sec, tenths)
}
