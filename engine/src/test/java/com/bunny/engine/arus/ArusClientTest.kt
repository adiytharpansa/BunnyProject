package com.bunny.engine.arus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ArusClientTest {

    private val videoFormat = ArusFormat(137, "video/mp4", 4_500_000, 111)
    private val audioFormat = ArusFormat(140, "audio/mp4", 128_000, 222)
    private val session = ArusSession(
        videoId = "test",
        sabrUrl = "https://example.test/sabr?a=b",
        ustreamerConfig = byteArrayOf(9, 8, 7),
        formats = listOf(videoFormat, audioFormat),
        approxDurationMs = 2_000,
    )

    private class FakeTransport(vararg val responses: ByteArray) : ArusTransport {
        val urls = mutableListOf<String>()
        var index = 0
        override fun post(url: String, body: ByteArray): ByteArray {
            urls += url
            return responses[minOf(index++, responses.size - 1)]
        }
    }

    private class CollectingSink : ArusMediaSink {
        val byItag = mutableMapOf<Int, ByteArrayOutputStream>()
        override fun onChunk(headerId: Int, format: FormatId?, data: ByteArray) {
            byItag.getOrPut(format?.itag ?: -1) { ByteArrayOutputStream() }.write(data)
        }
    }

    private fun ump(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun headerPart(headerId: Long, itag: Long, lmt: Long, durationMs: Long): ByteArray {
        val payload = ProtoWriter()
            .varintField(1, headerId)
            .varintField(3, itag)
            .varintField(4, lmt)
            .varintField(8, 0)
            .varintField(10, durationMs)
            .bytes()
        return UmpReader.writePart(UmpParts.MEDIA_HEADER, payload)
    }

    private fun mediaPart(headerId: Long, data: String): ByteArray =
        UmpReader.writePart(UmpParts.MEDIA, UmpVarint.write(headerId) + data.toByteArray())

    private fun endPart(headerId: Long): ByteArray =
        UmpReader.writePart(UmpParts.MEDIA_END, UmpVarint.write(headerId))

    @Test
    fun stream_followsRedirectThenDemultiplexesToCompletion() {
        val redirect = UmpReader.writePart(
            UmpParts.SABR_REDIRECT,
            ProtoWriter().stringField(1, "https://example.test/sabr2?x=1").bytes(),
        )
        val mediaResponse = ump(
            UmpReader.writePart(
                UmpParts.STREAM_PROTECTION_STATUS,
                ProtoWriter().varintField(1, 1).bytes(),
            ),
            headerPart(0, 137, 111, 2_000),
            mediaPart(0, "VIDEODATA"),
            headerPart(1, 140, 222, 2_000),
            mediaPart(1, "AUDIODATA"),
            endPart(0),
            endPart(1),
        )
        val transport = FakeTransport(redirect, mediaResponse)
        val sink = CollectingSink()
        val result = ArusClient(transport, sleeper = {}).stream(session, videoFormat, audioFormat, sink)

        assertTrue(result.completedNormally)
        assertEquals(2, result.requests)
        assertEquals(1, result.protectionStatus)
        assertEquals(mapOf(137 to 9L, 140 to 9L), result.bytesByItag)
        assertEquals("VIDEODATA", sink.byItag[137]!!.toString(Charsets.UTF_8.name()))
        assertEquals("AUDIODATA", sink.byItag[140]!!.toString(Charsets.UTF_8.name()))
        // rn counts every POST; the second went to the redirect target.
        assertEquals("https://example.test/sabr?a=b&rn=0", transport.urls[0])
        assertEquals("https://example.test/sabr2?x=1&rn=1", transport.urls[1])
        assertTrue(result.events.contains("redirect"))
        assertTrue(result.events.contains("protection:1"))
    }

    @Test
    fun stream_protectionRequired_throws() {
        val walled = UmpReader.writePart(
            UmpParts.STREAM_PROTECTION_STATUS,
            ProtoWriter().varintField(1, 3).bytes(),
        )
        val client = ArusClient(FakeTransport(walled), sleeper = {})
        assertThrows(ArusException.ProtectionRequired::class.java) {
            client.stream(session, videoFormat, audioFormat, CollectingSink())
        }
    }

    @Test
    fun stream_sabrError_throws() {
        val error = UmpReader.writePart(UmpParts.SABR_ERROR, "boom".toByteArray())
        val client = ArusClient(FakeTransport(error), sleeper = {})
        assertThrows(ArusException.SabrError::class.java) {
            client.stream(session, videoFormat, audioFormat, CollectingSink())
        }
    }

    @Test
    fun stream_emptyResponses_endPartialNotLooping() {
        // Policy-only responses forever: the client must stop, honestly
        // partial, rather than ask the server in a hot loop.
        val policyOnly = UmpReader.writePart(
            UmpParts.NEXT_REQUEST_POLICY,
            ProtoWriter().varintField(1, 21_000).bytes(),
        )
        val transport = FakeTransport(policyOnly)
        val result = ArusClient(transport, sleeper = {}).stream(
            session, videoFormat, audioFormat, CollectingSink(),
        )
        assertEquals(false, result.completedNormally)
        assertEquals(ArusClient.MAX_EMPTY_RESPONSES, result.requests)
        assertTrue(result.bytesByItag.isEmpty())
    }
}
