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
) {
    val isAudio: Boolean get() = mimeType.startsWith("audio")
    fun id(): SabrFormatId = SabrFormatId(itag, lastModified, xtags)
}

/** One received media segment of a track, in timeline + spool terms. */
data class SabrSegment(
    val sequence: Int,
    val startMs: Long,
    val durationMs: Long,
    /** Absolute byte offset of this segment inside its track's spool file. */
    val startRange: Long,
    /** Total media bytes received for this segment (it may arrive as
     * many MEDIA chunks; the length grows as they land). */
    var length: Long,
)

/** Per-itag session state. */
class SabrTrack(val format: SabrFormat) {
    val itag: Int get() = format.itag
    var endSegmentNumber: Int? = null
    var gotInit: Boolean = false
    var initBytes: Long = 0
    var mediaBytes: Long = 0
    val segments: MutableMap<Int, SabrSegment> = sortedMapOf()
    var timelineEndMs: Long = 0

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
         * same header already carried. */
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
 * the spool at the header's absolute start_range. A session is
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
        return proven.maxWithOrNull(
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
            for ((seq, segment) in trackSpool.segments) {
                track.segments[seq] = segment
                countedSegments.add(itag to seq)
                track.timelineEndMs =
                    maxOf(track.timelineEndMs, segment.startMs + segment.durationMs)
                track.mediaBytes += segment.length
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
                    } else {
                        val seq = header.sequenceNumber
                        pending.duplicate =
                            seq != null && (header.itag to seq) in countedSegments
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
                            )
                            track.segments[seq] = segment
                            pending.segment = segment
                            countedSegments.add(header.itag to seq)
                            track.timelineEndMs =
                                maxOf(track.timelineEndMs, startMs + durMs)
                        }
                    }
                    pending.segment?.let { it.length += part.data.size }
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
                            offsetInTrack = (header.startRange ?: 0) + pending.emittedBytes,
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
        fun generateCpn(): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return (1..16).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
