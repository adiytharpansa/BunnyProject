package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.DeviceCodecCeiling
import com.bunny.engine.lontar.sabr.SabrAdaptDecision
import com.bunny.engine.lontar.sabr.SabrAdaptation
import com.bunny.engine.lontar.sabr.SabrFormat
import com.bunny.engine.lontar.sabr.sabrVideoLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mechanism 24 (Tangga Mandiri): the ladder keeps the opening
 * pick's own fences, and the decider steps down only on sustained
 * measured starvation, up only one rung on sustained health, under
 * a cooldown and a per-open budget — never above the opening pin.
 */
class SabrAdaptationTest {

    private fun video(itag: Int, height: Int, bitrate: Int, av1: Boolean = false) =
        SabrFormat(
            itag = itag,
            mimeType = if (av1) {
                "video/mp4; codecs=\"av01.0.08M.08\""
            } else {
                "video/mp4; codecs=\"avc1.640028\""
            },
            bitrate = bitrate,
            height = height,
        )

    private fun audio(itag: Int = 140) = SabrFormat(
        itag = itag,
        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
        bitrate = 128_000,
    )

    private val v144 = video(160, 144, 200_000)
    private val v240 = video(133, 240, 400_000)
    private val v360 = video(134, 360, 800_000)
    private val v480 = video(135, 480, 1_500_000)
    private val v720 = video(136, 720, 3_000_000)
    private val v1080 = video(137, 1080, 6_000_000)
    private val registry = listOf(v1080, v360, audio(), v720, v144, v480, v240)

    private val noCeiling = DeviceCodecCeiling.UNKNOWN

    private fun decider(
        opening: SabrFormat = v720,
        ladder: List<SabrFormat> = sabrVideoLadder(registry, null, noCeiling),
    ) = SabrAdaptation(opening, ladder)

    /** Feeds samples every 500 ms; returns the decisions that fired. */
    private fun feed(
        d: SabrAdaptation,
        fromMs: Long,
        toMs: Long,
        leadMs: Long,
        estimateBps: Long,
        current: () -> SabrFormat,
        onSwitch: (SabrFormat) -> Unit,
    ): List<SabrAdaptDecision.SwitchTo> {
        val fired = mutableListOf<SabrAdaptDecision.SwitchTo>()
        var now = fromMs
        while (now <= toMs) {
            val decision = d.evaluate(now, leadMs, estimateBps, current())
            if (decision is SabrAdaptDecision.SwitchTo) {
                fired += decision
                onSwitch(decision.target)
            }
            now += 500
        }
        return fired
    }

    // ------------------------------------------------------------ ladder

    @Test
    fun ladder_sortsByBitrate_andDropsAudio() {
        val ladder = sabrVideoLadder(registry, null, noCeiling)
        assertEquals(
            listOf(160, 133, 134, 135, 136, 137),
            ladder.map { it.itag },
        )
    }

    @Test
    fun ladder_respectsHeightCap() {
        val ladder = sabrVideoLadder(registry, 720, noCeiling)
        assertEquals(listOf(160, 133, 134, 135, 136), ladder.map { it.itag })
    }

    @Test
    fun ladder_respectsCodecCeiling_whenHardwarePoolExists() {
        val av1Only1080 = video(401, 1080, 6_000_000, av1 = true)
        val formats = registry - v1080 + av1Only1080
        val ceiling = DeviceCodecCeiling(av1Hardware = false, vp9Hardware = true)
        val ladder = sabrVideoLadder(formats, null, ceiling)
        assertTrue(ladder.none { it.itag == 401 })
    }

    @Test
    fun ladder_fallsBack_whenOnlySoftwareCodecExists() {
        val av1Only = listOf(
            video(401, 720, 3_000_000, av1 = true),
            video(402, 360, 800_000, av1 = true),
            audio(),
        )
        val ceiling = DeviceCodecCeiling(av1Hardware = false, vp9Hardware = true)
        val ladder = sabrVideoLadder(av1Only, null, ceiling)
        assertEquals(listOf(402, 401), ladder.map { it.itag })
    }

    // ------------------------------------------------------------ decider

    @Test
    fun healthyPlayback_neverSwitches() {
        val d = decider()
        var current = v720
        val fired = feed(d, 0, 120_000, leadMs = 60_000, estimateBps = 10_000_000,
            current = { current }, onSwitch = { current = it })
        assertTrue(fired.isEmpty())
    }

    @Test
    fun briefDip_doesNotSwitch_andTimerResets() {
        val d = decider()
        var current = v720
        // 5 s of starvation (shorter than the 8 s sustain), recovery,
        // then another 5 s: the two dips must not add up.
        feed(d, 0, 5_000, leadMs = 5_000, estimateBps = 1_000_000,
            current = { current }, onSwitch = { current = it })
        feed(d, 5_500, 9_500, leadMs = 60_000, estimateBps = 10_000_000,
            current = { current }, onSwitch = { current = it })
        val fired = feed(d, 10_000, 15_000, leadMs = 5_000, estimateBps = 1_000_000,
            current = { current }, onSwitch = { current = it })
        assertTrue(fired.isEmpty())
        assertEquals(v720, current)
    }

    @Test
    fun sustainedStarvation_stepsDownToRichestAffordable() {
        val d = decider()
        var current = v720
        // Estimate 2 Mbps: under the 720p pin's 2.7 Mbps starvation
        // line; the richest rung at or under 2.0 × 0.8 = 1.6 Mbps
        // is 480p (1.5 Mbps).
        val fired = feed(d, 0, 9_000, leadMs = 5_000, estimateBps = 2_000_000,
            current = { current }, onSwitch = { current = it })
        assertEquals(1, fired.size)
        assertEquals(v480, fired[0].target)
        assertTrue(fired[0].down)
    }

    @Test
    fun cliffFall_goesToLowestRungBelowCurrent() {
        val d = decider()
        var current = v720
        val fired = feed(d, 0, 9_000, leadMs = 2_000, estimateBps = 300_000,
            current = { current }, onSwitch = { current = it })
        assertEquals(1, fired.size)
        assertEquals(v144, fired[0].target)
    }

    @Test
    fun atLowestRung_starvationStays() {
        val d = decider(opening = v360)
        var current = v144
        val fired = feed(d, 0, 30_000, leadMs = 1_000, estimateBps = 100_000,
            current = { current }, onSwitch = { current = it })
        assertTrue(fired.isEmpty())
    }

    @Test
    fun noMeasurement_noDecision() {
        val d = decider()
        var current = v720
        val fired = feed(d, 0, 60_000, leadMs = 0, estimateBps = 0,
            current = { current }, onSwitch = { current = it })
        assertTrue(fired.isEmpty())
    }

    @Test
    fun thinLeadAlone_doesNotSwitch_whenNetworkFeedsThePin() {
        val d = decider()
        var current = v720
        // Lead is thin but the estimate (5 Mbps) feeds the 3 Mbps
        // pin comfortably — a shallow spool, not starvation.
        val fired = feed(d, 0, 30_000, leadMs = 5_000, estimateBps = 5_000_000,
            current = { current }, onSwitch = { current = it })
        assertTrue(fired.isEmpty())
    }

    @Test
    fun stepUp_oneRung_onlyAfterSustainedHealth_neverAboveOpening() {
        val d = decider(opening = v720)
        var current = v480
        // 44 s of thriving is not enough…
        val early = feed(d, 0, 44_000, leadMs = 60_000, estimateBps = 5_000_000,
            current = { current }, onSwitch = { current = it })
        assertTrue(early.isEmpty())
        // …45 s is: exactly one rung up, to the opening pin.
        val fired = feed(d, 44_500, 46_000, leadMs = 60_000, estimateBps = 5_000_000,
            current = { current }, onSwitch = { current = it })
        assertEquals(1, fired.size)
        assertEquals(v720, fired[0].target)
        assertTrue(!fired[0].down)
        // At the opening pin, continued health changes nothing.
        val more = feed(d, 70_000, 180_000, leadMs = 60_000, estimateBps = 20_000_000,
            current = { current }, onSwitch = { current = it })
        assertTrue(more.isEmpty())
    }

    @Test
    fun stepUp_needsBandwidthHeadroom_notJustFatLead() {
        val d = decider(opening = v720)
        var current = v480
        // Next rung is 3 Mbps; 4 Mbps estimate is under 3 × 1.5.
        val fired = feed(d, 0, 120_000, leadMs = 60_000, estimateBps = 4_000_000,
            current = { current }, onSwitch = { current = it })
        assertTrue(fired.isEmpty())
    }

    @Test
    fun cooldown_blocksImmediateSecondSwitch_andRestartsTheClock() {
        val d = decider()
        var current = v720
        val first = feed(d, 0, 9_000, leadMs = 5_000, estimateBps = 2_000_000,
            current = { current }, onSwitch = { current = it })
        assertEquals(1, first.size)
        assertEquals(v480, current)
        // Still starving on the new pin (1 Mbps < 1.5 × 0.9), but the
        // 20 s cooldown must hold until t=28 s, and the distress
        // clock only starts accumulating afterwards: fire at ~36 s.
        val during = feed(d, 9_500, 35_500, leadMs = 3_000, estimateBps = 1_000_000,
            current = { current }, onSwitch = { current = it })
        assertTrue(during.isEmpty())
        val after = feed(d, 36_000, 37_000, leadMs = 3_000, estimateBps = 1_000_000,
            current = { current }, onSwitch = { current = it })
        assertEquals(1, after.size)
        assertEquals(v360, after[0].target)
    }

    @Test
    fun budget_sixSwitchesThenStays() {
        val d = decider(opening = v1080)
        var current = v1080
        var switches = 0
        var now = 0L
        // Alternate starvation and health to walk down and up the
        // ladder; every phase is long enough to fire exactly once.
        val downEst = listOf(2_000_000L, 900_000L, 300_000L)
        val phases = mutableListOf<Pair<Long, Long>>() // lead, estimate
        repeat(3) { phases += 5_000L to downEst[it] }
        repeat(3) { phases += 60_000L to 20_000_000L }
        phases += 5_000L to 100_000L // seventh attempt — must stay
        for ((lead, est) in phases) {
            val before = switches
            val fired = feed(d, now, now + 70_000, leadMs = lead, estimateBps = est,
                current = { current }, onSwitch = { current = it; switches++ })
            now += 70_500
            if (before < 6) assertEquals(1, fired.size) else assertTrue(fired.isEmpty())
        }
        assertEquals(6, switches)
        assertEquals(6, d.spentSwitches)
    }
}
