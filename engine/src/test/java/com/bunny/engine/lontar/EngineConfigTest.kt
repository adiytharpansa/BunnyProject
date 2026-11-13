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
                 "seedQueries":[{"query":"topik uji","label":"Uji"}],
                 "playerPersonas":["IOS","ANDROID_VR"],
                 "androidClientVersion":"21.0.0",
                 "iosClientVersion":"21.5.0"}
                """.trimIndent(),
            ),
        )
        assertEquals("2.20990101.01.00", values.fallbackClientVersion)
        assertEquals("9.99.9", values.androidVrClientVersion)
        assertEquals(listOf("topik uji" to "Uji"), values.seedQueries)
        assertEquals(listOf("IOS", "ANDROID_VR"), values.playerPersonas)
        assertEquals("21.0.0", values.androidClientVersion)
        assertEquals("21.5.0", values.iosClientVersion)
    }

    @Test
    fun parse_floorEnabled_remoteSwitch() {
        // Absent key keeps the shipped default (on); an explicit
        // false silences the floor remotely.
        assertTrue(EngineConfig.parse(JSONObject("{}")).floorEnabled)
        assertTrue(EngineConfig.parse(JSONObject("""{"floorEnabled":true}""")).floorEnabled)
        assertTrue(!EngineConfig.parse(JSONObject("""{"floorEnabled":false}""")).floorEnabled)
    }

    @Test
    fun parse_personaListWithoutUsableNames_keepsDefaultLadder() {
        val values = EngineConfig.parse(
            JSONObject("""{"playerPersonas":["","  "]}"""),
        )
        assertEquals(EngineConfig.DEFAULTS.playerPersonas, values.playerPersonas)
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
    fun parse_visionOsClientVersion_remoteWinsDefaultOtherwise() {
        val remote = EngineConfig.parse(JSONObject("""{"visionOsClientVersion":"1.03"}"""))
        assertEquals("1.03", remote.visionOsClientVersion)
        val absent = EngineConfig.parse(JSONObject("""{}"""))
        assertEquals("1.04", absent.visionOsClientVersion)
    }

    @Test
    fun parse_radarAndCanaries_remoteWinsDefaultsOtherwise() {
        val remote = EngineConfig.parse(
            JSONObject(
                """{"canaryVideoIds":["abc123","def456"],""" +
                    """"radar":{"deepProbeOffsetBytes":5000000,"maxAgeHours":3}}""",
            ),
        )
        assertEquals(listOf("abc123", "def456"), remote.canaryVideoIds)
        assertEquals(5_000_000L, remote.radarDeepProbeOffsetBytes)
        assertEquals(3, remote.radarMaxAgeHours)
        val absent = EngineConfig.parse(JSONObject("""{}"""))
        assertEquals(
            listOf("b2DfMST_WE8", "aqz-KE-bpKQ"),
            absent.canaryVideoIds,
        )
        assertEquals(3_000_000L, absent.radarDeepProbeOffsetBytes)
        assertEquals(6, absent.radarMaxAgeHours)
    }

    @Test
    fun parse_killThreshold_fullObject_isApplied() {
        val values = EngineConfig.parse(
            JSONObject(
                """{"sabrKillThreshold":{"maxFailureRate":0.25,""" +
                    """"windowAttempts":30,"minAttempts":5}}""",
            ),
        )
        assertEquals(0.25, values.sabrKillThreshold.maxFailureRate, 0.0)
        assertEquals(30, values.sabrKillThreshold.windowAttempts)
        assertEquals(5, values.sabrKillThreshold.minAttempts)
    }

    @Test
    fun parse_killThreshold_absentOrMalformed_keepsSafeDefaults() {
        val absent = EngineConfig.parse(JSONObject("{}"))
        assertEquals(
            com.bunny.engine.lontar.sabr.SabrKillThreshold.DEFAULT,
            absent.sabrKillThreshold,
        )
        // Out-of-range rate, non-positive window and minimum:
        // every field falls back on its own.
        val malformed = EngineConfig.parse(
            JSONObject(
                """{"sabrKillThreshold":{"maxFailureRate":1.5,""" +
                    """"windowAttempts":0,"minAttempts":-3}}""",
            ),
        )
        assertEquals(
            com.bunny.engine.lontar.sabr.SabrKillThreshold.DEFAULT,
            malformed.sabrKillThreshold,
        )
    }

    @Test
    fun parse_killThreshold_minAboveWindow_isClampedIntoTheWindow() {
        val values = EngineConfig.parse(
            JSONObject(
                """{"sabrKillThreshold":{"windowAttempts":10,""" +
                    """"minAttempts":30}}""",
            ),
        )
        assertEquals(10, values.sabrKillThreshold.windowAttempts)
        assertEquals(10, values.sabrKillThreshold.minAttempts)
    }

    @Test
    fun session_remoteFallback_usedWhenHomepageHasNoVersion() {
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"AIzaTEST",
            "INNERTUBE_CONTEXT":{"client":{"visitorData":"CgtTEST123"}}});</script>"""
        val session = InnertubeSession.fromHomepageHtml(html, "2.20990101.01.00")
        assertEquals("2.20990101.01.00", session?.clientVersion)
    }
}
