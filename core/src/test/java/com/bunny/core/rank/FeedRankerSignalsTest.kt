package com.bunny.core.rank

import com.bunny.core.model.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRankerSignalsTest {

    private fun item(
        id: String,
        channelId: String? = null,
        channelName: String = "Kanal $id",
        title: String = "Video $id",
        views: Long? = 10_000,
        uploaded: String? = "2 hari yang lalu",
    ) = VideoItem(
        id = id,
        title = title,
        channelId = channelId,
        channelName = channelName,
        thumbnailUrl = null,
        durationSeconds = null,
        viewCount = views,
        uploadedText = uploaded,
    )

    private fun twenty() = (1..20).map { i -> item("v$i", views = (i * 50_000).toLong()) }

    @Test
    fun hiddenVideoIds_areRemoved() {
        val ranked = FeedRanker.rank(
            listOf(item("a"), item("b")),
            TasteProfile(hiddenVideoIds = setOf("a")),
        )
        assertEquals(listOf("b"), ranked.map { it.id })
    }

    @Test
    fun blockedChannelId_removesItsVideos() {
        val ranked = FeedRanker.rank(
            listOf(item("a", channelId = "UC1"), item("b", channelId = "UC2")),
            TasteProfile(blockedChannelIds = setOf("UC1")),
        )
        assertEquals(listOf("b"), ranked.map { it.id })
    }

    @Test
    fun blockedChannelName_removesItsVideos() {
        val ranked = FeedRanker.rank(
            listOf(item("a", channelName = "Spam"), item("b")),
            TasteProfile(blockedChannelNames = setOf("Spam")),
        )
        assertEquals(listOf("b"), ranked.map { it.id })
    }

    @Test
    fun channelInterest_beatsRawPopularity() {
        val loved = item("loved", channelName = "Sayang", views = 100)
        val stranger = item("stranger", channelName = "Asing", views = 100_000)
        val ranked = FeedRanker.rank(
            listOf(stranger, loved),
            TasteProfile(channelInterest = mapOf("Sayang" to 4.0)),
        )
        assertEquals("loved", ranked.first().id)
    }

    @Test
    fun negativeChannelInterest_sinksItem() {
        val ok = item("ok")
        val bad = item("bad")
        val ranked = FeedRanker.rank(
            listOf(bad, ok),
            TasteProfile(channelInterest = mapOf("Kanal bad" to -5.0)),
        )
        assertEquals("bad", ranked.last().id)
    }

    @Test
    fun topicInterest_raisesMatchingTitle() {
        val match = item("match", title = "Resep Rendang Sapi", views = 1_000)
        val other = item("other", title = "Berita Pagi", views = 5_000)
        val ranked = FeedRanker.rank(
            listOf(other, match),
            TasteProfile(topicInterest = mapOf("rendang" to 4.0)),
        )
        assertEquals("match", ranked.first().id)
    }

    @Test
    fun rejectedTopics_sinkMatchingTitle() {
        val rejected = item("rejected", title = "Gosip Artis Terbaru")
        val fine = item("fine", title = "Resep Sapi Panggang")
        val ranked = FeedRanker.rank(
            listOf(rejected, fine),
            TasteProfile(rejectedTopics = mapOf("gosip" to 5.0)),
        )
        assertEquals("rejected", ranked.last().id)
    }

    @Test
    fun exploration_keepsEveryItem_andIsDeterministicPerSeed() {
        val items = twenty()
        val first = FeedRanker.rank(items, TasteProfile(), explorationSeed = 7L)
        val again = FeedRanker.rank(items, TasteProfile(), explorationSeed = 7L)
        assertEquals(items.map { it.id }.toSet(), first.map { it.id }.toSet())
        assertEquals(items.size, first.size)
        assertEquals(first.map { it.id }, again.map { it.id })
    }

    @Test
    fun exploration_neverResurfacesHiddenItems() {
        val ranked = FeedRanker.rank(
            twenty(),
            TasteProfile(hiddenVideoIds = setOf("v1", "v2")),
            explorationSeed = 3L,
        )
        assertFalse(ranked.any { it.id == "v1" || it.id == "v2" })
        assertEquals(18, ranked.size)
    }

    @Test
    fun exploration_keepsBestCandidateInFirstSlot() {
        val ranked = FeedRanker.rank(twenty(), TasteProfile(), explorationSeed = 11L)
        assertEquals("v20", ranked.first().id)
    }

    @Test
    fun profile_coldWhenEmpty_warmWhenSignalsExist() {
        assertTrue(TasteProfile().isCold)
        val warm = TasteProfile(subscribedChannelIds = (1..6).map { "UC$it" }.toSet())
        assertFalse(warm.isCold)
    }
}
