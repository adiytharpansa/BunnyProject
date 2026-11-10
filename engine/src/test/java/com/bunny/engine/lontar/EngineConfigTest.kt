package com.bunny.engine.lontar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineConfigTest {

    @Test
    fun parse_fullDocument_overridesEverything() {
        val values = EngineConfig.parse(
            JSONObject(
                """
                {"fallbackClientVersion":"2.20990101.01.00",
                 "androidVrClientVersion":"9.99.9",
                 "seedQueries":[{"query":"topik uji","label":"Uji"}]}
                """.trimIndent(),
            ),
        )
        assertEquals("2.20990101.01.00", values.fallbackClientVersion)
        assertEquals("9.99.9", values.androidVrClientVersion)
        assertEquals(listOf("topik uji" to "Uji"), values.seedQueries)
    }

    @Test
    fun parse_emptyDocument_keepsDefaults() {
        val values = EngineConfig.parse(JSONObject("{}"))
        assertEquals(EngineConfig.DEFAULTS, values)
    }

    @Test
    fun parse_partialAndMalformed_fallsBackPerField() {
        val values = EngineConfig.parse(
            JSONObject(
                """
                {"androidVrClientVersion":"1.70.0",
                 "seedQueries":[{"query":"","label":"Kosong"},{"query":"ok","label":""}]}
                """.trimIndent(),
            ),
        )
        // The one valid field is applied...
        assertEquals("1.70.0", values.androidVrClientVersion)
        // ...the untouched field keeps its default...
        assertEquals(
            EngineConfig.DEFAULTS.fallbackClientVersion,
            values.fallbackClientVersion,
        )
        // ...and a seed list with no usable entry keeps the default set.
        assertEquals(EngineConfig.DEFAULTS.seedQueries, values.seedQueries)
    }

    @Test
    fun androidVr_remoteVersion_substitutesContextAndAgent() {
        val persona = PlayerPersonas.androidVr("1.70.0")
        assertEquals("1.70.0", persona.clientVersion)
        assertTrue(persona.userAgent.contains("oculus/1.70.0"))
        // The shipped default is untouched by substitution.
        assertEquals("1.65.10", PlayerPersonas.ANDROID_VR.clientVersion)
    }

    @Test
    fun androidVr_defaultVersion_returnsShippedPersona() {
        assertEquals(PlayerPersonas.ANDROID_VR, PlayerPersonas.androidVr("1.65.10"))
        assertEquals(PlayerPersonas.ANDROID_VR, PlayerPersonas.androidVr(""))
    }

    @Test
    fun session_remoteFallback_usedWhenHomepageHasNoVersion() {
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"AIzaTEST",
            "INNERTUBE_CONTEXT":{"client":{"visitorData":"CgtTEST123"}}});</script>"""
        val session = InnertubeSession.fromHomepageHtml(html, "2.20990101.01.00")
        assertEquals("2.20990101.01.00", session?.clientVersion)
    }
}
