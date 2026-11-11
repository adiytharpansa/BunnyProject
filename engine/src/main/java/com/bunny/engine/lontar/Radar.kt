package com.bunny.engine.lontar

/**
 * Lontar Hidup (Fase 6, design docs/LONTAR-HIDUP.md): the engine that
 * feels, learns, and heals itself. This file holds the pure pieces —
 * the Radar classifier and the Adaptive Ladder builder — deliberately
 * free of I/O so every rule is unit-testable from fixtures.
 *
 * A persona is only "healthy" when BOTH layers answer: the player
 * response says OK *and* media bytes can be read past the cold-start
 * allowance. Field evidence (2026-11-10): personas routinely pass the
 * player layer and then get HTTP 403 at the 3 MB mark.
 */
enum class PersonaHealthState {
    /** Player OK and both byte probes read (HTTP 206). */
    SEHAT,

    /** Player OK, but the deep byte probe is refused (HTTP 403). */
    TERGERBANG_GVS,

    /** The player endpoint itself refuses (bot gate, retired, unplayable). */
    DITOLAK_PLAYER,

    /** No verdict: the network failed, not the persona. Never a punishment. */
    GAGAL_JARINGAN,

    /** Inconsistent or insufficient evidence; keep the configured order. */
    TAK_DIKETAHUI,
}

object RadarClassifier {

    /**
     * The verdict table from the design (§5.5). [networkFailed] means
     * the probe died of I/O, timeout, 429 or 5xx after backoff — the
     * persona is not blamed. Range statuses are HTTP codes, null when
     * that probe never ran (e.g. no format big enough to probe).
     */
    fun classify(
        playabilityStatus: String?,
        firstRangeStatus: Int?,
        deepRangeStatus: Int?,
        networkFailed: Boolean,
    ): PersonaHealthState = when {
        networkFailed -> PersonaHealthState.GAGAL_JARINGAN
        playabilityStatus == null -> PersonaHealthState.TAK_DIKETAHUI
        playabilityStatus != "OK" -> PersonaHealthState.DITOLAK_PLAYER
        firstRangeStatus == 206 && deepRangeStatus == 206 ->
            PersonaHealthState.SEHAT
        firstRangeStatus == 403 || deepRangeStatus == 403 ->
            PersonaHealthState.TERGERBANG_GVS
        firstRangeStatus == 206 && deepRangeStatus == null ->
            PersonaHealthState.TAK_DIKETAHUI
        else -> PersonaHealthState.TAK_DIKETAHUI
    }
}

/** Aggregate outcome counts for one persona on one network class. */
data class PersonaStatSnapshot(
    val sehat: Int = 0,
    val tergerbang: Int = 0,
    val ditolak: Int = 0,
    val gagal: Int = 0,
) {
    /** Evidence-weighted success score in [0,1]; network failures don't vote. */
    val score: Double
        get() {
            val votes = sehat + tergerbang + ditolak
            if (votes == 0) return NEUTRAL_SCORE
            return (sehat + 0.25 * tergerbang) / votes
        }

    companion object {
        const val NEUTRAL_SCORE = 0.5
    }
}

/**
 * Where the Adaptive Ladder's long-term memory lives. Implemented by
 * the app layer over Room; the engine only knows this contract, so
 * statistics never leave the device and the engine stays testable.
 */
interface PersonaStatsStore {
    suspend fun record(persona: String, networkClass: String, state: PersonaHealthState)
    suspend fun snapshot(networkClass: String): Map<String, PersonaStatSnapshot>

    companion object {
        /** In-memory-free default: records nothing, remembers nothing. */
        val NONE: PersonaStatsStore = object : PersonaStatsStore {
            override suspend fun record(
                persona: String,
                networkClass: String,
                state: PersonaHealthState,
            ) = Unit

            override suspend fun snapshot(
                networkClass: String,
            ): Map<String, PersonaStatSnapshot> = emptyMap()
        }
    }
}

/** Coarse network class for statistics bucketing — never an SSID. */
fun interface NetworkClassProvider {
    /** "WIFI", "SELULER", or "LAINNYA". */
    fun current(): String

    companion object {
        val UNKNOWN: NetworkClassProvider = NetworkClassProvider { "LAINNYA" }
    }
}

object LadderBuilder {

    /**
     * Orders the configured ladder by evidence, per the design (§6.2),
     * most trusted first: this session's Radar verdicts, then the
     * on-device statistics for this network class, then the configured
     * order as the stable tiebreak. A persona the Radar rejected at the
     * player layer leaves this session's ladder entirely; a GVS-gated
     * persona sinks below every healthy one but stays reachable —
     * enforcement is selective and a demotion is not a ban.
     */
    fun order(
        configuredNames: List<String>,
        radar: Map<String, PersonaHealthState>,
        stats: Map<String, PersonaStatSnapshot>,
    ): List<String> {
        val kept = configuredNames.filter { name ->
            radar[name] != PersonaHealthState.DITOLAK_PLAYER
        }
        return kept.sortedWith(
            compareBy<String>(
                { radarRank(radar[it]) },
                { -(stats[it]?.score ?: PersonaStatSnapshot.NEUTRAL_SCORE) },
            ),
        )
    }

    private fun radarRank(state: PersonaHealthState?): Int = when (state) {
        PersonaHealthState.SEHAT -> 0
        PersonaHealthState.TAK_DIKETAHUI, null -> 1
        PersonaHealthState.GAGAL_JARINGAN -> 1
        PersonaHealthState.TERGERBANG_GVS -> 2
        PersonaHealthState.DITOLAK_PLAYER -> 3
    }

    /** One-line, human-readable ladder explanation for Diagnostics. */
    fun describe(
        orderedNames: List<String>,
        radar: Map<String, PersonaHealthState>,
        stats: Map<String, PersonaStatSnapshot>,
    ): String = orderedNames.joinToString(" → ") { name ->
        val radarNote = when (radar[name]) {
            PersonaHealthState.SEHAT -> "Radar: sehat"
            PersonaHealthState.TERGERBANG_GVS -> "Radar: tergerbang GVS"
            PersonaHealthState.DITOLAK_PLAYER -> "Radar: ditolak player"
            PersonaHealthState.GAGAL_JARINGAN -> "Radar: gagal jaringan"
            PersonaHealthState.TAK_DIKETAHUI -> "Radar: belum jelas"
            null -> null
        }
        val stat = stats[name]
        val statNote = if (stat != null && stat.sehat + stat.tergerbang + stat.ditolak > 0) {
            "statistik: ${stat.sehat}/${stat.sehat + stat.tergerbang + stat.ditolak} sehat"
        } else {
            null
        }
        val note = radarNote ?: statNote ?: "prior config"
        "$name ($note)"
    }
}
