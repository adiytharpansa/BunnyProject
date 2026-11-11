package com.bunny.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Data API parsers against fixtures shaped like the real
 * responses (channels.list mine, subscriptions.list,
 * playlistItems.list, videos.list) — Jalan A's evidence base.
 */
class DataApiParserTest {

    @Test
    fun mineReadsIdentityAndWidestThumbnail() {
        val root = JSONObject(
            """
            {"items": [{
              "id": "UCabc",
              "snippet": {
                "title": "Adiyth Arpansa",
                "customUrl": "@AdiythArpansa",
                "thumbnails": {
                  "default": {"url": "https://img/d.jpg"},
                  "high": {"url": "https://img/h.jpg"}
                }
              },
              "statistics": {"subscriberCount": "128"}
            }]}
            """.trimIndent(),
        )
        val mine = parseMine(root)
        assertEquals("UCabc", mine?.id)
        assertEquals("Adiyth Arpansa", mine?.title)
        assertEquals("@AdiythArpansa", mine?.handle)
        assertEquals("https://img/h.jpg", mine?.avatarUrl)
        assertEquals(128L, mine?.subscriberCount)
    }

    @Test
    fun mineWithoutChannelIsNull() {
        assertNull(parseMine(JSONObject("""{"items": []}""")))
    }

    @Test
    fun subscriptionsReadResourceIds() {
        val root = JSONObject(
            """
            {"items": [
              {"snippet": {
                "title": "Kanal A",
                "resourceId": {"channelId": "UCa"},
                "thumbnails": {"default": {"url": "https://img/a.jpg"}}
              }},
              {"snippet": {
                "title": "Kanal B",
                "resourceId": {"channelId": "UCb"},
                "thumbnails": {}
              }}
            ], "nextPageToken": "tok"}
            """.trimIndent(),
        )
        val subs = parseSubscriptions(root)
        assertEquals(2, subs.size)
        assertEquals("UCa", subs[0].channelId)
        assertEquals("https://img/a.jpg", subs[0].avatarUrl)
        assertNull(subs[1].avatarUrl)
    }

    @Test
    fun uploadsPlaylistsMapChannels() {
        val root = JSONObject(
            """
            {"items": [
              {"id": "UCa", "contentDetails": {"relatedPlaylists": {"uploads": "UUa"}}},
              {"id": "UCb", "contentDetails": {"relatedPlaylists": {}}}
            ]}
            """.trimIndent(),
        )
        assertEquals(mapOf("UCa" to "UUa"), parseUploadsPlaylists(root))
    }

    @Test
    fun playlistItemsSkipDeletedAndReadOwner() {
        val root = JSONObject(
            """
            {"items": [
              {"snippet": {
                 "title": "Video Bagus",
                 "publishedAt": "2026-11-10T10:00:00Z",
                 "channelId": "UCowner",
                 "channelTitle": "Pemilik",
                 "videoOwnerChannelId": "UCa",
                 "videoOwnerChannelTitle": "Kanal A",
                 "thumbnails": {"high": {"url": "https://img/v.jpg"}}
               },
               "contentDetails": {"videoId": "v1"}},
              {"snippet": {"title": "Deleted video"},
               "contentDetails": {"videoId": "vX"}}
            ]}
            """.trimIndent(),
        )
        val videos = parsePlaylistItems(root)
        assertEquals(1, videos.size)
        assertEquals("v1", videos[0].id)
        assertEquals("UCa", videos[0].channelId)
        assertEquals("Kanal A", videos[0].channelName)
        assertEquals("https://img/v.jpg", videos[0].thumbnailUrl)
    }

    @Test
    fun videoDetailsParseDurationAndViews() {
        val root = JSONObject(
            """
            {"items": [{
              "id": "v1",
              "contentDetails": {"duration": "PT1H2M3S"},
              "statistics": {"viewCount": "12345"}
            }]}
            """.trimIndent(),
        )
        val details = parseVideoDetails(root)
        assertEquals(3723L, details["v1"]?.first)
        assertEquals(12345L, details["v1"]?.second)
    }

    @Test
    fun likedParsesFullRows() {
        val root = JSONObject(
            """
            {"items": [{
              "id": "v9",
              "snippet": {
                "title": "Disukai",
                "channelId": "UCa",
                "channelTitle": "Kanal A",
                "publishedAt": "2026-11-01T00:00:00Z",
                "thumbnails": {"medium": {"url": "https://img/m.jpg"}}
              },
              "contentDetails": {"duration": "PT45S"},
              "statistics": {"viewCount": "7"}
            }]}
            """.trimIndent(),
        )
        val liked = parseLiked(root)
        assertEquals(1, liked.size)
        assertEquals(45L, liked[0].durationSeconds)
        assertEquals(7L, liked[0].viewCount)
        assertEquals("https://img/m.jpg", liked[0].thumbnailUrl)
    }

    @Test
    fun isoDurations() {
        assertEquals(45L, parseIsoDuration("PT45S"))
        assertEquals(600L, parseIsoDuration("PT10M"))
        assertEquals(3723L, parseIsoDuration("PT1H2M3S"))
        assertEquals(90000L, parseIsoDuration("P1DT1H"))
        assertNull(parseIsoDuration("nonsense"))
    }

    @Test
    fun relativeTimesAreIndonesian() {
        val now = 1_700_000_000_000L
        assertEquals("baru saja", formatRelativeId(now - 10_000, now))
        assertEquals("5 menit lalu", formatRelativeId(now - 300_000, now))
        assertEquals("3 jam lalu", formatRelativeId(now - 10_800_000, now))
        assertEquals(
            "2 hari lalu",
            formatRelativeId(now - 172_800_000, now),
        )
        assertEquals(
            "3 minggu lalu",
            formatRelativeId(now - 1_814_400_000, now),
        )
        assertEquals(
            "2 bulan lalu",
            formatRelativeId(now - 5_184_000_000, now),
        )
        assertEquals(
            "1 tahun lalu",
            formatRelativeId(now - 34_560_000_000, now),
        )
    }
}
