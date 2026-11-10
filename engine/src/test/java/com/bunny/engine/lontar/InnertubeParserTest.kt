package com.bunny.engine.lontar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnertubeParserTest {

    private val feedJson = """
        {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{
          "content":{"richGridRenderer":{"contents":[
            {"richItemRenderer":{"content":{"videoRenderer":{
              "videoId":"aqz-KE-bpKQ",
              "title":{"runs":[{"text":"Big Buck Bunny 4K"}]},
              "longBylineText":{"runs":[{"text":"Blender Foundation",
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCtC9L7g"}}}]},
              "thumbnail":{"thumbnails":[
                {"url":"https://i.ytimg.com/small.jpg","width":120},
                {"url":"https://i.ytimg.com/big.jpg","width":480}]},
              "lengthText":{"simpleText":"10:35"},
              "viewCountText":{"simpleText":"28,123,456 views"},
              "publishedTimeText":{"simpleText":"9 years ago"}
            }}},
            {"richItemRenderer":{"content":{"videoRenderer":{
              "videoId":"live42",
              "title":{"simpleText":"Siaran Langsung Pagi"},
              "shortBylineText":{"runs":[{"text":"Bunny TV"}]},
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/live.jpg"}]},
              "viewCountText":{"runs":[{"text":"1.3K watching"}]},
              "badges":[{"metadataBadgeRenderer":{"label":"LIVE",
                "icon":{"iconType":"LIVE"}}}]
            }}},
            {"continuationItemRenderer":{
              "continuationEndpoint":{"continuationCommand":{"token":"TOK_NEXT"}}}}
          ]}}}}
        ]}}}}
    """.trimIndent()

    @Test
    fun parseFeedPage_collectsVideosWhereverNested() {
        val page = InnertubeParser.parseFeedPage(JSONObject(feedJson))
        assertEquals(2, page.items.size)
        assertEquals("TOK_NEXT", page.nextPage)

        val first = page.items[0]
        assertEquals("aqz-KE-bpKQ", first.id)
        assertEquals("Big Buck Bunny 4K", first.title)
        assertEquals("Blender Foundation", first.channelName)
        assertEquals("UCtC9L7g", first.channelId)
        assertEquals("https://i.ytimg.com/big.jpg", first.thumbnailUrl)
        assertEquals(635L, first.durationSeconds)
        assertEquals(28_123_456L, first.viewCount)
        assertEquals("9 years ago", first.uploadedText)
        assertTrue(!first.isLive)
    }

    @Test
    fun parseFeedPage_detectsLiveWithoutDuration() {
        val page = InnertubeParser.parseFeedPage(JSONObject(feedJson))
        val live = page.items[1]
        assertTrue(live.isLive)
        assertNull(live.durationSeconds)
        assertEquals(1_300L, live.viewCount)
        assertEquals("Bunny TV", live.channelName)
    }

    @Test
    fun parseCount_handlesSuffixesAndLocales() {
        assertEquals(1_200_000L, InnertubeParser.parseCount("1.2M views"))
        assertEquals(321_000L, InnertubeParser.parseCount("321K watching"))
        assertEquals(950L, InnertubeParser.parseCount("950 views"))
        assertEquals(0L, InnertubeParser.parseCount("No views"))
        assertNull(InnertubeParser.parseCount(""))
    }

    @Test
    fun parseDuration_handlesHms() {
        assertEquals(754L, InnertubeParser.parseDuration("12:34"))
        assertEquals(3_723L, InnertubeParser.parseDuration("1:02:03"))
        assertNull(InnertubeParser.parseDuration("abc"))
    }

    private val playerJson = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"aqz-KE-bpKQ","title":"Big Buck Bunny",
           "shortDescription":"Kelinci besar.","author":"Blender Foundation",
           "channelId":"UCtC9L7g","viewCount":"28123456","lengthSeconds":"635"},
         "microformat":{"playerMicroformatRenderer":{"publishDate":"2015-01-01"}},
         "streamingData":{"expiresInSeconds":"21540",
           "formats":[
             {"itag":18,"url":"https://rr.example/low","bitrate":500000},
             {"itag":22,"url":"https://rr.example/high","bitrate":2000000}],
           "adaptiveFormats":[
             {"itag":137,"signatureCipher":"s=abc&sp=sig&url=https://rr.example/v"}],
           "hlsManifestUrl":"https://manifest.example/hls.m3u8"}}
    """.trimIndent()

    @Test
    fun parseVideoDetails_mapsPlayerFields() {
        val details = InnertubeParser.parseVideoDetails(JSONObject(playerJson))
        assertEquals("aqz-KE-bpKQ", details.id)
        assertEquals("Blender Foundation", details.channelName)
        assertEquals(28_123_456L, details.viewCount)
        assertEquals("2015-01-01", details.uploadedText)
    }

    @Test
    fun parsePlaybackStreams_picksBestDirectProgressive_andHls() {
        val streams = InnertubeParser.parsePlaybackStreams(JSONObject(playerJson), nowEpochMs = 1_000)
        assertEquals("https://rr.example/high", streams.progressiveUrl)
        assertEquals("https://manifest.example/hls.m3u8", streams.hlsUrl)
        assertEquals(1_000 + 21_540_000, streams.expiresAtEpochMs)
    }

    @Test
    fun parsePlaybackStreams_unplayable_throws() {
        val blocked = JSONObject(
            """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}""",
        )
        try {
            InnertubeParser.parsePlaybackStreams(blocked, nowEpochMs = 0)
            throw AssertionError("expected LontarException")
        } catch (e: LontarException) {
            assertTrue(e.message!!.contains("Sign in"))
        }
    }

    @Test
    fun session_fromHomepageHtml_extractsMarkers() {
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"AIzaTEST",
            "INNERTUBE_CONTEXT":{"client":{"visitorData":"CgtTEST123"}}});</script>"""
        val session = InnertubeSession.fromHomepageHtml(html)
        assertEquals("AIzaTEST", session?.apiKey)
        assertEquals("CgtTEST123", session?.visitorData)
        assertNull(InnertubeSession.fromHomepageHtml("<html>nothing</html>"))
    }
}
