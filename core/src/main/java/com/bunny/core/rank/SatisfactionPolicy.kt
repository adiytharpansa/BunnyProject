package com.bunny.core.rank

/**
 * Kapan layak bertanya "Sepadan dengan waktu Anda?". Pertanyaan yang terlalu
 * sering adalah gangguan, jadi dibatasi: tontonan bermakna saja, jeda
 * antarpertanyaan, batas harian, dan pencuplikan acak.
 */
object SatisfactionPolicy {

    const val COOLDOWN_MS = 6 * 60 * 60 * 1000L
    const val MAX_PER_DAY = 3
    const val SAMPLE_RATE = 0.30

    fun isMeaningfulWatch(watchedMs: Long, durationMs: Long, completed: Boolean): Boolean {
        if (completed) return true
        if (durationMs > 0L) {
            return watchedMs >= 60_000L && watchedMs.toDouble() / durationMs >= 0.4
        }
        return watchedMs >= 5 * 60_000L
    }

    /** [roll] adalah angka acak 0..1 dari pemanggil, supaya fungsi ini mudah dites. */
    fun shouldAsk(
        meaningful: Boolean,
        sinceLastAskMs: Long,
        askedToday: Int,
        roll: Double,
    ): Boolean =
        meaningful &&
            sinceLastAskMs >= COOLDOWN_MS &&
            askedToday < MAX_PER_DAY &&
            roll < SAMPLE_RATE
}
