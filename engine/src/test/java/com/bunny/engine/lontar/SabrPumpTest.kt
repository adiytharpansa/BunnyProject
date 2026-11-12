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
import com.bunny.engine.lontar.sabr.SpoolReader
import com.bunny.engine.lontar.sabr.SpoolRoadState
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
    fun tokenDemandedMidStreamEndsTheRunAndKeepsSpooledBytes() = runBlocking {
        val initBytes = ByteArray(20).also {
            it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte()
            it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
        }
        val media = response(
            headerPart(0, 251, null, 0, 20, init = true),
            mediaPart(0, initBytes),
            nrpPart(60_000),
        )
        val sp = spool()
        val transport = QueueTransport(listOf(media, response(spsPart(3))))
        val outcome = pump(session(), sp, transport, ArrayList()).run(5)
        assertEquals(SabrOutcome.TokenDemanded, outcome)
        // What already flowed stays in the spool for the next open —
        // a mid-stream death never takes rolled bytes with it.
        assertEquals(20L, sp.track(251).fileLength())
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

    /**
     * Every answer carries one more 10-second audio segment (the
     * session lasts 100 s over 10 segments, so each prices at 10 s)
     * plus the server's usual 60 s backoff.
     */
    private fun segmentTransport(): SabrTransport = object : SabrTransport {
        private var calls = 0

        override suspend fun post(url: String, body: ByteArray): ByteArray {
            calls++
            val seq = calls
            return if (seq == 1) {
                response(
                    initMetadataPart(251, 10),
                    headerPart(0, 251, null, 0, 20, init = true),
                    mediaPart(0, ByteArray(20)),
                    headerPart(1, 251, 1, 20, 100, init = false),
                    mediaPart(1, ByteArray(100)),
                    nrpPart(60_000),
                )
            } else {
                response(
                    headerPart(1, 251, seq, 20L + (seq - 1) * 100, 100, init = false),
                    mediaPart(1, ByteArray(100)),
                    nrpPart(60_000),
                )
            }
        }
    }

    @Test
    fun thinLiveLeadKeepsThePumpHungryPastTheColdWindow() = runBlocking {
        val s = session()
        val sp = spool()
        val sleeps = ArrayList<Long>()
        val p = SabrPump(
            session = s,
            spool = sp,
            transport = segmentTransport(),
            bandwidth = FixedBandwidth,
            sleeper = { sleeps.add(it) },
            hungryUntilMs = 60_000,
            positionHintMs = { 30_000 },
        )
        val outcome = p.run(7)
        assertEquals(SabrOutcome.RequestsExhausted(7), outcome)
        // The cold window closes at 60 s of timeline (request 6),
        // but the playhead sits at 30 s: the lead stays under the
        // 45 s line, so every sleep remains at the hungry cap. On
        // device, the polite 20 s sleep in exactly this window
        // starved a playing video into a mid-stream rebuffer.
        assertEquals(7, sleeps.size)
        assertTrue(sleeps.all { it == 1_000L })
        // And the reader side of the same spool serves from byte
        // zero: the pump → spool → reader chain, proven end to end.
        val reader = SpoolReader(sp.track(251), SpoolRoadState())
        assertEquals(10, reader.readAt(0, ByteArray(10), 0, 10))
    }

    @Test
    fun fatLiveLeadLetsThePumpSleepPolitely() = runBlocking {
        val s = session()
        val sleeps = ArrayList<Long>()
        val p = SabrPump(
            session = s,
            spool = spool(),
            transport = segmentTransport(),
            bandwidth = FixedBandwidth,
            sleeper = { sleeps.add(it) },
            hungryUntilMs = 60_000,
            positionHintMs = { s.bufferedEndMs() - 60_000 },
        )
        p.run(7)
        // The lead is a constant 60 s — beyond the hunger line — so
        // once the cold window closes (request 6 leaves 60 s
        // spooled), the pump returns to the polite cadence.
        assertEquals(1_000L, sleeps[4])
        assertEquals(20_000L, sleeps[5])
        assertEquals(20_000L, sleeps[6])
    }

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

    @Test
    fun leadCapHoldsRequestsUntilThePlayheadCatchesUp() = runBlocking {
        // Mechanisms 3 + 22: with a 25 s lead cap, the pump must
        // never POST while its lead over the playhead is fat (one
        // segment of overshoot is inherent — the check is
        // pre-request); it waits in 1 s pauses instead, and each
        // pause here pretends the playhead ate 10 s.
        val s = session()
        var hint = 0L
        var postsAtFatLead = 0
        val base = segmentTransport()
        val transport = object : SabrTransport {
            override suspend fun post(url: String, body: ByteArray): ByteArray {
                if (s.bufferedEndMs() - hint > 35_000) postsAtFatLead++
                return base.post(url, body)
            }
        }
        val sleeps = ArrayList<Long>()
        val p = SabrPump(
            session = s,
            spool = spool(),
            transport = transport,
            bandwidth = FixedBandwidth,
            sleeper = { ms ->
                sleeps.add(ms)
                if (ms == SabrPump.LEAD_PAUSE_MS) hint += 10_000
            },
            positionHintMs = { hint },
            maxLeadMs = 25_000,
        )
        val outcome = p.run(8)
        assertEquals(SabrOutcome.RequestsExhausted(8), outcome)
        assertEquals(0, postsAtFatLead)
        assertTrue(
            "the cap must actually have paused the pump",
            sleeps.count { it == SabrPump.LEAD_PAUSE_MS } >= 2,
        )
    }

    @Test
    fun splitSegmentLandsWholeInTheSpool() = runBlocking {
        // Regression (device stall, 2026-11-12 22:32): the video
        // segment arrives in two header groups with the SAME
        // sequence number. The spool must end up holding both
        // groups contiguously — before the fix, the second group
        // was suppressed as a "duplicate" and the reader starved
        // at the hole while the session reported the timeline
        // complete.
        val resp = response(
            initMetadataPart(251, 1),
            initMetadataPart(136, 1),
            headerPart(0, 251, null, 0, 20, init = true),
            mediaPart(0, ByteArray(20)),
            headerPart(1, 251, 1, 20, 100, init = false),
            mediaPart(1, ByteArray(100) { 7 }),
            headerPart(2, 136, null, 0, 20, init = true),
            mediaPart(2, ByteArray(20)),
            headerPart(3, 136, 1, 20, 100, init = false),
            mediaPart(3, ByteArray(100) { 1 }),
            headerPart(4, 136, 1, 120, 100, init = false),
            mediaPart(4, ByteArray(100) { 2 }),
        )
        val sp = spool()
        val outcome = pump(session(), sp, QueueTransport(listOf(resp)), ArrayList())
            .run(1)
        assertEquals(SabrOutcome.Completed, outcome)
        val video = sp.track(136)

        assertEquals(220L, video.fileLength())
        assertEquals(220L, video.frontier())
        assertTrue(video.read(20, 100).all { it == 1.toByte() })
        assertTrue(video.read(120, 100).all { it == 2.toByte() })
    }

    @Test
    fun leadBoundedSleepBandEdges() {
        // M8's pure decision, at its edges. Thin lead: the
        // server's backoff is ignored outright.
        assertEquals(200L, SabrPump.leadBoundedSleep(60_000, 0, 100))
        assertEquals(200L, SabrPump.leadBoundedSleep(60_000, 29_999, 100))
        // Middle: the backoff under a 5 s ceiling; none stated → 5 s.
        assertEquals(5_000L, SabrPump.leadBoundedSleep(60_000, 30_000, 100))
        assertEquals(3_000L, SabrPump.leadBoundedSleep(3_000, 60_000, 100))
        assertEquals(5_000L, SabrPump.leadBoundedSleep(null, 60_000, 100))
        // Fat: the backoff under a 30 s ceiling; none stated → 30 s.
        assertEquals(30_000L, SabrPump.leadBoundedSleep(60_000, 120_000, 100))
        assertEquals(30_000L, SabrPump.leadBoundedSleep(null, 300_000, 100))
        // A media-less answer with no backoff keeps the legacy
        // 200 ms cadence in every band.
        assertEquals(200L, SabrPump.leadBoundedSleep(null, 0, 0))
        assertEquals(200L, SabrPump.leadBoundedSleep(null, 300_000, 0))
    }

    @Test
    fun leadAwareSleepWalksTheBandsAsTheLeadGrows() = runBlocking {
        // M8 end to end: the transport adds 10 s of timeline per
        // request against a 60 s server backoff while the
        // playhead sits at 0, so the lead walks thin (two floor
        // sleeps), middle (nine 5 s sleeps), then fat (30 s).
        val sleeps = ArrayList<Long>()
        val p = SabrPump(
            session = session(),
            spool = spool(),
            transport = segmentTransport(),
            bandwidth = FixedBandwidth,
            sleeper = { sleeps.add(it) },
            positionHintMs = { 0L },
            leadAware = true,
        )
        val outcome = p.run(13)
        assertEquals(SabrOutcome.RequestsExhausted(13), outcome)
        assertEquals(
            listOf(200L, 200L) + List(9) { 5_000L } + List(2) { 30_000L },
            sleeps,
        )
    }

    @Test
    fun leadAwareOffKeepsTheLegacyPoliteSleep() = runBlocking {
        // The probe/replay contract, stated explicitly: with
        // leadAware off a media-bearing answer under a 60 s
        // backoff still sleeps the polite 20 s — the recordings'
        // cadence is untouched by M8's existence.
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
        val p = SabrPump(
            session = session(),
            spool = spool(),
            transport = QueueTransport(listOf(resp)),
            bandwidth = FixedBandwidth,
            sleeper = { sleeps.add(it) },
            leadAware = false,
        )
        val outcome = p.run(1)
        assertEquals(SabrOutcome.RequestsExhausted(1), outcome)
        assertEquals(listOf(20_000L), sleeps)
    }

    @Test
    fun playbackTemperamentKeepsTwoMinutesOfLead() = runBlocking {
        // The live drive's cadence (device evidence 2026-11-12
        // 22:50): hungry 1 s sleeps while the spool's lead over
        // the playhead is under two minutes — the cold window
        // alone (60 s) is not what keeps it hungry at 100 s —
        // then polite sleeps capped at 5 s, never the probe's
        // 20 s. The transport adds 10 s of timeline per request
        // against a 60 s server backoff; the playhead sits at 0.
        val sleeps = ArrayList<Long>()
        val p = SabrPump(
            session = session(),
            spool = spool(),
            transport = segmentTransport(),
            bandwidth = FixedBandwidth,
            sleeper = { sleeps.add(it) },
            hungryUntilMs = 60_000,
            hungryLeadMs = 120_000,
            politeCapMs = 5_000,
            positionHintMs = { 0L },
        )
        val outcome = p.run(14)
        assertEquals(SabrOutcome.RequestsExhausted(14), outcome)
        assertEquals(
            List(11) { 1_000L } + List(3) { 5_000L },
            sleeps,
        )
    }
}
