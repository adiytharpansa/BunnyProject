package com.bunny.engine.arus

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArusSessionTest {

    private fun fixture(): JSONObject {
        val stream = ArusSessionTest::class.java.getResourceAsStream("/arus/visionos_player.json")
        return JSONObject(stream!!.bufferedReader().readText())
    }

    @Test
    fun parse_extractsConversationAndFormats() {
        val session = ArusSession.parsePlayerResponse(fixture(), "b2DfMST_WE8")!!
        assertEquals("b2DfMST_WE8", session.videoId)
        assertEquals(
            "https://rr1---sn-example.googlevideo.com/videoplayback?expire=1&ei=x",
            session.sabrUrl,
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), session.ustreamerConfig)
        assertEquals(120_000L, session.approxDurationMs)
        assertEquals(listOf(137), session.videoFormats().map { it.itag })
        assertEquals(listOf(140), session.audioFormats().map { it.itag })
        val video = session.videoFormats().single()
        assertEquals(1_794_210_640_189_440L, video.lastModified)
        assertEquals(0L, video.initRangeStart)
        assertEquals(741L, video.initRangeEnd)
        assertEquals("https://example.test/v137", video.directUrl)
    }

    @Test
    fun parse_withoutSabrUrl_returnsNull() {
        val json = JSONObject(
            """{"streamingData":{"adaptiveFormats":[]},"playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AQIDBA=="}}}}""",
        )
        assertNull(ArusSession.parsePlayerResponse(json, "x"))
    }
}
