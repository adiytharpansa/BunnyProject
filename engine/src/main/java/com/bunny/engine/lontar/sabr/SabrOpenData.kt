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
    /** Mechanism 6: the Jatah height cap this open was asked under. */
    val capHeightPx: Int? = null,
    /** Mechanism 15: the real panel size, when the app provided it. */
    val viewportWidth: Int = 1920,
    val viewportHeight: Int = 1080,
    /**
     * Anti-buffering M11: the device codec ceiling this open was
     * parsed under, carried so every session minted from it (the
     * first drive, and each RELOAD refresh) adopts pins under the
     * same ceiling.
     */
    val ceiling: DeviceCodecCeiling = DeviceCodecCeiling.UNKNOWN,
) {
    /**
     * Mints a session over this offer. [startTimeMs] seeds the
     * session's opening playhead claim (mechanism 23, full form):
     * the pump's first request carries it as player_time_ms, and
     * the server enters the stream there — proven by the jilid-2
     * cold-seek probe (2026-11-12: a session seeded at 50% landed
     * its first real segment at 190,001 ms). Zero, the default,
     * is the classic from-the-top session every existing caller
     * gets, byte-identical to before.
     */
    fun newSession(
        cpn: String = SabrSession.generateCpn(),
        startTimeMs: Long = 0,
        /**
         * Mechanism 24 (SABR Mandiri): a forced video pin for an
         * adaptation session. Null — every existing caller — mints
         * the opening pin exactly as before; a value must come from
         * the open's own ladder ([sabrVideoLadder]), never above
         * the opening pin and never past the open's cap or codec
         * ceiling.
         */
        videoPinOverride: SabrFormat? = null,
    ): SabrSession =
        SabrSession(
            videoId = videoId,
            initialUrl = sabrUrl,
            ustreamerConfig = ustreamerConfig,
            clientInfo = clientInfo,
            durationSeconds = durationSeconds,
            formats = formats,
            audioItag = audioPin.itag,
            videoItag = (videoPinOverride ?: videoPin).itag,
            startTimeMs = startTimeMs,
            cpn = cpn,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            videoCapHeightPx = capHeightPx,
            codecCeiling = ceiling,
        )
}

/** The SABR slice of the remote engine configuration (Bagian 7). */
data class SabrSettings(
    val enabled: Boolean,
    val personas: List<String>,
    val spoolBudgetBytes: Long,
    /** Mechanism 4 race budget: spooled bytes past which a still-
     *  buffering start is conceded to the classic road. */
    val raceBudgetBytes: Long = 4_194_304L,
    /** Mechanisms 3 + 22: the pump's lead cap over the playhead.
     *  Unbounded off-metered (the spool may complete while
     *  watching); the classic buffer ceiling on metered links. */
    val leadCapMs: Long = Long.MAX_VALUE,
    /** Langkah 7, mechanism 7: the self-disabling governance's
     *  threshold, carried with the rest of the SABR config slice. */
    val killThreshold: SabrKillThreshold = SabrKillThreshold.DEFAULT,
    /** Mechanism 24 (SABR Mandiri): Bunny's own quality stepping.
     *  On by default; off returns SABR to following the server's
     *  steering only, exactly the pre-M24 behaviour. */
    val adaptEnabled: Boolean = true,
)

/**
 * The basic road oracle of Langkah 3: SABR goes first only when the
 * remote config allows it, for a persona the matrix names
 * (mechanism 27 — the config's `sabrPersonas` list IS the
 * per-persona permission matrix), and never for live content (the
 * design keeps SABR off live entirely). Langkah 7 added the
 * self-disabling governance (mechanism 7) as a further conjunct
 * INSIDE LontarEngine.openSabrSession — config && persona &&
 * !governanceDisabled(class) — so this oracle stays the simple,
 * auditable core and governance can only ever veto, never enable.
 */
class RoadOracle(private val settings: SabrSettings) {

    fun allowsSabr(personaName: String, isLive: Boolean): Boolean {
        if (!settings.enabled) return false
        if (isLive) return false
        return personaName in settings.personas
    }
}
