package com.bunny.app.miniplayer // TODO: sesuaikan package proyek Anda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivityExample : ComponentActivity() {

    // Scope Activity: instance yang sama dipakai UI dan onStop() di bawah.
    private val playbackViewModel: PlaybackViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val navController = rememberNavController()
            val sheetState = playbackViewModel.uiState.sheetState

            // Navigasi MENGIKUTI sheetState (satu sumber kebenaran):
            //  FULL            → buka layar "player"
            //  MINI / HIDDEN   → tutup layar "player" (muncul mini-player bila MINI)
            LaunchedEffect(sheetState) {
                val onPlayer = navController.currentDestination?.route == "player"
                if (sheetState == PlayerSheetState.FULL) {
                    if (!onPlayer) navController.navigate("player") { launchSingleTop = true }
                } else if (onPlayer) {
                    navController.popBackStack()
                }
            }

            // TODO: bungkus dengan tema aplikasi Anda.
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(navController = navController, startDestination = "feed") {
                    composable("feed") {
                        // FeedScreen(
                        //     onVideoClick = { item, title, thumb ->
                        //         playbackViewModel.play(item, title, thumb)
                        //     },
                        // )
                    }
                    composable("player") {
                        PlayerScreen(viewModel = playbackViewModel)
                    }
                }

                // Selalu di atas NavHost → mengambang di atas feed/halaman apa pun
                MiniPlayerOverlay(viewModel = playbackViewModel)
            }
        }
    }

    /**
     * Aplikasi ditinggalkan (home / app switcher / ditutup) → video berhenti dan
     * mini-player ditutup. isChangingConfigurations: jangan ikut tertutup saat rotasi layar.
     * Ingin video tetap jalan di background? Hapus override ini.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) playbackViewModel.onAppExit()
    }
}

/*
 CATATAN INTEGRASI
 ------------------
 1. Tambahkan dependensi: media3-exoplayer, media3-ui, hilt-android (+ hilt-navigation-compose
    bila dipakai), navigation-compose, material3. PlayerModule.kt menyediakan ExoPlayer.

 2. Buat MediaItem SELALU dengan mediaId unik:
      MediaItem.Builder().setUri(url).setMediaId(videoId).build()
    Memutar video lain dari feed saat mini-player aktif → cukup panggil
    playbackViewModel.play(newItem, title, thumb).

 3. Alur: feed → tap video (FULL, buka layar player) → back/panah (MINI, layar tertutup,
    video tetap main) → tap mini (FULL lagi) → swipe/tombol X (HIDDEN, video berhenti)
    → keluar aplikasi (onStop → HIDDEN, video berhenti).
*/
