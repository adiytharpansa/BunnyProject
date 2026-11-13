package com.bunny.core.rank

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatisfactionPolicyTest {

    @Test
    fun shortWatch_isNotMeaningful() {
        assertFalse(SatisfactionPolicy.isMeaningfulWatch(30_000L, 600_000L, false))
    }

    @Test
    fun substantialWatch_isMeaningful() {
        assertTrue(SatisfactionPolicy.isMeaningfulWatch(120_000L, 300_000L, false))
        assertTrue(SatisfactionPolicy.isMeaningfulWatch(0L, 0L, true))
    }

    @Test
    fun unknownDuration_needsFiveMinutes() {
        assertTrue(SatisfactionPolicy.isMeaningfulWatch(10 * 60_000L, 0L, false))
        assertFalse(SatisfactionPolicy.isMeaningfulWatch(2 * 60_000L, 0L, false))
    }

    @Test
    fun cooldown_blocksRepeatQuestions() {
        assertFalse(
            SatisfactionPolicy.shouldAsk(true, SatisfactionPolicy.COOLDOWN_MS - 1, 0, 0.0),
        )
        assertTrue(
            SatisfactionPolicy.shouldAsk(true, SatisfactionPolicy.COOLDOWN_MS, 0, 0.0),
        )
    }

    @Test
    fun dailyCap_blocks() {
        assertFalse(
            SatisfactionPolicy.shouldAsk(
                true,
                SatisfactionPolicy.COOLDOWN_MS,
                SatisfactionPolicy.MAX_PER_DAY,
                0.0,
            ),
        )
    }

    @Test
    fun sampling_andMeaningfulness_arePrerequisites() {
        assertFalse(SatisfactionPolicy.shouldAsk(true, Long.MAX_VALUE, 0, 0.99))
        assertFalse(SatisfactionPolicy.shouldAsk(false, Long.MAX_VALUE, 0, 0.0))
    }
}
