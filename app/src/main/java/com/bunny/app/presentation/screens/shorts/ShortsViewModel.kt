package com.bunny.app.presentation.screens.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.FeedbackKind
import com.bunny.app.data.TasteRepository
import com.bunny.app.presentation.components.ThumbnailPreloader
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoDetails
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
 *
 * Aksi suka / tidak suka / tidak tertarik / jangan rekomendasikan
 * kanal HANYA dicatat sebagai sinyal selera lokal lewat
 * [TasteRepository] (tidak ada akun Bunny, tidak ada penulisan ke
 * YouTube) — tidak ada angka atau status yang dikarang.
 */
@HiltViewModel
class ShortsViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    private val thumbnails: ThumbnailPreloader,
    private val taste: TasteRepository,
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

    // ------------------------------------------------- umpan balik lokal

    fun like(item: VideoItem, details: VideoDetails?) =
        record(FeedbackKind.LIKE, item, details)

    fun dislike(item: VideoItem, details: VideoDetails?) =
        record(FeedbackKind.DISLIKE, item, details)

    /** "Tidak tertarik": dicatat, lalu video langsung hilang dari umpan. */
    fun notInterested(item: VideoItem, details: VideoDetails?) {
        record(FeedbackKind.NOT_INTERESTED, item, details)
        remove(item.id)
    }

    /** "Jangan rekomendasikan kanal ini": dicatat, video ini hilang dari umpan. */
    fun dontRecommendChannel(item: VideoItem, details: VideoDetails?) {
        record(FeedbackKind.DONT_RECOMMEND_CHANNEL, item, details)
        remove(item.id)
    }

    private fun remove(videoId: String) {
        _uiState.update { state ->
            state.copy(items = state.items.filterNot { video -> video.id == videoId })
        }
    }

    private fun record(kind: String, item: VideoItem, details: VideoDetails?) {
        viewModelScope.launch {
            try {
                taste.recordFeedback(
                    kind = kind,
                    videoId = item.id,
                    channelId = details?.channelId ?: item.channelId,
                    channelName = details?.channelName?.takeIf { it.isNotBlank() }
                        ?: item.channelName,
                    title = details?.title ?: item.title,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sinyal selera hanya pelengkap; kegagalan menulisnya
                // tidak boleh mengganggu menonton.
            }
        }
    }
}
