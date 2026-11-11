package com.bunny.core.model

/** One real comment, exactly as published under a video. */
data class VideoComment(
    val id: String,
    /** Display name as served (today the @handle form). */
    val authorName: String,
    val authorAvatarUrl: String?,
    val authorChannelId: String?,
    /** Localized as served, e.g. "6 bulan yang lalu". */
    val publishedText: String?,
    val text: String,
    /** Like count as served, e.g. "12"; null when not published. */
    val likeCountText: String?,
    val replyCount: Int = 0,
    /** True when the video's owner gave this comment a heart. */
    val isCreatorHearted: Boolean = false,
)

/** One page of a video's comments. */
data class CommentsPage(
    val items: List<VideoComment> = emptyList(),
    val nextPage: String? = null,
    /**
     * The video owner's real channel photo from the watch page; only
     * the first page carries it (later pages leave it null).
     */
    val ownerAvatarUrl: String? = null,
)
