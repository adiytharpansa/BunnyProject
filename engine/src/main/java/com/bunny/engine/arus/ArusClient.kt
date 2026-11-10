package com.bunny.engine.arus

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The one network operation Arus needs — abstracted so the state machine is testable. */
fun interface ArusTransport {
    /** POSTs [body] to [url] and returns the raw response body. */
    fun post(url: String, body: ByteArray): ByteArray
}

class ArusTransportException(val httpCode: Int, message: String) : Exception(message)

/** The production transport: OkHttp, the same HTTP stack Lontar uses. */
class OkHttpArusTransport(
    private val client: OkHttpClient,
    private val userAgent: String,
) : ArusTransport {
    override fun post(url: String, body: ByteArray): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.yt-ump")
            .header("Accept-Encoding", "identity")
            .post(body.toRequestBody("application/x-protobuf".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw ArusTransportException(response.code, "SABR POST HTTP ${response.code}")
            }
            return bytes
        }
    }
}

/** One SABR conversation, as consumers (the A2 stream store) depend on it. */
interface ArusConversation {
    fun stream(
        session: ArusSession,
        video: ArusFormat,
        audio: ArusFormat,
        sink: ArusMediaSink,
    ): ArusResult
}

/** Receives demultiplexed media as the conversation delivers it. */
interface ArusMediaSink {
    /**
     * One media chunk for [headerId]; [format] is the sequence's format
     * once its MEDIA_HEADER has been seen (headers precede their media
     * in every observed conversation, but the sink contract does not
     * depend on it).
     */
    fun onChunk(headerId: Int, format: FormatId?, data: ByteArray)
}

sealed class ArusException(message: String) : Exception(message) {
    class ProtectionRequired(val status: Int) :
        ArusException("stream protection status $status: a token this engine does not mint is required")

    class SabrError(val payload: ByteArray) :
        ArusException("server answered SABR_ERROR (${payload.size} bytes)")

    class ReloadRequired :
        ArusException("server asked for a fresh player response (RELOAD_PLAYER_RESPONSE)")

    class TooManyRequests(val requests: Int) :
        ArusException("conversation did not finish within $requests requests")

    class TransportFailure(cause: IOException) :
        ArusException("transport failed: ${cause.message}")
}

/** The outcome of one finished (or honestly partial) conversation. */
class ArusResult(
    val bytesByItag: Map<Int, Long>,
    val requests: Int,
    val protectionStatus: Int?,
    val completedNormally: Boolean,
    val events: List<String>,
)

/**
 * Arus A1 — the conversation driver. A state machine over one
 * [ArusSession]: it asks, reads UMP parts, demultiplexes media to the
 * sink, reports its buffered position back, and asks again, until the
 * server's answers run dry — the loop A0's single-burst probe
 * deliberately stopped short of.
 *
 * Behaviour pinned by the census:
 * - The conversation opens with a SABR_REDIRECT more often than not;
 *   the redirect is followed by re-POSTing to the new URL, and `rn`
 *   counts EVERY post, redirected or not.
 * - Protection status 3 ends the conversation ([ArusException.ProtectionRequired]):
 *   Arus does not mint tokens, by design — Radar's job is to see this.
 * - Buffered position is reported back as ranges per format, with the
 *   playhead at the least-buffered format's end. The accumulated
 *   header durations are a CLAIM, not a measurement: a controlled
 *   probe (2026-11-11) showed the header duration field is NOT media
 *   time (its sums reached 6.5x the video's real length while the
 *   delivered byte stream assembled into exactly the full video),
 *   but the server honours the claim as a held-watermark — it never
 *   resends what the client claims to hold, while UNDERSTATED claims
 *   (bytes ÷ bitrate estimates) made it resend whole sequences the
 *   client already had. So the claim stays, and is never used as a
 *   local clock.
 * - The conversation ends when the SERVER goes quiet, not when a
 *   local estimate says the video should be over: the earlier
 *   frontier-based finish (sums vs. approxDurationMs) fired after
 *   the first bursts on device and ended real streams mid-play
 *   (field report 2026-11-11 — playback cut, listener restarted the
 *   song). Media-less responses are retried with policy-paced
 *   patience (the server's own backoff, clamped), because a pacing
 *   pause is not the end; only a sustained silence is.
 *
 * Blocking by design: call from a background dispatcher.
 */
class ArusClient(
    private val transport: ArusTransport,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val maxRequests: Int = 400,
) : ArusConversation {

    override fun stream(
        session: ArusSession,
        video: ArusFormat,
        audio: ArusFormat,
        sink: ArusMediaSink,
    ): ArusResult {
        var url = session.sabrUrl
        var rn = 0
        var protection: Int? = null
        val events = mutableListOf<String>()
        val bytesByItag = mutableMapOf<Int, Long>()
        val headers = mutableMapOf<Int, MediaHeader>()
        val endedHeaders = mutableSetOf<Int>()
        val bufferedMs = mutableMapOf<Int, Long>() // itag -> held-watermark claim (header durations)
        var emptyStreak = 0
        var policyBackoffMs: Long? = null

        fun bufferedRanges(): List<BufferedRange> = listOf(video, audio).map { format ->
            BufferedRange(format.formatId, 0, bufferedMs[format.itag] ?: 0)
        }

        fun playheadMs(): Long = minOf(bufferedMs[video.itag] ?: 0, bufferedMs[audio.itag] ?: 0)

        fun emptyDelayMs(): Long =
            (policyBackoffMs ?: DEFAULT_EMPTY_DELAY_MS)
                .coerceIn(MIN_EMPTY_DELAY_MS, MAX_EMPTY_DELAY_MS)

        while (true) {
            if (rn >= maxRequests) throw ArusException.TooManyRequests(rn)
            val request = AbrRequest.build(
                state = ClientAbrState(
                    playerTimeMs = playheadMs(),
                    enabledTrackTypesBitfield = ClientAbrState.TRACKS_AUDIO_AND_VIDEO,
                ),
                initializationFormatIds = listOf(video.formatId, audio.formatId),
                bufferedRanges = bufferedRanges(),
                ustreamerConfig = session.ustreamerConfig,
                audioFormat = audio.formatId,
                videoFormat = video.formatId,
            )
            val separator = if ('?' in url) '&' else '?'
            val raw = try {
                transport.post("$url${separator}rn=$rn", request)
            } catch (e: IOException) {
                throw ArusException.TransportFailure(e)
            }
            rn++
            var mediaInResponse = 0L
            var sawRedirect = false

            for (part in UmpReader.parseAll(raw)) {
                when (part.type) {
                    UmpParts.SABR_REDIRECT -> {
                        val next = SabrRedirect.parseUrl(part.payload)
                        if (next != null) {
                            url = next
                            sawRedirect = true
                            events += "redirect"
                        }
                    }
                    UmpParts.STREAM_PROTECTION_STATUS -> {
                        val status = StreamProtectionStatus.parse(part.payload)
                        protection = status.status
                        events += "protection:${status.status}"
                        if (status.status == StreamProtectionStatus.REQUIRED) {
                            throw ArusException.ProtectionRequired(status.status)
                        }
                    }
                    UmpParts.NEXT_REQUEST_POLICY -> {
                        val policy = NextRequestPolicy.parse(part.payload)
                        policyBackoffMs = policy.pacingMs.firstOrNull() ?: policyBackoffMs
                        events += "policy"
                    }
                    UmpParts.FORMAT_ROSTER -> events += "roster"
                    UmpParts.PLAYBACK_START_POLICY -> events += "start-policy"
                    UmpParts.MEDIA_HEADER -> {
                        val header = MediaHeader.parse(part.payload)
                        headers[header.headerId] = header
                        if (header.durationMs != null) {
                            val end = (bufferedMs[header.itag] ?: 0) + header.durationMs
                            bufferedMs[header.itag] = maxOf(bufferedMs[header.itag] ?: 0, end)
                        }
                        events += "header:${header.headerId}:itag${header.itag}:seq${header.sequenceNumber}"
                    }
                    UmpParts.MEDIA -> {
                        val (headerId, offset) = UmpVarint.read(part.payload, 0)
                        val chunk = part.payload.copyOfRange(offset, part.payload.size)
                        val header = headers[headerId.toInt()]
                        sink.onChunk(headerId.toInt(), header?.formatId, chunk)
                        mediaInResponse += chunk.size
                        if (header != null) {
                            bytesByItag[header.itag] = (bytesByItag[header.itag] ?: 0) + chunk.size
                            if (header.durationMs == null && header.itag != 0) {
                                val format = session.formats.firstOrNull { it.itag == header.itag }
                                if (format != null && format.bitrate > 0) {
                                    val estimate = chunk.size * 8_000L / format.bitrate
                                    bufferedMs[header.itag] = (bufferedMs[header.itag] ?: 0) + estimate
                                }
                            }
                        }
                    }
                    UmpParts.MEDIA_END -> {
                        val (headerId, _) = UmpVarint.read(part.payload, 0)
                        endedHeaders += headerId.toInt()
                        events += "end:${headerId.toInt()}"
                    }
                    UmpParts.SABR_ERROR -> throw ArusException.SabrError(part.payload)
                    UmpParts.RELOAD_PLAYER_RESPONSE -> throw ArusException.ReloadRequired()
                    UmpParts.SABR_SEEK -> events += "seek"
                    UmpParts.SABR_CONTEXT_UPDATE -> events += "context-update"
                    else -> events += "part:${part.type}"
                }
            }

            // Termination is the server's silence, sustained: media
            // resets the streak, a redirect is movement (not silence),
            // and anything else polls patiently at the pace the
            // server's own policy asked for. "Completed" means the
            // silence came after both formats had delivered media —
            // the strongest end signal this protocol offers.
            if (mediaInResponse > 0L) {
                emptyStreak = 0
            } else if (!sawRedirect) {
                emptyStreak++
                if (emptyStreak >= MAX_EMPTY_STREAK) {
                    val deliveredBoth = (bytesByItag[video.itag] ?: 0) > 0 &&
                        (bytesByItag[audio.itag] ?: 0) > 0
                    return ArusResult(
                        bytesByItag, rn, protection,
                        completedNormally = deliveredBoth, events,
                    )
                }
                sleeper(emptyDelayMs())
            }
        }
    }

    companion object {
        const val MAX_EMPTY_STREAK = 5
        const val DEFAULT_EMPTY_DELAY_MS = 2_000L
        const val MIN_EMPTY_DELAY_MS = 1_000L
        const val MAX_EMPTY_DELAY_MS = 6_000L
    }
}
