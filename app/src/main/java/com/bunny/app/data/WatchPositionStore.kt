package com.bunny.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resume positions (adapted from the user's player document §4,
 * 2026-11-12 — SharedPreferences instead of a new DataStore
 * dependency, in keeping with the repo's existing stores). The
 * document's rules are kept: a position under five seconds is
 * not worth resuming, and a position within the last ten seconds
 * counts as finished, so both are forgotten instead of stored.
 * The shelf is capped; the oldest positions are evicted first.
 */
@Singleton
class WatchPositionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun get(videoId: String): Long =
        prefs().getLong(posKey(videoId), 0L)

    fun save(videoId: String, positionMs: Long, durationMs: Long) {
        val nearEnd = durationMs > 0 && positionMs >= durationMs - 10_000
        if (positionMs < 5_000 || nearEnd) {
            clear(videoId)
            return
        }
        val editor = prefs().edit()
            .putLong(posKey(videoId), positionMs)
            .putLong(timeKey(videoId), System.currentTimeMillis())
        editor.apply()
        prune()
    }

    fun clear(videoId: String) {
        prefs().edit()
            .remove(posKey(videoId))
            .remove(timeKey(videoId))
            .apply()
    }

    private fun prune() {
        val all = prefs().all
        val stamps = all.keys
            .filter { it.startsWith(PREFIX_TIME) }
            .mapNotNull { key -> all[key]?.let { it as? Long }?.let { key to it } }
        if (stamps.size <= MAX_ENTRIES) return
        val editor = prefs().edit()
        stamps.sortedBy { it.second }
            .take(stamps.size - MAX_ENTRIES)
            .forEach { (timeKey, _) ->
                val id = timeKey.removePrefix(PREFIX_TIME)
                editor.remove(timeKey).remove(posKey(id))
            }
        editor.apply()
    }

    private fun posKey(id: String) = PREFIX_POS + id
    private fun timeKey(id: String) = PREFIX_TIME + id

    private fun prefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "bunny_positions"
        private const val PREFIX_POS = "p_"
        private const val PREFIX_TIME = "t_"
        private const val MAX_ENTRIES = 200
    }
}
