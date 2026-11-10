package com.bunny.engine.arus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class ArusStreamStoreTest {

    private val videoFormat = ArusFormat(137, "video/mp4", 4_500_000, 111)
    private val audioFormat = ArusFormat(140, "audio/mp4", 128_000, 222)
    private val session = ArusSession(
        videoId = "test",
        sabrUrl = "https://example.test/sabr",
        ustreamerConfig = byteArrayOf(1),
        formats = listOf(videoFormat, audioFormat),
        approxDurationMs = 2_000,
    )

    /** A conversation that serves two chunks, one per format, then ends. */
    private class TwoChunkConversation : ArusConversation {
        override fun stream(
            session: ArusSession,
            video: ArusFormat,
            audio: ArusFormat,
            sink: ArusMediaSink,
        ): ArusResult {
            sink.onChunk(0, video.formatId, "VIDEODATA".toByteArray())
            sink.onChunk(1, audio.formatId, "AUDIODATA".toByteArray())
            return ArusResult(mapOf(137 to 9L, 140 to 9L), 1, 1, true, listOf("test"))
        }
    }

    private class WalledConversation : ArusConversation {
        override fun stream(
            session: ArusSession,
            video: ArusFormat,
            audio: ArusFormat,
            sink: ArusMediaSink,
        ): ArusResult = throw ArusException.ProtectionRequired(3)
    }

    /** Reads a whole pipe through a deliberately tiny buffer (chunk-splitting). */
    private fun readAll(store: ArusStreamStore, format: ArusFormat): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(2)
        while (true) {
            val n = store.read(format, buffer, 0, buffer.size)
            if (n == -1) return out.toByteArray()
            out.write(buffer, 0, n)
        }
    }

    @Test
    fun store_servesInitThenMediaPerFormat_thenEnds() {
        val store = ArusStreamStore(
            session, videoFormat, audioFormat,
            conversation = TwoChunkConversation(),
            initFetcher = InitSegmentFetcher { byteArrayOf(0x49, 0x4E) }, // "IN"
        )
        store.start()
        assertArrayEquals("INVIDEODATA".toByteArray(), readAll(store, videoFormat))
        assertArrayEquals("INAUDIODATA".toByteArray(), readAll(store, audioFormat))
        assertEquals(1, store.streamResult()?.protectionStatus)
        assertEquals(0, store.droppedChunks)
        store.close()
    }

    @Test
    fun store_conversationFailure_reachesReadersAsIOException() {
        val store = ArusStreamStore(
            session, videoFormat, audioFormat,
            conversation = WalledConversation(),
            initFetcher = InitSegmentFetcher { null },
        )
        store.start()
        assertThrows(IOException::class.java) { readAll(store, videoFormat) }
        store.close()
    }

    @Test
    fun store_missingInit_stillServesMedia() {
        val store = ArusStreamStore(
            session, videoFormat, audioFormat,
            conversation = TwoChunkConversation(),
            initFetcher = InitSegmentFetcher { null },
        )
        store.start()
        assertArrayEquals("VIDEODATA".toByteArray(), readAll(store, videoFormat))
        store.close()
    }
}
