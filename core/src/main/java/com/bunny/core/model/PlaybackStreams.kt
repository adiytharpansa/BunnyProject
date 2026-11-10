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
    /**
     * A DASH manifest the backend generated itself from adaptive formats
     * (Lontar builds one in-engine when the server offers none). Mutually
     * exclusive in practice with [dashManifestUrl]; players prefer content
     * they already hold over a URL they must fetch.
     */
    val dashManifestContent: String? = null,
    /**
     * User-Agent the stream URLs were issued to. Media URLs can be tied to
     * the client that requested them, so playback requests must present the
     * same agent; the UI layer stays engine-agnostic by receiving it here.
     */
    val playbackUserAgent: String? = null,
)
