package com.bunny.engine.lontar.sabr

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The road's shared pulse — one per playing video. The pump writes;
 * readers wait here. Death is a fact with a reason (token demanded,
 * SABR_ERROR, transport failure, playback replaced): a reader that
 * learns the road died throws [IOException], which is exactly the
 * signal ExoPlayer needs to fall through to the next source in its
 * chain — the classic road, over the same spool (design mechanism 1).
 */
class SpoolRoadState {
    private val lock = Object()

    @Volatile
    private var deathReason: String? = null

    private val completedTracks = ConcurrentHashMap.newKeySet<Int>()

    fun kill(reason: String) {
        synchronized(lock) {
            if (deathReason == null) deathReason = reason
            lock.notifyAll()
        }
    }

    fun markComplete(itag: Int) {
        synchronized(lock) {
            completedTracks.add(itag)
            lock.notifyAll()
        }
    }

    fun isComplete(itag: Int): Boolean = itag in completedTracks

    fun isDead(): Boolean = deathReason != null

    /** Throws when the road has died; the reason rides along. */
    fun checkAlive() {
        deathReason?.let { throw IOException("Jalan SABR berhenti: $it") }
    }

    /** Wakes every waiting reader (new bytes, completion, death). */
    fun pulse() {
        synchronized(lock) { lock.notifyAll() }
    }

    /** Waits for the next pulse, at most [timeoutMs]. */
    fun awaitPulse(timeoutMs: Long) {
        synchronized(lock) {
            if (deathReason == null) lock.wait(timeoutMs)
        }
    }
}

/**
 * The pure byte-serving core behind [SabrDataSource]: serves a spool
 * track at absolute positions while the pump is still filling it.
 *
 * The contract the player relies on (design Langkah 3's traps):
 * - A read at the arrived frontier WAITS — a missing range is a
 *   need for the pump, not an error.
 * - A read on a completed track past its end is end-of-input (-1).
 * - A dead road turns every pending and future read into an
 *   [IOException] — never a silent stall, never fabricated bytes.
 */
class SpoolReader(
    private val track: TrackSpool,
    private val road: SpoolRoadState,
    private val onWait: ((itag: Int, position: Long, frontier: Long) -> Unit)? = null,
) {
    @Volatile
    private var closed = false

    private var waitReported = false

    fun close() {
        closed = true
    }

    /** The track's full length once it has completely arrived, else -1. */
    fun knownLength(): Long {
        road.checkAlive()
        return if (road.isComplete(track.itag)) track.fileLength() else -1
    }

    /**
     * Reads up to [length] bytes at [position] into [buffer] at
     * [offset]; returns the count actually read (never more than what
     * has arrived), or -1 when [position] is at/past the end of a
     * completed track. Blocks while the needed bytes are in flight.
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (closed) throw IOException("Pembaca gulungan ditutup")
            road.checkAlive()
            val frontier = track.frontier()
            if (position < frontier) {
                val count = minOf(length.toLong(), frontier - position).toInt()
                val data = track.read(position, count)
                data.copyInto(buffer, offset)
                return data.size
            }
            if (road.isComplete(track.itag)) return -1
            if (!waitReported) {
                // The first wait of this reader, reported once: the
                // position it waits at, against the frontier at that
                // moment, exposes speculative far-ahead probes (an
                // extractor hunting for tail metadata) that a purely
                // sequential reader would never make.
                waitReported = true
                onWait?.invoke(track.itag, position, frontier)
            }
            road.awaitPulse(POLL_INTERVAL_MS)
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 50L
    }
}
