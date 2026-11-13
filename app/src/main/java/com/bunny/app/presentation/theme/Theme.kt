package com.bunny.app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Satu skema TERANG untuk seluruh aplikasi (perintah pengguna
// 2026-11-13: mode gelap dihapus). Semua peran yang disentuh UI
// dipaku ke sistem netral + oranye; peran yang tidak dipaku akan
// diam-diam mengimpor palet bawaan Material (ungu/lavender) —
// benturan yang pernah difoto pemilik pada 2026-11-10.
private val LightScheme = lightColorScheme(
    primary = BunnyOrange,
    onPrimary = BunnyOnOrange,
    primaryContainer = BunnyOrangeContainerLight,
    onPrimaryContainer = BunnyOnOrangeContainerLight,
    secondary = BunnyOnNeutralContainerLight,
    onSecondary = BunnyOnOrange,
    secondaryContainer = BunnyNeutralContainerLight,
    onSecondaryContainer = BunnyOnNeutralContainerLight,
    tertiary = BunnyOrange,
    onTertiary = BunnyOnOrange,
    tertiaryContainer = BunnyOrangeContainerLight,
    onTertiaryContainer = BunnyOnOrangeContainerLight,
    background = BunnyBackgroundLight,
    onBackground = BunnyOnBackgroundLight,
    surface = BunnySurfaceLight,
    onSurface = BunnyOnBackgroundLight,
    surfaceVariant = BunnySurfaceVariantLight,
    onSurfaceVariant = BunnyOnSurfaceVariantLight,
    outline = BunnyOutlineLight,
    outlineVariant = BunnyOutlineLight,
)

@Composable
fun BunnyTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightScheme,
        typography = BunnyTypography,
        content = content,
    )
}
