package com.bunny.app.presentation.theme

import androidx.compose.ui.graphics.Color

// Bunny brand: near-black neutral surfaces (YouTube-like) + signature
// orange accent. EVERY Material role is pinned in Theme.kt from these
// constants — an unpinned role falls back to the Material baseline
// (purple containers), which clashed with the brand (found on device
// 2026-11-10: purple avatar tiles, lavender selected chip).
val BunnyOrange = Color(0xFFFC6401)
val BunnyOnOrange = Color(0xFF2B1600)
val BunnyOrangeContainerDark = Color(0xFF4A2703)
val BunnyOnOrangeContainerDark = Color(0xFFFFDCC7)
val BunnyOrangeContainerLight = Color(0xFFFFE3D3)
val BunnyOnOrangeContainerLight = Color(0xFF4A2703)

val BunnyBackgroundDark = Color(0xFF0F0F0F)
val BunnyOnBackgroundDark = Color(0xFFF1F1F1)
val BunnySurfaceDark = Color(0xFF212121)
val BunnySurfaceVariantDark = Color(0xFF2A2A2A)
val BunnyOnSurfaceVariantDark = Color(0xFFAAAAAA)
val BunnyNeutralContainerDark = Color(0xFF2C2C2C)
val BunnyOnNeutralContainerDark = Color(0xFFE6E1D8)
val BunnyOutlineDark = Color(0xFF3D3D3D)

val BunnyBackgroundLight = Color(0xFFFFFFFF)
val BunnyOnBackgroundLight = Color(0xFF0F0F0F)
val BunnySurfaceLight = Color(0xFFF2F2F2)
val BunnySurfaceVariantLight = Color(0xFFE7E4DF)
val BunnyOnSurfaceVariantLight = Color(0xFF5A564F)
val BunnyNeutralContainerLight = Color(0xFFE8E2D9)
val BunnyOnNeutralContainerLight = Color(0xFF3A352E)
val BunnyOutlineLight = Color(0xFFC9C4BC)
