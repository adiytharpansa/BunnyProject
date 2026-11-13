package com.bunny.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Complete schemes: every role the UI touches is pinned to the neutral
// + orange system. Leaving a role unpinned silently imports the
// Material baseline palette (purple/lavender containers), which is
// exactly the clash the owner photographed on 2026-11-10.
private val DarkScheme = darkColorScheme(
    primary = BunnyOrange,
    onPrimary = BunnyOnOrange,
    primaryContainer = BunnyOrangeContainerDark,
    onPrimaryContainer = BunnyOnOrangeContainerDark,
    secondary = BunnyOnNeutralContainerDark,
    onSecondary = BunnyBackgroundDark,
    secondaryContainer = BunnyNeutralContainerDark,
    onSecondaryContainer = BunnyOnNeutralContainerDark,
    tertiary = BunnyOrange,
    onTertiary = BunnyOnOrange,
    tertiaryContainer = BunnyOrangeContainerDark,
    onTertiaryContainer = BunnyOnOrangeContainerDark,
    background = BunnyBackgroundDark,
    onBackground = BunnyOnBackgroundDark,
    surface = BunnySurfaceDark,
    onSurface = BunnyOnBackgroundDark,
    surfaceVariant = BunnySurfaceVariantDark,
    onSurfaceVariant = BunnyOnSurfaceVariantDark,
    outline = BunnyOutlineDark,
    outlineVariant = BunnyNeutralContainerDark,
)

private val LightScheme = lightColorScheme(
    primary = BunnyOrange,
    onPrimary = BunnyBackgroundLight,
    primaryContainer = BunnyOrangeContainerLight,
    onPrimaryContainer = BunnyOnOrangeContainerLight,
    secondary = BunnyOnNeutralContainerLight,
    onSecondary = BunnyBackgroundLight,
    secondaryContainer = BunnyNeutralContainerLight,
    onSecondaryContainer = BunnyOnNeutralContainerLight,
    tertiary = BunnyOrange,
    onTertiary = BunnyBackgroundLight,
    tertiaryContainer = BunnyOrangeContainerLight,
    onTertiaryContainer = BunnyOnOrangeContainerLight,
    background = BunnyBackgroundLight,
    onBackground = BunnyOnBackgroundLight,
    surface = BunnySurfaceLight,
    onSurface = BunnyOnBackgroundLight,
    surfaceVariant = BunnySurfaceVariantLight,
    onSurfaceVariant = BunnyOnSurfaceVariantLight,
    outline = BunnyOutlineLight,
    outlineVariant = BunnyNeutralContainerLight,
)

@Composable
fun BunnyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = BunnyTypography,
        content = content,
    )
}
