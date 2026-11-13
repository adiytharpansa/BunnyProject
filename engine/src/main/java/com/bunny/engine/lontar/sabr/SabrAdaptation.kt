package com.bunny.engine.lontar.sabr

/**
 * Mechanism 24 — Tangga Mandiri (RANCANGAN-SABR-MANDIRI, ordered by
 * the user 2026-11-13): Bunny decides SABR quality steps itself, on
 * the device, from measured evidence, instead of only following
 * the server's steering.
 *
 * This file is the pure decider. It knows nothing about pumps or
 * players: given the opening pin, the ladder the open already
 * allowed, and a stream of (lead, measured bandwidth) samples, it
 * answers Stay or SwitchTo. SabrPlayback feeds it and executes.
 *
 * The ladder is the fence: it is built with the opening pick's own
 * rules (chooseVideoPin's best tier — hardware-decodable AND under
 * the Jatah height cap, falling back through the same tiers), so a
 * step can never land anywhere the open itself would not have
 * allowed, and never above the opening pin.
 */

/**
 * The video formats an open may step among, poorest first. Mirrors
 * [chooseVideoPin]'s tier order: the first non-empty tier wins, so
 * when a hardware-decodable under-cap pool exists the ladder is
 * exactly that pool.
 */
fun sabrVideoLadder(
    formats: List<SabrFormat>,
    capHeightPx: Int?,
    ceiling: DeviceCodecCeiling,
): List<SabrFormat> {
    val videos = formats.filter { !it.isAudio }
    if (videos.isEmpty()) return emptyList()
    fun underCap(f: SabrFormat): Boolean =
        capHeightPx == null || f.height <= 0 || f.height <= capHeightPx
    fun hw(f: SabrFormat): Boolean = ceiling.hardwareOk(codecFamilyOf(f.mimeType))
    val tiers = listOf(
        videos.filter { hw(it) && underCap(it) },
        videos.filter { hw(it) },
        videos.filter { underCap(it) },
        videos,
    )
    val pool = tiers.firstOrNull { it.isNotEmpty() } ?: videos
    return pool.distinctBy { it.itag }.sortedBy { it.bitrate }
}

/** The decider's answer for one evaluation. */
sealed interface SabrAdaptDecision {
    data object Stay : SabrAdaptDecision
    data class SwitchTo(val target: SabrFormat, val down: Boolean) :
        SabrAdaptDecision
}

/**
 * Stateful step decider for one open. [evaluate] is fed at the
 * pump's progress cadence; all thresholds are wall-clock, so the
 * cadence only affects reaction granularity, never the rules.
 */
class SabrAdaptation(
    private val openingPin: SabrFormat,
    private val ladder: List<SabrFormat>,
) {
    private var lastSwitchMs: Long = Long.MIN_VALUE / 2
    private var distressSinceMs: Long = -1
    private var healthySinceMs: Long = -1
    private var switchesUsed: Int = 0

    /** Switches this open has fired (spent even when execution fails). */
    val spentSwitches: Int get() = switchesUsed

    fun evaluate(
        nowMs: Long,
        leadMs: Long,
        estimateBps: Long,
        current: SabrFormat,
    ): SabrAdaptDecision {
        if (ladder.size < 2 || switchesUsed >= MAX_SWITCHES) {
            return SabrAdaptDecision.Stay
        }
        if (nowMs - lastSwitchMs < COOLDOWN_MS) {
            distressSinceMs = -1
            healthySinceMs = -1
            return SabrAdaptDecision.Stay
        }

        // ---- step down: the cushion is thin AND the measured
        // network cannot feed the current pin.
        val starving = leadMs < DOWN_LEAD_MS && estimateBps > 0 &&
            estimateBps < current.bitrate * DOWN_BANDWIDTH_FACTOR
        if (starving) {
            if (distressSinceMs < 0) distressSinceMs = nowMs
        } else {
            distressSinceMs = -1
        }
        if (distressSinceMs >= 0 && nowMs - distressSinceMs >= DOWN_SUSTAIN_MS) {
            val below = ladder.filter { it.bitrate < current.bitrate }
            if (below.isNotEmpty()) {
                val affordable =
                    below.filter { it.bitrate <= estimateBps * TARGET_BANDWIDTH_FACTOR }
                val target = affordable.maxByOrNull { it.bitrate }
                    ?: below.minByOrNull { it.bitrate }!!
                return fire(nowMs, target, down = true)
            }
        }

        // ---- step up: fat cushion, sustained, and the measured
        // network clears the next rung with headroom. One rung,
        // never above the opening pin.
        val nextRung = ladder
            .filter { it.bitrate > current.bitrate && it.bitrate <= openingPin.bitrate }
            .minByOrNull { it.bitrate }
        val thriving = nextRung != null && leadMs > UP_LEAD_MS &&
            estimateBps > nextRung.bitrate * UP_BANDWIDTH_FACTOR
        if (thriving) {
            if (healthySinceMs < 0) healthySinceMs = nowMs
        } else {
            healthySinceMs = -1
        }
        if (nextRung != null && healthySinceMs >= 0 &&
            nowMs - healthySinceMs >= UP_SUSTAIN_MS
        ) {
            return fire(nowMs, nextRung, down = false)
        }

        return SabrAdaptDecision.Stay
    }

    private fun fire(nowMs: Long, target: SabrFormat, down: Boolean): SabrAdaptDecision {
        lastSwitchMs = nowMs
        switchesUsed++
        distressSinceMs = -1
        healthySinceMs = -1
        return SabrAdaptDecision.SwitchTo(target, down)
    }

    companion object {
        /** Lead under which the road counts as starving. */
        const val DOWN_LEAD_MS = 10_000L

        /** How long starving must persist before a step down. */
        const val DOWN_SUSTAIN_MS = 8_000L

        /** Starving also needs the estimate under pin × this. */
        const val DOWN_BANDWIDTH_FACTOR = 0.9

        /** Step-down target: richest rung at or under estimate × this. */
        const val TARGET_BANDWIDTH_FACTOR = 0.8

        /** Lead over which the road counts as thriving. */
        const val UP_LEAD_MS = 30_000L

        /** How long thriving must persist before a step up. */
        const val UP_SUSTAIN_MS = 45_000L

        /** Step up needs the estimate over next rung × this. */
        const val UP_BANDWIDTH_FACTOR = 1.5

        /** Minimum quiet between two switches. */
        const val COOLDOWN_MS = 20_000L

        /** Switch budget per open; spent even on failed execution. */
        const val MAX_SWITCHES = 6
    }
}
