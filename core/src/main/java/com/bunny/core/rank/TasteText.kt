package com.bunny.core.rank

/**
 * Tokenizer bersama Mesin Selera. Ranker (core) dan TasteRepository (app)
 * memakai fungsi yang sama, jadi kata yang dicatat adalah kata yang dicocokkan.
 */
object TasteText {

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private val STOPWORDS = setOf(
        "yang", "dan", "dari", "untuk", "dengan", "ini", "itu", "pada", "atau",
        "juga", "akan", "saya", "kamu", "kita", "kami", "tidak", "bisa", "ada",
        "apa", "cara", "paling", "dalam", "semua", "lengkap", "terbaru",
        "video", "official", "full", "part", "episode",
        "the", "and", "for", "with", "you", "how", "what", "new", "this", "that",
    )

    /** Kata unik (huruf kecil, minimal 3 karakter, bukan angka murni, bukan stopword). */
    fun words(text: String): Set<String> =
        WORD.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 3 && it !in STOPWORDS && !it.all { c -> c.isDigit() } }
            .toSet()
}
