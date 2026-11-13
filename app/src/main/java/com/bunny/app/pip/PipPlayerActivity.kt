package com.bunny.app.pip // TODO: sesuaikan dengan package proyek Anda

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Dependensi (build.gradle.kts :app):
 *   implementation("androidx.media3:media3-exoplayer:1.4.1")
 *   implementation("androidx.media3:media3-ui:1.4.1")
 *   implementation("androidx.activity:activity-compose:1.9.3")
 *
 * Di proyek Bunny, ganti sumber `MediaItem` dengan stream dari Lontar (DASH/HLS/progresif)
 * dan pindahkan inisialisasi player ke kode pemutaran yang sudah ada — PipController hanya
 * butuh akses ke `Player` dan `View` pemutar.
 */
class PipPlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var exoPlayerState by mutableStateOf<ExoPlayer?>(null)
    private var isInPip by mutableStateOf(false)

    private val pip by lazy {
        PipController(
            activity = this,
            player = { player },
            sourceView = { playerView as View? },
            lockTo16x9 = true,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.TopCenter,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            playerView = this
                        }
                    },
                    update = { view ->
                        view.player = exoPlayerState
                        view.useController = !isInPip // sembunyikan kontrol bawaan saat PiP
                    },
                    // Mode PiP: penuhi jendela. Mode normal: 16:9 di atas.
                    modifier = if (isInPip) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
        pip.attach()
    }

    override fun onStop() {
        super.onStop()
        // onStop saat isInPictureInPictureMode == true berarti jendela PiP ditutup (tombol X)
        // atau layar dikunci. Lepas player agar suara berhenti.
        // Jika Anda memakai MediaSessionService untuk pemutaran latar, hapus pelepasan ini.
        pip.detach()
        releasePlayer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        player?.apply {
            setMediaItem(MediaItem.fromUri(videoUrl()))
            prepare()
            play()
        }
    }

    /** Android 8–11: masuk PiP saat pengguna menekan Home. (Android 12+ ditangani auto-enter.) */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pip.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }

    private fun initPlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also {
                it.setMediaItem(MediaItem.fromUri(videoUrl()))
                it.prepare()
                it.playWhenReady = true
            }
        exoPlayerState = player
    }

    private fun releasePlayer() {
        exoPlayerState = null
        player?.release()
        player = null
    }

    private fun videoUrl(): String =
        intent.getStringExtra(EXTRA_VIDEO_URL) ?: SAMPLE_URL

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        private const val SAMPLE_URL =
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"
    }
}
