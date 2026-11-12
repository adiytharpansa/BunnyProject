package com.bunny.engine.lontar

import com.bunny.engine.lontar.sabr.RoadOracle
import com.bunny.engine.lontar.sabr.SabrSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Langkah 3 gate: config, persona matrix, never live. */
class RoadOracleTest {

    private fun oracle(enabled: Boolean, personas: List<String> = listOf("VISIONOS")) =
        RoadOracle(SabrSettings(enabled, personas, 512L * 1024 * 1024))

    @Test
    fun configOffMeansClassic() {
        assertFalse(oracle(enabled = false).allowsSabr("VISIONOS", isLive = false))
    }

    @Test
    fun enabledNamedPersonaMayGo() {
        assertTrue(oracle(enabled = true).allowsSabr("VISIONOS", isLive = false))
    }

    @Test
    fun unnamedPersonaMayNot() {
        assertFalse(oracle(enabled = true).allowsSabr("ANDROID_VR", isLive = false))
    }

    @Test
    fun liveNeverGoes() {
        assertFalse(oracle(enabled = true).allowsSabr("VISIONOS", isLive = true))
    }
}
