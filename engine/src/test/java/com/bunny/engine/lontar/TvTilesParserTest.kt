package com.bunny.engine.lontar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV tile parser against a fixture shaped like a real TV browse
 * reply: tvBrowseRenderer shelves of tileRenderer (Jalan B's
 * evidence base — the live shape is confirmed on device).
 */
class TvTilesParserTest {

    private val fixture = JSONObject(
        """
        {"contents": {"tvBrowseRenderer": {"content": {
          "tvSurfaceContentRenderer": {"content": {
            "sectionListRenderer": {"contents": [
              {"shelfRenderer": {"content": {"horizontalListRenderer": {"items": [
                {"tileRenderer": {
                  "contentId": "abc123XYZ09",
                  "contentType": "TILE_CONTENT_TYPE_VIDEO",
                  "headline": {"simpleText": "Video Pertama"},
                  "thumbnail": {"thumbnails": [
                    {"url": "https://img/s.jpg", "width": 320},
                    {"url": "https://img/l.jpg", "width": 1280}
                  ]},
                  "longBylineText": {"runs": [{"text": "Kanal A"}]},
                  "metadataLine": {"runs": [
                    {"text": "1,2 jt x ditonton"},
                    {"text": " • "},
                    {"text": "3 hari lalu"}
                  ]},
                  "thumbnailOverlays": [
                    {"thumbnailOverlayTimeStatusRenderer": {
                      "text": {"simpleText": "12:34"}
                    }}
                  ],
                  "onTap": {"watchEndpoint": {"videoId": "abc123XYZ09"}}
                }},
                {"tileRenderer": {
                  "contentId": "def456XYZ09",
                  "contentType": "TILE_CONTENT_TYPE_VIDEO",
                  "headline": {"runs": [{"text": "Video "}, {"text": "Kedua"}]},
                  "thumbnail": {"thumbnails": [
                    {"url": "https://img/m.jpg", "width": 640}
                  ]},
                  "onTap": {"watchEndpoint": {"videoId": "def456XYZ09"}}
                }},
                {"tileRenderer": {
                  "contentId": "UCchannel00",
                  "contentType": "TILE_CONTENT_TYPE_CHANNEL",
                  "headline": {"simpleText": "Sebuah Kanal"},
                  "onTap": {"watchEndpoint": {"videoId": ""}}
                }}
              ]}}}
            ]}
          }}
        }}}}
        """.trimIndent(),
    )

    @Test
    fun tilesBecomeVideosWithRealFields() {
        val videos = InnertubeParser.parseTvTiles(fixture, "Beranda pribadi Anda")
        assertEquals(2, videos.size)
        val first = videos[0]
        assertEquals("abc123XYZ09", first.id)
        assertEquals("Video Pertama", first.title)
        assertEquals("Kanal A", first.channelName)
        assertEquals("https://img/l.jpg", first.thumbnailUrl)
        assertEquals(754L, first.durationSeconds)
        assertEquals(1_200_000L, first.viewCount)
        assertEquals("3 hari lalu", first.uploadedText)
        assertEquals("Beranda pribadi Anda", first.reason)
        assertEquals("Video Kedua", videos[1].title)
        assertNull(videos[1].viewCount)
    }

    @Test
    fun emptyBrowseYieldsEmptyList() {
        val videos = InnertubeParser.parseTvTiles(JSONObject("{}"), "x")
        assertTrue(videos.isEmpty())
    }

    @Test
    fun duplicateTilesCollapse() {
        val root = JSONObject(
            """
            {"a": {"tileRenderer": {
              "contentId": "abc123XYZ09",
              "contentType": "TILE_CONTENT_TYPE_VIDEO",
              "headline": {"simpleText": "Satu"},
              "onTap": {"watchEndpoint": {"videoId": "abc123XYZ09"}}
            }},
             "b": {"tileRenderer": {
              "contentId": "abc123XYZ09",
              "contentType": "TILE_CONTENT_TYPE_VIDEO",
              "headline": {"simpleText": "Satu"},
              "onTap": {"watchEndpoint": {"videoId": "abc123XYZ09"}}
            }}}
            """.trimIndent(),
        )
        assertEquals(1, InnertubeParser.parseTvTiles(root, "x").size)
    }
}
