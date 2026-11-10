package com.bunny.engine.arus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UmpVarintTest {

    @Test
    fun singleByte_valuesBelow128() {
        assertArrayEquals(byteArrayOf(0x00), UmpVarint.write(0))
        assertArrayEquals(byteArrayOf(0x2B), UmpVarint.write(43))
        assertArrayEquals(byteArrayOf(0x7F), UmpVarint.write(127))
    }

    @Test
    fun twoByte_firstByteHoldsLowBits() {
        // The Fase A0 proof vector: a live SABR_REDIRECT declared its
        // size with the bytes 8C 0F, and exactly 972 payload bytes
        // followed. The UTF-8-style reading gives 3087 and desyncs.
        assertArrayEquals(byteArrayOf(0x8C.toByte(), 0x0F), UmpVarint.write(972))
        val (value, next) = UmpVarint.read(byteArrayOf(0x8C.toByte(), 0x0F), 0)
        assertEquals(972L, value)
        assertEquals(2, next)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x02), UmpVarint.write(128))
        assertArrayEquals(byteArrayOf(0xBF.toByte(), 0xFF.toByte()), UmpVarint.write(16_383))
    }

    @Test
    fun widerWidths_roundTrip() {
        for (value in listOf(16_384L, 2_000_000L, 268_435_455L, 268_435_456L, 5_500_000_000L)) {
            val encoded = UmpVarint.write(value)
            val (decoded, next) = UmpVarint.read(encoded, 0)
            assertEquals(value, decoded)
            assertEquals(encoded.size, next)
        }
        // 2^28 is the smallest five-byte value: prefix F0, value bits above.
        assertArrayEquals(
            byteArrayOf(0xF0.toByte(), 0x00, 0x00, 0x00, 0x02),
            UmpVarint.write(268_435_456),
        )
    }

    @Test
    fun read_truncated_throws() {
        assertThrows(UmpFormatException::class.java) {
            UmpVarint.read(byteArrayOf(0x8C.toByte()), 0)
        }
        assertThrows(UmpFormatException::class.java) {
            UmpVarint.read(ByteArray(0), 0)
        }
    }
}
