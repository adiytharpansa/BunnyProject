package com.bunny.engine.lontar

import java.util.concurrent.ConcurrentHashMap

/**
 * Paket Mesin B (approved by the user 2026-11-12): a short-lived,
 * in-process cache for metadata answers — the same endpoint with the
 * same parameters inside a small window is served from memory
 * instead of re-asking the server. The walk that profits most is
 * Fase 7-B's next-watch chain, which re-asks "related" for the same
 * videos repeatedly; channel pages and repeated searches follow.
 *
 * Honesty rules, mirroring the player-response cache beside it:
 * - Only REAL, successful answers are stored — a fetch that throws
 *   stores nothing, and a later call asks the server again.
 * - Entries die at [ttlMs]; nothing here pretends to be fresher
 *   than it is, and playback streams are NOT cached here (their
 *   URLs age on their own clock — the player cache owns those).
 * - Bounded: at [maxEntries] the whole map clears, the same
 *   courtesy-not-archive policy as the player cache. This is a
 *   courtesy for repeat questions, not a store.
 */
class MetadataCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry(val value: Any?, val storedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Test and diagnostics visibility: live entries right now. */
    val size: Int get() = entries.size

    /**
     * Serves [key]'s answer from the cache when a fresh one exists,
     * otherwise runs [fetch], stores its result, and returns it.
     * Exceptions from [fetch] propagate and store nothing.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: String, fetch: suspend () -> T): T {
        val hit = entries[key]
        if (hit != null && nowMs() - hit.storedAtMs < ttlMs) {
            return hit.value as T
        }
        val fresh = fetch()
        if (entries.size >= maxEntries) entries.clear()
        entries[key] = Entry(fresh, nowMs())
        return fresh
    }

    /** Drops one key (a caller that learned the answer changed). */
    fun invalidate(key: String) {
        entries.remove(key)
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        /** The approved window: a few minutes, no more. */
        const val DEFAULT_TTL_MS: Long = 180_000L
        const val DEFAULT_MAX_ENTRIES: Int = 64
    }
}
