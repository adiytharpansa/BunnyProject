package com.bunny.core.rank

import org.junit.Assert.assertEquals
import org.junit.Test

class EngagementTest {

    @Test
    fun completed_isFullWeight() {
        assertEquals(1.0, Engagement.weight(0L, 0L, true), 1e-9)
    }

    @Test
    fun quickBail_isWeaklyNegative() {
        assertEquals(-0.2, Engagement.weight(5_000L, 600_000L, false), 1e-9)
    }

    @Test
    fun watchedHalf_isStrongPositive() {
        assertEquals(0.7, Engagement.weight(300_000L, 600_000L, false), 1e-9)
    }

    @Test
    fun watchedQuarter_isModerate() {
        assertEquals(0.4, Engagement.weight(150_000L, 600_000L, false), 1e-9)
    }

    @Test
    fun unknownDuration_usesAbsoluteThresholds() {
        assertEquals(0.7, Engagement.weight(6 * 60_000L, 0L, false), 1e-9)
        assertEquals(0.4, Engagement.weight(70_000L, 0L, false), 1e-9)
        assertEquals(-0.2, Engagement.weight(3_000L, 0L, false), 1e-9)
    }

    @Test
    fun decay_halvesAtHalfLife() {
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        assertEquals(0.5, Engagement.decay(thirtyDaysMs, 30.0), 1e-9)
    }

    @Test
    fun decay_isOneForFreshOrFutureTimestamps() {
        assertEquals(1.0, Engagement.decay(0L, 30.0), 1e-9)
        assertEquals(1.0, Engagement.decay(-5_000L, 30.0), 1e-9)
    }
}
