package com.bunny.engine.arus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class UmpReaderTest {

    private fun streamOf(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    @Test
    fun parseAll_readsCensusShapedStream() {
        // Mirrors the Fase A0 conversation shape: redirect, protection,
        // one media chunk (header-id prefix + bytes), media end.
        val redirectPayload = ProtoWriter()
            .stringField(1, "https://example.test/moved")
            .bytes()
        val protectionPayload = ProtoWriter().varintField(1, 1).bytes()
        val mediaPayload = UmpVarint.write(1) + "DATA".toByteArray()
        val endPayload = UmpVarint.write(1)
        val stream = streamOf(
            UmpReader.writePart(UmpParts.SABR_REDIRECT, redirectPayload),
            UmpReader.writePart(UmpParts.STREAM_PROTECTION_STATUS, protectionPayload),
            UmpReader.writePart(UmpParts.MEDIA, mediaPayload),
            UmpReader.writePart(UmpParts.MEDIA_END, endPayload),
        )
        val parts = UmpReader.parseAll(stream)
        assertEquals(
            listOf(
                UmpParts.SABR_REDIRECT,
                UmpParts.STREAM_PROTECTION_STATUS,
                UmpParts.MEDIA,
                UmpParts.MEDIA_END,
            ),
            parts.map { it.type },
        )
        assertEquals("https://example.test/moved", SabrRedirect.parseUrl(parts[0].payload))
        assertEquals(1, StreamProtectionStatus.parse(parts[1].payload).status)
        val (headerId, offset) = UmpVarint.read(parts[2].payload, 0)
        assertEquals(1L, headerId)
        assertEquals("DATA", parts[2].payload.copyOfRange(offset, parts[2].payload.size).toString(Charsets.UTF_8))
    }

    @Test
    fun parseAll_emptyStream_isEmpty() {
        assertTrue(UmpReader.parseAll(ByteArray(0)).isEmpty())
    }

    @Test
    fun parseAll_overshootingSize_throws() {
        // A part that declares more than the body holds is truncation or
        // desynchronisation — never something to clamp silently.
        val out = ByteArrayOutputStream()
        out.write(UmpVarint.write(UmpParts.MEDIA.toLong()))
        out.write(UmpVarint.write(10_000))
        out.write(byteArrayOf(1, 2, 3))
        assertThrows(UmpFormatException::class.java) { UmpReader.parseAll(out.toByteArray()) }
    }
}
