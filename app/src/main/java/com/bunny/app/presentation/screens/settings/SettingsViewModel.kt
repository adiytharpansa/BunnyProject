package com.bunny.app.presentation.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.FeedDiskCache
import com.bunny.app.data.WatchPositionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val feedCache: FeedDiskCache,
    private val positions: WatchPositionStore,
) : ViewModel() {

    /** Menghapus salinan Beranda di disk. Beranda memuat ulang dari jaringan. */
    fun clearFeedCache() {
        viewModelScope.launch { feedCache.clear() }
    }

    /** Menghapus semua posisi "lanjutkan menonton". */
    fun clearWatchPositions() {
        positions.clearAll()
    }
}
