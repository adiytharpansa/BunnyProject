package com.bunny.engine.lontar.sabr

import java.io.Closeable

/**
 * Mechanism 14 — the classic road's spool recorder: one per
 * classic playback, created by [SabrPlaybackCoordinator]. The
 * player downloads the generated-DASH representations through a
 * [TeeDataSource]; the tee reports the bytes to this recorder's
 * [sink], and the recorder writes them into the video's spool at
 * their absolute offsets and notes the covered ranges as the
 * track's classic sidecar (see [TrackSpool.classicRanges]).
 *
 * Whether a track may be recorded is decided ONCE, here, per
 * itag, from the spool manifest's own testimony — the trust rules
 * the rendition-switch finding (wire evidence 2026-11-13) made
 * necessary, because classic bytes live in the classic rendition's
 * byte space:
 * - a track with NO SABR-authored state (no init, no segments)
 *   is open: its file holds nothing the classic bytes could
 *   contradict;
 * - a track WITH SABR state is open only when the manifest's
 *   wire rendition equals the classic rendition for this itag
 *   AND the manifest's anchor delta is zero — same byte space,
 *   same offsets. A manifest whose wire rendition is unset (0)
 *   counts as stateless ONLY when it also holds no init and no
 *   segments; with state but no named rendition, nothing can be
 *   proven, and unprovable means refused.
 * A refused track is a no-op for playback: the tee still runs (or
 * is never installed), the bytes simply have nowhere recorded to
 * go.
 *
 * The rendition the accepted ranges belong to is stamped onto the
 * track ([TrackSpool.noteClassicRendition]) before the first byte
 * lands. A track already stamped with a DIFFERENT classic
 * rendition has that era's ranges cleared first: two classic
 * renditions are two byte spaces, and their ranges must never
 * share one ledger (SABR re-authorship clears them the same way —
 * see the invariant on [TrackSpool.noteSegment]).
 */
class ClassicSpoolRecorder(
    private val store: SpoolStore,
    val spool: VideoSpool,
    renditions: Map<Int, Long>,
) : Closeable {

    private val tracks = LinkedHashMap<Int, TrackSpool>()

    /** The itags the gate let through — empty means this recorder
     * records nothing at all. */
    val recordableItags: Set<Int>

    @Volatile
    private var closed = false

    init {
        for ((itag, lastModified) in renditions) {
            if (lastModified <= 0) continue
            val track = spool.track(itag)
            if (!gateAllows(track, lastModified)) continue
            if (track.classicRendition != 0L &&
                track.classicRendition != lastModified
            ) {
                track.clearClassicRanges()
            }
            track.noteClassicRendition(lastModified)
            tracks[itag] = track
        }
        recordableItags = tracks.keys.toSet()
    }

    /**
     * The sink the tee reports to. Refused itags and a closed
     * recorder are silent no-ops; the write and the range note are
     * the whole act — bytes at their absolute offset, coverage in
     * the sidecar, nothing claimed anywhere else.
     */
    val sink: (itag: Int, position: Long, data: ByteArray) -> Unit =
        { itag, position, data ->
            if (!closed && data.isNotEmpty()) {
                val track = tracks[itag]
                if (track != null) {
                    track.write(position, data)
                    track.noteClassicRange(position, data.size.toLong())
                }
            }
        }

    /**
     * Flushes the touched tracks' manifests (the sidecar becomes
     * durable here) and returns the spool to the store's budget
     * discipline, protecting this video's own spool.
     */
    override fun close() {
        if (closed) return
        closed = true
        for (track in tracks.values) track.flushManifest()
        spool.close()
        store.enforceBudget(protectVideoId = spool.videoId)
    }

    private companion object {
        /** The per-track gate, decided from the manifest alone. */
        fun gateAllows(track: TrackSpool, classicLastModified: Long): Boolean {
            val hasSabrState = track.initLength > 0 || track.segments.isNotEmpty()
            if (!hasSabrState) return true
            return track.wireRendition == classicLastModified &&
                track.currentDelta == 0L
        }
    }
}
