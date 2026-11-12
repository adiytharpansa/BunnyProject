package com.bunny.engine.lontar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Mechanism 20 (Langkah 6): the player-response cache must stop
 * serving an answer before the URLs inside it die — expiry minus
 * the safety margin — and must fall back to an honest default
 * lifetime when the response states none.
 */
class PlayerResponseCacheTest {

    private var now = 1_000_000L

    private val persona = PlayerPersona(
        name = "VISIONOS",
        host = "youtubei.googleapis.com",
        apiKey = "key",
        clientNameId = "101",
        userAgent = "ua",
        clientFields = mapOf("clientVersion" to "1.04"),
    )

    private fun cache(maxEntries: Int = 100) = PlayerResponseCache(
        maxEntries = maxEntries,
        safetyMarginMs = 10_000L,
        defaultLifetimeMs = 60_000L,
        nowMs = { now },
    )

    @Test
    fun freshEntryIsReused() {
        val c = cache()
        val response = JSONObject()
        c.put("vid", response, persona, expiresInSeconds = 3600)
        now += 60_000
        val hit = c.get("vid")
        assertSame(response, hit?.first)
        assertSame(persona, hit?.second)
    }

    @Test
    fun entryGoesStaleAtExpiryMinusTheMargin() {
        val c = cache()
        c.put("vid", JSONObject(), persona, expiresInSeconds = 3600)
        // Lifetime is 3600 s − 10 s margin = 3590 s.
        now += 3_589_000
        assertSame(persona, c.get("vid")?.second)
        now += 1_000
        assertNull("at expiry − margin the entry must be re-asked", c.get("vid"))
        assertEquals(0, c.size)
    }

    @Test
    fun absentExpiryUsesTheDefaultLifetime() {
        val c = cache()
        c.put("vid", JSONObject(), persona, expiresInSeconds = null)
        now += 59_000
        assertSame(persona, c.get("vid")?.second)
        now += 1_000
        assertNull(c.get("vid"))
    }

    @Test
    fun expiryUnderTheMarginIsNeverServed() {
        val c = cache()
        // A stated lifetime shorter than the safety margin leaves
        // nothing honest to serve: stale on first read.
        c.put("vid", JSONObject(), persona, expiresInSeconds = 5)
        assertNull(c.get("vid"))
    }

    @Test
    fun invalidateDropsTheEntry() {
        val c = cache()
        c.put("vid", JSONObject(), persona, expiresInSeconds = 3600)
        c.invalidate("vid")
        assertNull(c.get("vid"))
        assertEquals(0, c.size)
    }

    @Test
    fun capacityClearsTheWholeMap() {
        val c = cache(maxEntries = 2)
        c.put("a", JSONObject(), persona, expiresInSeconds = 3600)
        c.put("b", JSONObject(), persona, expiresInSeconds = 3600)
        c.put("c", JSONObject(), persona, expiresInSeconds = 3600)
        assertNull(c.get("a"))
        assertNull(c.get("b"))
        assertSame(persona, c.get("c")?.second)
        assertEquals(1, c.size)
    }
}
