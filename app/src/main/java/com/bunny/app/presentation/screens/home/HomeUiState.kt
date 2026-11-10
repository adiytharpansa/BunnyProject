package com.bunny.app.presentation.screens.home

import com.bunny.core.model.VideoItem

data class HomeUiState(
    val isLoading: Boolean = true,
    val items: List<VideoItem> = emptyList(),
    val error: String? = null,
    val backendId: String = "",
    val backendStatus: String = "",
)

/** Jatah: this month's estimated usage against the user's data budget. */
data class JatahState(
    val usedBytes: Long = 0,
    val quotaBytes: Long? = null,
)
