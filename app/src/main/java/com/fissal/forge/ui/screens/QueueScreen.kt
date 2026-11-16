package com.fissal.forge.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.local.JobLane
import com.fissal.forge.data.local.JobStatus
import com.fissal.forge.pipeline.OrchestratorMetrics
import com.fissal.forge.ui.components.DomainRolePill
import com.fissal.forge.ui.components.EngineDomain
import com.fissal.forge.ui.components.WorkshopDomainCard
import com.fissal.forge.ui.theme.AmberActive
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.SlateSurface
import com.fissal.forge.ui.theme.VolcanicObsidian
import java.util.Locale

private enum class QueueFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    DONE("Done"),
    FAILED("Failed")
}

private sealed interface QueueEntry {
    data class PlaylistGroup(
        val groupId: String,
        val title: String,
        val thumbnailUrl: String?,
        val allGroupJobs: List<ForgeJobEntity>,
        val visibleTracks: List<ForgeJobEntity>
    ) : QueueEntry

    data class SingleJob(val job: ForgeJobEntity) : QueueEntry
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueueScreen(
    jobs: List<ForgeJobEntity>,
    metrics: OrchestratorMetrics,
    onCopyJob: (String) -> Unit,
    onShareJob: (ForgeJobEntity) -> Intent?,
    onCancelJob: (String) -> Unit,
    onRetryJob: (String) -> Unit,
    onRemoveJob: (String) -> Unit,
    onCancelPlaylistGroup: (String) -> Unit,
    onRemovePlaylistGroup: (String) -> Unit,
    onRetryPlaylistGroup: (String) -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit
) {
    var activeFilter by rememberSaveable { mutableStateOf(QueueFilter.ALL) }
    val context = LocalContext.current

    val filteredJobs = remember(jobs, activeFilter) {
        when (activeFilter) {
            QueueFilter.ALL -> jobs
            QueueFilter.ACTIVE -> jobs.filter { it.isActive }
            QueueFilter.DONE -> jobs.filter { it.statusEnum == JobStatus.COMPLETED }
            QueueFilter.FAILED -> jobs.filter {
                it.statusEnum == JobStatus.FAILED || it.statusEnum == JobStatus.CANCELED
            }
        }
    }

    val groupedEntries = remember(jobs, filteredJobs) {
        val allByGroup = jobs.filter { !it.groupId.isNullOrBlank() }.groupBy { it.groupId!! }
        val filteredByGroup = filteredJobs.filter { !it.groupId.isNullOrBlank() }.groupBy { it.groupId!! }
        val seenGroups = HashSet<String>()
        buildList {
            filteredJobs.forEach { job ->
                val gid = job.groupId
                if (gid.isNullOrBlank()) {
                    add(QueueEntry.SingleJob(job))
                } else if (seenGroups.add(gid)) {
                    val allTracks = (allByGroup[gid] ?: listOf(job))
                        .sortedWith(compareBy<ForgeJobEntity> { it.playlistIndex ?: Int.MAX_VALUE }.thenBy { it.createdAtMs })
                    val visibleTracks = (filteredByGroup[gid] ?: emptyList())
                        .sortedWith(compareBy<ForgeJobEntity> { it.playlistIndex ?: Int.MAX_VALUE }.thenBy { it.createdAtMs })
                    add(
                        QueueEntry.PlaylistGroup(
                            groupId = gid,
                            title = allTracks.firstNotNullOfOrNull { it.groupTitle } ?: "Playlist",
                            thumbnailUrl = allTracks.firstNotNullOfOrNull { it.thumbnailUrl },
                            allGroupJobs = allTracks,
                            visibleTracks = visibleTracks
                        )
                    )
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(VolcanicObsidian),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "queue_header") {
            val headerDomain = when {
                metrics.activeComputeSlotsUsed > 0 -> EngineDomain.FORGE
                metrics.activeNetworkCount > 0 -> EngineDomain.RELAY
                else -> EngineDomain.DELIVERY
            }
            WorkshopDomainCard(
                domain = headerDomain,
                isActive = metrics.totalActiveJobs > 0,
                contentPadding = PaddingValues(14.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${metrics.totalActiveJobs} active • ${jobs.size} total",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CrispWhite,
                                    fontWeight = FontWeight.SemiBold
                                )
                                DomainRolePill(
                                    domain = headerDomain,
                                    label = when (headerDomain) {
                                        EngineDomain.FORGE -> "Forging"
                                        EngineDomain.RELAY -> "Relaying"
                                        EngineDomain.DELIVERY -> "Standby"
                                    }
                                )
                            }
                            Text(
                                text = "Relay ${metrics.activeNetworkCount}/${metrics.maxNetworkSlots} • Forge ${metrics.activeComputeSlotsUsed}/${metrics.maxComputeSlots} RAM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSteel
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (metrics.totalActiveJobs > 0) {
                                OutlinedButton(
                                    onClick = onCancelAll,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    border = BorderStroke(1.dp, CoralError.copy(alpha = 0.6f))
                                ) {
                                    Text("Stop All", style = MaterialTheme.typography.labelMedium, color = CoralError)
                                }
                            }
                            IconButton(onClick = onClearFinished) {
                                Icon(
                                    Icons.Rounded.DeleteSweep,
                                    contentDescription = "Clear finished",
                                    tint = SecundaSilver
                                )
                            }
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QueueFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = activeFilter == filter,
                                onClick = { activeFilter = filter },
                                label = { Text(filter.label) }
                            )
                        }
                    }
                }
            }
        }

        if (groupedEntries.isEmpty()) {
            item(key = "queue_empty") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, SlateOutline),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (jobs.isEmpty()) {
                                "Queue is clear. Paste links on the Forge tab or share media files into Redfur Forge."
                            } else {
                                "No jobs match the '${activeFilter.label}' filter."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedSteel
                        )
                    }
                }
            }
        } else {
            items(
                items = groupedEntries,
                key = { entry ->
                    when (entry) {
                        is QueueEntry.PlaylistGroup -> "pl_${entry.groupId}"
                        is QueueEntry.SingleJob -> "job_${entry.job.id}"
                    }
                }
            ) { entry ->
                when (entry) {
                    is QueueEntry.PlaylistGroup -> PlaylistGroupCard(
                        group = entry,
                        onCopyTrack = onCopyJob,
                        onShareTrack = { job ->
                            onShareJob(job)?.let { intent ->
                                context.startActivity(Intent.createChooser(intent, "Share ${job.title}"))
                            }
                        },
                        onCancelTrack = onCancelJob,
                        onRetryTrack = onRetryJob,
                        onRemoveTrack = onRemoveJob,
                        onCancelPlaylist = { onCancelPlaylistGroup(entry.groupId) },
                        onRetryPlaylist = { onRetryPlaylistGroup(entry.groupId) },
                        onRemovePlaylist = { onRemovePlaylistGroup(entry.groupId) }
                    )
                    is QueueEntry.SingleJob -> StandaloneJobCard(
                        job = entry.job,
                        onCopy = { onCopyJob(entry.job.id) },
                        onShare = {
                            onShareJob(entry.job)?.let { intent ->
                                context.startActivity(Intent.createChooser(intent, "Share ${entry.job.title}"))
                            }
                        },
                        onCancel = { onCancelJob(entry.job.id) },
                        onRetry = { onRetryJob(entry.job.id) },
                        onRemove = { onRemoveJob(entry.job.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistGroupCard(
    group: QueueEntry.PlaylistGroup,
    onCopyTrack: (String) -> Unit,
    onShareTrack: (ForgeJobEntity) -> Unit,
    onCancelTrack: (String) -> Unit,
    onRetryTrack: (String) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onCancelPlaylist: () -> Unit,
    onRetryPlaylist: () -> Unit,
    onRemovePlaylist: () -> Unit
) {
    val totalCount = group.allGroupJobs.size
    val completedCount = group.allGroupJobs.count { it.statusEnum == JobStatus.COMPLETED }
    val activeCount = group.allGroupJobs.count { it.isActive }
    val failedCount = group.allGroupJobs.count { it.statusEnum == JobStatus.FAILED }
    val canceledCount = group.allGroupJobs.count { it.statusEnum == JobStatus.CANCELED }

    var expanded by rememberSaveable(group.groupId) {
        mutableStateOf(activeCount > 0 || failedCount > 0 || totalCount <= 15)
    }

    val aggregateProgress = if (totalCount == 0) 0f else {
        group.allGroupJobs.sumOf { job ->
            when (job.statusEnum) {
                JobStatus.COMPLETED -> 1.0
                else -> (job.progress.toDouble() / 100.0).coerceIn(0.0, 1.0)
            }
        }.toFloat() / totalCount.toFloat()
    }

    val totalInBytes = group.allGroupJobs.sumOf { it.inputBytes }
    val totalOutBytes = group.allGroupJobs.sumOf { it.outputBytes }
    val isChapterGroup = group.groupId.startsWith("ch_")
    val groupKindLabel = if (isChapterGroup) "Chapters" else "Playlist"
    val groupDomain = when {
        group.allGroupJobs.any { it.statusEnum == JobStatus.PROCESSING } -> EngineDomain.FORGE
        activeCount > 0 -> EngineDomain.RELAY
        completedCount == totalCount && totalCount > 0 -> EngineDomain.DELIVERY
        else -> EngineDomain.RELAY
    }

    WorkshopDomainCard(
        domain = groupDomain,
        isActive = activeCount > 0,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Playlist / Chapter Group Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = SlateCard,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SlateOutline),
                    modifier = Modifier.size(width = 56.dp, height = 44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (!group.thumbnailUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = group.thumbnailUrl,
                                contentDescription = group.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = MasserCrimson,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("$groupKindLabel • $completedCount/$totalCount done")
                            if (activeCount > 0) append(" • $activeCount active")
                            if (failedCount > 0) append(" • $failedCount failed")
                            if (totalOutBytes > 0L) append(" • ${formatQueueBytes(totalOutBytes)}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            groupDomain == EngineDomain.FORGE && activeCount > 0 -> EmberFlame
                            activeCount > 0 -> ArcRelayCyan
                            failedCount > 0 -> CoralError
                            completedCount == totalCount && totalCount > 0 -> EmeraldSuccess
                            else -> SecundaSilver
                        }
                    )
                }

                DomainRolePill(
                    domain = groupDomain,
                    label = when (groupDomain) {
                        EngineDomain.FORGE -> "Forge"
                        EngineDomain.RELAY -> "Relay"
                        EngineDomain.DELIVERY -> "Ready"
                    }
                )

                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Expand tracks",
                        tint = SecundaSilver
                    )
                }
            }

            LinearProgressIndicator(
                progress = { aggregateProgress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = when {
                    failedCount > 0 && activeCount == 0 -> CoralError
                    completedCount == totalCount && totalCount > 0 -> EmeraldSuccess
                    groupDomain == EngineDomain.FORGE -> MoltenEmber
                    else -> ArcRelayCyan
                },
                trackColor = SlateOutline
            )

            // Group-wide controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeCount > 0) {
                    OutlinedButton(
                        onClick = onCancelPlaylist,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, SlateOutline)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = AmberActive)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isChapterGroup) "Cancel Chapters" else "Cancel Playlist",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberActive
                        )
                    }
                }
                if (failedCount > 0 || canceledCount > 0) {
                    OutlinedButton(
                        onClick = onRetryPlaylist,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, SecundaSilver.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = SecundaSilver)
                        Spacer(Modifier.width(4.dp))
                        Text("Retry Failed", style = MaterialTheme.typography.labelSmall, color = SecundaSilver)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onRemovePlaylist,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = CoralError)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isChapterGroup) "Remove Chapters" else "Remove Playlist",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (totalInBytes > 0L && totalOutBytes > 0L && totalOutBytes < totalInBytes) {
                val savedPct = (((totalInBytes - totalOutBytes).toDouble() / totalInBytes.toDouble()) * 100.0).toInt()
                Text(
                    text = "Batch savings: ${formatQueueBytes(totalInBytes)} -> ${formatQueueBytes(totalOutBytes)} (-$savedPct%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldSuccess
                )
            }

            // Expanded Per-Song Rows
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = SlateOutline)
                    group.visibleTracks.forEach { trackJob ->
                        PlaylistTrackJobRow(
                            job = trackJob,
                            onCopy = { onCopyTrack(trackJob.id) },
                            onShare = { onShareTrack(trackJob) },
                            onCancel = { onCancelTrack(trackJob.id) },
                            onRetry = { onRetryTrack(trackJob.id) },
                            onRemove = { onRemoveTrack(trackJob.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackJobRow(
    job: ForgeJobEntity,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val jobDomain = domainForJob(job)
    val statusColor = jobStatusColor(job.statusEnum, jobDomain)
    Surface(
        color = SlateCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SlateOutline)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = SlateSurface,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "#${job.playlistIndex ?: "-"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SecundaSilver,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = CrispWhite,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(job.statusEnum.name, statusColor)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = buildString {
                        append(job.statusText)
                        if (job.outputBytes > 0L) {
                            append(" (${formatQueueBytes(job.outputBytes)})")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (job.statusEnum == JobStatus.FAILED) CoralError else MutedSteel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (job.statusEnum) {
                        JobStatus.COMPLETED -> {
                            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = CrispWhite, modifier = Modifier.size(15.dp))
                            }
                            IconButton(onClick = onShare, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Share, contentDescription = "Share", tint = SecundaSilver, modifier = Modifier.size(15.dp))
                            }
                        }
                        JobStatus.QUEUED, JobStatus.DOWNLOADING, JobStatus.PROCESSING -> {
                            IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "Cancel track", tint = AmberActive, modifier = Modifier.size(15.dp))
                            }
                        }
                        JobStatus.FAILED, JobStatus.CANCELED -> {
                            IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Retry track", tint = SecundaSilver, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove track", tint = MutedSteel, modifier = Modifier.size(15.dp))
                    }
                }
            }

            if (job.isActive) {
                LinearProgressIndicator(
                    progress = { (job.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = statusColor,
                    trackColor = SlateOutline
                )
            }
        }
    }
}

@Composable
private fun StandaloneJobCard(
    job: ForgeJobEntity,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val jobDomain = domainForJob(job)
    val statusColor = jobStatusColor(job.statusEnum, jobDomain)
    WorkshopDomainCard(
        domain = jobDomain,
        isActive = job.isActive,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!job.thumbnailUrl.isNullOrBlank()) {
                    Surface(
                        color = SlateCard,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(width = 48.dp, height = 36.dp)
                    ) {
                        AsyncImage(
                            model = job.thumbnailUrl,
                            contentDescription = job.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = CrispWhite,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${job.mediaKindEnum.name} • ${job.sourceLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSteel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DomainRolePill(
                        domain = jobDomain,
                        label = when (jobDomain) {
                            EngineDomain.RELAY -> "Relay"
                            EngineDomain.FORGE -> "Forge"
                            EngineDomain.DELIVERY -> "Ready"
                        }
                    )
                    StatusPill(job.statusEnum.name, statusColor)
                }
            }

            Text(
                text = job.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (job.statusEnum == JobStatus.FAILED) CoralError else SecundaSilver,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (job.isActive) {
                LinearProgressIndicator(
                    progress = { (job.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = statusColor,
                    trackColor = SlateOutline
                )
            }

            if (job.inputBytes > 0L && job.outputBytes > 0L) {
                val savings = job.savingsPercent?.let { " (-$it%)" }.orEmpty()
                Text(
                    text = "${formatQueueBytes(job.inputBytes)} -> ${formatQueueBytes(job.outputBytes)}$savings",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldSuccess
                )
            }

            if (job.statusEnum == JobStatus.FAILED && !job.errorMessage.isNullOrBlank()) {
                Text(
                    text = job.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = CoralError,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (job.statusEnum) {
                    JobStatus.COMPLETED -> {
                        OutlinedButton(
                            onClick = onCopy,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, SlateOutline)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = CrispWhite)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy", style = MaterialTheme.typography.labelMedium, color = CrispWhite)
                        }
                        OutlinedButton(
                            onClick = onShare,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, SlateOutline)
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = SecundaSilver)
                            Spacer(Modifier.width(6.dp))
                            Text("Share", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
                        }
                    }
                    JobStatus.QUEUED, JobStatus.DOWNLOADING, JobStatus.PROCESSING -> {
                        OutlinedButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, SlateOutline)
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = AmberActive)
                            Spacer(Modifier.width(6.dp))
                            Text("Cancel", style = MaterialTheme.typography.labelMedium, color = AmberActive)
                        }
                    }
                    JobStatus.FAILED, JobStatus.CANCELED -> {
                        OutlinedButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, SlateOutline)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = SecundaSilver)
                            Spacer(Modifier.width(6.dp))
                            Text("Retry", style = MaterialTheme.typography.labelMedium, color = SecundaSilver)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove job",
                        tint = MutedSteel,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun domainForJob(job: ForgeJobEntity): EngineDomain = when {
    job.statusEnum == JobStatus.COMPLETED -> EngineDomain.DELIVERY
    job.statusEnum == JobStatus.DOWNLOADING -> EngineDomain.RELAY
    job.statusEnum == JobStatus.PROCESSING -> EngineDomain.FORGE
    job.lane == JobLane.NETWORK.name -> EngineDomain.RELAY
    else -> EngineDomain.FORGE
}

private fun jobStatusColor(status: JobStatus, domain: EngineDomain): Color = when (status) {
    JobStatus.COMPLETED -> EmeraldSuccess
    JobStatus.DOWNLOADING -> ArcRelayCyan
    JobStatus.PROCESSING -> MoltenEmber
    JobStatus.QUEUED -> if (domain == EngineDomain.RELAY) ArcRelayCyan else SecundaSilver
    JobStatus.FAILED, JobStatus.CANCELED -> CoralError
}

private fun formatQueueBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
