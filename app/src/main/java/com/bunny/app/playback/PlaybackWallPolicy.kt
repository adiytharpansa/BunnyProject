package com.bunny.app.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

/**
 * The recovery decisions behind Arus Fase A3, kept pure so they can
 * be unit-tested away from the player: WHEN a classic failure counts
 * as the token wall, and WHEN a dead chain earns an automatic SABR
 * attempt.
 */
@UnstableApi
object PlaybackWallPolicy {

    /**
     * The mid-stream token wall's signature (device evidence
     * 2026-11-11): playback had genuinely started (position past 0),
     * then the server refused the media bytes with an HTTP error.
     * Every remaining classic source was minted by the same player
     * response and shares the fate, so the chain skips them instead
     * of dying three times in a row on a black screen. Load-time
     * refusals (position still 0) do NOT match: the chain walks its
     * sources normally, and only the terminal failure recovers.
     */
    fun isWallSignature(isArusChain: Boolean, errorCode: Int, positionMs: Long): Boolean =
        !isArusChain &&
            errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
            positionMs > 0L

    /**
     * One automatic SABR attempt per video open: never for live
     * streams (Arus is VOD-only — a SABR conversation cannot yet
     * carry a live edge), and never twice for the same video (an
     * Arus failure must surface, not loop against the classic
     * chain's corpse).
     */
    fun shouldAutoRecover(
        isLive: Boolean,
        attemptedForVideoId: String?,
        videoId: String?,
    ): Boolean =
        videoId != null && !isLive && attemptedForVideoId != videoId
}
