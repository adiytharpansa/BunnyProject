package com.bunny.app.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Hosts the MediaSession so playback survives the UI: background audio and
 * a media notification with transport controls.
 *
 * The foreground contract is deliberately Media3's standard one — this
 * service plus DefaultMediaNotificationProvider, which performs
 * startForeground itself while a session player is active. Hand-rolling
 * that contract is what crashed CLEAR's player; Fase 2 does not repeat it.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playbackManager: PlaybackManager

    /** Listener log; disimpan supaya bisa dilepas di onDestroy. */
    private val logListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelName(CHANNEL_NAME_RES)
            .build()
        // Bunny's own monochrome icon instead of the Media3 default.
        notificationProvider.setSmallIcon(com.bunny.app.R.drawable.ic_notification)
        setMediaNotificationProvider(notificationProvider)
        // Materialise the session NOW, not on first controller demand:
        // the notification provider can only promote this service to the
        // foreground for a session that exists (field finding 2026-11-10:
        // a purely lazy session meant no notification and no background
        // playback on the device test).
        playbackManager.session()
        playbackManager.player.addListener(logListener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return playbackManager.session()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Standard Media3 behaviour: stop when the app is swiped away and
        // nothing is playing; keep playing otherwise. Since Media3 1.6 the
        // service keeps its foreground state for a while after playback
        // stops, so plain stopSelf() is replaced by
        // pauseAllPlayersAndStopSelf(), which terminates immediately.
        val player = playbackManager.player
        if (!player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        // Pemutar adalah singleton yang hidup lebih lama dari layanan: tanpa
        // ini, tiap layanan baru menambah satu listener lagi dan log ganda.
        playbackManager.player.removeListener(logListener)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "bunny_playback"
        private const val NOTIFICATION_ID = 1001
        private val CHANNEL_NAME_RES = com.bunny.app.R.string.playback_channel_name

        /**
         * Memulai layanan (idealnya saat aplikasi di latar depan). Dari latar,
         * Android bisa menolak dengan IllegalStateException — misalnya penonton
         * menekan Home sebelum pemuatan selesai. Layanan sudah terikat lewat
         * MediaController, jadi kegagalan di sini tidak boleh menjatuhkan pemuatan.
         */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, PlaybackService::class.java))
            } catch (e: IllegalStateException) {
            }
        }
    }
}
