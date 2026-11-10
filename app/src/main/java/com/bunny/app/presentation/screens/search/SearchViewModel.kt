package com.bunny.app.presentation.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.LibraryRepository
import com.bunny.app.presentation.components.ThumbnailPreloader
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val submittedQuery: String? = null,
    val results: List<VideoItem> = emptyList(),
    val nextPage: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)

/**
 * Two-stage search, mirroring the official app's structure (REA
 * dissection 2026-11-10): the typing stage offers local recent
 * searches plus live suggestions, and submit loads a results page
 * that can page further with the engine's continuation token.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    private val library: LibraryRepository,
    private val thumbnails: ThumbnailPreloader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    val recentSearches: StateFlow<List<String>> = library.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var suggestionJob: Job? = null

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        suggestionJob?.cancel()
        if (value.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(SUGGESTION_DEBOUNCE_MS)
            val found = backend.getSearchSuggestions(value.trim())
            // Suggestions for a query the user has already edited away
            // from would be noise — apply only while it still matches.
            _uiState.update { state ->
                if (state.query == value) state.copy(suggestions = found) else state
            }
        }
    }

    fun submit(raw: String = _uiState.value.query) {
        val query = raw.trim()
        if (query.isEmpty()) return
        _uiState.update {
            it.copy(
                query = query,
                submittedQuery = query,
                suggestions = emptyList(),
                results = emptyList(),
                nextPage = null,
                isLoading = true,
                error = null,
            )
        }
        viewModelScope.launch {
            library.recordSearch(query)
            try {
                val page = backend.search(query, null)
                thumbnails.preload(page.items)
                _uiState.update {
                    it.copy(isLoading = false, results = page.items, nextPage = page.nextPage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val token = state.nextPage ?: return
        val query = state.submittedQuery ?: return
        if (state.isLoading || state.isLoadingMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            try {
                val page = backend.search(query, token)
                thumbnails.preload(page.items)
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        results = (it.results + page.items).distinctBy { item -> item.id },
                        nextPage = page.nextPage,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingMore = false, error = e.message) }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { library.clearRecentSearches() }
    }

    private companion object {
        const val SUGGESTION_DEBOUNCE_MS = 250L
    }
}
