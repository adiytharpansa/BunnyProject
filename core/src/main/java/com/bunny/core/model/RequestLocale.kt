package com.bunny.core.model

/**
 * Bahasa konten yang diminta dari YouTube (hl = bahasa, gl = negara).
 * Diisi :app dari bahasa perangkat; bawaan Indonesia — perilaku lama
 * Bunny sebelum bahasa mengikuti perangkat.
 */
data class RequestLocale(
    val hl: String = "id",
    val gl: String = "ID",
)
