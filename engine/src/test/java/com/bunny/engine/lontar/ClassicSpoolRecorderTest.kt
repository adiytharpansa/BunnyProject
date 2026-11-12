package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.ClassicSpoolRecorder
import com.bunny.engine.lontar.sabr.SabrFormatId
import com.bunny.engine.lontar.sabr.SabrSegment
import com.bunny.engine.lontar.sabr.SpoolStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The M14 recorder's gate: classic bytes may join a spool track
 * only when the byte spaces provably agree — the rendition-switch
 * lesson (wire evidence 2026-11-13) enforced at the door.
 */
class ClassicSpoolRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun ebmlInit(size: Int): ByteArray {
        val b = ByteArray(size)
        b[0] = 0x1A; b[1] = 0x45; b[2] = 0xDF.toByte(); b[3] = 0xA3.toByte()
        return b
    }

    @Test
    fun freshSpoolAcceptsAndRecords() {
        val store = SpoolStore(File(tempFolder.root, "s"))
        val spool = store.open("vid")
        val recorder = ClassicSpoolRecorder(store, spool, mapOf(251 to 111L))
        assertEquals(setOf(251), recorder.recordableItags)

        recorder.sink(251, 0L, ebmlInit(20))
        recorder.sink(251, 20L, ByteArray(300) { 0x62 })
        // An itag nobody offered a rendition for records nothing.
        recorder.sink(137, 0L, ByteArray(10))
        recorder.close()

        val reopened = store.open("vid")
        val track = reopened.track(251)
        assertEquals(320L, track.fileLength())
        assertEquals(111L, track.classicRendition)
        assertEquals(listOf(0L..319L), track.classicRanges())
        // Recorded classic bytes are a sidecar, never SABR truth.
        assertTrue(track.segments.isEmpty())
        assertEquals(0L, track.initLength)
        assertTrue(reopened.track(137).classicRanges().isEmpty())
        reopened.close()
    }

    @Test
    fun sabrStateUnderAnotherRenditionWithDeltaRefuses() {
        val store = SpoolStore(File(tempFolder.root, "s"))
        val spool = store.open("vid")
        val audio = spool.track(251)
        audio.write(0, ebmlInit(100))
        audio.write(100, ByteArray(1000))
        audio.noteFormat(SabrFormatId(251, 999L, null))
        audio.noteInit(100)
        audio.noteSegment(SabrSegment(1, 0L, 10_000L, 100L, 1000L, spoolDelta = -118L))
        audio.noteWireState(999L, -118L)
        audio.flushManifest()
        spool.close()

        // The classic road now offers rendition A (111) for the
        // same itag the spool holds under rendition B, anchored:
        // refused — no writes, no ranges, playback untouched.
        val reopened = store.open("vid")
        val before = reopened.track(251).fileLength()
        val recorder = ClassicSpoolRecorder(store, reopened, mapOf(251 to 111L))
        assertTrue(recorder.recordableItags.isEmpty())
        recorder.sink(251, 0L, ByteArray(64))
        recorder.close()

        val after = store.open("vid").track(251)
        assertEquals(before, after.fileLength())
        assertTrue(after.classicRanges().isEmpty())
        assertEquals(0L, after.classicRendition)
    }

    @Test
    fun sabrStateUnderTheSameRenditionAtDeltaZeroAccepts() {
        val store = SpoolStore(File(tempFolder.root, "s"))
        val spool = store.open("vid")
        val audio = spool.track(251)
        audio.write(0, ebmlInit(100))
        audio.write(100, ByteArray(400))
        audio.noteFormat(SabrFormatId(251, 111L, "x"))
        audio.noteInit(100)
        audio.noteSegment(SabrSegment(1, 0L, 10_000L, 100L, 400L))
        audio.noteWireState(111L, 0L)
        audio.flushManifest()
        spool.close()

        val reopened = store.open("vid")
        val recorder = ClassicSpoolRecorder(store, reopened, mapOf(251 to 111L))
        assertEquals(setOf(251), recorder.recordableItags)
        recorder.sink(251, 500L, ByteArray(600) { 0x63 })
        recorder.close()

        val track = store.open("vid").track(251)
        assertEquals(listOf(500L..1099L), track.classicRanges())
        assertEquals(111L, track.classicRendition)
        // The SABR facts themselves were not touched.
        assertEquals(400L, track.segments.getValue(1).length)
        assertEquals(100L, track.initLength)
    }
}
