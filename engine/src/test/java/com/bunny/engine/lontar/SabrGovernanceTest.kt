package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.SabrGovernance
import com.bunny.engine.lontar.sabr.SabrKillThreshold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Langkah 7, mechanism 7: the self-disabling governance's pure
 * rules — the minimum-attempts floor, the rate threshold, the
 * sliding window that heals, per-class separation, dilution by
 * successes, the bounded shelf, and persistence.
 */
class SabrGovernanceTest {

    /** An in-memory shelf that keeps what the governance writes. */
    private class MemoryStore : SabrGovernanceStore {
        var text: String? = null
        override fun load(): String? = text
        override fun save(serialized: String) {
            text = serialized
        }
    }

    private val threshold = SabrKillThreshold.DEFAULT

    private fun governance(
        store: SabrGovernanceStore = MemoryStore(),
    ): SabrGovernance {
        var tick = 1_700_000_000_000L
        return SabrGovernance(store) { tick += 1000 }
    }

    private fun record(
        governance: SabrGovernance,
        successes: Int,
        failures: Int,
        networkClass: String = "WIFI",
    ) {
        repeat(failures) { governance.record(false, networkClass) }
        repeat(successes) { governance.record(true, networkClass) }
    }

    @Test
    fun belowMinAttempts_neverDisables() {
        val g = governance()
        // Seven straight failures: a bad evening, not a verdict —
        // the floor (8) keeps the road alive.
        record(g, successes = 0, failures = 7)
        val verdict = g.verdict("WIFI", threshold)
        assertEquals(7, verdict.attempts)
        assertEquals(7, verdict.failures)
        assertFalse(verdict.disabled)
    }

    @Test
    fun rateAtThreshold_disables() {
        val g = governance()
        // 8 attempts, half failed: the rate AT the threshold
        // disables (the rule is >=, as the design's "melewati
        // ambang" is enforced at the boundary).
        record(g, successes = 4, failures = 4)
        val verdict = g.verdict("WIFI", threshold)
        assertEquals(8, verdict.attempts)
        assertTrue(verdict.disabled)
    }

    @Test
    fun rateBelowThreshold_staysEnabled() {
        val g = governance()
        record(g, successes = 5, failures = 3)
        assertFalse(g.verdict("WIFI", threshold).disabled)
        // A fuller window still below the rate: 9 of 20.
        record(g, successes = 6, failures = 6)
        val verdict = g.verdict("WIFI", threshold)
        assertEquals(20, verdict.attempts)
        assertEquals(9, verdict.failures)
        assertFalse(verdict.disabled)
    }

    @Test
    fun windowSlides_oldFailuresExpire_andTheRoadHeals() {
        val g = governance()
        record(g, successes = 0, failures = 10)
        assertTrue(g.verdict("WIFI", threshold).disabled)
        // Successes push the failures out of the last-20 window:
        // after 11 of them the window holds 9 failures of 20.
        record(g, successes = 11, failures = 0)
        val verdict = g.verdict("WIFI", threshold)
        assertEquals(20, verdict.attempts)
        assertEquals(9, verdict.failures)
        assertFalse(verdict.disabled)
    }

    @Test
    fun classesAreJudgedSeparately() {
        val g = governance()
        record(g, successes = 0, failures = 10, networkClass = "SELULER")
        assertTrue(g.verdict("SELULER", threshold).disabled)
        val wifi = g.verdict("WIFI", threshold)
        assertEquals(0, wifi.attempts)
        assertFalse(wifi.disabled)
    }

    @Test
    fun successesDiluteTheRate() {
        val g = governance()
        record(g, successes = 4, failures = 4)
        assertTrue(g.verdict("WIFI", threshold).disabled)
        record(g, successes = 2, failures = 0)
        val verdict = g.verdict("WIFI", threshold)
        assertEquals(10, verdict.attempts)
        assertEquals(4, verdict.failures)
        assertFalse(verdict.disabled)
    }

    @Test
    fun customThreshold_isHonoured() {
        val g = governance()
        val strict = SabrKillThreshold(
            maxFailureRate = 0.25,
            windowAttempts = 10,
            minAttempts = 4,
        )
        record(g, successes = 3, failures = 1)
        assertTrue(g.verdict("WIFI", strict).disabled)
        assertFalse(g.verdict("WIFI", threshold).disabled)
    }

    @Test
    fun persistenceRoundTrip_keepsTheWindow() {
        val store = MemoryStore()
        val first = governance(store)
        record(first, successes = 2, failures = 6)
        // A new process over the same shelf reads the same window.
        val second = governance(store)
        val verdict = second.verdict("WIFI", threshold)
        assertEquals(8, verdict.attempts)
        assertEquals(6, verdict.failures)
        assertTrue(verdict.disabled)
    }

    @Test
    fun corruptShelf_vouchesForNothing() {
        val store = MemoryStore()
        store.text = "bukan;format;ini\n123;X;WIFI\n;;\n"
        val g = governance(store)
        assertEquals(0, g.size())
        assertFalse(g.verdict("WIFI", threshold).disabled)
    }

    @Test
    fun logIsBoundedPerClass() {
        val g = governance()
        record(g, successes = SabrGovernance.MAX_PER_CLASS + 50, failures = 0)
        assertEquals(SabrGovernance.MAX_PER_CLASS, g.size())
        record(g, successes = 10, failures = 0, networkClass = "SELULER")
        assertEquals(SabrGovernance.MAX_PER_CLASS + 10, g.size())
    }
}
