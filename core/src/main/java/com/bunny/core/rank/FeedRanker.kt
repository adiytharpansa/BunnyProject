package com.bunny.core.rank

import com.bunny.core.model.VideoItem
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

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
    /**
     * Minat channel bertanda: waktu tonton nyata × peluruhan waktu,
     * ditambah suka/survei, dikurangi "tidak tertarik". Bila sebuah channel
     * ada di sini, nilai ini dipakai menggantikan [channelAffinity].
     */
    val channelInterest: Map<String, Double> = emptyMap(),
    /** Kata judul dari video yang benar-benar ditonton (kata → bobot). */
    val topicInterest: Map<String, Double> = emptyMap(),
    /** Kata judul dari video yang ditolak, sudah dikurangi minat positif. */
    val rejectedTopics: Map<String, Double> = emptyMap(),
    /** Video yang dibuang pemirsa lewat "Tidak tertarik": tak pernah tampil lagi. */
    val hiddenVideoIds: Set<String> = emptySet(),
    /** Channel yang diblokir lewat "Jangan rekomendasikan channel". */
    val blockedChannelIds: Set<String> = emptySet(),
    val blockedChannelNames: Set<String> = emptySet(),
) {
    /** Ukuran kasar seberapa banyak yang diketahui mesin tentang pemirsa. */
    val signalStrength: Int
        get() = subscribedChannelIds.size +
            channelAffinity.size +
            channelInterest.size +
            topicInterest.size / 3 +
            searchTerms.size / 2 +
            accountChannelIds.size / 3 +
            accountTerms.size / 5

    val isCold: Boolean get() = signalStrength < COLD_THRESHOLD

    companion object {
        const val COLD_THRESHOLD = 5
        const val RICH_THRESHOLD = 40
    }
}

/**
 * Mesin Selera — Bunny's local taste engine. It mirrors the SHAPE of
 * YouTube's published two-stage architecture (candidate generation,
 * then ranking) with signals that never leave the device.
 *
 * Candidate generation happens upstream (the topic mixture, Terbit's
 * subscriptions, the watch-next chain). This object is the ranking
 * stage: a transparent score (every point can be explained), an
 * exploration pass so the feed cannot collapse into a bubble, and a
 * diversity pass because five videos from one channel in a row is a bad
 * feed at any score.
 *
 * The score follows the objective YouTube itself published: rank by
 * EXPECTED WATCH TIME and satisfaction, never by clicks alone.
 */
object FeedRanker {

    /**
     * @param explorationSeed benih acak untuk slot eksplorasi. Nilai yang
     * sama menghasilkan urutan yang sama (mudah dites); Beranda memberi
     * waktu sekarang supaya setiap muatan berbeda.
     */
    fun rank(
        items: List<VideoItem>,
        taste: TasteProfile,
        explorationSeed: Long = 0L,
    ): List<VideoItem> {
        val visible = items.filterNot { isHidden(it, taste) }
        if (visible.size < 2) return visible
        val scored = visible.sortedByDescending { score(it, taste) }
        return diversify(withExploration(scored, taste, explorationSeed))
    }

    /** Video yang dibuang pemirsa, atau milik channel yang diblokir. */
    fun isHidden(item: VideoItem, taste: TasteProfile): Boolean {
        if (item.id in taste.hiddenVideoIds) return true
        val channelId = item.channelId
        if (channelId != null && channelId in taste.blockedChannelIds) return true
        return item.channelName.isNotEmpty() && item.channelName in taste.blockedChannelNames
    }

    /** The explainable score. Higher is more likely to be wanted. */
    fun score(item: VideoItem, taste: TasteProfile): Double {
        var score = 0.0
        // Expected watch time, the published objective: demand evidence
        // (views, log scale) times how much watch time a sitting can hold
        // (duration, capped).
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
        // Revealed interest. Minat bertanda (waktu tonton nyata + peluruhan +
        // umpan balik) menggantikan hitungan buka mentah bila channel-nya
        // tercatat; selain itu hitungan lama tetap berlaku.
        val interest = taste.channelInterest[item.channelName]
        if (interest != null) {
            score += interest.coerceIn(-INTEREST_CAP, INTEREST_CAP) * INTEREST_WEIGHT
        } else {
            val opens = taste.channelAffinity[item.channelName] ?: 0
            if (opens > 0) score += min(opens, AFFINITY_CAP) * AFFINITY_WEIGHT
        }
        // Searched-for words appearing in a title.
        if (taste.searchTerms.isNotEmpty()) {
            val title = item.title.lowercase()
            val matches = taste.searchTerms.count { title.contains(it) }
            score += min(matches, SEARCH_MATCH_CAP) * SEARCH_MATCH_WEIGHT
        }
        // Minat topik dari judul video yang ditonton, dan penolakan topik.
        if (taste.topicInterest.isNotEmpty() || taste.rejectedTopics.isNotEmpty()) {
            var liked = 0.0
            var rejected = 0.0
            for (word in TasteText.words(item.title)) {
                liked += taste.topicInterest[word] ?: 0.0
                rejected += taste.rejectedTopics[word] ?: 0.0
            }
            score += min(liked, TOPIC_LIKE_CAP) * TOPIC_LIKE_WEIGHT
            score -= min(rejected, TOPIC_REJECT_CAP) * TOPIC_REJECT_WEIGHT
        }
        // Selera Akun: channels the account itself follows or likes
        // from — stated interest at the account level.
        if (item.channelId != null &&
            item.channelId in taste.accountChannelIds
        ) {
            score += ACCOUNT_CHANNEL_BOOST
        }
        // Selera Akun: the account's weighted interest words in the title.
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
        // Freshness, from the relative upload text both languages use.
        score += if (item.isLive) LIVE_SCORE else freshness(item.uploadedText)
        // Churn: shown on Home before and not opened — demoted, not banned.
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

    // ------------------------------------------------------------ eksplorasi

    /**
     * Eksplorasi terkontrol. Tanpa ini, mesin yang hanya mengulang yang sudah
     * disukai mengurung pemirsa dalam gelembung, dan pemirsa baru (cold
     * start) melihat daftar yang sama dengan semua orang. Beberapa slot
     * diisi video dari paruh bawah urutan skor yang TIDAK menyentuh minat
     * apa pun yang dikenal profil. Porsinya mengecil seiring profil makin
     * kaya: 25% cold start, 15% normal, 10% kaya.
     */
    private fun withExploration(
        sorted: List<VideoItem>,
        taste: TasteProfile,
        seed: Long,
    ): List<VideoItem> {
        if (sorted.size < MIN_ITEMS_FOR_EXPLORATION) return sorted
        val fraction = when {
            taste.isCold -> EXPLORE_COLD
            taste.signalStrength >= TasteProfile.RICH_THRESHOLD -> EXPLORE_RICH
            else -> EXPLORE_NORMAL
        }
        val slots = (sorted.size * fraction).roundToInt().coerceAtLeast(1)
        val novel = sorted.drop(sorted.size / 2).filter { !touchesTaste(it, taste) }
        if (novel.isEmpty()) return sorted
        val picked = novel.shuffled(Random(seed)).take(slots)
        val pickedIds = picked.map { it.id }.toSet()
        val core = sorted.filter { it.id !in pickedIds }

        val out = ArrayList<VideoItem>(sorted.size)
        var ci = 0
        var pi = 0
        var position = 0
        while (ci < core.size || pi < picked.size) {
            val explorationTurn = pi < picked.size &&
                position >= EXPLORE_FIRST_SLOT &&
                (position - EXPLORE_FIRST_SLOT) % EXPLORE_STRIDE == 0
            if (explorationTurn || ci >= core.size) {
                out += picked[pi++]
            } else {
                out += core[ci++]
            }
            position++
        }
        return out
    }

    /** True bila item ini menyentuh minat apa pun yang dikenal profil. */
    private fun touchesTaste(item: VideoItem, taste: TasteProfile): Boolean {
        val channelId = item.channelId
        if (channelId != null &&
            (channelId in taste.subscribedChannelIds || channelId in taste.accountChannelIds)
        ) {
            return true
        }
        if ((taste.channelInterest[item.channelName] ?: 0.0) > 0.0) return true
        if ((taste.channelAffinity[item.channelName] ?: 0) > 0) return true
        val title = item.title.lowercase()
        if (taste.searchTerms.any { title.contains(it) }) return true
        if (taste.accountTerms.keys.any { title.contains(it) }) return true
        return TasteText.words(item.title).any { (taste.topicInterest[it] ?: 0.0) > 0.0 }
    }

    // -------------------------------------------------------------- diversitas

    /**
     * Greedy diversity: walk the order, always taking the best remaining
     * candidate that neither repeats the previous item's channel nor
     * completes three-in-a-row of one topic — unless nothing else remains.
     * Dijalankan TERAKHIR, jadi slot eksplorasi pun tak merusak aturan ini.
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
    private const val INTEREST_CAP = 5.0
    private const val INTEREST_WEIGHT = 1.2
    private const val SEARCH_MATCH_CAP = 2
    private const val SEARCH_MATCH_WEIGHT = 1.5
    private const val TOPIC_LIKE_CAP = 6.0
    private const val TOPIC_LIKE_WEIGHT = 0.5
    private const val TOPIC_REJECT_CAP = 4.0
    private const val TOPIC_REJECT_WEIGHT = 0.6
    private const val ACCOUNT_CHANNEL_BOOST = 5.0
    private const val ACCOUNT_TERM_WEIGHT = 0.25
    private const val ACCOUNT_TERM_WEIGHT_CAP = 12
    private const val ACCOUNT_TERM_TOTAL_CAP = 6.0
    private const val LIVE_SCORE = 2.8
    private const val FRESHNESS_UNKNOWN = 1.0
    private const val WATCHTIME_BASELINE_SECONDS = 1800.0
    private const val WATCHTIME_WEIGHT = 1.2
    private const val DEMOTE_PENALTY = 4.0

    private const val MIN_ITEMS_FOR_EXPLORATION = 10
    private const val EXPLORE_COLD = 0.25
    private const val EXPLORE_NORMAL = 0.15
    private const val EXPLORE_RICH = 0.10
    private const val EXPLORE_FIRST_SLOT = 3
    private const val EXPLORE_STRIDE = 3
}
