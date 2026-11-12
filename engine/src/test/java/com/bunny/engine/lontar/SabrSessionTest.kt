package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.DeviceCodecCeiling
import com.bunny.engine.lontar.sabr.FormatInit
import com.bunny.engine.lontar.sabr.MediaHeader
import com.bunny.engine.lontar.sabr.NextRequestPolicy
import com.bunny.engine.lontar.sabr.ProtoField
import com.bunny.engine.lontar.sabr.ProtoReader
import com.bunny.engine.lontar.sabr.ProtoWriter
import com.bunny.engine.lontar.sabr.SabrClientInfo
import com.bunny.engine.lontar.sabr.SabrEvent
import com.bunny.engine.lontar.sabr.SabrFormat
import com.bunny.engine.lontar.sabr.SabrFormatId
import com.bunny.engine.lontar.sabr.SabrPart
import com.bunny.engine.lontar.sabr.SabrSession
import com.bunny.engine.lontar.sabr.SabrSegment
import com.bunny.engine.lontar.sabr.SabrTimeRange
import com.bunny.engine.lontar.sabr.SpoolStore
import com.bunny.engine.lontar.sabr.VideoSpool
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Session ledger units the replay does not cover on its own. */
class SabrSessionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun session(durationSeconds: Int = 100): SabrSession = SabrSession(
        videoId = "vid",
        initialUrl = "https://x.invalid/s",
        ustreamerConfig = byteArrayOf(9, 9),
        clientInfo = SabrClientInfo(101, "1.04", "visionOS", "2.0"),
        durationSeconds = durationSeconds,
        formats = listOf(
            SabrFormat(251, "audio/webm", 160_000, 111L, "x"),
            SabrFormat(136, "video/mp4", 2_000_000, 222L, null),
        ),
        audioItag = 251,
        videoItag = 136,
        cpn = "CPN",
    )

    private fun fields(bytes: ByteArray): List<ProtoField> =
        ProtoReader.parseOrNull(bytes)!!

    private fun List<ProtoField>.bytesOf(n: Int): ByteArray? =
        firstOrNull { it.number == n }?.bytesValue

    private fun List<ProtoField>.varintOf(n: Int): Long? =
        firstOrNull { it.number == n }?.varintValue

    private fun List<ProtoField>.allBytesOf(n: Int): List<ByteArray> =
        filter { it.number == n }.mapNotNull { it.bytesValue }

    private fun streamerContext(request: ByteArray): List<ProtoField> =
        fields(fields(request).bytesOf(19)!!)

    private fun header(
        id: Int,
        itag: Int,
        seq: Int?,
        startRange: Long,
        contentLength: Long,
        init: Boolean = false,
        timeRange: SabrTimeRange? = null,
        bytesPerSecond: Long? = null,
    ): SabrPart.MediaHeaderPart = SabrPart.MediaHeaderPart(
        MediaHeader(
            headerId = id,
            videoId = "vid",
            itag = itag,
            lastModified = if (itag == 251) 111L else 222L,
            xtags = if (itag == 251) "x" else null,
            startRange = startRange,
            isInitSegment = init,
            sequenceNumber = seq,
            bytesPerSecond = bytesPerSecond,
            formatId = SabrFormatId(
                itag, if (itag == 251) 111L else 222L, if (itag == 251) "x" else null
            ),
            contentLength = contentLength,
            timeRange = timeRange,
        )
    )

    @Test
    fun adoptionFollowsTheWireNotTheMetadata() {
        val s = SabrSession(
            videoId = "vid",
            initialUrl = "https://x.invalid/s",
            ustreamerConfig = byteArrayOf(9, 9),
            clientInfo = SabrClientInfo(101, "1.04", "visionOS", "2.0"),
            durationSeconds = 100,
            formats = listOf(
                SabrFormat(251, "audio/webm", 160_000, 111L, "x"),
                SabrFormat(136, "video/mp4", 2_000_000, 222L, null),
                SabrFormat(137, "video/mp4", 4_500_000, 333L, null),
            ),
            audioItag = 251,
            videoItag = 137,
            cpn = "CPN",
        )
        // The init metadata lists BOTH video formats — paper only.
        s.consume(
            listOf(
                SabrPart.InitMetadataPart(
                    mapOf(
                        136 to FormatInit(40, 1000, emptyMap()),
                        137 to FormatInit(40, 1000, emptyMap()),
                    )
                )
            )
        )
        // Nothing has flowed yet, so nothing is proven.
        assertNull(s.wireProvenFormat(audio = false))
        // The wire proves 136 only: its init segment and one media
        // segment actually arrive. 137 never leaves the paper.
        s.consume(
            listOf(
                header(0, 136, null, 0, 100, init = true),
                SabrPart.MediaPart(0, ByteArray(100)),
                header(1, 136, 1, 100, 500),
                SabrPart.MediaPart(1, ByteArray(500)),
            )
        )
        assertEquals(136, s.wireProvenFormat(audio = false)?.itag)
        s.adoptServerPins()
        // Adoption follows the wire (136), not the metadata's
        // higher-bitrate paper format (137) — the player addresses
        // what flows, or waits forever on an empty spool track
        // (device evidence 2026-11-12: video spool file 0 KB).
        assertEquals(136, s.videoFormat.itag)
    }

    @Test
    fun adoptionPrefersTheProvenFormatThatAlreadyCarriesMedia() {
        val s = SabrSession(
            videoId = "vid",
            initialUrl = "https://x.invalid/s",
            ustreamerConfig = byteArrayOf(9, 9),
            clientInfo = SabrClientInfo(101, "1.04", "visionOS", "2.0"),
            durationSeconds = 100,
            formats = listOf(
                SabrFormat(251, "audio/webm", 160_000, 111L, "x"),
                SabrFormat(136, "video/mp4", 2_000_000, 222L, null),
                SabrFormat(137, "video/mp4", 4_500_000, 333L, null),
            ),
            audioItag = 251,
            videoItag = 137,
            cpn = "CPN",
        )
        s.consume(
            listOf(
                SabrPart.InitMetadataPart(
                    mapOf(
                        136 to FormatInit(40, 1000, emptyMap()),
                        137 to FormatInit(40, 1000, emptyMap()),
                    )
                )
            )
        )
        // BOTH video formats are proven at init-segment level, but
        // only 136 ever carries a media segment.
        s.consume(
            listOf(
                header(0, 137, null, 0, 100, init = true),
                SabrPart.MediaPart(0, ByteArray(100)),
                header(1, 136, null, 0, 100, init = true),
                SabrPart.MediaPart(1, ByteArray(100)),
                header(2, 136, 1, 100, 500),
                SabrPart.MediaPart(2, ByteArray(500)),
            )
        )
        s.adoptServerPins()
        // The format carrying media wins over the higher-bitrate
        // format that only ever sent its init segment.
        assertEquals(136, s.videoFormat.itag)
    }

    @Test
    fun requestPinsTheWireProvenFormatNotThePaperPin() {
        val s = SabrSession(
            videoId = "vid",
            initialUrl = "https://x.invalid/s",
            ustreamerConfig = byteArrayOf(9, 9),
            clientInfo = SabrClientInfo(101, "1.04", "visionOS", "2.0"),
            durationSeconds = 100,
            formats = listOf(
                SabrFormat(251, "audio/webm", 160_000, 111L, "x"),
                SabrFormat(136, "video/mp4", 2_000_000, 222L, null),
                SabrFormat(137, "video/mp4", 4_500_000, 333L, null),
            ),
            audioItag = 251,
            videoItag = 137,
            cpn = "CPN",
        )
        s.consume(
            listOf(
                SabrPart.InitMetadataPart(
                    mapOf(
                        136 to FormatInit(40, 1000, emptyMap()),
                        137 to FormatInit(40, 1000, emptyMap()),
                    )
                )
            )
        )
        s.consume(
            listOf(
                header(0, 136, null, 0, 100, init = true),
                SabrPart.MediaPart(0, ByteArray(100)),
                header(1, 136, 1, 100, 500),
                SabrPart.MediaPart(1, ByteArray(500)),
            )
        )
        s.adoptServerPins()
        val f = fields(s.buildRequest(0, 5_000_000, pinFormats = true))
        // Field 17 is the preferred-video FormatId; its field 1 is
        // the itag. The request must pin the wire-proven 136, never
        // the paper pin 137 the player response arrived with.
        assertEquals(136L, fields(f.bytesOf(17)!!).varintOf(1))
        // Field 2 (selected ids) likewise names only proven formats.
        val selected = f.allBytesOf(2).map { fields(it).varintOf(1) }
        assertTrue(136L in selected)
        assertTrue(137L !in selected)
    }

    @Test
    fun coldRequestCarriesNoSelectionRangesPinsOrCookie() {
        val request = session().buildRequest(0, 5_000_000, pinFormats = false)
        val f = fields(request)
        assertNull(f.bytesOf(2))
        assertNull(f.bytesOf(3))
        assertNull(f.bytesOf(16))
        assertNull(f.bytesOf(17))
        assertEquals(0L, f.varintOf(4))
        assertNull(streamerContext(request).bytesOf(3))
    }

    @Test
    fun cookieIsReplacedByTheNewerPolicy() {
        val s = session()
        val cookieA = byteArrayOf(1, 2, 3)
        val cookieB = byteArrayOf(4, 5)
        s.consume(
            listOf(
                SabrPart.NextRequestPolicyPart(
                    NextRequestPolicy(backoffTimeMs = 1000, playbackCookie = cookieA)
                )
            )
        )
        var ctx = streamerContext(s.buildRequest(0, 5_000_000, false))
        assertArrayEquals(cookieA, ctx.bytesOf(3))
        s.consume(
            listOf(
                SabrPart.NextRequestPolicyPart(
                    NextRequestPolicy(backoffTimeMs = 1000, playbackCookie = cookieB)
                )
            )
        )
        ctx = streamerContext(s.buildRequest(0, 5_000_000, false))
        assertArrayEquals(cookieB, ctx.bytesOf(3))
    }

    @Test
    fun contextsAccumulateAndAreEchoed() {
        val s = session()
        val subA = ProtoWriter().fieldVarint(1, 7).build()
        val subB = ProtoWriter().fieldVarint(1, 8).build()
        s.consume(
            listOf(SabrPart.ContextUpdatePart(listOf(subA, subB)))
        )
        val echoed = streamerContext(s.buildRequest(0, 5_000_000, false)).allBytesOf(5)
        assertEquals(2, echoed.size)
        assertArrayEquals(subA, echoed[0])
        assertArrayEquals(subB, echoed[1])
    }

    @Test
    fun rangesSplitOnASequenceGap() {
        val s = session()
        s.consume(
            listOf(
                SabrPart.InitMetadataPart(mapOf(251 to FormatInit(10, 1000, emptyMap())))
            )
        )
        s.consume(
            listOf(
                header(0, 251, null, 0, 10, init = true),
                SabrPart.MediaPart(0, ByteArray(10)),
            )
        )
        fun segment(id: Int, seq: Int, startMs: Long, startRange: Long) {
            s.consume(
                listOf(
                    header(
                        id, 251, seq, startRange, 100,
                        timeRange = SabrTimeRange(startMs, 1000, 1000),
                    ),
                    SabrPart.MediaPart(id, ByteArray(100)),
                )
            )
        }
        segment(1, 1, 0, 10)
        segment(2, 2, 1000, 110)
        segment(3, 4, 3000, 210)

        val ranges = s.bufferedRanges()
        assertEquals(2, ranges.size)
        assertEquals(0L, ranges[0].startTimeMs)
        assertEquals(2000L, ranges[0].durationMs)
        assertEquals(1, ranges[0].startSegmentIndex)
        assertEquals(2, ranges[0].endSegmentIndex)
        val second = ranges[1]
        assertEquals(3000L, second.startTimeMs)
        assertEquals(1000L, second.durationMs)
        assertEquals(4, second.startSegmentIndex)
        assertEquals(4, second.endSegmentIndex)
        assertEquals(4000L, s.bufferedEndMs())
    }

    @Test
    fun bufferedEndIsTheMinimumAcrossTracks() {
        val s = session()
        s.consume(
            listOf(
                SabrPart.InitMetadataPart(
                    mapOf(
                        251 to FormatInit(10, 1000, emptyMap()),
                        136 to FormatInit(10, 15360, emptyMap()),
                    )
                )
            )
        )
        s.consume(
            listOf(
                header(0, 251, null, 0, 10, init = true),
                SabrPart.MediaPart(0, ByteArray(10)),
                header(1, 136, null, 0, 10, init = true),
                SabrPart.MediaPart(1, ByteArray(10)),
            )
        )
        // Audio to 5000 ms via time_range; video to 9000 ms via cl/bps.
        s.consume(
            listOf(
                header(2, 251, 1, 10, 100, timeRange = SabrTimeRange(0, 5000, 1000)),
                SabrPart.MediaPart(2, ByteArray(100)),
                header(3, 136, 1, 10, 9000, bytesPerSecond = 1000),
                SabrPart.MediaPart(3, ByteArray(9000)),
            )
        )
        assertEquals(5000L, s.bufferedEndMs())
    }

    @Test
    fun coldSpoolRestoreProvesBothSlotsFromDisk() {
        // Mechanism 12: a spool a previous session rolled must let
        // a fresh session prove both slots from disk — init received,
        // wire-proven formats, playable heads, and the buffered end
        // already at the shorter track's frontier.
        val store = SpoolStore(File(tempFolder.root, "spool"))
        val spool = store.open("vid")
        // Audio 251: WebM head (EBML magic), 16 B init + 100 B segment.
        val audio = spool.track(251)
        val audioBytes = ByteArray(116)
        byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
            .copyInto(audioBytes, 0)
        audio.write(0, audioBytes)
        audio.noteFormat(SabrFormatId(251, 111L, "x"))
        audio.noteInit(16)
        audio.noteSegment(SabrSegment(1, 0L, 10_000L, 16L, 100L))
        audio.flushManifest()
        // Video 136: MP4 head (ftyp at bytes 4..7), 20 B init + 200 B.
        val video = spool.track(136)
        val videoBytes = ByteArray(220)
        "ftyp".toByteArray().copyInto(videoBytes, 4)
        video.write(0, videoBytes)
        video.noteFormat(SabrFormatId(136, 222L, null))
        video.noteInit(20)
        video.noteSegment(SabrSegment(1, 0L, 6_000L, 20L, 200L))
        video.flushManifest()
        spool.close()

        val reopened = store.open("vid")
        val s = session()
        s.restoreFrom(reopened)
        assertTrue("restored session must count init as received", s.initReceived)
        assertEquals(251, s.wireProvenFormat(audio = true)?.itag)
        assertEquals(136, s.wireProvenFormat(audio = false)?.itag)
        assertTrue(reopened.track(251).hasPlayableHead())
        assertTrue(reopened.track(136).hasPlayableHead())
        assertEquals(6_000L, s.bufferedEndMs())
        reopened.close()
    }

    @Test
    fun restoredSessionClaimsTheSpooledRangesAfterReload() {
        // Mechanism 18: a session that RELOADS (a fresh player
        // response, a fresh SabrSession) must tell the server the
        // spool's truth in its FIRST request — bufferedRanges()
        // claims exactly the restored segments, per track, and
        // the buffered end is the shorter track. That claim is
        // what makes the reload spool-honest: the server resumes
        // past what we hold instead of restarting the media.
        val store = SpoolStore(File(tempFolder.root, "spool2"))
        val spool = store.open("vid")
        val audio = spool.track(251)
        val audioBytes = ByteArray(116)
        byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
            .copyInto(audioBytes, 0)
        audio.write(0, audioBytes)
        audio.noteFormat(SabrFormatId(251, 111L, "x"))
        audio.noteInit(16)
        audio.noteSegment(SabrSegment(1, 0L, 10_000L, 16L, 100L))
        audio.flushManifest()
        val video = spool.track(136)
        val videoBytes = ByteArray(220)
        "ftyp".toByteArray().copyInto(videoBytes, 4)
        video.write(0, videoBytes)
        video.noteFormat(SabrFormatId(136, 222L, null))
        video.noteInit(20)
        video.noteSegment(SabrSegment(1, 0L, 6_000L, 20L, 200L))
        video.flushManifest()
        spool.close()

        val reopened = store.open("vid")
        val restored = session()
        restored.restoreFrom(reopened)
        val ranges = restored.bufferedRanges()
        assertEquals(2, ranges.size)
        val audioRange = ranges.first { it.formatId.itag == 251 }
        assertEquals(0L, audioRange.startTimeMs)
        assertEquals(10_000L, audioRange.durationMs)
        assertEquals(1, audioRange.startSegmentIndex)
        assertEquals(1, audioRange.endSegmentIndex)
        val videoRange = ranges.first { it.formatId.itag == 136 }
        assertEquals(0L, videoRange.startTimeMs)
        assertEquals(6_000L, videoRange.durationMs)
        assertEquals(1, videoRange.startSegmentIndex)
        assertEquals(1, videoRange.endSegmentIndex)
        // The buffered end is the shorter track: playback resumes
        // there, media beyond it is still owed by the server.
        assertEquals(6_000L, restored.bufferedEndMs())
        reopened.close()
    }

    private fun cappedSession(cap: Int?): SabrSession = SabrSession(
        videoId = "vid",
        initialUrl = "https://x.invalid/s",
        ustreamerConfig = byteArrayOf(9, 9),
        clientInfo = SabrClientInfo(101, "1.04", "visionOS", "2.0"),
        durationSeconds = 100,
        formats = listOf(
            SabrFormat(251, "audio/webm", 160_000, 111L, "x"),
            SabrFormat(136, "video/mp4", 2_000_000, 222L, null, height = 720),
            SabrFormat(137, "video/mp4", 3_000_000, 222L, null, height = 1080),
        ),
        audioItag = 251,
        videoItag = 136,
        cpn = "CPN",
        videoCapHeightPx = cap,
    )

    private fun proveBothVideos(s: SabrSession) {
        s.consume(
            listOf(
                header(0, 251, null, 0, 10, init = true),
                SabrPart.MediaPart(0, ByteArray(10)),
                header(1, 136, null, 0, 10, init = true),
                SabrPart.MediaPart(1, ByteArray(10)),
                header(2, 137, null, 0, 10, init = true),
                SabrPart.MediaPart(2, ByteArray(10)),
                header(3, 136, 1, 10, 100, timeRange = SabrTimeRange(0, 5000, 1000)),
                SabrPart.MediaPart(3, ByteArray(100)),
                header(4, 137, 1, 10, 100, timeRange = SabrTimeRange(0, 5000, 1000)),
                SabrPart.MediaPart(4, ByteArray(100)),
            )
        )
    }

    @Test
    fun adoptionUnderAJatahCapPrefersTheFittingProvenFormat() {
        val s = cappedSession(cap = 720)
        proveBothVideos(s)
        s.adoptServerPins()
        // 137 is richer and equally proven — the cap still wins.
        assertEquals(136, s.videoFormat.itag)
    }

    @Test
    fun adoptionWithoutACapTakesTheRichestProvenFormat() {
        val s = cappedSession(cap = null)
        proveBothVideos(s)
        s.adoptServerPins()
        assertEquals(137, s.videoFormat.itag)
    }

    private fun codecSession(ceiling: DeviceCodecCeiling): SabrSession = SabrSession(
        videoId = "vid",
        initialUrl = "https://x.invalid/s",
        ustreamerConfig = byteArrayOf(9, 9),
        clientInfo = SabrClientInfo(101, "1.04", "visionOS", "2.0"),
        durationSeconds = 100,
        formats = listOf(
            SabrFormat(251, "audio/webm; codecs=\"opus\"", 160_000, 111L, "x"),
            SabrFormat(
                398, "video/mp4; codecs=\"av01.0.08M.08\"",
                4_000_000, 222L, null, height = 720,
            ),
            SabrFormat(
                136, "video/mp4; codecs=\"avc1.640028\"",
                2_000_000, 222L, null, height = 720,
            ),
        ),
        audioItag = 251,
        videoItag = 398,
        cpn = "CPN",
        codecCeiling = ceiling,
    )

    private fun proveItag(s: SabrSession, headerId: Int, itag: Int) {
        s.consume(
            listOf(
                header(headerId, itag, null, 0, 10, init = true),
                SabrPart.MediaPart(headerId, ByteArray(10)),
                header(
                    headerId + 10, itag, 1, 10, 100,
                    timeRange = SabrTimeRange(0, 5000, 1000),
                ),
                SabrPart.MediaPart(headerId + 10, ByteArray(100)),
            )
        )
    }

    @Test
    fun adoptionWithoutAv1HardwarePrefersTheProvenAvcFormat() {
        // M11: 398 (AV1) is richer and equally wire-proven, but
        // this device has no AV1 hardware decoder — the proven
        // AVC format is adopted instead of the software road.
        val s = codecSession(
            DeviceCodecCeiling(av1Hardware = false, vp9Hardware = true),
        )
        proveItag(s, 0, 251)
        proveItag(s, 1, 398)
        proveItag(s, 2, 136)
        s.adoptServerPins()
        assertEquals(136, s.videoFormat.itag)
    }

    @Test
    fun adoptionKeepsAv1WhenItIsTheOnlyProvenFormat() {
        // M11 is a preference, never a refusal: when the wire
        // proves nothing but AV1, AV1 is what plays.
        val s = codecSession(
            DeviceCodecCeiling(av1Hardware = false, vp9Hardware = true),
        )
        proveItag(s, 0, 251)
        proveItag(s, 1, 398)
        s.adoptServerPins()
        assertEquals(398, s.videoFormat.itag)
    }

    @Test
    fun splitSegmentAcrossTwoHeaderGroupsLandsWhole() {
        // Regression (device stall, 2026-11-12 22:32): a large
        // segment's media arrives in TWO header groups carrying
        // the SAME sequence number. The second group is a
        // continuation, not a duplicate — suppressing it tore a
        // permanent hole into the spool while the session ledger
        // reported the timeline complete.
        val s = session()
        val result = s.consume(
            listOf(
                header(0, 136, null, 0, 10, init = true),
                SabrPart.MediaPart(0, ByteArray(10)),
                header(
                    1, 136, 1, 10, 100,
                    timeRange = SabrTimeRange(0, 5000, 1000),
                ),
                SabrPart.MediaPart(1, ByteArray(100)),
                header(
                    2, 136, 1, 110, 100,
                    timeRange = SabrTimeRange(0, 5000, 1000),
                ),
                SabrPart.MediaPart(2, ByteArray(100)),
            )
        )
        val chunks = result.events.filterIsInstance<SabrEvent.MediaChunk>()
            .filter { it.header.itag == 136 && !it.header.isInitSegment }
        assertEquals(listOf(10L, 110L), chunks.map { it.offsetInTrack })
        val segment = s.trackFor(136).segments.getValue(1)
        assertEquals(200L, segment.length)
        assertEquals(200L, segment.expectedLength)
        assertTrue(segment.isComplete)
        // The timeline counts the segment once, not once per group.
        assertEquals(5_000L, s.trackFor(136).timelineEndMs)
    }

    /** An audio header whose FormatId names an explicit rendition. */
    private fun renditionHeader(
        id: Int,
        seq: Int?,
        startRange: Long,
        contentLength: Long,
        lastModified: Long,
        init: Boolean = false,
        timeRange: SabrTimeRange? = null,
    ): SabrPart.MediaHeaderPart = SabrPart.MediaHeaderPart(
        MediaHeader(
            headerId = id,
            videoId = "vid",
            itag = 251,
            lastModified = lastModified,
            xtags = "x",
            startRange = startRange,
            isInitSegment = init,
            sequenceNumber = seq,
            bytesPerSecond = null,
            formatId = SabrFormatId(251, lastModified, "x"),
            contentLength = contentLength,
            timeRange = timeRange,
        )
    )

    @Test
    fun renditionSwitchMidTrackAnchorsTheNewSegmentAtTheFrontier() {
        // Wire evidence 2026-11-13 (video PrLTETO4blo, audio itag
        // 251): mid-track the server switches the RENDITION
        // backing a track — segment 2 arrives from a different
        // rendition (a different FormatId lastModified) and its
        // start_range is an offset in THAT file's space, 118
        // bytes past where segment 1 ended in ours. Written at
        // its declared offset it tears a hole the reader can
        // never cross (the on-device stall ~10 s into playback).
        // Anchored at the spool frontier instead, the spool
        // stays one unbroken file.
        val s = session()
        val chunks = ArrayList<SabrEvent.MediaChunk>()
        fun feed(vararg parts: SabrPart) {
            chunks += s.consume(parts.toList()).events
                .filterIsInstance<SabrEvent.MediaChunk>()
        }
        // Rendition A (lastModified 111): init 902 B + segment 1.
        feed(
            renditionHeader(0, null, 0, 902, 111L, init = true),
            SabrPart.MediaPart(0, ByteArray(902)),
            renditionHeader(
                1, 1, 902, 1000, 111L,
                timeRange = SabrTimeRange(0, 10_000, 1000),
            ),
            SabrPart.MediaPart(1, ByteArray(1000)),
        )
        // Rendition B (lastModified 999): segment 2 declares
        // 2020 — 118 past segment 1's wire end (1902).
        feed(
            renditionHeader(
                2, 2, 2020, 1000, 999L,
                timeRange = SabrTimeRange(10_000, 10_000, 1000),
            ),
            SabrPart.MediaPart(2, ByteArray(1000)),
        )
        // Segment 3 stays in rendition B and continues its space.
        feed(
            renditionHeader(
                3, 3, 3020, 1000, 999L,
                timeRange = SabrTimeRange(20_000, 10_000, 1000),
            ),
            SabrPart.MediaPart(3, ByteArray(1000)),
        )
        val track = s.trackFor(251)
        val seg2 = track.segments.getValue(2)
        val seg3 = track.segments.getValue(3)
        // The session ledger keeps speaking wire space...
        assertEquals(2020L, seg2.startRange)
        // ...but segment 2 is anchored 118 bytes back, exactly
        // onto segment 1's spool end, and segment 3 inherits the
        // anchor — same rendition, same shift.
        assertEquals(-118L, seg2.spoolDelta)
        assertEquals(-118L, seg3.spoolDelta)
        assertEquals(999L, track.wireRendition)
        assertEquals(-118L, track.currentDelta)
        // The chunks the pump writes land at 902, 1902, 2902:
        // the spool grows unbroken from the init onward.
        val mediaOffsets = chunks.filter { !it.header.isInitSegment }
            .map { it.offsetInTrack }
        assertEquals(listOf(902L, 1902L, 2902L), mediaOffsets)
        // Init + segments tile [0, 3902) with no gap.
        val intervals = ArrayList<LongRange>()
        intervals.add(0L until track.initBytes)
        for (seg in track.segments.values.sortedBy { it.sequence }) {
            intervals.add(
                seg.spoolStartRange until seg.spoolStartRange + seg.length
            )
        }
        var frontier = 0L
        for (range in intervals) {
            assertEquals(frontier, range.first)
            frontier = range.last + 1
        }
        assertEquals(3902L, frontier)
    }

    @Test
    fun renditionAnchorSurvivesTheSpoolRoundTrip() {
        // A spool persisted with a rendition anchor (segment 2
        // anchored −118 onto the frontier) must reopen with the
        // anchor and the wire rendition intact and a gapless
        // frontier, and a session restored from it must hold its
        // segments back in wire space.
        val store = SpoolStore(File(tempFolder.root, "spool"))
        val spool = store.open("vid")
        val audio = spool.track(251)
        val bytes = ByteArray(2902)
        byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
            .copyInto(bytes, 0)
        audio.write(0, bytes)
        audio.noteFormat(SabrFormatId(251, 111L, "x"))
        audio.noteEndSegmentNumber(40)
        audio.noteInit(902)
        // Spool space, exactly as the pump's persist copies hand
        // them over: segment 2 sits at 1902 in the spool but was
        // announced at 2020 on the wire; spoolDelta carries the
        // difference, and the wire state rides at the root.
        audio.noteSegment(SabrSegment(1, 0L, 10_000L, 902L, 1000L))
        audio.noteSegment(
            SabrSegment(2, 10_000L, 10_000L, 1902L, 1000L, spoolDelta = -118L)
        )
        audio.noteWireState(999L, -118L)
        audio.flushManifest()
        spool.close()

        val reopened = store.open("vid")
        val trackSpool = reopened.track(251)
        assertEquals(-118L, trackSpool.segments.getValue(2).spoolDelta)
        assertEquals(999L, trackSpool.wireRendition)
        assertEquals(-118L, trackSpool.currentDelta)
        // Gapless: init + both segments merge into one range.
        assertEquals(listOf(0L..2901L), trackSpool.writtenRanges())
        assertEquals(2902L, trackSpool.frontier())

        val s = session()
        s.restoreFrom(reopened)
        val track = s.trackFor(251)
        // Back in wire space: 1902 − (−118) = 2020, the offset
        // the wire announced; the anchor and the rendition ride
        // along for the next switch decision.
        assertEquals(2020L, track.segments.getValue(2).startRange)
        assertEquals(-118L, track.segments.getValue(2).spoolDelta)
        assertEquals(999L, track.wireRendition)
        assertEquals(-118L, track.currentDelta)
        reopened.close()
    }

    /**
     * A reopened spool whose audio track holds SABR's init (100 B)
     * and a segment 1 vouched at 400 of the 1000 its header
     * announced, plus a classic sidecar: [classicRendition] over
     * [classicRangeStart, +classicRangeLength). The sidecar is
     * noted last — SABR authorship (noteInit/noteSegment above)
     * clears an earlier one by design (M14 invariant).
     */
    private fun adoptionSpool(
        dirName: String,
        delta: Long = 0L,
        classicRendition: Long,
        classicRangeStart: Long,
        classicRangeLength: Long,
    ): VideoSpool {
        val store = SpoolStore(File(tempFolder.root, dirName))
        val spool = store.open("vid")
        val audio = spool.track(251)
        val bytes = ByteArray(2000)
        byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
            .copyInto(bytes, 0)
        audio.write(0, bytes)
        audio.noteFormat(SabrFormatId(251, 111L, "x"))
        audio.noteInit(100)
        audio.noteSegment(
            SabrSegment(
                1, 0L, 10_000L, 100L + delta, 400L,
                expectedLength = 1000L, spoolDelta = delta,
            )
        )
        audio.noteWireState(111L, delta)
        audio.noteClassicRendition(classicRendition)
        audio.noteClassicRange(classicRangeStart, classicRangeLength)
        audio.flushManifest()
        spool.close()
        return store.open("vid")
    }

    @Test
    fun classicAdoptionExtendsAShortSegmentOnMatchingRendition() {
        val spool = adoptionSpool(
            "adopt-ok",
            classicRendition = 111L,
            classicRangeStart = 100L,
            classicRangeLength = 1000L,
        )
        val s = session()
        s.restoreFrom(spool)
        assertEquals(1, s.adoptClassicRanges(spool))
        val segment = s.trackFor(251).segments.getValue(1)
        assertEquals(1000L, segment.length)
        assertTrue(segment.isComplete)
        spool.close()
    }

    @Test
    fun classicAdoptionRefusesAMismatchedRendition() {
        val spool = adoptionSpool(
            "adopt-mismatch",
            classicRendition = 999L,
            classicRangeStart = 100L,
            classicRangeLength = 1000L,
        )
        val s = session()
        s.restoreFrom(spool)
        assertEquals(0, s.adoptClassicRanges(spool))
        assertEquals(400L, s.trackFor(251).segments.getValue(1).length)
        spool.close()
    }

    @Test
    fun classicAdoptionRefusesPartialCoverage() {
        val spool = adoptionSpool(
            "adopt-partial",
            classicRendition = 111L,
            classicRangeStart = 100L,
            classicRangeLength = 500L,
        )
        val s = session()
        s.restoreFrom(spool)
        assertEquals(0, s.adoptClassicRanges(spool))
        assertEquals(400L, s.trackFor(251).segments.getValue(1).length)
        spool.close()
    }

    @Test
    fun classicAdoptionRefusesAShiftedTrack() {
        // The spool track is anchored (delta −50): its spool
        // offsets are not the classic file's offsets, so even a
        // rendition match adopts nothing.
        val spool = adoptionSpool(
            "adopt-shifted",
            delta = -50L,
            classicRendition = 111L,
            classicRangeStart = 50L,
            classicRangeLength = 1000L,
        )
        val s = session()
        s.restoreFrom(spool)
        assertEquals(-50L, s.trackFor(251).currentDelta)
        assertEquals(0, s.adoptClassicRanges(spool))
        assertEquals(400L, s.trackFor(251).segments.getValue(1).length)
        spool.close()
    }

    @Test
    fun classicAdoptionTakesTheInitWhenClassicCoversTheHead() {
        // No SABR init in the manifest at all — but segment 1 sits
        // at spool offset 300 and the classic road covered [0, 300)
        // in full: that covered run IS the init segment.
        val store = SpoolStore(File(tempFolder.root, "adopt-init"))
        val spool = store.open("vid")
        val audio = spool.track(251)
        audio.write(0, ByteArray(2000))
        audio.noteFormat(SabrFormatId(251, 111L, "x"))
        audio.noteSegment(
            SabrSegment(1, 0L, 10_000L, 300L, 500L, expectedLength = 500L)
        )
        audio.noteWireState(111L, 0L)
        audio.noteClassicRendition(111L)
        audio.noteClassicRange(0L, 300L)
        audio.flushManifest()
        spool.close()

        val reopened = store.open("vid")
        val s = session()
        s.restoreFrom(reopened)
        val track = s.trackFor(251)
        assertTrue(!track.gotInit)
        assertEquals(0, s.adoptClassicRanges(reopened))
        assertTrue(track.gotInit)
        assertEquals(300L, track.initBytes)
        reopened.close()
    }

    // ------------------------------------------------ mechanism 23

    private fun feedTimedSegment(
        s: SabrSession, id: Int, itag: Int, seq: Int,
        startMs: Long, durationMs: Long, startRange: Long,
    ) {
        s.consume(
            listOf(
                header(
                    id, itag, seq, startRange, 100,
                    timeRange = SabrTimeRange(startMs, durationMs, 1000),
                ),
                SabrPart.MediaPart(id, ByteArray(100)),
            ),
        )
    }

    @Test
    fun gapBetweenSegmentsStartsWhereCoverageEnds() {
        // Mechanism 23: [0–10 s] then [60–70 s] — the hole is
        // (10 s, 60 s) and a backfill pass seeds at its start.
        val s = session()
        feedTimedSegment(s, 0, 251, 1, 0, 10_000, 0)
        feedTimedSegment(s, 1, 251, 2, 60_000, 10_000, 100)
        assertEquals(10_000L, s.earliestGapStartMs())
        assertTrue(s.coversPosition(5_000))
        assertTrue(!s.coversPosition(30_000))
        assertTrue(s.coversPosition(65_000))
        assertTrue(!s.coversPosition(80_000))
    }

    @Test
    fun contiguousSegmentsLeaveNoGap() {
        val s = session()
        feedTimedSegment(s, 0, 251, 1, 0, 10_000, 0)
        feedTimedSegment(s, 1, 251, 2, 10_000, 10_000, 100)
        assertNull(s.earliestGapStartMs())
        assertTrue(s.coversPosition(15_000))
    }

    @Test
    fun headGapStartsAtZero() {
        // A jump-seeded track: nothing before 30 s, so the hole
        // is [0, 30 s) and the backfill seed is the top itself.
        val s = session()
        feedTimedSegment(s, 0, 251, 1, 30_000, 10_000, 0)
        assertEquals(0L, s.earliestGapStartMs())
        assertTrue(!s.coversPosition(10_000))
        assertTrue(s.coversPosition(35_000))
    }

}
