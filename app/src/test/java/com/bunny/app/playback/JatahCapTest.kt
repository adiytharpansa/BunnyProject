package com.bunny.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mechanism 6: the Jatah ceiling ladder, step by step. */
class JatahCapTest {

    private val quota = 10_000_000_000L // 10 GB

    @Test
    fun noQuotaMeansNoCap() {
        assertNull(JatahCap.heightCapPx(null, 0))
        assertNull(JatahCap.heightCapPx(0, 0))
    }

    @Test
    fun moreThanHalfLeftMeansNoCap() {
        assertNull(JatahCap.heightCapPx(quota, 1_000_000_000L))
        assertNull(JatahCap.heightCapPx(quota, 4_999_999_999L))
    }

    @Test
    fun quarterToHalfLeftCapsAt720() {
        assertEquals(720, JatahCap.heightCapPx(quota, 5_000_000_000L))
        assertEquals(720, JatahCap.heightCapPx(quota, 7_499_999_999L))
    }

    @Test
    fun tenthToQuarterLeftCapsAt480() {
        assertEquals(480, JatahCap.heightCapPx(quota, 7_500_000_000L))
        assertEquals(480, JatahCap.heightCapPx(quota, 8_999_999_999L))
    }

    @Test
    fun underATenthOrOverBudgetCapsAt360() {
        assertEquals(360, JatahCap.heightCapPx(quota, 9_000_000_000L))
        assertEquals(360, JatahCap.heightCapPx(quota, 12_000_000_000L))
    }
}
