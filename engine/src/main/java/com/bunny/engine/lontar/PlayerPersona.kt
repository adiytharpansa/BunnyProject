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

    /** Personas are tried in order until one returns playable streams. */
    val CHAIN: List<PlayerPersona> = listOf(ANDROID_VR)

    /**
     * The VR persona with a remotely configured client version applied
     * (Fase 4): the version appears both in the client context and in the
     * user agent, and both are substituted together — a mismatch between
     * them is exactly the kind of inconsistency the bot gate notices.
     */
    fun androidVr(clientVersion: String): PlayerPersona {
        if (clientVersion.isBlank() || clientVersion == ANDROID_VR.clientVersion) {
            return ANDROID_VR
        }
        return ANDROID_VR.copy(
            userAgent = ANDROID_VR.userAgent.replace(
                "oculus/${ANDROID_VR.clientVersion}",
                "oculus/$clientVersion",
            ),
            clientFields = ANDROID_VR.clientFields + ("clientVersion" to clientVersion),
        )
    }

    /** The persona chain for a (possibly remote-configured) VR version. */
    fun chain(clientVersion: String): List<PlayerPersona> = listOf(androidVr(clientVersion))
}
