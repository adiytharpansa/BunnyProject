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
 *
 * Service is RANGE-based (mechanism 23, full form): a position
 * inside ANY interval [TrackSpool.writtenRanges] vouches for is
 * served up to that interval's end — a spool whose middle was
 * seeded by a jump pass serves its second region while the first
 * gap is still being backfilled. A position in a gap, or past
 * every range, waits while the road lives; on a completed track
 * it is end-of-input instead — a finished spool's gap is a
 * terminal fact, not a wait.
 */
class SpoolReader(
    private val track: TrackSpool,
    private val road: SpoolRoadState,
    private val onWait: ((itag: Int, position: Long, frontier: Long) -> Unit)? = null,
    /**
     * Anti-buffering design M1: fired ONCE per wait episode when a
     * single continuous wait passes [LONG_WAIT_MS] — with the spool
     * file's length alongside the frontier, so a diagnostics line can
     * tell a far-ahead probe (position far beyond both = an extractor
     * hunting tail metadata, e.g. WebM Cues) from plain sequential
     * starvation (position == frontier). Resets when data lands.
     */
    private val onLongWait: (
        (itag: Int, position: Long, frontier: Long, fileLength: Long) -> Unit
    )? = null,
) {
    @Volatile
    private var closed = false

    private var waitReported = false

    /** NanoTime the current continuous wait began; -1 while fed. */
    private var waitStartedNanos: Long = -1

    private var longWaitReported = false

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
     * [offset]; returns the count actually read (never more than
     * the vouched range holding [position] offers), or -1 when
     * [position] is at/past the end of a completed track or inside
     * a completed track's gap. Blocks while the needed bytes are
     * in flight.
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (closed) throw IOException("Pembaca gulungan ditutup")
            road.checkAlive()
            val ranges = track.writtenRanges()
            val range = ranges.firstOrNull {
                position >= it.first && position <= it.last
            }
            if (range != null) {
                // Fed: any wait episode in progress is over.
                waitStartedNanos = -1
                longWaitReported = false
                val count = minOf(length.toLong(), range.last + 1 - position).toInt()
                val data = track.read(position, count)
                data.copyInto(buffer, offset)
                return data.size
            }
            if (road.isComplete(track.itag)) return -1
            // The frontier a wait at [position] is reported
            // against: the greatest vouched end at or before it.
            // In the sequential case (one range from zero) that
            // IS the old frontier, so the diagnostics lines keep
            // their proven meaning; behind a jump-seeded second
            // region it names the end of the region the position
            // actually follows.
            val frontier = ranges
                .filter { it.last + 1 <= position }
                .maxOfOrNull { it.last + 1 } ?: 0L
            if (waitStartedNanos < 0) waitStartedNanos = System.nanoTime()
            if (!waitReported) {
                // The first wait of this reader, reported once: the
                // position it waits at, against the frontier at that
                // moment, exposes speculative far-ahead probes (an
                // extractor hunting for tail metadata) that a purely
                // sequential reader would never make.
                waitReported = true
                onWait?.invoke(track.itag, position, frontier)
            }
            if (!longWaitReported && onLongWait != null &&
                System.nanoTime() - waitStartedNanos >= LONG_WAIT_NANOS
            ) {
                // M1: this wait stopped being a hiccup — name it,
                // once per episode, with the file's length so the
                // gap between the waited position and everything
                // the disk holds is visible at a glance.
                longWaitReported = true
                onLongWait.invoke(track.itag, position, frontier, track.fileLength())
            }
            road.awaitPulse(POLL_INTERVAL_MS)
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 50L

        /** M1: a single wait longer than this is a named event. */
        const val LONG_WAIT_MS = 2_000L
        const val LONG_WAIT_NANOS = LONG_WAIT_MS * 1_000_000L
    }
}
