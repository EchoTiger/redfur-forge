package com.fissal.forge.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fissal.forge.domain.logic.EstimatedSizeResult
import com.fissal.forge.ui.theme.AnvilGunmetal
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.ArcRelayDeep
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.EmeraldSuccess
import com.fissal.forge.ui.theme.ForgeBevelOutline
import com.fissal.forge.ui.theme.ForgeGold
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MasserCrimsonDark
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.RelayBevelOutline
import com.fissal.forge.ui.theme.RelayCard
import com.fissal.forge.ui.theme.RelaySurface
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.SlateSurface
import java.util.Locale

/**
 * Dual-engine architectural domains separating local media forging from network relay intake
 * and finished output delivery.
 */
enum class EngineDomain {
    FORGE,
    RELAY,
    DELIVERY
}

@Immutable
private data class DomainVisualSpec(
    val containerColor: Color,
    val innerCardColor: Color,
    val outlineColor: Color,
    val specularColor: Color,
    val pulseStartColor: Color,
    val pulseEndColor: Color,
    val accentColor: Color,
    val pillIcon: ImageVector
)

private fun EngineDomain.visualSpec(): DomainVisualSpec = when (this) {
    EngineDomain.FORGE -> DomainVisualSpec(
        containerColor = AnvilGunmetal,
        innerCardColor = FurnaceCard,
        outlineColor = ForgeBevelOutline,
        specularColor = EmberFlame,
        pulseStartColor = MoltenEmber,
        pulseEndColor = MasserCrimson,
        accentColor = EmberFlame,
        pillIcon = Icons.Rounded.LocalFireDepartment
    )
    EngineDomain.RELAY -> DomainVisualSpec(
        containerColor = RelaySurface,
        innerCardColor = RelayCard,
        outlineColor = RelayBevelOutline,
        specularColor = ArcRelayCyan,
        pulseStartColor = ArcRelayCyan,
        pulseEndColor = ArcRelayDeep,
        accentColor = ArcRelayCyan,
        pillIcon = Icons.Rounded.CloudDownload
    )
    EngineDomain.DELIVERY -> DomainVisualSpec(
        containerColor = SlateSurface,
        innerCardColor = SlateCard,
        outlineColor = SlateOutline,
        specularColor = SecundaSilver,
        pulseStartColor = EmeraldSuccess,
        pulseEndColor = SecundaSilver,
        accentColor = EmeraldSuccess,
        pillIcon = Icons.Rounded.DoneAll
    )
}

private val MoltenAnvilButtonStart = Color(0xFFC43419)

/**
 * Domain-coded surface card with machined top-edge bevel highlight and idle-safe active pulse.
 *
 * When [isActive] is `false`, zero infinite animations are instantiated to avoid idle frame drain.
 */
@Composable
fun WorkshopDomainCard(
    domain: EngineDomain,
    isActive: Boolean = false,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val spec = domain.visualSpec()
    // Only instantiate an infinite transition when the card is actively working.
    val activePulseState = if (isActive) rememberActiveDomainPulse() else null

    val staticBorder = BorderStroke(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                spec.specularColor.copy(alpha = 0.30f),
                spec.outlineColor,
                spec.outlineColor
            )
        )
    )

    val innerModifier = Modifier
        .fillMaxWidth()
        .drawBehind {
            val glazeHeight = (size.height * 0.28f).coerceAtLeast(36f)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        spec.specularColor.copy(alpha = if (isActive) 0.11f else 0.06f),
                        Color.Transparent
                    ),
                    startY = 0f,
                    endY = glazeHeight
                )
            )
        }
        .then(
            if (activePulseState != null) {
                Modifier.drawWithContent {
                    drawContent()
                    val pulseAlpha = activePulseState.value
                    val outline = shape.createOutline(size, layoutDirection, this)
                    drawOutline(
                        outline = outline,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                spec.pulseStartColor.copy(alpha = pulseAlpha),
                                spec.pulseEndColor.copy(alpha = pulseAlpha * 0.85f)
                            )
                        ),
                        style = Stroke(width = 1.75.dp.toPx())
                    )
                }
            } else {
                Modifier
            }
        )
        .padding(contentPadding)

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = spec.containerColor,
            contentColor = CrispWhite,
            border = staticBorder
        ) {
            Column(modifier = innerModifier, content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = spec.containerColor,
            contentColor = CrispWhite,
            border = staticBorder
        ) {
            Column(modifier = innerModifier, content = content)
        }
    }
}

@Composable
private fun rememberActiveDomainPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "workshopDomainPulse")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "domainBorderAlpha"
    )
}

/**
 * Compact uppercase role pill tagging a card or section to its active engine domain.
 */
@Composable
fun DomainRolePill(
    domain: EngineDomain,
    label: String,
    modifier: Modifier = Modifier
) {
    val spec = domain.visualSpec()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = spec.innerCardColor,
        border = BorderStroke(1.dp, spec.accentColor.copy(alpha = 0.42f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = spec.pillIcon,
                contentDescription = null,
                tint = spec.accentColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = spec.accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Molten ember & Masser crimson action button for local Forge encode/transform pipelines.
 * Uses deep ember-to-crimson stops so [CrispWhite] text maintains >= 5.0:1 WCAG AA contrast.
 */
@Composable
fun ForgePrimaryButton(
    onClick: () -> Unit,
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    DomainActionButton(
        onClick = onClick,
        text = text,
        icon = icon,
        enabled = enabled,
        gradientColors = listOf(MoltenAnvilButtonStart, MasserCrimsonDark),
        topHighlightColor = EmberFlame.copy(alpha = 0.80f),
        bottomOutlineColor = MoltenEmber.copy(alpha = 0.45f),
        contentColor = CrispWhite,
        modifier = modifier
    )
}

/**
 * Arc-Relay cyan-to-deep action button for downloader intake, aria2c, and playlist dispatch.
 * Uses [ObsidianBg] text on cyan stops to maintain 4.75:1–9.08:1 WCAG AA contrast.
 */
@Composable
fun RelayPrimaryButton(
    onClick: () -> Unit,
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    DomainActionButton(
        onClick = onClick,
        text = text,
        icon = icon,
        enabled = enabled,
        gradientColors = listOf(ArcRelayCyan, ArcRelayDeep),
        topHighlightColor = CrispWhite.copy(alpha = 0.55f),
        bottomOutlineColor = ArcRelayCyan.copy(alpha = 0.50f),
        contentColor = ObsidianBg,
        modifier = modifier
    )
}

@Composable
private fun DomainActionButton(
    onClick: () -> Unit,
    text: String,
    icon: ImageVector?,
    enabled: Boolean,
    gradientColors: List<Color>,
    topHighlightColor: Color,
    bottomOutlineColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    val resolvedContentColor = if (enabled) contentColor else MutedSteel.copy(alpha = 0.72f)
    val borderStroke = if (enabled) {
        BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(topHighlightColor, bottomOutlineColor))
        )
    } else {
        BorderStroke(1.dp, SlateOutline.copy(alpha = 0.55f))
    }

    val backgroundBrush = if (enabled) {
        Brush.horizontalGradient(gradientColors)
    } else {
        Brush.horizontalGradient(listOf(SlateCard.copy(alpha = 0.7f), SlateCard.copy(alpha = 0.7f)))
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = Color.Transparent,
        contentColor = resolvedContentColor,
        border = borderStroke,
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .semantics { role = Role.Button }
    ) {
        Row(
            modifier = Modifier
                .background(backgroundBrush)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = resolvedContentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = resolvedContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Compact live output size pill displaying [EstimatedSizeResult.formattedWithDelta] and
 * highlighting sample-calibrated estimates when available.
 */
@Composable
fun LiveSizeEstimateBadge(
    estimate: EstimatedSizeResult?,
    domain: EngineDomain = EngineDomain.FORGE,
    prefix: String? = null,
    modifier: Modifier = Modifier
) {
    if (estimate == null) return

    val spec = domain.visualSpec()
    val borderColor = if (estimate.isCalibratedFromSample) {
        ForgeGold.copy(alpha = 0.58f)
    } else {
        spec.outlineColor
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = spec.innerCardColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (estimate.isCalibratedFromSample) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = "Sample calibrated estimate",
                    tint = ForgeGold,
                    modifier = Modifier.size(13.dp)
                )
            }

            val formattedText = buildAnnotatedString {
                if (!prefix.isNullOrBlank()) {
                    withStyle(
                        SpanStyle(
                            color = spec.accentColor,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(prefix.trim())
                        append(" ")
                    }
                }
                withStyle(
                    SpanStyle(
                        color = CrispWhite,
                        fontWeight = FontWeight.SemiBold
                    )
                ) {
                    append(estimate.formattedWithDelta())
                }
            }

            Text(
                text = formattedText,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (estimate.isCalibratedFromSample) {
                Text(
                    text = "SAMPLE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = ForgeGold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
