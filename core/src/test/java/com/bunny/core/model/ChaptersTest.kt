package com.bunny.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

    @Test
    fun `bab yang sah terbaca utuh`() {
        val description = """
            Video tentang perjalanan kami.

            0:00 Pembuka
            2:35 Berangkat
            10:20 Sampai di lokasi
            15:00 Penutup
        """.trimIndent()
        val chapters = Chapters.parse(description, durationMs = 0)
        assertEquals(4, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("Berangkat", chapters[1].title)
        assertEquals(620_000L, chapters[2].startMs)
    }

    @Test
    fun `tanpa cap nol bukan bab`() {
        val description = "1:00 Satu\n2:00 Dua\n3:00 Tiga"
        assertTrue(Chapters.parse(description, 0).isEmpty())
    }

    @Test
    fun `kurang dari tiga bukan bab`() {
        val description = "0:00 Satu\n2:00 Dua"
        assertTrue(Chapters.parse(description, 0).isEmpty())
    }

    @Test
    fun `tidak menaik bukan bab`() {
        val description = "0:00 Satu\n5:00 Dua\n3:00 Tiga"
        assertTrue(Chapters.parse(description, 0).isEmpty())
    }

    @Test
    fun `cap lewat durasi dibuang`() {
        val description = "0:00 Satu\n1:00 Dua\n2:00 Tiga\n9:00 Empat"
        val chapters = Chapters.parse(description, durationMs = 300_000)
        assertEquals(3, chapters.size)
    }

    @Test
    fun `format jam terbaca`() {
        val description = "0:00 Satu\n30:00 Dua\n1:02:03 Tiga"
        val chapters = Chapters.parse(description, 0)
        assertEquals(3_723_000L, chapters[2].startMs)
    }
}
