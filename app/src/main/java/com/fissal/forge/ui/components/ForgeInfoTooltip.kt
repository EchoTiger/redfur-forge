package com.fissal.forge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fissal.forge.domain.logic.AdvisorySeverity
import com.fissal.forge.domain.logic.DeviceAdvisory
import com.fissal.forge.domain.logic.DeviceCapabilityReport
import com.fissal.forge.domain.logic.SettingTopic
import com.fissal.forge.ui.theme.AmberActive
import com.fissal.forge.ui.theme.AnvilGunmetal
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.CoralError
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.ForgeBevelOutline
import com.fissal.forge.ui.theme.ForgeGold
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.RelayBevelOutline
import com.fissal.forge.ui.theme.RelayCard
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline

/**
 * Plain-English guide describing a user-facing control, its safe default, and practical trade-offs.
 */
@Immutable
data class SettingDescriptor(
    val topic: SettingTopic,
    val title: String,
    val whatItDoes: String,
    val recommendedDefault: String,
    val tradeOffs: String
)

private data class TopicPresentation(
    val descriptor: SettingDescriptor,
    val domain: EngineDomain,
    val domainLabel: String
)

private fun presentationFor(topic: SettingTopic): TopicPresentation = when (topic) {
    SettingTopic.CRF_SLIDER -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Quality Balance (CRF)",
            whatItDoes = "Controls how sharply the video keeps fine details versus shrinking the file. Lower numbers preserve more detail; higher numbers compress more aggressively.",
            recommendedDefault = "22–24 for H.264 / HEVC, or 28–32 for AV1.",
            tradeOffs = "Raising CRF shrinks file size fast with minimal processing time, while pushing past 30 can soften textures and dark gradients."
        ),
        domain = EngineDomain.FORGE,
        domainLabel = "Local Forge"
    )
    SettingTopic.TARGET_FILESIZE -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Target Filesize Budget",
            whatItDoes = "Automatically calculates a custom bitrate ceiling so your finished clip lands safely under an exact megabyte cap (like Discord, messaging apps, or email attachments).",
            recommendedDefault = "Use 9.5 MB for 10 MB upload limits or 24 MB for 25 MB caps; leave off for general quality-based compression.",
            tradeOffs = "Guarantees predictable upload sizes, though squeezing long clips into tiny limits will noticeably lower sharpness."
        ),
        domain = EngineDomain.FORGE,
        domainLabel = "Local Forge"
    )
    SettingTopic.VIDEO_CODEC -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Video Format (Codec)",
            whatItDoes = "Selects the compression engine used to pack video frames: H.264 for universal playback, HEVC (H.265) for smaller modern files, or AV1 for maximum shrinkage.",
            recommendedDefault = "HEVC for modern Android/iOS sharing; H.264 when older TVs or consoles must play the file.",
            tradeOffs = "AV1 achieves the smallest files for a given clarity, but runs heavily on the CPU on devices without hardware AV1 encoding."
        ),
        domain = EngineDomain.FORGE,
        domainLabel = "Local Forge"
    )
    SettingTopic.TWO_PASS_ENCODING -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Two-Pass Precision",
            whatItDoes = "Scans the whole video first to spot high-motion scenes, then encodes a second pass that spends extra data where motion is complex and saves space on still scenes.",
            recommendedDefault = "On when targeting a strict megabyte cap; Off for quick everyday compression.",
            tradeOffs = "Delivers noticeably cleaner detail at strict target filesizes, at roughly double the encoding time and temporary staging storage."
        ),
        domain = EngineDomain.FORGE,
        domainLabel = "Local Forge"
    )
    SettingTopic.FAST_KEYFRAME_TRIM -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Fast Keyframe Copy Cut",
            whatItDoes = "Slices out your selected start-to-end range by copying existing video data directly instead of decoding and re-encoding every frame.",
            recommendedDefault = "On for instant trimming with zero generational quality loss.",
            tradeOffs = "Finishes in seconds and preserves original sharpness, though cut points snap to the nearest ~1–2 second keyframe instead of an exact frame."
        ),
        domain = EngineDomain.FORGE,
        domainLabel = "Local Forge"
    )
    SettingTopic.ARIA2C_ACCELERATOR -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Multi-Stream Accelerator (aria2c)",
            whatItDoes = "Splits eligible downloads across multiple simultaneous network connections so single-stream server throttling doesn't bottleneck your Wi-Fi speed.",
            recommendedDefault = "On for fast Wi-Fi transfers and large video files.",
            tradeOffs = "Boosts download throughput on many CDNs while using slightly more socket memory; a few strict hosts may prefer standard single-stream fetching."
        ),
        domain = EngineDomain.RELAY,
        domainLabel = "Relay Intake"
    )
    SettingTopic.RAW_HALF_SIZE_FAST -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Fast Half-Size RAW Demosaic",
            whatItDoes = "Reads 2x2 pixel clusters from camera RAW files (DNG, CR2, NEF, ARW) directly into a clean half-resolution image (for example, 60MP becomes 15MP) without heavy interpolation.",
            recommendedDefault = "On for 50MP+ sensors or devices under 8 GB RAM; Off when archiving full sensor pixels.",
            tradeOffs = "About 4x faster with a fraction of the RAM footprint and cleaner low-light noise, while reducing maximum crop zoom pixels."
        ),
        domain = EngineDomain.FORGE,
        domainLabel = "Local Forge"
    )
    SettingTopic.STRIP_METADATA -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Strip Metadata (Privacy Clean)",
            whatItDoes = "Scrubs hidden EXIF and container tags such as GPS coordinates, camera serial numbers, lens notes, and capture timestamps from exported files.",
            recommendedDefault = "On whenever sharing photos or clips online or in group chats.",
            tradeOffs = "Protects location and device privacy with zero visual or audio quality impact, while omitting camera EXIF details for personal photo libraries."
        ),
        domain = EngineDomain.DELIVERY,
        domainLabel = "Privacy & Output"
    )
    SettingTopic.SPONSOR_BLOCK -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "SponsorBlock Segment Skip",
            whatItDoes = "Checks community-submitted timestamps during Relay downloads and lets you pick exact categories to remove automatically: Sponsors, Self-Promo, Interaction Reminders, Intros, Endcards / Credits, Previews / Recaps, and Non-Music Sections.",
            recommendedDefault = "Enable Sponsors (plus Self-Promo or Interaction Reminders) for podcasts, essays, and commentary videos; select Non-Music Sections for music videos.",
            tradeOffs = "Keeps downloaded videos concise without manual editing, though brand-new uploads may lack community timestamps yet or trim a beat early near chapter transitions."
        ),
        domain = EngineDomain.RELAY,
        domainLabel = "Relay Intake"
    )
    SettingTopic.DEAD_AIR_SKIP -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Trim Dead Air / Silence",
            whatItDoes = "Detects silent pauses using threshold presets (Gentle -45dB, Standard -38dB, Tight -30dB) and a minimum pause duration (0.4s–1.0s), tightening gaps while keeping ~80ms speech-tail padding so words aren't clipped and automatically keeping video frames and audio synchronized on video clips.",
            recommendedDefault = "Standard (-38dB) with a 0.6s minimum pause for lectures, voice memos, and spoken podcasts; Off for music and cinematic clips.",
            tradeOffs = "Makes spoken recordings snappier with zero A/V drift, while aggressive Tight (-30dB) presets can trim soft breaths, musical fade-outs, or intentional quiet pauses."
        ),
        domain = EngineDomain.FORGE,
        domainLabel = "Local Forge"
    )
    SettingTopic.CONCURRENCY_SLOTS -> TopicPresentation(
        descriptor = SettingDescriptor(
            topic = topic,
            title = "Parallel Queue Slots",
            whatItDoes = "Controls how many downloads and heavy local FFmpeg or RAW conversions run side-by-side in the background.",
            recommendedDefault = "Follow your device's Auto hardware advisory (typically 2–3 compute slots).",
            tradeOffs = "More parallel slots finish mixed batches sooner, while pushing beyond your RAM or thermal ceiling can slow individual encodes or heat the device."
        ),
        domain = EngineDomain.DELIVERY,
        domainLabel = "Workshop Scheduler"
    )
}

/**
 * Accessible `(?)` helper button opening a plain-English bottom sheet with setting defaults,
 * trade-offs, and live [DeviceCapabilityReport] advisories for [topic].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgeInfoTipButton(
    topic: SettingTopic,
    capabilityReport: DeviceCapabilityReport? = null,
    modifier: Modifier = Modifier
) {
    val presentation = remember(topic) { presentationFor(topic) }
    val descriptor = presentation.descriptor
    val advisory = remember(capabilityReport, topic) { capabilityReport?.advisoryFor(topic) }
    var showSheet by remember { mutableStateOf(false) }

    val iconTint = when (presentation.domain) {
        EngineDomain.RELAY -> ArcRelayCyan
        EngineDomain.FORGE -> EmberFlame
        EngineDomain.DELIVERY -> SecundaSilver
    }

    IconButton(
        onClick = { showSheet = true },
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
            contentDescription = "Explain ${descriptor.title}",
            tint = iconTint.copy(alpha = 0.88f),
            modifier = Modifier.size(18.dp)
        )
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = AnvilGunmetal,
            contentColor = CrispWhite
        ) {
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
                        DomainRolePill(
                            domain = presentation.domain,
                            label = presentation.domainLabel
                        )
                        Text(
                            text = descriptor.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = CrispWhite
                        )
                    }
                    IconButton(onClick = { showSheet = false }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close guide",
                            tint = SecundaSilver
                        )
                    }
                }

                SettingInfoBlock(
                    label = "WHAT IT DOES",
                    body = descriptor.whatItDoes,
                    accentColor = iconTint,
                    containerColor = if (presentation.domain == EngineDomain.RELAY) RelayCard else FurnaceCard,
                    borderColor = if (presentation.domain == EngineDomain.RELAY) RelayBevelOutline else ForgeBevelOutline
                )

                SettingInfoBlock(
                    label = "RECOMMENDED DEFAULT",
                    body = descriptor.recommendedDefault,
                    accentColor = EmeraldSuccess,
                    containerColor = SlateCard,
                    borderColor = EmeraldSuccess.copy(alpha = 0.35f)
                )

                SettingInfoBlock(
                    label = "TRADE-OFFS",
                    body = descriptor.tradeOffs,
                    accentColor = SecundaSilver,
                    containerColor = SlateCard,
                    borderColor = SlateOutline
                )

                if (advisory != null) {
                    HostAdvisoryCard(advisory = advisory)
                }

                Spacer(Modifier.height(4.dp))

                if (presentation.domain == EngineDomain.RELAY) {
                    RelayPrimaryButton(
                        onClick = { showSheet = false },
                        text = "Got It",
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    ForgePrimaryButton(
                        onClick = { showSheet = false },
                        text = "Got It",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingInfoBlock(
    label: String,
    body: String,
    accentColor: Color,
    containerColor: Color,
    borderColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
                color = accentColor
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = CrispWhite
            )
        }
    }
}

private data class AdvisoryVisualStyle(
    val accent: Color,
    val container: Color,
    val border: Color,
    val icon: ImageVector
)

private val WarningContainer = Color(0xFF281318)

private fun AdvisorySeverity.visualStyle(): AdvisoryVisualStyle = when (this) {
    AdvisorySeverity.INFO -> AdvisoryVisualStyle(
        accent = ArcRelayCyan,
        container = RelayCard,
        border = ArcRelayCyan.copy(alpha = 0.45f),
        icon = Icons.Rounded.Info
    )
    AdvisorySeverity.CAUTION -> AdvisoryVisualStyle(
        accent = AmberActive,
        container = FurnaceCard,
        border = ForgeGold.copy(alpha = 0.50f),
        icon = Icons.Rounded.WarningAmber
    )
    AdvisorySeverity.WARNING -> AdvisoryVisualStyle(
        accent = CoralError,
        container = WarningContainer,
        border = MasserCrimson.copy(alpha = 0.65f),
        icon = Icons.Rounded.ErrorOutline
    )
}

@Composable
private fun HostAdvisoryCard(advisory: DeviceAdvisory) {
    val style = advisory.severity.visualStyle()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = style.container,
        border = BorderStroke(1.dp, style.border)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = style.accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "HOST DEVICE ADVISORY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                        color = style.accent
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = style.accent.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, style.accent.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = advisory.shortBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = style.accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = advisory.message,
                style = MaterialTheme.typography.bodySmall,
                color = CrispWhite
            )
        }
    }
}
