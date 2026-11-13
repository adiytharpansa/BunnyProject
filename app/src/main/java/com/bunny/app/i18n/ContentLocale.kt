package com.bunny.app.i18n

import java.util.Locale

/** Bahasa konten dari bahasa perangkat, untuk diteruskan ke Lontar. */
data class ContentLocale(val hl: String, val gl: String) {
    companion object {
        fun fromDevice(locale: Locale = Locale.getDefault()): ContentLocale {
            val tag = locale.toLanguageTag()              // "id-ID", "en-US", "pt-BR", "zh-Hant-TW"
            val lang = tag.substringBefore('-')           // "id", "en", "pt", "zh"
            val region = locale.country                   // "ID", "US", "BR", "TW" (bisa kosong)
            // Bahasa dengan varian wilayah yang berbeda di YouTube butuh "xx-YY".
            val hl = if (lang in setOf("pt", "zh", "en") && region.isNotEmpty()) "$lang-$region" else lang
            return ContentLocale(hl = hl, gl = region.ifEmpty { "US" })
        }
    }
}
