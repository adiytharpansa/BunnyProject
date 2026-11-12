package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.SabrSegment
import com.bunny.engine.lontar.sabr.SpoolReader
import com.bunny.engine.lontar.sabr.SpoolRoadState
import com.bunny.engine.lontar.sabr.SpoolStore
import com.bunny.engine.lontar.sabr.TrackSpool
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The spool reader's traps (design Langkah 3): reading past what has
 * arrived WAITS for the pump; a dead road turns the wait into an
 * IOException; a completed track ends honestly.
 */
class SpoolReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** A track with a 20-byte init and one 100-byte segment (frontier 120). */
    private fun filledTrack(store: SpoolStore): TrackSpool {
        val track = store.open("vid").track(251)
        val init = ByteArray(20).also {
            it[0] = 0x1A; it[1] = 0x45; it[2] = 0xDF.toByte(); it[3] = 0xA3.toByte()
        }
        track.write(0, init)
        track.write(20, ByteArray(100) { 0x61 })
        track.noteInit(20)
        track.noteSegment(SabrSegment(1, 0, 10001, 20, 100))
        track.flushManifest()
        return track
    }

    @Test
    fun readsArrivedBytesAtAbsolutePositions() {
        val track = filledTrack(SpoolStore(File(tempFolder.root, "a")))
        val reader = SpoolReader(track, SpoolRoadState())
        val buffer = ByteArray(16)
        assertEquals(10, reader.readAt(0, buffer, 0, 10))
        assertEquals(0x1A.toByte(), buffer[0])
        assertEquals(5, reader.readAt(115, buffer, 0, 10))
        assertEquals(-1L, reader.knownLength())
    }

    @Test
    fun readPastTheFrontierWaitsForThePump() {
        val store = SpoolStore(File(tempFolder.root, "b"))
        val track = filledTrack(store)
        val road = SpoolRoadState()
        val reader = SpoolReader(track, road)
        val result = AtomicReference<Int>()
        val done = CountDownLatch(1)
        val thread = Thread {
            val buffer = ByteArray(10)
            result.set(reader.readAt(120, buffer, 0, 10))
            done.countDown()
        }
        thread.start()
        // Still blocked after a grace period: the bytes are not there.
        assertTrue(!done.await(300, TimeUnit.MILLISECONDS))
        // The pump lands the next segment and pulses.
        track.write(120, ByteArray(50) { 0x62 })
        track.noteSegment(SabrSegment(2, 10001, 10001, 120, 50))
        road.pulse()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(10, result.get())
        thread.join(1000)
    }

    @Test
    fun deadRoadTurnsTheWaitIntoAnIOException() {
        val track = filledTrack(SpoolStore(File(tempFolder.root, "c")))
        val road = SpoolRoadState()
        val reader = SpoolReader(track, road)
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val thread = Thread {
            try {
                reader.readAt(120, ByteArray(10), 0, 10)
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                done.countDown()
            }
        }
        thread.start()
        assertTrue(!done.await(300, TimeUnit.MILLISECONDS))
        road.kill("server menuntut token (SPS 3)")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(failure.get() is IOException)
        assertTrue(failure.get()!!.message!!.contains("SPS 3"))
        thread.join(1000)
        // And every later read fails immediately, too.
        try {
            reader.readAt(0, ByteArray(4), 0, 4)
            fail("read on a dead road must throw")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun firstWaitIsReportedOnceWithPositionAndFrontier() {
        val track = filledTrack(SpoolStore(File(tempFolder.root, "e")))
        val road = SpoolRoadState()
        val waits = ArrayList<Triple<Int, Long, Long>>()
        val reader = SpoolReader(
            track,
            road,
            onWait = { itag, pos, front ->
                synchronized(waits) { waits.add(Triple(itag, pos, front)) }
            },
        )
        val done = CountDownLatch(1)
        val thread = Thread {
            try {
                reader.readAt(500, ByteArray(10), 0, 10)
            } catch (e: IOException) {
                // The road is killed below; the wait ends as designed.
            } finally {
                done.countDown()
            }
        }
        thread.start()
        assertTrue(!done.await(300, TimeUnit.MILLISECONDS))
        road.kill("uji")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        thread.join(1000)
        assertEquals(1, waits.size)
        assertEquals(Triple(251, 500L, 120L), waits[0])
    }

    @Test
    fun longWaitIsReportedOnceWithPositionFrontierAndFileLength() {
        // M1: a read parked past the frontier for over two seconds
        // stops being a hiccup and becomes a named event — fired
        // ONCE for the episode, carrying the waited position, the
        // frontier, and the spool file's whole length, so a
        // far-ahead probe (position beyond everything on disk) is
        // tellable from plain sequential starvation.
        val track = filledTrack(SpoolStore(File(tempFolder.root, "g")))
        val road = SpoolRoadState()
        val longWaits = ArrayList<LongArray>()
        val reader = SpoolReader(
            track, road,
            onLongWait = { itag, pos, front, len ->
                synchronized(longWaits) {
                    longWaits.add(longArrayOf(itag.toLong(), pos, front, len))
                }
            },
        )
        val done = CountDownLatch(1)
        val thread = Thread {
            try {
                reader.readAt(500, ByteArray(10), 0, 10)
            } catch (e: IOException) {
                // The road is killed below; the wait ends as designed.
            } finally {
                done.countDown()
            }
        }
        thread.start()
        // The reader polls every 50 ms, so by 2.8 s the 2 s
        // threshold has been crossed on some poll — and only once.
        Thread.sleep(2_800)
        road.kill("uji")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        thread.join(1000)
        assertEquals(1, longWaits.size)
        assertArrayEquals(longArrayOf(251, 500, 120, 120), longWaits[0])
    }

    @Test
    fun backwardSeekIntoSpooledBytesIsServedAtOnce() {
        // Mechanism 23: a seek BACK into bytes the spool already
        // holds is served immediately, at the seeked position —
        // no wait, no error, the exact bytes the file holds there.
        val track = filledTrack(SpoolStore(File(tempFolder.root, "h")))
        val reader = SpoolReader(track, SpoolRoadState())
        val buffer = ByteArray(10)
        // Read near the end first (the playhead's first home)...
        assertEquals(10, reader.readAt(110, buffer, 0, 10))
        // ...then seek back into the segment region and the init.
        assertEquals(10, reader.readAt(30, buffer, 0, 10))
        assertEquals(0x61.toByte(), buffer[0])
        assertEquals(10, reader.readAt(0, buffer, 0, 10))
        assertEquals(0x1A.toByte(), buffer[0])
    }

    @Test
    fun forwardSeekPastTheFrontierWaitsAndCompletesWhenTheFillPassesIt() {
        // Mechanism 23: a seek FORWARD past everything the spool
        // holds is not an error — the read waits (like any read at
        // the frontier) and completes once the pump's fill passes
        // the seeked position, serving the bytes that landed there.
        val store = SpoolStore(File(tempFolder.root, "i"))
        val track = filledTrack(store)
        val road = SpoolRoadState()
        val reader = SpoolReader(track, road)
        val result = AtomicReference<Int>()
        val content = AtomicReference<Byte>()
        val done = CountDownLatch(1)
        val thread = Thread {
            val buffer = ByteArray(10)
            result.set(reader.readAt(140, buffer, 0, 10))
            content.set(buffer[0])
            done.countDown()
        }
        thread.start()
        // The frontier is 120; position 140 is beyond everything.
        assertTrue(!done.await(300, TimeUnit.MILLISECONDS))
        // The pump lands the next segment (120..170) and pulses.
        track.write(120, ByteArray(50) { 0x62 })
        track.noteSegment(SabrSegment(2, 10001, 10001, 120, 50))
        road.pulse()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(10, result.get())
        assertEquals(0x62.toByte(), content.get())
        thread.join(1000)
    }

    @Test
    fun servedBytesAreCountedForDiagnostics() {
        val track = filledTrack(SpoolStore(File(tempFolder.root, "f")))
        val reader = SpoolReader(track, SpoolRoadState())
        val buffer = ByteArray(16)
        reader.readAt(0, buffer, 0, 10)
        reader.readAt(10, buffer, 0, 16)
        assertEquals(26L, track.servedBytes)
    }

    @Test
    fun completedTrackEndsHonestly() {
        val track = filledTrack(SpoolStore(File(tempFolder.root, "d")))
        val road = SpoolRoadState()
        val reader = SpoolReader(track, road)
        road.markComplete(251)
        assertEquals(120L, reader.knownLength())
        assertEquals(-1, reader.readAt(120, ByteArray(8), 0, 8))
    }

    /** A track with two vouched islands: [0, 120) and [220, 320). */
    private fun twoRegionTrack(store: SpoolStore): TrackSpool {
        val track = store.open("vid").track(251)
        val head = ByteArray(20)
        byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
            .copyInto(head)
        track.write(0, head)
        track.write(20, ByteArray(100) { 0x61 })
        track.write(220, ByteArray(100) { 0x63 })
        track.noteFormat(SabrFormatId(251, 111L, "x"))
        track.noteInit(20)
        track.noteSegment(SabrSegment(1, 0, 10001, 20, 100))
        track.noteSegment(SabrSegment(3, 20002, 10001, 220, 100))
        track.flushManifest()
        return track
    }

    @Test
    fun readInsideTheSecondRegionIsServedAtOnce() {
        // Mechanism 23: service follows WRITTEN RANGES, not just
        // the first contiguous frontier — a jump-seeded island
        // serves while the seam before it is still open.
        val store = SpoolStore(File(tempFolder.root, "s-two"))
        val reader = SpoolReader(twoRegionTrack(store), SpoolRoadState())
        val buffer = ByteArray(16)
        assertEquals(10, reader.readAt(250, buffer, 0, 10))
        assertEquals(0x63.toByte(), buffer[0])
        // Up to the region's end, and no further.
        assertEquals(5, reader.readAt(315, buffer, 0, 10))
    }

    @Test
    fun readInsideTheGapWaitsUntilTheGapIsFilled() {
        val store = SpoolStore(File(tempFolder.root, "s-gap"))
        val track = twoRegionTrack(store)
        val road = SpoolRoadState()
        val reader = SpoolReader(track, road)
        val outcome = AtomicReference<Result<Int>?>(null)
        val worker = Thread {
            val buffer = ByteArray(16)
            outcome.set(runCatching { reader.readAt(150, buffer, 0, 10) })
        }
        worker.isDaemon = true
        worker.start()
        Thread.sleep(400)
        assertNull(outcome.get())
        // The backfill lands: bytes plus the segment note, then
        // the road's pulse wakes the reader.
        track.write(120, ByteArray(100) { 0x62 })
        track.noteSegment(SabrSegment(2, 10001, 10001, 120, 100))
        road.pulse()
        worker.join(3000)
        assertEquals(10, outcome.get()?.getOrThrow())
    }

    @Test
    fun completedRoadEndsReadsInsideAGap() {
        // A completed track's gap is a terminal fact, not a wait.
        val store = SpoolStore(File(tempFolder.root, "s-done"))
        val road = SpoolRoadState()
        val reader = SpoolReader(twoRegionTrack(store), road)
        road.markComplete(251)
        val buffer = ByteArray(16)
        assertEquals(-1, reader.readAt(150, buffer, 0, 10))
        assertEquals(-1, reader.readAt(400, buffer, 0, 10))
        // The second region itself still serves.
        assertEquals(10, reader.readAt(250, buffer, 0, 10))
    }

}
