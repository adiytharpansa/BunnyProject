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
                if (node.has("playlistVideoRenderer")) {
                    parsePlaylistVideoRenderer(node.getJSONObject("playlistVideoRenderer"))
                        ?.let(out::add)
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

    /**
     * Playlist rows (a channel's uploads playlist, used by Terbit) carry a
     * slimmer renderer than search/browse video rows: no publishedTimeText,
     * views and age are combined in "videoInfo".
     */
    private fun parsePlaylistVideoRenderer(renderer: JSONObject): VideoItem? {
        val videoId = renderer.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val infoText = textOf(renderer.optJSONObject("videoInfo"))
        return VideoItem(
            id = videoId,
            title = textOf(renderer.optJSONObject("title")),
            channelId = channelBrowseId(renderer),
            channelName = textOf(renderer.optJSONObject("shortBylineText")),
            thumbnailUrl = bestThumbnail(renderer.optJSONObject("thumbnail")),
            durationSeconds = renderer.optJSONObject("lengthText")
                ?.let { parseDuration(textOf(it)) },
            viewCount = infoText.takeIf { it.isNotEmpty() }?.let { parseCount(it) },
            uploadedText = infoText.takeIf { it.contains("•") }
                ?.substringAfter("•")?.trim()?.takeIf { it.isNotEmpty() },
            isLive = false,
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
            thumbnailUrl = bestThumbnail(details.optJSONObject("thumbnail")),
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
        // Ciphered formats (signatureCipher) are skipped rather than faked:
        // the VR player persona returns direct URLs, so no cipher solver is
        // on the playback path (see PlayerPersona).
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

        // Adaptive formats become Bunny's own DASH manifest (Fase 2): the
        // server rarely hands one out, so Lontar generates it from the
        // formats' URLs and byte ranges. See DashManifestBuilder.
        val durationSeconds = player.optJSONObject("videoDetails")
            ?.opt("lengthSeconds")?.toString()?.toLongOrNull()
        val dashContent = DashManifestBuilder.build(
            streaming.optJSONArray("adaptiveFormats"),
            durationSeconds,
        )

        return PlaybackStreams(
            dashManifestUrl = streaming.optString("dashManifestUrl").takeIf { it.isNotEmpty() },
            hlsUrl = streaming.optString("hlsManifestUrl").takeIf { it.isNotEmpty() },
            progressiveUrl = progressive,
            expiresAtEpochMs = expiresAt,
            dashManifestContent = dashContent,
            estimatedSizeBytes = estimateAdaptiveSize(
                streaming.optJSONArray("adaptiveFormats"),
                durationSeconds,
            ),
        )
    }

    /**
     * Timbangan at the player: what watching this video costs at the
     * reference quality, from the REAL adaptive formats on offer — the
     * richest video format at or below 480p (or the leanest above it)
     * plus the richest audio format, bitrates times duration. Null when
     * the inputs are missing; a wrong number is worse than none.
     */
    private fun estimateAdaptiveSize(
        adaptiveFormats: JSONArray?,
        durationSeconds: Long?,
    ): Long? {
        if (adaptiveFormats == null || durationSeconds == null || durationSeconds <= 0) {
            return null
        }
        var bestAtOrBelow480 = 0L
        var leanestVideo = Long.MAX_VALUE
        var bestAudio = 0L
        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val bitrate = format.optLong("bitrate", 0)
            if (bitrate <= 0) continue
            val mimeType = format.optString("mimeType")
            when {
                mimeType.startsWith("audio/") -> {
                    if (bitrate > bestAudio) bestAudio = bitrate
                }
                mimeType.startsWith("video/") -> {
                    if (bitrate < leanestVideo) leanestVideo = bitrate
                    if (format.optInt("height", 0) in 1..480 && bitrate > bestAtOrBelow480) {
                        bestAtOrBelow480 = bitrate
                    }
                }
            }
        }
        val videoBitrate = when {
            bestAtOrBelow480 > 0 -> bestAtOrBelow480
            leanestVideo != Long.MAX_VALUE -> leanestVideo
            else -> return null
        }
        return (videoBitrate + bestAudio) * durationSeconds / 8
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
     * Indonesian counts abbreviate with a SEPARATE word — "1,2 rb" (ribu),
     * "3,5 jt" (juta), "1,1 M" (miliar) — while English glues the suffix
     * to the number; both shapes are handled. Full numbers use '.' or ','
     * as thousands separators; abbreviated ones use them as the decimal
     * separator.
     */
    fun parseCount(text: String): Long? {
        val cleaned = text.trim().lowercase()
        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith("no ")) return 0L
        val tokens = cleaned.split(" ").filter { it.isNotEmpty() }
        val first = tokens.firstOrNull() ?: return null
        val multiplier = when {
            first.endsWith("k") || tokens.getOrNull(1) == "rb" -> 1_000L
            first.endsWith("m") || tokens.getOrNull(1) == "jt" -> 1_000_000L
            first.endsWith("b") || tokens.getOrNull(1) == "m" -> 1_000_000_000L
            else -> 1L
        }
        val numeric = first.trimEnd('k', 'm', 'b')
        return if (multiplier > 1) {
            (numeric.replace(",", ".").toDoubleOrNull()?.times(multiplier))?.toLong()
        } else {
            numeric.replace(",", "").replace(".", "").toLongOrNull()
        }
    }
}
