package com.fissal.forge.ui.components

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.ForgeBevelOutline
import com.fissal.forge.ui.theme.ForgeGold
import com.fissal.forge.ui.theme.ForgeReadoutStyle
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.VolcanicObsidian
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Interactive range audition player for Forge Studio's Trim & Audio section.
 * Seeks frame-accurately while dragging Start/End handles and loops or auditions
 * `[trimStartMs .. trimEndMs]` on device without running a full FFmpeg pass.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrimRangePreviewPlayer(
    probe: MediaProbeResult,
    trimStartMs: Long,
    trimEndMs: Long,
    muteAudio: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isVideo = probe.mediaKind == MediaKind.VIDEO
    val totalMs = probe.durationMs.coerceAtLeast(1000L)
    val safeStartMs = trimStartMs.coerceIn(0L, totalMs - 100L)
    val safeEndMs = trimEndMs.coerceIn(safeStartMs + 100L, totalMs)

    val latestStartMs by rememberUpdatedState(safeStartMs)
    val latestEndMs by rememberUpdatedState(safeEndMs)
    val latestMuteAudio by rememberUpdatedState(muteAudio)

    var mediaPlayerRef by remember(probe.uriString) { mutableStateOf<MediaPlayer?>(null) }
    var activeSurfaceRef by remember(probe.uriString) { mutableStateOf<Surface?>(null) }
    var textureViewRef by remember(probe.uriString) { mutableStateOf<TextureView?>(null) }

    var isPrepared by remember(probe.uriString) { mutableStateOf(false) }
    var isPlaying by remember(probe.uriString) { mutableStateOf(false) }
    var loopEnabled by remember { mutableStateOf(true) }
    var stopAtRangeEndOnce by remember { mutableStateOf(false) }
    var playheadMs by remember(probe.uriString) { mutableLongStateOf(safeStartMs) }
    var playerError by remember(probe.uriString) { mutableStateOf<String?>(null) }
    var videoW by remember(probe.uriString) { mutableIntStateOf(0) }
    var videoH by remember(probe.uriString) { mutableIntStateOf(0) }

    // Coalesced frame-accurate seek state so rapid slider drags never flood MediaPlayer
    var isSeeking by remember(probe.uriString) { mutableStateOf(false) }
    var pendingSeekTarget by remember(probe.uriString) { mutableStateOf<Long?>(null) }

    fun issueSeek(player: MediaPlayer, targetMs: Long) {
        val clamped = targetMs.coerceIn(0L, totalMs)
        playheadMs = clamped
        if (isSeeking) {
            pendingSeekTarget = clamped
            return
        }
        isSeeking = true
        pendingSeekTarget = null
        val ok = runCatching {
            player.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
            true
        }.getOrElse {
            runCatching {
                player.seekTo(clamped.toInt())
                true
            }.getOrDefault(false)
        }
        if (!ok) {
            isSeeking = false
        }
    }

    fun requestSeek(targetMs: Long) {
        val player = mediaPlayerRef ?: return
        if (!isPrepared) {
            playheadMs = targetMs.coerceIn(0L, totalMs)
            return
        }
        issueSeek(player, targetMs)
    }

    fun startPlaybackFrom(fromMs: Long, singlePassEndAudition: Boolean) {
        val player = mediaPlayerRef ?: return
        if (!isPrepared) return
        stopAtRangeEndOnce = singlePassEndAudition
        val clamped = fromMs.coerceIn(latestStartMs, latestEndMs)
        issueSeek(player, clamped)
        val started = runCatching {
            val vol = if (latestMuteAudio) 0f else 1f
            player.setVolume(vol, vol)
            player.start()
            true
        }.getOrDefault(false)
        isPlaying = started
    }

    // Prepare & cleanly release MediaPlayer per media item
    DisposableEffect(probe.uriString) {
        isPrepared = false
        isPlaying = false
        playerError = null
        isSeeking = false
        pendingSeekTarget = null

        val player = MediaPlayer()
        mediaPlayerRef = player
        var released = false

        player.setOnVideoSizeChangedListener { _, width, height ->
            if (!released && width > 0 && height > 0) {
                videoW = width
                videoH = height
                textureViewRef?.let { tv ->
                    applyFitTransform(tv, tv.width, tv.height, width, height)
                }
            }
        }

        player.setOnSeekCompleteListener { mp ->
            if (released) return@setOnSeekCompleteListener
            isSeeking = false
            val queued = pendingSeekTarget
            if (queued != null) {
                pendingSeekTarget = null
                issueSeek(mp, queued)
            }
        }

        player.setOnPreparedListener { mp ->
            if (released) return@setOnPreparedListener
            isPrepared = true
            val vol = if (latestMuteAudio) 0f else 1f
            runCatching { mp.setVolume(vol, vol) }
            activeSurfaceRef?.let { surf ->
                runCatching { mp.setSurface(surf) }
            }
            if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                videoW = mp.videoWidth
                videoH = mp.videoHeight
                textureViewRef?.let { tv ->
                    applyFitTransform(tv, tv.width, tv.height, mp.videoWidth, mp.videoHeight)
                }
            }
            // Seek immediately to start handle so TextureView paints a real initial frame
            issueSeek(mp, latestStartMs)
        }

        player.setOnCompletionListener { mp ->
            if (released) return@setOnCompletionListener
            if (loopEnabled && !stopAtRangeEndOnce) {
                issueSeek(mp, latestStartMs)
                runCatching { mp.start() }.onFailure { isPlaying = false }
            } else {
                isPlaying = false
                stopAtRangeEndOnce = false
                playheadMs = latestEndMs
            }
        }

        player.setOnErrorListener { _, _, _ ->
            if (!released) {
                isPlaying = false
                isPrepared = false
                playerError = "Preview codec unsupported on device (FFmpeg trim will still work accurately)"
            }
            true
        }

        runCatching {
            player.setDataSource(context, Uri.parse(probe.uriString))
            activeSurfaceRef?.let { surf -> player.setSurface(surf) }
            player.prepareAsync()
        }.onFailure { err ->
            playerError = err.message ?: "Unable to open media for preview"
        }

        onDispose {
            released = true
            mediaPlayerRef = null
            runCatching {
                if (player.isPlaying) player.stop()
            }
            runCatching { player.setSurface(null) }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }

    // Pause playback cleanly if Studio leaves foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val player = mediaPlayerRef
                if (player != null && isPlaying) {
                    runCatching { player.pause() }
                    isPlaying = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Live audio mute toggle
    LaunchedEffect(muteAudio, isPrepared) {
        val player = mediaPlayerRef ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        val vol = if (muteAudio) 0f else 1f
        runCatching { player.setVolume(vol, vol) }
    }

    // Scrub frame when Start handle moves
    var prevObservedStart by remember(probe.uriString) { mutableLongStateOf(safeStartMs) }
    LaunchedEffect(safeStartMs, isPrepared) {
        if (!isPrepared) {
            prevObservedStart = safeStartMs
            return@LaunchedEffect
        }
        if (safeStartMs != prevObservedStart) {
            prevObservedStart = safeStartMs
            if (isPlaying) {
                runCatching { mediaPlayerRef?.pause() }
                isPlaying = false
                stopAtRangeEndOnce = false
            }
            requestSeek(safeStartMs)
        }
    }

    // Scrub frame when End handle moves
    var prevObservedEnd by remember(probe.uriString) { mutableLongStateOf(safeEndMs) }
    LaunchedEffect(safeEndMs, isPrepared) {
        if (!isPrepared) {
            prevObservedEnd = safeEndMs
            return@LaunchedEffect
        }
        if (safeEndMs != prevObservedEnd) {
            prevObservedEnd = safeEndMs
            if (isPlaying) {
                runCatching { mediaPlayerRef?.pause() }
                isPlaying = false
                stopAtRangeEndOnce = false
            }
            requestSeek(safeEndMs)
        }
    }

    // Playhead monitor enforcing exact [trimStartMs .. trimEndMs] boundary
    LaunchedEffect(isPlaying, isPrepared) {
        while (isPlaying && isPrepared) {
            val player = mediaPlayerRef ?: break
            if (!isSeeking) {
                val pos = runCatching { player.currentPosition.toLong() }.getOrDefault(playheadMs)
                playheadMs = pos.coerceIn(0L, totalMs)
                if (pos >= latestEndMs - 20L) {
                    if (loopEnabled && !stopAtRangeEndOnce) {
                        requestSeek(latestStartMs)
                    } else {
                        runCatching { player.pause() }
                        isPlaying = false
                        stopAtRangeEndOnce = false
                        requestSeek(latestEndMs)
                        break
                    }
                }
            }
            delay(50L)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = FurnaceCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ForgeBevelOutline)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (playerError != null) {
                Text(
                    text = playerError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = CoralError
                )
            } else if (isVideo) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 168.dp, max = 230.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(VolcanicObsidian),
                    contentAlignment = Alignment.Center
                ) {
                    key(probe.uriString) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                TextureView(ctx).apply {
                                    textureViewRef = this
                                    isOpaque = true
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(
                                            surfaceTexture: SurfaceTexture,
                                            width: Int,
                                            height: Int
                                        ) {
                                            val surf = Surface(surfaceTexture)
                                            activeSurfaceRef?.release()
                                            activeSurfaceRef = surf
                                            runCatching { mediaPlayerRef?.setSurface(surf) }
                                            applyFitTransform(this@apply, width, height, videoW, videoH)
                                        }

                                        override fun onSurfaceTextureSizeChanged(
                                            surfaceTexture: SurfaceTexture,
                                            width: Int,
                                            height: Int
                                        ) {
                                            applyFitTransform(this@apply, width, height, videoW, videoH)
                                        }

                                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                            runCatching { mediaPlayerRef?.setSurface(null) }
                                            activeSurfaceRef?.release()
                                            activeSurfaceRef = null
                                            return true
                                        }

                                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                                    }
                                }
                            }
                        )
                    }

                    if (!isPrepared) {
                        CircularProgressIndicator(
                            color = EmberFlame,
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp
                        )
                    }

                    // Top-left live playhead pill
                    Surface(
                        color = VolcanicObsidian.copy(alpha = 0.84f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ForgeBevelOutline),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "${formatTrimTime(playheadMs)} / ${formatTrimTime(totalMs)}",
                            style = ForgeReadoutStyle,
                            color = ForgeGold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (muteAudio) {
                        Surface(
                            color = VolcanicObsidian.copy(alpha = 0.84f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, SlateOutline),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                                    contentDescription = "Audio muted",
                                    tint = MutedSteel,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text("Muted", style = MaterialTheme.typography.labelSmall, color = MutedSteel)
                            }
                        }
                    }
                }
            } else {
                // Compact Audio Range Monitor
                Surface(
                    color = VolcanicObsidian,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SlateOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = if (isPlaying) EmberFlame else SecundaSilver,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (isPlaying) "Auditioning Selection" else "Audio Range Preview",
                                style = MaterialTheme.typography.bodySmall,
                                color = CrispWhite
                            )
                        }
                        Text(
                            text = formatTrimTime(playheadMs),
                            style = ForgeReadoutStyle,
                            color = ForgeGold
                        )
                    }
                }
            }

            // Selection playhead bar within [safeStartMs .. safeEndMs]
            val selectionDur = (safeEndMs - safeStartMs).coerceAtLeast(1L)
            val selectionFraction = ((playheadMs - safeStartMs).toFloat() / selectionDur.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { selectionFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = EmberFlame,
                trackColor = SlateOutline
            )

            // Playback controls row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val player = mediaPlayerRef ?: return@OutlinedButton
                        if (!isPrepared) return@OutlinedButton
                        if (isPlaying) {
                            runCatching { player.pause() }
                            isPlaying = false
                            stopAtRangeEndOnce = false
                        } else {
                            val resumeAt = if (playheadMs in safeStartMs until (safeEndMs - 150L)) {
                                playheadMs
                            } else {
                                safeStartMs
                            }
                            startPlaybackFrom(resumeAt, singlePassEndAudition = false)
                        }
                    },
                    enabled = isPrepared && playerError == null,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, EmberFlame.copy(alpha = 0.72f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isPlaying) MoltenEmber.copy(alpha = 0.22f) else VolcanicObsidian,
                        contentColor = EmberFlame
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isPlaying) "Pause" else "Play Range",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = { startPlaybackFrom(safeStartMs, singlePassEndAudition = false) },
                    enabled = isPrepared && playerError == null,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SlateOutline),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.Replay, contentDescription = null, tint = SecundaSilver, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("From Start", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
                }

                OutlinedButton(
                    onClick = {
                        val cueMs = (safeEndMs - 2000L).coerceAtLeast(safeStartMs)
                        startPlaybackFrom(cueMs, singlePassEndAudition = true)
                    },
                    enabled = isPrepared && playerError == null,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SlateOutline),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = SecundaSilver, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Last 2s", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
                }

                FilterChip(
                    selected = loopEnabled,
                    onClick = { loopEnabled = !loopEnabled },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Loop,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                    },
                    label = { Text("Loop") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MoltenEmber.copy(alpha = 0.22f),
                        selectedLabelColor = EmberFlame,
                        selectedLeadingIconColor = EmberFlame
                    )
                )
            }
        }
    }
}

private fun applyFitTransform(
    textureView: TextureView,
    viewW: Int,
    viewH: Int,
    videoW: Int,
    videoH: Int
) {
    if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return
    val viewRatio = viewW.toFloat() / viewH.toFloat()
    val videoRatio = videoW.toFloat() / videoH.toFloat()

    val scaleX: Float
    val scaleY: Float
    if (videoRatio > viewRatio) {
        scaleX = 1f
        scaleY = viewRatio / videoRatio
    } else {
        scaleX = videoRatio / viewRatio
        scaleY = 1f
    }

    val matrix = Matrix().apply {
        setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
    }
    textureView.setTransform(matrix)
}

private fun formatTrimTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    val tenths = (ms % 1000L) / 100L
    return String.format(Locale.US, "%d:%02d.%d", min, sec, tenths)
}
