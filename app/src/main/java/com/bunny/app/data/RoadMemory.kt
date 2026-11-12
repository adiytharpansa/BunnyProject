package com.bunny.app.data

/**
 * Fase 8 Langkah 6, mechanism 10 — the per-video road memory: a
 * small notebook of what the SABR road ANSWERED for a video, so
 * an open does not keep paying for a refusal that is a property
 * of the video itself.
 *
 * Only two outcomes exist, and only one of them acts:
 * - [Outcome.HARD] — the road refused the video outright: the
 *   player response carried no SABR offer for it, or the server
 *   demanded a token for it (SPS 3). While a HARD entry is fresh
 *   (inside [TTL_MS]), the player skips the SABR attempt and goes
 *   straight to the classic road.
 * - [Outcome.OK] — the road opened and played. Recorded so a
 *   proven road replaces an older refusal, and kept as evidence;
 *   it never skips anything.
 *
 * Soft failures are NOT an outcome here — deliberately. Transport
 * errors, HTTP refusals, stalls: those are the road's weather,
 * not the video's verdict, and remembering them would punish
 * videos for a bad network minute. They never reach [record]
 * (the engine reports only the two hard facts — see
 * LontarEngine.onSabrHardRefusal), and this class offers no way
 * to write them.
 *
 * Bounded like the engine's caches: at [MAX_ENTRIES] the whole
 * map clears — a courtesy notebook, not an archive. The clock is
 * injected so the TTL and cap are pure JVM-testable; persistence
 * (SharedPreferences JSON) lives in [RoadMemoryStore], this class
 * only ever speaks in snapshots.
 */
class RoadMemory(
    private val ttlMs: Long = TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Outcome { HARD, OK }

    data class Entry(val outcome: Outcome, val atMs: Long)

    private val entries = LinkedHashMap<String, Entry>()

    /** Live entries right now (test visibility). */
    val size: Int get() = entries.size

    @Synchronized
    fun record(videoId: String, outcome: Outcome) {
        if (videoId !in entries && entries.size >= maxEntries) {
            entries.clear()
        }
        entries[videoId] = Entry(outcome, nowMs())
    }

    /**
     * The remembered outcome for [videoId], or null when nothing
     * is remembered or the memory has outlived the TTL (an expired
     * entry is dropped as it is found — the road gets its fresh
     * chance exactly once per TTL window).
     */
    @Synchronized
    fun outcome(videoId: String): Outcome? {
        val entry = entries[videoId] ?: return null
        if (nowMs() - entry.atMs >= ttlMs) {
            entries.remove(videoId)
            return null
        }
        return entry.outcome
    }

    /** The one question the player asks: skip the SABR road? */
    fun isHardRefused(videoId: String): Boolean =
        outcome(videoId) == Outcome.HARD

    @Synchronized
    fun snapshot(): Map<String, Entry> = LinkedHashMap(entries)

    @Synchronized
    fun loadSnapshot(values: Map<String, Entry>) {
        entries.clear()
        entries.putAll(values)
    }

    companion object {
        /** A refusal is remembered for a week, then re-earned. */
        const val TTL_MS: Long = 7L * 24 * 3600 * 1000
        const val MAX_ENTRIES: Int = 200
    }
}
