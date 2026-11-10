package com.bunny.engine.lontar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPersonaTest {

    @Test
    fun clientJson_includesVisitorData_whenProvided() {
        val client = PlayerPersonas.ANDROID_VR.clientJson("CgtTESTVISITOR")
        assertEquals("CgtTESTVISITOR", client.getString("visitorData"))
        assertEquals("ANDROID_VR", client.getString("clientName"))
    }

    @Test
    fun clientJson_omitsVisitorData_whenAbsent() {
        assertFalse(PlayerPersonas.ANDROID_VR.clientJson().has("visitorData"))
        assertFalse(PlayerPersonas.ANDROID_VR.clientJson("  ").has("visitorData"))
    }

    @Test
    fun chain_defaultConfig_shipsProbedLadder() {
        val names = PlayerPersonas.chain(EngineConfig.DEFAULTS).map { it.name }
        assertEquals(listOf("VISIONOS", "ANDROID_VR"), names)
    }

    @Test
    fun chain_configOrderAndVersions_win() {
        val config = EngineConfig.DEFAULTS.copy(
            playerPersonas = listOf("ios", "ANDROID"),
            androidClientVersion = "21.0.0",
            iosClientVersion = "21.5.0",
        )
        val chain = PlayerPersonas.chain(config)
        assertEquals(listOf("IOS", "ANDROID"), chain.map { it.name })
        assertEquals("21.5.0", chain[0].clientVersion)
        assertTrue(chain[0].userAgent.contains("/21.5.0"))
        assertEquals("21.0.0", chain[1].clientVersion)
        // The shipped personas are untouched by substitution.
        assertEquals("20.10.4", PlayerPersonas.IOS.clientVersion)
        assertEquals("20.10.38", PlayerPersonas.ANDROID.clientVersion)
    }

    @Test
    fun chain_unknownNamesSkipped_neverEmpty() {
        val unknownOnly = EngineConfig.DEFAULTS.copy(
            playerPersonas = listOf("TIDAK_ADA"),
        )
        assertEquals(PlayerPersonas.CHAIN, PlayerPersonas.chain(unknownOnly))
        val mixed = EngineConfig.DEFAULTS.copy(
            playerPersonas = listOf("TIDAK_ADA", "ANDROID"),
        )
        assertEquals(listOf("ANDROID"), PlayerPersonas.chain(mixed).map { it.name })
    }

    @Test
    fun visionOs_probedShape_headsDefaultChain() {
        // Re-persona 2026-11-11: VISIONOS (1.04 / RealityDevice17,1,
        // re-probed that day) heads the shipped ladder; ANDROID_VR is
        // the last resort.
        assertEquals("101", PlayerPersonas.VISIONOS.clientNameId)
        assertEquals("1.04", PlayerPersonas.VISIONOS.clientVersion)
        assertEquals(PlayerPersonas.VISIONOS, PlayerPersonas.CHAIN.first())
        assertEquals(PlayerPersonas.ANDROID_VR, PlayerPersonas.CHAIN.last())
        val chain = PlayerPersonas.chain(
            EngineConfig.DEFAULTS.copy(playerPersonas = listOf("VISIONOS", "ANDROID_VR")),
        )
        assertEquals(listOf("VISIONOS", "ANDROID_VR"), chain.map { it.name })
        val bumped = PlayerPersonas.chain(
            EngineConfig.DEFAULTS.copy(
                playerPersonas = listOf("VISIONOS"),
                visionOsClientVersion = "1.05",
            ),
        )
        assertEquals("1.05", bumped.single().clientVersion)
        assertTrue(bumped.single().userAgent.contains("/1.05"))
    }
}
