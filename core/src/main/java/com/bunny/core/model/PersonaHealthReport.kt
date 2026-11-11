package com.bunny.core.model

/**
 * One player persona's health as the Engine Health Radar last measured
 * it (Lontar Hidup, Fase 6). [state] is one of SEHAT, TERGERBANG_GVS,
 * DITOLAK_PLAYER, GAGAL_JARINGAN, TAK_DIKETAHUI — measured at the byte
 * layer, on this device and network, never guessed.
 */
data class PersonaHealthReport(
    val persona: String,
    val state: String,
    val checkedAtEpochMs: Long,
    /** Coarse network class of the measurement: WIFI / SELULER / LAINNYA. */
    val networkClass: String,
)
