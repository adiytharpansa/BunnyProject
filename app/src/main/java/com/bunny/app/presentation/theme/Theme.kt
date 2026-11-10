package com.bunny.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = BunnyOrange,
    background = BunnyBackgroundDark,
    surface = BunnySurfaceDark,
)

private val LightScheme = lightColorScheme(
    primary = BunnyOrange,
    background = BunnyBackgroundLight,
    surface = BunnySurfaceLight,
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
