package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.AbrRequest
import com.bunny.engine.lontar.sabr.ClientAbrState
import com.bunny.engine.lontar.sabr.ProtoReader
import com.bunny.engine.lontar.sabr.ProtoWriter
import com.bunny.engine.lontar.sabr.SabrBufferedRange
import com.bunny.engine.lontar.sabr.SabrClientInfo
import com.bunny.engine.lontar.sabr.SabrFormatId
import com.bunny.engine.lontar.sabr.SabrParseException
import com.bunny.engine.lontar.sabr.SabrPart
import com.bunny.engine.lontar.sabr.SabrPartDecoder
import com.bunny.engine.lontar.sabr.StreamerContext
import com.bunny.engine.lontar.sabr.Ump
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fase 8 (Anyam), Langkah 1: the SABR protocol bones, tested against
 * REAL wire recordings — fixtures captured 2026-11-12 by
 * capture_fixtures.py driving the jilid-2 probe's proven session
 * machinery (video 2Jk6C3Asn1U, VISIONOS persona, token-free).
 * Requests must rebuild byte-exact from their recorded specs; responses
 * must decode to the same facts the probe recorded in manifest.json.
 * Nothing here is mocked wire data.
 */
class SabrProtocolTest {

    private fun resource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path)!!.readBytes()

    /**
     * Wire fixtures are stored as .bin.b64 text (the repo push path is
     * UTF-8 only); the bytes decoded here are the exact recorded bytes —
     * capture_fixtures.py asserts the same hashes it recorded.
     */
    private fun fixture(name: String): ByteArray =
        Base64.getMimeDecoder().decode(resource("sabr/$name.bin.b64"))

    private val manifest: JSONObject by lazy {
        JSONObject(String(resource("sabr/manifest.json"), Charsets.UTF_8))
    }

    private fun pair(i: Int): JSONObject =
        manifest.getJSONArray("pairs").getJSONObject(i)

    private fun knownItags(): Set<Int> {
        val arr = manifest.getJSONArray("knownItags")
        return (0 until arr.length()).map { arr.getInt(it) }.toSet()
    }

    private fun decodeResponse(i: Int): List<SabrPart> =
        SabrPartDecoder.decodeAll(
            Ump.parseParts(fixture("resp$i")), knownItags()
        )

    private fun optLong(o: JSONObject, key: String): Long? {
        if (o.isNull(key)) return null
        return when (val v = o.get(key)) {
            is Number -> v.toLong()
            is String -> v.toLong()
            else -> null
        }
    }

    private fun formatId(o: JSONObject): SabrFormatId = SabrFormatId(
        itag = o.getInt("itag"),
        lastModified = optLong(o, "lastModified"),
        xtags = if (o.isNull("xtags")) null else o.getString("xtags"),
    )

    // ------------------------------------------------------------ UMP varint

    @Test
    fun umpVarintWidthsMatchTheProvenLayout() {
        // (bytes, value): the 997/1003 pair is the probe-jilid-1 lesson,
        // cross-checked live; the wider vectors are hand-computed from
        // the same low-bits-first layout.
        val vectors = listOf(
            byteArrayOf(0x2B) to 43L,
            byteArrayOf(0xA5.toByte(), 0x0F) to 997L,
            byteArrayOf(0xAB.toByte(), 0x0F) to 1003L,
            byteArrayOf(0xC0.toByte(), 0x12, 0x7A) to 1_000_000L,
            byteArrayOf(0xE0.toByte(), 0x10, 0x5E, 0x5F) to 100_000_000L,
            byteArrayOf(
                0xF0.toByte(), 0xC0.toByte(), 0x0B, 0x5A, 0x16
            ) to 3_000_000_000L,
        )
        for ((bytes, expected) in vectors) {
            val v = Ump.readVarint(bytes, 0)
            assertEquals(expected, v.value)
            assertEquals(bytes.size, v.nextPos)
        }
    }

    @Test
    fun umpParseRejectsTruncatedFraming() {
        val truncated = fixture("resp1").copyOf(500)
        try {
            Ump.parseParts(truncated)
            fail("a truncated response must not parse silently")
        } catch (expected: SabrParseException) {
            // honest failure, as designed
        }
    }

    // -------------------------------------------------------------- responses

    @Test
    fun resp0IsASingleRedirectPart() {
        val parts = Ump.parseParts(fixture("resp0"))
        assertEquals(1, parts.size)
        assertEquals(Ump.PART_SABR_REDIRECT, parts[0].type)
        assertEquals(997, parts[0].payload.size)
        val decoded = SabrPartDecoder.decode(parts[0], knownItags())
        assertTrue(decoded is SabrPart.RedirectPart)
        val url = (decoded as SabrPart.RedirectPart).url
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://"))
    }

    @Test
    fun resp1PartSequenceMatchesTheRecording() {
        val expected = pair(1).getJSONObject("response").getJSONArray("partTypes")
        val actual = Ump.parseParts(fixture("resp1")).map { it.type }
        assertEquals(expected.length(), actual.size)
        for (i in actual.indices) {
            assertEquals("part $i", expected.getInt(i), actual[i])
        }
    }

    @Test
    fun resp1ProtectionStatusIsOk() {
        val statuses = decodeResponse(1).filterIsInstance<SabrPart.ProtectionStatusPart>()
        assertEquals(1, statuses.size)
        assertEquals(StreamProtectionStatusOk, statuses[0].status.status)
        assertTrue(statuses[0].status.isOk)
    }

    @Test
    fun resp1NextRequestPolicyMatchesTheRecording() {
        val summary = pair(1).getJSONObject("response")
        val policy = decodeResponse(1)
            .filterIsInstance<SabrPart.NextRequestPolicyPart>()
            .single().policy
        assertEquals(summary.getLong("nrpBackoffMs"), policy.backoffTimeMs)
        assertNotNull(policy.playbackCookie)
        assertEquals(summary.getInt("nrpCookieBytes"), policy.playbackCookie!!.size)
    }

    @Test
    fun resp1InitMetadataUnionAcrossBothParts() {
        val expected = pair(1).getJSONObject("response").getJSONObject("initEndSegments")
        val merged = LinkedHashMap<Int, com.bunny.engine.lontar.sabr.FormatInit>()
        for (part in decodeResponse(1).filterIsInstance<SabrPart.InitMetadataPart>()) {
            for ((itag, init) in part.formats) merged.putIfAbsent(itag, init)
        }
        assertEquals(expected.length(), merged.size)
        val keys = expected.keys()
        while (keys.hasNext()) {
            val itag = keys.next().toInt()
            assertEquals(
                "endSegmentNumber for itag $itag",
                expected.getInt(itag.toString()),
                merged[itag]?.endSegmentNumber,
            )
        }
        assertEquals(1000L, merged[251]?.timescale)
        assertEquals(15360L, merged[398]?.timescale)
    }

    @Test
    fun resp1MediaHeadersMatchTheRecording() {
        val recorded = pair(1).getJSONObject("response").getJSONArray("mediaHeaders")
        val headers = decodeResponse(1)
            .filterIsInstance<SabrPart.MediaHeaderPart>()
            .map { it.header }
        assertTrue(headers.size >= recorded.length())
        for (i in 0 until recorded.length()) {
            val rec = recorded.getJSONObject(i)
            val h = headers[i]
            assertEquals("header $i id", rec.getInt("headerId"), h.headerId)
            assertEquals("header $i itag", rec.getInt("itag"), h.itag)
            assertEquals("header $i init", rec.getBoolean("isInit"), h.isInitSegment)
            assertEquals("header $i startRange", optLong(rec, "startRange"), h.startRange)
            assertEquals(
                "header $i seq", optLong(rec, "seqF9")?.toInt(), h.sequenceNumber
            )
            assertEquals(
                "header $i contentLength", optLong(rec, "contentLength"), h.contentLength
            )
            if (rec.isNull("timeRange")) {
                assertNull("header $i timeRange", h.timeRange)
            } else {
                val tr = rec.getJSONObject("timeRange")
                assertNotNull("header $i timeRange", h.timeRange)
                assertEquals(tr.getLong("start"), h.timeRange!!.startTicks)
                assertEquals(tr.getLong("dur"), h.timeRange!!.durationTicks)
                assertEquals(tr.getLong("timescale"), h.timeRange!!.timescale)
            }
        }
    }

    @Test
    fun resp1MediaBytesMatchHeadersAndRecording() {
        val parts = decodeResponse(1)
        val headers = parts.filterIsInstance<SabrPart.MediaHeaderPart>()
            .map { it.header }
            .associateBy { it.headerId }
        val mediaByHeader = parts.filterIsInstance<SabrPart.MediaPart>()
            .groupBy { it.headerId }
            .mapValues { (_, v) -> v.sumOf { it.data.size } }
        // Init segments: header 0 (audio, 936 B) and header 2 (video,
        // 1560 B) — content_length verified byte-exact, as the probe did.
        for (headerId in listOf(0, 2)) {
            assertEquals(
                "init media bytes for header $headerId",
                headers[headerId]?.contentLength,
                mediaByHeader[headerId]?.toLong(),
            )
        }
        val total = mediaByHeader.values.sum()
        assertEquals(
            pair(1).getJSONObject("response").getLong("mediaBytes"), total.toLong()
        )
    }

    @Test
    fun unknownPartsAreToleratedNotFatal() {
        val parts = decodeResponse(1)
        val unknownTypes = parts.filterIsInstance<SabrPart.UnknownPart>().map { it.type }
        // The recording carries part types 51, 52, 49 and 47, none of
        // which Langkah 1 interprets. All must survive as UnknownPart.
        for (t in listOf(51, 52, 49, 47)) {
            assertTrue("part type $t tolerated", t in unknownTypes)
        }
        assertEquals(
            pair(1).getJSONObject("response").getJSONArray("partTypes").length(),
            parts.size,
        )
    }

    @Test
    fun resp2SteadyStateParsesWithOkStatus() {
        val expected = pair(2).getJSONObject("response").getJSONArray("partTypes")
        val parts = decodeResponse(2)
        assertEquals(expected.length(), parts.size)
        val status = parts.filterIsInstance<SabrPart.ProtectionStatusPart>().single()
        assertTrue(status.status.isOk)
    }

    // --------------------------------------------------------------- requests

    private fun requestFromSpec(spec: JSONObject): AbrRequest {
        val abrJson = spec.getJSONObject("abr")
        val abr = ClientAbrState(
            playerTimeMs = abrJson.getLong("15"),
            bandwidthEstimateBps = abrJson.getLong("10"),
            viewportWidth = abrJson.getInt("5"),
            viewportHeight = abrJson.getInt("6"),
            enabledTrackTypes = abrJson.getInt("24"),
        )
        val selected = spec.getJSONArray("selected").let { arr ->
            (0 until arr.length()).map { formatId(arr.getJSONObject(it)) }
        }
        val ranges = spec.getJSONArray("ranges").let { arr ->
            (0 until arr.length()).map {
                val r = arr.getJSONObject(it)
                SabrBufferedRange(
                    formatId = formatId(r.getJSONObject("formatId")),
                    startTimeMs = r.getLong("startMs"),
                    durationMs = r.getLong("durationMs"),
                    startSegmentIndex = r.getInt("startIdx"),
                    endSegmentIndex = r.getInt("endIdx"),
                )
            }
        }
        val ci = spec.getJSONObject("clientInfo")
        val contexts = spec.getJSONArray("contextsB64").let { arr ->
            (0 until arr.length()).map { Base64.getDecoder().decode(arr.getString(it)) }
        }
        val streamer = StreamerContext(
            clientInfo = SabrClientInfo(
                clientName = ci.getInt("clientName"),
                clientVersion = ci.getString("clientVersion"),
                osName = ci.getString("osName"),
                osVersion = ci.getString("osVersion"),
            ),
            playbackCookie = if (spec.isNull("cookieB64")) {
                null
            } else {
                Base64.getDecoder().decode(spec.getString("cookieB64"))
            },
            sabrContexts = contexts,
        )
        return AbrRequest(
            abrState = abr,
            selectedFormatIds = selected,
            bufferedRanges = ranges,
            playerTimeMs = spec.getLong("playerTimeMs"),
            ustreamerConfig = Base64.getDecoder().decode(spec.getString("ustreamerB64")),
            preferredAudio = if (spec.isNull("pinAudio")) {
                null
            } else {
                formatId(spec.getJSONObject("pinAudio"))
            },
            preferredVideo = if (spec.isNull("pinVideo")) {
                null
            } else {
                formatId(spec.getJSONObject("pinVideo"))
            },
            streamerContext = streamer,
        )
    }

    @Test
    fun abrRequestsRebuildByteExactFromRecordedSpecs() {
        val pairs = manifest.getJSONArray("pairs")
        for (i in 0 until pairs.length()) {
            val spec = pairs.getJSONObject(i).getJSONObject("requestSpec")
            val rebuilt = requestFromSpec(spec).encode()
            assertArrayEquals("request $i must rebuild byte-exact", fixture("req$i"), rebuilt)
        }
    }

    @Test
    fun streamerContextNeverCarriesAPoTokenField() {
        val spec = pair(2).getJSONObject("requestSpec")
        val bytes = requestFromSpec(spec).encode()
        val top = ProtoReader.parseOrNull(bytes)!!
        val ctxBytes = top.first { it.number == 19 }.bytesValue!!
        val ctxFields = ProtoReader.parseOrNull(ctxBytes)!!
        // Field 2 is where a PoToken would live. Anyam writes none, ever.
        assertTrue(ctxFields.none { it.number == 2 })
        assertTrue(ctxFields.all { it.number in setOf(1, 3, 5) })
    }

    // --------------------------------------------------------------- protobuf

    @Test
    fun protoWriterReaderRoundTrip() {
        val bytes = ProtoWriter()
            .fieldVarint(1, 0)
            .fieldVarint(2, 300)
            .fieldVarint(3, 1794318155658714L)
            .fieldString(4, "visionOS")
            .fieldBytes(5, byteArrayOf(1, 2, 3))
            .build()
        val fields = ProtoReader.parseOrNull(bytes)!!
        assertEquals(5, fields.size)
        assertEquals(0L, fields[0].varintValue)
        assertEquals(300L, fields[1].varintValue)
        assertEquals(1794318155658714L, fields[2].varintValue)
        assertEquals("visionOS", fields[3].asString())
        assertArrayEquals(byteArrayOf(1, 2, 3), fields[4].bytesValue)
    }

    private companion object {
        const val StreamProtectionStatusOk = 1
    }
}
