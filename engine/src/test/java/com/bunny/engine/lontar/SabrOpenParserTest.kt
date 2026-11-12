package com.bunny.engine.lontar

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fase 8 Langkah 3: the SABR offer is lifted out of a player
 * response — or honestly refused. Fixtures are synthetic but shaped
 * exactly like the real VISIONOS answers the probe recorded.
 */
class SabrOpenParserTest {

    private val ustreamerBytes = byteArrayOf(1, 2, 3, 4, 5)

    private fun format(itag: Int, mime: String, bitrate: Int): JSONObject =
        JSONObject()
            .put("itag", itag)
            .put("mimeType", mime)
            .put("bitrate", bitrate)
            .put("lastModified", "1794318155658714")
            .put("xtags", "xt$itag")

    private fun playerJson(
        status: String = "OK",
        live: Boolean = false,
        sabrUrl: String? = "https://gv.example/sabr",
        ustreamer: Boolean = true,
    ): JSONObject {
        val streaming = JSONObject()
            .put(
                "adaptiveFormats",
                JSONArray()
                    .put(format(251, "audio/webm; codecs=\"opus\"", 160_000))
                    .put(format(140, "audio/mp4; codecs=\"mp4a.40.2\"", 128_000))
                    .put(format(136, "video/mp4; codecs=\"avc1.640028\"", 2_500_000))
                    .put(format(398, "video/mp4; codecs=\"av01.0.08M.08\"", 4_000_000)),
            )
        if (sabrUrl != null) streaming.put("serverAbrStreamingUrl", sabrUrl)
        val root = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", status))
            .put(
                "videoDetails",
                JSONObject()
                    .put("videoId", "abc123")
                    .put("lengthSeconds", "398")
                    .put("isLive", live),
            )
            .put("streamingData", streaming)
            .put(
                "playbackTracking",
                JSONObject()
                    .put(
                        "videostatsPlaybackUrl",
                        JSONObject().put("baseUrl", "https://s.example/playback"),
                    )
                    .put(
                        "videostatsWatchtimeUrl",
                        JSONObject().put("baseUrl", "https://s.example/watchtime"),
                    ),
            )
        if (ustreamer) {
            root.put(
                "playerConfig",
                JSONObject().put(
                    "mediaCommonConfig",
                    JSONObject().put(
                        "mediaUstreamerRequestConfig",
                        JSONObject().put(
                            "videoPlaybackUstreamerConfig",
                            Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(ustreamerBytes),
                        ),
                    ),
                ),
            )
        }
        return root
    }

    @Test
    fun fullOfferParsesWithRichestPins() {
        val open = InnertubeParser.parseSabrOpen(playerJson(), PlayerPersonas.VISIONOS)
        assertNotNull(open)
        open!!
        assertEquals("abc123", open.videoId)
        assertEquals("https://gv.example/sabr", open.sabrUrl)
        assertArrayEquals(ustreamerBytes, open.ustreamerConfig)
        assertEquals(4, open.formats.size)
        assertEquals(251, open.audioPin.itag)
        assertEquals(398, open.videoPin.itag)
        assertEquals(398, open.durationSeconds)
        assertEquals(101, open.clientInfo.clientName)
        assertEquals("1.04", open.clientInfo.clientVersion)
        assertEquals("visionOS", open.clientInfo.osName)
        assertEquals("https://s.example/playback", open.tracking?.playbackUrl)
        assertEquals("https://s.example/watchtime", open.tracking?.watchtimeUrl)
    }

    @Test
    fun unplayableResponseOffersNothing() {
        assertNull(
            InnertubeParser.parseSabrOpen(
                playerJson(status = "LOGIN_REQUIRED"), PlayerPersonas.VISIONOS
            )
        )
    }

    @Test
    fun liveOffersNothing() {
        assertNull(
            InnertubeParser.parseSabrOpen(playerJson(live = true), PlayerPersonas.VISIONOS)
        )
    }

    @Test
    fun missingStreamingUrlOffersNothing() {
        assertNull(
            InnertubeParser.parseSabrOpen(
                playerJson(sabrUrl = null), PlayerPersonas.VISIONOS
            )
        )
    }

    @Test
    fun missingUstreamerOffersNothing() {
        assertNull(
            InnertubeParser.parseSabrOpen(
                playerJson(ustreamer = false), PlayerPersonas.VISIONOS
            )
        )
    }

    @Test
    fun noAv1HardwareMovesThePinToAvc() {
        // Mechanism 15: 398 (AV1) is the richest format, but a
        // device without an AV1 hardware decoder opens on 136
        // (AVC) instead — software decode is a battery tax.
        val open = InnertubeParser.parseSabrOpen(
            playerJson(),
            PlayerPersonas.VISIONOS,
            ceiling = com.bunny.engine.lontar.sabr.DeviceCodecCeiling(
                av1Hardware = false, vp9Hardware = true,
            ),
        )
        assertNotNull(open)
        assertEquals(136, open!!.videoPin.itag)
    }

    @Test
    fun jatahCapChoosesThePinUnderTheCeiling() {
        // Mechanism 6: with heights on the formats (136 = 720p,
        // 398 = 1080p), a 720 cap opens on 136 and the open data
        // remembers the cap it was asked under.
        val json = playerJson()
        val formats = json.getJSONObject("streamingData")
            .getJSONArray("adaptiveFormats")
        formats.getJSONObject(2).put("height", 720)
        formats.getJSONObject(3).put("height", 1080)
        val open = InnertubeParser.parseSabrOpen(
            json, PlayerPersonas.VISIONOS, capHeightPx = 720,
        )
        assertNotNull(open)
        assertEquals(136, open!!.videoPin.itag)
        assertEquals(720, open.capHeightPx)
        assertEquals(720, open.formats.first { it.itag == 136 }.height)
        assertEquals(1080, open.formats.first { it.itag == 398 }.height)
    }
}
