package com.bunny.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * The road memory's shelf (Fase 8 Langkah 6, mechanism 10): a
 * [RoadMemory] persisted as one JSON object in SharedPreferences,
 * in keeping with the repo's existing small stores
 * (WatchPositionStore, PrefsVisitorStore). Loaded once at
 * construction; every record rewrites the shelf — records are
 * rare (one per hard refusal, one per successful SABR open), so
 * the write cost is a non-event.
 *
 * Shape: `{ "<videoId>": {"o": "HARD", "t": 1730000000000} }`.
 * An unreadable shelf is an empty memory: the worst it can cost
 * is one fresh SABR attempt per video.
 */
@Singleton
class RoadMemoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val memory = RoadMemory()

    init {
        load()
    }

    /** The player's question before it offers the SABR road. */
    fun isHardRefused(videoId: String): Boolean = memory.isHardRefused(videoId)

    /** The engine's two hard facts land here (via its refusal hook). */
    fun recordHard(videoId: String) {
        memory.record(videoId, RoadMemory.Outcome.HARD)
        persist()
    }

    /** A SABR road that opened and played replaces any refusal. */
    fun recordOk(videoId: String) {
        memory.record(videoId, RoadMemory.Outcome.OK)
        persist()
    }

    private fun load() {
        val raw = prefs().getString(KEY_ENTRIES, null) ?: return
        try {
            val root = JSONObject(raw)
            val loaded = LinkedHashMap<String, RoadMemory.Entry>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val videoId = keys.next()
                val o = root.getJSONObject(videoId)
                val outcome = RoadMemory.Outcome.valueOf(o.getString("o"))
                loaded[videoId] = RoadMemory.Entry(outcome, o.getLong("t"))
            }
            memory.loadSnapshot(loaded)
        } catch (e: Exception) {
            // A corrupt shelf vouches for nothing (see class docs).
        }
    }

    private fun persist() {
        val root = JSONObject()
        for ((videoId, entry) in memory.snapshot()) {
            root.put(
                videoId,
                JSONObject()
                    .put("o", entry.outcome.name)
                    .put("t", entry.atMs),
            )
        }
        prefs().edit().putString(KEY_ENTRIES, root.toString()).apply()
    }

    private fun prefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "bunny_road_memory"
        private const val KEY_ENTRIES = "entries"
    }
}
