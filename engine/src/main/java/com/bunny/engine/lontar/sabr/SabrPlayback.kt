package com.bunny.engine.lontar.sabr

import android.os.SystemClock
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
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    /** SABR Adaptif Baru F0: one exchange's hard deadline (0 = off). */
    val exchangeTimeoutMs: Long = 0,
    /** SABR Adaptif Baru F1: run the frontier supervisor. */
    val adaptiveV2: Boolean = false,
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

    /**
     * One reader wait, kept for the app's stall detector
     * (anti-buffering package): the waited position against the
     * frontier and the whole file is what separates a far-ahead
     * extractor probe from sequential starvation.
     */
    class ReaderWait(
        val itag: Int,
        val position: Long,
        val frontier: Long,
        val fileLength: Long,
        val longWait: Boolean,
        val atUptimeMs: Long,
    )

    /** The most recent reader wait on either track, if any. */
    @Volatile
    var lastReaderWait: ReaderWait? = null
        private set

    val dataSourceFactory: DataSource.Factory =
        DataSource.Factory {
            SabrDataSource(
                spool = spool,
                road = roadState,
                onWait = { itag, position, frontier ->
                    lastReaderWait = ReaderWait(
                        itag, position, frontier,
                        spool.track(itag).fileLength(),
                        longWait = false,
                        atUptimeMs = SystemClock.uptimeMillis(),
                    )
                    onEvent?.invoke(
                        "SABR baca: itag $itag menunggu data di posisi " +
                            "$position (gulungan baru sampai $frontier)",
                    )
                },
                // Anti-buffering M1: a wait that stops being a
                // hiccup gets its own line — the waited position
                // against the frontier AND the whole file separates
                // a far-ahead extractor probe (Cues/index hunting)
                // from plain sequential starvation.
                onLongWait = { itag, position, frontier, fileLength ->
                    lastReaderWait = ReaderWait(
                        itag, position, frontier, fileLength,
                        longWait = true,
                        atUptimeMs = SystemClock.uptimeMillis(),
                    )
                    onEvent?.invoke(
                        "SABR baca lama: itag $itag menunggu >2 dtk di posisi " +
                            "$position (frontier $frontier, berkas $fileLength)",
                    )
                },
            )
        }

    /**
     * Optional observer for road events (the app wires this to its
     * playback diagnostics ring, so a stall always leaves written
     * evidence). Set right after the handle is created.
     */
    @Volatile
    var onEvent: ((String) -> Unit)? = null

    private val readySignal = CompletableDeferred<Boolean>()

    /**
     * Langkah 7, mechanism 7: this open's governance verdicts are
     * recorded at most once each — ready firing is the success,
     * the first road-level death is the failure. A handle may
     * honestly contribute both (it readied, then the road died
     * mid-play); it may never contribute either twice. Written
     * only by the drive thread (success also by its onProgress
     * callback — the same pump thread).
     */
    private var governanceSuccessRecorded = false
    private var governanceFailureRecorded = false

    /** Completes the ready signal, recording the open's success
     *  verdict for the governance the first time it fires. */
    private fun signalReady() {
        if (readySignal.isCompleted) return
        readySignal.complete(true)
        if (!governanceSuccessRecorded) {
            governanceSuccessRecorded = true
            engine.recordSabrOutcome(success = true)
        }
    }

    /** Records this open's failure verdict for the governance —
     *  called at the drive's road-level deaths only. */
    private fun recordGovernanceFailure() {
        if (governanceFailureRecorded) return
        governanceFailureRecorded = true
        engine.recordSabrOutcome(success = false)
    }

    /** The session currently being driven; read by [statusLine]. */
    @Volatile
    private var activeSession: SabrSession? = null

    /**
     * Anti-buffering M3: the live pump's latest sleep decision —
     * the backoff the server last stated and the sleep the pump
     * actually took — quoted by the spool heartbeat below.
     */
    @Volatile
    private var lastBackoffMs: Long? = null

    @Volatile
    private var lastSleepMs: Long = -1

    @Volatile
    private var job: Job? = null

    /**
     * Mechanism 23, full form: a seek target the spool does not
     * cover yet, posted by [onSeekObserved] from the player's
     * listener thread. The drive loop consumes it as a jump pass
     * (a fresh session seeded at the target). Latest wins; -1 is
     * no pending jump. Volatile hand-off only — the drive thread
     * re-reads it between passes and after a cut pump.
     */
    @Volatile
    private var pendingJumpMs: Long = -1

    /**
     * The pump run currently in flight, so a seek can cut JUST
     * the pump (the drive coroutine itself survives to start the
     * jump pass). Set and cleared by the drive thread.
     */
    @Volatile
    private var pumpChild: Job? = null

    /**
     * Jumps spent this open, mirrored here so [onSeekObserved]
     * can enforce the budget before posting another one; the
     * drive thread is the counter's owner and writer.
     */
    @Volatile
    private var jumpsSpent: Int = 0

    /** SABR Adaptif Baru F1: the frontier supervisor's brain. */
    private val watch = FrontierWatch()

    /** Set by the supervisor, consumed by the pump's sleep gate. */
    private val nudgeFlag =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * A supervisor restart seed awaiting the drive loop — the
     * frozen track's frontier. Latest wins; -1 is none pending.
     * Volatile hand-off only, exactly like [pendingJumpMs].
     */
    @Volatile
    private var pendingRestartMs: Long = -1

    /**
     * Restarts spent this open (supervisor T2 + exchange-timeout
     * recoveries share the budget); the drive thread owns it,
     * the supervisor only peeks before posting another.
     */
    @Volatile
    private var restartsSpent: Int = 0

    private var watchJob: Job? = null
    private var restartBudgetLogged = false

    fun start() {
        if (job != null) return
        job = scope.launch { drive() }
        if (adaptiveV2 && watchJob == null) {
            watchJob = scope.launch { watchLoop() }
        }
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
        watchJob?.cancel()
        watchJob = null
        pumpChild?.cancel()
        roadState.kill("pemutaran diganti")
        spool.close()
    }

    /**
     * Mechanism 23, full form — the seek hook the player calls
     * (main thread, from its position-discontinuity listener)
     * when the playhead lands somewhere new. A covered target
     * needs nothing: the reader already serves it. An uncovered
     * target becomes a JUMP: it is stored (latest wins) and the
     * in-flight pump is cut, so the drive loop wakes and starts a
     * fresh session seeded at the target instead of rolling
     * sequentially from zero and losing to the stall rescue —
     * the device evidence of 2026-11-13 (playhead at ~60 s,
     * spool at 38 s, rescue fired ~7 s before the roll arrived).
     * At most [MAX_JUMPS] jumps per open; past that the
     * sequential roll and the rescue keep their old behaviour.
     */
    fun onSeekObserved(targetMs: Long) {
        val session = activeSession ?: return
        if (session.coversPosition(targetMs)) return
        if (jumpsSpent >= MAX_JUMPS) return
        pendingJumpMs = targetMs
        pumpChild?.cancel()
    }

    /**
     * SABR Adaptif Baru F1 — the frontier supervisor loop:
     * half-second ticks over the live session's per-track
     * frontiers. A [WatchAction.Nudge] raises the flag the pump's
     * sleep gate consumes; a [WatchAction.Restart] posts the
     * frozen track's frontier as a restart seed and cuts the
     * in-flight pump, so the drive loop starts a fresh session
     * over the SAME spool seeded there — the spool is never
     * thrown away, and the classic road stays the last resort,
     * not the first responder (the 18:07 shape: 12 s of player
     * buffering while a frozen frontier went unwatched).
     */
    private suspend fun watchLoop() {
        while (currentCoroutineContext().isActive) {
            delay(WATCH_TICK_MS)
            val session = activeSession ?: continue
            val audioEnd =
                session.trackFor(session.audioFormat.itag).timelineEndMs
            val videoEnd =
                session.trackFor(session.videoFormat.itag).timelineEndMs
            val action = synchronized(watch) {
                watch.tick(
                    SystemClock.uptimeMillis(),
                    audioEnd,
                    videoEnd,
                    playerPositionMs,
                    readySignal.isCompleted,
                )
            }
            when (action) {
                is WatchAction.Nudge -> {
                    nudgeFlag.set(true)
                    onEvent?.invoke(
                        "SABR pengawas: ujung ${trackName(action.track)} " +
                            "diam ${action.frozenMs / 1000} dtk (jarak aman " +
                            "${action.leadMs / 1000} dtk) — pompa didorong",
                    )
                }
                is WatchAction.Restart -> {
                    if (restartsSpent >= MAX_RESTARTS ||
                        pendingRestartMs >= 0
                    ) {
                        continue
                    }
                    pendingRestartMs = if (action.track == WatchTrack.AUDIO) {
                        audioEnd
                    } else {
                        videoEnd
                    }
                    onEvent?.invoke(
                        "SABR pengawas: ujung ${trackName(action.track)} " +
                            "diam ${action.frozenMs / 1000} dtk — sesi " +
                            "dimulai ulang di ujung " +
                            "${pendingRestartMs / 1000} dtk",
                    )
                    pumpChild?.cancel()
                }
                WatchAction.None -> Unit
            }
        }
    }

    private fun trackName(track: WatchTrack): String =
        if (track == WatchTrack.AUDIO) "audio" else "video"

    /**
     * Mechanism 23, full form: the drive is a loop of PASSES over
     * one spool. A pass is one fresh session (one /player offer)
     * feeding one pump run. Pass kinds:
     * - initial: seed 0, the classic from-the-top roll;
     * - reload: mechanism 18's refresh, seeded at the playhead;
     * - jump: a seek landed somewhere the spool does not cover —
     *   the session is seeded AT the target (proven enterable by
     *   the jilid-2 cold-seek probe, 2026-11-12);
     * - gap-backfill: a pass ended with the tail on disk but a
     *   hole behind it — the next pass seeds at the earliest
     *   hole's start and rolls forward over it.
     * All passes share one budget, [MAX_DRIVE_PASSES]; reloads
     * keep their own [MAX_RELOADS] cap and jumps their own
     * [MAX_JUMPS] cap inside it. A pass ends when its pump
     * returns; pump deaths keep their old meaning (the road dies,
     * classic takes over) EXCEPT a stall or request exhaustion
     * arriving with the tail complete and gaps remaining — that
     * converts into a backfill pass, because the road is nearly
     * whole and only the hole is left to fill.
     */
    private suspend fun drive() {
        var session = openData.newSession()
        activeSession = session
        // What this road ASKS for, and what the device can decode
        // in hardware — one line per open, so a screenshot can
        // separate "we asked for AV1" from "the server overrode
        // the pin" (the two need different repairs).
        onEvent?.invoke(
            "SABR pin: video ${openData.videoPin.itag} " +
                "(${codecFamilyOf(openData.videoPin.mimeType)}), " +
                "audio ${openData.audioPin.itag}; codec perangkat: " +
                "AV1 keras ${openData.ceiling.av1Hardware}, " +
                "VP9 keras ${openData.ceiling.vp9Hardware}",
        )
        // Fase 8 mechanism 12 — cold spool: whatever a previous
        // session rolled for this video is still on disk. Hydrate
        // the fresh session from it BEFORE the first request; when
        // both slots come back wire-proven with a playable head,
        // the road is ready from disk immediately and the pump's
        // first request already asks only for the frontier onward.
        session.restoreFrom(spool)
        adoptClassicRanges(session)
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
                signalReady()
                onEvent?.invoke(
                    "SABR: mulai dari gulungan dingin " +
                        "(video itag ${provenVideo.itag}, " +
                        "audio itag ${provenAudio.itag})",
                )
            }
        }
        var reloads = 0
        var passes = 0
        var jumps = 0
        var jumpBudgetLogged = false
        var lastLoggedSpoolSecond = 0L
        // Mechanism 21: the honest preroll wait is named at most
        // once per open (see the pump's onSleepDecision below).
        var prerollWaitLogged = false
        // Adaptif Baru F0: dry (media-less) answers this open —
        // the journal names them sparingly (see onExchange below).
        var dryAnswers = 0
        try {
            while (true) {
                // SABR Adaptif Baru F1: a supervisor restart
                // posted while the previous pass ran becomes the
                // next pass — a fresh session seeded at the frozen
                // track's frontier over the SAME spool.
                val pendingRestart = pendingRestartMs
                if (pendingRestart >= 0) {
                    pendingRestartMs = -1
                    if (restartsSpent >= MAX_RESTARTS) {
                        if (!restartBudgetLogged) {
                            restartBudgetLogged = true
                            onEvent?.invoke(
                                "SABR pengawas: anggaran mulai ulang " +
                                    "habis — gulungan berurutan diteruskan",
                            )
                        }
                    } else {
                        restartsSpent++
                        val restarted = refreshedSession(pendingRestart)
                        if (restarted == null) {
                            die("penyegaran /player gagal")
                            return
                        }
                        session = restarted
                    }
                }
                // Mechanism 23: a seek posted while the previous
                // pass ran (or between passes) becomes the next
                // pass — a fresh session seeded at the target
                // over the SAME spool.
                val pendingJump = pendingJumpMs
                if (pendingJump >= 0) {
                    pendingJumpMs = -1
                    if (jumps >= MAX_JUMPS) {
                        if (!jumpBudgetLogged) {
                            jumpBudgetLogged = true
                            onEvent?.invoke(
                                "SABR: anggaran lompatan habis — " +
                                    "gulungan berurutan diteruskan",
                            )
                        }
                    } else {
                        jumps++
                        jumpsSpent = jumps
                        onEvent?.invoke(
                            "SABR: melompat ke ${pendingJump / 1000} dtk " +
                                "(gulungan belum menjangkau)",
                        )
                        val jumped = refreshedSession(pendingJump)
                        if (jumped == null) {
                            die("penyegaran /player gagal")
                            return
                        }
                        session = jumped
                    }
                }
                if (passes >= MAX_DRIVE_PASSES) {
                    die("batas lintasan pompa tercapai")
                    return
                }
                passes++
                // Adaptif Baru F1: every fresh pass earns the
                // supervisor's grace window before any verdict.
                synchronized(watch) {
                    watch.reset(SystemClock.uptimeMillis())
                }
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
                                signalReady()
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
                                    projectionText(session) +
                                    sleepDecisionText(session),
                            )
                        }
                    },
                    onTrackComplete = { roadState.markComplete(it) },
                    // Adaptif Baru F0 — the verdict journal: a
                    // media-less answer is the earliest visible
                    // shape of a dying pump, so the first few (and
                    // every tenth after) are named with their rn
                    // and the server's stated backoff. Media-bearing
                    // answers stay silent — the 30 s spool heartbeat
                    // already speaks for healthy flow.
                    onExchange = { ex ->
                        if (ex.mediaBytes == 0L) {
                            dryAnswers++
                            if (dryAnswers <= 3 || dryAnswers % 10 == 0) {
                                onEvent?.invoke(
                                    "SABR detak: rn=${ex.rn} tanpa media " +
                                        "(kering ke-$dryAnswers" +
                                        (ex.backoffTimeMs?.let {
                                            ", backoff ${it / 1000} dtk"
                                        } ?: "") +
                                        ")",
                                )
                            }
                        }
                    },
                    onSleepDecision = { backoff, sleep ->
                        lastBackoffMs = backoff
                        lastSleepMs = sleep
                        // Langkah 6, mechanism 21 — the honest
                        // preroll: a long server-imposed wait
                        // BEFORE the road has proven itself (the
                        // classic preroll shape: the server answers
                        // with backoff and no media) is named on
                        // the diagnostics surface, once per open —
                        // the wait is the server's, and the line
                        // says so with its size, instead of leaving
                        // a silent spinner to be blamed on the road.
                        if (!prerollWaitLogged && !readySignal.isCompleted &&
                            sleep >= PREROLL_WAIT_EVENT_MS
                        ) {
                            prerollWaitLogged = true
                            onEvent?.invoke(
                                "SABR: server meminta menunggu " +
                                    "±${sleep / 1000} dtk sebelum media " +
                                    "pertama (preroll)",
                            )
                        }
                    },
                    // Anti-buffering M8 (supersedes the fixed
                    // temperament of 2026-11-12 23:05): the sleep
                    // between requests is bounded by the spool's
                    // lead over the playhead — thin lead, no sleep
                    // worth the name; fat lead, the server's
                    // backoff is honoured under a 30 s ceiling.
                    // The cold-start hunger is the thin band by
                    // another name: at position zero the lead IS
                    // the spooled timeline.
                    leadAware = true,
                    positionHintMs = { playerPositionMs },
                    // Mechanism 13: on device, disk and network
                    // work at the same time.
                    pipelined = true,
                    // Langkah 6 audit (mechanisms 8 + 24, 2026-11-13):
                    // listen-only is a PLAYER-side switch today —
                    // PlaybackManager disables the video TRACK TYPE
                    // in background mode, so the player stops
                    // consuming video — but the pump does not learn
                    // about it and keeps fetching video segments:
                    // this cap is global (the lead is the MINIMUM
                    // across tracks), and every response still
                    // carries both tracks. A pump-side video brake
                    // would need a per-track request knob; the only
                    // wire candidate is ClientAbrState's
                    // enabled_track_types, whose bitmask semantics
                    // our captures never proved — inventing it is
                    // banned by the honesty rules. Deferred until a
                    // capture proves the knob; the player-side
                    // saving (no decode, no display) stands.
                    maxLeadMs = leadCapMs,
                    // Adaptif Baru F0: the exchange deadline.
                    exchangeTimeoutMs = exchangeTimeoutMs,
                    // Adaptif Baru F1: the supervisor's nudge,
                    // consumed by the pump's sleep gate.
                    nudgeRequested = { nudgeFlag.getAndSet(false) },
                )
                // The pump runs as a child job so a seek can cut
                // JUST the pump (mechanism 23) without cancelling
                // the drive coroutine that must start the jump
                // pass in its place.
                val run = scope.async { pump.run(MAX_PUMP_REQUESTS) }
                pumpChild = run
                val outcome = try {
                    run.await()
                } catch (e: CancellationException) {
                    // Two cancellers reach here: the owner closing
                    // the handle cancels the drive coroutine
                    // itself (rethrow — the close path owns the
                    // ending), and a seek cuts just the pump child
                    // (loop around: the pending jump at the top
                    // becomes the next pass).
                    if ((pendingJumpMs >= 0 || pendingRestartMs >= 0) &&
                        currentCoroutineContext().isActive
                    ) {
                        continue
                    }
                    throw e
                } finally {
                    if (pumpChild === run) pumpChild = null
                }
                // Adaptif Baru F0: every pass ends with its
                // outcome AND both frontiers on the journal — a
                // death that used to be invisible (the frontier
                // simply stopped) now names where it stopped.
                onEvent?.invoke(
                    "SABR lintasan ke-$passes berakhir: " +
                        outcomeSummary(outcome) +
                        " (ujung audio " +
                        "${session.trackFor(session.audioFormat.itag)" +
                            ".timelineEndMs / 1000} dtk, video " +
                        "${session.trackFor(session.videoFormat.itag)" +
                            ".timelineEndMs / 1000} dtk)",
                )
                when (outcome) {
                    is SabrOutcome.Completed -> {
                        val gap = session.earliestGapStartMs()
                        when {
                            gap == null -> {
                                roadState.markComplete(session.audioFormat.itag)
                                roadState.markComplete(session.videoFormat.itag)
                                signalReady()
                                onEvent?.invoke("SABR: gulungan lengkap terisi")
                                return
                            }
                            passes < MAX_DRIVE_PASSES -> {
                                onEvent?.invoke(
                                    "SABR: menambal celah dari ${gap / 1000} dtk",
                                )
                                val filled = refreshedSession(gap)
                                if (filled == null) {
                                    die("penyegaran /player gagal")
                                    return
                                }
                                session = filled
                            }
                            else -> {
                                // Out of passes with a hole left:
                                // killing the road lets classic
                                // resume at the playhead — strictly
                                // better than marking the road
                                // complete, whose gap read would
                                // end playback at the hole.
                                die("celah gulungan tersisa")
                                return
                            }
                        }
                    }
                    is SabrOutcome.ReloadRequested -> {
                        reloads++
                        if (reloads > MAX_RELOADS) {
                            die("RELOAD_PLAYER_RESPONSE berulang")
                            return
                        }
                        // Mechanisms 18 + 23: the refreshed offer
                        // is seeded at the CURRENT playhead, so a
                        // reload no longer re-enters at zero.
                        val fresh = refreshedSession(playerPositionMs)
                        if (fresh == null) {
                            die("penyegaran /player gagal")
                            return
                        }
                        session = fresh
                    }
                    is SabrOutcome.TokenDemanded -> {
                        engine.noteSabrFailure(
                            "token",
                            "SPS 3: server menuntut token — jalan SABR ditutup",
                        )
                        // Langkah 7, mechanism 7: a token demand is
                        // the governance's headline failure signal.
                        recordGovernanceFailure()
                        // Mechanism 10: a token demand is a hard
                        // refusal of THIS video on this road — the
                        // road memory may keep it.
                        engine.reportSabrHardRefusal(videoId)
                        die("server menuntut token (SPS 3)")
                        return
                    }
                    is SabrOutcome.SabrErrorReceived -> {
                        engine.noteSabrFailure("sabr-error", "SABR_ERROR dari server")
                        recordGovernanceFailure()
                        die("SABR_ERROR")
                        return
                    }
                    is SabrOutcome.HttpFailed -> {
                        engine.noteSabrFailure("HTTP ${outcome.status}", "POST SABR gagal")
                        recordGovernanceFailure()
                        die("HTTP ${outcome.status}")
                        return
                    }
                    is SabrOutcome.ExchangeTimeout -> {
                        // Adaptif Baru F0/F1: a deadline death is
                        // recoverable while restart budget remains —
                        // a fresh session seeded at the lagging
                        // frontier over the same spool. Only a
                        // REPEATED deadline death is a road failure.
                        if (restartsSpent < MAX_RESTARTS &&
                            passes < MAX_DRIVE_PASSES
                        ) {
                            restartsSpent++
                            val seed = minOf(
                                session.trackFor(
                                    session.audioFormat.itag,
                                ).timelineEndMs,
                                session.trackFor(
                                    session.videoFormat.itag,
                                ).timelineEndMs,
                            )
                            onEvent?.invoke(
                                "SABR: pertukaran rn=${outcome.rn} lewat " +
                                    "${outcome.timeoutMs / 1000} dtk — " +
                                    "sesi dimulai ulang di ujung " +
                                    "${seed / 1000} dtk",
                            )
                            val fresh = refreshedSession(seed)
                            if (fresh == null) {
                                die("penyegaran /player gagal")
                                return
                            }
                            session = fresh
                        } else {
                            engine.noteSabrFailure(
                                "macet",
                                "pertukaran berulang lewat batas waktu",
                            )
                            recordGovernanceFailure()
                            die("pertukaran berulang lewat batas waktu")
                            return
                        }
                    }
                    is SabrOutcome.Stalled -> {
                        // Mechanism 23: a stall with the tail
                        // already on disk and only a hole left is
                        // not a death — the backfill pass owns the
                        // hole. (A mid-seeded pass whose answers
                        // run dry at the tail must not kill a
                        // nearly-whole road.)
                        val gap = session.earliestGapStartMs()
                        if (session.isTailComplete() && gap != null &&
                            passes < MAX_DRIVE_PASSES
                        ) {
                            onEvent?.invoke(
                                "SABR: menambal celah dari ${gap / 1000} dtk",
                            )
                            val filled = refreshedSession(gap)
                            if (filled == null) {
                                die("penyegaran /player gagal")
                                return
                            }
                            session = filled
                        } else {
                            // Cancellation-shaped stalls are lifecycle
                            // noise (the handle was closed on purpose);
                            // keep them off the failure evidence surface.
                            if (!outcome.reason.contains("cancelled", ignoreCase = true)) {
                                engine.noteSabrFailure("macet", outcome.reason)
                                // Langkah 7, mechanism 7: a real
                                // stall is a road-level death; the
                                // lifecycle kind above is not, and
                                // the governance applies the same
                                // distinction as the evidence surface.
                                recordGovernanceFailure()
                            }
                            die(outcome.reason)
                            return
                        }
                    }
                    is SabrOutcome.RequestsExhausted -> {
                        // Mechanism 23: same conversion as a stall
                        // — tail whole, hole left, budget left.
                        val gap = session.earliestGapStartMs()
                        if (session.isTailComplete() && gap != null &&
                            passes < MAX_DRIVE_PASSES
                        ) {
                            onEvent?.invoke(
                                "SABR: menambal celah dari ${gap / 1000} dtk",
                            )
                            val filled = refreshedSession(gap)
                            if (filled == null) {
                                die("penyegaran /player gagal")
                                return
                            }
                            session = filled
                        } else {
                            die("permintaan habis")
                            return
                        }
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
            pumpChild?.cancel()
            if (!readySignal.isCompleted) readySignal.complete(false)
        }
    }

    /**
     * Mechanisms 18 + 23: a fresh /player offer's session over
     * the SAME spool — cap and viewport preserved from this open
     * (mechanisms 6 + 15) — seeded at [seedMs]: the playhead for
     * a reload, the seek target for a jump, the earliest gap's
     * start for a backfill. Hydrated from the spool and offered
     * the classic road's ranges exactly like the initial session.
     * Null when the refresh itself fails.
     */
    private suspend fun refreshedSession(seedMs: Long): SabrSession? {
        val fresh = engine.openSabrSession(
            videoId,
            openData.capHeightPx,
            openData.viewportWidth to openData.viewportHeight,
        ) ?: return null
        val seeded = fresh.newSession(startTimeMs = seedMs)
        seeded.restoreFrom(spool)
        adoptClassicRanges(seeded)
        activeSession = seeded
        return seeded
    }

    /**
     * Mechanism 14: right after a session is hydrated from the
     * spool, let it adopt whatever the classic road contributed
     * to the same files — under the session's own trust gate
     * (see [SabrSession.adoptClassicRanges]) — and put the count
     * on the event line when the classic road completed anything.
     */
    private fun adoptClassicRanges(session: SabrSession) {
        val adopted = session.adoptClassicRanges(spool)
        if (adopted > 0) {
            onEvent?.invoke("SABR: $adopted segmen dilengkapi jalan klasik")
        }
    }

    /** Total bytes currently on disk across this video's spool. */
    fun spooledBytes(): Long =
        spool.trackItags().sumOf { spool.track(it).fileLength() }

    /**
     * Anti-buffering M13 (media prewarm): how much timeline the
     * live session has rolled, min across the pinned tracks — the
     * prewarm loop's stopwatch. Zero before the first session runs.
     */
    fun bufferedEndMs(): Long = activeSession?.bufferedEndMs() ?: 0

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
     * Anti-buffering M3 — the heartbeat's pump-decision suffix:
     * the sleep the pump last took against the backoff the server
     * last asked for, and each served track's lead over the
     * playhead. Silent until the pump has decided anything.
     */
    private fun sleepDecisionText(session: SabrSession): String {
        val sleep = lastSleepMs
        if (sleep < 0) return ""
        fun leadSeconds(format: SabrFormat): Long {
            val track = session.trackFor(format.itag)
            return maxOf(0L, track.timelineEndMs - playerPositionMs) / 1000
        }
        val backoffText = lastBackoffMs?.let { "${it / 1000}" } ?: "-"
        return "; tidur terakhir ${sleep / 1000} dtk (backoff server " +
            "$backoffText), lead audio ${leadSeconds(session.audioFormat)} dtk, " +
            "video ${leadSeconds(session.videoFormat)} dtk"
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
        var line = "berkas gulungan: $files; dibaca pemutar: video " +
            "${spool.track(servedVideoItag).servedBytes / 1000} KB, audio " +
            "${spool.track(servedAudioItag).servedBytes / 1000} KB; " +
            "tergulung ${s.bufferedEndMs() / 1000} dtk"
        // Autopsi gulungan (anti-buffering detector): a track whose
        // FILE is much longer than its vouched FRONTIER has bytes
        // the ledger does not vouch for — the hole shape. Dump that
        // track's segment map so one screenshot shows exactly which
        // sequence is short or missing.
        for (itag in spool.trackItags()) {
            val track = spool.track(itag)
            val frontier = track.frontier()
            if (track.fileLength() - frontier <= 4096) continue
            val map = track.segments.values
                .sortedBy { it.sequence }
                .take(14)
                .joinToString(" ") { seg ->
                    val expected = if (seg.expectedLength > seg.length) {
                        "/${seg.expectedLength}"
                    } else {
                        ""
                    }
                    val mark = if (seg.isComplete) "" else "!"
                    "${seg.sequence}[${seg.length}$expected$mark]"
                }
            line += "; autopsi $itag: frontier $frontier dari berkas " +
                "${track.fileLength()}; segmen $map"
        }
        return line
    }

    /** Adaptif Baru F0: one short name per pump outcome, for
     *  the pass-end journal line. */
    private fun outcomeSummary(outcome: SabrOutcome): String =
        when (outcome) {
            is SabrOutcome.Completed -> "selesai"
            is SabrOutcome.TokenDemanded -> "server menuntut token"
            is SabrOutcome.SabrErrorReceived -> "SABR_ERROR"
            is SabrOutcome.ReloadRequested -> "server meminta segarkan"
            is SabrOutcome.RequestsExhausted ->
                "permintaan habis (${outcome.requests})"
            is SabrOutcome.HttpFailed -> "HTTP ${outcome.status}"
            is SabrOutcome.Stalled -> "macet: ${outcome.reason}"
            is SabrOutcome.ExchangeTimeout ->
                "pertukaran rn=${outcome.rn} lewat batas waktu"
        }

    private fun die(reason: String) {
        if (!readySignal.isCompleted) readySignal.complete(false)
        onEvent?.invoke("SABR berhenti: $reason — jalan klasik mengambil alih")
        roadState.kill(reason)
    }

    companion object {
        const val MAX_PUMP_REQUESTS = 5000
        const val MAX_RELOADS = 3

        /**
         * Mechanism 23: the total drive passes (initial + reloads
         * + jumps + backfills) one open may spend — a hard stop
         * against pass ping-pong on a pathological spool.
         */
        const val MAX_DRIVE_PASSES = 10

        /**
         * Mechanism 23: jumps one open may answer; past this the
         * sequential roll and the stall rescue behave as before.
         */
        const val MAX_JUMPS = 5

        /**
         * SABR Adaptif Baru F1: session restarts one open may
         * spend (supervisor T2 and exchange-timeout recoveries
         * share this budget); past it the road behaves exactly
         * as before — the sequential roll, then classic.
         */
        const val MAX_RESTARTS = 3

        /** The frontier supervisor's tick cadence. */
        const val WATCH_TICK_MS = 500L
        const val READY_TIMEOUT_MS = 15_000L

        /** Mechanism 21: a pre-ready pump sleep at least this long
         *  is a named preroll wait, not a hiccup. */
        const val PREROLL_WAIT_EVENT_MS = 5_000L
        // The pre-M8 hunger constants (cold window 60 s, hunger
        // lead 120 s, polite cap 5 s) are gone: leadBoundedSleep's
        // bands carry the same shape as the pump's own rule now.
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
            exchangeTimeoutMs = settings.exchangeTimeoutMs,
            adaptiveV2 = settings.adaptiveV2,
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

    /**
     * Mechanism 14: opens a spool recorder for a CLASSIC playback
     * of [videoId] — the bytes the classic road downloads are
     * written into the same spool the SABR road uses, under the
     * per-track trust gate the recorder itself applies (see
     * [ClassicSpoolRecorder]). [renditions] maps itag → the
     * classic rendition (lastModified) the player response named
     * for the formats the DASH manifest uses; itags absent from
     * it, or mapped to 0, are simply never recorded. Null when
     * nothing at all is recordable — no renditions offered, or
     * every track refused by the gate.
     */
    fun openClassicRecorder(
        videoId: String,
        renditions: Map<Int, Long>,
    ): ClassicSpoolRecorder? {
        val usable = renditions.filterValues { it > 0 }
        if (usable.isEmpty()) return null
        val store = SpoolStore(spoolRoot, engine.sabrSettings().spoolBudgetBytes)
        val spool = store.open(videoId)
        val recorder = ClassicSpoolRecorder(store, spool, usable)
        if (recorder.recordableItags.isEmpty()) {
            recorder.close()
            return null
        }
        return recorder
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

    /** When each video's media was last prewarmed (M13). */
    private val prewarmedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Anti-buffering M13 — media prewarm: on an unmetered link,
     * the queue head's spool itself starts filling while the
     * current video plays, so the switch opens with stock instead
     * of from zero. Speculative exactly like [warm] (deduplicated
     * inside the window, silent on failure) and self-limiting:
     * the handle closes once the spool leads by
     * [PREWARM_TARGET_MS], or once the roll stops growing —
     * completed, refused, or stalled, prewarm has then done what
     * it can.
     */
    fun prewarmMedia(videoId: String) {
        if (!engine.isUnmetered()) return
        val now = System.currentTimeMillis()
        val last = prewarmedAt[videoId]
        if (last != null && now - last < WARM_WINDOW_MS) return
        if (prewarmedAt.size >= MAX_WARM_ENTRIES) prewarmedAt.clear()
        prewarmedAt[videoId] = now
        scope.launch {
            try {
                val handle = tryOpen(videoId, null) { } ?: return@launch
                try {
                    var lastEnd = -1L
                    var stillPolls = 0
                    while (handle.bufferedEndMs() < PREWARM_TARGET_MS &&
                        stillPolls < PREWARM_STILL_POLLS
                    ) {
                        delay(PREWARM_POLL_MS)
                        val end = handle.bufferedEndMs()
                        stillPolls = if (end == lastEnd) stillPolls + 1 else 0
                        lastEnd = end
                    }
                } finally {
                    handle.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Speculative work stays silent.
            }
        }
    }

    companion object {
        /** A warmed response stays warm in the player cache anyway;
         *  this window only stops duplicate speculative fetches. */
        const val WARM_WINDOW_MS: Long = 600_000L
        const val MAX_WARM_ENTRIES: Int = 200

        /** M13: prewarm fills this much timeline, then lets go. */
        const val PREWARM_TARGET_MS: Long = 180_000L
        const val PREWARM_POLL_MS: Long = 5_000L

        /** M13: polls without growth before prewarm gives up. */
        const val PREWARM_STILL_POLLS: Int = 6
    }
}
