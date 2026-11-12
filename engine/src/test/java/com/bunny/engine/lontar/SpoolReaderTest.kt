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
import org.junit.Assert.assertEquals
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
    fun completedTrackEndsHonestly() {
        val track = filledTrack(SpoolStore(File(tempFolder.root, "d")))
        val road = SpoolRoadState()
        val reader = SpoolReader(track, road)
        road.markComplete(251)
        assertEquals(120L, reader.knownLength())
        assertEquals(-1, reader.readAt(120, ByteArray(8), 0, 8))
    }
}
