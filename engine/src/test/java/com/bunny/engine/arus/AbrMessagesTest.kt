package com.bunny.engine.arus

import com.bunny.engine.arus.ProtoReader.bytes
import com.bunny.engine.arus.ProtoReader.varint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AbrMessagesTest {

    @Test
    fun formatId_roundTrips() {
        val id = FormatId(137, 1_794_210_640_189_440L)
        val parsed = FormatId.parse(id.encode())
        assertEquals(id.itag, parsed.itag)
        assertEquals(id.lastModified, parsed.lastModified)
    }

    @Test
    fun abrRequest_hasTheProbedFieldLayout() {
        val audio = FormatId(140, 222)
        val video = FormatId(137, 111)
        val ustreamer = byteArrayOf(9, 8, 7)
        val request = AbrRequest.build(
            state = ClientAbrState(0, ClientAbrState.TRACKS_AUDIO_AND_VIDEO),
            initializationFormatIds = listOf(video, audio),
            bufferedRanges = listOf(BufferedRange(video, 0, 0), BufferedRange(audio, 0, 0)),
            ustreamerConfig = ustreamer,
            audioFormat = audio,
            videoFormat = video,
        )
        val fields = ProtoReader.parse(request)
        // 1: clientAbrState { 28: playerTimeMs, 40: track bitfield }
        val state = ProtoReader.parse(fields.bytes(1)!!)
        assertEquals(0L, state.varint(28))
        assertEquals(3L, state.varint(40))
        // 2: two initialization format ids, video first
        val inits = fields.filter { it.number == 2 }.map { FormatId.parse(it.asBytes()!!) }
        assertEquals(listOf(video, audio), inits)
        // 5: the ustreamer config, echoed verbatim
        assertArrayEquals(ustreamer, fields.bytes(5))
        // 16/17: selected audio / video
        assertEquals(audio, FormatId.parse(fields.bytes(16)!!))
        assertEquals(video, FormatId.parse(fields.bytes(17)!!))
        // No streamerContext (19) is sent: A0 ran token-free without it.
        assertEquals(null, fields.bytes(19))
    }

    @Test
    fun protectionStatus_parsesA0Payloads() {
        assertEquals(1, StreamProtectionStatus.parse(byteArrayOf(0x08, 0x01)).status)
        assertEquals(3, StreamProtectionStatus.parse(byteArrayOf(0x08, 0x03)).status)
    }

    @Test
    fun nextRequestPolicy_keepsPacingAndCookie() {
        val payload = ProtoWriter()
            .varintField(1, 21_000)
            .varintField(2, 21_000)
            .varintField(3, 60_000)
            .bytesField(7, byteArrayOf(1, 2, 3))
            .bytes()
        val policy = NextRequestPolicy.parse(payload)
        assertEquals(listOf(21_000L, 21_000L, 60_000L), policy.pacingMs)
        assertArrayEquals(byteArrayOf(1, 2, 3), policy.playbackCookie)
    }

    @Test
    fun mediaHeader_parsesA0Shape() {
        // The exact field shape the A0 dump showed for an audio header:
        // 1 headerId, 3 itag, 4 lastModified, 8 sequence, 10 durationMs,
        // 14 media size.
        val payload = ProtoWriter()
            .varintField(1, 0)
            .varintField(3, 140)
            .varintField(4, 1_794_210_559_644_665L)
            .varintField(8, 0)
            .varintField(10, 16_229)
            .varintField(14, 162_042)
            .bytes()
        val header = MediaHeader.parse(payload)
        assertEquals(0, header.headerId)
        assertEquals(140, header.itag)
        assertEquals(1_794_210_559_644_665L, header.lastModified)
        assertEquals(0, header.sequenceNumber)
        assertEquals(16_229L, header.durationMs)
        assertEquals(162_042L, header.mediaSizeBytes)
        assertEquals(FormatId(140, 1_794_210_559_644_665L), header.formatId)
    }

    @Test
    fun formatRoster_splitsVideoAndAudio() {
        val payload = ProtoWriter()
            .bytesField(1, FormatId(137, 111).encode())
            .bytesField(1, FormatId(136, 112).encode())
            .bytesField(2, FormatId(140, 222).encode())
            .bytesField(2, FormatId(251, 223).encode())
            .bytes()
        val roster = FormatRoster.parse(payload)
        assertEquals(listOf(137, 136), roster.video.map { it.itag })
        assertEquals(listOf(140, 251), roster.audio.map { it.itag })
    }
}
