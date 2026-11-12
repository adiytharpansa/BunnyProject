package com.bunny.engine.lontar.sabr

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.bunny.engine.lontar.LontarEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The SABR transport over OkHttp, sending exactly the headers the
 * proving probe sent: protobuf content type, the UMP accept type,
 * identity encoding (no transparent decompression of a byte stream
 * whose lengths are the protocol), and the issuing persona's agent.
 */
class OkHttpSabrTransport(
    private val client: OkHttpClient,
    private val userAgent: String,
) : SabrTransport {
    override suspend fun post(url: String, body: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/x-protobuf".toMediaType()))
                .header("Accept", "application/vnd.yt-ump")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", userAgent)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SabrHttpException(response.code)
                response.body?.bytes() ?: ByteArray(0)
            }
        }
}

/**
 * One video's SABR playback, owned by the coordinator and lent to
 * the player: the session + pump live here (never in a DataSource —
 * Media3 re-opens those per seek), filling [spool] while
 * [dataSourceFactory] serves the player from it.
 *
 * The drive loop is the probe's discipline, continued: RELOAD earns
 * a fresh /player response and a new session over the SAME spool
 * (state rehydrated from the manifests, mechanism 18), at most
 * [MAX_RELOADS] times; every other terminal outcome kills the road —
 * readers throw, the player falls through to the classic chain, and
 * the spool keeps every byte for the next open.
 */
@UnstableApi
class SabrPlayback internal constructor(
    val openData: SabrOpenData,
    val spool: VideoSpool,
    val roadState: SpoolRoadState,
    private val engine: LontarEngine,
    private val scope: CoroutineScope,
    /** Mechanism 4: the hedged-start race budget in bytes. */
    val raceBudgetBytes: Long = 4_194_304L,
    /** Mechanisms 3 + 22: the pump's lead cap over the playhead. */
    val leadCapMs: Long = Long.MAX_VALUE,
) {
    val videoId: String get() = openData.videoId

    /** The itags the player must address — frozen once init has
     * arrived and the server's own pins were adopted. */
    @Volatile
    var servedAudioItag: Int = openData.audioPin.itag
        private set

    @Volatile
    var servedVideoItag: Int = openData.videoPin.itag
        private set

    /**
     * The player's current position, fed by the app while this
     * handle feeds the player: the pump measures its spool lead
     * against it and stays hungry while the lead is thin.
     */
    @Volatile
    var playerPositionMs: Long = 0

    val dataSourceFactory: DataSource.Factory =
        DataSource.Factory {
            SabrDataSource(spool, roadState) { itag, position, frontier ->
                onEvent?.invoke(
                    "SABR baca: itag $itag menunggu data di posisi " +
                        "$position (gulungan baru sampai $frontier)",
                )
            }
        }

    /**
     * Optional observer for road events (the app wires this to its
     * playback diagnostics ring, so a stall always leaves written
     * evidence). Set right after the handle is created.
     */
    @Volatile
    var onEvent: ((String) -> Unit)? = null

    private val readySignal = CompletableDeferred<Boolean>()

    /** The session currently being driven; read by [statusLine]. */
    @Volatile
    private var activeSession: SabrSession? = null

    @Volatile
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch { drive() }
    }

    /**
     * True once the first SABR response has delivered init — the
     * moment the road has proven it can serve. False (or timeout)
     * means the caller should simply play the classic road; nothing
     * about the attempt was user-visible.
     */
    suspend fun awaitReady(timeoutMs: Long = READY_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { readySignal.await() } ?: false

    fun close() {
        job?.cancel()
        job = null
        roadState.kill("pemutaran diganti")
        spool.close()
    }

    private suspend fun drive() {
        var session = openData.newSession()
        activeSession = session
        // Fase 8 mechanism 12 — cold spool: whatever a previous
        // session rolled for this video is still on disk. Hydrate
        // the fresh session from it BEFORE the first request; when
        // both slots come back wire-proven with a playable head,
        // the road is ready from disk immediately and the pump's
        // first request already asks only for the frontier onward.
        session.restoreFrom(spool)
        if (session.initReceived) {
            val provenAudio = session.wireProvenFormat(audio = true)
            val provenVideo = session.wireProvenFormat(audio = false)
            if (provenAudio != null && provenVideo != null &&
                spool.track(provenAudio.itag).hasPlayableHead() &&
                spool.track(provenVideo.itag).hasPlayableHead()
            ) {
                servedAudioItag = provenAudio.itag
                servedVideoItag = provenVideo.itag
                for (format in listOf(provenAudio, provenVideo)) {
                    val track = session.trackFor(format.itag)
                    if (track.isComplete) roadState.markComplete(format.itag)
                }
                readySignal.complete(true)
                onEvent?.invoke(
                    "SABR: mulai dari gulungan dingin " +
                        "(video itag ${provenVideo.itag}, " +
                        "audio itag ${provenAudio.itag})",
                )
            }
        }
        var reloads = 0
        var lastLoggedSpoolSecond = 0L
        try {
            while (true) {
                val pump = SabrPump(
                    session = session,
                    spool = spool,
                    transport = engine.createSabrTransport(openData.userAgent),
                    bandwidth = EwmaBandwidthMeter(),
                    onProgress = {
                        roadState.pulse()
                        if (!readySignal.isCompleted && session.initReceived) {
                            // Ready freezes the itags the player will
                            // address — so they are taken from the
                            // WIRE (init segments actually received),
                            // never from paper pins: a paper-only
                            // format's spool track stays empty and the
                            // player would wait on it forever.
                            val provenAudio = session.wireProvenFormat(audio = true)
                            val provenVideo = session.wireProvenFormat(audio = false)
                            if (provenAudio != null && provenVideo != null) {
                                servedAudioItag = provenAudio.itag
                                servedVideoItag = provenVideo.itag
                                readySignal.complete(true)
                                onEvent?.invoke(
                                    "SABR: data pertama tiba, pompa berjalan " +
                                        "(video itag ${provenVideo.itag}, " +
                                        "audio itag ${provenAudio.itag})",
                                )
                            }
                        }
                        // Spool heartbeat for the diagnostics ring: one
                        // line per fresh 30 seconds of rolled media,
                        // so a later screenshot shows whether media
                        // kept flowing or the roll froze.
                        val spooledSecond = session.bufferedEndMs() / 1000
                        if (spooledSecond >= lastLoggedSpoolSecond + 30) {
                            lastLoggedSpoolSecond =
                                spooledSecond - spooledSecond % 30
                            val totalMb = session.tracks.values
                                .sumOf { it.mediaBytes } / 1_000_000
                            onEvent?.invoke(
                                "SABR: tergulung $spooledSecond dtk media, " +
                                    "total $totalMb MB" +
                                    projectionText(session),
                            )
                        }
                    },
                    onTrackComplete = { roadState.markComplete(it) },
                    hungryUntilMs = HUNGRY_UNTIL_MS,
                    positionHintMs = { playerPositionMs },
                    // Mechanism 13: on device, disk and network
                    // work at the same time.
                    pipelined = true,
                    maxLeadMs = leadCapMs,
                )
                when (val outcome = pump.run(MAX_PUMP_REQUESTS)) {
                    is SabrOutcome.Completed -> {
                        roadState.markComplete(session.audioFormat.itag)
                        roadState.markComplete(session.videoFormat.itag)
                        if (!readySignal.isCompleted) readySignal.complete(true)
                        onEvent?.invoke("SABR: gulungan lengkap terisi")
                        return
                    }
                    is SabrOutcome.ReloadRequested -> {
                        reloads++
                        if (reloads > MAX_RELOADS) {
                            die("RELOAD_PLAYER_RESPONSE berulang")
                            return
                        }
                        // The refreshed offer keeps this open's
                        // cap and viewport (mechanisms 6 + 15).
                        val fresh = engine.openSabrSession(
                            videoId,
                            openData.capHeightPx,
                            openData.viewportWidth to openData.viewportHeight,
                        )
                        if (fresh == null) {
                            die("penyegaran /player gagal")
                            return
                        }
                        session = fresh.newSession()
                        activeSession = session
                        session.restoreFrom(spool)
                    }
                    is SabrOutcome.TokenDemanded -> {
                        engine.noteSabrFailure(
                            "token",
                            "SPS 3: server menuntut token — jalan SABR ditutup",
                        )
                        die("server menuntut token (SPS 3)")
                        return
                    }
                    is SabrOutcome.SabrErrorReceived -> {
                        engine.noteSabrFailure("sabr-error", "SABR_ERROR dari server")
                        die("SABR_ERROR")
                        return
                    }
                    is SabrOutcome.HttpFailed -> {
                        engine.noteSabrFailure("HTTP ${outcome.status}", "POST SABR gagal")
                        die("HTTP ${outcome.status}")
                        return
                    }
                    is SabrOutcome.Stalled -> {
                        engine.noteSabrFailure("macet", outcome.reason)
                        die(outcome.reason)
                        return
                    }
                    is SabrOutcome.RequestsExhausted -> {
                        die("permintaan habis")
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            // Closed by the owner; nothing to record.
        } catch (e: Exception) {
            // The drive must never die silently: an unwitnessed death
            // leaves spool readers waiting on data that will never
            // come. Record it and close the road with the reason.
            engine.noteSabrFailure(
                "penggerak",
                "${e.javaClass.simpleName}: ${e.message}",
            )
            die("penggerak pompa gagal: ${e.javaClass.simpleName}")
        } finally {
            if (!readySignal.isCompleted) readySignal.complete(false)
        }
    }

    /** Total bytes currently on disk across this video's spool. */
    fun spooledBytes(): Long =
        spool.trackItags().sumOf { spool.track(it).fileLength() }

    /**
     * Mechanism 16 — the exact cost projection: each served
     * track's MEASURED byte rate (bytes actually rolled over the
     * timeline they cover) stretched over the whole duration.
     * Real numbers only; silent until a track has rolled media.
     */
    private fun projectionText(session: SabrSession): String {
        val durationMs = openData.durationSeconds * 1000L
        if (durationMs <= 0) return ""
        var total = 0L
        var measured = false
        for (format in listOf(session.audioFormat, session.videoFormat)) {
            val track = session.trackFor(format.itag)
            if (track.mediaBytes > 0 && track.timelineEndMs > 0) {
                total += track.mediaBytes * durationMs / track.timelineEndMs
                measured = true
            }
        }
        return if (measured) {
            "; proyeksi penuh ≈${total / 1_000_000} MB (laju terukur)"
        } else {
            ""
        }
    }

    /**
     * One-line spool state for diagnostics: how much timeline the
     * pump has rolled and how many bytes it holds. Lets a watchdog
     * report distinguish a dead pump (frozen numbers) from a stuck
     * reader (full spool, silent player).
     */
    fun statusLine(): String {
        val s = activeSession ?: return "sesi belum mulai"
        // EVERY spool file is listed, not just the two the player
        // addresses: media stranded under an unexpected itag (the
        // 2026-11-12 failure shape) must be visible at a glance.
        val files = spool.trackItags().sorted().joinToString(", ") { itag ->
            "$itag.bin ${spool.track(itag).fileLength() / 1000} KB"
        }
        return "berkas gulungan: $files; dibaca pemutar: video " +
            "${spool.track(servedVideoItag).servedBytes / 1000} KB, audio " +
            "${spool.track(servedAudioItag).servedBytes / 1000} KB; " +
            "tergulung ${s.bufferedEndMs() / 1000} dtk"
    }

    private fun die(reason: String) {
        if (!readySignal.isCompleted) readySignal.complete(false)
        onEvent?.invoke("SABR berhenti: $reason — jalan klasik mengambil alih")
        roadState.kill(reason)
    }

    companion object {
        const val MAX_PUMP_REQUESTS = 5000
        const val MAX_RELOADS = 3
        const val READY_TIMEOUT_MS = 15_000L

        /** The pump stays hungry until this much timeline is spooled. */
        const val HUNGRY_UNTIL_MS = 60_000L
    }
}

/**
 * Opens SABR playbacks on request. One coordinator for the app: it
 * owns the spool store (under the app's cache directory — spool
 * bytes are cache, never user data) and the coroutine scope the
 * pumps live on.
 */
@UnstableApi
class SabrPlaybackCoordinator(
    private val engine: LontarEngine,
    private val spoolRoot: File,
    /** Mechanism 15: the real player-panel size, read per open. */
    private val viewportProvider: () -> Pair<Int, Int> = { 1920 to 1080 },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Attempts the SABR road for [videoId]: a started, first-bytes-
     * proven playback handle, or null when the road is not taken
     * (config gate, refusal, or a failed first exchange). Never
     * throws for SABR reasons — the classic road is the caller's
     * ordinary next step either way.
     */
    suspend fun tryOpen(
        videoId: String,
        capHeightPx: Int? = null,
        onEvent: ((String) -> Unit)? = null,
    ): SabrPlayback? {
        val open = engine.openSabrSession(
            videoId,
            capHeightPx,
            viewportProvider(),
        ) ?: return null
        val store = SpoolStore(spoolRoot, engine.sabrSettings().spoolBudgetBytes)
        val spool = store.open(videoId)
        val settings = engine.sabrSettings()
        val handle = SabrPlayback(
            openData = open,
            spool = spool,
            roadState = SpoolRoadState(),
            engine = engine,
            scope = scope,
            raceBudgetBytes = settings.raceBudgetBytes,
            leadCapMs = settings.leadCapMs,
        )
        // The listener is attached BEFORE the drive starts: the
        // first-init event fires inside awaitReady below, and a
        // listener attached afterwards would never see it.
        handle.onEvent = onEvent
        handle.start()
        if (!handle.awaitReady()) {
            handle.close()
            return null
        }
        store.enforceBudget(protectVideoId = videoId)
        return handle
    }

    // ------------------------------------------------------ pemanasan

    /** When each video was last warmed; a warm inside the window is a no-op. */
    private val warmedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Pemanasan Antrean + mechanism 14: fire-and-forget warming of a
     * video the user is likely to open next (the queue's head, a
     * previewing home card). Metadata only, deduplicated inside
     * [WARM_WINDOW_MS]; the engine side never throws and never
     * records speculative failures.
     */
    fun warm(videoId: String) {
        val now = System.currentTimeMillis()
        val last = warmedAt[videoId]
        if (last != null && now - last < WARM_WINDOW_MS) return
        if (warmedAt.size >= MAX_WARM_ENTRIES) warmedAt.clear()
        warmedAt[videoId] = now
        scope.launch { engine.warmPlayerResponse(videoId) }
    }

    companion object {
        /** A warmed response stays warm in the player cache anyway;
         *  this window only stops duplicate speculative fetches. */
        const val WARM_WINDOW_MS: Long = 600_000L
        const val MAX_WARM_ENTRIES: Int = 200
    }
}
