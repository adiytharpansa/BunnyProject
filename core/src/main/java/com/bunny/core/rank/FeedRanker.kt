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
    /**
     * Selera Akun: interest words weighted by the signed-in
     * account's own likes (tags count most, then title words,
     * categories, subscription names) — rebuilt on-device from the
     * official Data API, never the server's profile.
     */
    val accountTerms: Map<String, Int> = emptyMap(),
    /** Channels behind the account's subscriptions and likes. */
    val accountChannelIds: Set<String> = emptySet(),
    /**
     * Videos already shown on Home in earlier loads that the viewer
     * never opened. YouTube's published model churns its lists between
     * requests and demotes what was shown but not watched (2016); this
     * set is Bunny's local equivalent.
     */
    val demotedIds: Set<String> = emptySet(),
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
 *
 * The score follows the objective YouTube itself published (2016,
 * reaffirmed for watch-next in 2019): rank by EXPECTED WATCH TIME and
 * satisfaction, never by clicks/popularity alone — popularity enters
 * only as demand evidence inside the watch-time estimate, and the
 * viewer's own previous interaction with a channel or topic is the
 * strongest feature family, exactly as the papers describe. There is
 * no server-composed home list to borrow for an anonymous session
 * (probed four ways 2026-11-11: the guest home grid is empty), so —
 * unlike the watch page's related list, which Bunny now takes from
 * the server as-is — Home applies the published recipe locally.
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
        // Expected watch time, the published objective: demand evidence
        // (views, log scale — the difference between 1 rb and 10 rb
        // matters; between 41 jt and 42 jt it does not) times how much
        // watch time a sitting can hold (duration, capped). A short
        // clip with the same views has delivered less watch time per
        // viewer than a long video people stayed with.
        val views = item.viewCount ?: 0L
        if (views > 0) score += ln(views.toDouble() + 1.0) / ln(10.0) * POPULARITY_WEIGHT
        val duration = item.durationSeconds
        if (duration != null && duration > 0) {
            score += min(duration.toDouble(), WATCHTIME_BASELINE_SECONDS) /
                WATCHTIME_BASELINE_SECONDS * WATCHTIME_WEIGHT
        }
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
        // Selera Akun: channels the account itself follows or likes
        // from — stated interest at the account level.
        if (item.channelId != null &&
            item.channelId in taste.accountChannelIds
        ) {
            score += ACCOUNT_CHANNEL_BOOST
        }
        // Selera Akun: the account's weighted interest words in the
        // title, in proportion to how strongly the account's likes
        // voted for each word.
        if (taste.accountTerms.isNotEmpty()) {
            val title = item.title.lowercase()
            var termScore = 0.0
            for ((term, weight) in taste.accountTerms) {
                if (title.contains(term)) {
                    termScore += min(weight, ACCOUNT_TERM_WEIGHT_CAP) *
                        ACCOUNT_TERM_WEIGHT
                }
            }
            score += min(termScore, ACCOUNT_TERM_TOTAL_CAP)
        }
        // Freshness, from the relative upload text both languages use
        // (the papers' "example age": fresh is preferred, never at the
        // expense of relevance).
        score += if (item.isLive) LIVE_SCORE else freshness(item.uploadedText)
        // Churn: shown on Home before and not opened — demoted, not
        // banned; a strong enough candidate can still surface.
        if (item.id in taste.demotedIds) score -= DEMOTE_PENALTY
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
    private const val ACCOUNT_CHANNEL_BOOST = 5.0
    private const val ACCOUNT_TERM_WEIGHT = 0.25
    private const val ACCOUNT_TERM_WEIGHT_CAP = 12
    private const val ACCOUNT_TERM_TOTAL_CAP = 6.0
    private const val LIVE_SCORE = 2.8
    private const val FRESHNESS_UNKNOWN = 1.0
    private const val WATCHTIME_BASELINE_SECONDS = 1800.0
    private const val WATCHTIME_WEIGHT = 1.2
    private const val DEMOTE_PENALTY = 4.0
}
