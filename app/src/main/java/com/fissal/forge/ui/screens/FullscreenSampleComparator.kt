package com.fissal.forge.ui.screens

import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fissal.forge.domain.logic.FilesizeEstimator
import com.fissal.forge.domain.logic.NormalizedViewportState
import com.fissal.forge.domain.logic.TapZone
import com.fissal.forge.domain.logic.ViewportSyncMath
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.RenderedSampleResult
import com.fissal.forge.domain.model.SampleCandidateSpec
import com.fissal.forge.domain.model.SampleRenderStatus
import com.fissal.forge.domain.model.SampleVoteState
import com.fissal.forge.engine.SampleFrameEngine
import com.fissal.forge.ui.components.DomainRolePill
import com.fissal.forge.ui.components.EngineDomain
import com.fissal.forge.ui.components.ForgePrimaryButton
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
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.RelayCard
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val COMPARATOR_BITMAP_CACHE_MAX_KB = 48 * 1024 // 48 MB bounded cache
private const val COMPARATOR_MAX_DECODE_DIM_PX = 1920

/**
 * Fullscreen OLED-black A/B Blink Sample Comparator.
 *
 * Disables horizontal swipe tab-switching so pan and pinch-zoom never misfire, executes Left/Right
 * edge taps instantaneously on pointer release for zero-lag blink inspection, locks normalized
 * `(X, Y, Zoom)` coordinates across differing resolutions/aspects via [ViewportSyncMath], and
 * surfaces Vote Up / Vote Down / Remove / Preset / Studio actions on long-press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenSampleComparator(
    probe: MediaProbeResult,
    tabs: List<SampleCandidateSpec>,
    renderedResults: Map<String, RenderedSampleResult>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onVote: (candidateId: String, SampleVoteState) -> Unit,
    onRemoveCandidate: (candidateId: String) -> Unit,
    onSaveAsPreset: (candidateId: String, suggestedName: String, spec: PipelineSpec) -> Unit,
    onUseConfigInStudio: (candidateId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onClose)

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val tabRowState = rememberLazyListState()

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var cameraState by remember { mutableStateOf(NormalizedViewportState()) }
    var showActionSheet by remember { mutableStateOf(false) }
    var lastKnownActiveIndex by remember { mutableIntStateOf(0) }
    var bitmapVersion by remember { mutableIntStateOf(0) }

    // Safe active tab index resolution, clamping cleanly if an active sample was just removed.
    val rawActiveIndex = tabs.indexOfFirst { it.id == activeTabId }
    val activeIndex = if (rawActiveIndex >= 0) {
        rawActiveIndex
    } else {
        ViewportSyncMath.clampActiveIndexAfterRemoval(lastKnownActiveIndex, tabs.size)
    }
    SideEffect {
        if (tabs.isNotEmpty()) {
            lastKnownActiveIndex = activeIndex
        }
    }

    val activeTab = tabs.getOrNull(activeIndex)
    val activeRendered = activeTab?.let { renderedResults[it.id] }
    val userCandidateCount = remember(tabs) { tabs.count { !it.isOriginalBaseline } }

    // Bounded 48 MB ImageBitmap LruCache without recycling inside entryRemoved (GPU frame safety).
    val bitmapCache = remember {
        object : LruCache<String, ImageBitmap>(COMPARATOR_BITMAP_CACHE_MAX_KB) {
            override fun sizeOf(key: String, value: ImageBitmap): Int {
                val bytesPerPixel = when (value.config) {
                    ImageBitmapConfig.Alpha8 -> 1L
                    ImageBitmapConfig.Rgb565 -> 2L
                    ImageBitmapConfig.F16 -> 8L
                    else -> 4L
                }
                val kb = (value.width.toLong() * value.height.toLong() * bytesPerPixel) / 1024L
                return kb.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            }
        }
    }

    DisposableEffect(bitmapCache) {
        onDispose {
            bitmapCache.evictAll()
        }
    }

    val activeCacheKey = remember(activeTab, activeRendered) {
        if (activeTab == null || activeRendered == null) {
            null
        } else {
            val path = activeRendered.previewFilePath?.takeIf { it.isNotBlank() }
            if (path == null || activeRendered.status != SampleRenderStatus.READY) {
                null
            } else {
                buildBitmapCacheKey(
                    candidateId = activeTab.id,
                    path = path,
                    sampleBytes = activeRendered.sampleBytes,
                    width = activeRendered.width,
                    height = activeRendered.height
                )
            }
        }
    }

    val activeBitmap = remember(activeCacheKey, bitmapVersion) {
        activeCacheKey?.let { bitmapCache.get(it) }
    }

    // Pre-warm active tab plus immediate Left & Right neighbors for 0ms blink switching.
    LaunchedEffect(activeTabId, tabs, renderedResults) {
        if (tabs.isEmpty()) return@LaunchedEffect

        val missingActive = tabs.indexOfFirst { it.id == activeTabId } < 0
        val resolvedIndex = if (missingActive) {
            ViewportSyncMath.clampActiveIndexAfterRemoval(lastKnownActiveIndex, tabs.size)
        } else {
            tabs.indexOfFirst { it.id == activeTabId }
        }
        if (missingActive) {
            onSelectTab(tabs[resolvedIndex].id)
        }

        val indicesToWarm = buildList {
            add(resolvedIndex)
            if (tabs.size > 1) {
                add((resolvedIndex + 1) % tabs.size)
                add((resolvedIndex - 1 + tabs.size) % tabs.size)
            }
        }.distinct()

        for (idx in indicesToWarm) {
            coroutineContext.ensureActive()
            val candidate = tabs.getOrNull(idx) ?: continue
            val rendered = renderedResults[candidate.id] ?: continue
            if (rendered.status != SampleRenderStatus.READY) continue
            val path = rendered.previewFilePath?.takeIf { it.isNotBlank() } ?: continue
            val key = buildBitmapCacheKey(
                candidateId = candidate.id,
                path = path,
                sampleBytes = rendered.sampleBytes,
                width = rendered.width,
                height = rendered.height
            )
            if (bitmapCache.get(key) != null) continue

            val decoded = withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (!file.exists() || file.length() <= 0L) {
                        null
                    } else {
                        SampleFrameEngine.decodeSampleFileSafely(
                            file = file,
                            maxDimensionPx = COMPARATOR_MAX_DECODE_DIM_PX
                        )
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: OutOfMemoryError) {
                    bitmapCache.evictAll()
                    System.gc()
                    null
                } catch (_: Throwable) {
                    null
                }
            }
            coroutineContext.ensureActive()
            if (decoded != null) {
                bitmapCache.put(key, decoded.asImageBitmap())
                bitmapVersion++
            }
        }
    }

    // Seed initial referenceAspectRatio from baseline or first valid image so 480p/720p/1080p
    // render pixel-for-pixel locked even at 1.0x before the user's first pinch or pan gesture.
    LaunchedEffect(tabs, renderedResults, activeBitmap) {
        if (cameraState.referenceAspectRatio == null) {
            val baselineRendered = tabs.firstOrNull { it.isOriginalBaseline }?.let { renderedResults[it.id] }
            val refW = baselineRendered?.width?.takeIf { it > 0 }
                ?: activeBitmap?.width?.takeIf { it > 0 }
                ?: activeRendered?.width?.takeIf { it > 0 }
            val refH = baselineRendered?.height?.takeIf { it > 0 }
                ?: activeBitmap?.height?.takeIf { it > 0 }
                ?: activeRendered?.height?.takeIf { it > 0 }
            if (refW != null && refH != null) {
                cameraState = cameraState.copy(
                    referenceAspectRatio = refW.toFloat() / refH.toFloat()
                )
            }
        }
    }

    // Keep top tab strip scrolled to the active sample when switching via edge blink taps.
    LaunchedEffect(activeIndex, tabs.size) {
        if (tabs.isNotEmpty() && activeIndex in tabs.indices) {
            tabRowState.animateScrollToItem(activeIndex)
        }
    }

    val activeImgW = activeBitmap?.width
        ?: activeRendered?.width?.takeIf { it > 0 }
        ?: probe.effectiveWidth.takeIf { it > 0 }
        ?: 1920
    val activeImgH = activeBitmap?.height
        ?: activeRendered?.height?.takeIf { it > 0 }
        ?: probe.effectiveHeight.takeIf { it > 0 }
        ?: 1080

    val transform = remember(cameraState, viewportSize, activeImgW, activeImgH) {
        ViewportSyncMath.computeTransform(
            state = cameraState,
            viewportWidthPx = viewportSize.width.toFloat(),
            viewportHeightPx = viewportSize.height.toFloat(),
            imageWidthPx = activeImgW,
            imageHeightPx = activeImgH
        )
    }

    // Keep latest state references inside pointerInput(Unit) so switching tabs never cancels gestures.
    val latestTabs by rememberUpdatedState(tabs)
    val latestActiveIndex by rememberUpdatedState(activeIndex)
    val latestViewportSize by rememberUpdatedState(viewportSize)
    val latestActiveImgW by rememberUpdatedState(activeImgW)
    val latestActiveImgH by rememberUpdatedState(activeImgH)
    val latestOnSelectTab by rememberUpdatedState(onSelectTab)
    val latestOnOpenActionSheet by rememberUpdatedState { showActionSheet = true }
    val doubleTapSlopPx = with(density) { 48.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Layer 1: Full-viewport interactive image stage (no HorizontalPager, 0ms L/R blink tap).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .pointerInput(Unit) {
                    var lastCenterTapTimeMs = 0L
                    var lastCenterTapPos = Offset.Zero

                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val downPos = firstDown.position
                        val downTimeMs = firstDown.uptimeMillis
                        var lastUptimeMs = downTimeMs
                        var hasMovedPastSlop = false
                        var hadMultiTouch = false
                        var isLongPressTriggered = false
                        var prevPressedCount = 1

                        while (true) {
                            val waitingForLongPress =
                                !hasMovedPastSlop && !hadMultiTouch && !isLongPressTriggered
                            val event = if (waitingForLongPress) {
                                val elapsedMs = (lastUptimeMs - downTimeMs).coerceAtLeast(0L)
                                val remainingMs = viewConfiguration.longPressTimeoutMillis - elapsedMs
                                if (remainingMs <= 0L) {
                                    null
                                } else {
                                    withTimeoutOrNull(remainingMs) { awaitPointerEvent() }
                                }
                            } else {
                                awaitPointerEvent()
                            }

                            if (event == null) {
                                isLongPressTriggered = true
                                lastCenterTapTimeMs = 0L
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnOpenActionSheet()
                                continue
                            }

                            val pressedChanges = event.changes.filter { it.pressed }
                            val curPressedCount = pressedChanges.size
                            if (curPressedCount == 0) {
                                if (!isLongPressTriggered && !hasMovedPastSlop && !hadMultiTouch) {
                                    event.changes.forEach { it.consume() }
                                    val releaseTimeMs = event.changes.maxOfOrNull { it.uptimeMillis }
                                        ?: lastUptimeMs
                                    val currentTabs = latestTabs
                                    val vpW = latestViewportSize.width.toFloat()
                                    val vpH = latestViewportSize.height.toFloat()
                                    if (currentTabs.isNotEmpty() && vpW > 0f) {
                                        when (ViewportSyncMath.classifyTapZone(downPos.x, vpW)) {
                                            TapZone.LEFT -> {
                                                lastCenterTapTimeMs = 0L
                                                if (currentTabs.size > 1) {
                                                    val prevIdx =
                                                        (latestActiveIndex - 1 + currentTabs.size) % currentTabs.size
                                                    latestOnSelectTab(currentTabs[prevIdx].id)
                                                }
                                            }
                                            TapZone.RIGHT -> {
                                                lastCenterTapTimeMs = 0L
                                                if (currentTabs.size > 1) {
                                                    val nextIdx = (latestActiveIndex + 1) % currentTabs.size
                                                    latestOnSelectTab(currentTabs[nextIdx].id)
                                                }
                                            }
                                            TapZone.CENTER -> {
                                                val withinTimeout = lastCenterTapTimeMs > 0L &&
                                                    (releaseTimeMs - lastCenterTapTimeMs) <=
                                                    viewConfiguration.doubleTapTimeoutMillis
                                                val withinSlop =
                                                    (downPos - lastCenterTapPos).getDistance() <= doubleTapSlopPx
                                                if (withinTimeout && withinSlop) {
                                                    lastCenterTapTimeMs = 0L
                                                    cameraState = ViewportSyncMath.toggleDoubleTapZoom(
                                                        state = cameraState,
                                                        tapXPx = downPos.x,
                                                        tapYPx = downPos.y,
                                                        viewportWidthPx = vpW,
                                                        viewportHeightPx = vpH,
                                                        imageWidthPx = latestActiveImgW,
                                                        imageHeightPx = latestActiveImgH
                                                    )
                                                } else {
                                                    lastCenterTapTimeMs = releaseTimeMs
                                                    lastCenterTapPos = downPos
                                                }
                                            }
                                        }
                                    }
                                }
                                break
                            }

                            lastUptimeMs = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastUptimeMs
                            if (curPressedCount > 1) {
                                hadMultiTouch = true
                                lastCenterTapTimeMs = 0L
                            } else if (!hasMovedPastSlop) {
                                val dist = (pressedChanges[0].position - downPos).getDistance()
                                if (dist > viewConfiguration.touchSlop) {
                                    hasMovedPastSlop = true
                                    lastCenterTapTimeMs = 0L
                                }
                            }

                            if ((hasMovedPastSlop || hadMultiTouch) && !isLongPressTriggered) {
                                // Skip pointer-count transition frames so adding/lifting a 2nd finger
                                // never causes a centroid pan jump.
                                if (curPressedCount == prevPressedCount) {
                                    val zoomChange = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    if ((zoomChange != 1f || pan != Offset.Zero) &&
                                        centroid != Offset.Unspecified
                                    ) {
                                        val vpW = latestViewportSize.width.toFloat()
                                        val vpH = latestViewportSize.height.toFloat()
                                        cameraState = ViewportSyncMath.applyGesture(
                                            state = cameraState,
                                            panDeltaXPx = pan.x,
                                            panDeltaYPx = pan.y,
                                            zoomChange = zoomChange,
                                            centroidXPx = centroid.x,
                                            centroidYPx = centroid.y,
                                            viewportWidthPx = vpW,
                                            viewportHeightPx = vpH,
                                            imageWidthPx = latestActiveImgW,
                                            imageHeightPx = latestActiveImgH
                                        )
                                    }
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) {
                                            change.consume()
                                        }
                                    }
                                } else if (curPressedCount > 1) {
                                    event.changes.forEach { it.consume() }
                                }
                            } else if (isLongPressTriggered) {
                                event.changes.forEach { it.consume() }
                            }

                            prevPressedCount = curPressedCount
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (activeBitmap != null && transform.fittedWidthPx > 0f && transform.fittedHeightPx > 0f) {
                val fittedWidthDp = with(density) { transform.fittedWidthPx.toDp() }
                val fittedHeightDp = with(density) { transform.fittedHeightPx.toDp() }
                Image(
                    bitmap = activeBitmap,
                    contentDescription = activeTab?.label ?: "Sample frame",
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Medium,
                    modifier = Modifier
                        .size(width = fittedWidthDp, height = fittedHeightDp)
                        .graphicsLayer {
                            scaleX = transform.effectiveZoom
                            scaleY = transform.effectiveZoom
                            translationX = transform.translationXPx
                            translationY = transform.translationYPx
                        }
                )
            } else if (activeTab != null) {
                ComparatorPlaceholderState(rendered = activeRendered)
            }
        }

        // Layer 2: Slim Top Tab Strip (statusBarsPadding).
        ComparatorTopBar(
            tabs = tabs,
            renderedResults = renderedResults,
            activeTabId = activeTab?.id ?: activeTabId,
            tabRowState = tabRowState,
            zoomScale = cameraState.zoomScale,
            hasActiveTab = activeTab != null,
            onSelectTab = onSelectTab,
            onResetZoom = {
                cameraState = NormalizedViewportState(
                    referenceAspectRatio = cameraState.referenceAspectRatio
                )
            },
            onOpenActions = { showActionSheet = true },
            onClose = onClose,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        )

        // Layer 3: Minimal Bottom-Center HUD Pill (navigationBarsPadding).
        if (activeTab != null) {
            ComparatorBottomHudPill(
                probe = probe,
                activeTab = activeTab,
                activeRendered = activeRendered,
                onClick = { showActionSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }

    if (showActionSheet && activeTab != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showActionSheet = false },
            sheetState = sheetState,
            containerColor = AnvilGunmetal,
            contentColor = CrispWhite
        ) {
            SampleComparatorActionSheetContent(
                probe = probe,
                activeTab = activeTab,
                activeRendered = activeRendered,
                canRemoveSample = !activeTab.isOriginalBaseline && userCandidateCount > 1,
                onVote = { nextVote ->
                    onVote(activeTab.id, nextVote)
                },
                onRemoveSample = {
                    showActionSheet = false
                    onRemoveCandidate(activeTab.id)
                },
                onSaveAsPreset = {
                    showActionSheet = false
                    onSaveAsPreset(activeTab.id, activeTab.label, activeTab.pipelineSpec)
                },
                onUseConfigInStudio = {
                    showActionSheet = false
                    onUseConfigInStudio(activeTab.id)
                },
                onDismiss = { showActionSheet = false }
            )
        }
    }
}

@Composable
private fun ComparatorTopBar(
    tabs: List<SampleCandidateSpec>,
    renderedResults: Map<String, RenderedSampleResult>,
    activeTabId: String,
    tabRowState: androidx.compose.foundation.lazy.LazyListState,
    zoomScale: Float,
    hasActiveTab: Boolean,
    onSelectTab: (String) -> Unit,
    onResetZoom: () -> Unit,
    onOpenActions: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.84f),
                        Color.Black.copy(alpha = 0.45f),
                        Color.Transparent
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Exit fullscreen comparator",
                tint = CrispWhite
            )
        }

        LazyRow(
            state = tabRowState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(tabs, key = { _, item -> item.id }) { _, candidate ->
                val rendered = renderedResults[candidate.id]
                val isSelected = candidate.id == activeTabId
                val isBest = rendered?.isRecommendedBest == true
                val accentColor = when {
                    candidate.isOriginalBaseline -> ArcRelayCyan
                    isBest -> ForgeGold
                    else -> MoltenEmber
                }
                val containerColor = when {
                    isSelected && candidate.isOriginalBaseline -> RelayCard.copy(alpha = 0.92f)
                    isSelected -> FurnaceCard.copy(alpha = 0.94f)
                    else -> ObsidianBg.copy(alpha = 0.72f)
                }
                val borderColor = when {
                    isSelected -> accentColor
                    isBest -> ForgeGold.copy(alpha = 0.45f)
                    else -> SlateOutline.copy(alpha = 0.75f)
                }

                Surface(
                    onClick = { onSelectTab(candidate.id) },
                    shape = RoundedCornerShape(999.dp),
                    color = containerColor,
                    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor)
                ) {
                    Text(
                        text = buildTabChipLabel(candidate, rendered),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) CrispWhite else SecundaSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (zoomScale > 1.02f) {
            Surface(
                onClick = onResetZoom,
                shape = RoundedCornerShape(999.dp),
                color = FurnaceCard.copy(alpha = 0.90f),
                border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.65f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ZoomOutMap,
                        contentDescription = "Reset zoom",
                        tint = EmberFlame,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "1.0×",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = CrispWhite
                    )
                }
            }
        }

        if (hasActiveTab) {
            IconButton(onClick = onOpenActions) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Sample actions",
                    tint = CrispWhite
                )
            }
        }
    }
}

@Composable
private fun ComparatorBottomHudPill(
    probe: MediaProbeResult,
    activeTab: SampleCandidateSpec,
    activeRendered: RenderedSampleResult?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val indexText = if (activeTab.isOriginalBaseline) "ORIG" else "#${activeTab.indexNumber}"
    val sizeText = if (activeTab.isOriginalBaseline) {
        val origBytes = activeRendered?.projectedTotalBytes?.takeIf { it > 0L } ?: probe.fileSizeBytes
        if (origBytes > 0L) {
            "100% • ${FilesizeEstimator.formatBytes(origBytes)}"
        } else {
            "100%"
        }
    } else {
        when (activeRendered?.status) {
            SampleRenderStatus.READY -> {
                if (activeRendered.projectedTotalBytes > 0L) {
                    val pct = (activeRendered.sizeRatioVsOriginal * 100.0).roundToInt().coerceAtLeast(1)
                    "$pct% • ~${FilesizeEstimator.formatBytes(activeRendered.projectedTotalBytes)}"
                } else {
                    "Ready"
                }
            }
            SampleRenderStatus.RENDERING -> "Rendering…"
            SampleRenderStatus.FAILED -> "Failed"
            SampleRenderStatus.IDLE, null -> "Pending"
        }
    }

    val isBest = activeRendered?.isRecommendedBest == true
    val badgeColor = when {
        isBest -> ForgeGold
        activeTab.voteState == SampleVoteState.UP -> EmeraldSuccess
        activeTab.voteState == SampleVoteState.DOWN -> CoralError
        else -> null
    }
    val statusBadge = buildList {
        if (isBest) add("★ Best")
        when (activeTab.voteState) {
            SampleVoteState.UP -> add("👍")
            SampleVoteState.DOWN -> add("👎")
            SampleVoteState.NEUTRAL -> Unit
        }
    }.joinToString(" ")

    val accentBorder = when {
        activeTab.isOriginalBaseline -> ArcRelayCyan.copy(alpha = 0.55f)
        isBest -> ForgeGold.copy(alpha = 0.60f)
        else -> SlateOutline.copy(alpha = 0.85f)
    }

    Surface(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ),
        shape = RoundedCornerShape(999.dp),
        color = ObsidianBg.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, accentBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = indexText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (activeTab.isOriginalBaseline) ArcRelayCyan else EmberFlame,
                maxLines = 1
            )
            Text(
                text = sizeText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = CrispWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (statusBadge.isNotEmpty() && badgeColor != null) {
                Text(
                    text = statusBadge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ComparatorPlaceholderState(rendered: RenderedSampleResult?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(24.dp)
    ) {
        when (rendered?.status) {
            SampleRenderStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = CoralError,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = rendered.error ?: "Sample render failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CoralError
                )
            }
            SampleRenderStatus.RENDERING, SampleRenderStatus.IDLE -> {
                CircularProgressIndicator(
                    color = MoltenEmber,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(30.dp)
                )
                Text(
                    text = "Forging sample frame…",
                    style = MaterialTheme.typography.labelLarge,
                    color = SecundaSilver
                )
            }
            SampleRenderStatus.READY, null -> {
                CircularProgressIndicator(
                    color = SecundaSilver,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun SampleComparatorActionSheetContent(
    probe: MediaProbeResult,
    activeTab: SampleCandidateSpec,
    activeRendered: RenderedSampleResult?,
    canRemoveSample: Boolean,
    onVote: (SampleVoteState) -> Unit,
    onRemoveSample: () -> Unit,
    onSaveAsPreset: () -> Unit,
    onUseConfigInStudio: () -> Unit,
    onDismiss: () -> Unit
) {
    val summaryText = when {
        activeTab.isOriginalBaseline -> {
            val origBytes = activeRendered?.projectedTotalBytes?.takeIf { it > 0L } ?: probe.fileSizeBytes
            val parts = listOfNotNull(
                probe.displayResolution().takeIf { it != "Unknown res" },
                origBytes.takeIf { it > 0L }?.let { FilesizeEstimator.formatBytes(it) }
            )
            if (parts.isEmpty()) "Unmodified source reference" else parts.joinToString(" • ")
        }
        !activeRendered?.recommendationSummary.isNullOrBlank() -> activeRendered?.recommendationSummary!!
        activeRendered?.status == SampleRenderStatus.READY && activeRendered.projectedTotalBytes > 0L -> {
            val pct = (activeRendered.sizeRatioVsOriginal * 100.0).roundToInt().coerceAtLeast(1)
            "$pct% • ~${FilesizeEstimator.formatBytes(activeRendered.projectedTotalBytes)}"
        }
        activeRendered?.status == SampleRenderStatus.FAILED -> activeRendered.error ?: "Render failed"
        else -> activeTab.pipelineSpec.summaryLabel()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DomainRolePill(
                        domain = if (activeTab.isOriginalBaseline) EngineDomain.RELAY else EngineDomain.FORGE,
                        label = if (activeTab.isOriginalBaseline) "ORIG BASELINE" else "SAMPLE #${activeTab.indexNumber}"
                    )
                    if (activeRendered?.isRecommendedBest == true) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = FurnaceCard,
                            border = BorderStroke(1.dp, ForgeGold.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = "★ BEST KNEE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = ForgeGold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Text(
                    text = activeTab.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = CrispWhite
                )

                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecundaSilver
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close actions",
                    tint = SecundaSilver
                )
            }
        }

        HorizontalDivider(color = SlateOutline.copy(alpha = 0.6f))

        // Vote Up / Vote Down row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val isUp = activeTab.voteState == SampleVoteState.UP
            val isDown = activeTab.voteState == SampleVoteState.DOWN

            OutlinedButton(
                onClick = {
                    onVote(if (isUp) SampleVoteState.NEUTRAL else SampleVoteState.UP)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = if (isUp) 1.5.dp else 1.dp,
                    color = if (isUp) EmeraldSuccess else SlateOutline
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isUp) EmeraldSuccess.copy(alpha = 0.16f) else SlateCard,
                    contentColor = if (isUp) EmeraldSuccess else CrispWhite
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.ThumbUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Vote Up 👍",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }

            OutlinedButton(
                onClick = {
                    onVote(if (isDown) SampleVoteState.NEUTRAL else SampleVoteState.DOWN)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    width = if (isDown) 1.5.dp else 1.dp,
                    color = if (isDown) CoralError else SlateOutline
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isDown) CoralError.copy(alpha = 0.16f) else SlateCard,
                    contentColor = if (isDown) CoralError else CrispWhite
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.ThumbDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Vote Down 👎",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        if (!activeTab.isOriginalBaseline) {
            ForgePrimaryButton(
                onClick = onUseConfigInStudio,
                text = "Use This Config in Studio",
                icon = Icons.Rounded.Tune,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = onSaveAsPreset,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, ForgeGold.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = FurnaceCard,
                    contentColor = ForgeGold
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Save as Emoji Preset",
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (canRemoveSample) {
                OutlinedButton(
                    onClick = onRemoveSample,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CoralError.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SlateCard,
                        contentColor = CoralError
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Remove Sample",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun buildTabChipLabel(
    candidate: SampleCandidateSpec,
    rendered: RenderedSampleResult?
): String {
    if (candidate.isOriginalBaseline) {
        return when (candidate.voteState) {
            SampleVoteState.UP -> "ORIG 👍"
            SampleVoteState.DOWN -> "ORIG 👎"
            SampleVoteState.NEUTRAL -> "ORIG"
        }
    }
    return buildString {
        append('#').append(candidate.indexNumber)
        if (rendered?.isRecommendedBest == true) append(" ★")
        when (candidate.voteState) {
            SampleVoteState.UP -> append(" 👍")
            SampleVoteState.DOWN -> append(" 👎")
            SampleVoteState.NEUTRAL -> Unit
        }
    }
}

private fun buildBitmapCacheKey(
    candidateId: String,
    path: String,
    sampleBytes: Long,
    width: Int,
    height: Int
): String = "$candidateId|$path|$sampleBytes|${width}x${height}"
