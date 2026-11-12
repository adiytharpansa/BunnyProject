package com.bunny.engine.lontar

import org.json.JSONObject

/**
 * A client persona for the *player* endpoint — deliberately separate from
 * the WEB persona the metadata plane uses (see [LontarPersona]).
 *
 * Field findings (2026-11-10, from live responses): the WEB and TVHTML5
 * clients answer playability OK but strip streamingData down to a single
 * ciphered format, and the ANDROID app client rejects the web API key
 * outright — while ANDROID_VR returns the full format set (progressive +
 * adaptive up to 4K) with direct, unsigned URLs. So playback speaks VR;
 * no cipher solver is needed on this path.
 *
 * The bot gate is bound to the session, not the persona: a bare VR request
 * earns LOGIN_REQUIRED ("confirm you're not a bot") even from a clean
 * residential IP, while the SAME request carrying the homepage session's
 * visitorData returns playability OK with the full direct format set —
 * verified 2026-11-10 against the exact video a device test failed on
 * (baseline LOGIN_REQUIRED, with visitorData OK + 23 direct formats).
 * [LontarEngine] therefore threads the session's visitorData into every
 * player request.
 *
 * Distribution hardening (2026-11-10): the chain no longer rests on the
 * VR persona alone. A live probe from this project's own session
 * bootstrap tested five personas against a plain video and a music
 * video: ANDROID_VR, ANDROID and IOS all answered playability OK with
 * full direct-URL format sets (ANDROID returned the most formats; IOS
 * also offered an HLS manifest), while TVHTML5 answered UNPLAYABLE
 * ("page needs to reload") and TVHTML5_SIMPLY_EMBEDDED_PLAYER answered
 * ERROR (client retired). Only probed-and-proven personas ship here:
 * a persona YouTube retires tomorrow is a fallback that never fires,
 * and the chain's order is remotely configurable (see [EngineConfig]).
 */
data class PlayerPersona(
    val name: String,
    val host: String,
    val apiKey: String,
    val clientNameId: String,
    val userAgent: String,
    /** Fields for context.client in the player request body. */
    val clientFields: Map<String, Any>,
) {
    /** The request's client context; [visitorData] binds it to a session. */
    fun clientJson(visitorData: String? = null): JSONObject {
        val client = JSONObject()
        for ((key, value) in clientFields) client.put(key, value)
        if (!visitorData.isNullOrBlank()) client.put("visitorData", visitorData)
        return client
    }

    val clientVersion: String get() = clientFields["clientVersion"] as String
}

object PlayerPersonas {

    /**
     * The Oculus VR client of the official YouTube app. Its API key is the
     * public key shipped inside that app — the same arrangement every
     * third-party InnerTube client uses.
     */
    val ANDROID_VR = PlayerPersona(
        name = "ANDROID_VR",
        host = "https://youtubei.googleapis.com",
        apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
        clientNameId = "28",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1)",
        clientFields = mapOf(
            "clientName" to "ANDROID_VR",
            "clientVersion" to "1.65.10",
            "deviceMake" to "Oculus",
            "deviceModel" to "Quest 3",
            "osName" to "Android",
            "osVersion" to "12L",
            "androidSdkVersion" to 32,
            "hl" to LontarPersona.HL,
            "gl" to LontarPersona.GL,
        ),
    )

    /**
     * The official Android app client. Shares the generic Android API
     * key with the VR client (the web key is what it rejects — see
     * [LontarPersona]); probed 2026-11-10: playability OK with the
     * largest direct-URL format set of any persona tested.
     */
    val ANDROID = PlayerPersona(
        name = "ANDROID",
        host = "https://youtubei.googleapis.com",
        apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w",
        clientNameId = "3",
        userAgent = "com.google.android.youtube/20.10.38 " +
            "(Linux; U; Android 14; id_ID; SM-A155F; Build/UP1A.231005.007; " +
            "Cronet/132.0.6808.3)",
        clientFields = mapOf(
            "clientName" to "ANDROID",
            "clientVersion" to "20.10.38",
            "deviceMake" to "Samsung",
            "deviceModel" to "SM-A155F",
            "osName" to "Android",
            "osVersion" to "14",
            "androidSdkVersion" to 34,
            "hl" to LontarPersona.HL,
            "gl" to LontarPersona.GL,
        ),
    )

    /**
     * The official iOS app client. Probed 2026-11-10: playability OK
     * with direct adaptive formats, and an HLS manifest on content the
     * other personas served DASH-only — the chain's most different
     * fallback, valuable exactly because it fails differently.
     */
    val IOS = PlayerPersona(
        name = "IOS",
        host = "https://youtubei.googleapis.com",
        apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
        clientNameId = "5",
        userAgent = "com.google.ios.youtube/20.10.4 " +
            "(iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X; id_ID)",
        clientFields = mapOf(
            "clientName" to "IOS",
            "clientVersion" to "20.10.4",
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2",
            "osName" to "iPhone",
            "osVersion" to "18.3.2",
            "hl" to LontarPersona.HL,
            "gl" to LontarPersona.GL,
        ),
    )

    /**
     * The Vision Pro client (visionOS) of the official YouTube app —
     * since the 2026-11-11 re-persona, the HEAD of the classic ladder.
     * Probed 2026-11-10: the only persona whose media bytes survived
     * past the cold-start allowance from the project VM — the app
     * personas answered the player OK and then HTTP 403 at the 3 MB
     * mark, while VISIONOS read clean at both offsets. The ecosystem
     * converged on the same answer in Aug 2026 (yt-dlp's signed-out
     * defaults became visionos+web; NewPipeExtractor went
     * VISIONOS-only for streams) after ANDROID_VR began requiring a
     * GVS token. Re-probed 2026-11-11 at this shape (1.04 /
     * RealityDevice17,1, tracking NewPipe's dev line): 27 adaptive
     * formats with direct URLs (the 1.02 shape offered 19), HLS
     * manifest present, bytes HTTP 206 at 0 and at 3 MB. Shares the
     * iOS family's public API key.
     */
    val VISIONOS = PlayerPersona(
        name = "VISIONOS",
        host = "https://youtubei.googleapis.com",
        apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
        clientNameId = "101",
        userAgent = "com.google.ios.youtube/1.04 " +
            "(RealityDevice17,1; U; CPU visionOS 2_0 like Mac OS X; id_ID)",
        clientFields = mapOf(
            "clientName" to "VISIONOS",
            "clientVersion" to "1.04",
            "deviceMake" to "Apple",
            "deviceModel" to "RealityDevice17,1",
            "osName" to "visionOS",
            "osVersion" to "2.0",
            "hl" to LontarPersona.HL,
            "gl" to LontarPersona.GL,
        ),
    )

    /**
     * The TV app's "downgraded" shape (TVHTML5 5.x, Cobalt) — the
     * ecosystem's last token-free door beside VISIONOS (yt-dlp's kids
     * fallback since Jul 2026; the only anonymous door reported to
     * play non-embeddable kids content VISIONOS refuses). Probed from
     * the project VM 2026-11-13: it answered ONCE in full (playability
     * OK, 59 direct formats) — then every later knock from that
     * network met the `tcl` player experiment's UNPLAYABLE wall, and
     * the one answer's media URLs were refused at the byte layer
     * (the `n` parameter lock is the suspect; untested behind the
     * wall). It is therefore NOT a ladder persona: playback through
     * it would additionally need a JS cipher engine this project does
     * not have. It ships as a Radar candidate only — measured on real
     * home networks, never played — so the byte verdict comes from
     * the user's own network instead of a datacenter bucket. Uses the
     * public WEB API key, the arrangement TV clients use.
     */
    val TV_DOWNGRADED = PlayerPersona(
        name = "TV_DOWNGRADED",
        host = "https://youtubei.googleapis.com",
        apiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
        clientNameId = "7",
        userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
        clientFields = mapOf(
            "clientName" to "TVHTML5",
            "clientVersion" to "5.20260901",
            "hl" to LontarPersona.HL,
            "gl" to LontarPersona.GL,
        ),
    )

    /**
     * Personas the Radar measures WITHOUT admitting them to the
     * playback ladder: candidate doors whose health on real networks
     * is worth knowing before any build decision rests on them. The
     * Adaptive Ladder only ever orders configured ladder names, so a
     * candidate's statistics can never leak into playback.
     */
    val RADAR_CANDIDATES: List<PlayerPersona> = listOf(TV_DOWNGRADED)

    /**
     * Personas are tried in order until one returns playable streams.
     * VISIONOS heads the ladder (see its doc); ANDROID_VR remains as
     * the last resort — token-gated since Aug 2026 but selectively
     * enforced, so it still rescues some videos on some networks.
     * ANDROID and IOS stay defined for remote-config use but left the
     * shipped ladder in the re-persona.
     */
    val CHAIN: List<PlayerPersona> = listOf(VISIONOS, ANDROID_VR)

    /**
     * A persona with a remotely configured client version applied
     * (Fase 4): the version appears both in the client context and in
     * the user agent, and both are substituted together — a mismatch
     * between them is exactly the kind of inconsistency the bot gate
     * notices. Every shipped persona's user agent carries its version
     * as a "/<version>" segment, so one substitution serves all three.
     */
    private fun withVersion(persona: PlayerPersona, clientVersion: String): PlayerPersona {
        if (clientVersion.isBlank() || clientVersion == persona.clientVersion) {
            return persona
        }
        return persona.copy(
            userAgent = persona.userAgent.replace(
                "/${persona.clientVersion}",
                "/$clientVersion",
            ),
            clientFields = persona.clientFields + ("clientVersion" to clientVersion),
        )
    }

    /** The VR persona with a remotely configured client version applied. */
    fun androidVr(clientVersion: String): PlayerPersona =
        withVersion(ANDROID_VR, clientVersion)

    /** The Android app persona with a remotely configured version applied. */
    fun android(clientVersion: String): PlayerPersona =
        withVersion(ANDROID, clientVersion)

    /** The iOS app persona with a remotely configured version applied. */
    fun ios(clientVersion: String): PlayerPersona =
        withVersion(IOS, clientVersion)

    /** The visionOS persona with a remotely configured version applied. */
    fun visionOs(clientVersion: String): PlayerPersona =
        withVersion(VISIONOS, clientVersion)

    /**
     * The persona chain for a (possibly remote-configured) engine
     * config: the config's persona names, in order, each with its
     * configured client version. Unknown names are skipped — a config
     * typo must degrade the chain, never break it — and an empty
     * result falls back to the shipped [CHAIN].
     */
    fun chain(config: EngineConfig.Values): List<PlayerPersona> {
        val built = config.playerPersonas.mapNotNull { name ->
            when (name.trim().uppercase()) {
                "ANDROID_VR" -> androidVr(config.androidVrClientVersion)
                "ANDROID" -> android(config.androidClientVersion)
                "IOS" -> ios(config.iosClientVersion)
                "VISIONOS" -> visionOs(config.visionOsClientVersion)
                "TV_DOWNGRADED" -> TV_DOWNGRADED
                else -> null
            }
        }
        return built.ifEmpty { CHAIN }
    }
}
