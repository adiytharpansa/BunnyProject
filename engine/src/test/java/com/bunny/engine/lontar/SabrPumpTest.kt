package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.BandwidthMeter
import com.bunny.engine.lontar.sabr.ProtoWriter
import com.bunny.engine.lontar.sabr.SabrClientInfo
import com.bunny.engine.lontar.sabr.SabrFormat
import com.bunny.engine.lontar.sabr.SabrFormatId
import com.bunny.engine.lontar.sabr.SabrOutcome
import com.bunny.engine.lontar.sabr.SabrPump
import com.bunny.engine.lontar.sabr.SabrSession
import com.bunny.engine.lontar.sabr.SabrTransport
import com.bunny.engine.lontar.sabr.SpoolStore
import com.bunny.engine.lontar.sabr.VideoSpool
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The pump against synthetic wire responses (a test-side UMP writer
 * mirrors the reader): terminal discipline, streaks, backoff caps,
 * redirect following, persistence, completion.
 */
class SabrPumpTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var spoolCounter = 0

    // ------------------------------------------------------------ wire kit

    private fun umpVarint(v: Long): ByteArray = when {
        v < 0x80 -> byteArrayOf(v.toByte())
        v < 0x4000 -> byteArrayOf(
            (0x80L or (v and 0x3FL)).toByte(), (v shr 6).toByte()
        )
        v < 0x200000 -> byteArrayOf(
            (0xC0L or (v and 0x1FL)).toByte(),
            ((v shr 5) and 0xFF).toByte(), (v shr 13).toByte()
        )
        v < 0x10000000 -> byteArrayOf(
            (0xE0L or (v and 0xFL)).toByte(), ((v shr 4) and 0xFF).toByte(),
            ((v shr 12) and 0xFF).toByte(), (v shr 20).toByte()
        )
        else -> byteArrayOf(
            0xF0.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), (v shr 24).toByte()
        )
    }

    private fun umpPart(type: Int, payload: ByteArray): ByteArray =
        umpVarint(type.toLong()) + umpVarint(payload.size.toLong()) + payload

    private fun response(vararg parts: ByteArray): ByteArray =
        parts.fold(ByteArray(0)) { acc, p -> acc + p }

    private fun spsPart(status: Long): ByteArray =
        umpPart(58, ProtoWriter().fieldVarint(1, status).build())

    private fun nrpPart(backoff: Long, cookie: ByteArray? = null): ByteArray {
        val w = ProtoWriter().fieldVarint(3, backoff)
        if (cookie != null) w.fieldBytes(7, cookie)
        return umpPart(35, w.build())
    }

    private fun headerPart(
        id: Int,
        itag: Int,
        seq: Int?,
        startRange: Long,
        contentLength: Long,
        init: Boolean,
    ): ByteArray {
        val w = ProtoWriter()
            .fieldVarint(1, id.toLong())
            .fieldVarint(3, itag.toLong())
            .fieldVarint(6, startRange)
        if (init) {
            w.fieldVarint(8, 1)
        } else {
            w.fieldVarint(9, (seq ?: 0).toLong())
        }
        w.fieldBytes(
            13,
            SabrFormatId(
                itag, if (itag == 251) 111L else 222L, if (itag == 251) "x" else null
            ).encode(),
        )
        w.fieldVarint(14, contentLength)
        return umpPart(20, w.build())
    }

    private fun mediaPart(headerId: Int, data: ByteArray): ByteArray =
        umpPart(21, umpVarint(headerId.toLong()) + data)

    private fun initMetadataPart(itag: Int, endSegment: Long): ByteArray {
        val fmtId = SabrFormatId(
            itag, if (itag == 251) 111L else 222L, if (itag == 251) "x" else null
        )
        val node = ProtoWriter()
            .fieldBytes(1, fmtId.encode())
            .fieldVarint(4, endSegment)
            .fieldVarint(10, 1000)
            .build()
        return umpPart(42, node)
    }

    // ------------------------------------------------------------- harness

    private class QueueTransport(responses: List<ByteArray>) : SabrTransport {
        val urls = ArrayList<String>()
        private val queue = ArrayDeque(responses)
        val calls: Int get() = urls.size
        override suspend fun post(url: String, body: ByteArray): ByteArray {
            urls.add(url)
            return queue.removeFirst()
        }
    }

    private class ConstTransport(private val payload: ByteArray) : SabrTransport {
        var calls = 0
        override suspend fun post(url: String, body: ByteArray): ByteArray {
            calls++
            return payload
        }
    }

    private object FixedBandwidth : BandwidthMeter {
        override fun estimateBps(): Long = 5_000_000
        override fun observe(bytes: Int, durationMs: Long) = Unit
    }

    private fun session(): SabrSession = SabrSession(
        videoId = "vid",
        initialUrl = "https://x.invalid/s",
        ustreamerConfig = byteArrayOf(9, 9),
        clientInfo = SabrClientInfo(101, "1.04", "visionOS", "2.0"),
        durationSeconds = 100,
        formats = listOf(
            SabrFormat(251, "audio/webm", 160_000, 111L, "x"),
            SabrFormat(136, "video/mp4", 2_000_000, 222L, null),
        ),
        audioItag = 251,
        videoItag = 136,
        cpn = "CPN",
    )

    private fun spool(): VideoSpool =
        SpoolStore(File(tempFolder.root, "s${spoolCounter++}")).open("vid")

    private fun pump(
        session: SabrSession,
        spool: VideoSpool,
        transport: SabrTransport,
        sleeps: MutableList<Long>,
    ): SabrPump = SabrPump(
        session = session,
        spool = spool,
        transport = transport,
        bandwidth = FixedBandwidth,
        sleeper = { sleeps.add(it) },
    )

    // ---------------------------------------------------------------- tests

    @Test
    fun tokenDemandedEndsTheRun() = runBlocking {
        val s = session()
        val transport = QueueTransport(listOf(response(spsPart(3))))
        val outcome = pump(s, spool(), transport, ArrayList()).run(5)
        assertEquals(SabrOutcome.TokenDemanded, outcome)
        assertEquals(1, transport.calls)
        assertEquals(3, s.protectionStatus)
    }

    @Test
    fun sabrErrorEndsTheRun() = runBlocking {
        val transport = QueueTransport(listOf(response(umpPart(44, ByteArray(0)))))
        val outcome = pump(session(), spool(), transport, ArrayList()).run(5)
        assertEquals(SabrOutcome.SabrErrorReceived, outcome)
        assertEquals(1, transport.calls)
    }

    @Test
    fun redirectMovesTheNextRequest() = runBlocking {
        val redirect = umpPart(
            43,
            ProtoWriter().fieldBytes(1, "https://r.invalid/new".toByteArray()).build(),
        )
        val transport = QueueTransport(listOf(response(redirect), response(spsPart(1))))
        val outcome = pump(session(), spool(), transport, ArrayList()).run(2)
        assertEquals(SabrOutcome.RequestsExhausted(2), outcome)
        assertTrue(transport.urls[1].startsWith("https://r.invalid/new"))
        assertTrue(transport.urls[1].contains("rn=1"))
    }

    @Test
    fun reloadIsReportedAndTheGuardTripsOnRepeatedReuse() = runBlocking {
        val s = session()
        val sp = spool()
        val transport = ConstTransport(response(umpPart(46, ByteArray(0))))
        val p = pump(s, sp, transport, ArrayList())
        assertEquals(SabrOutcome.ReloadRequested, p.run(5))
        assertEquals(SabrOutcome.ReloadRequested, p.run(5))
        assertEquals(SabrOutcome.ReloadRequested, p.run(5))
        assertTrue(p.run(5) is SabrOutcome.Stalled)
        assertEquals(4, transport.calls)
    }

    @Test
    fun dryStreakStallsAfterFourMedialessResponses() = runBlocking {
        val transport = ConstTransport(ByteArray(0))
        val outcome = pump(session(), spool(), transport, ArrayList()).run(10)
        assertTrue(outcome is SabrOutcome.Stalled)
        assertEquals(4, transport.calls)
    }

    @Test
    fun waitStreakStallsAndSleepsAreCappedAtFifteenSeconds() = runBlocking {
        val transport = ConstTransport(response(nrpPart(60_000, byteArrayOf(1))))
        val sleeps = ArrayList<Long>()
        val outcome = pump(session(), spool(), transport, sleeps).run(20)
        assertTrue(outcome is SabrOutcome.Stalled)
        assertEquals(9, transport.calls)
        assertEquals(8, sleeps.size)
        assertTrue(sleeps.all { it == 15_000L })
    }

    @Test
    fun mediaResponseSleepsTwentySecondsAndPersistsChunks() = runBlocking {
        val initBytes = ByteArray(20).also {
            it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte()
            it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
        }
        val resp = response(
            headerPart(0, 251, null, 0, 20, init = true),
            mediaPart(0, initBytes),
            nrpPart(60_000),
        )
        val sp = spool()
        val sleeps = ArrayList<Long>()
        val outcome = pump(session(), sp, QueueTransport(listOf(resp)), sleeps).run(1)
        assertEquals(SabrOutcome.RequestsExhausted(1), outcome)
        assertEquals(listOf(20_000L), sleeps)
        val track = sp.track(251)
        assertEquals(20L, track.fileLength())
        assertArrayEquals(initBytes, track.read(0, 20))
        assertTrue(track.hasPlayableHead())
        sp.close()
    }

    @Test
    fun runCompletesWhenBothTracksHoldEverySegment() = runBlocking {
        val audioInit = ByteArray(8) { 0x1A }
        val videoInit = ByteArray(8).also {
            it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte()
            it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
        }
        val resp = response(
            initMetadataPart(251, 1),
            initMetadataPart(136, 1),
            headerPart(0, 251, null, 0, 8, init = true),
            mediaPart(0, audioInit),
            headerPart(1, 136, null, 0, 8, init = true),
            mediaPart(1, videoInit),
            headerPart(2, 251, 1, 8, 4, init = false),
            mediaPart(2, ByteArray(4) { 0x55 }),
            headerPart(3, 136, 1, 8, 4, init = false),
            mediaPart(3, ByteArray(4) { 0x66 }),
        )
        val s = session()
        val outcome = pump(s, spool(), QueueTransport(listOf(resp)), ArrayList()).run(5)
        assertEquals(SabrOutcome.Completed, outcome)
        assertTrue(s.isComplete())
    }

    @Test
    @Test
    fun spoolWriteFailureBecomesAStalledOutcomeNotADeath() = runBlocking {
        val initBytes = ByteArray(20).also {
            it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte()
            it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
        }
        val resp = response(
            headerPart(0, 251, null, 0, 20, init = true),
            mediaPart(0, initBytes),
            nrpPart(60_000),
        )
        val sp = spool()
        // Sabotage the storage: the video's spool directory becomes
        // a plain file, so every segment write must fail. The pump
        // must report that as a Stalled outcome — never throw and
        // die silently (device evidence 2026-11-12: a silent pump
        // death leaves the player buffering forever).
        sp.dir.deleteRecursively()
        assertTrue(sp.dir.createNewFile())
        val outcome = pump(
            session(), sp, QueueTransport(listOf(resp)), ArrayList(),
        ).run(5)
        val stalled = outcome as SabrOutcome.Stalled
        assertTrue(stalled.reason.startsWith("proses:"))
    }

    @Test
    fun hungryStartCapsTheServerBackoff() = runBlocking {
        val initBytes = ByteArray(20).also {
            it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte()
            it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
        }
        val resp = response(
            headerPart(0, 251, null, 0, 20, init = true),
            mediaPart(0, initBytes),
            nrpPart(60_000),
        )
        val sleeps = ArrayList<Long>()
        val hungryPump = SabrPump(
            session = session(),
            spool = spool(),
            transport = QueueTransport(listOf(resp)),
            bandwidth = FixedBandwidth,
            sleeper = { sleeps.add(it) },
            hungryUntilMs = 60_000,
        )
        val outcome = hungryPump.run(1)
        assertEquals(SabrOutcome.RequestsExhausted(1), outcome)
        assertEquals(listOf(1_000L), sleeps)
    }
}
