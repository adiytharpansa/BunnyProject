package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.SabrKillThreshold
import org.json.JSONObject

/**
 * Fase 4 — remotely updatable engine configuration.
 *
 * The values that break first when YouTube changes something — client
 * versions and the home mixture's seed topics — live in a small JSON
 * file in the project repository ([REMOTE_URL]). Lontar fetches it once
 * per process, best-effort: any failure (offline, moved file, malformed
 * JSON) silently keeps [DEFAULTS], which mirror the shipped file, so the
 * engine never depends on the remote copy existing. Editing that one
 * file then retunes every installed Bunny without an app update.
 */
object EngineConfig {

    const val REMOTE_URL =
        "https://raw.githubusercontent.com/adiytharpansa/BunnyProject/main/engine-config.json"

    data class Values(
        val fallbackClientVersion: String,
        val androidVrClientVersion: String,
        val seedQueries: List<Pair<String, String>>,
        /**
         * Player persona ladder, by [PlayerPersona.name], in the order
         * [LontarEngine] tries them (distribution hardening 2026-11-10:
         * remotely reorderable so a retired persona can be demoted or
         * dropped without an app update).
         */
        val playerPersonas: List<String> = listOf("VISIONOS", "ANDROID_VR"),
        /** Remotely updatable client versions for the ladder's personas. */
        val androidClientVersion: String = "20.10.38",
        val iosClientVersion: String = "20.10.4",
        /** visionOS persona version (probed 2026-11-10; re-probed at 1.04 on 2026-11-11, see PlayerPersonas.VISIONOS). */
        val visionOsClientVersion: String = "1.04",
        /**
         * Lontar Hidup (Fase 6): the canary videos the Engine Health
         * Radar probes — long-lived, stable uploads; replaceable from
         * remote config when one disappears or stops representing the
         * general case.
         */
        val canaryVideoIds: List<String> = listOf("b2DfMST_WE8", "aqz-KE-bpKQ"),
        /** Byte offset of the Radar's deep probe (past the cold-start allowance). */
        val radarDeepProbeOffsetBytes: Long = 3_000_000L,
        /** A Radar result older than this is stale and may be refreshed. */
        val radarMaxAgeHours: Int = 6,
        /**
         * Fase 8 (Anyam, RANCANGAN-FASE-8 Bagian 7): the SABR road's
         * remote gate. The shipped default is OFF — the road exists in
         * code from Langkah 3 but serves nobody until the config
         * deliberately opens it (activation is Langkah 7's ceremony,
         * per network class, on device evidence).
         */
        val sabrEnabled: Boolean = false,
        /** Personas allowed to speak SABR, by [PlayerPersona.name]. */
        val sabrPersonas: List<String> = listOf("VISIONOS"),
        /** Total on-disk budget for all spools, in megabytes. */
        val spoolBudgetMb: Int = 512,
        /**
         * Fase 8 mechanism 4 (hedged start): the race budget in KB.
         * When the spool already holds this much media and the
         * player still has not started, the bytes are not the
         * bottleneck — the classic road takes the start instead of
         * waiting out the full watchdog. Halved on cellular.
         */
        val sabrRaceBudgetKb: Int = 4096,
        /**
         * Fase 8 Langkah 7, mechanism 7 (design Bagian 7): the
         * self-disabling governance's threshold — when the SABR
         * road's failure rate over its rolling attempt window on
         * one network class reaches this, the road disables itself
         * locally for that class until the window heals. The
         * shipped defaults are the safe ones (see
         * [SabrKillThreshold]); a malformed remote field falls
         * back to them field by field.
         */
        val sabrKillThreshold: SabrKillThreshold = SabrKillThreshold.DEFAULT,
        /**
         * SABR Adaptif Baru F0: the hard deadline for one SABR
         * exchange, in seconds. A POST that outlives it is cancelled
         * and named, instead of hanging the pump silently (the
         * 2026-11-13 18:07 device photo: the frontier froze at the
         * exact waited byte and no line ever said why). Zero
         * disables the deadline (the pre-F0 behaviour).
         */
        val sabrExchangeTimeoutSec: Int = 10,
        /**
         * SABR Adaptif Baru: the frontier supervisor's remote
         * switch. The code default is OFF; the shipped remote
         * document turns it on deliberately, and the field exists
         * so a misbehaving supervisor can be silenced without an
         * app update.
         */
        val sabrAdaptiveV2: Boolean = false,
        /**
         * Lantai Penyelamat (RANCANGAN-LANTAI-PENYELAMAT): the rescue
         * floor's remote switch. The shipped default is ON — the floor
         * only ever runs after every other road has failed, so it
         * cannot degrade a healthy open; the switch exists so a floor
         * that misbehaves in the field can be silenced without an app
         * update.
         */
        val floorEnabled: Boolean = true,
    )

    val DEFAULTS = Values(
        fallbackClientVersion = InnertubeSession.FALLBACK_CLIENT_VERSION,
        androidVrClientVersion = "1.65.10",
        seedQueries = listOf(
            "berita hari ini" to "Berita",
            "musik indonesia terbaru" to "Musik",
            "gaming" to "Gaming",
            "film pendek" to "Film",
            "podcast indonesia" to "Podcast",
        ),
    )

    /** Merges a remote document over [DEFAULTS], field by field. */
    fun parse(json: JSONObject): Values {
        val seeds = mutableListOf<Pair<String, String>>()
        val array = json.optJSONArray("seedQueries")
        if (array != null) {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val query = entry.optString("query").trim()
                val label = entry.optString("label").trim()
                if (query.isNotEmpty() && label.isNotEmpty()) seeds += query to label
            }
        }
        val personas = mutableListOf<String>()
        val personaArray = json.optJSONArray("playerPersonas")
        if (personaArray != null) {
            for (i in 0 until personaArray.length()) {
                val name = personaArray.optString(i).trim()
                if (name.isNotEmpty()) personas += name
            }
        }
        val canaries = mutableListOf<String>()
        val canaryArray = json.optJSONArray("canaryVideoIds")
        if (canaryArray != null) {
            for (i in 0 until canaryArray.length()) {
                val id = canaryArray.optString(i).trim()
                if (id.isNotEmpty()) canaries += id
            }
        }
        val radar = json.optJSONObject("radar")
        val killThreshold = json.optJSONObject("sabrKillThreshold")
        val killWindow = killThreshold
            ?.optInt("windowAttempts")
            ?.takeIf { it > 0 } ?: SabrKillThreshold.DEFAULT_WINDOW_ATTEMPTS
        val killMin = killThreshold
            ?.optInt("minAttempts")
            ?.takeIf { it > 0 } ?: SabrKillThreshold.DEFAULT_MIN_ATTEMPTS
        val sabrPersonas = mutableListOf<String>()
        val sabrPersonaArray = json.optJSONArray("sabrPersonas")
        if (sabrPersonaArray != null) {
            for (i in 0 until sabrPersonaArray.length()) {
                val name = sabrPersonaArray.optString(i).trim()
                if (name.isNotEmpty()) sabrPersonas += name
            }
        }
        return Values(
            fallbackClientVersion = json.optString("fallbackClientVersion")
                .takeIf { it.isNotBlank() } ?: DEFAULTS.fallbackClientVersion,
            androidVrClientVersion = json.optString("androidVrClientVersion")
                .takeIf { it.isNotBlank() } ?: DEFAULTS.androidVrClientVersion,
            seedQueries = seeds.takeIf { it.isNotEmpty() } ?: DEFAULTS.seedQueries,
            playerPersonas = personas.takeIf { it.isNotEmpty() } ?: DEFAULTS.playerPersonas,
            androidClientVersion = json.optString("androidClientVersion")
                .takeIf { it.isNotBlank() } ?: DEFAULTS.androidClientVersion,
            iosClientVersion = json.optString("iosClientVersion")
                .takeIf { it.isNotBlank() } ?: DEFAULTS.iosClientVersion,
            visionOsClientVersion = json.optString("visionOsClientVersion")
                .takeIf { it.isNotBlank() } ?: DEFAULTS.visionOsClientVersion,
            canaryVideoIds = canaries.takeIf { it.isNotEmpty() }
                ?: DEFAULTS.canaryVideoIds,
            radarDeepProbeOffsetBytes = radar
                ?.optLong("deepProbeOffsetBytes")
                ?.takeIf { it > 0 } ?: DEFAULTS.radarDeepProbeOffsetBytes,
            radarMaxAgeHours = radar
                ?.optInt("maxAgeHours")
                ?.takeIf { it > 0 } ?: DEFAULTS.radarMaxAgeHours,
            sabrEnabled = if (json.has("sabrEnabled")) {
                json.optBoolean("sabrEnabled")
            } else {
                DEFAULTS.sabrEnabled
            },
            sabrPersonas = sabrPersonas.takeIf { it.isNotEmpty() }
                ?: DEFAULTS.sabrPersonas,
            spoolBudgetMb = json.optInt("spoolBudgetMb")
                .takeIf { it > 0 } ?: DEFAULTS.spoolBudgetMb,
            sabrRaceBudgetKb = json.optInt("sabrRaceBudgetKb")
                .takeIf { it > 0 } ?: DEFAULTS.sabrRaceBudgetKb,
            sabrKillThreshold = SabrKillThreshold(
                maxFailureRate = killThreshold
                    ?.optDouble("maxFailureRate")
                    ?.takeIf { it > 0.0 && it <= 1.0 }
                    ?: SabrKillThreshold.DEFAULT_MAX_FAILURE_RATE,
                windowAttempts = killWindow,
                // A minimum above the window could never fire;
                // clamp it into the window instead of shipping a
                // threshold that silently means "never".
                minAttempts = minOf(killMin, killWindow),
            ),
            sabrExchangeTimeoutSec = if (json.has("sabrExchangeTimeoutSec")) {
                json.optInt("sabrExchangeTimeoutSec").coerceAtLeast(0)
            } else {
                DEFAULTS.sabrExchangeTimeoutSec
            },
            sabrAdaptiveV2 = if (json.has("sabrAdaptiveV2")) {
                json.optBoolean("sabrAdaptiveV2")
            } else {
                DEFAULTS.sabrAdaptiveV2
            },
            floorEnabled = if (json.has("floorEnabled")) {
                json.optBoolean("floorEnabled")
            } else {
                DEFAULTS.floorEnabled
            },
        )
    }
}
