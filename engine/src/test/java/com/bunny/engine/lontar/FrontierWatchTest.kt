package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.FrontierWatch
import com.bunny.engine.lontar.sabr.WatchAction
import com.bunny.engine.lontar.sabr.WatchTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SABR Adaptif Baru F1: the frontier supervisor's state machine,
 * tick by deterministic tick — advancing frontiers stay silent,
 * a frozen frontier with a thin lead earns a nudge then a
 * restart, a fat lead earns nothing, and a re-seeded frontier
 * (a session restart changing the reported end) counts as life,
 * never as a stall.
 */
class FrontierWatchTest {

    /** One second of ticks from [fromMs] to [toMs], ends fixed. */
    private fun tickRange(
        watch: FrontierWatch,
        fromMs: Long,
        toMs: Long,
        audioEndMs: Long,
        videoEndMs: Long,
        positionMs: Long,
        ready: Boolean = true,
    ): List<WatchAction> {
        val out = ArrayList<WatchAction>()
        var t = fromMs
        while (t <= toMs) {
            out += watch.tick(t, audioEndMs, videoEndMs, positionMs, ready)
            t += 500
        }
        return out
    }

    @Test
    fun advancingFrontiersStaySilent() {
        val watch = FrontierWatch()
        watch.reset(0)
        var end = 0L
        var t = 0L
        repeat(40) {
            end += 1_000
            val action = watch.tick(t, end, end, positionMs = 0, ready = true)
            assertEquals(WatchAction.None, action)
            t += 500
        }
    }

    @Test
    fun frozenThinLeadEarnsNudgeThenRestart() {
        val watch = FrontierWatch()
        watch.reset(0)
        // Audio frozen at 60 s with the playhead at 55 s (lead
        // 5 s — thin); video keeps the same end but the verdict
        // must name AUDIO, the frozen one... both ends are equal
        // here, so freeze video too and let the tie break by kind.
        // First sighting at t=0 sets the advance clock.
        assertEquals(
            WatchAction.None,
            watch.tick(0, 60_000, 90_000, 55_000, ready = true),
        )
        // Grace runs to 4 s; at 4.5 s the freeze is 4.5 s old —
        // past the nudge line, short of the restart line.
        val at45 = watch.tick(4_500, 60_000, 90_500, 55_000, ready = true)
        assertTrue(at45 is WatchAction.Nudge)
        assertEquals(WatchTrack.AUDIO, (at45 as WatchAction.Nudge).track)
        // At exactly 5 s frozen the restart line is crossed.
        val at50 = watch.tick(5_000, 60_000, 91_000, 55_000, ready = true)
        assertTrue(at50 is WatchAction.Restart)
        assertEquals(WatchTrack.AUDIO, (at50 as WatchAction.Restart).track)
        // Restart cooldown (and the nudge's) silence the next tick.
        assertEquals(
            WatchAction.None,
            watch.tick(5_500, 60_000, 91_500, 55_000, ready = true),
        )
    }

    @Test
    fun frozenFatLeadEarnsNothing() {
        val watch = FrontierWatch()
        watch.reset(0)
        // Frontier frozen at 120 s, playhead at 55 s: 65 s of
        // lead — a freeze here is no emergency.
        val actions = tickRange(
            watch, 0, 12_000,
            audioEndMs = 120_000, videoEndMs = 120_000,
            positionMs = 55_000,
        )
        assertTrue(actions.all { it == WatchAction.None })
    }

    @Test
    fun notReadyStaysSilent() {
        val watch = FrontierWatch()
        watch.reset(0)
        val actions = tickRange(
            watch, 0, 12_000,
            audioEndMs = 60_000, videoEndMs = 60_000,
            positionMs = 55_000, ready = false,
        )
        assertTrue(actions.all { it == WatchAction.None })
    }

    @Test
    fun reseededFrontierCountsAsLife() {
        val watch = FrontierWatch()
        watch.reset(0)
        watch.tick(0, 60_000, 60_000, 55_000, ready = true)
        // The frontier MOVES BACKWARD at 4.5 s (a fresh session
        // seeded at the playhead reports a lower end): that is a
        // restart doing its job, not a stall — the freeze clock
        // restarts, so no nudge may fire at 5 s.
        val actions = tickRange(
            watch, 500, 3_500,
            audioEndMs = 60_000, videoEndMs = 60_000,
            positionMs = 55_000,
        )
        assertTrue(actions.all { it == WatchAction.None })
        assertEquals(
            WatchAction.None,
            watch.tick(4_500, 56_000, 56_000, 55_000, ready = true),
        )
        assertEquals(
            WatchAction.None,
            watch.tick(5_000, 56_000, 56_000, 55_000, ready = true),
        )
    }

    @Test
    fun restartOutranksNudgeAcrossTracks() {
        val watch = FrontierWatch()
        watch.reset(0)
        // Both frozen from t=0, both thin: audio lead 5 s, video
        // lead 5 s. Video advances once at 2 s, so at 5.5 s the
        // audio freeze (5.5 s) qualifies for Restart while video
        // (3.5 s) only qualifies for Nudge — the Restart must win.
        watch.tick(0, 60_000, 60_000, 55_000, ready = true)
        watch.tick(2_000, 60_000, 61_000, 55_000, ready = true)
        val action = watch.tick(5_500, 60_000, 61_000, 55_000, ready = true)
        assertTrue(action is WatchAction.Restart)
        assertEquals(WatchTrack.AUDIO, (action as WatchAction.Restart).track)
    }
}
