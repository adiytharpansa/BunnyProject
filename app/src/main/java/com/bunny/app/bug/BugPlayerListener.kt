package com.bunny.app.bug

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/** Hanya mencatat jejak. Tidak memunculkan dialog; error sementara ditangani logika fallback sumber. */
class BugPlayerListener(private val label: String = "player") : Player.Listener {

    override fun onPlaybackStateChanged(playbackState: Int) {
        val s = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "?"
        }
        BugRecorder.note(label, "state=$s")
    }

    override fun onPlayerError(error: PlaybackException) {
        BugRecorder.note(label, "error ${error.errorCodeName}: ${error.message}")
    }
}
