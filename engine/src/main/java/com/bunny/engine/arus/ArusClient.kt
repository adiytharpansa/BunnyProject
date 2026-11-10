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
 * sink, reports its buffered position back, and asks again, until both
 * selected formats' sequences have ended at the video's full length —
 * the loop A0's single-burst probe deliberately stopped short of.
 *
 * Behaviour pinned by the census:
 * - The conversation opens with a SABR_REDIRECT more often than not;
 *   the redirect is followed by re-POSTing to the new URL, and `rn`
 *   counts EVERY post, redirected or not.
 * - Protection status 3 ends the conversation ([ArusException.ProtectionRequired]):
 *   Arus does not mint tokens, by design — Radar's job is to see this.
 * - Buffered position is reported back as ranges per format, with the
 *   playhead at the least-buffered format's end: the honest position
 *   of a player that has played everything it holds. Durations come
 *   from MEDIA_HEADER where present, else from bytes ÷ bitrate.
 * - When a response brings no media and nothing has ended, the client
 *   waits briefly before asking again instead of hot-looping; the
 *   server's pacing fields are recorded, not obeyed literally — a
 *   player holds minutes of buffer when it paces, A1 holds none.
 *
 * Blocking by design: call from a background dispatcher.
 */
class ArusClient(
    private val transport: ArusTransport,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val maxRequests: Int = 60,
) {

    fun stream(
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
        val bufferedMs = mutableMapOf<Int, Long>() // itag -> buffered end position estimate
        var emptyResponses = 0

        fun bufferedRanges(): List<BufferedRange> = listOf(video, audio).map { format ->
            BufferedRange(format.formatId, 0, bufferedMs[format.itag] ?: 0)
        }

        fun playheadMs(): Long = minOf(bufferedMs[video.itag] ?: 0, bufferedMs[audio.itag] ?: 0)

        fun finished(): Boolean {
            val total = session.approxDurationMs ?: return false
            return playheadMs() >= total - COMPLETION_TOLERANCE_MS
        }

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

            for (part in UmpReader.parseAll(raw)) {
                when (part.type) {
                    UmpParts.SABR_REDIRECT -> {
                        val next = SabrRedirect.parseUrl(part.payload)
                        if (next != null) {
                            url = next
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
                    UmpParts.NEXT_REQUEST_POLICY -> events += "policy"
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

            if (finished()) {
                return ArusResult(bytesByItag, rn, protection, completedNormally = true, events)
            }
            if (mediaInResponse == 0L) {
                emptyResponses++
                if (emptyResponses >= MAX_EMPTY_RESPONSES) {
                    // The server has nothing more for us right now: what
                    // arrived is the honest result, flagged partial
                    // unless the length check above already passed.
                    return ArusResult(bytesByItag, rn, protection, completedNormally = false, events)
                }
                sleeper(EMPTY_RESPONSE_DELAY_MS)
            } else {
                emptyResponses = 0
            }
        }
    }

    companion object {
        const val COMPLETION_TOLERANCE_MS = 1_500L
        const val MAX_EMPTY_RESPONSES = 3
        const val EMPTY_RESPONSE_DELAY_MS = 150L
    }
}
