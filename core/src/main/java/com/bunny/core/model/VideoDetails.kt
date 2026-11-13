package com.bunny.core.model

/** Full metadata for the watch screen. */
data class VideoDetails(
    val id: String,
    val title: String,
    val description: String,
    val channelId: String?,
    val channelName: String,
    val viewCount: Long?,
    val likeCount: Long?,
    val uploadedText: String?,
    val isLive: Boolean = false,
    /** Largest thumbnail URL — feeds the media notification's artwork. */
    val thumbnailUrl: String? = null,
)
