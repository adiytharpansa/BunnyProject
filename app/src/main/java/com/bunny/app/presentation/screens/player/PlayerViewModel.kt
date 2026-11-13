package com.bunny.app.presentation.screens.player

import android.app.Application
import android.content.ComponentName
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bunny.app.bug.BugRecorder
import com.bunny.app.data.DownloadEntity
import com.bunny.app.data.DownloadManager
import com.bunny.app.data.LibraryRepository
import com.bunny.app.data.RoadMemoryStore
import com.bunny.app.data.WatchPositionStore
import com.bunny.app.data.TasteRepository
import java.io.File
import com.bunny.app.playback.PlaybackManager
import com.bunny.app.playback.PlaybackService
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.PlaybackStreams
import com.bunny.core.model.VideoComment
import com.bunny.engine.lontar.LontarEngine
import com.bunny.engine.lontar.sabr.SabrPlaybackCoordinator
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
        /** Mechanism 6: the Jatah height cap the SABR road opened
         *  under, when one applied — stated in the player. */
        val sabrCapHeightPx: Int? = null,
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
    /** Ulangi video ini (paket pemutar 2026-11-12). */
    val loop: Boolean = false,
    /** Timer tidur aktif, dalam menit; null berarti mati. */
    val sleepMinutes: Int? = null,
)

/** Balasan satu utas komentar, dimuat saat utasnya dibuka. */
data class RepliesUiState(
    val isLoading: Boolean = false,
    val items: List<VideoComment> = emptyList(),
    val nextPage: String? = null,
    val isLoadingMore: Boolean = false,
    val expanded: Boolean = false,
    /** Pemuatan pertama selesai tanpa hasil — tampilkan pesan jujur. */
    val failed: Boolean = false,
)

/** Komentar asli di bawah pemutar + foto pemilik dari halaman tonton. */
data class CommentsUiState(
    val isLoading: Boolean = true,
    val items: List<VideoComment> = emptyList(),
    val nextPage: String? = null,
    val isLoadingMore: Boolean = false,
    /** The watch page's owner photo for the channel row. */
    val ownerAvatarUrl: String? = null,
    /** Per-thread reply state, keyed by comment id. */
    val replies: Map<String, RepliesUiState> = emptyMap(),
)

/** Rekomendasi di bawah pemutar: tiga tampilan dari satu sumber data. */
data class RelatedUiState(
    val isLoading: Boolean = true,
    /** Daftar utama (tab "Semua"): Berikutnya dari server YouTube. */
    val all: List<VideoItem> = emptyList(),
    /** Tab "Terkait": daftar server di luar kanal ini. */
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
    private val playbackManager: PlaybackManager,
    private val library: LibraryRepository,
    private val downloadManager: DownloadManager,
    private val positions: WatchPositionStore,
    private val sabrCoordinator: SabrPlaybackCoordinator,
    private val engine: LontarEngine,
    private val roadMemory: RoadMemoryStore,
    private val taste: TasteRepository,
) : ViewModel() {

    init {
        // Fase 8 Langkah 6, mechanism 10: the road memory hears
        // the engine's hard refusals (no SABR offer in the player
        // response, or a token demanded mid-stream) as they
        // happen — including refusals inside a playback this
        // ViewModel opened, which is exactly when they occur.
        engine.onSabrHardRefusal = { videoId -> roadMemory.recordHard(videoId) }
    }

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


    /** Video yang diminta layar; menjaga open() tidak mengulang video asal setelah pindah. */
    private var requestedId: String? = null

    /** Video yang sudah diputar sebelum yang sekarang (untuk ⏮). */
    private val history = ArrayDeque<String>()

    /** Semua video yang sudah diputar di sesi ini (supaya ⏭ tidak mengulang). */
    private val playedIds = LinkedHashSet<String>()

    /** ⏮ / ⏭ dari notifikasi, layar kunci, headset, dan kontrol pemutar. */
    private val queueHandler = object : PlaybackManager.QueueHandler {
        override fun onSkipNext() = skipToNext(auto = false)
        override fun onSkipPrevious() = skipToPrevious()
    }

    /** Putar otomatis: video selesai -> rekomendasi berikutnya. */
    private val endListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED &&
                _uiState.value is PlayerUiState.Ready
            ) {
                // A finished video has no resume position.
                videoId?.let { positions.clear(it) }
                // Mesin Selera: menonton sampai habis adalah sinyal terkuat,
                // dan tontonan yang selesai layak ditanyai (bila kebijakan survei mengizinkan).
                videoId?.let { finishedId ->
                    viewModelScope.launch {
                        taste.markCompleted(finishedId)
                        taste.maybeQueueSurvey(finishedId)
                    }
                }
                skipToNext(auto = true)
            }
        }
    }

    init {
        connectController()
        playbackManager.queueHandler = queueHandler
        playbackManager.onSuspectedGvs = { persona -> onSuspectedGvs(persona) }
        playbackManager.onTerminalFailure = { position -> onTerminalFailure(position) }
        playbackManager.player.addListener(endListener)
        // Resume bookkeeping: shelve the position every five
        // seconds of an open video (main thread — the scope's
        // default; the player refuses other threads).
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                saveCurrentPosition()
                trackWatchTime(5_000)
            }
        }
    }

    // ------------------------------------- posisi terakhir (resume)

    private fun saveCurrentPosition() {
        val id = videoId ?: return
        val player = playbackManager.player
        if (player.mediaItemCount == 0) return
        val duration = player.duration
        if (duration <= 0) return
        positions.save(id, player.currentPosition, duration)
    }

    /**
     * Mesin Selera: setiap detak lima detik yang benar-benar sedang memutar
     * ditambahkan ke waktu tonton video ini. Waktu putar kumulatif (bukan
     * posisi) dipakai supaya melompat ke akhir tidak menggelembungkan
     * kepuasan. Durasi tak diketahui (siaran langsung) dicatat sebagai 0.
     */
    private fun trackWatchTime(elapsedMs: Long) {
        val id = videoId ?: return
        val player = playbackManager.player
        if (!player.isPlaying) return
        val details = (_uiState.value as? PlayerUiState.Ready)?.details ?: return
        if (details.id != id) return
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        viewModelScope.launch {
            taste.addWatchTime(
                videoId = id,
                channelId = details.channelId,
                channelName = details.channelName,
                title = details.title,
                elapsedMs = elapsedMs,
                durationMs = duration,
            )
        }
    }

    /**
     * Seeks a freshly opened video to its shelved position. The
     * player's duration can lag the play call, so the seek waits
     * (briefly) for a known duration before judging the position;
     * a position within the last fifteen seconds counts as
     * finished and is not resumed.
     */
    private fun applyResume(id: String) {
        val stored = positions.get(id)
        if (stored < 10_000) return
        viewModelScope.launch {
            val player = playbackManager.player
            var waitedMs = 0L
            while (waitedMs < 3_000 && videoId == id) {
                val duration = player.duration
                if (duration > 0) {
                    if (stored < duration - 15_000) {
                        player.seekTo(stored)
                    } else {
                        positions.clear(id)
                    }
                    return@launch
                }
                delay(200)
                waitedMs += 200
            }
        }
    }

    // --------------------------------------- ulangi & timer tidur

    private var sleepJob: Job? = null

    fun toggleLoop() {
        val player = playbackManager.player
        val enabled = player.repeatMode != Player.REPEAT_MODE_ONE
        player.repeatMode =
            if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _options.update { it.copy(loop = enabled) }
    }

    /** Timer tidur dengan redup suara di 10 detik terakhir (paket §10). */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        // Membatalkan di tengah redup suara (termasuk lewat stopPlayback())
        // tidak boleh meninggalkan volume rendah untuk video berikutnya.
        playbackManager.player.volume = NORMAL_VOLUME
        _options.update { it.copy(sleepMinutes = minutes) }
        if (minutes == null) return
        val player = playbackManager.player
        sleepJob = viewModelScope.launch {
            try {
                delay((minutes * 60_000L - 10_000L).coerceAtLeast(0))
                for (step in 10 downTo 1) {
                    player.volume = NORMAL_VOLUME * step / 10f
                    delay(1_000)
                }
                player.pause()
                _options.update { it.copy(sleepMinutes = null) }
            } finally {
                player.volume = NORMAL_VOLUME
            }
        }
    }

    // ------------------------------------------------- gestur

    fun seekBy(deltaMs: Long) {
        val player = playbackManager.player
        if (player.mediaItemCount == 0 || !player.isCurrentMediaItemSeekable) return
        val duration = player.duration
        val target = player.currentPosition + deltaMs
        // Durasi siaran langsung adalah C.TIME_UNSET (negatif): jangan dijadikan
        // batas atas, kalau tidak setiap lompatan berakhir di posisi 0.
        val clamped = if (duration == C.TIME_UNSET || duration <= 0L) {
            target.coerceAtLeast(0L)
        } else {
            target.coerceIn(0L, duration)
        }
        player.seekTo(clamped)
    }

    fun seekTo(positionMs: Long) {
        playbackManager.player.seekTo(positionMs)
    }

    private var speedBeforeHold: Float? = null

    /** Tahan = 2x sementara; lepas mengembalikan kecepatan asal. */
    fun setHoldSpeed(holding: Boolean) {
        val player = playbackManager.player
        if (holding) {
            if (speedBeforeHold == null) {
                speedBeforeHold = player.playbackParameters.speed
                player.setPlaybackSpeed(2.0f)
            }
        } else {
            speedBeforeHold?.let { player.setPlaybackSpeed(it) }
            speedBeforeHold = null
        }
    }

    // -------------------------------------------------- pemulihan putar

    /** The video the one allowed in-place recovery was spent on. */
    @Volatile
    private var recoveryUsedFor: String? = null

    /** The video the one allowed floor attempt was spent on. */
    @Volatile
    private var floorUsedFor: String? = null

    /**
     * Pemulihan Putar (Lontar Hidup §8): a mid-stream GVS refusal is
     * answered by demoting the persona that earned it, re-resolving the
     * SAME video (the ladder now prefers another persona), and
     * continuing from the saved position. Exactly one attempt per
     * video open — a second refusal is the user's error to see, not a
     * loop to hide it in.
     */
    private fun onSuspectedGvs(persona: String) {
        val id = videoId ?: return
        if (recoveryUsedFor == id) {
            // The recovery already spent its one attempt on this
            // video; what remains is the floor (Lantai Penyelamat,
            // pemicu B — RANCANGAN-LANTAI-PENYELAMAT Bagian 3.2).
            val details = (_uiState.value as? PlayerUiState.Ready)?.details
                ?: return
            val position = playbackManager.player.currentPosition
            viewModelScope.launch { attemptFloor(id, details, position) }
            return
        }
        recoveryUsedFor = id
        val position = playbackManager.player.currentPosition
        viewModelScope.launch {
            val streams = try {
                backend.getRefreshedPlaybackStreams(id, persona)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (streams == null) {
                // The re-resolve itself failed — the ladder is
                // exhausted, so the floor gets its attempt from the
                // saved position (pemicu B).
                val readyDetails =
                    (_uiState.value as? PlayerUiState.Ready)?.details
                if (readyDetails != null && videoId == id) {
                    attemptFloor(id, readyDetails, position)
                }
                return@launch
            }
            if (videoId != id) return@launch
            val details = (_uiState.value as? PlayerUiState.Ready)?.details
                ?: return@launch
            playbackManager.configure(streams.playbackUserAgent)
            playbackManager.play(
                streams,
                details.title,
                details.channelName,
                details.thumbnailUrl,
            )
            if (position > 0) {
                playbackManager.player.seekTo(position)
            }
        }
    }

    // ------------------------------------------------- lantai penyelamat

    /**
     * Lantai Penyelamat (RANCANGAN-LANTAI-PENYELAMAT Bagian 3.2): the
     * one floor attempt for this video open — asks the engine for the
     * ANDROID progressive file and plays it, continuing from
     * [positionMs]. Returns the floor streams when playing, null when
     * the floor holds nothing (the caller then surfaces the original
     * failure, untouched). The attempt is recorded either way: a
     * rescue that fired — or was asked for and found empty — must be
     * visible in the on-device telemetry, never silent.
     */
    private suspend fun attemptFloor(
        id: String,
        details: VideoDetails,
        positionMs: Long,
    ): PlaybackStreams? {
        if (floorUsedFor == id) return null
        floorUsedFor = id
        val floor = try {
            backend.getFloorStreams(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (floor == null || videoId != id) {
            return null
        }
        PlaybackService.start(application)
        playbackManager.configure(floor.playbackUserAgent)
        val floorStarted = playbackManager.play(
            floor,
            details.title,
            details.channelName,
            details.thumbnailUrl,
        )
        if (!floorStarted) return null
        if (positionMs > 0) {
            playbackManager.player.seekTo(positionMs)
        }
        return floor
    }

    /**
     * The tail of a floor-rescued OPEN (pemicu A): the open is
     * charged exactly once, on the floor's real file size (Bagian
     * 3.4 — the floor is a continuation of the same open, never a
     * second charge), then the Ready state names the floor as the
     * source, honestly.
     */
    private suspend fun finishOpenWithFloor(
        id: String,
        details: VideoDetails,
        floor: PlaybackStreams,
    ) {
        val estimate = floor.estimatedSizeBytes
        if (estimate != null) {
            library.charge(id, details.title, estimate)
        }
        library.recordChannelWatch(details.channelName)
        taste.recordOpen(id, details.channelId, details.channelName, details.title)
        val quota = library.quotaBytes.first()
        val used = library.monthUsageBytes().first()
        _uiState.value = PlayerUiState.Ready(
            details = details,
            sourceNote = "Sumber: Lantai Penyelamat (ANDROID · 360p)",
            estimatedSizeBytes = estimate,
            jatahExceeded = quota != null && used > quota,
            monthUsedBytes = used,
            quotaBytes = quota,
        )
        applyResume(id)
        loadRelated(id, details)
        loadComments(id)
    }

    /**
     * Pemicu B: PlaybackManager's source chain ended in a terminal
     * error that is not the GVS signature — every classic road is
     * exhausted, so the floor gets its one attempt from the last
     * position.
     */
    private fun onTerminalFailure(positionMs: Long) {
        val id = videoId ?: return
        val details = (_uiState.value as? PlayerUiState.Ready)?.details
            ?: return
        viewModelScope.launch { attemptFloor(id, details, positionMs) }
    }

    /**
     * Bor Lantai (Bagian 4): the diagnostics drill — forces the
     * currently open video through the floor door so the rescue can
     * be proven on demand, without waiting for every other road to
     * fail. Bypasses the once-per-open guard on purpose; the real
     * triggers keep theirs.
     */
    fun playViaFloor() {
        val id = videoId ?: return
        val details = (_uiState.value as? PlayerUiState.Ready)?.details
            ?: return
        val position = playbackManager.player.currentPosition
        floorUsedFor = null
        viewModelScope.launch {
            attemptFloor(id, details, position)
        }
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
                        val remote = future.get()
                        _controllerPlayer.value = remote
                        // Kecepatan awal + setiap perubahan (termasuk dari
                        // menu roda gigi pemutar) masuk ke tombol "1x".
                        _options.update { it.copy(speed = remote.playbackParameters.speed) }
                        remote.addListener(object : Player.Listener {
                            override fun onPlaybackParametersChanged(
                                playbackParameters: PlaybackParameters,
                            ) {
                                _options.update { it.copy(speed = playbackParameters.speed) }
                            }
                        })
                    } catch (e: Exception) {
                    }
                },
                ContextCompat.getMainExecutor(application),
            )
        } catch (e: Exception) {
        }
    }

    private var videoId: String? = null
    private var loadJob: Job? = null
    private var loadAttempts = 0

    fun open(videoId: String) {
        if (requestedId == videoId && _uiState.value !is PlayerUiState.Error) return
        requestedId = videoId
        recoveryUsedFor = null
        floorUsedFor = null
        history.clear()
        playedIds.clear()
        this.videoId = videoId
        load()
    }

    fun retry() {
        load()
    }

    fun stopPlayback() {
        saveCurrentPosition()
        // Pemirsa meninggalkan video: tontonan bermakna boleh ditanyai di Beranda.
        videoId?.let { leftId ->
            viewModelScope.launch { taste.maybeQueueSurvey(leftId) }
        }
        setSleepTimer(null)
        requestedId = null
        playbackManager.stop()
    }

    // ------------------------------------------- pindah video (⏮ ⏭)

    /** ⏭ dan putar otomatis: rekomendasi pertama yang belum diputar. */
    private fun skipToNext(auto: Boolean) {
        val current = videoId ?: return
        val next = _related.value.all
            .firstOrNull { it.id !in playedIds && !it.isLive }
            ?.id
        if (next == null) {
            if (!auto) {
                Toast.makeText(
                    application,
                    "Belum ada video berikutnya",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return
        }
        history.addLast(current)
        if (history.size > MAX_HISTORY) history.removeFirst()
        playbackManager.holdForTransition()
        videoId = next
        load()
    }

    /** ⏮: video yang tadi diputar; tanpa riwayat, ulang dari awal. */
    private fun skipToPrevious() {
        val previous = history.removeLastOrNull()
        if (previous == null) {
            playbackManager.player.seekTo(0L)
            return
        }
        playbackManager.holdForTransition()
        videoId = previous
        load()
    }

    /** Terbit's source: local subscriptions, toggled from this screen. */
    fun subscriptionState(channelId: String): Flow<Boolean> =
        library.isSubscribed(channelId)

    fun toggleSubscription(channelId: String, channelName: String, subscribed: Boolean) {
        viewModelScope.launch {
            if (subscribed) {
                library.unsubscribe(channelId)
            } else {
                // The watch page's real owner photo rides along, so
                // the Kanal list can show it instead of a stand-in.
                library.subscribe(
                    channelId,
                    channelName,
                    avatarUrl = _comments.value.ownerAvatarUrl,
                )
            }
        }
    }

    // ------------------------------------------------ panen bertingkat

    /** This video's download record, if any (drives the Unduh pill). */
    fun downloadState(id: String): Flow<DownloadEntity?> =
        downloadManager.observe(id)

    fun enqueueDownload() {
        videoId?.let { downloadManager.enqueue(it) }
    }

    fun deleteDownload() {
        videoId?.let { downloadManager.delete(it) }
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
     *
     * Sumbernya SATU: daftar Berikutnya dari server YouTube sendiri
     * (algoritma mereka, urutan mereka — tidak diacak ulang di sini).
     * TANPA CADANGAN lokal (aturan pengguna 2026-11-11: semua yang
     * tampil harus data asli, jangan ada cadangan) — server kosong
     * berarti daftar kosong dan keadaan kosong yang tampil.
     */
    private fun loadRelated(id: String, details: VideoDetails) {
        relatedJob?.cancel()
        _related.value = RelatedUiState(isLoading = true)
        relatedJob = viewModelScope.launch {
            val channelId = details.channelId
            val (serverRaw, channelRaw) = coroutineScope {
                val serverJob = async {
                    safeItems { backend.getRelatedVideos(id) }
                }
                val channelJob = async {
                    safeItems {
                        channelId?.let { backend.getChannelVideos(it).items }.orEmpty()
                    }
                }
                serverJob.await() to channelJob.await()
            }

            val fromChannel = channelRaw
                .filter { it.id != id && !it.isLive }
                .distinctBy { it.id }
                .take(15)
                .map { it.copy(reason = "Dari kanal ini") }

            val server = serverRaw
                .filter { it.id != id && !it.isLive }
                .distinctBy { it.id }
                .map { it.copy(reason = "Saran YouTube") }
            _related.value = RelatedUiState(
                isLoading = false,
                all = server,
                // Tab "Terkait": daftar server tanpa video kanal ini
                // (yang dari kanal ini tempatnya di tab kanalnya).
                similar = server.filter {
                    it.channelId == null || it.channelId != channelId
                },
                fromChannel = fromChannel,
                error = if (server.isEmpty()) {
                    "Rekomendasi tidak dapat dimuat."
                } else {
                    null
                },
            )
            // Pemanasan Antrean (disetujui 2026-11-12): the queue's
            // head — the same candidate ⏭/autoplay will pick — gets
            // its player response warmed now, metadata only, so the
            // switch starts warm instead of cold.
            _related.value.all
                .firstOrNull { it.id !in playedIds && !it.isLive }
                ?.let {
                    sabrCoordinator.warm(it.id)
                    // Anti-buffering M13: on an unmetered link the
                    // head's spool itself starts filling too, so
                    // the switch opens with media, not metadata
                    // alone (gating + dedupe live in the coordinator).
                    sabrCoordinator.prewarmMedia(it.id)
                }
        }
    }

    // ------------------------------------------------ komentar asli

    private val _comments = MutableStateFlow(CommentsUiState())
    val comments: StateFlow<CommentsUiState> = _comments.asStateFlow()
    private var commentsJob: Job? = null

    /**
     * Komentar adalah hiasan di bawah pemutar, pola yang sama dengan
     * rekomendasi: job terpisah setelah video siap; kegagalannya berarti
     * daftar kosong, tidak pernah error pemutar. Halaman pertama juga
     * membawa foto asli pemilik video untuk baris kanal.
     */
    private fun loadComments(id: String) {
        commentsJob?.cancel()
        _comments.value = CommentsUiState(isLoading = true)
        commentsJob = viewModelScope.launch {
            val page = try {
                backend.getComments(id, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _comments.value = CommentsUiState(
                isLoading = false,
                items = page?.items.orEmpty(),
                nextPage = page?.nextPage,
                ownerAvatarUrl = page?.ownerAvatarUrl,
            )
        }
    }

    fun loadMoreComments() {
        val id = videoId ?: return
        val current = _comments.value
        val next = current.nextPage ?: return
        if (current.isLoadingMore || current.isLoading) return
        _comments.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val page = try {
                backend.getComments(id, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _comments.update {
                it.copy(
                    isLoadingMore = false,
                    items = if (page != null) {
                        (it.items + page.items).distinctBy { c -> c.id }
                    } else {
                        it.items
                    },
                    nextPage = page?.nextPage ?: it.nextPage,
                )
            }
        }
    }

    /**
     * Opens or closes one thread's replies. First open loads them
     * through the thread's own continuation token; later opens reuse
     * what is already loaded. A load that returns nothing is shown
     * honestly as unavailable — never filled with anything else.
     */
    fun toggleReplies(comment: VideoComment) {
        val token = comment.repliesContinuation ?: return
        val current = _comments.value.replies[comment.id]
        if (current != null && (current.items.isNotEmpty() || current.failed)) {
            _comments.update {
                it.copy(
                    replies = it.replies + (comment.id to
                        current.copy(expanded = !current.expanded)),
                )
            }
            return
        }
        if (current?.isLoading == true) return
        _comments.update {
            it.copy(
                replies = it.replies + (comment.id to
                    RepliesUiState(isLoading = true, expanded = true)),
            )
        }
        viewModelScope.launch {
            val page = try {
                backend.getCommentReplies(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _comments.update {
                it.copy(
                    replies = it.replies + (comment.id to RepliesUiState(
                        items = page?.items.orEmpty(),
                        nextPage = page?.nextPage,
                        expanded = true,
                        failed = page == null || page.items.isEmpty(),
                    )),
                )
            }
        }
    }

    fun loadMoreReplies(commentId: String) {
        val current = _comments.value.replies[commentId] ?: return
        val next = current.nextPage ?: return
        if (current.isLoadingMore || current.isLoading) return
        _comments.update {
            it.copy(
                replies = it.replies + (commentId to
                    current.copy(isLoadingMore = true)),
            )
        }
        viewModelScope.launch {
            val page = try {
                backend.getCommentReplies(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _comments.update { state ->
                val latest = state.replies[commentId] ?: return@update state
                state.copy(
                    replies = state.replies + (commentId to latest.copy(
                        isLoadingMore = false,
                        items = if (page != null) {
                            (latest.items + page.items).distinctBy { c -> c.id }
                        } else {
                            latest.items
                        },
                        nextPage = page?.nextPage ?: latest.nextPage,
                    )),
                )
            }
        }
    }

    private suspend fun safeItems(block: suspend () -> List<VideoItem>): List<VideoItem> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    override fun onCleared() {
        // Tripwire: if loads die with "Parent job is Cancelling" because this
        // ViewModel is being cleared mid-flight, this line brackets them in
        // the diagnostics ring (field report 2026-11-10).
        if (playbackManager.queueHandler === queueHandler) {
            playbackManager.queueHandler = null
        }
        playbackManager.onSuspectedGvs = null
        playbackManager.onTerminalFailure = null
        playbackManager.player.removeListener(endListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _controllerPlayer.value = null
        super.onCleared()
    }

    private fun load() {
        val id = videoId ?: return
        playedIds += id
        loadJob?.cancel()
        relatedJob?.cancel()
        _related.value = RelatedUiState(isLoading = true)
        val attempt = ++loadAttempts
        _uiState.value = PlayerUiState.Loading
        loadJob = viewModelScope.launch {
            // Hoisted so the catch below can hand the details to a
            // floor attempt (pemicu A) when the streams call is the
            // one that failed.
            var openedDetails: VideoDetails? = null
            try {
                // Panen Bertingkat: a finished local download plays
                // from the device — no network, and no second Jatah
                // charge (its data was already paid when downloading).
                val local = downloadManager.record(id)
                val localAudio = local?.audioPath
                    ?.let { File(it) }?.takeIf { it.exists() }
                val localVideo = local?.videoPath
                    ?.let { File(it) }?.takeIf { it.exists() }
                if (local != null && (localAudio != null || localVideo != null)) {
                    PlaybackService.start(application)
                    playbackManager.playLocal(
                        videoFile = localVideo,
                        audioFile = if (local.videoWithAudio) null else localAudio,
                        title = local.title,
                        channelName = local.channelName,
                    )
                    library.recordChannelWatch(local.channelName)
                    taste.recordOpen(id, local.channelId, local.channelName, local.title)
                    val localDetails = VideoDetails(
                        id = id,
                        title = local.title,
                        description = local.description,
                        channelId = local.channelId,
                        channelName = local.channelName,
                        viewCount = null,
                        likeCount = null,
                        uploadedText = null,
                        thumbnailUrl = local.thumbnailUrl,
                    )
                    val quota = library.quotaBytes.first()
                    val used = library.monthUsageBytes().first()
                    _uiState.value = PlayerUiState.Ready(
                        details = localDetails,
                        sourceNote = "Sumber: unduhan di perangkat",
                        estimatedSizeBytes = null,
                        jatahExceeded = quota != null && used > quota,
                        monthUsedBytes = used,
                        quotaBytes = quota,
                    )
                    loadRelated(id, localDetails)
                    loadComments(id)
                    return@launch
                }
                // Sequential on purpose: both calls share the engine's
                // cached player response, so the second one is free — and
                // sequential calls keep the real error visible instead of
                // letting a sibling coroutine's cancellation mask it (the
                // parallel version surfaced "Parent job is Cancelling" as
                // the user-facing error on 2026-11-10).
                val details = backend.getVideoDetails(id)
                openedDetails = details
                val streams = backend.getPlaybackStreams(id)

                val sources = listOfNotNull(
                    streams.dashManifestContent?.let { "DASH adaptif" },
                    streams.dashManifestUrl?.let { "DASH" },
                    streams.hlsUrl?.let { "HLS" },
                    streams.progressiveUrl?.let { "progresif" },
                )

                // Fase 8 (Anyam), Langkah 3: the SABR road is offered
                // first when the remote config allows it. tryOpen
                // rides the same cached player response fetched above,
                // proves the road with a first exchange, and returns
                // null when the road is not taken — the classic flow
                // below is then exactly what it was before Fase 8.
                //
                // Langkah 5, mechanism 6: the month's remaining
                // Jatah may cap the road's opening quality.
                val capQuota = library.quotaBytes.first()
                val capUsed = library.monthUsageBytes().first()
                val jatahCap = com.bunny.app.playback.JatahCap
                    .heightCapPx(capQuota, capUsed)
                if (jatahCap != null) {
                }
                // Langkah 6, mechanism 10 — the road memory is
                // consulted BEFORE the offer: a video the road
                // refused outright while the memory is fresh skips
                // the doomed attempt entirely, and the classic
                // flow below runs exactly as it would after a
                // failed tryOpen.
                val roadRefused = roadMemory.isHardRefused(id)
                if (roadRefused) {
                }
                // Langkah 7, mechanism 7 — the self-disabling
                // governance is consulted beside the road memory:
                // when this network class's recent SABR verdicts
                // crossed the config threshold, the road has
                // disabled itself locally and the classic flow
                // below runs. The skip is named here, with its
                // evidence — no silent deaths. (The engine's own
                // gate applies the same verdict as a backstop on
                // every other SABR path.)
                val governance = engine.sabrGovernanceVerdict()
                if (governance.disabled) {
                }
                val sabr = if (roadRefused || governance.disabled) {
                    null
                } else {
                    sabrCoordinator.tryOpen(id, jatahCap) { message ->
                    }
                }
                if (sabr != null) {
                    // A road that opened replaces any older
                    // refusal in the memory.
                    roadMemory.recordOk(id)
                }
                if (sources.isEmpty() && sabr == null) {
                    // Lantai Penyelamat, pemicu A: the ladder answered
                    // but holds nothing playable — before the error is
                    // shown, the floor gets its one attempt.
                    val floor = attemptFloor(id, details, 0L)
                    if (floor != null) {
                        finishOpenWithFloor(id, details, floor)
                        return@launch
                    }
                    BugRecorder.reportCritical(
                        source = "Pemutaran",
                        title = "Semua sumber gagal diputar (videoId=$id)",
                    )
                    _uiState.value = PlayerUiState.Error(
                        "Video ini tidak menyediakan aliran yang dapat diputar.",
                    )
                    return@launch
                }
                PlaybackService.start(application)
                playbackManager.configure(streams.playbackUserAgent)
                if (sabr != null) {
                    playbackManager.playWithSabr(
                        sabr,
                        streams,
                        details.title,
                        details.channelName,
                        details.thumbnailUrl,
                    )
                } else {
                    // Fase 8 mechanism 14: on the classic road, the
                    // playback itself fills the spool — a recorder
                    // gated per track by the spool's own rendition
                    // testimony (null when nothing is recordable).
                    val classicRecorder = sabrCoordinator.openClassicRecorder(
                        id,
                        streams.dashRenditions,
                    )
                    if (classicRecorder != null) {
                    }
                    val started = playbackManager.play(
                        streams,
                        details.title,
                        details.channelName,
                        details.thumbnailUrl,
                        classicRecorder,
                    )
                    if (!started) {
                        // Tidak ada sumber yang bisa diputar: lantai
                        // mendapat satu percobaannya, lalu kegagalan
                        // asli tampil sebagai error — bukan Ready
                        // yang berbohong.
                        val floor = attemptFloor(id, details, 0L)
                        if (floor != null) {
                            finishOpenWithFloor(id, details, floor)
                            return@launch
                        }
                        BugRecorder.reportCritical(
                            source = "Pemutaran",
                            title = "Semua sumber gagal diputar (videoId=$id)",
                        )
                        _uiState.value = PlayerUiState.Error(
                            "Video ini tidak menyediakan aliran yang dapat diputar.",
                        )
                        return@launch
                    }
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
                taste.recordOpen(id, details.channelId, details.channelName, details.title)
                val quota = library.quotaBytes.first()
                val used = library.monthUsageBytes().first()
                val exceeded = quota != null && used > quota
                if (exceeded) {
                }
                _uiState.value = PlayerUiState.Ready(
                    details = details,
                    sourceNote = "Sumber: ${sources.first()}",
                    estimatedSizeBytes = estimate,
                    jatahExceeded = exceeded,
                    monthUsedBytes = used,
                    quotaBytes = quota,
                    sabrCapHeightPx = if (sabr != null) jatahCap else null,
                )
                applyResume(id)
                loadRelated(id, details)
                loadComments(id)
            } catch (e: CancellationException) {
                // Cancellation is control flow, not a playback failure: it
                // must never become an error state (its message is a
                // coroutines-internal string users cannot act on).
                throw e
            } catch (e: Exception) {
                // Lantai Penyelamat, pemicu A: the whole ladder
                // refused (playerResponse already walked it twice,
                // session swap included) — before the error reaches
                // the user, the floor gets its one attempt. Only when
                // the details are in hand: the floor plays a video,
                // not an error screen with a guessed title.
                val floorDetails = openedDetails
                if (floorDetails != null) {
                    val floor = attemptFloor(id, floorDetails, 0L)
                    if (floor != null) {
                        finishOpenWithFloor(id, floorDetails, floor)
                        return@launch
                    }
                }
                // Semua jalan menyerah (tangga dua lintasan + lantai):
                // ini kegagalan inti — tawarkan laporan ke pengguna.
                BugRecorder.reportCritical(
                    source = "Pemutaran",
                    title = "Semua sumber gagal diputar (videoId=$id)",
                    error = e,
                )
                _uiState.value = PlayerUiState.Error(
                    e.message ?: "Video tidak dapat disiapkan.",
                )
            }
        }
    }

    private companion object {
        const val MAX_HISTORY = 20
        const val NORMAL_VOLUME = 1.0f
    }
}
