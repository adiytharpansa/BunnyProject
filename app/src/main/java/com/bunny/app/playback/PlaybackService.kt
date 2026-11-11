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
 * service plus DefaultMediaNotificationProvider (the media3-session 1.4
 * class name; plain DefaultMediaNotification does not exist there), which
 * performs startForeground itself while a session player is active.
 * Hand-rolling that contract is what crashed CLEAR's player; Fase 2 does
 * not repeat it.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playbackManager: PlaybackManager

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
        PlaybackDiagnostics.log("Layanan dibuat; sesi media siap")
        playbackManager.player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                PlaybackDiagnostics.log("Layanan: playWhenReady=$playWhenReady reason=$reason")
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        PlaybackDiagnostics.log("Kontroler terhubung: ${controllerInfo.packageName}")
        return playbackManager.session()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Standard Media3 behaviour: stop when the app is swiped away and
        // nothing is playing; keep playing otherwise. Since Media3 1.6 the
        // service keeps its foreground state for a while after playback
        // stops, so plain stopSelf() is replaced by
        // pauseAllPlayersAndStopSelf(), which terminates immediately.
        val player = playbackManager.player
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    companion object {
        private const val CHANNEL_ID = "bunny_playback"
        private const val NOTIFICATION_ID = 1001
        private val CHANNEL_NAME_RES = com.bunny.app.R.string.playback_channel_name

        /** Starts the service (call while the app is in the foreground). */
        fun start(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java))
        }
    }
}
