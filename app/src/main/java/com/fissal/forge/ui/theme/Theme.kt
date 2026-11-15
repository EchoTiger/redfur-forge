package com.fissal.forge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ForgeDarkColorScheme = darkColorScheme(
    primary = MasserCrimson,
    onPrimary = CrispWhite,
    primaryContainer = MasserCrimsonDark,
    onPrimaryContainer = CrispWhite,
    secondary = SecundaSilver,
    onSecondary = ObsidianBg,
    secondaryContainer = SlateCard,
    onSecondaryContainer = CrispWhite,
    tertiary = EmeraldSuccess,
    background = ObsidianBg,
    onBackground = CrispWhite,
    surface = SlateSurface,
    onSurface = CrispWhite,
    surfaceVariant = SlateCard,
    onSurfaceVariant = MutedSteel,
    outline = SlateOutline,
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
