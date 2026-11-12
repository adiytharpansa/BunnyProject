package com.bunny.core.model

/**
 * Playable URLs for one video, in the order the player should try them:
 * the classic chain — generated DASH, then DASH URL, HLS, progressive.
 * Backends fill whichever of these they can produce.
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
    /**
     * Name of the player persona that issued these URLs (Lontar
     * Hidup): playback errors are attributed to it, so a mid-stream
     * refusal can demote exactly the persona that earned it.
     */
    val personaName: String? = null,
    /**
     * Timbangan at the player: estimated bytes for watching this video at
     * the reference quality, computed by the backend from the real format
     * bitrates it was offered (video + audio) times the duration. Null
     * when the backend could not estimate honestly.
     */
    val estimatedSizeBytes: Long? = null,
    /**
     * Selera Tamu (Langkah 1): the playback-tracking addresses the
     * player response itself supplies for this video — where the
     * official clients report "a view started" and "watch time is
     * accumulating", tied to the session's visitor identity.
     */
    val tracking: PlaybackTrackingUrls? = null,
    /**
     * Fase 8 mechanism 14: itag → lastModified (the rendition
     * identity) for exactly the formats the generated DASH manifest
     * ([dashManifestContent]) is built from. The classic road uses
     * it to decide whether bytes it downloads may be recorded into
     * the video's spool: a rendition is a byte space, and bytes may
     * only join a spool state that speaks the same one. Empty when
     * no generated manifest exists or no format named a rendition.
     */
    val dashRenditions: Map<Int, Long> = emptyMap(),
)

/** The stats addresses inside a player response's playbackTracking. */
data class PlaybackTrackingUrls(
    val playbackUrl: String?,
    val watchtimeUrl: String?,
)
