package com.bunny.core.rank

import com.bunny.core.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRankerTest {

    private fun item(
        id: String,
        channelId: String? = null,
        channelName: String = "Kanal $id",
        title: String = "Video $id",
        views: Long? = null,
        uploaded: String? = null,
        reason: String? = null,
        duration: Long? = null,
    ) = VideoItem(
        id = id,
        title = title,
        channelId = channelId,
        channelName = channelName,
        thumbnailUrl = null,
        durationSeconds = duration,
        viewCount = views,
        uploadedText = uploaded,
        reason = reason,
    )

    @Test
    fun rank_subscriptionBeatsRawPopularity() {
        val viral = item("viral", views = 50_000_000, uploaded = "2 hari yang lalu")
        val subscribed = item(
            "sub",
            channelId = "UCsub",
            views = 300,
            uploaded = "2 hari yang lalu",
        )
        val ranked = FeedRanker.rank(
            listOf(viral, subscribed),
            TasteProfile(subscribedChannelIds = setOf("UCsub")),
        )
        assertEquals("sub", ranked.first().id)
    }

    @Test
    fun rank_channelAffinityRaisesItem() {
        val loved = item("loved", channelName = "Kanal Sayang", views = 100)
        val stranger = item("stranger", channelName = "Kanal Asing", views = 100_000)
        val ranked = FeedRanker.rank(
            listOf(stranger, loved),
            TasteProfile(channelAffinity = mapOf("Kanal Sayang" to 5)),
        )
        assertEquals("loved", ranked.first().id)
    }

    @Test
    fun rank_searchTermMatchRaisesItem() {
        val match = item("match", title = "Resep Rendang Sapi", views = 1_000)
        val other = item("other", title = "Berita Pagi", views = 5_000)
        val ranked = FeedRanker.rank(
            listOf(other, match),
            TasteProfile(searchTerms = setOf("rendang")),
        )
        assertEquals("match", ranked.first().id)
    }

    @Test
    fun rank_diversityBreaksChannelRuns() {
        val a1 = item("a1", channelName = "Sama", views = 9_000_000)
        val a2 = item("a2", channelName = "Sama", views = 8_000_000)
        val a3 = item("a3", channelName = "Sama", views = 7_000_000)
        val b1 = item("b1", channelName = "Beda", views = 10)
        val ranked = FeedRanker.rank(listOf(a1, a2, a3, b1), TasteProfile())
        assertEquals(4, ranked.size)
        // The lone different channel must not sit last behind a wall of
        // same-channel items: no two neighbours share a channel while
        // an alternative exists.
        assertTrue(ranked[1].channelName != ranked[0].channelName)
    }

    @Test
    fun rank_emptyProfileKeepsEveryItem() {
        val items = (1..12).map { i ->
            item("v$i", views = (i * 137_000).toLong(), uploaded = "$i hari yang lalu")
        }
        val ranked = FeedRanker.rank(items, TasteProfile())
        assertEquals(items.map { it.id }.toSet(), ranked.map { it.id }.toSet())
        assertEquals(items.size, ranked.size)
    }

    @Test
    fun score_freshnessPrefersRecentUploads() {
        val fresh = item("fresh", uploaded = "3 jam yang lalu")
        val stale = item("stale", uploaded = "2 tahun yang lalu")
        assertTrue(
            FeedRanker.score(fresh, TasteProfile()) >
                FeedRanker.score(stale, TasteProfile()),
        )
    }
}
