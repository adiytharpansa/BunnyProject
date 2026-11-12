package com.bunny.engine.lontar.sabr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** HTTP failure at the transport seam; carries the status code. */
class SabrHttpException(val status: Int) : Exception("SABR HTTP $status")

/**
 * The transport seam: one POST of a protobuf body to a streaming URL,
 * returning the raw UMP response. The production implementation rides
 * Lontar's existing OkHttp stack (wired in Langkah 3); tests replay
 * recordings through it.
 */
interface SabrTransport {
    suspend fun post(url: String, body: ByteArray): ByteArray
}

/**
 * Where the ClientAbrState's bandwidth figure comes from: a MEASURED
 * estimate (design mechanism 15), never a guess the server could
 * catch us inflating.
 */
interface BandwidthMeter {
    fun estimateBps(): Long
    fun observe(bytes: Int, durationMs: Long)
}

/** EWMA of real response throughput, exactly the probe's recipe. */
class EwmaBandwidthMeter(
    initialBps: Long = 5_000_000,
) : BandwidthMeter {
    private var current: Double = initialBps.toDouble()

    override fun estimateBps(): Long = current.toLong()

    override fun observe(bytes: Int, durationMs: Long) {
        if (bytes <= 0 || durationMs <= 0) return
        val instant = bytes * 8.0 / (durationMs / 1000.0)
        current = (0.7 * current + 0.3 * instant).coerceIn(200_000.0, 50_000_000.0)
    }
}

/** One request/response exchange, as the transcript records it. */
data class SabrExchange(
    val rn: Int,
    val requestBytes: Int,
    val responseBytes: Int,
    val partTypes: List<Int>,
    val protectionStatuses: List<Int>,
    val mediaBytes: Long,
    val backoffTimeMs: Long?,
)

/** How a pump run ended — the road's report to its caller. */
sealed interface SabrOutcome {
    /** Both pinned tracks hold every segment. */
    object Completed : SabrOutcome

    /** STREAM_PROTECTION_STATUS 3: this road is closed without a token. */
    object TokenDemanded : SabrOutcome

    object SabrErrorReceived : SabrOutcome

    /**
     * The server ordered a player refresh. The pump stops here by
     * design: refreshing /player and starting a new session over the
     * SAME spool is the caller's move (design mechanism 18).
     */
    object ReloadRequested : SabrOutcome

    data class RequestsExhausted(val requests: Int) : SabrOutcome
    data class HttpFailed(val status: Int) : SabrOutcome
    data class Stalled(val reason: String) : SabrOutcome
}

/**
 * The pump — Fase 8 design §3. Drives a [SabrSession] against a
 * [SabrTransport], persists every media chunk into the spool at its
 * absolute offset, mirrors the session's bookkeeping into the spool
 * manifests, and honours the server's backoff the way the probe did
 * (capped: 20 s when media flowed, 15 s when it did not).
 *
 * Terminal discipline is the probe's: SABR_ERROR ends the run, a
 * protection status of 3 ends the run (token demanded — the road
 * yields, it never argues), repeated media-less answers trip the
 * dry/wait streaks that caught the jilid-1 wall.
 */
class SabrPump(
    private val session: SabrSession,
    private val spool: VideoSpool,
    private val transport: SabrTransport,
    private val bandwidth: BandwidthMeter,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val onExchange: ((SabrExchange) -> Unit)? = null,
    /** Called after each response's chunks are persisted. */
    private val onProgress: (() -> Unit)? = null,
    /** Called once per track whose final segment has landed. */
    private val onTrackComplete: ((Int) -> Unit)? = null,
    /**
     * Playback hunger (device verdict 2026-11-12, Langkah 3): while
     * the spooled timeline is shorter than [hungryUntilMs], the
     * server's backoff sleep is capped to [hungrySleepCapMs] — a
     * player waiting to start needs its opening stock filled
     * promptly, and full politeness starved it into silence on
     * device. Zero disables; probe and replay runs keep the
     * recorded cadence.
     */
    private val hungryUntilMs: Long = 0,
    private val hungrySleepCapMs: Long = 1_000,
    /**
     * Live-playback hunger: while the spool's lead over the playhead
     * ([positionHintMs]) is thinner than [hungryLeadMs], the sleep
     * stays capped too — politeness after the cold window starved a
     * playing video on device (2026-11-12: bursts arrived slower
     * than real time and the player rebuffered mid-stream). Only
     * consulted when [hungryUntilMs] > 0, so probe and replay runs
     * keep the recorded cadence.
     */
    private val positionHintMs: () -> Long = { 0L },
    private val hungryLeadMs: Long = 45_000,
    /**
     * Fase 8 mechanism 13 — pipelining: when true, each response's
     * spool writes run on a single background writer (in order)
     * while the next request is already on the wire; network and
     * disk work at the same time instead of taking turns. The
     * session state the next request is built from is updated by
     * consume() on this thread, never by the writer, and every
     * persist job snapshots what it needs before queueing — so the
     * wire cadence and the final spool bytes are identical to the
     * synchronous run, which stays the default for probe and
     * replay fidelity.
     */
    private val pipelined: Boolean = false,
    /**
     * Mechanisms 3 + 22 — the budget-aware lead cap: while the
     * spooled timeline leads the playhead ([positionHintMs]) by
     * more than [maxLeadMs], the pump waits instead of requesting
     * (bytes are a budget, not an abstraction). The default is
     * unbounded — the unmetered temperament, and the probe/replay
     * behaviour. A capped pump whose playhead never advances waits
     * indefinitely by design; the owning handle's cancellation is
     * its exit, exactly like a paused player's.
     */
    private val maxLeadMs: Long = Long.MAX_VALUE,
    /**
     * The politeness cap for media-bearing responses once the
     * pump is no longer hungry. The probe's 20 s default mirrors
     * what a bench run owes the server; a LIVE player is starved
     * by it (device evidence 2026-11-12 22:50: 20–42 s rebuffers
     * on a healthy, far-ahead spool — each starve lasted exactly
     * one polite sleep plus one slow exchange), so the playback
     * drive passes a much shorter cap.
     */
    private val politeCapMs: Long = 20_000,
    /**
     * Anti-buffering M3: each exchange's sleep decision, reported
     * as (the server's backoff, the sleep actually taken) right
     * before the pump sleeps — so a later verdict reads numbers,
     * not impressions. (A sibling of [onExchange], which reports
     * the exchange itself before its sleep exists.)
     */
    private val onSleepDecision: ((backoffMs: Long?, sleepMs: Long) -> Unit)? = null,
    /**
     * Anti-buffering M8: lead-aware pacing. When true, the
     * polite/hungry sleep computation is REPLACED by
     * [leadBoundedSleep]: the server's backoff is honoured only
     * as far as the spool's lead over the playhead allows —
     * health first, politeness second. The lead is
     * [SabrSession.bufferedEndMs] minus the playhead — already
     * the MINIMUM across tracks, so the lagging track (the one
     * the merged player's readiness waits for) drives the pace:
     * that is design M9 by construction. When false, nothing
     * changes: probe and replay runs keep the recorded cadence.
     */
    private val leadAware: Boolean = false,
) {

    companion object {
        /** How long a lead-capped pump waits between lead checks. */
        const val LEAD_PAUSE_MS: Long = 1_000L

        /** M8: under this lead the pump asks again at once. */
        const val LEAD_THIN_MS: Long = 30_000L

        /** M8: past this lead the server's backoff is (capped) law. */
        const val LEAD_FAT_MS: Long = 120_000L

        /** The immediate-cadence floor of the thin band. */
        const val LEAD_FLOOR_MS: Long = 200L

        /**
         * M8's pure decision: how long to sleep after an exchange,
         * given the server's [backoffMs] (null = none stated), the
         * spool's [leadMs] over the playhead (minimum across
         * tracks), and whether the exchange carried media. A
         * media-less answer with no backoff keeps the legacy 200 ms
         * cadence in every band; the bands themselves are:
         * thin (< 30 s) → the floor, backoff ignored; middle
         * (< 120 s) → the backoff capped at 5 s; fat → the backoff
         * capped at 30 s (the PipePipe precedent the research
         * found in the wild).
         */
        fun leadBoundedSleep(backoffMs: Long?, leadMs: Long, mediaBytes: Long): Long {
            if (mediaBytes == 0L && backoffMs == null) return LEAD_FLOOR_MS
            return when {
                leadMs < LEAD_THIN_MS -> LEAD_FLOOR_MS
                leadMs < LEAD_FAT_MS -> minOf(backoffMs ?: 5_000L, 5_000L)
                else -> minOf(backoffMs ?: 30_000L, 30_000L)
            }
        }
    }

    /** The pipelining writer; null in synchronous mode. */
    private val writer: java.util.concurrent.ExecutorService? =
        if (pipelined) {
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "sabr-persist").apply { isDaemon = true }
            }
        } else {
            null
        }

    @Volatile
    private var lastWrite: java.util.concurrent.Future<*>? = null

    /** First spool-write failure seen on the writer, if any. */
    private val writeError =
        java.util.concurrent.atomic.AtomicReference<Exception?>(null)

    /**
     * Runs the pump to a terminal outcome. The pump NEVER throws
     * (cancellation aside): any unexpected failure inside an exchange
     * becomes [SabrOutcome.Stalled] with the cause named, so the
     * caller always closes the road and the classic fallthrough
     * always gets its turn — a silent pump death would leave spool
     * readers waiting forever.
     */
    suspend fun run(maxRequests: Int): SabrOutcome {
        val outcome = try {
            runGuarded(maxRequests)
        } catch (e: CancellationException) {
            drainWrites()
            throw e
        } catch (e: Exception) {
            drainWrites()
            return SabrOutcome.Stalled(
                "proses: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
        drainWrites()
        // A completed run whose spool writes failed is not complete:
        // the session believes bytes the disk does not hold, and
        // readers would wait at a hole forever. Name it a stall.
        val failure = writeError.get()
        if (failure != null && outcome == SabrOutcome.Completed) {
            return SabrOutcome.Stalled("tulis gulungan: ${failure.message}")
        }
        return outcome
    }

    /** Waits out the pipelining writer and closes it down. */
    private fun drainWrites() {
        try {
            lastWrite?.get()
        } catch (e: Exception) {
            writeError.compareAndSet(null, e)
        }
        writer?.shutdown()
    }

    private suspend fun runGuarded(maxRequests: Int): SabrOutcome {
        var pin = false
        var playerTimeMs = session.startTimeMs
        var dryStreak = 0
        var waitStreak = 0
        var requests = 0
        val completedNotified = HashSet<Int>()

        while (requests < maxRequests) {
            if (maxLeadMs != Long.MAX_VALUE && session.initReceived &&
                session.bufferedEndMs() - positionHintMs() > maxLeadMs
            ) {
                // Lead is fat enough for a metered link: hold the
                // requests (and their bytes) until the playhead
                // eats into the lead.
                sleeper(LEAD_PAUSE_MS)
                continue
            }
            val body = session.buildRequest(playerTimeMs, bandwidth.estimateBps(), pin)
            val sep = if (session.currentUrl.contains('?')) '&' else '?'
            val url = "${session.currentUrl}${sep}rn=${session.rn}&cpn=${session.cpn}"
            val started = System.nanoTime()
            val payload = try {
                transport.post(url, body)
            } catch (e: SabrHttpException) {
                return SabrOutcome.HttpFailed(e.status)
            } catch (e: CancellationException) {
                // The drive was closed (the viewer moved on): that is
                // lifecycle, not a stall — never let it be recorded
                // as one.
                throw e
            } catch (e: Exception) {
                return SabrOutcome.Stalled("transport: ${e.message}")
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            bandwidth.observe(payload.size, elapsedMs)
            val exchangeRn = session.rn
            session.rn++
            requests++

            val parts = try {
                Ump.parseParts(payload)
            } catch (e: SabrParseException) {
                return SabrOutcome.Stalled("urai UMP: ${e.message}")
            }
            val decoded = SabrPartDecoder.decodeAll(parts, session.knownItags)
            val result = session.consume(decoded)

            submitPersist(snapshotOf(result))
            onProgress?.invoke()
            for ((itag, track) in session.tracks) {
                if (track.isComplete && completedNotified.add(itag)) {
                    onTrackComplete?.invoke(itag)
                }
            }
            onExchange?.invoke(
                SabrExchange(
                    rn = exchangeRn,
                    requestBytes = body.size,
                    responseBytes = payload.size,
                    partTypes = parts.map { it.type },
                    protectionStatuses = decoded
                        .filterIsInstance<SabrPart.ProtectionStatusPart>()
                        .map { it.status.status },
                    mediaBytes = result.mediaBytes,
                    backoffTimeMs = result.backoffTimeMs,
                )
            )

            // Terminal conditions, in the probe's order.
            if (result.sawSabrError) return SabrOutcome.SabrErrorReceived
            if (result.protectionStatus ==
                StreamProtectionStatus.STATUS_TOKEN_REQUIRED
            ) {
                return SabrOutcome.TokenDemanded
            }
            if (result.sawReload) {
                return if (session.reloads > 3) {
                    SabrOutcome.Stalled("RELOAD_PLAYER_RESPONSE berulang (>3)")
                } else {
                    SabrOutcome.ReloadRequested
                }
            }

            // Progress bookkeeping: the streaks that caught the wall.
            if (result.mediaBytes > 0 || result.sawRedirect) {
                dryStreak = 0
                waitStreak = 0
            } else if ((result.backoffTimeMs ?: 0) > 1000) {
                waitStreak++
                if (waitStreak > 8) {
                    return SabrOutcome.Stalled(
                        "server terus meminta menunggu, tanpa media"
                    )
                }
            } else {
                dryStreak++
                if (dryStreak >= 4) {
                    return SabrOutcome.Stalled("4 jawaban tanpa media berturut-turut")
                }
            }

            if (session.initReceived) {
                session.adoptServerPins()
                pin = true
            }
            playerTimeMs = maxOf(playerTimeMs, session.bufferedEndMs())
            if (session.isComplete()) return SabrOutcome.Completed

            val backoff = result.backoffTimeMs
            val leadMs = session.bufferedEndMs() - positionHintMs()
            val sleep = if (leadAware) {
                // M8: the lead decides, the backoff advises.
                leadBoundedSleep(backoff, leadMs, result.mediaBytes)
            } else {
                val polite = if (backoff != null && backoff > 0) {
                    minOf(backoff, if (result.mediaBytes == 0L) 15_000 else politeCapMs)
                } else {
                    200L
                }
                val hungry = hungryUntilMs > 0 &&
                    (session.bufferedEndMs() < hungryUntilMs || leadMs < hungryLeadMs)
                if (hungry) {
                    minOf(polite, hungrySleepCapMs)
                } else {
                    polite
                }
            }
            // M3: the decision is on record before it is taken.
            onSleepDecision?.invoke(backoff, sleep)
            sleeper(sleep)
        }
        return SabrOutcome.RequestsExhausted(requests)
    }

    /** One track's manifest facts, copied off the live session. */
    private class TrackSync(
        val itag: Int,
        val formatId: SabrFormatId,
        val endSegmentNumber: Int?,
        val gotInit: Boolean,
        val initBytes: Long,
        val segments: List<SabrSegment>,
        /** The rendition being appended + its spool anchor. */
        val wireRendition: Long,
        val currentDelta: Long,
    )

    /** Everything one persist needs, snapshotted on the pump thread. */
    private class PersistJob(
        val chunks: List<SabrEvent.MediaChunk>,
        val tracks: List<TrackSync>,
    )

    /**
     * Copies what a persist needs out of the live session. Segment
     * objects are copied too: a length that grows with the next
     * response must not retro-edit a queued job (each job re-notes
     * every segment anyway, so the last job's copy is the exact
     * final state). The copies are SPOOL-space — their startRange
     * is shifted by the segment's rendition anchor, because the
     * manifest and the data file both speak spool space; each
     * copy keeps its spoolDelta, so the pair round-trips and a
     * later session converts back to wire space exactly.
     */
    private fun snapshotOf(result: SabrConsumeResult): PersistJob {
        val chunks = result.events.filterIsInstance<SabrEvent.MediaChunk>()
        val tracks = session.tracks.map { (itag, track) ->
            TrackSync(
                itag = itag,
                formatId = track.format.id(),
                endSegmentNumber = track.endSegmentNumber,
                gotInit = track.gotInit,
                initBytes = track.initBytes,
                segments = track.segments.values.map {
                    it.copy(startRange = it.spoolStartRange)
                },
                wireRendition = track.wireRendition,
                currentDelta = track.currentDelta,
            )
        }
        return PersistJob(chunks, tracks)
    }

    /**
     * Runs [job] on the pipelining writer when there is one, inline
     * otherwise. Writer failures are captured, never thrown into
     * the wire loop; [run] reads them at drain time.
     */
    private fun submitPersist(job: PersistJob) {
        val w = writer
        if (w == null) {
            persist(job)
            return
        }
        lastWrite = w.submit {
            try {
                persist(job)
            } catch (e: Exception) {
                writeError.compareAndSet(null, e)
            }
        }
    }

    /** Writes chunks at their absolute offsets and syncs manifests. */
    private fun persist(job: PersistJob) {
        val touched = HashSet<Int>()
        for (chunk in job.chunks) {
            spool.track(chunk.header.itag).write(chunk.offsetInTrack, chunk.data)
            touched.add(chunk.header.itag)
        }
        for (track in job.tracks) {
            val trackSpool = spool.track(track.itag)
            trackSpool.noteFormat(track.formatId)
            track.endSegmentNumber?.let { trackSpool.noteEndSegmentNumber(it) }
            if (track.gotInit) trackSpool.noteInit(track.initBytes)
            trackSpool.noteWireState(track.wireRendition, track.currentDelta)
            for (segment in track.segments) trackSpool.noteSegment(segment)
            touched.add(track.itag)
        }
        spool.touch()
        for (itag in touched) spool.track(itag).flushManifest()
    }
}
