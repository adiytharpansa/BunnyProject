package com.bunny.app.presentation.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bunny.app.data.LibraryRepository
import com.bunny.app.playback.FeedPreviewManager
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.rank.FeedRanker
import com.bunny.core.rank.TasteProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    private val library: LibraryRepository,
    private val previewManager: FeedPreviewManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(backendId = backend.id))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Jatah bar state: estimated month usage + the user's budget. */
    val jatah: StateFlow<JatahState> = combine(
        library.quotaBytes,
        library.monthUsageBytes(),
    ) { quota, used -> JatahState(usedBytes = used, quotaBytes = quota) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JatahState())

    fun setQuota(bytes: Long?) {
        viewModelScope.launch { library.setQuotaBytes(bytes) }
    }

    // ------------------------------------------------------ pratinjau

    private val _previewingId = MutableStateFlow<String?>(null)

    /** The video whose card currently shows an inline preview. */
    val previewingId: StateFlow<String?> = _previewingId.asStateFlow()

    /** Attach point for the preview surface in the previewing card. */
    val previewPlayer: ExoPlayer get() = previewManager.player

    private var previewJob: Job? = null

    fun startPreview(videoId: String) {
        if (_previewingId.value == videoId) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                val streams = backend.getPlaybackStreams(videoId)
                previewManager.start(streams)
                _previewingId.value = videoId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Previews are decoration: a failure stays silent and
                // the card simply keeps its thumbnail.
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        _previewingId.value = null
        previewManager.stop()
    }

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val status = backend.healthCheck()
                _uiState.update { it.copy(backendStatus = status) }
                val page = backend.getHomeFeed(page = null)
                // Mesin Selera: the mixture is candidate generation;
                // this ranking pass is Bunny's local second stage,
                // fed by a one-shot snapshot of the on-device taste
                // profile (subscriptions, opened channels, searches).
                val taste = TasteProfile(
                    subscribedChannelIds = library.subscriptions.first()
                        .map { it.channelId }
                        .toSet(),
                    channelAffinity = library.channelAffinity.first(),
                    searchTerms = library.recentSearches.first()
                        .flatMap { query -> query.lowercase().split(Regex("\\s+")) }
                        .filter { it.length >= 3 }
                        .toSet(),
                )
                val ranked = FeedRanker.rank(page.items, taste)
                _uiState.update { it.copy(isLoading = false, items = ranked) }
            } catch (e: CancellationException) {
                // Cancellation is control flow, never a feed error.
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }
}
