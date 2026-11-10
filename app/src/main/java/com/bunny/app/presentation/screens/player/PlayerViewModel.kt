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
import com.bunny.app.playback.ArusPlayback
import com.bunny.app.playback.PlaybackDiagnostics
import com.bunny.app.playback.PlaybackFailureLog
import com.bunny.app.playback.PlaybackManager
import com.bunny.app.playback.PlaybackService
import com.bunny.app.playback.PlaybackWallPolicy
import com.bunny.engine.lontar.LontarEngine
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.PlaybackStreams
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

/** Player-control options shown on the player screen (S5). */
data class PlayerOptionsState(
    /** Heights the current source offers, best first; empty until known. */
    val qualities: List<Int> = emptyList(),
    /** Manually pinned height; null means adaptive ("Otomatis"). */
    val selectedQuality: Int? = null,
    val speed: Float = 1.0f,
)

/** Rekomendasi di bawah pemutar: tiga tampilan dari satu sumber data. */
data class RelatedUiState(
    val isLoading: Boolean = true,
    /** Campuran serupa + kanal (tab "Semua"). */
    val all: List<VideoItem> = emptyList(),
    /** Hasil pencarian dari judul video (tab "Terkait"). */
    val similar: List<VideoItem> = emptyList(),
    /** Unggahan terbaru kanal yang sama (tab "Dari {kanal}"). */
    val fromChannel: List<VideoItem> = emptyList(),
    val error: String? = null,
)

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val application: Application,
    private val backend: ExtractionBackend,
    private val lontarEngine: LontarEngine,
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
        // Arus recovery (Fase A3): when the classic chain dies under
        // this screen, the manager reports it here and the video gets
        // one automatic SABR attempt instead of a black screen.
        playbackManager.onClassicChainDied = { onClassicChainDied() }
        // The merged paths' reverse direction: a dead Arus chain
        // earns one classic rescue, with the streams load() fetched.
        playbackManager.onArusChainDied = { onArusChainDied() }
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

    /** The video the automatic Arus recovery already spent itself on. */
    private var arusAutoAttemptedFor: String? = null

    /**
     * Arus Fase A3 — automatic recovery: the classic chain died under
     * this screen (the token wall's mid-play cut, or every source
     * refused at load). The video gets ONE automatic SABR attempt;
     * live streams are excluded (Arus is VOD-only) and a spent
     * attempt never loops. The manual button below stays available
     * regardless — it is the user's own retry.
     */
    private fun onClassicChainDied() {
        val ready = _uiState.value as? PlayerUiState.Ready ?: return
        if (!PlaybackWallPolicy.shouldAutoRecover(
                isLive = ready.details.isLive,
                attemptedForVideoId = arusAutoAttemptedFor,
                videoId = videoId,
            )
        ) {
            return
        }
        arusAutoAttemptedFor = videoId
        PlaybackDiagnostics.log(
            "Pemulihan otomatis: jalur klasik gugur — beralih ke Arus (SABR)…",
        )
        startArus(ready)
    }

    /**
     * Arus Fase A2 — the deliberate experiment: play the open video
     * through a SABR conversation instead of the classic chain, from
     * the diagnostics block's button. Since Fase A3 the same path is
     * also the automatic recovery when the classic chain dies.
     * Failures land in the diagnostics ring, where the user who
     * pressed the button is already looking.
     */
    fun playViaArus() {
        videoId ?: return
        val ready = _uiState.value as? PlayerUiState.Ready ?: run {
            PlaybackDiagnostics.log("Arus: video belum siap, percobaan dibatalkan")
            return
        }
        startArus(ready)
    }

    /** Shared by the manual button and the automatic recovery. */
    private fun startArus(ready: PlayerUiState.Ready) {
        val id = videoId ?: return
        viewModelScope.launch { startArusNow(id, ready.details) }
    }

    /**
     * The one SABR start every caller shares — the diagnostics
     * button, the classic chain's rescue, and (since the paths
     * merged) the primary open in [load]. Returns true when the
     * Arus chain took the player over; false leaves the caller to
     * run the classic chain instead. A session FETCH failure is a
     * statement about the engine, not the video, so it trips the
     * session breaker: later videos skip straight to classic.
     */
    private suspend fun startArusNow(id: String, details: VideoDetails): Boolean {
        PlaybackDiagnostics.log("Arus: meminta sesi SABR (persona VISIONOS)…")
        val sabr = try {
            lontarEngine.sabrSession(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            playbackManager.arusSessionBroken = true
            PlaybackDiagnostics.log(
                "Arus gagal: ${e.javaClass.simpleName}: ${e.message} (pemutus sesi terpicu — video berikutnya langsung klasik)",
            )
            return false
        }
        if (sabr == null) {
            PlaybackDiagnostics.log("Arus: respons player tidak membawa sesi SABR")
            return false
        }
        val (session, persona) = sabr
        val picked = ArusPlayback.pickFormats(session)
        if (picked == null) {
            PlaybackDiagnostics.log("Arus: sesi SABR tanpa format video/audio")
            return false
        }
        val (video, audio) = picked
        PlaybackDiagnostics.log(
            "Arus: video itag ${video.itag} (${video.height}p), audio itag ${audio.itag}",
        )
        return try {
            val store = ArusPlayback.newStore(session, video, audio, persona.userAgent)
            PlaybackService.start(application)
            playbackManager.playArus(
                store, video, audio,
                details.title,
                details.channelName,
                details.thumbnailUrl,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlaybackDiagnostics.log(
                "Arus gagal: ${e.javaClass.simpleName}: ${e.message}",
            )
            false
        }
    }

    /** The video the classic rescue already spent itself on. */
    private var classicRescueAttemptedFor: String? = null

    /** load()'s classic streams, kept so a dead Arus chain can be rescued with them. */
    private var lastStreams: PlaybackStreams? = null
    private var lastDetails: VideoDetails? = null

    /**
     * The reverse rescue (merged paths): the primary Arus chain died
     * under this screen — hand the video to the classic chain, whose
     * streams [load] already fetched. One shot per video; if classic
     * then hits the wall, the classic hook offers Arus once more,
     * and after that the failure surfaces honestly.
     */
    private fun onArusChainDied() {
        val streams = lastStreams ?: return
        val details = lastDetails ?: return
        if (_uiState.value !is PlayerUiState.Ready) return
        if (!PlaybackWallPolicy.shouldRescueWithClassic(classicRescueAttemptedFor, videoId)) {
            return
        }
        classicRescueAttemptedFor = videoId
        PlaybackDiagnostics.log(
            "Pemulihan otomatis: Arus gugur — beralih ke jalur klasik…",
        )
        PlaybackService.start(application)
        playbackManager.configure(streams.playbackUserAgent)
        playbackManager.play(
            streams,
            details.title,
            details.channelName,
            details.thumbnailUrl,
        )
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

    // -------------------------------------------- kontrol pemutar (S5)

    private val _options = MutableStateFlow(PlayerOptionsState())
    val options: StateFlow<PlayerOptionsState> = _options.asStateFlow()

    /** Re-reads the track-derived options; called when a dialog opens. */
    fun refreshOptions() {
        _options.update {
            it.copy(
                qualities = playbackManager.videoQualities(),
                selectedQuality = playbackManager.selectedQuality(),
            )
        }
    }

    fun setQuality(height: Int?) {
        playbackManager.setVideoQuality(height)
        refreshOptions()
    }

    fun setSpeed(speed: Float) {
        playbackManager.setSpeed(speed)
        _options.update { it.copy(speed = speed) }
    }

// ------------------------------------------------ rekomendasi video

private val _related = MutableStateFlow(RelatedUiState())
val related: StateFlow<RelatedUiState> = _related.asStateFlow()
private var relatedJob: Job? = null

fun retryRelated() {
    val id = videoId ?: return
    val details = (_uiState.value as? PlayerUiState.Ready)?.details ?: return
    loadRelated(id, details)
}

/**
 * Rekomendasi tidak boleh menghambat pemutaran: dijalankan di job
 * terpisah SETELAH video siap, dan semua kegagalannya hanya berujung
 * daftar kosong, tidak pernah error pemutar.
 */
private fun loadRelated(id: String, details: VideoDetails) {
    relatedJob?.cancel()
    _related.value = RelatedUiState(isLoading = true)
    relatedJob = viewModelScope.launch {
        val query = relatedQuery(details.title).ifBlank { details.channelName }
        val channelId = details.channelId
        val (similarRaw, channelRaw) = coroutineScope {
            val similarJob = async {
                safeItems { backend.search(query, null).items }
            }
            val channelJob = async {
                safeItems {
                    channelId?.let { backend.getChannelVideos(it).items }.orEmpty()
                }
            }
            similarJob.await() to channelJob.await()
        }

        val similar = similarRaw
            .filter { it.id != id && !it.isLive }
            .distinctBy { it.id }
            .map { it.copy(reason = "Serupa dengan video ini") }
        val fromChannel = channelRaw
            .filter { it.id != id && !it.isLive }
            .distinctBy { it.id }
            .take(15)
            .map { it.copy(reason = "Dari kanal ini") }
        val all = mixRelated(similar, fromChannel)

        _related.value = RelatedUiState(
            isLoading = false,
            all = all,
            similar = similar.take(30),
            fromChannel = fromChannel,
            error = if (all.isEmpty()) "Rekomendasi tidak dapat dimuat." else null,
        )
    }
}

private suspend fun safeItems(block: suspend () -> List<VideoItem>): List<VideoItem> =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PlaybackDiagnostics.log("Rekomendasi gagal: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

/** Judul bersih untuk pencarian: tanpa emoji/simbol, maksimal 6 kata. */
private fun relatedQuery(title: String): String =
    title.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(6)
        .joinToString(" ")

/** Pola 2 serupa : 1 kanal, duplikat dibuang, maksimal 30 item. */
private fun mixRelated(similar: List<VideoItem>, channel: List<VideoItem>): List<VideoItem> {
    val out = ArrayList<VideoItem>()
    val seen = HashSet<String>()
    fun add(item: VideoItem?) {
        if (item != null && seen.add(item.id)) out += item
    }
    for (i in 0 until maxOf((similar.size + 1) / 2, channel.size)) {
        add(similar.getOrNull(i * 2))
        add(similar.getOrNull(i * 2 + 1))
        add(channel.getOrNull(i))
    }
    return out.take(30)
}

    override fun onCleared() {
        // Tripwire: if loads die with "Parent job is Cancelling" because this
        // ViewModel is being cleared mid-flight, this line brackets them in
        // the diagnostics ring (field report 2026-11-10).
        PlaybackDiagnostics.log("PlayerViewModel dibersihkan (onCleared)")
        playbackManager.onClassicChainDied = null
        playbackManager.onArusChainDied = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _controllerPlayer.value = null
        super.onCleared()
    }

    private fun load() {
        val id = videoId ?: return
        loadJob?.cancel()
        relatedJob?.cancel()
        _related.value = RelatedUiState(isLoading = true)
        val attempt = ++loadAttempts
        arusAutoAttemptedFor = null
        classicRescueAttemptedFor = null
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
                lastStreams = streams
                lastDetails = details

                val sources = listOfNotNull(
                    streams.dashManifestContent?.let { "DASH adaptif" },
                    streams.dashManifestUrl?.let { "DASH" },
                    streams.hlsUrl?.let { "HLS" },
                    streams.progressiveUrl?.let { "progresif" },
                )
                PlaybackDiagnostics.log(
                    "Stream tersedia: ${sources.joinToString(", ").ifEmpty { "tidak ada" }}",
                )

                PlaybackService.start(application)
                playbackManager.configure(streams.playbackUserAgent)
                // Merged paths: Arus opens VOD videos (its conversation
                // is never cut by the token wall that kills classic
                // URLs); the classic chain — already fetched above —
                // takes over instantly when Arus cannot open, and the
                // two rescue hooks cover deaths after the open.
                val arusFirst = PlaybackWallPolicy.primaryPath(
                    isLive = details.isLive,
                    arusSessionBroken = playbackManager.arusSessionBroken,
                ) == PlaybackWallPolicy.PrimaryPath.ARUS_FIRST
                var playingViaArus = false
                if (arusFirst) {
                    playingViaArus = startArusNow(id, details)
                    if (!playingViaArus) {
                        PlaybackDiagnostics.log(
                            "Arus tidak dapat membuka video ini — jalur klasik mengambil alih",
                        )
                    }
                }
                if (!playingViaArus) {
                    // The empty-classic verdict only applies once Arus
                    // has also had its chance to open the video.
                    if (sources.isEmpty()) {
                        _uiState.value = PlayerUiState.Error(
                            "Video ini tidak menyediakan aliran yang dapat diputar.",
                        )
                        return@launch
                    }
                    playbackManager.play(
                        streams,
                        details.title,
                        details.channelName,
                        details.thumbnailUrl,
                    )
                }

                // Jatah: charge the estimate once playback starts, then
                // judge the month WITH this video included — the warning
                // must reflect the state the user is now in, not before.
                val estimate = streams.estimatedSizeBytes
                if (estimate != null) {
                    library.charge(id, details.title, estimate)
                }
                // Mesin Selera: an open is the strongest local signal
                // Bunny gets — record which channel earned it.
                library.recordChannelWatch(details.channelName)
                val quota = library.quotaBytes.first()
                val used = library.monthUsageBytes().first()
                val exceeded = quota != null && used > quota
                if (exceeded) {
                    PlaybackDiagnostics.log("Jatah terlampaui bulan ini")
                }
                _uiState.value = PlayerUiState.Ready(
                    details = details,
                    sourceNote = if (playingViaArus) {
                        "Sumber: SABR (Arus)"
                    } else {
                        "Sumber: ${sources.first()}"
                    },
                    estimatedSizeBytes = estimate,
                    jatahExceeded = exceeded,
                    monthUsedBytes = used,
                    quotaBytes = quota,
                )
                loadRelated(id, details)
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
                // Fase 4 telemetry: load failures persist on-device so a
                // refusal pattern (e.g. a tightening bot gate) stays
                // visible across restarts, not only in this session's ring.
                PlaybackFailureLog.record(
                    application,
                    stage = "muat",
                    subject = id,
                    detail = "${e.javaClass.simpleName}: ${e.message}",
                )
                _uiState.value = PlayerUiState.Error(
                    e.message ?: "Video tidak dapat disiapkan.",
                )
            }
        }
    }
}
