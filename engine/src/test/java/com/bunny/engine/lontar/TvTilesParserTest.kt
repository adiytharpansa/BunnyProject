package com.bunny.engine.lontar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV tile parser against a fixture shaped like a real TV
 * browse reply: tvBrowseRenderer shelves of tileRenderer. The
 * fixture is built programmatically — the earlier hand-written
 * JSON string was malformed and failed CI #120 before any
 * assertion ran; structure in code cannot drift that way.
 */
class TvTilesParserTest {

    private fun tile(
        id: String,
        type: String,
        title: JSONObject,
        extra: JSONObject.() -> Unit = {},
    ): JSONObject = JSONObject()
        .put("tileRenderer", JSONObject()
            .put("contentId", id)
            .put("contentType", type)
            .put("headline", title)
            .put("onTap", JSONObject().put(
                "watchEndpoint",
                JSONObject().put("videoId", id),
            ))
            .apply(extra))

    private val fixture: JSONObject = run {
        val first = tile(
            "abc123XYZ09",
            "TILE_CONTENT_TYPE_VIDEO",
            JSONObject().put("simpleText", "Video Pertama"),
        ) {
            put("thumbnail", JSONObject().put("thumbnails", JSONArray()
                .put(JSONObject().put("url", "https://img/s.jpg").put("width", 320))
                .put(JSONObject().put("url", "https://img/l.jpg").put("width", 1280))))
            put("longBylineText", JSONObject().put("runs", JSONArray()
                .put(JSONObject().put("text", "Kanal A"))))
            put("metadataLine", JSONObject().put("runs", JSONArray()
                .put(JSONObject().put("text", "1,2 jt x ditonton"))
                .put(JSONObject().put("text", " • "))
                .put(JSONObject().put("text", "3 hari lalu"))))
            put("thumbnailOverlays", JSONArray().put(JSONObject().put(
                "thumbnailOverlayTimeStatusRenderer",
                JSONObject().put(
                    "text",
                    JSONObject().put("simpleText", "12:34"),
                ),
            )))
        }
        val second = tile(
            "def456XYZ09",
            "TILE_CONTENT_TYPE_VIDEO",
            JSONObject().put("runs", JSONArray()
                .put(JSONObject().put("text", "Video "))
                .put(JSONObject().put("text", "Kedua"))),
        ) {
            put("thumbnail", JSONObject().put("thumbnails", JSONArray()
                .put(JSONObject().put("url", "https://img/m.jpg").put("width", 640))))
        }
        val channel = tile(
            "UCchannel00",
            "TILE_CONTENT_TYPE_CHANNEL",
            JSONObject().put("simpleText", "Sebuah Kanal"),
        )
        JSONObject().put("contents", JSONObject().put(
            "tvBrowseRenderer",
            JSONObject().put("content", JSONObject().put(
                "tvSurfaceContentRenderer",
                JSONObject().put("content", JSONObject().put(
                    "sectionListRenderer",
                    JSONObject().put("contents", JSONArray().put(
                        JSONObject().put("shelfRenderer", JSONObject().put(
                            "content",
                            JSONObject().put(
                                "horizontalListRenderer",
                                JSONObject().put(
                                    "items",
                                    JSONArray().put(first).put(second).put(channel),
                                ),
                            ),
                        )),
                    )),
                )),
            )),
        ))
    }

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
        val one = tile(
            "abc123XYZ09",
            "TILE_CONTENT_TYPE_VIDEO",
            JSONObject().put("simpleText", "Satu"),
        )
        val root = JSONObject().put("a", one).put("b", one)
        assertEquals(1, InnertubeParser.parseTvTiles(root, "x").size)
    }
}
