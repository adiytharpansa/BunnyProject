package com.bunny.engine.lontar.sabr

import kotlinx.coroutines.delay

/**
 * A finished probe run, written down in full: every exchange, every
 * protection status, every byte — the structured transcript the
 * Fase 8 design demands for gate decisions and for the Radar's
 * shadow mode later (mechanism 25: transcripts are evidence, kept).
 */
data class SabrProbeTranscript(
    val videoId: String,
    val exchanges: List<SabrExchange>,
    val outcome: SabrOutcome,
    val totalMediaBytes: Long,
    val statusValues: List<Int>,
    val audioTimelineEndMs: Long,
    val videoTimelineEndMs: Long,
) {
    /** One-line verdict, fit for a diagnostics surface. */
    fun summaryLine(): String {
        val outcomeName = outcome.javaClass.simpleName
        return "SABR probe $videoId: $outcomeName, " +
            "${exchanges.size} req, $totalMediaBytes B media, " +
            "SPS ${statusValues.distinct()}, " +
            "audio ${audioTimelineEndMs}ms, video ${videoTimelineEndMs}ms"
    }
}

/** Where transcripts go. The engine ships a [NONE]; the app side
 * binds the real evidence surface (failure surface "sabr"). */
fun interface SabrTranscriptSink {
    fun record(transcript: SabrProbeTranscript)

    companion object {
        val NONE: SabrTranscriptSink = SabrTranscriptSink { }
    }
}

/**
 * Drives one bounded SABR session purely to LEARN: the canary driver
 * of gate 1 (the VM probes) and, later, the Radar's shadow mode
 * (gate 2). It is the pump with a notebook — nothing about it can
 * affect playback; it only observes and writes the transcript down.
 */
class SabrProbe(
    private val transport: SabrTransport,
    private val bandwidth: BandwidthMeter,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val sink: SabrTranscriptSink = SabrTranscriptSink.NONE,
) {

    suspend fun run(
        session: SabrSession,
        spool: VideoSpool,
        maxRequests: Int,
    ): SabrProbeTranscript {
        val exchanges = ArrayList<SabrExchange>()
        val pump = SabrPump(
            session = session,
            spool = spool,
            transport = transport,
            bandwidth = bandwidth,
            sleeper = sleeper,
            onExchange = { exchanges.add(it) },
        )
        val outcome = pump.run(maxRequests)
        val transcript = SabrProbeTranscript(
            videoId = session.videoId,
            exchanges = exchanges,
            outcome = outcome,
            totalMediaBytes = exchanges.sumOf { it.mediaBytes },
            statusValues = session.statusValues.toList(),
            audioTimelineEndMs =
                session.tracks[session.audioFormat.itag]?.timelineEndMs ?: 0,
            videoTimelineEndMs =
                session.tracks[session.videoFormat.itag]?.timelineEndMs ?: 0,
        )
        sink.record(transcript)
        return transcript
    }
}
