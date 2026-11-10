package com.bunny.engine.lontar

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashManifestBuilderTest {

    private fun format(
        itag: Int,
        mime: String,
        url: String?,
        bitrate: Long = 1_000_000,
        extra: String = "",
    ): String {
        val urlPart = if (url != null) """"url":"$url",""" else """"signatureCipher":"s=abc&sp=sig","""
        return """{"itag":$itag,$urlPart"mimeType":"$mime","bitrate":$bitrate,
            "initRange":{"start":"0","end":"100"},"indexRange":{"start":"101","end":"200"}$extra}"""
    }

    @Test
    fun build_groupsByMimeAndWritesRepresentations() {
        val formats = JSONArray(
            "[" +
                format(137, "video/mp4; codecs=\"avc1.640028\"", "https://v.example/137?a=1&b=2", extra = ",\"width\":1920,\"height\":1080,\"fps\":30") +
                "," +
                format(248, "video/webm; codecs=\"vp9\"", "https://v.example/248", extra = ",\"width\":1920,\"height\":1080,\"fps\":30") +
                "," +
                format(140, "audio/mp4; codecs=\"mp4a.40.2\"", "https://v.example/140", extra = ",\"audioSampleRate\":\"44100\",\"audioChannels\":2") +
                "]",
        )
        val mpd = DashManifestBuilder.build(formats, 213)
        assertNotNull(mpd)
        mpd!!
        assertTrue(mpd.contains("mediaPresentationDuration=\"PT213S\""))
        assertEquals(3, Regex("<AdaptationSet").findAll(mpd).count())
        assertTrue(mpd.contains("<Representation id=\"137\" bandwidth=\"1000000\" codecs=\"avc1.640028\" width=\"1920\" height=\"1080\" frameRate=\"30\">"))
        assertTrue(mpd.contains("<SegmentBase indexRange=\"101-200\">"))
        assertTrue(mpd.contains("<Initialization range=\"0-100\" />"))
        // URLs are XML-escaped inside BaseURL.
        assertTrue(mpd.contains("<BaseURL>https://v.example/137?a=1&amp;b=2</BaseURL>"))
        assertTrue(mpd.contains("audioSamplingRate=\"44100\""))
        assertTrue(mpd.contains("<AudioChannelConfiguration"))
    }

    @Test
    fun build_skipsCipheredAndRangelessFormats() {
        val formats = JSONArray(
            "[" +
                format(137, "video/mp4; codecs=\"avc1.640028\"", url = null) +
                "," +
                """{"itag":136,"url":"https://v.example/136","mimeType":"video/mp4","bitrate":5}""" +
                "]",
        )
        assertNull(DashManifestBuilder.build(formats, 100))
    }

    @Test
    fun build_emptyInputs_returnNull() {
        assertNull(DashManifestBuilder.build(null, 100))
        assertNull(DashManifestBuilder.build(JSONArray(), 100))
    }

    @Test
    fun codecsOf_extractsQuotedCodecs() {
        assertEquals("av01.0.08M.08", DashManifestBuilder.codecsOf("video/mp4; codecs=\"av01.0.08M.08\""))
        assertEquals("", DashManifestBuilder.codecsOf("video/mp4"))
    }
}
