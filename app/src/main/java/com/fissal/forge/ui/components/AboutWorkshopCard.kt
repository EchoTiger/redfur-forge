package com.fissal.forge.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fissal.forge.BuildConfig
import com.fissal.forge.R
import com.fissal.forge.ui.theme.ArcRelayCyan
import com.fissal.forge.ui.theme.BrassCap
import com.fissal.forge.ui.theme.CrispWhite
import com.fissal.forge.ui.theme.EmberFlame
import com.fissal.forge.ui.theme.ForgeBevelOutline
import com.fissal.forge.ui.theme.ForgeGold
import com.fissal.forge.ui.theme.ForgeReadoutStyle
import com.fissal.forge.ui.theme.FurnaceCard
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.MoltenEmber
import com.fissal.forge.ui.theme.MutedSteel
import com.fissal.forge.ui.theme.NixieAmber
import com.fissal.forge.ui.theme.NixieFilament
import com.fissal.forge.ui.theme.NixieGlassBg
import com.fissal.forge.ui.theme.RelayBevelOutline
import com.fissal.forge.ui.theme.RelayCard
import com.fissal.forge.ui.theme.SecundaSilver
import com.fissal.forge.ui.theme.SlateCard
import com.fissal.forge.ui.theme.SlateOutline
import com.fissal.forge.ui.theme.VolcanicObsidian

private const val GITHUB_REPO_URL = "https://github.com/EchoTiger/redfur-forge"
private const val DISCORD_HANDLE = "@echotiger"

/**
 * Workshop identity & attribution card featuring the Forge Fissal medallion, version pill,
 * Echo & Fissal credits, hardware summary, interactive links, and the Nixie vacuum-tube stamp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutWorkshopCard(
    appVersionName: String = BuildConfig.VERSION_NAME,
    appVersionCode: Int = BuildConfig.VERSION_CODE,
    capabilitySummary: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    WorkshopDomainCard(
        domain = EngineDomain.FORGE,
        isActive = false,
        modifier = modifier,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Emblem medallion + Title + Version pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier.size(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .drawBehind {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            MoltenEmber.copy(alpha = 0.34f),
                                            MasserCrimson.copy(alpha = 0.14f),
                                            Color.Transparent
                                        )
                                    )
                                )
                            }
                    )
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape,
                        color = VolcanicObsidian,
                        border = BorderStroke(
                            1.5.dp,
                            Brush.verticalGradient(
                                listOf(ForgeGold.copy(alpha = 0.75f), BrassCap, ForgeBevelOutline)
                            )
                        )
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_forge_fissal_emblem),
                            contentDescription = "Forge Fissal clockwork emblem",
                            modifier = Modifier
                                .size(52.dp)
                                .padding(2.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Redfur Forge",
                            style = MaterialTheme.typography.titleLarge,
                            color = CrispWhite,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        DomainRolePill(
                            domain = EngineDomain.FORGE,
                            label = "Workshop",
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = FurnaceCard,
                        border = BorderStroke(1.dp, ForgeBevelOutline)
                    ) {
                        Text(
                            text = "v$appVersionName • build $appVersionCode",
                            style = MaterialTheme.typography.labelSmall,
                            color = SecundaSilver,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Text(
                text = "Sovereign local media forging & relay intake — built for sharp compression, RAW film alchemy, and zero cloud rent.",
                style = MaterialTheme.typography.bodySmall,
                color = SecundaSilver
            )

            // Workshop Crew Credits
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CrewCreditBadge(
                    name = "Echo",
                    role = "Workshop Architect",
                    accentColor = ArcRelayCyan,
                    containerColor = RelayCard,
                    borderColor = RelayBevelOutline,
                    modifier = Modifier.weight(1f)
                )
                CrewCreditBadge(
                    name = "Fissal",
                    role = "Clockwork Alfiq Co-Pilot",
                    accentColor = EmberFlame,
                    containerColor = FurnaceCard,
                    borderColor = ForgeBevelOutline,
                    modifier = Modifier.weight(1f)
                )
            }

            // Optional Host Hardware Summary
            if (!capabilitySummary.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = VolcanicObsidian,
                    border = BorderStroke(1.dp, SlateOutline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = ArcRelayCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = capabilitySummary,
                            style = ForgeReadoutStyle,
                            color = SecundaSilver,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Interactive Community & Source Pills
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WorkshopLinkPill(
                    label = "GitHub Repository",
                    leadingIcon = Icons.Rounded.Code,
                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                    accentColor = ArcRelayCyan,
                    containerColor = RelayCard,
                    borderColor = RelayBevelOutline,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
                            )
                        }.onFailure {
                            Toast.makeText(
                                context,
                                "No browser available to open GitHub link",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )

                WorkshopLinkPill(
                    label = "Discord $DISCORD_HANDLE",
                    leadingIcon = Icons.Rounded.ContentCopy,
                    trailingIcon = null,
                    accentColor = ForgeGold,
                    containerColor = FurnaceCard,
                    borderColor = ForgeBevelOutline,
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Discord", DISCORD_HANDLE))
                        Toast.makeText(
                            context,
                            "Copied $DISCORD_HANDLE to clipboard",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }

            // Vacuum-Fuse Nixie Workshop Stamp
            NixieWorkshopStamp(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CrewCreditBadge(
    name: String,
    role: String,
    accentColor: Color,
    containerColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = CrispWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = role,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WorkshopLinkPill(
    label: String,
    leadingIcon: ImageVector,
    trailingIcon: ImageVector?,
    accentColor: Color,
    containerColor: Color,
    borderColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = CrispWhite,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = CrispWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (trailingIcon != null) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = MutedSteel,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * Custom Canvas vacuum-fuse Nixie tube signature stamp with brass end-caps, cathode wire mesh,
 * glass glare reflection, and draw-phase flickering amber filament bloom (zero recompositions).
 */
@Composable
fun NixieWorkshopStamp(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "nixieFilamentTransition")
    // Kept as State<Float> and read strictly inside graphicsLayer/drawBehind to avoid recomposition.
    val filamentFlickerState = infiniteTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "nixieGlowFlicker"
    )

    val brassDark = Color(0xFF75431C)
    val brassRim = Color(0xFF472911)
    val tubeShape = RoundedCornerShape(10.dp)
    val meshColor = Color(0xFF4D2D16)

    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(SlateCard.copy(alpha = 0.35f))
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NixieBrassEndCap(
            brassDark = brassDark,
            brassRim = brassRim,
            isLeft = true
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(tubeShape)
                .background(NixieGlassBg)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.24f),
                            BrassCap.copy(alpha = 0.45f),
                            Color(0xFF3A2212)
                        )
                    ),
                    shape = tubeShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Cathode wire mesh grid & specular glass glare
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokePx = 0.75.dp.toPx()
                val step = 9.dp.toPx()
                var x = -size.height
                while (x < size.width + size.height) {
                    drawLine(
                        color = meshColor.copy(alpha = 0.36f),
                        start = Offset(x, 0f),
                        end = Offset(x + size.height * 0.65f, size.height),
                        strokeWidth = strokePx
                    )
                    drawLine(
                        color = meshColor.copy(alpha = 0.28f),
                        start = Offset(x + size.height * 0.65f, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = strokePx
                    )
                    x += step
                }

                // Subtle horizontal hex cathode wire bars
                val rowStep = 8.dp.toPx()
                var y = rowStep * 0.6f
                while (y < size.height) {
                    drawLine(
                        color = meshColor.copy(alpha = 0.24f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokePx * 0.8f
                    )
                    y += rowStep
                }

                // Curved top specular glass glare streak
                val glarePath = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height * 0.24f)
                    cubicTo(
                        size.width * 0.68f,
                        size.height * 0.36f,
                        size.width * 0.30f,
                        size.height * 0.38f,
                        0f,
                        size.height * 0.30f
                    )
                    close()
                }
                drawPath(
                    path = glarePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.White.copy(alpha = 0.02f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = size.height * 0.44f
                    )
                )
            }

            // Draw-phase flickering glass filament bloom
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = filamentFlickerState.value
                    }
                    .drawBehind {
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    NixieAmber.copy(alpha = 0.28f),
                                    NixieFilament.copy(alpha = 0.11f),
                                    Color.Transparent
                                ),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = (size.width * 0.56f).coerceAtLeast(1f)
                            ),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                            size = Size(size.width, size.height)
                        )
                    }
            )

            Text(
                text = "CRAFTED BY FISSAL & ECHO — WORKSHOP STAMP",
                style = ForgeReadoutStyle.copy(
                    color = NixieFilament,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 0.8.sp,
                    shadow = Shadow(
                        color = NixieAmber,
                        offset = Offset.Zero,
                        blurRadius = 14f
                    )
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp)
            )
        }

        NixieBrassEndCap(
            brassDark = brassDark,
            brassRim = brassRim,
            isLeft = false
        )
    }
}

@Composable
private fun NixieBrassEndCap(
    brassDark: Color,
    brassRim: Color,
    isLeft: Boolean
) {
    val capShape = if (isLeft) {
        RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 2.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    }

    Box(
        modifier = Modifier
            .width(12.dp)
            .fillMaxHeight()
            .padding(vertical = 2.dp)
            .clip(capShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(ForgeGold.copy(alpha = 0.82f), BrassCap, brassDark)
                )
            )
            .border(1.dp, brassRim, capShape)
            .drawBehind {
                val centerX = size.width / 2f
                drawCircle(
                    color = brassRim.copy(alpha = 0.75f),
                    radius = 1.6.dp.toPx(),
                    center = Offset(centerX, size.height * 0.28f)
                )
                drawCircle(
                    color = brassRim.copy(alpha = 0.75f),
                    radius = 1.6.dp.toPx(),
                    center = Offset(centerX, size.height * 0.72f)
                )
            }
    )
}
