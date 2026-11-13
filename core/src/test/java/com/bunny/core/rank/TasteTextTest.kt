package com.bunny.core.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTextTest {

    @Test
    fun words_dropsStopwordsNumbersAndShortTokens() {
        val words = TasteText.words("Resep Rendang Sapi 2026 - Yang Enak di Rumah")
        assertTrue("rendang" in words)
        assertTrue("resep" in words)
        assertTrue("enak" in words)
        assertFalse("yang" in words)
        assertFalse("2026" in words)
        assertFalse("di" in words)
    }

    @Test
    fun words_isCaseInsensitiveAndUnique() {
        assertEquals(setOf("rendang"), TasteText.words("RENDANG rendang Rendang"))
    }
}
