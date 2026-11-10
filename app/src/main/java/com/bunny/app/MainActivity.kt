package com.bunny.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bunny.app.presentation.screens.home.HomeScreen
import com.bunny.app.presentation.screens.player.PlayerScreen
import com.bunny.app.presentation.theme.BunnyTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.media3.common.util.UnstableApi

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BunnyTheme {
                // Fase 2 navigation is deliberately a single state: home, or
                // one open video. A nav graph earns its place in Fase 3 when
                // Terbit and channel pages exist.
                var openVideoId by remember { mutableStateOf<String?>(null) }
                val videoId = openVideoId
                if (videoId == null) {
                    HomeScreen(onVideoClick = { openVideoId = it })
                } else {
                    PlayerScreen(
                        videoId = videoId,
                        onBack = { openVideoId = null },
                    )
                }
            }
        }
    }
}
