package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.SabrFormatId
import com.bunny.engine.lontar.sabr.SabrSegment
import com.bunny.engine.lontar.sabr.SpoolStore
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The spool: absolute-offset storage, manifest truth, sanity, budget. */
class SpoolTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun ftypInit(size: Int): ByteArray {
        val b = ByteArray(size)
        b[4] = 'f'.code.toByte(); b[5] = 't'.code.toByte()
        b[6] = 'y'.code.toByte(); b[7] = 'p'.code.toByte()
        return b
    }

    private fun ebmlInit(size: Int): ByteArray {
        val b = ByteArray(size)
        b[0] = 0x1A; b[1] = 0x45; b[2] = 0xDF.toByte(); b[3] = 0xA3.toByte()
        return b
    }

    @Test
    fun writesAndReadsAtAbsoluteOffsets() {
        val store = SpoolStore(File(tempFolder.root, "s"))
        val spool = store.open("vid1")
        val track = spool.track(140)
        track.write(0, ftypInit(16))
        track.write(100, ByteArray(50) { 0x61 })
        assertEquals(150L, track.fileLength())
        assertArrayEquals(ftypInit(16), track.read(0, 16))
        assertEquals(0x61.toByte(), track.read(100, 1)[0])

        track.noteInit(16)
        track.noteSegment(SabrSegment(1, 0, 5000, 100, 50))
        track.flushManifest()
        assertTrue(track.hasRange(100, 50))
        assertFalse(track.hasRange(100, 51))
        assertTrue(track.hasPlayableHead())
        assertEquals(listOf(0L..15L, 100L..149L), track.writtenRanges())
        spool.close()
    }

    @Test
    fun frontierStopsAtTheFirstGapAndResumesWhenFilled() {
        val store = SpoolStore(File(tempFolder.root, "g"))
        val track = store.open("vid").track(140)
        track.write(0, ftypInit(16))
        track.noteInit(16)
        // Only segment 2 lands (bytes 116..165); segment 1 is missing.
        track.write(116, ByteArray(50) { 0x63 })
        track.noteSegment(SabrSegment(2, 5000, 5000, 116, 50))
        track.flushManifest()
        // The frontier must stop at the gap: only the init is playable.
        assertEquals(16L, track.frontier())
        // Segment 1 fills the gap (bytes 16..115): the frontier now
        // runs unbroken to the end of segment 2.
        track.write(16, ByteArray(100) { 0x64 })
        track.noteSegment(SabrSegment(1, 0, 5000, 16, 100))
        track.flushManifest()
        assertEquals(166L, track.frontier())
    }

    @Test
    fun manifestSurvivesReopen() {
        val dir = File(tempFolder.root, "s")
        val spool = SpoolStore(dir).open("vid2")
        val track = spool.track(251)
        track.write(0, ebmlInit(20))
        track.write(20, ByteArray(300) { 0x62 })
        track.noteFormat(SabrFormatId(251, 123L, "xt"))
        track.noteEndSegmentNumber(40)
        track.noteInit(20)
        track.noteSegment(SabrSegment(1, 0, 10001, 20, 300))
        track.flushManifest()
        spool.close()

        val reopened = SpoolStore(dir).open("vid2").track(251)
        assertEquals(20L, reopened.initLength)
        assertEquals(40, reopened.endSegmentNumber)
        assertEquals(SabrFormatId(251, 123L, "xt"), reopened.formatId)
        assertEquals(setOf(1), reopened.segments.keys)
        assertEquals(300L, reopened.segments[1]?.length)
        assertTrue(reopened.hasPlayableHead())
    }

    @Test
    fun sanityDropsSegmentPastFileEnd() {
        val dir = File(tempFolder.root, "s")
        val spool = SpoolStore(dir).open("vid3")
        val track = spool.track(140)
        track.write(0, ftypInit(16))
        track.noteInit(16)
        // The manifest will claim 5000 bytes that the file does not hold.
        track.noteSegment(SabrSegment(1, 0, 5000, 16, 5000))
        track.flushManifest()
        spool.close()

        val reopened = SpoolStore(dir).open("vid3").track(140)
        assertTrue(reopened.segments.isEmpty())
        assertEquals(16L, reopened.initLength)
    }

    @Test
    fun sanityStrikesInitWithUnknownHeadButKeepsSegments() {
        val dir = File(tempFolder.root, "s")
        val spool = SpoolStore(dir).open("vid4")
        val track = spool.track(140)
        track.write(0, ByteArray(16)) // no ftyp, no EBML — not a container head
        track.write(16, ByteArray(100) { 0x63 })
        track.noteInit(16)
        track.noteSegment(SabrSegment(1, 0, 5000, 16, 100))
        track.flushManifest()
        spool.close()

        val reopened = SpoolStore(dir).open("vid4").track(140)
        assertEquals(0L, reopened.initLength)
        assertFalse(reopened.hasPlayableHead())
        assertEquals(setOf(1), reopened.segments.keys)
    }

    @Test
    fun budgetEvictsLeastRecentlyTouchedAndProtects() {
        val dir = File(tempFolder.root, "b")
        val store = SpoolStore(dir, budgetBytes = 1000)
        for (id in listOf("old", "mid", "new")) {
            val spool = store.open(id)
            spool.track(1).write(0, ByteArray(600))
            spool.close()
        }
        File(dir, "old/lastaccess").setLastModified(1000)
        File(dir, "mid/lastaccess").setLastModified(2000)
        File(dir, "new/lastaccess").setLastModified(3000)

        val evicted = store.enforceBudget(protectVideoId = "new")
        assertEquals(listOf("old", "mid"), evicted)
        assertFalse(store.exists("old"))
        assertFalse(store.exists("mid"))
        assertTrue(store.exists("new"))
    }
}
