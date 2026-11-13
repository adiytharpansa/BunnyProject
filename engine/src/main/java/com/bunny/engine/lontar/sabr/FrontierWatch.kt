package com.bunny.engine.lontar.sabr

/** Which pinned track a [WatchAction] concerns. */
enum class WatchTrack { AUDIO, VIDEO }

/**
 * One frontier-supervisor decision, carrying the numbers that
 * earned it so the diagnostics journal never has to guess.
 */
sealed interface WatchAction {
    object None : WatchAction

    /** The frontier froze briefly with a thin lead: spend the
     *  pump's current sleep and request again at once. */
    data class Nudge(
        val track: WatchTrack,
        val frozenMs: Long,
        val leadMs: Long,
    ) : WatchAction

    /** The frontier stayed frozen past the nudge: cut the pump
     *  and restart the session seeded at this track's frontier. */
    data class Restart(
        val track: WatchTrack,
        val frozenMs: Long,
        val leadMs: Long,
    ) : WatchAction
}

/**
 * SABR Adaptif Baru F1 — the frontier supervisor's pure brain.
 *
 * The 2026-11-13 18:07 device evidence: the spool frontier froze
 * at the exact byte the player waited for, with ±28 s of lead
 * still on disk, and nothing noticed for 12 s — detection lived
 * only at the player (the BUFFERING watchdog), and the pump's
 * stop was never named. This watcher moves detection to the
 * spool: a caller ticks it with each pinned track's frontier
 * (its timeline end), the playhead, and the road's readiness,
 * and it answers with graduated pressure — a [WatchAction.Nudge]
 * once a frontier has been frozen [nudgeAfterMs] with a lead
 * thinner than [thinLeadMs], a [WatchAction.Restart] once the
 * freeze outlives [restartAfterMs].
 *
 * Pure and clock-fed (every timestamp arrives as an argument),
 * so the whole state machine is unit-tested deterministically.
 * A frontier counts as alive whenever it CHANGES — forward roll
 * or a re-seeded session reporting a different end both reset
 * the freeze clock, so session restarts never read as stalls.
 * After every drive pass starts, [reset] grants [graceMs]
 * before any verdict: a fresh pass gets time to prove itself.
 * Cooldowns keep one freeze episode from machine-gunning the
 * drive; budgets live with the drive, which owns the passes.
 */
class FrontierWatch(
    private val nudgeAfterMs: Long = 2_500,
    private val restartAfterMs: Long = 5_000,
    private val thinLeadMs: Long = 15_000,
    private val nudgeCooldownMs: Long = 6_000,
    private val restartCooldownMs: Long = 10_000,
    private val graceMs: Long = 4_000,
) {
    private class TrackState {
        var seen: Boolean = false
        var lastEndMs: Long = 0
        var lastAdvanceAtMs: Long = 0
        var lastNudgeAtMs: Long = -1
        var lastRestartAtMs: Long = -1
    }

    private val audio = TrackState()
    private val video = TrackState()
    private var graceUntilMs: Long = 0

    /** A new drive pass started: frontiers get [graceMs] to move. */
    fun reset(nowMs: Long) {
        graceUntilMs = nowMs + graceMs
        audio.seen = false
        video.seen = false
    }

    fun tick(
        nowMs: Long,
        audioEndMs: Long,
        videoEndMs: Long,
        positionMs: Long,
        ready: Boolean,
    ): WatchAction {
        observe(audio, audioEndMs, nowMs)
        observe(video, videoEndMs, nowMs)
        if (!ready || nowMs < graceUntilMs) return WatchAction.None
        val candidates = listOfNotNull(
            candidate(audio, WatchTrack.AUDIO, audioEndMs, positionMs, nowMs),
            candidate(video, WatchTrack.VIDEO, videoEndMs, positionMs, nowMs),
        )
        // A restart outranks a nudge; within one kind, the
        // longer freeze wins.
        return candidates.maxWithOrNull(
            compareBy<WatchAction> { rank(it) }.thenBy { frozenOf(it) },
        ) ?: WatchAction.None
    }

    private fun rank(action: WatchAction): Int = when (action) {
        is WatchAction.Restart -> 2
        is WatchAction.Nudge -> 1
        WatchAction.None -> 0
    }

    private fun frozenOf(action: WatchAction): Long = when (action) {
        is WatchAction.Restart -> action.frozenMs
        is WatchAction.Nudge -> action.frozenMs
        WatchAction.None -> 0
    }

    private fun observe(state: TrackState, endMs: Long, nowMs: Long) {
        if (!state.seen) {
            state.seen = true
            state.lastEndMs = endMs
            state.lastAdvanceAtMs = nowMs
            return
        }
        if (endMs != state.lastEndMs) {
            state.lastEndMs = endMs
            state.lastAdvanceAtMs = nowMs
        }
    }

    private fun candidate(
        state: TrackState,
        track: WatchTrack,
        endMs: Long,
        positionMs: Long,
        nowMs: Long,
    ): WatchAction? {
        if (!state.seen || endMs <= 0) return null
        val leadMs = endMs - positionMs
        if (leadMs >= thinLeadMs) return null
        val frozenMs = nowMs - state.lastAdvanceAtMs
        if (frozenMs >= restartAfterMs &&
            (state.lastRestartAtMs < 0 ||
                nowMs - state.lastRestartAtMs >= restartCooldownMs)
        ) {
            state.lastRestartAtMs = nowMs
            return WatchAction.Restart(track, frozenMs, leadMs)
        }
        if (frozenMs >= nudgeAfterMs &&
            (state.lastNudgeAtMs < 0 ||
                nowMs - state.lastNudgeAtMs >= nudgeCooldownMs)
        ) {
            state.lastNudgeAtMs = nowMs
            return WatchAction.Nudge(track, frozenMs, leadMs)
        }
        return null
    }
}
