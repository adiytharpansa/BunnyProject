package com.bunny.engine.arus

import com.bunny.engine.arus.ProtoReader.bytes
import com.bunny.engine.arus.ProtoReader.bytesList
import com.bunny.engine.arus.ProtoReader.varint

/**
 * Arus A1 — the SABR message map, every field number below earned from
 * the Fase A0 live census (LAPORAN-PROBE-A0-ARUS.md) or the project's
 * own probe requests that the server answered with media. Anything not
 * yet identified is retained raw and labelled as such; nothing here is
 * guessed into semantics it has not demonstrated.
 *
 * The request, `VideoPlaybackAbrRequest`:
 *  1  clientAbrState        (see [ClientAbrState])
 *  2  initializationFormatIds (repeated [FormatId])
 *  3  bufferedRanges        (repeated [BufferedRange])
 *  5  videoPlaybackUstreamerConfig (raw bytes from the player response)
 *  16 selectedAudioFormatId, 17 selectedVideoFormatId, 18 selectedCaptionFormatId
 *  19 streamerContext — deliberately absent: the VISIONOS conversation
 *     ran token-free without it (A0, protection status 1 throughout).
 */

/** A format identity: itag + the lastModified stamp that pins the exact rendition. */
data class FormatId(val itag: Int, val lastModified: Long, val xtags: String? = null) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
            .varintField(1, itag.toLong())
            .varintField(2, lastModified)
        if (!xtags.isNullOrEmpty()) w.stringField(3, xtags)
        return w.bytes()
    }

    companion object {
        fun parse(payload: ByteArray): FormatId {
            val fields = ProtoReader.parse(payload)
            return FormatId(
                itag = (fields.varint(1) ?: 0).toInt(),
                lastModified = fields.varint(2) ?: 0,
                xtags = fields.bytes(3)?.toString(Charsets.UTF_8),
            )
        }
    }
}

/**
 * The client's half of the ABR conversation (request field 1).
 * Only the two subfields A1 needs are modelled:
 *  28 playerTimeMs — where the playhead claims to be;
 *  40 enabledTrackTypesBitfield — 1 = audio only (observed in the
 *     ecosystem's notes); 3 = audio + video, probe-verified in A0:
 *     it is the value our own requests carried when both tracks flowed.
 */
data class ClientAbrState(val playerTimeMs: Long, val enabledTrackTypesBitfield: Int) {
    fun encode(): ByteArray = ProtoWriter()
        .varintField(28, playerTimeMs)
        .varintField(40, enabledTrackTypesBitfield.toLong())
        .bytes()

    companion object {
        const val TRACKS_AUDIO_ONLY = 1
        const val TRACKS_AUDIO_AND_VIDEO = 3
    }
}

/** One buffered span of one format (request field 3). */
data class BufferedRange(val formatId: FormatId, val startTimeMs: Long, val durationMs: Long) {
    fun encode(): ByteArray = ProtoWriter()
        .bytesField(1, formatId.encode())
        .varintField(2, startTimeMs)
        .varintField(3, durationMs)
        .bytes()
}

object AbrRequest {
    fun build(
        state: ClientAbrState,
        initializationFormatIds: List<FormatId>,
        bufferedRanges: List<BufferedRange>,
        ustreamerConfig: ByteArray,
        audioFormat: FormatId,
        videoFormat: FormatId,
        captionFormat: FormatId? = null,
    ): ByteArray {
        val w = ProtoWriter()
        w.bytesField(1, state.encode())
        for (id in initializationFormatIds) w.bytesField(2, id.encode())
        for (range in bufferedRanges) w.bytesField(3, range.encode())
        w.bytesField(5, ustreamerConfig)
        w.bytesField(16, audioFormat.encode())
        w.bytesField(17, videoFormat.encode())
        if (captionFormat != null) w.bytesField(18, captionFormat.encode())
        return w.bytes()
    }
}

/**
 * UMP part 58 — the token thermometer, the single most important byte
 * in the protocol for Bunny: 1 = no token required, 2 = token required
 * but a cold-start allowance is running, 3 = playback cannot continue
 * without a valid token. A0 measured 1 throughout, on both videos.
 */
data class StreamProtectionStatus(val status: Int) {
    companion object {
        const val NOT_REQUIRED = 1
        const val REQUIRED_BUT_COLD_START_ALLOWED = 2
        const val REQUIRED = 3

        fun parse(payload: ByteArray): StreamProtectionStatus =
            StreamProtectionStatus((ProtoReader.parse(payload).varint(1) ?: 0).toInt())
    }
}

/**
 * UMP part 35 — when to ask again, and the playback cookie to carry.
 * A0 shape: varint fields 1/2/3 (21_000 / 21_000 / 60_000 on one video,
 * 15_000 / 15_000 / 60_000 on the other — pacing targets whose exact
 * individual meanings A1 does not need) and field 7, a bytes blob
 * holding the playback cookie container, retained raw.
 */
class NextRequestPolicy(val pacingMs: List<Long>, val playbackCookie: ByteArray?) {
    companion object {
        fun parse(payload: ByteArray): NextRequestPolicy {
            val fields = ProtoReader.parse(payload)
            val pacing = listOfNotNull(fields.varint(1), fields.varint(2), fields.varint(3))
            return NextRequestPolicy(pacing, fields.bytes(7))
        }
    }
}

/** UMP part 43 — the server moves the conversation; field 1 is the new URL. */
object SabrRedirect {
    fun parseUrl(payload: ByteArray): String? =
        ProtoReader.parse(payload).bytes(1)?.toString(Charsets.UTF_8)
}

/**
 * UMP part 20 — the header that opens one media sequence. Field map
 * pinned by the A0 header dump (audio itag 140, first sequence):
 *  1 headerId, 2 video-id container (raw), 3 itag, 4 lastModified
 *  (cross-checked against the format roster — same stamp), 8 sequence
 *  number (0-based), 10 sequence duration in ms (16_229 for a sequence
 *  whose media weighed ~160 KB at 128 kbps — attribution observed, not
 *  yet load-bearing), 14 sequence media size in bytes (same standing).
 * Everything else is retained in [rawFields] for A2 to identify.
 */
class MediaHeader(
    val headerId: Int,
    val itag: Int,
    val lastModified: Long,
    val sequenceNumber: Int,
    val durationMs: Long?,
    val mediaSizeBytes: Long?,
    val rawFields: List<ProtoField>,
) {
    val formatId: FormatId get() = FormatId(itag, lastModified)

    companion object {
        fun parse(payload: ByteArray): MediaHeader {
            val fields = ProtoReader.parse(payload)
            return MediaHeader(
                headerId = (fields.varint(1) ?: 0).toInt(),
                itag = (fields.varint(3) ?: 0).toInt(),
                lastModified = fields.varint(4) ?: 0,
                sequenceNumber = (fields.varint(8) ?: 0).toInt(),
                durationMs = fields.varint(10),
                mediaSizeBytes = fields.varint(14),
                rawFields = fields,
            )
        }
    }
}

/**
 * UMP part 51 — the conversation's format roster, identified in the A0
 * header dump: field 1 repeats the video [FormatId]s, field 2 the
 * audio ones (six video + four audio on the probe video, each a bare
 * {itag, lastModified}); fields 4/5 repeat the same sets wrapped one
 * level deeper — purpose not yet identified, counted but not modelled.
 */
class FormatRoster(val video: List<FormatId>, val audio: List<FormatId>) {
    companion object {
        fun parse(payload: ByteArray): FormatRoster {
            val fields = ProtoReader.parse(payload)
            return FormatRoster(
                video = fields.bytesList(1).map { FormatId.parse(it) },
                audio = fields.bytesList(2).map { FormatId.parse(it) },
            )
        }
    }
}
