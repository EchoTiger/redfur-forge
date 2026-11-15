package com.fissal.forge.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fissal.forge.data.local.ForgeJobEntity
import com.fissal.forge.data.local.JobStatus
import com.fissal.forge.pipeline.OrchestratorMetrics
import com.fissal.forge.ui.theme.AmberActive
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

private enum class QueueFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    DONE("Completed"),
    FAILED("Failed")
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
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(QueueFilter.ALL) }

    val filteredJobs = remember(jobs, filter) {
        when (filter) {
            QueueFilter.ALL -> jobs
            QueueFilter.ACTIVE -> jobs.filter { it.isActive }
            QueueFilter.DONE -> jobs.filter { it.statusEnum == JobStatus.COMPLETED }
            QueueFilter.FAILED -> jobs.filter {
                it.statusEnum == JobStatus.FAILED || it.statusEnum == JobStatus.CANCELED
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Dual-Lane Utilization Telemetry Bar
        Surface(
            color = SlateSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SlateOutline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Dual-Lane Orchestrator", style = MaterialTheme.typography.labelMedium, color = CrispWhite)
                    Text(
                        text = "Network ${metrics.activeNetworkCount}/${metrics.maxNetworkSlots} • Compute ${metrics.activeComputeSlotsUsed}/${metrics.maxComputeSlots} RAM slots",
                        style = MaterialTheme.typography.bodySmall,
                        color = SecundaSilver
                    )
                }
                Row {
                    if (metrics.totalActiveJobs > 0) {
                        TextButton(onClick = onCancelAll) {
                            Text("Cancel All", color = CoralError)
                        }
                    } else if (jobs.any { !it.isActive }) {
                        IconButton(onClick = onClearFinished) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear finished", tint = SecundaSilver)
                        }
                    }
                }
            }
        }

        // Filter Chips
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueueFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) }
                )
            }
        }

        if (filteredJobs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No jobs in this view yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedSteel
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredJobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        onCopy = { onCopyJob(job.id) },
                        onShare = {
                            val intent = onShareJob(job)
                            if (intent != null) {
                                context.startActivity(Intent.createChooser(intent, "Share processed media"))
                            }
                        },
                        onCancel = { onCancelJob(job.id) },
                        onRetry = { onRetryJob(job.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: ForgeJobEntity,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val status = job.statusEnum
    val accent = when (status) {
        JobStatus.COMPLETED -> EmeraldSuccess
        JobStatus.FAILED, JobStatus.CANCELED -> CoralError
        JobStatus.DOWNLOADING, JobStatus.PROCESSING -> AmberActive
        JobStatus.QUEUED -> SecundaSilver
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SlateOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = CrispWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = job.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSteel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = job.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = SecundaSilver
            )

            if (job.isActive) {
                LinearProgressIndicator(
                    progress = { (job.progress / 100f).coerceIn(0f, 1f) },
                    color = MasserCrimson,
                    trackColor = SlateCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }

            if (job.inputBytes > 0L && job.outputBytes > 0L) {
                val savings = job.savingsPercent?.let { " (-$it%)" } ?: ""
                Text(
                    text = "${formatBytes(job.inputBytes)} → ${formatBytes(job.outputBytes)}$savings",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldSuccess
                )
            }

            if (!job.errorMessage.isNullOrBlank()) {
                Text(
                    text = job.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = CoralError,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (status) {
                    JobStatus.COMPLETED -> {
                        OutlinedButton(
                            onClick = onCopy,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        OutlinedButton(
                            onClick = onShare,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                    JobStatus.FAILED, JobStatus.CANCELED -> {
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}
