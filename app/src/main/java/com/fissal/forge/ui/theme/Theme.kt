package com.fissal.forge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ForgeDarkColorScheme = darkColorScheme(
    primary = MasserCrimson,
    onPrimary = ObsidianBg,
    primaryContainer = MasserCrimsonDark,
    onPrimaryContainer = CrispWhite,
    secondary = ArcRelayCyan,
    onSecondary = ObsidianBg,
    secondaryContainer = RelayCard,
    onSecondaryContainer = CrispWhite,
    tertiary = EmberFlame,
    onTertiary = ObsidianBg,
    tertiaryContainer = FurnaceCard,
    onTertiaryContainer = ForgeGold,
    background = VolcanicObsidian,
    onBackground = CrispWhite,
    surface = AnvilGunmetal,
    onSurface = CrispWhite,
    surfaceVariant = FurnaceCard,
    onSurfaceVariant = SecundaSilver,
    surfaceContainerLowest = VolcanicObsidian,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = AnvilGunmetal,
    surfaceContainerHigh = FurnaceCard,
    surfaceContainerHighest = SlateCard,
    outline = ForgeBevelOutline,
    outlineVariant = SlateOutline,
    error = CoralError,
    onError = ObsidianBg
)

@Composable
fun RedfurForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ForgeDarkColorScheme,
        typography = ForgeTypography,
        content = content
    )
}
