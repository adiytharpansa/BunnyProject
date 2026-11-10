package com.bunny.app.presentation.screens.player

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.viewModelScope
import com.bunny.app.playback.PlaybackDiagnostics
import com.bunny.app.playback.PlaybackManager
import com.bunny.app.playback.PlaybackService
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Ready(val details: VideoDetails, val sourceNote: String) : PlayerUiState
    data class Error(val message: String) : PlayerUiState
}

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val application: Application,
    private val backend: ExtractionBackend,
    private val playbackManager: PlaybackManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** The shared player the screen's PlayerView binds to. */
    val player get() = playbackManager.player

    val diagnostics = PlaybackDiagnostics.events

    private var videoId: String? = null
    private var loadJob: Job? = null
    private var loadAttempts = 0

    fun open(videoId: String) {
        if (this.videoId == videoId && _uiState.value !is PlayerUiState.Error) return
        this.videoId = videoId
        load()
    }

    fun retry() {
        load()
    }

    fun stopPlayback() {
        playbackManager.stop()
    }

    override fun onCleared() {
        // Tripwire: if loads die with "Parent job is Cancelling" because this
        // ViewModel is being cleared mid-flight, this line brackets them in
        // the diagnostics ring (field report 2026-11-10).
        PlaybackDiagnostics.log("PlayerViewModel dibersihkan (onCleared)")
        super.onCleared()
    }

    private fun load() {
        val id = videoId ?: return
        loadJob?.cancel()
        val attempt = ++loadAttempts
        _uiState.value = PlayerUiState.Loading
        PlaybackDiagnostics.log("Membuka video $id via backend ${backend.id} (percobaan $attempt)")
        loadJob = viewModelScope.launch {
            try {
                // Sequential on purpose: both calls share the engine's
                // cached player response, so the second one is free — and
                // sequential calls keep the real error visible instead of
                // letting a sibling coroutine's cancellation mask it (the
                // parallel version surfaced "Parent job is Cancelling" as
                // the user-facing error on 2026-11-10).
                val details = backend.getVideoDetails(id)
                val streams = backend.getPlaybackStreams(id)

                val sources = listOfNotNull(
                    streams.dashManifestContent?.let { "DASH adaptif" },
                    streams.dashManifestUrl?.let { "DASH" },
                    streams.hlsUrl?.let { "HLS" },
                    streams.progressiveUrl?.let { "progresif" },
                )
                PlaybackDiagnostics.log(
                    "Stream tersedia: ${sources.joinToString(", ").ifEmpty { "tidak ada" }}",
                )
                if (sources.isEmpty()) {
                    _uiState.value = PlayerUiState.Error(
                        "Video ini tidak menyediakan aliran yang dapat diputar.",
                    )
                    return@launch
                }

                PlaybackService.start(application)
                playbackManager.configure(streams.playbackUserAgent)
                playbackManager.play(streams, details.title)
                _uiState.value = PlayerUiState.Ready(
                    details = details,
                    sourceNote = "Sumber: ${sources.first()}",
                )
            } catch (e: CancellationException) {
                // Cancellation is control flow, not a playback failure: it
                // must never become an error state (its message is a
                // coroutines-internal string users cannot act on).
                PlaybackDiagnostics.log("Pemuatan #$attempt dibatalkan")
                throw e
            } catch (e: Exception) {
                PlaybackDiagnostics.log(
                    "Gagal menyiapkan (#$attempt): ${e.javaClass.simpleName}: ${e.message}",
                )
                _uiState.value = PlayerUiState.Error(
                    e.message ?: "Video tidak dapat disiapkan.",
                )
            }
        }
    }
}
