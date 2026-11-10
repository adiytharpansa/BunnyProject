package com.bunny.core.model

/**
 * Playable URLs for one video, in the order the player should try them.
 * Fase 2 turns this into the full SABR -> DASH -> HLS chain; in Fase 0/1
 * backends fill whichever of these they can produce.
 *
 * Stream URLs expire: [expiresAtEpochMs] is when the backend believes these
 * URLs stop working, so the player can re-resolve instead of failing.
 */
data class PlaybackStreams(
    val dashManifestUrl: String?,
    val hlsUrl: String?,
    val progressiveUrl: String?,
    val expiresAtEpochMs: Long?,
)
