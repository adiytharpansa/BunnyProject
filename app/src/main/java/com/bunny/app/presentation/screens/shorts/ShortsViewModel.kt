package com.bunny.app.presentation.screens.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.presentation.components.ThumbnailPreloader
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * Tab Shorts: video pendek (<= 3 menit, bukan siaran langsung),
 * disusun dari beberapa pencarian topik lewat [ExtractionBackend.search]
 * yang sudah ada — tanpa mengubah kontrak mesin. Daftar terbatas,
 * disegarkan dengan memuat ulang.
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
                val results = coroutineScope {
                    SHORTS_QUERIES.map { query ->
                        async {
                            try {
                                Result.success(
                                    backend.search(query, null).items
                                        .filter { isShort(it) },
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure<List<VideoItem>>(e)
                            }
                        }
                    }.awaitAll()
                }
                val items = interleave(results.mapNotNull { it.getOrNull() })
                if (items.isEmpty()) {
                    val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                    _uiState.update {
                        it.copy(isLoading = false, items = emptyList(), error = failure?.message)
                    }
                } else {
                    thumbnails.preload(items)
                    _uiState.update { it.copy(isLoading = false, items = items) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun isShort(item: VideoItem): Boolean {
        if (item.isLive) return false
        val seconds = item.durationSeconds ?: return false
        return seconds in 1..MAX_SHORT_SECONDS
    }

    /** Round-robin antar topik, duplikat dibuang. */
    private fun interleave(pages: List<List<VideoItem>>): List<VideoItem> {
        val merged = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        var added = true
        while (added) {
            added = false
            for (list in pages) {
                val item = list.getOrNull(index) ?: continue
                added = true
                if (seen.add(item.id)) merged += item
            }
            index++
        }
        return merged.take(MAX_ITEMS)
    }

    private companion object {
        const val MAX_SHORT_SECONDS = 180L
        const val MAX_ITEMS = 40
        val SHORTS_QUERIES = listOf(
            "#shorts",
            "shorts viral",
            "shorts lucu",
            "shorts musik",
            "shorts bola",
            "shorts gaming",
        )
    }
}
