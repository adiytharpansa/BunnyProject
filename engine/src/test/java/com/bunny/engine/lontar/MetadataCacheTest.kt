package com.bunny.engine.lontar

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Paket Mesin B: the short-window metadata cache serves a repeated
 * question from memory, asks again after the window, never stores a
 * failure, and keeps keys apart.
 */
class MetadataCacheTest {

    @Test
    fun secondCallInsideTheWindowSkipsTheFetch() = runBlocking {
        var clock = 1_000_000L
        val cache = MetadataCache(ttlMs = 180_000L, nowMs = { clock })
        var fetches = 0
        val first = cache.get("related:abc") { fetches++; "jawaban-$fetches" }
        clock += 60_000L
        val second = cache.get("related:abc") { fetches++; "jawaban-$fetches" }
        assertEquals("jawaban-1", first)
        assertEquals("jawaban-1", second)
        assertEquals(1, fetches)
    }

    @Test
    fun callAfterTheWindowFetchesAgain() = runBlocking {
        var clock = 0L
        val cache = MetadataCache(ttlMs = 180_000L, nowMs = { clock })
        var fetches = 0
        cache.get("k") { fetches++; fetches }
        clock += 180_001L
        val again = cache.get("k") { fetches++; fetches }
        assertEquals(2, again)
        assertEquals(2, fetches)
    }

    @Test
    fun failuresAreNotCached() = runBlocking {
        val cache = MetadataCache(nowMs = { 0L })
        var attempts = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                cache.get<String>("k") { attempts++; throw IllegalStateException("gagal") }
            }
        }
        val ok = cache.get("k") { attempts++; "pulih" }
        assertEquals("pulih", ok)
        assertEquals(2, attempts)
        assertEquals(1, cache.size)
    }

    @Test
    fun keysStayApartAndInvalidateWorks() = runBlocking {
        val cache = MetadataCache(nowMs = { 0L })
        assertEquals("a", cache.get("related:a") { "a" })
        assertEquals("b", cache.get("related:b") { "b" })
        cache.invalidate("related:a")
        assertEquals("a2", cache.get("related:a") { "a2" })
        assertEquals("b", cache.get("related:b") { "b-lain" })
    }

    @Test
    fun fullCacheClearsRatherThanGrowingForever() = runBlocking {
        val cache = MetadataCache(maxEntries = 2, nowMs = { 0L })
        cache.get("1") { 1 }
        cache.get("2") { 2 }
        cache.get("3") { 3 }
        // Storing the third entry into a full cache cleared the map
        // first (courtesy, not archive): only the newest survives.
        assertEquals(1, cache.size)
        assertEquals(3, cache.get("3") { -1 })
    }
}
