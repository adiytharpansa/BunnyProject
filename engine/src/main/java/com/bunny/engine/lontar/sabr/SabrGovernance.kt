package com.bunny.engine.lontar.sabr

import com.bunny.engine.lontar.SabrGovernanceStore

/**
 * Fase 8 Langkah 7, mechanism 7 — the self-disabling governance's
 * threshold, carried by the remote engine config (`sabrKillThreshold`,
 * design Bagian 7): the failure rate at or above which, over a
 * rolling window of SABR attempts on ONE network class, the road
 * disables itself locally for that class. The defaults below are
 * the safe code defaults: a config that omits or mangles the field
 * gets exactly these, and governance can never fire below
 * [minAttempts] — no death by two unlucky opens.
 */
data class SabrKillThreshold(
    val maxFailureRate: Double = DEFAULT_MAX_FAILURE_RATE,
    val windowAttempts: Int = DEFAULT_WINDOW_ATTEMPTS,
    val minAttempts: Int = DEFAULT_MIN_ATTEMPTS,
) {
    companion object {
        const val DEFAULT_MAX_FAILURE_RATE: Double = 0.5
        const val DEFAULT_WINDOW_ATTEMPTS: Int = 20
        const val DEFAULT_MIN_ATTEMPTS: Int = 8
        val DEFAULT = SabrKillThreshold()
    }
}

/**
 * Fase 8 Langkah 7, mechanism 7 — Tata kelola mematikan diri:
 * the rolling outcome log that decides whether the SABR road is
 * locally disabled on a network class.
 *
 * What an "attempt" is (and is not) is the honesty core of this
 * class, and it is enforced at the recording call sites, not
 * here: this log only ever hears VERDICTS — a playback whose
 * ready signal fired (success), or a road-level death (a token
 * demanded SPS 3, a SABR_ERROR, an HTTP failure, a non-lifecycle
 * stall — recorded by the drive), or a /player fetch that failed
 * at transport level before any session existed. Per-video facts
 * (no SABR offer for THIS video — "tawaran") are NEVER recorded:
 * they are the road memory's (mechanism 10) business, and letting
 * them in here would let a run of age-gated videos disable the
 * road for everyone.
 *
 * The disabled state is DERIVED, never stored: [verdict] reads
 * the last [SabrKillThreshold.windowAttempts] outcomes OF ONE
 * CLASS and disables only when at least
 * [SabrKillThreshold.minAttempts] of them are present and the
 * failure share reaches [SabrKillThreshold.maxFailureRate]. As
 * new outcomes land, old failures slide out of the window — the
 * road heals itself with no flag to get stuck and no timer to
 * trust. Classes are judged separately: a cellular collapse
 * never disables Wi-Fi.
 *
 * The remote config stays the manual switch ABOVE this gate
 * (RoadOracle's `sabrEnabled`): governance can only ever veto,
 * never enable. Effective permission is a conjunction —
 * config enabled && persona allowed && !governance disabled.
 *
 * The log is bounded per class ([MAX_PER_CLASS] newest) and
 * persisted through the dumb [SabrGovernanceStore] shelf as
 * lines of `epochMs;S|F;CLASS`. The clock is injected; this
 * class is pure JVM-testable.
 */
class SabrGovernance(
    private val store: SabrGovernanceStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** One recorded verdict. */
    data class Outcome(
        val atMs: Long,
        val success: Boolean,
        val networkClass: String,
    )

    /** The window's reading for one network class. */
    data class Verdict(
        val networkClass: String,
        /** Attempts inside the window (≤ the threshold's window). */
        val attempts: Int,
        /** Failures inside the window. */
        val failures: Int,
        val disabled: Boolean,
    )

    private val outcomes = mutableListOf<Outcome>()

    init {
        store.load()?.let { raw ->
            for (line in raw.split('\n')) {
                parseLine(line)?.let { outcomes += it }
            }
        }
        trimLocked()
    }

    /** Records one verdict and persists the shelf. */
    @Synchronized
    fun record(success: Boolean, networkClass: String) {
        outcomes += Outcome(nowMs(), success, networkClass)
        trimLocked()
        store.save(serializeLocked())
    }

    /**
     * The governance question for [networkClass] under
     * [threshold]: is the road locally disabled there, and on
     * what window evidence?
     */
    @Synchronized
    fun verdict(
        networkClass: String,
        threshold: SabrKillThreshold,
    ): Verdict {
        val window = outcomes
            .filter { it.networkClass == networkClass }
            .takeLast(threshold.windowAttempts.coerceAtLeast(1))
        val failures = window.count { !it.success }
        val disabled = window.size >= threshold.minAttempts &&
            window.isNotEmpty() &&
            failures.toDouble() / window.size >= threshold.maxFailureRate
        return Verdict(networkClass, window.size, failures, disabled)
    }

    /** Total recorded outcomes, all classes (test visibility). */
    @Synchronized
    fun size(): Int = outcomes.size

    /** Keeps only the newest [MAX_PER_CLASS] outcomes per class. */
    private fun trimLocked() {
        if (outcomes.size <= MAX_PER_CLASS) return
        val kept = mutableListOf<Outcome>()
        val seen = HashMap<String, Int>()
        for (i in outcomes.indices.reversed()) {
            val outcome = outcomes[i]
            val count = seen.getOrDefault(outcome.networkClass, 0)
            if (count >= MAX_PER_CLASS) continue
            seen[outcome.networkClass] = count + 1
            kept += outcome
        }
        outcomes.clear()
        outcomes.addAll(kept.asReversed())
    }

    private fun serializeLocked(): String =
        outcomes.joinToString("\n") {
            "${it.atMs};${if (it.success) "S" else "F"};${it.networkClass}"
        }

    private fun parseLine(line: String): Outcome? {
        val parts = line.trim().split(';')
        if (parts.size != 3) return null
        val atMs = parts[0].toLongOrNull() ?: return null
        val success = when (parts[1]) {
            "S" -> true
            "F" -> false
            else -> return null
        }
        val networkClass = parts[2].takeIf { it.isNotEmpty() } ?: return null
        return Outcome(atMs, success, networkClass)
    }

    companion object {
        /**
         * Newest outcomes kept per class: comfortably above the
         * default window (20) so a class's window survives the
         * other classes' traffic, small enough that the shelf
         * stays a courtesy log, not an archive.
         */
        const val MAX_PER_CLASS: Int = 100
    }
}
