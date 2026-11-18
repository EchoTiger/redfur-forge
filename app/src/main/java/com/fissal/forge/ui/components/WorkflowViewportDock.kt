package com.fissal.forge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.local.JobStatus
import com.fissal.forge.domain.model.LinkResolveState
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.pipeline.OrchestratorMetrics
import com.fissal.forge.ui.theme.AnvilGunmetal
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.ForgeBevelOutline
import com.fissal.forge.ui.theme.ForgeReadoutStyle
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.RelayBevelOutline
import com.fissal.forge.ui.theme.RelaySurface
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.SlateSurface

/**
 * Collapsible viewport-attached Workflow Engine HUD allowing Echo to inspect live download,
 * extraction, and encode slots or jump straight to the Activity Queue from any screen.
 */
@Composable
fun WorkflowViewportDock(
    resolvedLinks: List<ResolvedLinkChipItem>,
    jobs: List<ForgeJobEntity>,
    metrics: OrchestratorMetrics,
    isUpdatingYtDlp: Boolean,
    onOpenQueueClick: () -> Unit,
    onCancelAll: () -> Unit,
    onRemovePlaylistGroup: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    val resolvingCount = resolvedLinks.count { it.resolveState == LinkResolveState.RESOLVING }
    val resolvedCount = resolvedLinks.count { it.resolveState == LinkResolveState.RESOLVED }
    val activeDownloading = jobs.count { it.statusEnum == JobStatus.DOWNLOADING }
    val activeProcessing = jobs.count { it.statusEnum == JobStatus.PROCESSING }
    val queuedCount = jobs.count { it.statusEnum == JobStatus.QUEUED }
    val completedCount = jobs.count { it.statusEnum == JobStatus.COMPLETED }

    val activeJobs = remember(jobs) { jobs.filter { it.isActive } }
    val isEngineBusy = metrics.totalActiveJobs > 0 || resolvingCount > 0 || isUpdatingYtDlp

    val dockDomain = when {
        activeProcessing > 0 -> EngineDomain.FORGE
        activeDownloading > 0 || resolvingCount > 0 || isUpdatingYtDlp -> EngineDomain.RELAY
        else -> EngineDomain.DELIVERY
    }

    val accentColor = when {
        dockDomain == EngineDomain.FORGE -> EmberFlame
        dockDomain == EngineDomain.RELAY -> ArcRelayCyan
        completedCount > 0 -> EmeraldSuccess
        else -> SecundaSilver
    }

    val containerColor = when (dockDomain) {
        EngineDomain.FORGE -> AnvilGunmetal
        EngineDomain.RELAY -> RelaySurface
        EngineDomain.DELIVERY -> SlateSurface
    }

    val borderColor = when (dockDomain) {
        EngineDomain.FORGE -> ForgeBevelOutline
        EngineDomain.RELAY -> RelayBevelOutline
        EngineDomain.DELIVERY -> SlateOutline
    }

    val summaryText = when {
        metrics.totalActiveJobs > 0 -> buildString {
            val parts = mutableListOf<String>()
            if (activeDownloading > 0) parts += "$activeDownloading downloading"
            if (activeProcessing > 0) parts += "$activeProcessing processing"
            if (queuedCount > 0) parts += "$queuedCount queued"
            if (parts.isEmpty()) parts += "${metrics.totalActiveJobs} active"
            append(parts.joinToString(" • "))
        }
        isUpdatingYtDlp -> "Updating yt-dlp extractors..."
        resolvingCount > 0 -> "Resolving $resolvingCount link${if (resolvingCount == 1) "" else "s"}..."
        jobs.isNotEmpty() -> "Engine Idle • $completedCount/${jobs.size} complete"
        resolvedLinks.isNotEmpty() -> "${resolvedLinks.size} link${if (resolvedLinks.size == 1) "" else "s"} staged"
        else -> "Engine Ready • Standby"
    }

    val playlistSummaries = remember(jobs) {
        jobs.filter { !it.groupId.isNullOrBlank() }
            .groupBy { it.groupId!! }
            .entries
            .map { (groupId, groupJobs) ->
                val done = groupJobs.count { it.statusEnum == JobStatus.COMPLETED }
                val active = groupJobs.count { it.isActive }
                val total = (groupJobs.firstOrNull()?.playlistTotal ?: groupJobs.size).coerceAtLeast(groupJobs.size)
                val progress = if (groupJobs.isEmpty()) 0f else {
                    groupJobs.sumOf { job ->
                        when (job.statusEnum) {
                            JobStatus.COMPLETED -> 1.0
                            else -> (job.progress.toDouble() / 100.0).coerceIn(0.0, 1.0)
                        }
                    }.toFloat() / groupJobs.size.toFloat()
                }
                Triple(
                    groupId,
                    groupJobs.firstOrNull()?.groupTitle ?: "Batch",
                    Triple(done, total, Pair(active, progress))
                )
            }
            .sortedByDescending { it.third.third.first > 0 }
            .take(3)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(
            width = if (isEngineBusy) 1.25.dp else 1.dp,
            color = if (isEngineBusy) accentColor.copy(alpha = 0.65f) else borderColor
        ),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Always-visible compact HUD strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )

                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = CrispWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Compact live slot counters
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SlateCard,
                    border = BorderStroke(1.dp, SlateOutline)
                ) {
                    Text(
                        text = "DL ${metrics.activeNetworkCount}/${metrics.maxNetworkSlots} • FG ${metrics.activeComputeSlotsUsed}/${metrics.maxComputeSlots}",
                        style = ForgeReadoutStyle,
                        color = if (isEngineBusy) accentColor else SecundaSilver,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse workflow engine" else "Expand workflow engine",
                    tint = SecundaSilver,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Hairline activity progress along bottom edge of collapsed bar
            if (isEngineBusy) {
                val hasDeterminateProgress = activeJobs.any { it.progress >= 0f }
                val avgProgress = if (activeJobs.isEmpty()) 0f else {
                    activeJobs.map { it.progress.coerceIn(0f, 100f) / 100f }.average().toFloat()
                }
                if (!hasDeterminateProgress || resolvingCount > 0 || isUpdatingYtDlp) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = accentColor,
                        trackColor = SlateOutline
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { avgProgress.coerceIn(0.02f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = accentColor,
                        trackColor = SlateOutline
                    )
                }
            }

            // Expanded Workflow Engine Drawer
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = SlateOutline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DockStageTile(
                            modifier = Modifier.weight(1f),
                            title = "1. Resolve",
                            detail = when {
                                isUpdatingYtDlp -> "Updating yt-dlp"
                                resolvingCount > 0 -> "$resolvingCount checking"
                                resolvedCount > 0 -> "$resolvedCount ready"
                                else -> "Standby"
                            },
                            fraction = if (resolvedLinks.isEmpty()) 0f else resolvedCount.toFloat() / resolvedLinks.size.toFloat(),
                            indeterminate = resolvingCount > 0 || isUpdatingYtDlp,
                            accent = SecundaSilver
                        )
                        DockStageTile(
                            modifier = Modifier.weight(1f),
                            title = "2. Download",
                            detail = "${metrics.activeNetworkCount} / ${metrics.maxNetworkSlots} active",
                            fraction = if (metrics.maxNetworkSlots <= 0) 0f else
                                (metrics.activeNetworkCount.toFloat() / metrics.maxNetworkSlots.toFloat()).coerceIn(0f, 1f),
                            indeterminate = activeDownloading > 0 && activeJobs.filter { it.statusEnum == JobStatus.DOWNLOADING }.all { it.progress < 0f },
                            accent = ArcRelayCyan
                        )
                        DockStageTile(
                            modifier = Modifier.weight(1f),
                            title = "3. Process",
                            detail = "${metrics.activeComputeSlotsUsed} / ${metrics.maxComputeSlots} active",
                            fraction = if (metrics.maxComputeSlots <= 0) 0f else
                                (metrics.activeComputeSlotsUsed.toFloat() / metrics.maxComputeSlots.toFloat()).coerceIn(0f, 1f),
                            indeterminate = false,
                            accent = MoltenEmber
                        )
                    }

                    if (playlistSummaries.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Playlists & Chapter Batches",
                                style = MaterialTheme.typography.labelSmall,
                                color = SecundaSilver
                            )
                            playlistSummaries.forEach { (groupId, title, counts) ->
                                val (done, total, pair) = counts
                                val (active, progress) = pair
                                val isChapterGroup = groupId.startsWith("ch_")
                                Surface(
                                    color = SlateCard,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, SlateOutline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expanded = false
                                            onOpenQueueClick()
                                        }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(9.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Rounded.QueueMusic,
                                                    contentDescription = null,
                                                    tint = if (isChapterGroup) ArcRelayCyan else MasserCrimson,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = title,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = CrispWhite,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val unitLabel = if (isChapterGroup) "tracks" else "items"
                                                Text(
                                                    text = if (active > 0) "$done/$total • ${(progress * 100).toInt()}%" else "$done/$total $unitLabel",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (done == total && total > 0) EmeraldSuccess else SecundaSilver
                                                )
                                                IconButton(
                                                    onClick = { onRemovePlaylistGroup(groupId) },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Close,
                                                        contentDescription = "Remove batch",
                                                        tint = CoralError,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        }
                                        LinearProgressIndicator(
                                            progress = { progress.coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = if (done == total && total > 0) EmeraldSuccess else if (isChapterGroup) ArcRelayCyan else MasserCrimson,
                                            trackColor = SlateOutline
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                expanded = false
                                onOpenQueueClick()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, SlateOutline),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "View Queue (${jobs.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = SecundaSilver
                            )
                        }

                        if (metrics.totalActiveJobs > 0) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onCancelAll) {
                                Text(
                                    text = "Stop All Tasks",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CoralError
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
private fun DockStageTile(
    modifier: Modifier,
    title: String,
    detail: String,
    fraction: Float,
    indeterminate: Boolean,
    accent: Color
) {
    Surface(
        modifier = modifier,
        color = SlateCard,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, SlateOutline)
    ) {
        Column(
            modifier = Modifier.padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = CrispWhite,
                maxLines = 1
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (fraction > 0f || indeterminate) accent else MutedSteel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (indeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accent,
                    trackColor = SlateOutline
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accent,
                    trackColor = SlateOutline
                )
            }
        }
    }
}
