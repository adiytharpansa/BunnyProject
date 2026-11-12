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
) {

    /**
     * Runs the pump to a terminal outcome. The pump NEVER throws
     * (cancellation aside): any unexpected failure inside an exchange
     * becomes [SabrOutcome.Stalled] with the cause named, so the
     * caller always closes the road and the classic fallthrough
     * always gets its turn — a silent pump death would leave spool
     * readers waiting forever.
     */
    suspend fun run(maxRequests: Int): SabrOutcome = try {
        runGuarded(maxRequests)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SabrOutcome.Stalled("proses: ${e.javaClass.simpleName}: ${e.message}")
    }

    private suspend fun runGuarded(maxRequests: Int): SabrOutcome {
        var pin = false
        var playerTimeMs = session.startTimeMs
        var dryStreak = 0
        var waitStreak = 0
        var requests = 0
        val completedNotified = HashSet<Int>()

        while (requests < maxRequests) {
            val body = session.buildRequest(playerTimeMs, bandwidth.estimateBps(), pin)
            val sep = if (session.currentUrl.contains('?')) '&' else '?'
            val url = "${session.currentUrl}${sep}rn=${session.rn}&cpn=${session.cpn}"
            val started = System.nanoTime()
            val payload = try {
                transport.post(url, body)
            } catch (e: SabrHttpException) {
                return SabrOutcome.HttpFailed(e.status)
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

            persist(result)
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
            val polite = if (backoff != null && backoff > 0) {
                minOf(backoff, if (result.mediaBytes == 0L) 15_000 else 20_000)
            } else {
                200L
            }
            val leadMs = session.bufferedEndMs() - positionHintMs()
            val hungry = hungryUntilMs > 0 &&
                (session.bufferedEndMs() < hungryUntilMs || leadMs < hungryLeadMs)
            val sleep = if (hungry) {
                minOf(polite, hungrySleepCapMs)
            } else {
                polite
            }
            sleeper(sleep)
        }
        return SabrOutcome.RequestsExhausted(requests)
    }

    /** Writes chunks at their absolute offsets and syncs manifests. */
    private fun persist(result: SabrConsumeResult) {
        val touched = HashSet<Int>()
        for (event in result.events) {
            if (event is SabrEvent.MediaChunk) {
                spool.track(event.header.itag).write(event.offsetInTrack, event.data)
                touched.add(event.header.itag)
            }
        }
        for ((itag, track) in session.tracks) {
            val trackSpool = spool.track(itag)
            trackSpool.noteFormat(track.format.id())
            track.endSegmentNumber?.let { trackSpool.noteEndSegmentNumber(it) }
            if (track.gotInit) trackSpool.noteInit(track.initBytes)
            for (segment in track.segments.values) trackSpool.noteSegment(segment)
            touched.add(itag)
        }
        spool.touch()
        for (itag in touched) spool.track(itag).flushManifest()
    }
}
