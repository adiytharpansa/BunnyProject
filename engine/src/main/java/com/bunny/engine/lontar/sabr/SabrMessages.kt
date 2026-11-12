package com.bunny.engine.lontar.sabr

import com.bunny.engine.lontar.sabr.ProtoReader.firstBytes
import com.bunny.engine.lontar.sabr.ProtoReader.firstVarint

/**
 * The SABR message set, as proven on the wire by the Fase 8 probes
 * (jilid 1 + jilid 2, 2026-11-12; logs in
 * ~/workspace/research_notes/sabr-probe/). Every field number and field
 * ORDER here is the recipe that streamed a full 398 s video token-free
 * under the VISIONOS persona — including the two "hedge" fields in
 * [ClientAbrState]. They are part of the proven recipe: removing them
 * would invalidate the evidence, so they stay, marked.
 *
 * Anyam carries NO token in any form: the streamer context's field 2
 * (where a PoToken would live) is never written. A unit test stands
 * guard over that.
 */

// ------------------------------------------------------------------ request

/** FormatId { 1: itag, 2: last_modified, 3: xtags }. */
data class SabrFormatId(
    val itag: Int,
    val lastModified: Long? = null,
    val xtags: String? = null,
) {
    fun encode(): ByteArray {
        val w = ProtoWriter().fieldVarint(1, itag.toLong())
        if (lastModified != null && lastModified != 0L) {
            w.fieldVarint(2, lastModified)
        }
        if (!xtags.isNullOrEmpty()) w.fieldString(3, xtags)
        return w.build()
    }

    companion object {
        fun decode(bytes: ByteArray): SabrFormatId? {
            val fields = ProtoReader.parseOrNull(bytes) ?: return null
            val itag = fields.firstVarint(1) ?: return null
            return SabrFormatId(
                itag = itag.toInt(),
                lastModified = fields.firstVarint(2),
                xtags = fields.firstBytes(3)?.let { String(it, Charsets.UTF_8) },
            )
        }
    }
}

/**
 * ClientAbrState — the full, honest session state of design mechanism 15:
 * real player time, a MEASURED bandwidth estimate (EWMA of actual
 * response throughput), the real viewport, and the track-type mode.
 * Field numbers: player_time_ms 15, viewport 5/6, bandwidth 10,
 * enabled_track_types 24 — plus the probe's hedge duplicates at 28 and
 * 40, sent exactly as jilid 2 sent them.
 */
class ClientAbrState(
    val playerTimeMs: Long,
    val bandwidthEstimateBps: Long,
    val viewportWidth: Int = 1920,
    val viewportHeight: Int = 1080,
    /** 0 = audio+video, 1 = audio-only. */
    val enabledTrackTypes: Int = 0,
) {
    fun encode(): ByteArray = ProtoWriter()
        .fieldVarint(15, playerTimeMs)
        .fieldVarint(28, playerTimeMs) // hedge, part of the proven recipe
        .fieldVarint(5, viewportWidth.toLong())
        .fieldVarint(6, viewportHeight.toLong())
        .fieldVarint(10, bandwidthEstimateBps)
        .fieldVarint(24, enabledTrackTypes.toLong())
        .fieldVarint(40, enabledTrackTypes.toLong()) // hedge, as proven
        .build()
}

/** BufferedRange { 1: format_id, 2: start_ms, 3: duration_ms, 4/5: segment indexes }. */
data class SabrBufferedRange(
    val formatId: SabrFormatId,
    val startTimeMs: Long,
    val durationMs: Long,
    val startSegmentIndex: Int,
    val endSegmentIndex: Int,
) {
    fun encode(): ByteArray = ProtoWriter()
        .fieldBytes(1, formatId.encode())
        .fieldVarint(2, startTimeMs)
        .fieldVarint(3, durationMs)
        .fieldVarint(4, startSegmentIndex.toLong())
        .fieldVarint(5, endSegmentIndex.toLong())
        .build()
}

/** StreamerContext.ClientInfo { 1: client_name enum, 2: version, 3: os_name, 4: os_version }. */
data class SabrClientInfo(
    val clientName: Int,
    val clientVersion: String,
    val osName: String,
    val osVersion: String,
) {
    fun encode(): ByteArray = ProtoWriter()
        .fieldVarint(1, clientName.toLong())
        .fieldString(2, clientVersion)
        .fieldString(3, osName)
        .fieldString(4, osVersion)
        .build()
}

/**
 * StreamerContext { 1: client_info, 3: playback_cookie, 5: sabr_contexts }.
 * Field 2 is where a PoToken would be carried. It is never written —
 * the Fase 8 design forbids any token in any form.
 */
class StreamerContext(
    val clientInfo: SabrClientInfo,
    val playbackCookie: ByteArray? = null,
    val sabrContexts: List<ByteArray> = emptyList(),
) {
    fun encode(): ByteArray {
        val w = ProtoWriter().fieldBytes(1, clientInfo.encode())
        if (playbackCookie != null) w.fieldBytes(3, playbackCookie)
        for (ctx in sabrContexts) w.fieldBytes(5, ctx)
        return w.build()
    }
}

/**
 * VideoPlaybackAbrRequest — the one request SABR knows.
 * Field order is the probe's: 1 abr_state, 2 selected_format_ids,
 * 3 buffered_ranges, 4 player_time_ms, 5 ustreamer_config (verbatim),
 * 16/17 preferred audio/video, 19 streamer_context.
 */
class AbrRequest(
    val abrState: ClientAbrState,
    val selectedFormatIds: List<SabrFormatId> = emptyList(),
    val bufferedRanges: List<SabrBufferedRange> = emptyList(),
    val playerTimeMs: Long,
    val ustreamerConfig: ByteArray,
    val preferredAudio: SabrFormatId? = null,
    val preferredVideo: SabrFormatId? = null,
    val streamerContext: StreamerContext,
) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.fieldBytes(1, abrState.encode())
        for (fmt in selectedFormatIds) w.fieldBytes(2, fmt.encode())
        for (range in bufferedRanges) w.fieldBytes(3, range.encode())
        w.fieldVarint(4, playerTimeMs)
        w.fieldBytes(5, ustreamerConfig)
        if (preferredAudio != null) w.fieldBytes(16, preferredAudio.encode())
        if (preferredVideo != null) w.fieldBytes(17, preferredVideo.encode())
        w.fieldBytes(19, streamerContext.encode())
        return w.build()
    }
}

// ----------------------------------------------------------------- response

/** TimeRange { 1: start_ticks, 2: duration_ticks, 3: timescale }. */
data class SabrTimeRange(
    val startTicks: Long,
    val durationTicks: Long,
    val timescale: Long,
) {
    fun startMs(): Long = startTicks * 1000 / timescale
    fun durationMs(): Long = durationTicks * 1000 / timescale
}

/**
 * MEDIA_HEADER, layout identified from live responses and verified
 * byte-exact where it counts (content_length against MEDIA payloads,
 * start_range against the spool offsets of following segments):
 * f1 header_id, f2 video_id, f3 itag, f4 last_modified, f5 xtags,
 * f6 start_range — the ABSOLUTE byte offset this segment occupies in
 * its track's file, the fact the one-spool design is built on —
 * f8 is_init_segment (1 only on the init header), f9 sequence_number
 * (media headers only), f10 bytes-per-second (content_length/duration),
 * f13 the embedded FormatId, f14 content_length. The TimeRange rides in
 * a submessage with no fixed home; [decode] finds it the way the probe
 * did, by shape and a known timescale.
 */
class MediaHeader(
    val headerId: Int,
    val videoId: String?,
    val itag: Int,
    val lastModified: Long?,
    val xtags: String?,
    val startRange: Long?,
    val isInitSegment: Boolean,
    val sequenceNumber: Int?,
    val bytesPerSecond: Long?,
    val formatId: SabrFormatId?,
    val contentLength: Long?,
    val timeRange: SabrTimeRange?,
) {
    companion object {
        private val KNOWN_TIMESCALES =
            setOf(1000L, 8000L, 90000L, 44100L, 48000L, 1000000L, 10000000L)

        fun decode(payload: ByteArray, knownItags: Set<Int>): MediaHeader? {
            val fields = ProtoReader.parseOrNull(payload) ?: return null
            val headerId = fields.firstVarint(1) ?: return null
            val formatId = findFormatId(fields, knownItags)
            val itag = formatId?.itag ?: fields.firstVarint(3)?.toInt() ?: return null
            return MediaHeader(
                headerId = headerId.toInt(),
                videoId = fields.firstBytes(2)?.let { String(it, Charsets.UTF_8) },
                itag = itag,
                lastModified = fields.firstVarint(4) ?: formatId?.lastModified,
                xtags = fields.firstBytes(5)?.let { String(it, Charsets.UTF_8) }
                    ?: formatId?.xtags,
                startRange = fields.firstVarint(6),
                isInitSegment = fields.firstVarint(8) == 1L,
                sequenceNumber = fields.firstVarint(9)?.toInt(),
                bytesPerSecond = fields.firstVarint(10),
                formatId = formatId,
                contentLength = fields.firstVarint(14),
                timeRange = findTimeRange(fields, formatId),
            )
        }

        /**
         * Locates the embedded FormatId: a bytes field whose content is a
         * message of only fields {1,2,3} with field 1 = a known itag.
         * Stricter than the probe's scan (which identified it live); the
         * shape requirement keeps the video-id string at f2 from ever
         * masquerading as one.
         */
        internal fun findFormatId(
            fields: List<ProtoField>,
            knownItags: Set<Int>,
        ): SabrFormatId? {
            for (field in fields) {
                if (field.wireType != ProtoField.WIRE_BYTES) continue
                val raw = field.bytesValue ?: continue
                val sub = ProtoReader.parseOrNull(raw) ?: continue
                if (sub.any { it.number !in 1..3 }) continue
                val fmt = SabrFormatId.decode(raw) ?: continue
                if (fmt.itag in knownItags) return fmt
            }
            return null
        }

        private fun findTimeRange(
            fields: List<ProtoField>,
            formatId: SabrFormatId?,
        ): SabrTimeRange? {
            for (field in fields) {
                if (field.wireType != ProtoField.WIRE_BYTES) continue
                val raw = field.bytesValue ?: continue
                if (formatId != null && raw.contentEquals(formatId.encode())) continue
                val sub = ProtoReader.parseOrNull(raw) ?: continue
                val start = sub.firstVarint(1) ?: continue
                val dur = sub.firstVarint(2) ?: continue
                val scale = sub.firstVarint(3) ?: continue
                if (scale in KNOWN_TIMESCALES && dur < 10 * scale * 60) {
                    return SabrTimeRange(start, dur, scale)
                }
            }
            return null
        }
    }
}

/** NEXT_REQUEST_POLICY { 1: playback_cookie, 2: backoff_time_ms }. */
class NextRequestPolicy(
    val playbackCookie: ByteArray?,
    val backoffTimeMs: Long?,
) {
    companion object {
        fun decode(payload: ByteArray): NextRequestPolicy {
            val fields = ProtoReader.parseOrNull(payload) ?: return NextRequestPolicy(null, null)
            // Schema first; the probe's shape fallback (longest bytes
            // field is the cookie) covers schema drift without guessing.
            val cookie = fields.firstBytes(1)
                ?: fields.filter { it.wireType == ProtoField.WIRE_BYTES }
                    .mapNotNull { it.bytesValue }
                    .filter { it.size > 4 }
                    .maxByOrNull { it.size }
            val backoff = fields.firstVarint(2)
                ?: fields.firstOrNull { it.wireType == ProtoField.WIRE_VARINT }?.varintValue
            return NextRequestPolicy(cookie, backoff)
        }
    }
}

/** STREAM_PROTECTION_STATUS: the attestation weather report. */
class StreamProtectionStatus(val status: Int) {
    val isOk: Boolean get() = status == STATUS_OK
    val isTokenRequired: Boolean get() = status == STATUS_TOKEN_REQUIRED

    companion object {
        const val STATUS_OK = 1
        const val STATUS_PENDING = 2
        const val STATUS_TOKEN_REQUIRED = 3

        fun decode(payload: ByteArray): StreamProtectionStatus? {
            val fields = ProtoReader.parseOrNull(payload) ?: return null
            val v = fields.firstVarint(1)
                ?: fields.firstOrNull { it.wireType == ProtoField.WIRE_VARINT }?.varintValue
                ?: return null
            return StreamProtectionStatus(v.toInt())
        }
    }
}

/** One format's row in FORMAT_INITIALIZATION_METADATA. */
data class FormatInit(
    val endSegmentNumber: Int?,
    val timescale: Long?,
    /** Every small varint field of the node, for evidence. */
    val varintFields: Map<Int, Long>,
)

/**
 * FORMAT_INITIALIZATION_METADATA: a tree of nodes; a node carrying an
 * embedded FormatId also carries that format's end_segment_number at
 * field 4 (and its timescale at field 10) — identified live in the
 * probes (audio node {4: 40, 10: 1000} for a 40-segment audio track).
 * First value per itag wins, matching session behaviour.
 */
object FormatInitMetadata {

    fun decode(payload: ByteArray, knownItags: Set<Int>): Map<Int, FormatInit> {
        val out = LinkedHashMap<Int, FormatInit>()

        fun walk(bytes: ByteArray, depth: Int) {
            if (depth > 4) return
            val fields = ProtoReader.parseOrNull(bytes) ?: return
            val fmt = MediaHeader.findFormatId(fields, knownItags)
            if (fmt != null && fmt.itag !in out) {
                val varints = LinkedHashMap<Int, Long>()
                for (f in fields) {
                    val v = f.varintValue
                    if (f.wireType == ProtoField.WIRE_VARINT && v != null &&
                        v > 0 && v < 100000
                    ) {
                        varints.putIfAbsent(f.number, v)
                    }
                }
                out[fmt.itag] = FormatInit(
                    endSegmentNumber = varints[4]?.toInt(),
                    timescale = varints[10],
                    varintFields = varints,
                )
            }
            for (f in fields) {
                val raw = f.bytesValue
                if (f.wireType == ProtoField.WIRE_BYTES && raw != null && raw.size > 2) {
                    walk(raw, depth + 1)
                }
            }
        }

        walk(payload, 0)
        return out
    }
}

/** SABR_REDIRECT: the new streaming URL rides in a string field. */
object SabrRedirect {
    fun decodeUrl(payload: ByteArray): String? {
        val fields = ProtoReader.parseOrNull(payload) ?: return null
        val first = fields.firstBytes(1)?.let { String(it, Charsets.UTF_8) }
        if (first != null && first.startsWith("http")) return first
        for (f in fields) {
            val s = f.bytesValue?.let { String(it, Charsets.UTF_8) } ?: continue
            if (s.startsWith("http")) return s
        }
        return null
    }
}

/** SABR_CONTEXT_UPDATE: submessages the server asks us to echo back. */
object SabrContextUpdate {
    fun decodeContexts(payload: ByteArray): List<ByteArray> {
        val fields = ProtoReader.parseOrNull(payload) ?: return emptyList()
        return fields.mapNotNull { f ->
            val raw = f.bytesValue
            if (f.wireType == ProtoField.WIRE_BYTES && raw != null &&
                ProtoReader.parseOrNull(raw) != null
            ) {
                raw
            } else {
                null
            }
        }
    }
}

/** MEDIA payloads are umpvarint(header_id) followed by the raw bytes. */
object SabrMedia {
    /** Returns (headerId, media bytes), or null when the prefix is broken. */
    fun split(payload: ByteArray): Pair<Int, ByteArray>? = try {
        val v = Ump.readVarint(payload, 0)
        v.value.toInt() to payload.copyOfRange(v.nextPos, payload.size)
    } catch (e: SabrParseException) {
        null
    }
}

// ------------------------------------------------------------- typed parts

/**
 * A UMP part with its meaning attached. Decoding NEVER throws: a known
 * part whose payload will not decode arrives as [Undecodable] with the
 * raw bytes kept — evidence for the failure surface, not a crash
 * (design mechanisms 25/26).
 */
sealed interface SabrPart {
    class MediaHeaderPart(val header: MediaHeader) : SabrPart
    class MediaPart(val headerId: Int, val data: ByteArray) : SabrPart
    object MediaEndPart : SabrPart
    class NextRequestPolicyPart(val policy: NextRequestPolicy) : SabrPart
    class InitMetadataPart(val formats: Map<Int, FormatInit>) : SabrPart
    class RedirectPart(val url: String?) : SabrPart
    class ErrorPart(val payload: ByteArray) : SabrPart
    class SeekPart(val payload: ByteArray) : SabrPart
    class ReloadPlayerResponsePart(val payload: ByteArray) : SabrPart
    class ContextUpdatePart(val contexts: List<ByteArray>) : SabrPart
    class ProtectionStatusPart(val status: StreamProtectionStatus) : SabrPart
    class UnknownPart(val type: Int, val payload: ByteArray) : SabrPart
    class UndecodablePart(val type: Int, val payload: ByteArray) : SabrPart
}

object SabrPartDecoder {

    fun decode(part: UmpPart, knownItags: Set<Int>): SabrPart = when (part.type) {
        Ump.PART_MEDIA_HEADER -> MediaHeader.decode(part.payload, knownItags)
            ?.let { SabrPart.MediaHeaderPart(it) }
            ?: SabrPart.UndecodablePart(part.type, part.payload)

        Ump.PART_MEDIA -> SabrMedia.split(part.payload)
            ?.let { (headerId, data) -> SabrPart.MediaPart(headerId, data) }
            ?: SabrPart.UndecodablePart(part.type, part.payload)

        Ump.PART_MEDIA_END -> SabrPart.MediaEndPart

        Ump.PART_NEXT_REQUEST_POLICY ->
            SabrPart.NextRequestPolicyPart(NextRequestPolicy.decode(part.payload))

        Ump.PART_FORMAT_INIT_METADATA -> SabrPart.InitMetadataPart(
            FormatInitMetadata.decode(part.payload, knownItags)
        )

        Ump.PART_SABR_REDIRECT -> SabrPart.RedirectPart(SabrRedirect.decodeUrl(part.payload))

        Ump.PART_SABR_ERROR -> SabrPart.ErrorPart(part.payload)

        Ump.PART_SABR_SEEK -> SabrPart.SeekPart(part.payload)

        Ump.PART_RELOAD_PLAYER_RESPONSE -> SabrPart.ReloadPlayerResponsePart(part.payload)

        Ump.PART_SABR_CONTEXT_UPDATE ->
            SabrPart.ContextUpdatePart(SabrContextUpdate.decodeContexts(part.payload))

        Ump.PART_STREAM_PROTECTION_STATUS -> StreamProtectionStatus.decode(part.payload)
            ?.let { SabrPart.ProtectionStatusPart(it) }
            ?: SabrPart.UndecodablePart(part.type, part.payload)

        else -> SabrPart.UnknownPart(part.type, part.payload)
    }

    fun decodeAll(parts: List<UmpPart>, knownItags: Set<Int>): List<SabrPart> =
        parts.map { decode(it, knownItags) }
}
