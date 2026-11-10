package com.bunny.app.presentation.screens.home

import com.bunny.core.model.VideoItem

data class HomeUiState(
    val isLoading: Boolean = true,
    val items: List<VideoItem> = emptyList(),
    val error: String? = null,
    val backendId: String = "",
    val backendStatus: String = "",
)
