package com.bunny.app.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.bunny.app.R

/**
 * Mengelola Picture-in-Picture ala YouTube:
 *  - auto-enter saat pengguna menekan Home / gesture ke layar utama (Android 12+ via setAutoEnterEnabled,
 *    Android 8–11 via onUserLeaveHint)
 *  - rasio aspek 16:9 (atau mengikuti video bila lockTo16x9 = false), dengan sourceRectHint
 *    agar animasi masuk/keluar mulus
 *  - 3 RemoteAction: Mundur 10 dtk, Putar/Jeda, Maju 10 dtk
 *
 * Catatan: pastikan ExoPlayer dibuat dengan
 *   .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
 * agar seekBack()/seekForward() = 10 detik.
 *
 * Kabel Bunny (paket pip_24, 2026-11-13): controller ini dipasang
 * pada MainActivity dengan player milik PlaybackManager — BUKAN
 * activity pemutar tersendiri. Satu penyesuaian dari paket asal:
 * gerbang [enabled] (eligibilitas PiP milik MainActivity: video
 * sedang terbuka penuh), supaya auto-enter tidak menyala saat
 * hanya audio latar yang berjalan. sourceView diisi null —
 * PlayerView tinggal di dalam PlayerScreen dan tidak terjangkau
 * dari activity; sourceRectHint memang opsional.
 */
class PipController(
    private val activity: Activity,
    private val player: () -> Player?,
    private val sourceView: () -> View?,
    private val lockTo16x9: Boolean = true,
    /** Gerbang eligibilitas pemilik (lihat catatan kabel di atas). */
    private val enabled: () -> Boolean = { true },
) {

    private var attachedPlayer: Player? = null
    private var receiverRegistered = false

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    // ---- Listener player: setiap perubahan state memperbarui params PiP ----
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateParams()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateParams()
        override fun onPlaybackStateChanged(playbackState: Int) = updateParams()
        override fun onVideoSizeChanged(videoSize: VideoSize) = updateParams()
    }

    // ---- Receiver untuk tombol di jendela PiP ----
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val p = player() ?: return
            when (intent?.action) {
                ACTION_REWIND -> p.seekBack()
                ACTION_FORWARD -> p.seekForward()
                ACTION_TOGGLE -> {
                    if (p.isPlaying) {
                        p.pause()
                    } else {
                        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                        p.play()
                    }
                }
            }
            updateParams()
        }
    }

    /** Panggil di onStart (setelah player dibuat). */
    fun attach() {
        if (!isSupported) return
        attachedPlayer = player()?.also { it.addListener(playerListener) }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_REWIND)
                addAction(ACTION_TOGGLE)
                addAction(ACTION_FORWARD)
            }
            ContextCompat.registerReceiver(
                activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        updateParams()
    }

    /** Panggil di onStop (sebelum player dilepas). */
    fun detach() {
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = null
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver)
            receiverRegistered = false
        }
    }

    /** Panggil dari onUserLeaveHint(). Di Android 12+ sistem yang melakukan auto-enter. */
    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && shouldAutoEnter()) enter()
    }

    /** Masuk PiP manual (mis. dari tombol "PiP" di UI). */
    fun enter() {
        if (!isSupported || !enabled() ||
            activity.isInPictureInPictureMode
        ) {
            return
        }
        try {
            activity.enterPictureInPictureMode(buildParams())
        } catch (_: IllegalStateException) {
            // Activity belum mendukung PiP / sedang transisi
        }
    }

    /** Perbarui rasio, aksi, dan flag auto-enter. Aman dipanggil kapan saja. */
    fun updateParams() {
        if (!isSupported) return
        try {
            activity.setPictureInPictureParams(buildParams())
        } catch (_: IllegalStateException) {
        }
    }

    // ------------------------------------------------------------------

    private fun shouldAutoEnter(): Boolean {
        if (!enabled()) return false
        val p = player() ?: return false
        return p.playWhenReady &&
            p.playbackState != Player.STATE_IDLE &&
            p.playbackState != Player.STATE_ENDED
    }

    private fun buildParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(currentAspectRatio())
            .setActions(buildActions())

        sourceRect()?.let { builder.setSourceRectHint(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Auto-enter hanya aktif saat video sedang diputar (perilaku YouTube)
            builder.setAutoEnterEnabled(shouldAutoEnter())
            // false = cross-fade halus untuk konten video (true cocok untuk konten non-video)
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    private fun currentAspectRatio(): Rational {
        if (lockTo16x9) return RATIO_16_9
        val size = player()?.videoSize ?: return RATIO_16_9
        if (size.width <= 0 || size.height <= 0) return RATIO_16_9
        val w = (size.width * size.pixelWidthHeightRatio * 1000).toInt()
        val h = size.height * 1000
        val ratio = w.toFloat() / h
        // Batas yang diizinkan Android: 1:2.39 .. 2.39:1
        return when {
            ratio > MAX_RATIO -> Rational(239, 100)
            ratio < MIN_RATIO -> Rational(100, 239)
            else -> Rational(w, h)
        }
    }

    private fun sourceRect(): Rect? {
        val view = sourceView() ?: return null
        val rect = Rect()
        return if (view.getGlobalVisibleRect(rect) && !rect.isEmpty) rect else null
    }

    private fun buildActions(): List<RemoteAction> {
        val playing = player()?.isPlaying == true
        return listOf(
            action(R.drawable.ic_pip_rewind, "Mundur 10 detik", ACTION_REWIND, REQ_REWIND),
            if (playing) {
                action(R.drawable.ic_pip_pause, "Jeda", ACTION_TOGGLE, REQ_TOGGLE)
            } else {
                action(R.drawable.ic_pip_play, "Putar", ACTION_TOGGLE, REQ_TOGGLE)
            },
            action(R.drawable.ic_pip_forward, "Maju 10 detik", ACTION_FORWARD, REQ_FORWARD),
        )
    }

    private fun action(
        @DrawableRes icon: Int,
        title: String,
        actionName: String,
        requestCode: Int,
    ): RemoteAction {
        val intent = Intent(actionName).setPackage(activity.packageName)
        val pending = PendingIntent.getBroadcast(
            activity, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return RemoteAction(Icon.createWithResource(activity, icon), title, title, pending)
    }

    companion object {
        private const val ACTION_REWIND = "com.bunny.app.pip.REWIND"
        private const val ACTION_TOGGLE = "com.bunny.app.pip.TOGGLE"
        private const val ACTION_FORWARD = "com.bunny.app.pip.FORWARD"
        private const val REQ_REWIND = 1
        private const val REQ_TOGGLE = 2
        private const val REQ_FORWARD = 3
        private val RATIO_16_9 = Rational(16, 9)
        private const val MAX_RATIO = 2.39f
        private const val MIN_RATIO = 1f / 2.39f
    }
}
