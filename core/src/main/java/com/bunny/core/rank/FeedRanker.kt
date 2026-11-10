package com.bunny.core.rank

import com.bunny.core.model.VideoItem
import kotlin.math.ln
import kotlin.math.min

/**
 * What Mesin Selera knows about the viewer — all of it local, all of
 * it explainable. Search terms are single words (length ≥ 3) drawn
 * from the recent-search list by whoever builds the profile.
 */
data class TasteProfile(
    val subscribedChannelIds: Set<String> = emptySet(),
    /** Channel name → times a video from it was opened. */
    val channelAffinity: Map<String, Int> = emptyMap(),
    val searchTerms: Set<String> = emptySet(),
)

/**
 * Mesin Selera — Bunny's local taste engine. It mirrors the SHAPE of
 * YouTube's published two-stage architecture (candidate generation,
 * then ranking) with signals that never leave the device, after the
 * 2026-11-10 REA dissection showed the official ranking model itself
 * lives server-side and only its signal collectors ship in the app.
 *
 * Candidate generation happens upstream (the topic mixture plus, in
 * Terbit, subscriptions). This object is the ranking stage: a
 * transparent score (no learned black box — every point can be
 * explained) followed by a diversity pass, because a feed that is
 * five videos from one channel in a row is a bad feed at any score.
 */
object FeedRanker {

    fun rank(items: List<VideoItem>, taste: TasteProfile): List<VideoItem> {
        if (items.size < 2) return items
        val scored = items.sortedByDescending { score(it, taste) }
        return diversify(scored)
    }

    /** The explainable score. Higher is more likely to be wanted. */
    fun score(item: VideoItem, taste: TasteProfile): Double {
        var score = 0.0
        // Popularity on a log scale: the difference between 1 rb and
        // 10 rb views matters; between 41 jt and 42 jt it does not.
        val views = item.viewCount ?: 0L
        if (views > 0) score += ln(views.toDouble() + 1.0) / ln(10.0) * POPULARITY_WEIGHT
        // Stated interest beats inferred interest: a subscription is
        // the strongest signal the user ever gives Bunny.
        if (item.channelId != null && item.channelId in taste.subscribedChannelIds) {
            score += SUBSCRIPTION_BOOST
        }
        // Revealed interest: channels whose videos get opened.
        val opens = taste.channelAffinity[item.channelName] ?: 0
        if (opens > 0) score += min(opens, AFFINITY_CAP) * AFFINITY_WEIGHT
        // Searched-for words appearing in a title.
        if (taste.searchTerms.isNotEmpty()) {
            val title = item.title.lowercase()
            val matches = taste.searchTerms.count { title.contains(it) }
            score += min(matches, SEARCH_MATCH_CAP) * SEARCH_MATCH_WEIGHT
        }
        // Freshness, from the relative upload text both languages use.
        score += if (item.isLive) LIVE_SCORE else freshness(item.uploadedText)
        // Timbangan as the tiebreak only Bunny has: within otherwise
        // similar candidates, the cheaper video wins by a nose.
        val duration = item.durationSeconds
        if (duration != null && duration > 0) {
            score += (1.0 - min(duration, COST_BASELINE_SECONDS) / COST_BASELINE_SECONDS) *
                COST_WEIGHT
        }
        return score
    }

    private fun freshness(uploadedText: String?): Double {
        if (uploadedText.isNullOrBlank()) return FRESHNESS_UNKNOWN
        val text = uploadedText.lowercase()
        return when {
            "detik" in text || "second" in text -> 3.0
            "menit" in text || "minute" in text -> 3.0
            "jam" in text || "hour" in text -> 3.0
            "hari" in text || "day" in text -> 2.5
            "minggu" in text || "week" in text -> 2.0
            "bulan" in text || "month" in text -> 1.2
            "tahun" in text || "year" in text -> 0.3
            else -> FRESHNESS_UNKNOWN
        }
    }

    /**
     * Greedy diversity: walk the score order, always taking the best
     * remaining candidate that neither repeats the previous item's
     * channel nor completes three-in-a-row of one topic — unless
     * nothing else remains.
     */
    private fun diversify(sorted: List<VideoItem>): List<VideoItem> {
        val pool = sorted.toMutableList()
        val out = ArrayList<VideoItem>(sorted.size)
        while (pool.isNotEmpty()) {
            val prev = out.lastOrNull()
            val prev2 = out.getOrNull(out.size - 2)
            val index = pool.indexOfFirst { candidate ->
                val channelOk = candidate.channelName != prev?.channelName
                val topicOk = !(
                    candidate.reason != null &&
                        candidate.reason == prev?.reason &&
                        candidate.reason == prev2?.reason
                    )
                channelOk && topicOk
            }
            out += pool.removeAt(if (index >= 0) index else 0)
        }
        return out
    }

    private const val POPULARITY_WEIGHT = 0.8
    private const val SUBSCRIPTION_BOOST = 6.0
    private const val AFFINITY_CAP = 5
    private const val AFFINITY_WEIGHT = 1.2
    private const val SEARCH_MATCH_CAP = 2
    private const val SEARCH_MATCH_WEIGHT = 1.5
    private const val LIVE_SCORE = 2.8
    private const val FRESHNESS_UNKNOWN = 1.0
    private const val COST_BASELINE_SECONDS = 1800.0
    private const val COST_WEIGHT = 0.5
}
