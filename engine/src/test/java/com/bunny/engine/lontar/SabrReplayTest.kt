package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.BandwidthMeter
import com.bunny.engine.lontar.sabr.SabrClientInfo
import com.bunny.engine.lontar.sabr.SabrFormat
import com.bunny.engine.lontar.sabr.SabrHttpException
import com.bunny.engine.lontar.sabr.SabrOutcome
import com.bunny.engine.lontar.sabr.SabrPart
import com.bunny.engine.lontar.sabr.SabrPartDecoder
import com.bunny.engine.lontar.sabr.SabrProbe
import com.bunny.engine.lontar.sabr.SabrProbeTranscript
import com.bunny.engine.lontar.sabr.SabrPump
import com.bunny.engine.lontar.sabr.SabrSession
import com.bunny.engine.lontar.sabr.SabrTransport
import com.bunny.engine.lontar.sabr.SpoolStore
import com.bunny.engine.lontar.sabr.Ump
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 8 Langkah 2, the centrepiece: the recorded SABR session is
 * REPLAYED through SabrSession + SabrPump + Spool. The scripted
 * transport demands that every request the session builds equals the
 * recorded request byte-for-byte (bandwidth seeded from the recorded
 * specs — it was a network measurement, not session state), then
 * returns the recorded response. Afterwards the spool must hold, at
 * the recorded absolute offsets, exactly the recorded media — checked
 * against an independent reconstruction from the fixtures — and the
 * manifests must survive a close/reopen with their sanity intact
 * (ftyp video head, EBML audio head).
 */
class SabrReplayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun resource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path)!!.readBytes()

    private fun fixture(name: String): ByteArray =
        Base64.getMimeDecoder().decode(resource("sabr/$name.bin.b64"))

    private val manifest: JSONObject by lazy {
        JSONObject(String(resource("sabr/manifest.json"), Charsets.UTF_8))
    }

    private fun pair(i: Int): JSONObject =
        manifest.getJSONArray("pairs").getJSONObject(i)

    private fun spec(i: Int): JSONObject = pair(i).getJSONObject("requestSpec")

    private fun optLong(o: JSONObject, key: String): Long? {
        if (o.isNull(key)) return null
        return when (val v = o.get(key)) {
            is Number -> v.toLong()
            is String -> v.toLong()
            else -> null
        }
    }

    private class ScriptedBandwidth(values: List<Long>) : BandwidthMeter {
        private val queue = ArrayDeque(values)
        private var last: Long = values.last()
        override fun estimateBps(): Long {
            if (queue.isNotEmpty()) last = queue.removeFirst()
            return last
        }
        override fun observe(bytes: Int, durationMs: Long) = Unit
    }

    private class ScriptedTransport(
        private val script: List<Pair<ByteArray, ByteArray?>>,
    ) : SabrTransport {
        val urls = ArrayList<String>()
        private var index = 0
        override suspend fun post(url: String, body: ByteArray): ByteArray {
            urls.add(url)
            val (expected, response) = script[index]
            if (!expected.contentEquals(body)) {
                throw AssertionError(
                    "request $index differs from the recording " +
                        "(expected ${expected.size} B, got ${body.size} B)"
                )
            }
            index++
            return response ?: throw SabrHttpException(410)
        }
    }

    private fun buildSession(): SabrSession {
        val audio = manifest.getJSONObject("audioFormat")
        val video = manifest.getJSONObject("videoFormat")
        val adopted = spec(2).getJSONArray("selected").getJSONObject(1)
        val formats = listOf(
            SabrFormat(
                itag = audio.getInt("itag"), mimeType = "audio/webm; codecs=\"opus\"",
                bitrate = 160_000,
                lastModified = optLong(audio, "lastModified"),
                xtags = if (audio.isNull("xtags")) null else audio.getString("xtags"),
            ),
            SabrFormat(
                itag = video.getInt("itag"), mimeType = "video/mp4; codecs=\"avc1.640028\"",
                bitrate = 2_500_000,
                lastModified = optLong(video, "lastModified"),
                xtags = if (video.isNull("xtags")) null else video.getString("xtags"),
            ),
            SabrFormat(
                itag = adopted.getInt("itag"),
                mimeType = "video/mp4; codecs=\"av01.0.08M.08\"",
                bitrate = 4_000_000,
                lastModified = optLong(adopted, "lastModified"),
                xtags = if (adopted.isNull("xtags")) null else adopted.getString("xtags"),
            ),
        )
        return SabrSession(
            videoId = manifest.getString("videoId"),
            initialUrl = "https://fixture.invalid/sabr",
            ustreamerConfig = Base64.getDecoder()
                .decode(spec(0).getString("ustreamerB64")),
            clientInfo = SabrClientInfo(101, "1.04", "visionOS", "2.0"),
            durationSeconds = manifest.getInt("durationSeconds"),
            formats = formats,
            audioItag = audio.getInt("itag"),
            videoItag = video.getInt("itag"),
            cpn = "REPLAYCPN0000000",
        )
    }

    /** Independent per-track reconstruction straight from the fixtures. */
    private fun expectedTrackBytes(responses: List<ByteArray>): Map<Int, ByteArray> {
        val known = manifest.getJSONArray("knownItags").let { arr ->
            (0 until arr.length()).map { arr.getInt(it) }.toSet()
        }
        val chunks = HashMap<Int, MutableList<Pair<Long, ByteArray>>>()
        for (response in responses) {
            val parts = SabrPartDecoder.decodeAll(Ump.parseParts(response), known)
            val headers = parts.filterIsInstance<SabrPart.MediaHeaderPart>()
                .associate { it.header.headerId to it.header }
            for (media in parts.filterIsInstance<SabrPart.MediaPart>()) {
                val header = headers[media.headerId] ?: continue
                chunks.getOrPut(header.itag) { ArrayList() }
                    .add((header.startRange ?: 0) to media.data)
            }
        }
        val out = HashMap<Int, ByteArray>()
        for ((itag, list) in chunks) {
            // Chunks of one header are consecutive in arrival order and
            // belong at startRange + (bytes of that header before them);
            // headers are disjoint, so a stable sort by startRange with
            // per-header running offsets reconstructs the file.
            val byHeader = list.groupBy { it.first }
            var size = 0L
            for ((start, datas) in byHeader) {
                size = maxOf(size, start + datas.sumOf { it.second.size })
            }
            val buf = ByteArray(size.toInt())
            for ((start, datas) in byHeader) {
                var off = start
                for ((_, bytes) in datas) {
                    bytes.copyInto(buf, off.toInt())
                    off += bytes.size
                }
            }
            out[itag] = buf
        }
        return out
    }

    @Test
    fun replayReproducesTheRecordedSession() = runBlocking {
        val session = buildSession()
        val store = SpoolStore(File(tempFolder.root, "spool"))
        val spool = store.open(session.videoId)

        val script = (0..3).map { i ->
            fixture("req$i") to if (i <= 2) fixture("resp$i") else null
        }
        val transport = ScriptedTransport(script)
        val bandwidth = ScriptedBandwidth((0..3).map { spec(it).getJSONObject("abr").getLong("10") })
        val sleeps = ArrayList<Long>()
        var transcript: SabrProbeTranscript? = null
        val probe = SabrProbe(
            transport = transport,
            bandwidth = bandwidth,
            sleeper = { sleeps.add(it) },
            sink = { transcript = it },
        )

        val result = probe.run(session, spool, maxRequests = 10)

        // The 4th request was built byte-exact, then the script ended
        // the run with a 410 — a harness artefact, asserted as such.
        assertTrue(result.outcome is SabrOutcome.HttpFailed)
        assertEquals(410, (result.outcome as SabrOutcome.HttpFailed).status)
        assertEquals(3, result.exchanges.size)
        assertEquals(listOf(200L, 20_000L, 20_000L), sleeps)

        // Redirect was followed: the second post went to the URL the
        // recorded redirect carried, with rn=1.
        val redirectUrl = SabrPartDecoder.decodeAll(
            Ump.parseParts(fixture("resp0")), session.knownItags
        ).filterIsInstance<SabrPart.RedirectPart>().single().url
        assertNotNull(redirectUrl)
        assertTrue(transport.urls[0].startsWith("https://fixture.invalid/sabr"))
        assertTrue(transport.urls[1].startsWith(redirectUrl!!))
        assertTrue(transport.urls[1].contains("rn=1"))

        // Transcript against the recording's own summary.
        val expectedMedia = (1..2).sumOf {
            pair(it).getJSONObject("response").getLong("mediaBytes")
        }
        assertEquals(expectedMedia, result.totalMediaBytes)
        assertEquals(listOf(1, 1), result.statusValues)
        assertEquals(20_001L, result.audioTimelineEndMs)
        assertNotNull(transcript)
        assertEquals(result, transcript)

        // Session ledger after the replay.
        assertEquals(1, session.protectionStatus)
        assertEquals(82, session.playbackCookie?.size)
        assertEquals(398, session.videoFormat.itag)
        assertEquals(251, session.audioFormat.itag)
        assertEquals(setOf(1, 2), session.tracks[251]?.segments?.keys)
        assertEquals(setOf(1, 2, 3, 4), session.tracks[398]?.segments?.keys)
        assertEquals(40, session.tracks[251]?.endSegmentNumber)
        assertEquals(69, session.tracks[398]?.endSegmentNumber)
        assertEquals(20_001L, session.bufferedEndMs())
        assertEquals(
            listOf(251, 398), session.selectedFormatIds().map { it.itag }
        )

        // Spool bytes equal the independent reconstruction.
        val expected = expectedTrackBytes(listOf(fixture("resp1"), fixture("resp2")))
        for ((itag, bytes) in expected) {
            val trackSpool = spool.track(itag)
            assertEquals("track $itag length", bytes.size.toLong(), trackSpool.fileLength())
            assertArrayEquals(
                "track $itag content",
                bytes, trackSpool.read(0, bytes.size),
            )
            assertTrue("track $itag playable head", trackSpool.hasPlayableHead())
        }
        spool.close()

        // Manifests survive a reopen with sanity intact: the video
        // head is a real ftyp, the audio head a real EBML — both kept.
        val reopened = SpoolStore(File(tempFolder.root, "spool")).open(session.videoId)
        assertEquals(936L, reopened.track(251).initLength)
        assertEquals(1560L, reopened.track(398).initLength)
        assertEquals(setOf(1, 2), reopened.track(251).segments.keys)
        assertEquals(setOf(1, 2, 3, 4), reopened.track(398).segments.keys)
        assertEquals(40, reopened.track(251).endSegmentNumber)
        assertEquals(69, reopened.track(398).endSegmentNumber)
        assertTrue(reopened.track(251).hasPlayableHead())
        assertTrue(reopened.track(398).hasPlayableHead())
        reopened.close()
    }

    @Test
    fun pipelinedReplayLandsTheSameSpoolBytes() = runBlocking {
        // Mechanism 13: with spool writes pipelined onto the writer
        // thread, the wire cadence (requests byte-exact, sleeps
        // identical) and the drained spool must equal the
        // synchronous run above, byte for byte.
        val session = buildSession()
        val store = SpoolStore(File(tempFolder.root, "spool-pipe"))
        val spool = store.open(session.videoId)
        val script = (0..3).map { i ->
            fixture("req$i") to if (i <= 2) fixture("resp$i") else null
        }
        val transport = ScriptedTransport(script)
        val bandwidth = ScriptedBandwidth(
            (0..3).map { spec(it).getJSONObject("abr").getLong("10") }
        )
        val sleeps = ArrayList<Long>()
        val pump = SabrPump(
            session = session,
            spool = spool,
            transport = transport,
            bandwidth = bandwidth,
            sleeper = { sleeps.add(it) },
            pipelined = true,
        )
        val outcome = pump.run(maxRequests = 10)
        assertTrue(outcome is SabrOutcome.HttpFailed)
        assertEquals(410, (outcome as SabrOutcome.HttpFailed).status)
        assertEquals(listOf(200L, 20_000L, 20_000L), sleeps)
        val expected = expectedTrackBytes(listOf(fixture("resp1"), fixture("resp2")))
        for ((itag, bytes) in expected) {
            val trackSpool = spool.track(itag)
            assertEquals("track $itag length", bytes.size.toLong(), trackSpool.fileLength())
            assertArrayEquals("track $itag content", bytes, trackSpool.read(0, bytes.size))
        }
        spool.close()
    }
}
