package com.bunny.engine.lontar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarTest {

    @Test
    fun classifier_fullTable() {
        // Player OK + both ranges read: the only SEHAT.
        assertEquals(
            PersonaHealthState.SEHAT,
            RadarClassifier.classify("OK", 206, 206, networkFailed = false),
        )
        // The field-proven trap: player OK, start reads, deep refused.
        assertEquals(
            PersonaHealthState.TERGERBANG_GVS,
            RadarClassifier.classify("OK", 206, 403, networkFailed = false),
        )
        assertEquals(
            PersonaHealthState.TERGERBANG_GVS,
            RadarClassifier.classify("OK", 403, null, networkFailed = false),
        )
        // Player-layer refusals.
        assertEquals(
            PersonaHealthState.DITOLAK_PLAYER,
            RadarClassifier.classify("LOGIN_REQUIRED", null, null, false),
        )
        assertEquals(
            PersonaHealthState.DITOLAK_PLAYER,
            RadarClassifier.classify("UNPLAYABLE", null, null, false),
        )
        // Network weather never convicts the persona.
        assertEquals(
            PersonaHealthState.GAGAL_JARINGAN,
            RadarClassifier.classify("OK", 206, 206, networkFailed = true),
        )
        assertEquals(
            PersonaHealthState.GAGAL_JARINGAN,
            RadarClassifier.classify(null, null, null, networkFailed = true),
        )
        // No format big enough to deep-probe: honest unknown.
        assertEquals(
            PersonaHealthState.TAK_DIKETAHUI,
            RadarClassifier.classify("OK", 206, null, networkFailed = false),
        )
        assertEquals(
            PersonaHealthState.TAK_DIKETAHUI,
            RadarClassifier.classify(null, null, null, networkFailed = false),
        )
    }

    @Test
    fun ladder_radarRejectedLeavesLadder() {
        val ordered = LadderBuilder.order(
            configuredNames = listOf("VISIONOS", "ANDROID_VR"),
            radar = mapOf("VISIONOS" to PersonaHealthState.DITOLAK_PLAYER),
            stats = emptyMap(),
        )
        assertEquals(listOf("ANDROID_VR"), ordered)
    }

    @Test
    fun ladder_healthyFirstGatedLast() {
        val ordered = LadderBuilder.order(
            configuredNames = listOf("VISIONOS", "ANDROID_VR", "ANDROID"),
            radar = mapOf(
                "VISIONOS" to PersonaHealthState.TERGERBANG_GVS,
                "ANDROID" to PersonaHealthState.SEHAT,
            ),
            stats = emptyMap(),
        )
        // SEHAT first, the unmeasured middle keeps config position,
        // the gated persona sinks but is not banned.
        assertEquals(listOf("ANDROID", "ANDROID_VR", "VISIONOS"), ordered)
    }

    @Test
    fun ladder_statisticsBreakTies() {
        val ordered = LadderBuilder.order(
            configuredNames = listOf("VISIONOS", "ANDROID_VR"),
            radar = emptyMap(),
            stats = mapOf(
                "VISIONOS" to PersonaStatSnapshot(sehat = 1, ditolak = 9),
                "ANDROID_VR" to PersonaStatSnapshot(sehat = 9, ditolak = 1),
            ),
        )
        assertEquals(listOf("ANDROID_VR", "VISIONOS"), ordered)
    }

    @Test
    fun ladder_noEvidenceKeepsConfiguredOrder() {
        val configured = listOf("VISIONOS", "ANDROID_VR")
        assertEquals(
            configured,
            LadderBuilder.order(configured, emptyMap(), emptyMap()),
        )
    }

    @Test
    fun describe_explainsInPlainTerms() {
        val text = LadderBuilder.describe(
            orderedNames = listOf("VISIONOS", "ANDROID_VR"),
            radar = mapOf("VISIONOS" to PersonaHealthState.SEHAT),
            stats = mapOf(
                "ANDROID_VR" to PersonaStatSnapshot(sehat = 8, tergerbang = 2),
            ),
        )
        assertTrue(text.contains("VISIONOS (Radar: sehat)"))
        assertTrue(text.contains("ANDROID_VR (statistik: 8/10 sehat)"))
    }
}
