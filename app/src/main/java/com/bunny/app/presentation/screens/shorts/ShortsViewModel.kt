package com.bunny.app.presentation.screens.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.presentation.components.ThumbnailPreloader
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShortsUiState(
    val isLoading: Boolean = true,
    val items: List<VideoItem> = emptyList(),
    val error: String? = null,
)

/**
 * Tab Shorts: umpan video pendek vertikal asli dari
 * [ExtractionBackend.getShortsFeed] — mesin yang menyusunnya dari
 * pencarian tanpa tapis dan hanya menerima baris bertanda vertikal.
 * Gagal memuat tampil sebagai galat + coba lagi, tidak pernah
 * menyamar sebagai daftar kosong.
 */
@HiltViewModel
class ShortsViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    private val thumbnails: ThumbnailPreloader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShortsUiState())
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val items = backend.getShortsFeed().items
                if (items.isNotEmpty()) {
                    thumbnails.preload(items)
                }
                _uiState.update {
                    it.copy(isLoading = false, items = items, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = emptyList(),
                        error = e.message ?: "Unknown error",
                    )
                }
            }
        }
    }
}
