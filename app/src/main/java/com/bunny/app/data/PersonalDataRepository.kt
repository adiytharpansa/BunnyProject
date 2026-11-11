package com.bunny.app.data

import com.bunny.core.model.VideoItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The personal layer Jalan A: everything here is the signed-in
 * account's real Data API data. Playback itself stays on Lontar —
 * these lists only decide WHICH real videos are offered.
 */
@Singleton
class PersonalDataRepository @Inject constructor(
    private val api: YouTubeDataApi,
    private val auth: AuthRepository,
) {

    private fun token(): String =
        auth.dataToken ?: throw DataApiException(401)

    /** The stored token was refused (401): drop it so the next visit re-fetches one silently. */
    fun invalidateToken() {
        auth.dataToken = null
    }

    suspend fun subscriptions(): List<SubscriptionEntry> =
        api.subscriptions(token())

    /**
     * Newest uploads across the user's real subscriptions: the first
     * [FEED_CHANNELS] subscriptions, [PER_CHANNEL] videos each, merged
     * and sorted newest first, enriched with real durations and view
     * counts. The reason chip names the subscription it came from.
     */
    suspend fun latestFeed(): List<VideoItem> {
        val token = token()
        val subs = api.subscriptions(token).take(FEED_CHANNELS)
        if (subs.isEmpty()) return emptyList()
        val uploads = api.uploadsPlaylists(token, subs.map { it.channelId })
        val avatarByChannel = subs.associate { it.channelId to it.avatarUrl }
        val perChannel = coroutineScope {
            subs.map { sub ->
                async {
                    val playlist = uploads[sub.channelId]
                        ?: return@async emptyList()
                    runCatching {
                        api.playlistVideos(token, playlist, PER_CHANNEL)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }.flatten()
        val details = api.videoDetails(token, perChannel.map { it.id })
        return perChannel
            .sortedByDescending { it.publishedAtMillis }
            .take(FEED_MAX)
            .map { video ->
                val (duration, views) = details[video.id]
                    ?: (null to null)
                VideoItem(
                    id = video.id,
                    title = video.title,
                    channelId = video.channelId,
                    channelName = video.channelName,
                    channelAvatarUrl = avatarByChannel[video.channelId],
                    thumbnailUrl = video.thumbnailUrl,
                    durationSeconds = duration,
                    viewCount = views,
                    uploadedText = formatRelativeId(
                        video.publishedAtMillis,
                    ),
                    reason = "Dari langganan YouTube: ${video.channelName}",
                )
            }
    }

    /** The user's real liked videos, newest first. */
    suspend fun liked(): List<VideoItem> {
        val videos = api.liked(token())
        val subs = runCatching { api.subscriptions(token()) }
            .getOrDefault(emptyList())
        val avatarByChannel = subs.associate { it.channelId to it.avatarUrl }
        return videos.map { video ->
            VideoItem(
                id = video.id,
                title = video.title,
                channelId = video.channelId,
                channelName = video.channelName,
                channelAvatarUrl = avatarByChannel[video.channelId],
                thumbnailUrl = video.thumbnailUrl,
                durationSeconds = video.durationSeconds,
                viewCount = video.viewCount,
                uploadedText = formatRelativeId(video.publishedAtMillis),
                reason = "Video yang Anda sukai",
            )
        }
    }

    private companion object {
        const val FEED_CHANNELS = 30
        const val PER_CHANNEL = 3
        const val FEED_MAX = 60
    }
}

/**
 * Indonesian relative time from a real publish timestamp:
 * "5 menit lalu", "3 jam lalu", "2 hari lalu", "1 minggu lalu",
 * "4 bulan lalu", "1 tahun lalu". Future/unknown timestamps and a
 * broken clock fall back to the smallest honest unit.
 */
internal fun formatRelativeId(
    publishedAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    if (publishedAtMillis <= 0L) return "baru saja"
    val seconds = ((nowMillis - publishedAtMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "baru saja"
        seconds < 3_600 -> "${seconds / 60} menit lalu"
        seconds < 86_400 -> "${seconds / 3_600} jam lalu"
        seconds < 604_800 -> "${seconds / 86_400} hari lalu"
        seconds < 2_592_000 -> "${seconds / 604_800} minggu lalu"
        seconds < 31_536_000 -> "${seconds / 2_592_000} bulan lalu"
        else -> "${seconds / 31_536_000} tahun lalu"
    }
}
