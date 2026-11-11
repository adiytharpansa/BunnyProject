package com.bunny.core.model

/**
 * One downloadable media file for a video, exactly as the player
 * response offered it (Panen Bertingkat's raw material). URLs are
 * signed and expire — a downloader must fetch promptly and re-ask
 * the backend when it comes back later for the next stage.
 */
data class DownloadFormat(
    val itag: Int,
    val url: String,
    /** Base MIME type without codec parameters, e.g. "video/mp4". */
    val mimeType: String,
    val isAudioOnly: Boolean,
    /** True for combined audio+video files (the response's "formats"). */
    val isProgressive: Boolean = false,
    /** Video height in pixels; 0 for audio-only formats. */
    val height: Int = 0,
    val bitrate: Long = 0,
    val contentLength: Long? = null,
)

/** The format set of one video plus the agent its URLs were issued to. */
data class DownloadFormats(
    val formats: List<DownloadFormat> = emptyList(),
    val userAgent: String? = null,
)
