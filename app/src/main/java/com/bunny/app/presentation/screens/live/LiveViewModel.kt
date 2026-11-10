package com.bunny.app.presentation.screens.live

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

data class LiveUiState(
    val isLoading: Boolean = true,
    val items: List<VideoItem> = emptyList(),
    val error: String? = null,
)

/**
 * The Live tab: streams broadcasting right now, composed by the
 * engine from live-filtered topic searches (see
 * [ExtractionBackend.getLiveFeed]). A finite list, refreshed by
 * reloading — live catalogues reward a fresh look, not pagination.
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    private val thumbnails: ThumbnailPreloader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val page = backend.getLiveFeed()
                thumbnails.preload(page.items)
                _uiState.update { it.copy(isLoading = false, items = page.items) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Unknown error")
                }
            }
        }
    }
}
