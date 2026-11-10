package com.bunny.engine.lontar

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
        val playerPersonas: List<String> = listOf("ANDROID_VR", "ANDROID", "IOS"),
        /** Remotely updatable client versions for the ladder's personas. */
        val androidClientVersion: String = "20.10.38",
        val iosClientVersion: String = "20.10.4",
        /** visionOS persona version (Arus A1; probed 2026-11-10, see PlayerPersonas.VISIONOS). */
        val visionOsClientVersion: String = "1.02",
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
        )
    }
}
