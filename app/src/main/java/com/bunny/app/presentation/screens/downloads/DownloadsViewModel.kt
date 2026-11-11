package com.bunny.app.presentation.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.DownloadEntity
import com.bunny.app.data.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Unduhan — daftar Panen Bertingkat di perangkat ini. */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntity>> = downloadManager.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun retry(videoId: String) {
        downloadManager.enqueue(videoId)
    }

    fun delete(videoId: String) {
        downloadManager.delete(videoId)
    }
}
