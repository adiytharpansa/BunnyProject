package com.bunny.engine.lontar

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Fase 8 Langkah 6, mechanism 20 — the SABR URL expiry watch,
 * generalised to every consumer of a /player response: the URLs
 * inside a player response (the SABR streaming URL and the classic
 * direct URLs alike) are issued with a lifetime —
 * `streamingData.expiresInSeconds`, ~6 hours in the proving
 * captures of 2026-11-12. A cache that ignores that clock serves
 * dead URLs hours later and misreads the refusal as a SABR or
 * persona failure.
 *
 * So an entry here carries its fetch time and the expiry the
 * server stated, and [get] treats the entry as GONE once its age
 * passes (expiry − [SAFETY_MARGIN_MS]): the margin is the time a
 * playback may still need from the URLs, so the refetch happens
 * while the old answer could still have served, never after it
 * died mid-play. A response that states no expiry gets
 * [DEFAULT_LIFETIME_MS] — deliberately far under the observed
 * ~6 hours: an unstated lifetime is not evidence of a long one.
 * An expiry at or under the margin yields a non-positive
 * lifetime, so the entry is stale the moment it is read — the
 * conservative reading, again.
 *
 * The cache itself is the old player cache's policy, unchanged:
 * only accepted answers are stored (the caller puts nothing
 * else), and at [maxEntries] the whole map clears — a courtesy,
 * not an archive. The clock is injected, following [MetadataCache].
 */
class PlayerResponseCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val safetyMarginMs: Long = SAFETY_MARGIN_MS,
    private val defaultLifetimeMs: Long = DEFAULT_LIFETIME_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private class Entry(
        val response: JSONObject,
        val persona: PlayerPersona,
        val fetchedAtMs: Long,
        /** How long after the fetch this entry may be served. */
        val lifetimeMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Test and diagnostics visibility: live entries right now. */
    val size: Int get() = entries.size

    /**
     * The cached answer for [videoId], or null when there is none
     * or the one there is has aged past its honest lifetime (a
     * stale entry is removed as it is found — the caller's refetch
     * then replaces it through [put]).
     */
    fun get(videoId: String): Pair<JSONObject, PlayerPersona>? {
        val entry = entries[videoId] ?: return null
        if (nowMs() - entry.fetchedAtMs >= entry.lifetimeMs) {
            entries.remove(videoId, entry)
            return null
        }
        return entry.response to entry.persona
    }

    /**
     * Stores an ACCEPTED player answer. [expiresInSeconds] is the
     * lifetime the server stated for the response's URLs (null
     * when the response did not say — the default lifetime then
     * applies, see the class docs).
     */
    fun put(
        videoId: String,
        response: JSONObject,
        persona: PlayerPersona,
        expiresInSeconds: Long?,
    ) {
        val lifetimeMs = if (expiresInSeconds != null && expiresInSeconds > 0) {
            expiresInSeconds * 1000 - safetyMarginMs
        } else {
            defaultLifetimeMs
        }
        if (entries.size >= maxEntries) entries.clear()
        entries[videoId] = Entry(response, persona, nowMs(), lifetimeMs)
    }

    /** Drops one video's answer (a caller that learned it died). */
    fun invalidate(videoId: String) {
        entries.remove(videoId)
    }

    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 100

        /** URLs are re-asked 10 minutes before they would die. */
        const val SAFETY_MARGIN_MS: Long = 600_000L

        /** The honest lifetime of a response that states none. */
        const val DEFAULT_LIFETIME_MS: Long = 3_600_000L
    }
}
