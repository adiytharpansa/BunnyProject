package com.bunny.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** The signed-in user's own channel, as the Data API publishes it. */
data class MineChannel(
    val id: String,
    val title: String,
    val handle: String?,
    val avatarUrl: String?,
    val subscriberCount: Long?,
)

/** One real subscription of the signed-in user. */
data class SubscriptionEntry(
    val channelId: String,
    val title: String,
    val avatarUrl: String?,
)

/** One video row from the Data API, before detail enrichment. */
data class ApiVideo(
    val id: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val publishedAtMillis: Long,
    val durationSeconds: Long? = null,
    val viewCount: Long? = null,
)

/** A Data API refusal, carrying the HTTP status for honest display. */
class DataApiException(val httpCode: Int) :
    Exception("Data API HTTP $httpCode")

/**
 * YouTube Data API v3 — Google's official data office. The Satu Ketuk
 * token is a first-class key here (InnerTube refused it on every
 * context, device probe 2026-11-11: 403/403/403). Everything returned
 * is the account's real data; nothing is estimated or substituted.
 * Read-only: only GET endpoints are ever called.
 */
class YouTubeDataApi(private val httpClient: OkHttpClient) {

    /** The user's own channel (identity for the profile header). */
    suspend fun mine(token: String): MineChannel? {
        val root = get(token, "channels?part=snippet,statistics&mine=true")
        return parseMine(root)
    }

    /** All subscriptions, following pages (hard cap: [MAX_SUBSCRIPTIONS]). */
    suspend fun subscriptions(token: String): List<SubscriptionEntry> {
        val all = mutableListOf<SubscriptionEntry>()
        var pageToken: String? = null
        while (all.size < MAX_SUBSCRIPTIONS) {
            val path = buildString {
                append("subscriptions?part=snippet&mine=true")
                append("&maxResults=50&order=relevance")
                if (pageToken != null) append("&pageToken=$pageToken")
            }
            val root = get(token, path)
            all += parseSubscriptions(root)
            pageToken = root.optString("nextPageToken")
                .takeIf { it.isNotEmpty() } ?: break
        }
        return all.take(MAX_SUBSCRIPTIONS)
    }

    /** channelId -> uploads playlist id, batched 50 ids per call. */
    suspend fun uploadsPlaylists(
        token: String,
        channelIds: List<String>,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (chunk in channelIds.chunked(50)) {
            val root = get(
                token,
                "channels?part=contentDetails&id=${chunk.joinToString(",")}",
            )
            result += parseUploadsPlaylists(root)
        }
        return result
    }

    /** The newest [max] videos of one playlist, newest first. */
    suspend fun playlistVideos(
        token: String,
        playlistId: String,
        max: Int,
    ): List<ApiVideo> {
        val root = get(
            token,
            "playlistItems?part=snippet,contentDetails" +
                "&playlistId=$playlistId&maxResults=$max",
        )
        return parsePlaylistItems(root)
    }

    /** videoId -> (durationSeconds, viewCount), batched 50 ids per call. */
    suspend fun videoDetails(
        token: String,
        ids: List<String>,
    ): Map<String, Pair<Long?, Long?>> {
        val result = mutableMapOf<String, Pair<Long?, Long?>>()
        for (chunk in ids.chunked(50)) {
            val root = get(
                token,
                "videos?part=contentDetails,statistics&id=${chunk.joinToString(",")}",
            )
            result += parseVideoDetails(root)
        }
        return result
    }

    /** The user's real liked videos, newest first, details included. */
    suspend fun liked(token: String, max: Int = 25): List<ApiVideo> {
        val root = get(
            token,
            "videos?part=snippet,contentDetails,statistics" +
                "&myRating=like&maxResults=$max",
        )
        return parseLiked(root)
    }

    private suspend fun get(token: String, path: String): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE_URL$path")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DataApiException(response.code)
                }
                val body = response.body?.string()
                    ?: throw IOException("Data API: empty body")
                JSONObject(body)
            }
        }

    companion object {
        private const val BASE_URL = "https://www.googleapis.com/youtube/v3/"
        private const val MAX_SUBSCRIPTIONS = 200
    }
}

// ---------------------------------------------------------------------------
// Parsers — kept apart from the network so fixtures can prove them.
// ---------------------------------------------------------------------------

internal fun parseMine(root: JSONObject): MineChannel? {
    val items = root.optJSONArray("items") ?: return null
    if (items.length() == 0) return null
    val item = items.optJSONObject(0) ?: return null
    val snippet = item.optJSONObject("snippet") ?: return null
    val title = snippet.optString("title").takeIf { it.isNotEmpty() }
        ?: return null
    return MineChannel(
        id = item.optString("id"),
        title = title,
        handle = snippet.optString("customUrl").takeIf { it.isNotEmpty() },
        avatarUrl = bestThumbnail(snippet.optJSONObject("thumbnails")),
        subscriberCount = item.optJSONObject("statistics")
            ?.optString("subscriberCount")?.toLongOrNull(),
    )
}

internal fun parseSubscriptions(root: JSONObject): List<SubscriptionEntry> {
    val items = root.optJSONArray("items") ?: return emptyList()
    val result = mutableListOf<SubscriptionEntry>()
    for (i in 0 until items.length()) {
        val snippet = items.optJSONObject(i)?.optJSONObject("snippet")
            ?: continue
        val channelId = snippet.optJSONObject("resourceId")
            ?.optString("channelId")?.takeIf { it.isNotEmpty() } ?: continue
        val title = snippet.optString("title").takeIf { it.isNotEmpty() }
            ?: continue
        result += SubscriptionEntry(
            channelId = channelId,
            title = title,
            avatarUrl = bestThumbnail(snippet.optJSONObject("thumbnails")),
        )
    }
    return result
}

internal fun parseUploadsPlaylists(root: JSONObject): Map<String, String> {
    val items = root.optJSONArray("items") ?: return emptyMap()
    val result = mutableMapOf<String, String>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val id = item.optString("id").takeIf { it.isNotEmpty() } ?: continue
        val uploads = item.optJSONObject("contentDetails")
            ?.optJSONObject("relatedPlaylists")
            ?.optString("uploads")?.takeIf { it.isNotEmpty() } ?: continue
        result[id] = uploads
    }
    return result
}

internal fun parsePlaylistItems(root: JSONObject): List<ApiVideo> {
    val items = root.optJSONArray("items") ?: return emptyList()
    val result = mutableListOf<ApiVideo>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val snippet = item.optJSONObject("snippet") ?: continue
        val videoId = item.optJSONObject("contentDetails")
            ?.optString("videoId")?.takeIf { it.isNotEmpty() } ?: continue
        // Deleted/private entries arrive titled as such, without a
        // playable id worth showing; they are not real rows.
        val title = snippet.optString("title")
        if (title.isEmpty() || title == "Deleted video" ||
            title == "Private video"
        ) {
            continue
        }
        result += ApiVideo(
            id = videoId,
            title = title,
            channelId = snippet.optString("videoOwnerChannelId")
                .ifEmpty { snippet.optString("channelId") },
            channelName = snippet.optString("videoOwnerChannelTitle")
                .ifEmpty { snippet.optString("channelTitle") },
            thumbnailUrl = bestThumbnail(snippet.optJSONObject("thumbnails")),
            publishedAtMillis = parsePublishedAt(
                snippet.optString("publishedAt"),
            ),
        )
    }
    return result
}

internal fun parseVideoDetails(
    root: JSONObject,
): Map<String, Pair<Long?, Long?>> {
    val items = root.optJSONArray("items") ?: return emptyMap()
    val result = mutableMapOf<String, Pair<Long?, Long?>>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val id = item.optString("id").takeIf { it.isNotEmpty() } ?: continue
        val duration = item.optJSONObject("contentDetails")
            ?.optString("duration")?.let { parseIsoDuration(it) }
        val views = item.optJSONObject("statistics")
            ?.optString("viewCount")?.toLongOrNull()
        result[id] = duration to views
    }
    return result
}

internal fun parseLiked(root: JSONObject): List<ApiVideo> {
    val items = root.optJSONArray("items") ?: return emptyList()
    val result = mutableListOf<ApiVideo>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val id = item.optString("id").takeIf { it.isNotEmpty() } ?: continue
        val snippet = item.optJSONObject("snippet") ?: continue
        result += ApiVideo(
            id = id,
            title = snippet.optString("title"),
            channelId = snippet.optString("channelId"),
            channelName = snippet.optString("channelTitle"),
            thumbnailUrl = bestThumbnail(snippet.optJSONObject("thumbnails")),
            publishedAtMillis = parsePublishedAt(
                snippet.optString("publishedAt"),
            ),
            durationSeconds = item.optJSONObject("contentDetails")
                ?.optString("duration")?.let { parseIsoDuration(it) },
            viewCount = item.optJSONObject("statistics")
                ?.optString("viewCount")?.toLongOrNull(),
        )
    }
    return result
}

/** Widest published thumbnail: high, then medium, then default. */
internal fun bestThumbnail(thumbnails: JSONObject?): String? {
    if (thumbnails == null) return null
    for (key in listOf("high", "medium", "default")) {
        val url = thumbnails.optJSONObject(key)?.optString("url")
        if (!url.isNullOrEmpty()) return url
    }
    return null
}

internal fun parsePublishedAt(text: String): Long =
    runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)

/** ISO-8601 durations the API emits: PT#H#M#S, sometimes P#DT#H#M#S. */
internal fun parseIsoDuration(text: String): Long? {
    val match = ISO_DURATION.matchEntire(text) ?: return null
    val days = match.groupValues[1].toLongOrNull() ?: 0
    val hours = match.groupValues[2].toLongOrNull() ?: 0
    val minutes = match.groupValues[3].toLongOrNull() ?: 0
    val seconds = match.groupValues[4].toLongOrNull() ?: 0
    return days * 86_400 + hours * 3_600 + minutes * 60 + seconds
}

private val ISO_DURATION =
    Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")
