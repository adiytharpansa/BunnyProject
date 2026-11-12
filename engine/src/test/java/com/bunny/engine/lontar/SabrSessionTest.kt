package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.FormatInit
import com.bunny.engine.lontar.sabr.MediaHeader
import com.bunny.engine.lontar.sabr.NextRequestPolicy
import com.bunny.engine.lontar.sabr.ProtoField
import com.bunny.engine.lontar.sabr.ProtoReader
import com.bunny.engine.lontar.sabr.ProtoWriter
import com.bunny.engine.lontar.sabr.SabrClientInfo
import com.bunny.engine.lontar.sabr.SabrFormat
import com.bunny.engine.lontar.sabr.SabrFormatId
import com.bunny.engine.lontar.sabr.SabrPart
import com.bunny.engine.lontar.sabr.SabrSession
import com.bunny.engine.lontar.sabr.SabrTimeRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session ledger units the replay does not cover on its own. */
class SabrSessionTest {

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
}
