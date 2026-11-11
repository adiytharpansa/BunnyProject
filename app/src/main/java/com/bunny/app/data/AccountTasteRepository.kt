package com.bunny.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profil Selera Akun (RANCANGAN-BERANDA-SELERA-AKUN, 2026-11-12):
 * the signed-in account's taste, REBUILT on-device from the two
 * signals the official Data API legitimately serves — the
 * account's subscriptions and the videos it liked. This is not
 * YouTube's server profile and is never presented as such; watch
 * history stays unread (no third party can read it).
 *
 * The profile is derived data, not a credential: interest terms
 * with weights, the account's channels, its top categories. It
 * lives in plain preferences, refreshes at most once a day, and
 * is wiped on sign-out.
 */
@Singleton
class AccountTasteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class AccountTaste(
        /** Interest words/phrases → accumulated weight. */
        val terms: Map<String, Int>,
        /** Channels the account subscribes to. */
        val subscribedChannelIds: Set<String>,
        /** Channels behind videos the account liked. */
        val likedChannelIds: Set<String>,
        val updatedAtMillis: Long,
    )

    @Volatile
    private var cached: AccountTaste? = null
    private var loaded: Boolean = false

    fun profile(): AccountTaste? {
        if (!loaded) {
            cached = read()
            loaded = true
        }
        return cached
    }

    /**
     * Rebuilds the profile when the stored one is older than a
     * day (or absent). Returns the current profile either way;
     * failures keep the previous profile — taste is enrichment
     * for ranking, never a reason to fail a screen.
     */
    suspend fun refreshIfStale(
        api: YouTubeDataApi,
        token: String,
    ): AccountTaste? {
        val current = profile()
        val now = System.currentTimeMillis()
        if (current != null && now - current.updatedAtMillis < DAY_MS) {
            return current
        }
        return try {
            val subs = api.subscriptions(token)
            val liked = api.liked(token, max = 50)
            val terms = mutableMapOf<String, Int>()
            fun add(term: String, weight: Int) {
                val clean = term.trim().lowercase()
                if (clean.length >= 3) {
                    terms[clean] = (terms[clean] ?: 0) + weight
                }
            }
            for (video in liked) {
                for (tag in video.tags) add(tag, TAG_WEIGHT)
                for (word in video.title.lowercase().split(WORD_SPLIT)) {
                    add(word, TITLE_WEIGHT)
                }
                CATEGORY_TERMS[video.categoryId]?.forEach {
                    add(it, CATEGORY_WEIGHT)
                }
            }
            for (sub in subs) {
                for (word in sub.title.lowercase().split(WORD_SPLIT)) {
                    add(word, CHANNEL_WEIGHT)
                }
            }
            val built = AccountTaste(
                terms = terms.entries
                    .sortedByDescending { it.value }
                    .take(MAX_TERMS)
                    .associate { it.key to it.value },
                subscribedChannelIds = subs.map { it.channelId }.toSet(),
                likedChannelIds = liked.map { it.channelId }.toSet(),
                updatedAtMillis = now,
            )
            cached = built
            write(built)
            built
        } catch (e: Exception) {
            current
        }
    }

    fun clear() {
        cached = null
        loaded = true
        prefs().edit().remove(KEY_PROFILE).apply()
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun write(taste: AccountTaste) {
        val terms = JSONObject()
        for ((key, value) in taste.terms) terms.put(key, value)
        val root = JSONObject()
            .put("terms", terms)
            .put(
                "subs",
                JSONArray().apply {
                    taste.subscribedChannelIds.forEach { put(it) }
                },
            )
            .put(
                "liked",
                JSONArray().apply {
                    taste.likedChannelIds.forEach { put(it) }
                },
            )
            .put("updatedAt", taste.updatedAtMillis)
        prefs().edit().putString(KEY_PROFILE, root.toString()).apply()
    }

    private fun read(): AccountTaste? {
        val raw = prefs().getString(KEY_PROFILE, null) ?: return null
        return try {
            val root = JSONObject(raw)
            val termsObj = root.optJSONObject("terms") ?: JSONObject()
            val terms = mutableMapOf<String, Int>()
            val keys = termsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                terms[key] = termsObj.optInt(key)
            }
            fun ids(name: String): Set<String> {
                val array = root.optJSONArray(name) ?: return emptySet()
                return (0 until array.length())
                    .map { array.optString(it) }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
            AccountTaste(
                terms = terms,
                subscribedChannelIds = ids("subs"),
                likedChannelIds = ids("liked"),
                updatedAtMillis = root.optLong("updatedAt"),
            )
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "bunny_account_taste"
        private const val KEY_PROFILE = "profile"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_TERMS = 60
        private const val TAG_WEIGHT = 3
        private const val TITLE_WEIGHT = 2
        private const val CATEGORY_WEIGHT = 2
        private const val CHANNEL_WEIGHT = 1
        private val WORD_SPLIT = Regex("[^a-z0-9à-ÿ]+")

        /**
         * YouTube category id → interest words in the two languages
         * the account's titles actually use. Categories enter the
         * profile as words because Home candidates carry no
         * category field of their own to match an id against.
         */
        private val CATEGORY_TERMS: Map<String, List<String>> = mapOf(
            "1" to listOf("film", "animasi", "animation"),
            "2" to listOf("otomotif", "mobil", "vehicles"),
            "10" to listOf("musik", "music", "lagu"),
            "15" to listOf("hewan", "pets", "animals"),
            "17" to listOf("olahraga", "sports", "bola"),
            "19" to listOf("wisata", "travel", "jalan-jalan"),
            "20" to listOf("gaming", "game", "games"),
            "22" to listOf("vlog", "blog"),
            "23" to listOf("komedi", "comedy", "lucu"),
            "24" to listOf("hiburan", "entertainment"),
            "25" to listOf("berita", "news"),
            "26" to listOf("tutorial", "cara", "howto"),
            "27" to listOf("pendidikan", "education", "belajar"),
            "28" to listOf("sains", "teknologi", "science", "tech"),
            "29" to listOf("aktivisme", "nonprofit"),
            "34" to listOf("komedi", "comedy"),
        )
    }
}
