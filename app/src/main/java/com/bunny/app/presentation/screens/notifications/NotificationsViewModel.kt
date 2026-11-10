package com.bunny.app.presentation.screens.notifications

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
import javax.inject.Inject

data class NotificationsUiState(
    val isLoading: Boolean = true,
    val items: List<VideoItem> = emptyList(),
    val subscriptionCount: Int = 0,
)

/**
 * Notifikasi — the newest upload of each subscribed channel, newest
 * first. Local subscriptions only; nothing leaves the device but the
 * same channel fetches Terbit/Kanal already make. One failing channel
 * never sinks the list.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val backend: ExtractionBackend,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState: StateFlow<NotificationsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** Called each time the page opens, so it is always fresh. */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val subscriptions = library.subscriptions.first().take(MAX_CHANNELS)
            if (subscriptions.isEmpty()) {
                _uiState.value = NotificationsUiState(isLoading = false)
                return@launch
            }
            _uiState.value = NotificationsUiState(
                isLoading = true,
                subscriptionCount = subscriptions.size,
            )
            val latest = subscriptions.map { subscription ->
                async {
                    try {
                        backend.getChannelVideos(subscription.channelId).items
                            .firstOrNull()
                            ?.copy(reason = "Dari langganan: ${subscription.channelName}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
            _uiState.value = NotificationsUiState(
                isLoading = false,
                items = latest.sortedBy { ageMinutes(it.uploadedText) },
                subscriptionCount = subscriptions.size,
            )
        }
    }

    private companion object {
        const val MAX_CHANNELS = 15

        private val AGE = Regex("(\\d+)\\s*(detik|menit|jam|hari|minggu|bulan|tahun)")

        /** Rough age of "5 hari yang lalu"-style text, for newest-first ordering. */
        fun ageMinutes(text: String?): Long {
            val m = text?.let { AGE.find(it.lowercase()) } ?: return Long.MAX_VALUE
            val n = m.groupValues[1].toLongOrNull() ?: return Long.MAX_VALUE
            val unit = when (m.groupValues[2]) {
                "detik" -> 0L
                "menit" -> 1L
                "jam" -> 60L
                "hari" -> 60L * 24
                "minggu" -> 60L * 24 * 7
                "bulan" -> 60L * 24 * 30
                else -> 60L * 24 * 365
            }
            return n * unit
        }
    }
}
