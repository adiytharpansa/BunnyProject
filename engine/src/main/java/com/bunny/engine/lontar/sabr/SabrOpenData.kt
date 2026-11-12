package com.bunny.engine.lontar.sabr

import com.bunny.core.model.PlaybackTrackingUrls

/**
 * Everything a SABR session needs, lifted out of one VISIONOS
 * /player response: the streaming URL, the ustreamer configuration
 * (echoed verbatim in every request — it is part of the proven
 * recipe), the full format registry, the opening pins, and the stats
 * addresses the response hands out for this view.
 *
 * Built by InnertubeParser.parseSabrOpen; null there means the
 * response does not offer this road (unplayable, live, paid, or the
 * fields are simply absent) — the classic road is then the only one,
 * exactly as before Fase 8.
 */
data class SabrOpenData(
    val videoId: String,
    val sabrUrl: String,
    val ustreamerConfig: ByteArray,
    val formats: List<SabrFormat>,
    val audioPin: SabrFormat,
    val videoPin: SabrFormat,
    val durationSeconds: Int,
    val clientInfo: SabrClientInfo,
    val userAgent: String,
    val tracking: PlaybackTrackingUrls?,
) {
    fun newSession(cpn: String = SabrSession.generateCpn()): SabrSession =
        SabrSession(
            videoId = videoId,
            initialUrl = sabrUrl,
            ustreamerConfig = ustreamerConfig,
            clientInfo = clientInfo,
            durationSeconds = durationSeconds,
            formats = formats,
            audioItag = audioPin.itag,
            videoItag = videoPin.itag,
            cpn = cpn,
        )
}

/** The SABR slice of the remote engine configuration (Bagian 7). */
data class SabrSettings(
    val enabled: Boolean,
    val personas: List<String>,
    val spoolBudgetBytes: Long,
)

/**
 * The basic road oracle of Langkah 3: SABR goes first only when the
 * remote config allows it, for a persona the matrix names, and never
 * for live content (the design keeps SABR off live entirely). The
 * per-network-class activation and the self-disabling governance
 * arrive with Langkah 7; this gate is deliberately the simple,
 * auditable core they will extend.
 */
class RoadOracle(private val settings: SabrSettings) {

    fun allowsSabr(personaName: String, isLive: Boolean): Boolean {
        if (!settings.enabled) return false
        if (isLive) return false
        return personaName in settings.personas
    }
}
