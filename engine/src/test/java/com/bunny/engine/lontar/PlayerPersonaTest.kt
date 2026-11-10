package com.bunny.engine.lontar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
