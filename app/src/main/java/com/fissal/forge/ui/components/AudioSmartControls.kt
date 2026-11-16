package com.fissal.forge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.SettingTopic
import com.fissal.forge.domain.model.SponsorBlockCategory
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.RelayCard
import com.fissal.forge.ui.theme.SecundaSilver

private val SILENCE_THRESHOLD_PRESETS = listOf(
    -45 to "Gentle (-45 dB)",
    -38 to "Standard (-38 dB)",
    -30 to "Tight (-30 dB)"
)

private val SILENCE_MIN_DURATION_PRESETS = listOf(
    400 to "0.4s",
    600 to "0.6s",
    1000 to "1.0s"
)

/**
 * Reusable multi-category SponsorBlock selector paired with [ForgeInfoTipButton].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SponsorBlockCategorySelector(
    title: String = "SponsorBlock Skip Categories",
    selectedCategories: Set<SponsorBlockCategory>,
    capabilityReport: DeviceCapabilityReport?,
    onToggleCategory: (SponsorBlockCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = SecundaSilver
                )
                Text(
                    text = if (selectedCategories.isEmpty()) {
                        "Off (tap categories to skip during download)"
                    } else {
                        "${selectedCategories.size} active: ${selectedCategories.joinToString(", ") { it.label }}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectedCategories.isEmpty()) MutedSteel else ArcRelayCyan
                )
            }
            ForgeInfoTipButton(
                topic = SettingTopic.SPONSOR_BLOCK,
                capabilityReport = capabilityReport
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SponsorBlockCategory.entries.forEach { category ->
                val isSelected = category in selectedCategories
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggleCategory(category) },
                    label = { Text(category.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = RelayCard,
                        selectedLabelColor = ArcRelayCyan
                    )
                )
            }
        }
    }
}

/**
 * Reusable FFmpeg Dead-Air / Silence Skipping switch with threshold and pause duration presets
 * paired with [ForgeInfoTipButton].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SilenceSkipControlPanel(
    enabled: Boolean,
    thresholdDb: Int,
    minSilenceMs: Int,
    subtitle: String,
    audioAvailable: Boolean = true,
    capabilityReport: DeviceCapabilityReport?,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectThresholdDb: (Int) -> Unit,
    onSelectMinSilenceMs: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dead-Air / Silence Skip",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (audioAvailable) CrispWhite else MutedSteel
                )
                Text(
                    text = if (audioAvailable) {
                        subtitle
                    } else {
                        "Unavailable when output has no audio track (GIF / Muted)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSteel
                )
            }
            ForgeInfoTipButton(
                topic = SettingTopic.DEAD_AIR_SKIP,
                capabilityReport = capabilityReport
            )
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = enabled && audioAvailable,
                enabled = audioAvailable,
                onCheckedChange = onToggleEnabled
            )
        }

        if (enabled && audioAvailable) {
            Text(
                text = "Silence Threshold",
                style = MaterialTheme.typography.labelSmall,
                color = SecundaSilver
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SILENCE_THRESHOLD_PRESETS.forEach { (db, label) ->
                    FilterChip(
                        selected = thresholdDb == db,
                        onClick = { onSelectThresholdDb(db) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MoltenEmber.copy(alpha = 0.22f),
                            selectedLabelColor = EmberFlame
                        )
                    )
                }
            }

            Text(
                text = "Minimum Pause Duration",
                style = MaterialTheme.typography.labelSmall,
                color = SecundaSilver
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SILENCE_MIN_DURATION_PRESETS.forEach { (ms, label) ->
                    FilterChip(
                        selected = minSilenceMs == ms,
                        onClick = { onSelectMinSilenceMs(ms) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MoltenEmber.copy(alpha = 0.22f),
                            selectedLabelColor = EmberFlame
                        )
                    )
                }
            }
        }
    }
}
