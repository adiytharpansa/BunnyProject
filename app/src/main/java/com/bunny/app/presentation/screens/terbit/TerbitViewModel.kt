package com.bunny.app.presentation.screens.terbit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.LibraryRepository
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class TerbitUiState(
    val isLoading: Boolean = true,
    val items: List<VideoItem> = emptyList(),
    val subscriptionCount: Int = 0,
    val editionLabel: String = "",
)

/**
 * Terbit — the morning edition. Composes a FINITE list from the user's
 * local subscriptions: the newest few uploads per channel, interleaved
 * round-robin like the home mixture, each stamped with its Chip Alasan.
 * Opened, read to the end, done — the opposite of an endless feed.
 */
@HiltViewModel
class TerbitViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerbitUiState())
    val uiState: StateFlow<TerbitUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val editionLabel = DateTimeFormatter
                .ofPattern("EEEE, d MMMM yyyy", Locale("id", "ID"))
                .format(LocalDate.now())
            val subscriptions = library.subscriptions.first().take(MAX_CHANNELS)
            if (subscriptions.isEmpty()) {
                _uiState.value = TerbitUiState(
                    isLoading = false,
                    editionLabel = editionLabel,
                )
                return@launch
            }
            _uiState.value = TerbitUiState(
                isLoading = true,
                subscriptionCount = subscriptions.size,
                editionLabel = editionLabel,
            )
            // Channels are fetched concurrently and in isolation: one
            // failing channel must not sink the whole edition.
            val perChannel = subscriptions.map { subscription ->
                async {
                    try {
                        backend.getChannelVideos(subscription.channelId).items
                            .take(ITEMS_PER_CHANNEL)
                            .map { item ->
                                item.copy(
                                    reason = "Dari langganan: ${subscription.channelName}",
                                )
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
            val merged = mutableListOf<VideoItem>()
            val seen = mutableSetOf<String>()
            var index = 0
            var added = true
            while (added) {
                added = false
                for (list in perChannel) {
                    val item = list.getOrNull(index) ?: continue
                    added = true
                    if (seen.add(item.id)) merged += item
                }
                index++
            }
            _uiState.value = TerbitUiState(
                isLoading = false,
                items = merged,
                subscriptionCount = subscriptions.size,
                editionLabel = editionLabel,
            )
        }
    }

    private companion object {
        const val ITEMS_PER_CHANNEL = 3
        const val MAX_CHANNELS = 20
    }
}
