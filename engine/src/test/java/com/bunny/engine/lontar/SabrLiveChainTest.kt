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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Langkah 3 chain at simulated real time — the closest CI can
 * come to the device test. A pump fills a real spool from scripted
 * wire answers while a simulated player drains both tracks through
 * real [SpoolReader]s at playhead pace. The invariants the A15
 * verdicts established, pinned forever:
 *
 * - a reader behind the spool lead NEVER waits (no starvation);
 * - every byte the wire sent lands in the addressed track's spool,
 *   byte-exact and in order (the 0 KB video file verdict);
 * - the pump holds the hungry cadence while the lead is thin (the
 *   mid-stream rebuffer verdict).
 *
 * The session lasts 100 s over 10 segments per track, so every
 * segment prices at exactly 10 s of timeline.
 */
class SabrLiveChainTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    private fun nrpPart(backoff: Long): ByteArray =
        umpPart(35, ProtoWriter().fieldVarint(3, backoff).build())

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

    private object FixedBandwidth : BandwidthMeter {
        override fun estimateBps(): Long = 5_000_000
        override fun observe(bytes: Int, durationMs: Long) = Unit
    }

    private fun audioSeg(seq: Int) = ByteArray(100) { (0x40 + seq).toByte() }
    private fun videoSeg(seq: Int) = ByteArray(500) { (0x60 + seq).toByte() }

    /**
     * Every answer carries one more segment per track plus the
     * server's usual 60 s backoff; everything sent is recorded so
     * the drained bytes can be compared byte for byte.
     */
    private inner class LiveTransport : SabrTransport {
        var calls = 0
        val sentAudio = ArrayList<Byte>()
        val sentVideo = ArrayList<Byte>()

        private fun record(list: ArrayList<Byte>, data: ByteArray) {
            for (b in data) list.add(b)
        }

        override suspend fun post(url: String, body: ByteArray): ByteArray {
            calls++
            val seq = calls
            val audio = audioSeg(seq)
            val video = videoSeg(seq)
            record(sentAudio, audio)
            record(sentVideo, video)
            return if (seq == 1) {
                val audioInit = ByteArray(20) { 0x11 }
                val videoInit = ByteArray(24) { 0x22 }
                record(sentAudio, audioInit)
                record(sentVideo, videoInit)
                // Init bytes belong at the head of each track's byte
                // stream, ahead of segment 1 — re-record in order.
                sentAudio.clear()
                sentVideo.clear()
                record(sentAudio, audioInit)
                record(sentAudio, audio)
                record(sentVideo, videoInit)
                record(sentVideo, video)
                response(
                    initMetadataPart(251, 10),
                    initMetadataPart(136, 10),
                    headerPart(0, 251, null, 0, 20, init = true),
                    mediaPart(0, audioInit),
                    headerPart(1, 136, null, 0, 24, init = true),
                    mediaPart(1, videoInit),
                    headerPart(2, 251, 1, 20, 100, init = false),
                    mediaPart(2, audio),
                    headerPart(3, 136, 1, 24, 500, init = false),
                    mediaPart(3, video),
                    nrpPart(60_000),
                )
            } else {
                response(
                    headerPart(
                        2, 251, seq, 20L + (seq - 1) * 100, 100, init = false
                    ),
                    mediaPart(2, audio),
                    headerPart(
                        3, 136, seq, 24L + (seq - 1) * 500, 500, init = false
                    ),
                    mediaPart(3, video),
                    nrpPart(60_000),
                )
            }
        }
    }

    private class DrainState(var position: Long = 0)

    /**
     * Reads [targetBytes] from [reader] on a worker thread. A read
     * that has to wait (starvation) never counts the latch down, and
     * the step fails with the starvation named.
     */
    private fun drain(
        reader: SpoolReader,
        state: DrainState,
        targetBytes: Long,
        sink: ArrayList<Byte>,
    ) {
        if (state.position >= targetBytes) return
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                val buffer = ByteArray(64)
                while (state.position < targetBytes) {
                    val n = reader.readAt(
                        state.position, buffer, 0,
                        minOf(64L, targetBytes - state.position).toInt(),
                    )
                    check(n > 0) { "reader returned $n below its target" }
                    for (i in 0 until n) sink.add(buffer[i])
                    state.position += n
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }
        thread.isDaemon = true
        thread.start()
        if (!done.await(5, TimeUnit.SECONDS)) {
            fail(
                "reader starved below the spool lead " +
                    "(position ${state.position} of $targetBytes)"
            )
        }
        thread.join(1000)
        failure.get()?.let { throw AssertionError("reader failed", it) }
    }

    private fun List<Byte>.bytes(): ByteArray = ByteArray(size) { this[it] }

    // ---------------------------------------------------------------- test

    @Test
    fun liveChainNeverStarvesAndDeliversEveryByte() = runBlocking {
        val session = SabrSession(
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
        val spool = SpoolStore(File(tempFolder.root, "live")).open("vid")
        val road = SpoolRoadState()
        val audioReader = SpoolReader(spool.track(251), road)
        val videoReader = SpoolReader(spool.track(136), road)
        val audioState = DrainState()
        val videoState = DrainState()
        val drainedAudio = ArrayList<Byte>()
        val drainedVideo = ArrayList<Byte>()
        val transport = LiveTransport()
        val sleeps = ArrayList<Long>()
        var playheadMs = 0L
        var exchanges = 0

        val pump = SabrPump(
            session = session,
            spool = spool,
            transport = transport,
            bandwidth = FixedBandwidth,
            sleeper = { sleeps.add(it) },
            onProgress = {
                exchanges++
                // The player consumes one segment of playback per
                // exchange and stays one segment behind the frontier.
                playheadMs = (exchanges - 1) * 10_000L
                val segmentsPlayed = (playheadMs / 10_000).toInt()
                drain(
                    audioReader, audioState,
                    20L + segmentsPlayed * 100L, drainedAudio,
                )
                drain(
                    videoReader, videoState,
                    24L + segmentsPlayed * 500L, drainedVideo,
                )
            },
            hungryUntilMs = 60_000,
            positionHintMs = { playheadMs },
        )
        val outcome = pump.run(7)

        assertEquals(SabrOutcome.RequestsExhausted(7), outcome)
        // Thin lead the whole run: no polite sleep may appear.
        assertEquals(7, sleeps.size)
        assertTrue(sleeps.all { it == 1_000L })

        // Final drain to the very end of what the wire sent.
        drain(audioReader, audioState, 720, drainedAudio)
        drain(videoReader, videoState, 3524, drainedVideo)

        // Every byte the wire sent reached the reader of the track
        // it was addressed to, byte-exact and in order.
        assertArrayEquals(transport.sentAudio.bytes(), drainedAudio.bytes())
        assertArrayEquals(transport.sentVideo.bytes(), drainedVideo.bytes())
        assertEquals(720L, spool.track(251).fileLength())
        assertEquals(3524L, spool.track(136).fileLength())
        assertEquals(720L, spool.track(251).servedBytes)
        assertEquals(3524L, spool.track(136).servedBytes)
    }
}
