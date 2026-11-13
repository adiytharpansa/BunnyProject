package com.bunny.app.presentation.theme

import androidx.compose.ui.graphics.Color

// Palet diambil dari ikon aplikasi (diukur dari piksel ikon):
//   oranye telinga/hidung  #E76E25  -> aksen utama
//   cokelat telinga        #8F481C  -> turunan container aksen
//   wajah krem keputihan   #D7D8D2  -> turunan permukaan terang
// Tata letak tetap ala YouTube; hanya warnanya yang mengikuti ikon.
// Satu tema terang saja — mode gelap dihapus 2026-11-13.
val BunnyOrange = Color(0xFFE76E25)
val BunnyOrangeContainerLight = Color(0xFFFFDCC7)
val BunnyOnOrangeContainerLight = Color(0xFF4A2005)


val BunnyBackgroundLight = Color(0xFFFFFAF6)
val BunnyOnBackgroundLight = Color(0xFF2D1A0D)
// Permukaan kartu putih murni (modernisasi 2026-11-13): kartu terpisah
// jelas dari latar krem; peran warna lain tetap turunan ikon.
val BunnySurfaceLight = Color(0xFFFFFFFF)
val BunnySurfaceVariantLight = Color(0xFFF1E2D6)
val BunnyOnSurfaceVariantLight = Color(0xFF6E5646)
val BunnyNeutralContainerLight = Color(0xFFF1E2D6)
val BunnyOnNeutralContainerLight = Color(0xFF2D1A0D)
val BunnyOutlineLight = Color(0xFFD9C4B4)
