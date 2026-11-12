package com.bunny.engine.lontar.sabr

import kotlin.random.Random

/**
 * A format as the /player response describes it — the session's
 * registry. [id] is the wire form used inside SABR messages.
 */
data class SabrFormat(
    val itag: Int,
    val mimeType: String,
    val bitrate: Int,
    val lastModified: Long? = null,
    val xtags: String? = null,
    /** Pixel height for video formats (0 = the response didn't say). */
    val height: Int = 0,
) {
    val isAudio: Boolean get() = mimeType.startsWith("audio")
    fun id(): SabrFormatId = SabrFormatId(itag, lastModified, xtags)
}

/** One received media segment of a track, in timeline + spool terms. */
data class SabrSegment(
    val sequence: Int,
    val startMs: Long,
    val durationMs: Long,
    /**
     * The byte offset the wire announced for this segment
     * (start_range) — an offset inside the file space of the
     * RENDITION that served it, which is not necessarily the
     * space of the segments before it (see [spoolDelta]).
     */
    val startRange: Long,
    /** Total media bytes received for this segment (it may arrive as
     * many MEDIA chunks; the length grows as they land). */
    var length: Long,
    /**
     * Total bytes the wire has announced for this segment: the
     * furthest (header start + header content length) seen for it.
     * A segment whose media arrives in several header groups —
     * the normal shape for large segments — is only complete when
     * [length] reaches this. Zero means the wire never said (the
     * segment then counts as complete on any bytes, as before).
     */
    var expectedLength: Long = 0,
    /**
     * The rendition anchor: this segment's offset inside the
     * track's SPOOL file is [startRange] + [spoolDelta]. Zero
     * while a track is served by a single rendition (the wire
     * offsets ARE the spool offsets). The server can switch the
     * rendition backing a track mid-stream — the new rendition's
     * segments are byte offsets in a different file's space
     * (same itag, same timeline grid, different lastModified) —
     * so a switched segment is anchored at the spool frontier
     * instead of written at its declared offset, which would
     * tear a permanent hole into the spool (wire evidence
     * 2026-11-13: audio itag 251 of video PrLTETO4blo switched
     * rendition at segment 2; at its declared offset it left a
     * 118-byte hole the reader could never cross).
     */
    val spoolDelta: Long = 0,
) {
    /** This segment's offset inside the track's spool file. */
    val spoolStartRange: Long get() = startRange + spoolDelta

    /** True when every announced byte of this segment has landed. */
    val isComplete: Boolean
        get() = expectedLength <= 0 || length >= expectedLength
}

/** Per-itag session state. */
class SabrTrack(val format: SabrFormat) {
    val itag: Int get() = format.itag
    var endSegmentNumber: Int? = null
    var gotInit: Boolean = false
    var initBytes: Long = 0
    var mediaBytes: Long = 0
    val segments: MutableMap<Int, SabrSegment> = sortedMapOf()
    var timelineEndMs: Long = 0

    /**
     * The rendition currently being appended, identified by the
     * lastModified its headers' FormatIds carry; 0 = not yet
     * established (no init or media header has named one). A
     * header naming a DIFFERENT rendition is a rendition switch:
     * its segments live in another file's byte space and must be
     * re-anchored (see [SabrSegment.spoolDelta]).
     */
    var wireRendition: Long = 0

    /**
     * The spool shift of the rendition currently being appended:
     * spool offset = wire start_range + [currentDelta]. Zero for
     * a track's first rendition; fixed at a switch by anchoring
     * the new rendition at [spoolFrontier].
     */
    var currentDelta: Long = 0

    /**
     * The spool-space frontier: the furthest spool-space end of
     * everything this track already holds — the init segment's
     * bytes, or a segment's spool end when further. The anchor a
     * rendition switch is measured from.
     */
    val spoolFrontier: Long
        get() = maxOf(
            initBytes,
            segments.values.maxOfOrNull {
                it.startRange + it.spoolDelta + it.length
            } ?: 0L,
        )

    val isComplete: Boolean
        get() {
            val end = endSegmentNumber ?: return false
            return gotInit && segments.size == end &&
                segments.keys.containsAll((1..end).toList())
        }
}

/** Things a consumed response did, in arrival order. */
sealed interface SabrEvent {
    /** Media bytes for [header]'s track; the caller persists them. */
    class MediaChunk(
        val header: MediaHeader,
        val data: ByteArray,
        /** Absolute offset inside the track's spool file for [data]:
         * the header's start_range plus what earlier chunks of the
         * same header already carried, plus the attributed
         * segment's rendition anchor ([SabrSegment.spoolDelta]) —
         * init chunks carry no segment and keep the plain offset. */
        val offsetInTrack: Long,
    ) : SabrEvent

    object InitMetadataReceived : SabrEvent
    class PolicyUpdated(val backoffTimeMs: Long?) : SabrEvent
    class ProtectionStatusSeen(val status: Int) : SabrEvent
    class RedirectFollowed(val url: String) : SabrEvent
    object ReloadRequested : SabrEvent
    object SabrErrorSeen : SabrEvent
    class ContextsUpdated(val totalContexts: Int) : SabrEvent
    class SeekRequested(val payload: ByteArray) : SabrEvent
    class Uninterpreted(val partType: Int) : SabrEvent
}

/** The folded result of consuming one response. */
class SabrConsumeResult(
    val events: List<SabrEvent>,
    /** Media bytes that were progress (duplicates excluded). */
    val mediaBytes: Long,
    val backoffTimeMs: Long?,
    val protectionStatus: Int?,
    val sawRedirect: Boolean,
    val sawReload: Boolean,
    val sawSabrError: Boolean,
)

/**
 * The SABR session ledger — Fase 8 design §3. Owns everything that
 * must survive from one request to the next: the current streaming
 * URL (redirects move it), the playback cookie, the contexts the
 * server asked us to echo, the protection-status history, per-track
 * segment accounting, and the request factory.
 *
 * Behaviour is a faithful port of the probe session that proved the
 * door (jilid 2, 2026-11-12): buffered ranges accumulate session-wide
 * in milliseconds and merge across sequence gaps; after init, the
 * pins ADOPT the formats the server itself initialized in this
 * session (pinning a format the server never initializes earned an
 * immediate RELOAD in the probes); segment timing comes from the
 * header's time_range when present, else from content_length /
 * bytes-per-second exactly as the probe recovered it.
 *
 * Media bytes are NOT stored here: [consume] emits them as
 * [SabrEvent.MediaChunk] and the caller (the pump) writes them into
 * the spool at the chunk's spool offset — the header's start_range
 * shifted by its segment's rendition anchor. A session is
 * per-/player-response; spool state outlives it by design and is
 * rehydrated one layer up.
 */
class SabrSession(
    val videoId: String,
    initialUrl: String,
    val ustreamerConfig: ByteArray,
    val clientInfo: SabrClientInfo,
    val durationSeconds: Int,
    formats: List<SabrFormat>,
    audioItag: Int,
    videoItag: Int,
    val startTimeMs: Long = 0,
    val cpn: String = generateCpn(),
    val viewportWidth: Int = 1920,
    val viewportHeight: Int = 1080,
    /**
     * Mechanism 6: the Jatah height cap for the video slot, when
     * the app set one for this open. A preference, stated in the
     * request pins and preferred at adoption — never a reason to
     * refuse a proven format the server actually serves.
     */
    val videoCapHeightPx: Int? = null,
    /**
     * Anti-buffering design M11: the device's hardware codec
     * ceiling (mechanism 15's probe), applied at adoption: among
     * wire-proven video formats, hardware-decodable families
     * outrank richer software-only ones. [DeviceCodecCeiling.UNKNOWN]
     * filters nothing.
     */
    val codecCeiling: DeviceCodecCeiling = DeviceCodecCeiling.UNKNOWN,
) {
    private val registry: Map<Int, SabrFormat> = formats.associateBy { it.itag }

    /** Itags the decoders may trust when hunting embedded FormatIds. */
    val knownItags: Set<Int> get() = registry.keys

    var currentUrl: String = initialUrl
        private set

    /** Monotonic request counter, appended to the URL as rn=. */
    var rn: Int = 0

    var playbackCookie: ByteArray? = null
        private set

    private val _sabrContexts = ArrayList<ByteArray>()
    val sabrContexts: List<ByteArray> get() = _sabrContexts

    var protectionStatus: Int? = null
        private set
    val statusValues: MutableList<Int> = ArrayList()

    var redirects: Int = 0
        private set
    var reloads: Int = 0
        private set
    var sabrErrors: Int = 0
        private set
    var duplicateBytes: Long = 0
        private set

    var initReceived: Boolean = false
        private set

    var audioFormat: SabrFormat = registry[audioItag]
        ?: SabrFormat(audioItag, "audio/", 0)
        private set
    var videoFormat: SabrFormat = registry[videoItag]
        ?: SabrFormat(videoItag, "video/", 0)
        private set

    private val _tracks = LinkedHashMap<Int, SabrTrack>()
    val tracks: Map<Int, SabrTrack> get() = _tracks

    /** Formats the server itself initialized in THIS session, by itag. */
    private val serverInitialized = LinkedHashMap<Int, SabrFormat>()
    private val initializedInSession = HashSet<Int>()
    private val countedSegments = HashSet<Pair<Int, Int>>()
    private val pendingHeaders = HashMap<Int, PendingHeader>()

    private class PendingHeader(
        val header: MediaHeader,
        var attributed: Boolean = false,
        var duplicate: Boolean = false,
        /** The segment created on first attribution (media headers). */
        var segment: SabrSegment? = null,
        /** Bytes of this header already emitted as chunks. */
        var emittedBytes: Long = 0,
    )

    fun trackFor(itag: Int): SabrTrack = _tracks.getOrPut(itag) {
        SabrTrack(registry[itag] ?: SabrFormat(itag, "", 0))
    }

    fun pinnedItags(): Set<Int> = setOf(audioFormat.itag, videoFormat.itag)

    /** Selected format ids: pinned AND server-initialized, audio first. */
    fun selectedFormatIds(): List<SabrFormatId> = listOf(audioFormat, videoFormat)
        .filter { it.itag in initializedInSession }
        .map { it.id() }

    /**
     * One merged range per pinned track; a sequence gap splits the
     * range (the probe's rule — ranges describe only bytes truly held).
     */
    /** Grows [segment]'s announced extent from one of its headers. */
    private fun noteAnnouncedExtent(segment: SabrSegment, header: MediaHeader) {
        val start = header.startRange ?: return
        val announced = start - segment.startRange +
            (header.contentLength ?: 0)
        if (announced > segment.expectedLength) {
            segment.expectedLength = announced
        }
    }

    /**
     * The spool anchor for a segment being born from [header] on
     * [track]. The header's embedded FormatId names the rendition
     * serving it: while the track has none, that rendition is
     * adopted and the track's current delta stands (zero for a
     * fresh track; the restored delta for a resumed one). A
     * header naming the SAME rendition keeps the current delta.
     * A header naming a DIFFERENT one is a rendition switch: the
     * new segment anchors at the track's spool frontier, so the
     * spool stays one unbroken file even though the wire offsets
     * jumped into another file's space (wire evidence
     * 2026-11-13, video PrLTETO4blo: the switch declared a
     * start_range 118 bytes past the previous segment's end).
     * Headers naming no rendition (no FormatId, or a zero
     * lastModified) decide nothing.
     */
    private fun anchorDelta(track: SabrTrack, header: MediaHeader): Long {
        val lm = header.formatId?.lastModified ?: 0L
        if (lm == 0L) return track.currentDelta
        if (track.wireRendition == 0L) {
            track.wireRendition = lm
            return track.currentDelta
        }
        if (lm == track.wireRendition) return track.currentDelta
        val delta = track.spoolFrontier - (header.startRange ?: 0)
        track.wireRendition = lm
        track.currentDelta = delta
        return delta
    }

    fun bufferedRanges(): List<SabrBufferedRange> {
        val out = ArrayList<SabrBufferedRange>()
        for (slot in listOf(audioFormat, videoFormat)) {
            val track = _tracks[slot.itag] ?: continue
            if (track.segments.isEmpty()) continue
            val segs = track.segments.values.sortedBy { it.sequence }
            var runStart = segs.first()
            var prev = segs.first()
            fun flush() {
                out.add(
                    SabrBufferedRange(
                        formatId = track.format.id(),
                        startTimeMs = runStart.startMs,
                        durationMs = (prev.startMs + prev.durationMs) - runStart.startMs,
                        startSegmentIndex = runStart.sequence,
                        endSegmentIndex = prev.sequence,
                    )
                )
            }
            for (seg in segs.drop(1)) {
                if (seg.sequence == prev.sequence + 1) {
                    prev = seg
                } else {
                    flush()
                    runStart = seg
                    prev = seg
                }
            }
            flush()
        }
        return out
    }

    /** The playhead the next request should claim: the buffered end. */
    fun bufferedEndMs(): Long {
        val ends = listOf(audioFormat, videoFormat)
            .mapNotNull { _tracks[it.itag] }
            .filter { it.segments.isNotEmpty() }
            .map { it.timelineEndMs }
        return when (ends.size) {
            0 -> 0
            1 -> ends[0]
            else -> ends.min()
        }
    }

    fun isComplete(): Boolean {
        val pinned = listOf(audioFormat, videoFormat)
            .mapNotNull { _tracks[it.itag] }
        return pinned.size == 2 && pinned.all { it.isComplete }
    }

    /**
     * Mechanism 23, full form: the TAIL of the stream is on disk —
     * every pinned track exists and, where the wire named its end
     * segment, holds that final segment. Weaker than [isComplete]
     * (earlier gaps may remain) but the fact a pass seeded
     * mid-stream can actually reach: such a session can never
     * satisfy [isComplete] while a gap behind it is unfilled, yet
     * its pump has nothing left to ask for at the playhead's end
     * of the timeline. A pinned track that never materialised
     * (no headers, no restore) blocks the verdict — an untouched
     * slot proves nothing about the tail.
     */
    fun isTailComplete(): Boolean {
        var anyKnownEnd = false
        for (format in listOf(audioFormat, videoFormat)) {
            val track = _tracks[format.itag] ?: return false
            val end = track.endSegmentNumber ?: continue
            anyKnownEnd = true
            if (!track.segments.containsKey(end)) return false
        }
        return anyKnownEnd
    }

    /**
     * The timeline holes of one track, as [start, end) millisecond
     * intervals: a head hole when the first segment starts past
     * [GAP_EPSILON_MS], and a hole between consecutive segments
     * (in startMs order) whose joint leaves more than the epsilon
     * uncovered. A track with no segments has no holes — it is
     * untouched, not gapped (the initial pass owns it).
     */
    private fun gapsOf(track: SabrTrack): List<LongRange> {
        val segs = track.segments.values.sortedBy { it.startMs }
        if (segs.isEmpty()) return emptyList()
        val gaps = ArrayList<LongRange>()
        val first = segs.first()
        if (first.startMs > GAP_EPSILON_MS) {
            gaps.add(0L until first.startMs)
        }
        for (i in 1 until segs.size) {
            val prevEnd = segs[i - 1].startMs + segs[i - 1].durationMs
            if (segs[i].startMs > prevEnd + GAP_EPSILON_MS) {
                gaps.add(prevEnd until segs[i].startMs)
            }
        }
        return gaps
    }

    /**
     * Mechanism 23: where the earliest timeline hole across the
     * pinned tracks begins (a backfill pass seeds there), or null
     * when the spooled timeline is unbroken. Only tracks holding
     * segments vote — an untouched track is the initial pass's
     * job, not a hole.
     */
    fun earliestGapStartMs(): Long? {
        var earliest: Long? = null
        for (format in listOf(audioFormat, videoFormat)) {
            val track = _tracks[format.itag] ?: continue
            val gap = gapsOf(track).firstOrNull() ?: continue
            earliest = earliest?.let { minOf(it, gap.first) } ?: gap.first
        }
        return earliest
    }

    /**
     * Mechanism 23: does the spool already serve [positionMs] —
     * the playhead may sit there without waiting for the roll?
     * The brief's rule: at or under the buffered end (the minimum
     * across the tracks that hold segments) AND inside no track's
     * hole. Position zero is trivially covered. With nothing
     * rolled yet, nothing past zero is covered.
     */
    fun coversPosition(positionMs: Long): Boolean {
        if (positionMs <= 0) return true
        val voters = listOf(audioFormat, videoFormat)
            .mapNotNull { _tracks[it.itag] }
            .filter { it.segments.isNotEmpty() }
        if (voters.isEmpty()) return false
        if (positionMs > bufferedEndMs()) return false
        return voters.none { track ->
            gapsOf(track).any { positionMs in it }
        }
    }

    /**
     * The format the WIRE has proven for one slot: its init segment
     * actually arrived in this session. Among proven formats, one
     * already carrying media segments wins, then the highest bitrate.
     * Null while nothing has flowed — paper (the player response, the
     * init metadata) is never proof: the metadata may list formats
     * the server then declines to stream, and addressing one of those
     * leaves the player waiting on a spool track that stays empty
     * forever (device evidence 2026-11-12: the player addressed itag
     * 137 while its spool file held 0 bytes and the media flowed
     * under another itag).
     */
    fun wireProvenFormat(audio: Boolean): SabrFormat? {
        val proven = initializedInSession
            .mapNotNull { registry[it] }
            .filter { it.isAudio == audio }
        if (proven.isEmpty()) return null
        // Mechanism 6: under a Jatah cap, a proven format at or
        // under the cap outranks a richer one over it — the cap is
        // the user's, and adoption is where it can still be kept.
        // When nothing proven fits, the richest proven format
        // stands (the server served what it served; playback
        // first, and the request already stated the cap).
        val cap = videoCapHeightPx
        val capPool = if (!audio && cap != null) {
            val fitting = proven.filter { it.height <= 0 || it.height <= cap }
            fitting.ifEmpty { proven }
        } else {
            proven
        }
        // Anti-buffering M11: inside the pool, a proven format the
        // device decodes in HARDWARE outranks a richer one it can
        // only decode in software — software decode spends frames
        // (M2's dropped-frame count is the evidence that convicts
        // that road). A preference only, like the cap: when nothing
        // proven is hardware-decodable, the pool stands — the wire
        // served what it served.
        val pool = if (!audio) {
            val hardware = capPool.filter {
                codecCeiling.hardwareOk(codecFamilyOf(it.mimeType))
            }
            hardware.ifEmpty { capPool }
        } else {
            capPool
        }
        return pool.maxWithOrNull(
            compareBy<SabrFormat> { trackFor(it.itag).segments.isNotEmpty() }
                .thenBy { it.bitrate },
        )
    }

    /**
     * Adopts, per slot, the format the wire has proven — never a
     * format that exists only in the init metadata. Runs once init
     * has arrived (the pump calls it after every response, as the
     * probe did).
     */
    fun adoptServerPins() {
        wireProvenFormat(audio = true)?.let {
            if (it.itag != audioFormat.itag) audioFormat = it
        }
        wireProvenFormat(audio = false)?.let {
            if (it.itag != videoFormat.itag) videoFormat = it
        }
    }

    /**
     * Rehydrates this session's track state from a spool an earlier
     * session filled (the RELOAD road, design mechanism 18): the new
     * session must tell the truth about what the disk already holds —
     * its buffered ranges, its selections, its playhead — or it would
     * re-ask for segments it already owns. Tracks the spool holds
     * beyond the opening pins (the server's adopted formats) are
     * loaded too, and the pin adoption runs over the restored state.
     */
    fun restoreFrom(spool: VideoSpool) {
        fun load(itag: Int) {
            val trackSpool = spool.track(itag)
            if (trackSpool.initLength == 0L && trackSpool.segments.isEmpty()) return
            val track = trackFor(itag)
            trackSpool.endSegmentNumber?.let {
                if (track.endSegmentNumber == null) track.endSegmentNumber = it
            }
            if (trackSpool.initLength > 0) {
                track.gotInit = true
                track.initBytes = trackSpool.initLength
            }
            // The track's wire state rides along: the rendition
            // being appended and its spool anchor, so a session
            // resumed over this spool keeps appending in the same
            // space, and a later rendition switch is measured from
            // the restored frontier. A manifest written before
            // rendition anchoring carries neither; the format's
            // own lastModified is the only rendition identity
            // known then, with a zero delta context — the old
            // behaviour, exact for the single-rendition spools
            // those manifests describe.
            track.wireRendition = if (trackSpool.wireRendition != 0L) {
                trackSpool.wireRendition
            } else {
                track.format.lastModified ?: 0L
            }
            track.currentDelta = trackSpool.currentDelta
            for ((seq, segment) in trackSpool.segments) {
                // The manifest stores SPOOL space; this ledger
                // speaks WIRE space. Convert back — the segment
                // keeps its spoolDelta, so the pump re-derives
                // the spool placement exactly.
                val restored = segment.copy(
                    startRange = segment.startRange - segment.spoolDelta,
                )
                track.segments[seq] = restored
                countedSegments.add(itag to seq)
                track.timelineEndMs =
                    maxOf(
                        track.timelineEndMs,
                        restored.startMs + restored.durationMs,
                    )
                track.mediaBytes += restored.length
            }
            initializedInSession.add(itag)
            registry[itag]?.let { serverInitialized[itag] = it }
            initReceived = true
        }
        load(audioFormat.itag)
        load(videoFormat.itag)
        for (itag in spool.trackItags()) {
            if (itag != audioFormat.itag && itag != videoFormat.itag) load(itag)
        }
        if (initReceived) adoptServerPins()
    }

    /**
     * Mechanism 14 — adoption of the classic road's contributions:
     * while this video played over the classic road, the player
     * downloaded bytes into the same spool files and the spool
     * recorded the covered ranges as a sidecar, stamped with the
     * rendition that served them (see [TrackSpool.classicRanges]).
     * Classic bytes belong to the classic rendition's byte space,
     * so a restored segment may only grow over them when every
     * part of the identity matches: the sidecar's rendition
     * ([TrackSpool.classicRendition]) equals this track's wire
     * rendition (the one [restoreFrom] brought back from the
     * manifest, or the registry format's lastModified when the
     * manifest named none) AND the track's current anchor delta
     * is zero — a shifted track's spool offsets are not the
     * classic file's offsets, and no translation between the two
     * spaces is ever guessed.
     *
     * What adoption may do, per track passing the gate:
     * - Segments: a restored segment whose announced extent
     *   ([SabrSegment.expectedLength], named by a SABR header)
     *   exceeds the bytes SABR itself vouched for grows to that
     *   extent when classic ranges FULLY cover
     *   [spoolStartRange, spoolStartRange + expectedLength) —
     *   the bytes are already in the file; the next pump persist
     *   writes the grown length back into the manifest, which is
     *   what lets the reader serve them. Partial coverage extends
     *   nothing: a segment is whole or it is SABR's to finish.
     * - Init: only in the one shape where the classic file itself
     *   defines the length — a restored segment 1 exists and
     *   classic ranges fully cover [0, segment 1's spool start):
     *   the covered run IS the init segment, so the track gains
     *   it (gotInit, initBytes = the covered length). Any other
     *   shape leaves the init unadopted; SABR will fetch its own.
     *
     * Returns how many segments were extended.
     */
    fun adoptClassicRanges(spool: VideoSpool): Int {
        var extended = 0
        val spoolItags = spool.trackItags()
        for ((itag, track) in _tracks) {
            if (itag !in spoolItags) continue
            val trackSpool = spool.track(itag)
            val classicRendition = trackSpool.classicRendition
            if (classicRendition == 0L) continue
            val rendition = if (track.wireRendition != 0L) {
                track.wireRendition
            } else {
                track.format.lastModified ?: 0L
            }
            if (rendition == 0L || classicRendition != rendition) continue
            if (track.currentDelta != 0L) continue
            val ranges = trackSpool.classicRanges()
            if (ranges.isEmpty()) continue
            fun covered(start: Long, length: Long): Boolean =
                ranges.any { it.first <= start && start + length <= it.last + 1 }
            if (!track.gotInit) {
                val first = track.segments[1]
                if (first != null && first.spoolStartRange > 0 &&
                    covered(0L, first.spoolStartRange)
                ) {
                    track.gotInit = true
                    track.initBytes = first.spoolStartRange
                }
            }
            for (segment in track.segments.values) {
                if (segment.expectedLength > segment.length &&
                    covered(segment.spoolStartRange, segment.expectedLength)
                ) {
                    segment.length = segment.expectedLength
                    extended++
                }
            }
        }
        return extended
    }

    fun buildRequest(
        playerTimeMs: Long,
        bandwidthBps: Long,
        pinFormats: Boolean,
    ): ByteArray = AbrRequest(
        abrState = ClientAbrState(
            playerTimeMs = playerTimeMs,
            bandwidthEstimateBps = bandwidthBps,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        ),
        selectedFormatIds = if (initReceived) selectedFormatIds() else emptyList(),
        bufferedRanges = bufferedRanges(),
        playerTimeMs = playerTimeMs,
        ustreamerConfig = ustreamerConfig,
        preferredAudio = if (pinFormats) audioFormat.id() else null,
        preferredVideo = if (pinFormats) videoFormat.id() else null,
        streamerContext = StreamerContext(
            clientInfo = clientInfo,
            playbackCookie = playbackCookie,
            sabrContexts = _sabrContexts,
        ),
    ).encode()

    /** Folds one decoded response into the ledger. Never throws. */
    fun consume(parts: List<SabrPart>): SabrConsumeResult {
        val events = ArrayList<SabrEvent>()
        var mediaBytes = 0L
        var backoffMs: Long? = null
        var sps: Int? = null
        var sawRedirect = false
        var sawReload = false
        var sawError = false

        for (part in parts) {
            when (part) {
                is SabrPart.InitMetadataPart -> {
                    initReceived = true
                    for ((itag, init) in part.formats) {
                        val track = trackFor(itag)
                        if (track.endSegmentNumber == null) {
                            track.endSegmentNumber = init.endSegmentNumber
                        }
                        registry[itag]?.let { serverInitialized[itag] = it }
                    }
                    events.add(SabrEvent.InitMetadataReceived)
                }

                is SabrPart.MediaHeaderPart -> {
                    val header = part.header
                    val track = trackFor(header.itag)
                    val pending = PendingHeader(header)
                    if (header.isInitSegment) {
                        pending.duplicate = track.gotInit
                        initializedInSession.add(header.itag)
                        registry[header.itag]?.let { serverInitialized[header.itag] = it }
                        // The init segment also names the track's
                        // first rendition — its header carries a
                        // FormatId like any other, and the spool
                        // frontier starts at the init's length.
                        // An init arriving after a rendition is
                        // established decides nothing (its
                        // duplicate suppression above governs it).
                        val lm = header.formatId?.lastModified ?: 0L
                        if (track.wireRendition == 0L && lm != 0L) {
                            track.wireRendition = lm
                        }
                    } else {
                        // A segment's media may arrive in SEVERAL
                        // header groups (large segments do): a
                        // repeated sequence number is a CONTINUATION,
                        // not a duplicate. Bind this header to the
                        // segment that already exists so its chunks
                        // land at their absolute offsets and grow
                        // the same segment. (Suppressing these as
                        // duplicates — the pre-2026-11-12 behaviour —
                        // tore a permanent hole into the spool at
                        // the first split segment: the session
                        // ledger looked complete while the file
                        // starved the player mid-playback.)
                        val seq = header.sequenceNumber
                        val existing = seq?.let { track.segments[it] }
                        if (existing != null) {
                            pending.segment = existing
                            pending.attributed = true
                            noteAnnouncedExtent(existing, header)
                        }
                    }
                    pendingHeaders[header.headerId] = pending
                }

                is SabrPart.MediaPart -> {
                    val pending = pendingHeaders[part.headerId]
                    if (pending == null) {
                        // Unattributed media still counts as flow.
                        mediaBytes += part.data.size
                        continue
                    }
                    if (pending.duplicate) {
                        duplicateBytes += part.data.size
                        continue
                    }
                    val header = pending.header
                    val track = trackFor(header.itag)
                    if (!pending.attributed) {
                        pending.attributed = true
                        if (header.isInitSegment) {
                            track.gotInit = true
                        } else {
                            val seq = header.sequenceNumber
                                ?: (track.segments.keys.maxOrNull() ?: 0) + 1
                            val (startMs, durMs) = segmentTimes(header, track)
                            val segment = SabrSegment(
                                sequence = seq,
                                startMs = startMs,
                                durationMs = durMs,
                                startRange = header.startRange ?: 0,
                                length = 0,
                                spoolDelta = anchorDelta(track, header),
                            )
                            track.segments[seq] = segment
                            pending.segment = segment
                            countedSegments.add(header.itag to seq)
                            noteAnnouncedExtent(segment, header)
                            track.timelineEndMs =
                                maxOf(track.timelineEndMs, startMs + durMs)
                        }
                    }
                    // The vouched length grows only across bytes
                    // that actually extend the received extent: a
                    // continuation group extends it, a re-sent
                    // group rewrites the same bytes in place and
                    // must not inflate it, and a group beyond a
                    // gap must not paper over the gap.
                    pending.segment?.let { seg ->
                        val chunkStart =
                            (header.startRange ?: 0) + pending.emittedBytes
                        val receivedEnd = seg.startRange + seg.length
                        if (chunkStart <= receivedEnd &&
                            chunkStart + part.data.size > receivedEnd
                        ) {
                            seg.length =
                                chunkStart + part.data.size - seg.startRange
                        }
                    }
                    if (header.isInitSegment) {
                        track.initBytes += part.data.size
                    } else {
                        track.mediaBytes += part.data.size
                    }
                    mediaBytes += part.data.size
                    events.add(
                        SabrEvent.MediaChunk(
                            header = header,
                            data = part.data,
                            offsetInTrack = (header.startRange ?: 0) +
                                pending.emittedBytes +
                                (pending.segment?.spoolDelta ?: 0),
                        )
                    )
                    pending.emittedBytes += part.data.size
                }

                is SabrPart.MediaEndPart -> Unit

                is SabrPart.NextRequestPolicyPart -> {
                    if (part.policy.playbackCookie != null) {
                        playbackCookie = part.policy.playbackCookie
                    }
                    backoffMs = part.policy.backoffTimeMs
                    events.add(SabrEvent.PolicyUpdated(part.policy.backoffTimeMs))
                }

                is SabrPart.ProtectionStatusPart -> {
                    protectionStatus = part.status.status
                    statusValues.add(part.status.status)
                    sps = part.status.status
                    events.add(SabrEvent.ProtectionStatusSeen(part.status.status))
                }

                is SabrPart.RedirectPart -> {
                    if (part.url != null) {
                        currentUrl = part.url
                        redirects++
                        sawRedirect = true
                        events.add(SabrEvent.RedirectFollowed(part.url))
                    }
                }

                is SabrPart.ErrorPart -> {
                    sabrErrors++
                    sawError = true
                    events.add(SabrEvent.SabrErrorSeen)
                }

                is SabrPart.ReloadPlayerResponsePart -> {
                    reloads++
                    sawReload = true
                    events.add(SabrEvent.ReloadRequested)
                }

                is SabrPart.ContextUpdatePart -> {
                    _sabrContexts.addAll(part.contexts)
                    events.add(SabrEvent.ContextsUpdated(_sabrContexts.size))
                }

                is SabrPart.SeekPart -> events.add(SabrEvent.SeekRequested(part.payload))

                is SabrPart.UnknownPart -> events.add(SabrEvent.Uninterpreted(part.type))

                is SabrPart.UndecodablePart -> events.add(SabrEvent.Uninterpreted(part.type))
            }
        }

        return SabrConsumeResult(
            events = events,
            mediaBytes = mediaBytes,
            backoffTimeMs = backoffMs,
            protectionStatus = sps,
            sawRedirect = sawRedirect,
            sawReload = sawReload,
            sawSabrError = sawError,
        )
    }

    /**
     * (startMs, durMs) for a media segment — the probe's recovery,
     * verbatim in spirit: the header's time_range when it carries one
     * (audio does); otherwise duration falls out of content_length /
     * bytes-per-second (rint = the probe's round-half-even), and the
     * start is the track's running timeline end, or the seek anchor
     * for a seek-started track.
     */
    private fun segmentTimes(header: MediaHeader, track: SabrTrack): Pair<Long, Long> {
        header.timeRange?.let { return it.startMs() to it.durationMs() }
        val cl = header.contentLength
        val bps = header.bytesPerSecond
        val endSeg = track.endSegmentNumber
        val durMs = when {
            cl != null && bps != null && bps > 0 ->
                Math.rint(cl * 1000.0 / bps).toLong()
            endSeg != null && endSeg > 0 -> durationSeconds * 1000L / endSeg
            else -> 0L
        }
        val seq = header.sequenceNumber
        val startMs = when {
            track.segments.isNotEmpty() -> track.timelineEndMs
            startTimeMs > 0 -> startTimeMs
            endSeg != null && endSeg > 0 && seq != null ->
                (seq - 1) * (durationSeconds * 1000L / endSeg)
            else -> track.timelineEndMs
        }
        return startMs to durMs
    }

    companion object {
        /**
         * Mechanism 23: segment joints within this slack count as
         * continuous — time_range arithmetic and per-format
         * rounding leave sub-frame seams a backfill pass would
         * only chase as noise.
         */
        const val GAP_EPSILON_MS: Long = 250L

        fun generateCpn(): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return (1..16).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
