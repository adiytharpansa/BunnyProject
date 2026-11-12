package com.bunny.app.presentation.screens.player

import android.app.Application
import android.content.ComponentName
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bunny.app.data.DownloadEntity
import com.bunny.app.data.DownloadManager
import com.bunny.app.data.LibraryRepository
import com.bunny.app.data.WatchPositionStore
import java.io.File
import com.bunny.app.playback.PlaybackDiagnostics
import com.bunny.app.playback.PlaybackFailureLog
import com.bunny.app.playback.PlaybackManager
import com.bunny.app.playback.PlaybackService
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.VideoComment
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
                PlaybackDiagnostics.log("Video selesai: putar otomatis berikutnya")
                skipToNext(auto = true)
            }
        }
    }

    init {
        connectController()
        playbackManager.queueHandler = queueHandler
        playbackManager.onSuspectedGvs = { persona -> onSuspectedGvs(persona) }
        playbackManager.player.addListener(endListener)
        // Resume bookkeeping: shelve the position every five
        // seconds of an open video (main thread — the scope's
        // default; the player refuses other threads).
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                saveCurrentPosition()
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
                        PlaybackDiagnostics.log(
                            "Melanjutkan dari posisi ${stored}ms",
                        )
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
        _options.update { it.copy(sleepMinutes = minutes) }
        if (minutes == null) return
        val player = playbackManager.player
        sleepJob = viewModelScope.launch {
            delay((minutes * 60_000L - 10_000L).coerceAtLeast(0))
            val original = player.volume
            for (step in 10 downTo 1) {
                player.volume = original * step / 10f
                delay(1_000)
            }
            player.pause()
            player.volume = original
            _options.update { it.copy(sleepMinutes = null) }
        }
    }

    // ------------------------------------------------- gestur

    fun seekBy(deltaMs: Long) {
        val player = playbackManager.player
        val duration = player.duration.coerceAtLeast(0)
        player.seekTo(
            (player.currentPosition + deltaMs).coerceIn(0, duration),
        )
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
        if (recoveryUsedFor == id) return
        recoveryUsedFor = id
        val position = playbackManager.player.currentPosition
        PlaybackDiagnostics.log(
            "Pemulihan putar: persona $persona diduga tergerbang GVS " +
                "pada posisi ${position}ms — menyusun ulang aliran",
        )
        viewModelScope.launch {
            val streams = try {
                backend.getRefreshedPlaybackStreams(id, persona)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (streams == null || videoId != id) return@launch
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
            PlaybackDiagnostics.log(
                "Pemulihan putar selesai: aliran baru via " +
                    (streams.personaName ?: "persona lain") +
                    ", posisi dilanjutkan",
            )
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
        if (requestedId == videoId && _uiState.value !is PlayerUiState.Error) return
        requestedId = videoId
        recoveryUsedFor = null
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
            PlaybackDiagnostics.log("Berikutnya: belum ada rekomendasi")
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
        PlaybackDiagnostics.log("Berikutnya: $next")
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
        PlaybackDiagnostics.log("Sebelumnya: $previous")
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
                ?.let { sabrCoordinator.warm(it.id) }
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
            PlaybackDiagnostics.log("Rekomendasi gagal: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }

    override fun onCleared() {
        // Tripwire: if loads die with "Parent job is Cancelling" because this
        // ViewModel is being cleared mid-flight, this line brackets them in
        // the diagnostics ring (field report 2026-11-10).
        PlaybackDiagnostics.log("PlayerViewModel dibersihkan (onCleared)")
        if (playbackManager.queueHandler === queueHandler) {
            playbackManager.queueHandler = null
        }
        playbackManager.onSuspectedGvs = null
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
        PlaybackDiagnostics.log("Membuka video $id via backend ${backend.id} (percobaan $attempt)")
        loadJob = viewModelScope.launch {
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
                // Tangga Adaptif, terbuka untuk diaudit (Lontar Hidup
                // §6.3): persona apa yang dipilih, dan atas bukti apa.
                backend.ladderDescription()
                    .takeIf { it.isNotEmpty() }
                    ?.let { PlaybackDiagnostics.log("Tangga mesin: $it") }

                // Fase 8 (Anyam), Langkah 3: the SABR road is offered
                // first when the remote config allows it. tryOpen
                // rides the same cached player response fetched above,
                // proves the road with a first exchange, and returns
                // null when the road is not taken — the classic flow
                // below is then exactly what it was before Fase 8.
                val sabr = sabrCoordinator.tryOpen(id) { message ->
                    PlaybackDiagnostics.log(message)
                }
                if (sabr != null) {
                    PlaybackDiagnostics.log(
                        "Jalan SABR (Anyam) terbuka: gulungan " +
                            "video itag ${sabr.servedVideoItag}, " +
                            "audio itag ${sabr.servedAudioItag}",
                    )
                }
                if (sources.isEmpty() && sabr == null) {
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
                    sourceNote = "Sumber: ${sources.first()}",
                    estimatedSizeBytes = estimate,
                    jatahExceeded = exceeded,
                    monthUsedBytes = used,
                    quotaBytes = quota,
                )
                applyResume(id)
                loadRelated(id, details)
                loadComments(id)
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

    private companion object {
        const val MAX_HISTORY = 20
    }
}
