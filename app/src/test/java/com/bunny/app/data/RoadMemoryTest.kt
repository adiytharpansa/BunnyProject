package com.bunny.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mechanism 10 (Langkah 6): the road memory — a hard refusal is
 * remembered and skips the road, an OK road replaces it, and the
 * memory expires on its TTL and clears at its cap. Soft failures
 * are not modelled at all: the class has no way to write one.
 */
class RoadMemoryTest {

    private var now = 1_000L

    private fun memory(ttlMs: Long = 7_000L, maxEntries: Int = 200) =
        RoadMemory(ttlMs = ttlMs, maxEntries = maxEntries, nowMs = { now })

    @Test
    fun hardRefusalIsRememberedAndSkipsTheRoad() {
        val m = memory()
        m.record("vid", RoadMemory.Outcome.HARD)
        assertTrue(m.isHardRefused("vid"))
        assertEquals(RoadMemory.Outcome.HARD, m.outcome("vid"))
    }

    @Test
    fun okReplacesAnOlderRefusal() {
        val m = memory()
        m.record("vid", RoadMemory.Outcome.HARD)
        m.record("vid", RoadMemory.Outcome.OK)
        assertFalse(m.isHardRefused("vid"))
        assertEquals(RoadMemory.Outcome.OK, m.outcome("vid"))
    }

    @Test
    fun anOkRoadNeverSkips() {
        val m = memory()
        m.record("vid", RoadMemory.Outcome.OK)
        assertFalse(m.isHardRefused("vid"))
    }

    @Test
    fun unknownVideoHasNoMemory() {
        val m = memory()
        assertNull(m.outcome("nope"))
        assertFalse(m.isHardRefused("nope"))
    }

    @Test
    fun refusalExpiresAtTheTtl() {
        val m = memory(ttlMs = 7_000L)
        m.record("vid", RoadMemory.Outcome.HARD)
        now += 6_999
        assertTrue("still remembered just inside the window", m.isHardRefused("vid"))
        now += 1
        assertFalse("at the TTL the road earns a fresh chance", m.isHardRefused("vid"))
        assertNull(m.outcome("vid"))
    }

    @Test
    fun capacityClearsTheWholeNotebook() {
        val m = memory(maxEntries = 3)
        m.record("a", RoadMemory.Outcome.HARD)
        m.record("b", RoadMemory.Outcome.HARD)
        m.record("c", RoadMemory.Outcome.HARD)
        m.record("d", RoadMemory.Outcome.HARD)
        assertEquals(1, m.size)
        assertFalse(m.isHardRefused("a"))
        assertTrue(m.isHardRefused("d"))
    }

    @Test
    fun refreshingAnExistingEntryDoesNotClearTheNotebook() {
        val m = memory(maxEntries = 2)
        m.record("a", RoadMemory.Outcome.HARD)
        m.record("b", RoadMemory.Outcome.HARD)
        m.record("a", RoadMemory.Outcome.OK)
        assertEquals(2, m.size)
        assertTrue(m.isHardRefused("b"))
    }

    @Test
    fun snapshotRoundTripRestoresTheMemory() {
        val m = memory()
        m.record("vid", RoadMemory.Outcome.HARD)
        val other = memory()
        other.loadSnapshot(m.snapshot())
        assertTrue(other.isHardRefused("vid"))
        assertEquals(m.snapshot(), other.snapshot())
    }
}
