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

    /** Which engine opens a video (the merged-paths policy, A3). */
    enum class PrimaryPath { ARUS_FIRST, CLASSIC_ONLY }

    /**
     * Since the paths merged, Arus opens every VOD video: the classic
     * chain's URLs are the ones the token wall cuts, while the SABR
     * conversation has never been refused on device. Classic opens
     * only live streams (Arus cannot carry them) and every video
     * while the session's Arus breaker is tripped (the SABR session
     * fetch itself failed at transport level — retrying it per video
     * would tax every open for a condition that is not the video's
     * fault). Classic streams are fetched either way during load, so
     * falling back costs nothing extra.
     */
    fun primaryPath(isLive: Boolean, arusSessionBroken: Boolean): PrimaryPath =
        if (!isLive && !arusSessionBroken) PrimaryPath.ARUS_FIRST else PrimaryPath.CLASSIC_ONLY

    /**
     * The reverse rescue, one shot per video: when the primary Arus
     * chain dies, the classic chain (already fetched) gets its turn.
     * Together with [shouldAutoRecover] the worst case per video is
     * bounded: Arus -> classic -> Arus, then the failure surfaces.
     */
    fun shouldRescueWithClassic(
        attemptedForVideoId: String?,
        videoId: String?,
    ): Boolean =
        videoId != null && attemptedForVideoId != videoId
}
