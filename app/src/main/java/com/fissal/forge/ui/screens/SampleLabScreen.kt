package com.fissal.forge.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fissal.forge.data.local.FormatPresetEntity
import com.fissal.forge.domain.logic.FilesizeEstimator
import com.fissal.forge.domain.logic.SettingTopic
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.RenderedSampleResult
import com.fissal.forge.domain.model.SampleCandidateSpec
import com.fissal.forge.domain.model.SampleEntryDraft
import com.fissal.forge.domain.model.SampleRenderStatus
import com.fissal.forge.domain.model.SampleVoteState
import com.fissal.forge.domain.model.VideoCodecChoice
import com.fissal.forge.engine.SampleFrameEngine
import com.fissal.forge.ui.components.DomainRolePill
import com.fissal.forge.ui.components.EmojiPresetStrip
import com.fissal.forge.ui.components.EngineDomain
import com.fissal.forge.ui.components.ForgeInfoTipButton
import com.fissal.forge.ui.components.ForgePrimaryButton
import com.fissal.forge.ui.components.LiveSizeEstimateBadge
import com.fissal.forge.ui.components.WorkshopDomainCard
import com.fissal.forge.ui.theme.AnvilGunmetal
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.ForgeBevelOutline
import com.fissal.forge.ui.theme.ForgeGold
import com.fissal.forge.ui.theme.ForgeReadoutStyle
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.VolcanicObsidian
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val SAMPLE_DIM_OPTIONS = listOf(
    null to "Orig",
    1920 to "1080p",
    1280 to "720p",
    854 to "480p"
)

private val SAMPLE_TARGET_MB_PRESETS = listOf("2", "8", "10", "25", "50")

private val ErrorStripContainer = Color(0xFF281318)

/**
 * Safely decodes a sample image file on [Dispatchers.IO] bounded to [maxDimensionPx] without
 * allocating unbounded bitmaps or manually recycling Compose-owned [ImageBitmap] instances.
 */
@Composable
fun rememberSafeSampleImageBitmap(
    filePath: String?,
    maxDimensionPx: Int
): ImageBitmap? = rememberSafeSampleImageBitmapReloadable(
    filePath = filePath,
    maxDimensionPx = maxDimensionPx,
    revisionKey = Unit
)

@Composable
private fun rememberSafeSampleImageBitmapReloadable(
    filePath: String?,
    maxDimensionPx: Int,
    revisionKey: Any?
): ImageBitmap? {
    val bitmapState = produceState<ImageBitmap?>(
        initialValue = null,
        key1 = filePath,
        key2 = maxDimensionPx,
        key3 = revisionKey
    ) {
        value = if (filePath.isNullOrBlank() || maxDimensionPx <= 0) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    SampleFrameEngine.decodeSampleFileSafely(
                        file = File(filePath),
                        maxDimensionPx = maxDimensionPx
                    )?.asImageBitmap()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: OutOfMemoryError) {
                    System.gc()
                    null
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }
    return bitmapState.value
}

/**
 * Interactive Sample Lab workbench for rendering, comparing, and calibrating candidate encode
 * profiles side-by-side before applying a winning configuration back to Studio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleLabScreen(
    probe: MediaProbeResult,
    timestampMs: Long,
    includeOriginalFrame: Boolean,
    candidates: List<SampleCandidateSpec>,
    renderedResults: Map<String, RenderedSampleResult>,
    referencePreviewPath: String?,
    isPreparingBaseline: Boolean,
    isRenderingSamples: Boolean,
    baselineError: String?,
    presets: List<FormatPresetEntity>,
    onTimestampChange: (Long) -> Unit,
    onRandomTimestamp: () -> Unit,
    onToggleIncludeOriginal: (Boolean) -> Unit,
    onSuggestBestConfig: () -> Unit,
    onRenderAll: () -> Unit,
    onRetryBaseline: () -> Unit,
    onOpenFullscreenCompare: (initialCandidateId: String?) -> Unit,
    onAddCandidate: () -> Unit,
    onApplyPresetAsCandidate: (FormatPresetEntity) -> Unit,
    onTogglePresetDefault: (FormatPresetEntity) -> Unit,
    onDeletePreset: (FormatPresetEntity) -> Unit,
    onOpenSavePresetDialog: (suggestedName: String, spec: PipelineSpec) -> Unit,
    onUpdateCandidateDraft: (candidateId: String, (SampleEntryDraft) -> SampleEntryDraft) -> Unit,
    onRenderSingleCandidate: (candidateId: String) -> Unit,
    onRemoveCandidate: (candidateId: String) -> Unit,
    onVoteCandidate: (candidateId: String, SampleVoteState) -> Unit,
    onUseConfigInStudio: (candidateId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onClose)

    val userCandidates = remember(candidates) {
        candidates.filter { !it.isOriginalBaseline }
    }
    val readyCandidateCount = remember(userCandidates, renderedResults) {
        userCandidates.count { candidate ->
            val result = renderedResults[candidate.id]
            result?.status == SampleRenderStatus.READY && !result.previewFilePath.isNullOrBlank()
        }
    }
    val readyTabCount = remember(includeOriginalFrame, referencePreviewPath, readyCandidateCount) {
        val origTab = if (includeOriginalFrame && !referencePreviewPath.isNullOrBlank()) 1 else 0
        origTab + readyCandidateCount
    }
    val bestOrFirstCandidate = remember(userCandidates, renderedResults) {
        userCandidates.firstOrNull { renderedResults[it.id]?.isRecommendedBest == true }
            ?: userCandidates.firstOrNull()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VolcanicObsidian,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AnvilGunmetal),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close Sample Lab",
                            tint = CrispWhite
                        )
                    }
                },
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Sample Lab",
                                style = MaterialTheme.typography.titleMedium,
                                color = CrispWhite
                            )
                            DomainRolePill(domain = EngineDomain.FORGE, label = "Perceptual")
                        }
                        Text(
                            text = probe.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedSteel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Section 1 — Target Media Header
            item(key = "section_target_header") {
                TargetMediaHeaderCard(
                    probe = probe,
                    timestampMs = timestampMs,
                    referencePreviewPath = referencePreviewPath,
                    isPreparingBaseline = isPreparingBaseline,
                    baselineError = baselineError,
                    canOpenBaselineCompare = includeOriginalFrame &&
                        !referencePreviewPath.isNullOrBlank() &&
                        !isPreparingBaseline &&
                        readyTabCount >= 1,
                    onOpenBaselineCompare = { onOpenFullscreenCompare("orig") },
                    onRetryBaseline = onRetryBaseline
                )
            }

            // Section 2 — Timestamp Control (videos with known duration)
            if (probe.mediaKind == MediaKind.VIDEO && probe.durationMs > 0L) {
                item(key = "section_timestamp_control") {
                    TimestampControlCard(
                        durationMs = probe.durationMs,
                        timestampMs = timestampMs,
                        isBusy = isPreparingBaseline,
                        onTimestampChange = onTimestampChange,
                        onRandomTimestamp = onRandomTimestamp
                    )
                }
            }

            // Section 3 — Lab Actions Bar
            item(key = "section_actions_bar") {
                LabActionsCard(
                    includeOriginalFrame = includeOriginalFrame,
                    readyTabCount = readyTabCount,
                    hasCandidates = userCandidates.isNotEmpty(),
                    isPreparingBaseline = isPreparingBaseline,
                    isRenderingSamples = isRenderingSamples,
                    onToggleIncludeOriginal = onToggleIncludeOriginal,
                    onSuggestBestConfig = onSuggestBestConfig,
                    onRenderAll = onRenderAll,
                    onOpenFullscreenCompare = { onOpenFullscreenCompare(null) }
                )
            }

            // Section 4A — Emoji Presets Strip
            item(key = "section_emoji_presets") {
                EmojiPresetStrip(
                    mediaKind = probe.mediaKind,
                    presets = presets,
                    onApplyPreset = onApplyPresetAsCandidate,
                    onSaveCurrentAsPreset = {
                        val target = bestOrFirstCandidate
                        if (target != null) {
                            onOpenSavePresetDialog(target.label, target.pipelineSpec)
                        } else {
                            onOpenSavePresetDialog("Forge Preset", PipelineSpec())
                        }
                    },
                    onToggleDefaultForKind = onTogglePresetDefault,
                    onDeletePreset = onDeletePreset,
                    actionLabel = "Save Preset"
                )
            }

            // Section 4B — Dynamic Sample Config Entries
            items(
                items = userCandidates,
                key = { it.id }
            ) { candidate ->
                val result = renderedResults[candidate.id]
                val canRemove = userCandidates.size > 1
                SampleCandidateCard(
                    probe = probe,
                    timestampMs = timestampMs,
                    candidate = candidate,
                    result = result,
                    canRemove = canRemove,
                    isPreparingBaseline = isPreparingBaseline,
                    onOpenFullscreenCompare = { onOpenFullscreenCompare(candidate.id) },
                    onSavePreset = { onOpenSavePresetDialog(candidate.label, candidate.pipelineSpec) },
                    onUseConfig = { onUseConfigInStudio(candidate.id) },
                    onRemove = { onRemoveCandidate(candidate.id) },
                    onVote = { vote -> onVoteCandidate(candidate.id, vote) },
                    onUpdateDraft = { updater -> onUpdateCandidateDraft(candidate.id, updater) },
                    onRenderSingle = { onRenderSingleCandidate(candidate.id) }
                )
            }

            // Section 4C — Bottom Add Sample Config CTA
            item(key = "section_add_candidate_cta") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    OutlinedButton(
                        onClick = onAddCandidate,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.25.dp, EmberFlame.copy(alpha = 0.78f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = FurnaceCard,
                            contentColor = EmberFlame
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Add Sample Config",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun TargetMediaHeaderCard(
    probe: MediaProbeResult,
    timestampMs: Long,
    referencePreviewPath: String?,
    isPreparingBaseline: Boolean,
    baselineError: String?,
    canOpenBaselineCompare: Boolean,
    onOpenBaselineCompare: () -> Unit,
    onRetryBaseline: () -> Unit
) {
    val referenceBitmap = rememberSafeSampleImageBitmapReloadable(
        filePath = if (isPreparingBaseline) null else referencePreviewPath,
        maxDimensionPx = 480,
        revisionKey = timestampMs
    )

    WorkshopDomainCard(
        domain = EngineDomain.FORGE,
        isActive = isPreparingBaseline,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(VolcanicObsidian)
                        .then(
                            if (canOpenBaselineCompare && referenceBitmap != null) {
                                Modifier.clickable(onClick = onOpenBaselineCompare)
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isPreparingBaseline -> CircularProgressIndicator(
                            color = EmberFlame,
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp
                        )
                        referenceBitmap != null -> Image(
                            bitmap = referenceBitmap,
                            contentDescription = "Baseline reference frame",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        else -> Icon(
                            imageVector = when (probe.mediaKind) {
                                MediaKind.VIDEO -> Icons.Rounded.Videocam
                                MediaKind.CAMERA_RAW -> Icons.Rounded.PhotoCamera
                                else -> Icons.Rounded.Image
                            },
                            contentDescription = null,
                            tint = SecundaSilver,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = probe.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = CrispWhite,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = FurnaceCard,
                            border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.50f))
                        ) {
                            Text(
                                text = mediaKindBadgeLabel(probe.mediaKind),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = EmberFlame,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        if (probe.extension.isNotBlank()) {
                            Text(
                                text = probe.extension.uppercase(Locale.US),
                                style = ForgeReadoutStyle,
                                color = SecundaSilver
                            )
                        }
                    }

                    val metaParts = buildList {
                        add(probe.displayResolution())
                        if (probe.mediaKind == MediaKind.VIDEO && probe.durationMs > 0L) {
                            add(probe.formattedDuration())
                        }
                        add(probe.formattedSize())
                    }
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = SecundaSilver
                    )
                }
            }

            val safeBaselineError = baselineError?.takeIf { it.isNotBlank() }
            if (safeBaselineError != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = ErrorStripContainer,
                    border = BorderStroke(1.dp, MasserCrimson.copy(alpha = 0.65f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = CoralError,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = safeBaselineError,
                            style = MaterialTheme.typography.bodySmall,
                            color = CoralError,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = onRetryBaseline,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CoralError.copy(alpha = 0.7f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Retry",
                                style = MaterialTheme.typography.labelMedium,
                                color = CrispWhite
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimestampControlCard(
    durationMs: Long,
    timestampMs: Long,
    isBusy: Boolean,
    onTimestampChange: (Long) -> Unit,
    onRandomTimestamp: () -> Unit
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val clampedTsFloat = timestampMs.coerceIn(0L, safeDuration).toFloat()
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFloat by remember { mutableFloatStateOf(clampedTsFloat) }

    LaunchedEffect(clampedTsFloat) {
        if (!isScrubbing) {
            scrubFloat = clampedTsFloat
        }
    }

    val previewMs = scrubFloat.roundToLong().coerceIn(0L, safeDuration)

    WorkshopDomainCard(
        domain = EngineDomain.FORGE,
        isActive = false,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Sample Frame Timestamp",
                        style = MaterialTheme.typography.titleSmall,
                        color = CrispWhite
                    )
                    Text(
                        text = "${formatTimestampTenths(previewMs)} / ${formatTimestampTenths(safeDuration)}",
                        style = ForgeReadoutStyle,
                        color = ForgeGold
                    )
                }

                OutlinedButton(
                    onClick = onRandomTimestamp,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ForgeBevelOutline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = FurnaceCard,
                        contentColor = EmberFlame
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Casino,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Random",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Slider(
                value = scrubFloat.coerceIn(0f, safeDuration.toFloat()),
                onValueChange = { next ->
                    isScrubbing = true
                    scrubFloat = next
                },
                onValueChangeFinished = {
                    isScrubbing = false
                    onTimestampChange(scrubFloat.roundToLong().coerceIn(0L, safeDuration))
                },
                valueRange = 0f..safeDuration.toFloat(),
                enabled = !isBusy,
                colors = SliderDefaults.colors(
                    thumbColor = EmberFlame,
                    activeTrackColor = EmberFlame,
                    inactiveTrackColor = SlateOutline
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LabActionsCard(
    includeOriginalFrame: Boolean,
    readyTabCount: Int,
    hasCandidates: Boolean,
    isPreparingBaseline: Boolean,
    isRenderingSamples: Boolean,
    onToggleIncludeOriginal: (Boolean) -> Unit,
    onSuggestBestConfig: () -> Unit,
    onRenderAll: () -> Unit,
    onOpenFullscreenCompare: () -> Unit
) {
    WorkshopDomainCard(
        domain = EngineDomain.FORGE,
        isActive = isRenderingSamples,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Include Untouched Original Frame",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CrispWhite
                    )
                    Text(
                        text = "Keeps an ORIG tab inside Fullscreen Compare",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSteel
                    )
                }
                Switch(
                    checked = includeOriginalFrame,
                    onCheckedChange = onToggleIncludeOriginal,
                    colors = forgeSwitchColors()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onSuggestBestConfig,
                    enabled = !isPreparingBaseline && !isRenderingSamples,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ForgeGold.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = FurnaceCard,
                        contentColor = ForgeGold
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .defaultMinSize(minHeight = 46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Suggest Best Config",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onRenderAll,
                    enabled = !isPreparingBaseline && !isRenderingSamples && hasCandidates,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = FurnaceCard,
                        contentColor = CrispWhite
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 46.dp)
                ) {
                    if (isRenderingSamples) {
                        CircularProgressIndicator(
                            color = EmberFlame,
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = EmberFlame,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isRenderingSamples) "Rendering..." else "Render All",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            ForgePrimaryButton(
                onClick = onOpenFullscreenCompare,
                text = "Fullscreen Compare ($readyTabCount)",
                icon = Icons.Rounded.Fullscreen,
                enabled = readyTabCount >= 1,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SampleCandidateCard(
    probe: MediaProbeResult,
    timestampMs: Long,
    candidate: SampleCandidateSpec,
    result: RenderedSampleResult?,
    canRemove: Boolean,
    isPreparingBaseline: Boolean,
    onOpenFullscreenCompare: () -> Unit,
    onSavePreset: () -> Unit,
    onUseConfig: () -> Unit,
    onRemove: () -> Unit,
    onVote: (SampleVoteState) -> Unit,
    onUpdateDraft: ((SampleEntryDraft) -> SampleEntryDraft) -> Unit,
    onRenderSingle: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val isRendering = result?.status == SampleRenderStatus.RENDERING
    val isReady = result != null &&
        result.status == SampleRenderStatus.READY &&
        !result.previewFilePath.isNullOrBlank()
    val isBest = result?.isRecommendedBest == true
    val recSummary = result?.recommendationSummary?.takeIf { it.isNotBlank() }
    val draft = candidate.draft ?: remember(probe, candidate.pipelineSpec) {
        SampleEntryDraft.fromPipelineSpec(probe, candidate.pipelineSpec)
    }

    val estimate = remember(probe, candidate.pipelineSpec, result?.calibration) {
        FilesizeEstimator.estimateLocalPipeline(
            probe = probe,
            spec = candidate.pipelineSpec,
            sampleCalibration = result?.calibration
        )
    }

    WorkshopDomainCard(
        domain = EngineDomain.FORGE,
        isActive = isRendering,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(13.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Card Header
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = FurnaceCard,
                            border = BorderStroke(
                                1.dp,
                                if (isBest) ForgeGold else EmberFlame.copy(alpha = 0.55f)
                            )
                        ) {
                            Text(
                                text = "#${candidate.indexNumber}",
                                style = ForgeReadoutStyle,
                                color = if (isBest) ForgeGold else EmberFlame,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Text(
                            text = candidate.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = CrispWhite,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    if (candidate.voteState != SampleVoteState.NEUTRAL) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (candidate.voteState == SampleVoteState.UP) {
                                EmeraldSuccess.copy(alpha = 0.18f)
                            } else {
                                CoralError.copy(alpha = 0.18f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (candidate.voteState == SampleVoteState.UP) {
                                    EmeraldSuccess.copy(alpha = 0.55f)
                                } else {
                                    CoralError.copy(alpha = 0.55f)
                                }
                            )
                        ) {
                            Text(
                                text = if (candidate.voteState == SampleVoteState.UP) "👍" else "👎",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (isBest || recSummary != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isBest) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = ForgeGold.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, ForgeGold),
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text(
                                    text = "★ Best Config",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = ForgeGold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        }
                        if (recSummary != null) {
                            Text(
                                text = recSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isBest) ForgeGold else SecundaSilver,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }
                    }
                }

                // Header Quick Actions Row (Vote Up / Vote Down / Save Preset / Use Config / Remove)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val next = if (candidate.voteState == SampleVoteState.UP) {
                                    SampleVoteState.NEUTRAL
                                } else {
                                    SampleVoteState.UP
                                }
                                onVote(next)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ThumbUp,
                                contentDescription = "Vote Up",
                                tint = if (candidate.voteState == SampleVoteState.UP) {
                                    EmeraldSuccess
                                } else {
                                    MutedSteel
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val next = if (candidate.voteState == SampleVoteState.DOWN) {
                                    SampleVoteState.NEUTRAL
                                } else {
                                    SampleVoteState.DOWN
                                }
                                onVote(next)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ThumbDown,
                                contentDescription = "Vote Down",
                                tint = if (candidate.voteState == SampleVoteState.DOWN) {
                                    CoralError
                                } else {
                                    MutedSteel
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onSavePreset,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BookmarkAdd,
                                contentDescription = "Save Preset",
                                tint = EmberFlame,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onRemove,
                            enabled = canRemove,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Remove Sample Config",
                                tint = if (canRemove) CoralError.copy(alpha = 0.85f) else MutedSteel.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onUseConfig,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.72f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MoltenEmber.copy(alpha = 0.14f),
                            contentColor = EmberFlame
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Use Config",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (!canRemove) {
                    Text(
                        text = "Keep at least 1 sample",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSteel
                    )
                }
            }

            // Rendered Preview Strip
            SamplePreviewStrip(
                timestampMs = timestampMs,
                result = result,
                isReady = isReady,
                isRendering = isRendering,
                isPreparingBaseline = isPreparingBaseline,
                onOpenFullscreenCompare = onOpenFullscreenCompare,
                onRetry = onRenderSingle
            )

            HorizontalDivider(color = ForgeBevelOutline)

            // Interactive Sample Draft Config Editor
            val isImageOrRaw = probe.mediaKind == MediaKind.IMAGE || probe.mediaKind == MediaKind.CAMERA_RAW
            val isGif = !isImageOrRaw && draft.targetExtension == "gif"
            val containerChoices = if (isImageOrRaw) {
                listOf("jpg", "webp", "png")
            } else {
                listOf("mp4", "mkv", "webm", "gif")
            }

            // Container Chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Container Format",
                    style = MaterialTheme.typography.labelMedium,
                    color = SecundaSilver
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    containerChoices.forEach { ext ->
                        FilterChip(
                            selected = draft.targetExtension.equals(ext, ignoreCase = true),
                            onClick = {
                                onUpdateDraft { current ->
                                    current.harmonizedForContainer(ext, current.videoCodec, probe.mediaKind)
                                }
                            },
                            label = { Text(ext.uppercase(Locale.US)) },
                            colors = forgeFilterChipColors()
                        )
                    }
                }
            }

            // Video Codec Chips (Video & non-GIF)
            if (!isImageOrRaw && !isGif) {
                val codecChoices = buildList {
                    add(VideoCodecChoice.AV1 to "AV1")
                    if (draft.targetExtension != "webm") {
                        add(VideoCodecChoice.HEVC to "HEVC")
                    }
                    if (draft.targetExtension == "webm" || draft.targetExtension == "mkv") {
                        add(VideoCodecChoice.VP9 to "VP9")
                    }
                    if (draft.targetExtension != "webm") {
                        add(VideoCodecChoice.H264 to "H.264")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Video Codec",
                            style = MaterialTheme.typography.labelMedium,
                            color = SecundaSilver
                        )
                        ForgeInfoTipButton(topic = SettingTopic.VIDEO_CODEC)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        codecChoices.forEach { (codec, label) ->
                            FilterChip(
                                selected = draft.videoCodec == codec,
                                onClick = {
                                    onUpdateDraft { current ->
                                        current.harmonizedForContainer(
                                            ext = current.targetExtension,
                                            codec = codec,
                                            mediaKind = probe.mediaKind
                                        )
                                    }
                                },
                                label = { Text(label) },
                                colors = forgeFilterChipColors()
                            )
                        }
                    }
                }
            }

            // Max Dimension Cap Chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Max Dimension Cap",
                    style = MaterialTheme.typography.labelMedium,
                    color = SecundaSilver
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SAMPLE_DIM_OPTIONS.forEach { (dim, label) ->
                        FilterChip(
                            selected = draft.maxDimensionPx == dim,
                            onClick = { onUpdateDraft { it.copy(maxDimensionPx = dim) } },
                            label = { Text(label) },
                            colors = forgeFilterChipColors()
                        )
                    }
                }
            }

            // Compress Mode Chips (Quality vs Target MB, disabled for GIF)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Compression Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = SecundaSilver
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = draft.compressMode == CompressMode.QUALITY && !isGif,
                        enabled = !isGif,
                        onClick = { onUpdateDraft { it.copy(compressMode = CompressMode.QUALITY) } },
                        label = { Text("Quality") },
                        colors = forgeFilterChipColors()
                    )
                    FilterChip(
                        selected = draft.compressMode == CompressMode.TARGET_BYTES && !isGif,
                        enabled = !isGif,
                        onClick = { onUpdateDraft { it.copy(compressMode = CompressMode.TARGET_BYTES) } },
                        label = { Text("Target MB") },
                        colors = forgeFilterChipColors()
                    )
                }

                when {
                    isGif -> {
                        Text(
                            text = "Animated GIF uses palette optimization capped at 540px",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSteel
                        )
                    }
                    draft.compressMode == CompressMode.QUALITY -> {
                        if (isImageOrRaw) {
                            if (draft.targetExtension == "png") {
                                Text(
                                    text = "PNG uses lossless pixel encoding (adjust Max Dimension to shrink size)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedSteel
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Image Quality: ${draft.imageQuality.coerceIn(35, 98)}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SecundaSilver,
                                        modifier = Modifier.weight(1f)
                                    )
                                    ForgeInfoTipButton(topic = SettingTopic.CRF_SLIDER)
                                }
                                Slider(
                                    value = draft.imageQuality.coerceIn(35, 98).toFloat(),
                                    onValueChange = { next ->
                                        val q = next.roundToInt().coerceIn(35, 98)
                                        if (q != draft.imageQuality) {
                                            onUpdateDraft { it.copy(imageQuality = q) }
                                        }
                                    },
                                    valueRange = 35f..98f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = EmberFlame,
                                        activeTrackColor = EmberFlame,
                                        inactiveTrackColor = SlateOutline
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Video CRF (lower = sharper): ${draft.videoCrf.coerceIn(16, 38)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SecundaSilver,
                                    modifier = Modifier.weight(1f)
                                )
                                ForgeInfoTipButton(topic = SettingTopic.CRF_SLIDER)
                            }
                            Slider(
                                value = draft.videoCrf.coerceIn(16, 38).toFloat(),
                                onValueChange = { next ->
                                    val crf = next.roundToInt().coerceIn(16, 38)
                                    if (crf != draft.videoCrf) {
                                        onUpdateDraft { it.copy(videoCrf = crf) }
                                    }
                                },
                                valueRange = 16f..38f,
                                colors = SliderDefaults.colors(
                                    thumbColor = EmberFlame,
                                    activeTrackColor = EmberFlame,
                                    inactiveTrackColor = SlateOutline
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Target Size (MB)",
                                style = MaterialTheme.typography.labelMedium,
                                color = SecundaSilver
                            )
                            ForgeInfoTipButton(topic = SettingTopic.TARGET_FILESIZE)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SAMPLE_TARGET_MB_PRESETS.forEach { mb ->
                                FilterChip(
                                    selected = draft.targetMbText.trim() == mb,
                                    onClick = {
                                        onUpdateDraft {
                                            it.copy(
                                                compressMode = CompressMode.TARGET_BYTES,
                                                targetMbText = mb
                                            )
                                        }
                                    },
                                    label = { Text("$mb MB") },
                                    colors = forgeFilterChipColors()
                                )
                            }
                        }
                        OutlinedTextField(
                            value = draft.targetMbText,
                            onValueChange = { raw ->
                                onUpdateDraft {
                                    it.copy(
                                        compressMode = CompressMode.TARGET_BYTES,
                                        targetMbText = raw
                                    )
                                }
                            },
                            label = { Text("Custom Target (MB)") },
                            placeholder = { Text("e.g. 9.5") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmberFlame,
                                unfocusedBorderColor = ForgeBevelOutline,
                                focusedLabelColor = EmberFlame,
                                unfocusedLabelColor = SecundaSilver,
                                focusedTextColor = CrispWhite,
                                unfocusedTextColor = CrispWhite,
                                cursorColor = EmberFlame,
                                focusedContainerColor = FurnaceCard,
                                unfocusedContainerColor = FurnaceCard
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Fast ½ RAW Switch (Camera RAW only)
            if (probe.mediaKind == MediaKind.CAMERA_RAW) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Fast ½ RAW",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CrispWhite
                        )
                        Text(
                            text = "4x faster half-size demosaic with low RAM footprint",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSteel
                        )
                    }
                    ForgeInfoTipButton(topic = SettingTopic.RAW_HALF_SIZE_FAST)
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = draft.rawHalfSizeFast,
                        onCheckedChange = { checked ->
                            onUpdateDraft { it.copy(rawHalfSizeFast = checked) }
                        },
                        colors = forgeSwitchColors()
                    )
                }
            }

            // Live Size Estimate Badge + Per-Card Render Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiveSizeEstimateBadge(
                    estimate = estimate,
                    domain = EngineDomain.FORGE,
                    prefix = "Est.",
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onRenderSingle,
                    enabled = !isRendering && !isPreparingBaseline,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = FurnaceCard,
                        contentColor = EmberFlame
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (isRendering) {
                        CircularProgressIndicator(
                            color = EmberFlame,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isReady) Icons.Rounded.Refresh else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isRendering) "Rendering..." else "Render",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SamplePreviewStrip(
    timestampMs: Long,
    result: RenderedSampleResult?,
    isReady: Boolean,
    isRendering: Boolean,
    isPreparingBaseline: Boolean,
    onOpenFullscreenCompare: () -> Unit,
    onRetry: () -> Unit
) {
    val previewBitmap = rememberSafeSampleImageBitmapReloadable(
        filePath = if (isReady) result?.previewFilePath else null,
        maxDimensionPx = 560,
        revisionKey = Triple(timestampMs, result?.sampleBytes ?: 0L, result?.width ?: 0)
    )

    when {
        isRendering -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = FurnaceCard,
                border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = EmberFlame,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.2.dp
                    )
                    Text(
                        text = "Rendering sample preview & perceptual SSIM...",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecundaSilver
                    )
                }
            }
        }
        result != null && result.status == SampleRenderStatus.FAILED -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = ErrorStripContainer,
                border = BorderStroke(1.dp, MasserCrimson.copy(alpha = 0.65f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = CoralError,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = result.error ?: "Sample render failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralError,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onRetry,
                        enabled = !isPreparingBaseline,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CoralError.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrispWhite
                        )
                    }
                }
            }
        }
        isReady && result != null -> {
            val pctOfOriginal = (result.sizeRatioVsOriginal * 100.0).roundToInt()
            val projectedFormatted = FilesizeEstimator.formatBytes(result.projectedTotalBytes)
            val ssimFormatted = String.format(Locale.US, "SSIM %.2f", result.perceptualScore)
            val ssimColor = when {
                result.perceptualScore >= 0.94 -> EmeraldSuccess
                result.perceptualScore >= 0.88 -> ForgeGold
                else -> CoralError
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenFullscreenCompare),
                shape = RoundedCornerShape(12.dp),
                color = FurnaceCard,
                border = BorderStroke(1.dp, ForgeBevelOutline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 104.dp, height = 74.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(VolcanicObsidian),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap,
                                contentDescription = "Rendered candidate thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(
                                color = EmberFlame,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AnvilGunmetal,
                                border = BorderStroke(1.dp, ForgeBevelOutline)
                            ) {
                                Text(
                                    text = "$pctOfOriginal% • $projectedFormatted",
                                    style = ForgeReadoutStyle,
                                    color = CrispWhite,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ssimColor.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, ssimColor.copy(alpha = 0.55f))
                            ) {
                                Text(
                                    text = ssimFormatted,
                                    style = ForgeReadoutStyle,
                                    color = ssimColor,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }

                        val resText = if (result.width > 0 && result.height > 0) {
                            "${result.width}×${result.height} • Tap thumbnail to compare"
                        } else {
                            "Tap thumbnail to inspect fullscreen"
                        }
                        Text(
                            text = resText,
                            style = MaterialTheme.typography.labelSmall,
                            color = SecundaSilver,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        else -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = FurnaceCard,
                border = BorderStroke(1.dp, ForgeBevelOutline)
            ) {
                Text(
                    text = "Not rendered yet — tap Render or Render All to preview quality & calibrated size",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSteel,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun forgeFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MoltenEmber.copy(alpha = 0.22f),
    selectedLabelColor = EmberFlame,
    containerColor = AnvilGunmetal,
    labelColor = SecundaSilver,
    disabledContainerColor = SlateCard.copy(alpha = 0.5f),
    disabledLabelColor = MutedSteel.copy(alpha = 0.5f)
)

@Composable
private fun forgeSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = CrispWhite,
    checkedTrackColor = EmberFlame,
    uncheckedThumbColor = SecundaSilver,
    uncheckedTrackColor = SlateCard,
    uncheckedBorderColor = SlateOutline
)

private fun mediaKindBadgeLabel(mediaKind: MediaKind): String = when (mediaKind) {
    MediaKind.VIDEO -> "VIDEO"
    MediaKind.AUDIO -> "AUDIO"
    MediaKind.IMAGE -> "IMAGE"
    MediaKind.CAMERA_RAW -> "RAW"
    MediaKind.REMOTE_URL -> "LINK"
    MediaKind.UNKNOWN -> "MEDIA"
}

private fun formatTimestampTenths(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val totalSec = clamped / 1000L
    val min = totalSec / 60L
    val sec = totalSec % 60L
    val tenths = (clamped % 1000L) / 100L
    return String.format(Locale.US, "%02d:%02d.%d", min, sec, tenths)
}
