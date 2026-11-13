package com.bunny.app.presentation.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.LibraryRepository
import com.bunny.app.presentation.components.ThumbnailPreloader
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.SearchFilter
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
    val filter: SearchFilter = SearchFilter.DEFAULT,
    val results: List<VideoItem> = emptyList(),
    val nextPage: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    /** Gagal memuat halaman lanjutan (hasil yang ada tetap tampil). */
    val loadMoreFailed: Boolean = false,
    /** True = sedang mengetik (saran); false = melihat hasil. */
    val isEditing: Boolean = true,
) {
    val showResults: Boolean get() = submittedQuery != null && !isEditing
}

/**
 * Dua tahap seperti YouTube: mengetik (riwayat + saran langsung) lalu
 * hasil berhalaman dengan filter. Mengetik ulang setelah hasil tampil
 * kembali membuka saran; tombol kembali dari saran membawa ke hasil.
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
    private var searchJob: Job? = null
    private var moreJob: Job? = null

    // ------------------------------------------------------------ mengetik

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value, isEditing = true) }
        fetchSuggestions(value)
    }

    /** Kolom cari disentuh saat hasil tampil: kembali ke mode saran. */
    fun onSearchFieldFocused() {
        val state = _uiState.value
        if (state.isEditing) return
        _uiState.update { it.copy(isEditing = true) }
        fetchSuggestions(state.query)
    }

    /** Tombol kembali dari mode saran: pulihkan hasil yang tadi. */
    fun backToResults() {
        _uiState.update {
            it.copy(
                isEditing = false,
                query = it.submittedQuery ?: it.query,
                suggestions = emptyList(),
            )
        }
    }

    private fun fetchSuggestions(value: String) {
        suggestionJob?.cancel()
        if (value.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(SUGGESTION_DEBOUNCE_MS)
            val found = backend.getSearchSuggestions(value.trim())
            // Saran untuk teks yang sudah diubah lagi hanya jadi noise.
            _uiState.update { state ->
                if (state.query == value) state.copy(suggestions = found) else state
            }
        }
    }

    // ------------------------------------------------------------- mencari

    fun submit(raw: String = _uiState.value.query) {
        runSearch(raw.trim(), _uiState.value.filter, record = true)
    }

    fun applyFilter(filter: SearchFilter) {
        val state = _uiState.value
        val query = state.submittedQuery ?: return
        if (filter == state.filter) return
        runSearch(query, filter, record = false)
    }

    fun retry() {
        val state = _uiState.value
        val query = state.submittedQuery ?: return
        runSearch(query, state.filter, record = false)
    }

    private fun runSearch(query: String, filter: SearchFilter, record: Boolean) {
        if (query.isEmpty()) return
        suggestionJob?.cancel()
        searchJob?.cancel()
        moreJob?.cancel()
        _uiState.update {
            it.copy(
                query = query,
                submittedQuery = query,
                filter = filter,
                suggestions = emptyList(),
                results = emptyList(),
                nextPage = null,
                isLoading = true,
                isLoadingMore = false,
                error = null,
                loadMoreFailed = false,
                isEditing = false,
            )
        }
        searchJob = viewModelScope.launch {
            if (record) library.recordSearch(query)
            try {
                val page = backend.search(query, null, filter)
                thumbnails.preload(page.items)
                _uiState.update {
                    it.copy(isLoading = false, results = page.items, nextPage = page.nextPage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "") }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val token = state.nextPage ?: return
        val query = state.submittedQuery ?: return
        if (state.isLoading || state.isLoadingMore) return
        _uiState.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
        moreJob = viewModelScope.launch {
            try {
                val page = backend.search(query, token, state.filter)
                thumbnails.preload(page.items)
                _uiState.update {
                    val merged = (it.results + page.items).distinctBy { item -> item.id }
                    val added = merged.size - it.results.size
                    it.copy(
                        isLoadingMore = false,
                        results = merged,
                        // Halaman tanpa tambahan baru = akhir daftar; cegah
                        // gulir tak terbatas berputar tanpa henti.
                        nextPage = if (added == 0) null else page.nextPage,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingMore = false, loadMoreFailed = true) }
            }
        }
    }

    // ------------------------------------------------------------- riwayat

    fun removeRecent(term: String) {
        viewModelScope.launch { library.removeRecentSearch(term) }
    }

    fun clearHistory() {
        viewModelScope.launch { library.clearRecentSearches() }
    }

    private companion object {
        const val SUGGESTION_DEBOUNCE_MS = 250L
    }
}
