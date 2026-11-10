package com.bunny.app.presentation.screens.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.LibraryRepository
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChannelUiState(
    val channelName: String = "",
    val items: List<VideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * The channel page's state: the channel's uploads from the engine
 * (the same uploads-playlist path Terbit uses) plus the local
 * subscription toggle shared with the player screen.
 */
@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChannelUiState())
    val uiState: StateFlow<ChannelUiState> = _uiState

    private var loadedChannelId: String? = null

    fun load(channelId: String, channelName: String) {
        // Guard against recomposition re-entry; a failed load (empty
        // items) stays retryable.
        if (loadedChannelId == channelId && _uiState.value.items.isNotEmpty()) return
        loadedChannelId = channelId
        _uiState.update {
            it.copy(channelName = channelName, isLoading = true, error = null)
        }
        viewModelScope.launch {
            try {
                val page = backend.getChannelVideos(channelId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = page.items,
                        channelName = page.items.firstOrNull()?.channelName
                            ?.takeIf { name -> name.isNotEmpty() } ?: channelName,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun subscriptionState(channelId: String): Flow<Boolean> =
        library.isSubscribed(channelId)

    fun toggleSubscription(channelId: String, channelName: String, subscribed: Boolean) {
        viewModelScope.launch {
            if (subscribed) {
                library.unsubscribe(channelId)
            } else {
                library.subscribe(channelId, channelName)
            }
        }
    }
}
