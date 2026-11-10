package com.bunny.core.model

/**
 * One video as the UI sees it. Deliberately free of any extractor types:
 * every backend maps its own models into this shape.
 */
data class VideoItem(
    val id: String,
    val title: String,
    val channelId: String?,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val viewCount: Long?,
    val uploadedText: String?,
    val isLive: Boolean = false,
    /**
     * Chip Alasan: why this item is being shown, in the user's language
     * ("Topik: Musik", "Dari langganan: Blender Foundation"). Computed
     * locally by whoever composed the list; null means no claim is made.
     */
    val reason: String? = null,
)
