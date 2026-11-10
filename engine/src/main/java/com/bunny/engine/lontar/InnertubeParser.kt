package com.bunny.engine.lontar

import com.bunny.core.model.Page
import com.bunny.core.model.PlaybackStreams
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns raw InnerTube JSON into Bunny domain models.
 *
 * The browse/search responses are deep renderer trees whose exact nesting
 * shifts between client versions, so video collection is a recursive walk:
 * any object holding a "videoRenderer" is a video, wherever it sits.
 * Player responses are flat by comparison and parsed directly.
 *
 * Pure functions over org.json — unit-tested with hand-written fixtures.
 */
object InnertubeParser {

    // ------------------------------------------------------------------ feeds

    fun parseFeedPage(response: JSONObject): Page<VideoItem> {
        val items = mutableListOf<VideoItem>()
        collectVideos(response, items)
        return Page(
            items = items.distinctBy { it.id },
            nextPage = findContinuation(response),
        )
    }

    private fun collectVideos(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                if (node.has("videoRenderer")) {
                    parseVideoRenderer(node.getJSONObject("videoRenderer"))?.let(out::add)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectVideos(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideos(node.opt(i), out)
                }
            }
        }
    }

    private fun parseVideoRenderer(renderer: JSONObject): VideoItem? {
        val videoId = renderer.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val viewCountText = textOf(renderer.optJSONObject("viewCountText"))
        val isLive = isLiveRenderer(renderer, viewCountText)
        return VideoItem(
            id = videoId,
            title = textOf(renderer.optJSONObject("title")).ifEmpty {
                renderer.optJSONObject("headline")?.let { textOf(it) }.orEmpty()
            },
            channelId = channelBrowseId(renderer),
            channelName = textOf(renderer.optJSONObject("longBylineText")).ifEmpty {
                textOf(renderer.optJSONObject("shortBylineText"))
            },
            thumbnailUrl = bestThumbnail(renderer.optJSONObject("thumbnail")),
            durationSeconds = renderer.optJSONObject("lengthText")
                ?.let { parseDuration(textOf(it)) },
            viewCount = viewCountText?.let { parseCount(it) },
            uploadedText = renderer.optJSONObject("publishedTimeText")
                ?.let { textOf(it) }
                ?.takeIf { it.isNotEmpty() },
            isLive = isLive,
        )
    }

    private fun isLiveRenderer(renderer: JSONObject, viewCountText: String?): Boolean {
        val badges = renderer.optJSONArray("badges") ?: JSONArray()
        for (i in 0 until badges.length()) {
            val badge = badges.optJSONObject(i)?.optJSONObject("metadataBadgeRenderer")
                ?: continue
            if (badge.optString("label") == "LIVE" ||
                badge.optJSONObject("icon")?.optString("iconType") == "LIVE"
            ) {
                return true
            }
        }
        return viewCountText?.contains("watching", ignoreCase = true) == true
    }

    private fun channelBrowseId(renderer: JSONObject): String? {
        val byline = renderer.optJSONObject("longBylineText")
            ?: renderer.optJSONObject("shortBylineText")
            ?: return null
        val run = byline.optJSONArray("runs")?.optJSONObject(0) ?: return null
        return run.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun bestThumbnail(thumbnail: JSONObject?): String? {
        val thumbs = thumbnail?.optJSONArray("thumbnails") ?: return null
        if (thumbs.length() == 0) return null
        return thumbs.optJSONObject(thumbs.length() - 1)
            ?.optString("url")
            ?.takeIf { it.isNotEmpty() }
    }

    /** First continuation token found anywhere in the tree, if any. */
    fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
                node.optJSONObject("nextContinuationData")
                    ?.optString("continuation")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findContinuation(node.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findContinuation(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    // ----------------------------------------------------------------- player

    fun parseVideoDetails(player: JSONObject): VideoDetails {
        val details = player.optJSONObject("videoDetails")
            ?: throw LontarException("Respons player tidak memuat videoDetails")
        val microformat = player.optJSONObject("microformat")
            ?.optJSONObject("playerMicroformatRenderer")
        return VideoDetails(
            id = details.optString("videoId"),
            title = details.optString("title"),
            description = details.optString("shortDescription"),
            channelId = details.optString("channelId").takeIf { it.isNotEmpty() },
            channelName = details.optString("author"),
            viewCount = details.optString("viewCount").toLongOrNull(),
            likeCount = null, // Not exposed by the player endpoint.
            uploadedText = microformat?.optString("publishDate")?.takeIf { it.isNotEmpty() },
            isLive = details.optBoolean("isLiveContent", false),
        )
    }

    fun parsePlaybackStreams(player: JSONObject, nowEpochMs: Long): PlaybackStreams {
        val playability = player.optJSONObject("playabilityStatus")
        if (playability != null && playability.optString("status") != "OK") {
            val reason = playability.optString("reason", "Video tidak dapat diputar")
            throw LontarException("YouTube menolak memutar video ini: $reason")
        }
        val streaming = player.optJSONObject("streamingData")
            ?: return PlaybackStreams(null, null, null, null)
        // InnerTube sends expiresInSeconds as a JSON string, and org.json's
        // optLong does not coerce strings — read it flexibly (CI run #1
        // caught this: expiry silently came back null).
        val expiresAt = streaming.opt("expiresInSeconds")?.toString()?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { nowEpochMs + it * 1000 }

        // Progressive (muxed) formats carrying a direct URL can play as-is.
        // Ciphered formats (signatureCipher) are Fase 2 work — Lontar's own
        // cipher solver — and are skipped here rather than faked.
        var progressive: String? = null
        var bestBitrate = -1L
        val formats = streaming.optJSONArray("formats") ?: JSONArray()
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val url = format.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val bitrate = format.optLong("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                progressive = url
            }
        }

        return PlaybackStreams(
            dashManifestUrl = streaming.optString("dashManifestUrl").takeIf { it.isNotEmpty() },
            hlsUrl = streaming.optString("hlsManifestUrl").takeIf { it.isNotEmpty() },
            progressiveUrl = progressive,
            expiresAtEpochMs = expiresAt,
        )
    }

    // ---------------------------------------------------------------- helpers

    /** InnerTube text object: {"simpleText": "..."} or {"runs":[{"text":...}]}. */
    fun textOf(textObject: JSONObject?): String {
        if (textObject == null) return ""
        textObject.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = textObject.optJSONArray("runs") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until runs.length()) {
            builder.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return builder.toString()
    }

    /** "12:34" / "1:02:03" -> seconds. Null when unparseable. */
    fun parseDuration(text: String): Long? {
        val parts = text.trim().split(":").map { it.toLongOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    /**
     * "1,234,567 views" / "1.2M views" / "321K watching" / "No views" -> count.
     * Indonesian locales use '.' as the thousands separator; both are handled.
     */
    fun parseCount(text: String): Long? {
        val cleaned = text.trim().lowercase()
        if (cleaned.startsWith("no ") || cleaned.isEmpty()) return if (cleaned.isEmpty()) null else 0L
        val token = cleaned.split(" ").firstOrNull() ?: return null
        val multiplier = when {
            token.endsWith("k") -> 1_000L
            token.endsWith("m") -> 1_000_000L
            token.endsWith("b") -> 1_000_000_000L
            else -> 1L
        }
        val numeric = token.trimEnd('k', 'm', 'b')
        return if (multiplier > 1) {
            (numeric.replace(",", ".").toDoubleOrNull()?.times(multiplier))?.toLong()
        } else {
            numeric.replace(",", "").replace(".", "").toLongOrNull()
        }
    }
}
