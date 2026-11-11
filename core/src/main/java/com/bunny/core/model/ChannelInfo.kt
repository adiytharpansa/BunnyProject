package com.bunny.core.model

/** A channel's real public identity, exactly as published on its page. */
data class ChannelInfo(
    val channelId: String,
    val name: String,
    val avatarUrl: String?,
    val description: String,
    /** Localized as served, e.g. "2,86 jt subscriber"; null when absent. */
    val subscriberText: String?,
    /** Localized as served, e.g. "158 rb video"; null when absent. */
    val videoCountText: String?,
)
