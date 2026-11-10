package com.bunny.app.presentation.screens.player

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bunny.app.data.LibraryRepository
import com.bunny.app.playback.PlaybackDiagnostics
import com.bunny.app.playback.PlaybackManager
import com.bunny.app.playback.PlaybackService
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoDetails
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Ready(
        val details: VideoDetails,
        val sourceNote: String,
        /** Timbangan at the player: real-format estimate, when available. */
        val estimatedSizeBytes: Long? = null,
        /** Jatah: true when this playback pushes the month over budget. */
        val jatahExceeded: Boolean = false,
        val monthUsedBytes: Long = 0,
        val quotaBytes: Long? = null,
    ) : PlayerUiState
    data class Error(val message: String) : PlayerUiState
}

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val application: Application,
    private val backend: ExtractionBackend,
    private val playbackManager: PlaybackManager,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * The player the screen's PlayerView binds to: a MediaController, not
     * the raw ExoPlayer. The controller connection is what registers the
     * session with PlaybackService — without it the notification provider
     * never sees a session, so no media notification is posted and
     * background playback dies with the UI (device test 2026-11-10).
     * Sources are still loaded through PlaybackManager onto the very same
     * underlying player; the controller is the remote control for it.
     */
    private val _controllerPlayer = MutableStateFlow<Player?>(null)
    val controllerPlayer: StateFlow<Player?> = _controllerPlayer.asStateFlow()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    val diagnostics = PlaybackDiagnostics.events

    init {
        connectController()
    }

    private fun connectController() {
        // The controller is an enhancement (notification + background
        // controls); playback itself runs through PlaybackManager and must
        // never be hostage to it. SessionToken's constructor throws
        // IllegalArgumentException when the service cannot be resolved —
        // a wrong manifest action string did exactly that and crashed
        // every video open (field crash report 2026-11-10) — so any
        // failure here degrades to "no controller" and is only logged.
        try {
            val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
            val future = MediaController.Builder(application, token).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    try {
                        _controllerPlayer.value = future.get()
                        PlaybackDiagnostics.log("MediaController terhubung ke layanan")
                    } catch (e: Exception) {
                        PlaybackDiagnostics.log("MediaController gagal: ${e.javaClass.simpleName}: ${e.message}")
                    }
                },
                ContextCompat.getMainExecutor(application),
            )
        } catch (e: Exception) {
            PlaybackDiagnostics.log("MediaController gagal: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

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

    /** Terbit's source: local subscriptions, toggled from this screen. */
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

    override fun onCleared() {
        // Tripwire: if loads die with "Parent job is Cancelling" because this
        // ViewModel is being cleared mid-flight, this line brackets them in
        // the diagnostics ring (field report 2026-11-10).
        PlaybackDiagnostics.log("PlayerViewModel dibersihkan (onCleared)")
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _controllerPlayer.value = null
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

                // Jatah: charge the estimate once playback starts, then
                // judge the month WITH this video included — the warning
                // must reflect the state the user is now in, not before.
                val estimate = streams.estimatedSizeBytes
                if (estimate != null) {
                    library.charge(id, details.title, estimate)
                }
                val quota = library.quotaBytes.first()
                val used = library.monthUsageBytes().first()
                val exceeded = quota != null && used > quota
                if (exceeded) {
                    PlaybackDiagnostics.log("Jatah terlampaui bulan ini")
                }
                _uiState.value = PlayerUiState.Ready(
                    details = details,
                    sourceNote = "Sumber: ${sources.first()}",
                    estimatedSizeBytes = estimate,
                    jatahExceeded = exceeded,
                    monthUsedBytes = used,
                    quotaBytes = quota,
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
