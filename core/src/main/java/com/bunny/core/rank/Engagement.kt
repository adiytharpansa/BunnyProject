package com.bunny.core.rank

import kotlin.math.pow

/**
 * Dari waktu tonton nyata menjadi bobot kepuasan. Ini pengganti lokal untuk
 * "valued watch time": menonton sampai habis berarti kuat, kabur dalam
 * hitungan detik berarti sinyal negatif lemah.
 */
object Engagement {

    private const val DAY_MS = 86_400_000L

    fun fraction(watchedMs: Long, durationMs: Long): Double =
        if (durationMs <= 0L) 0.0 else (watchedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)

    /** Bobot −0,2 … 1,0. Durasi tak diketahui (siaran langsung) memakai ambang waktu mutlak. */
    fun weight(watchedMs: Long, durationMs: Long, completed: Boolean): Double {
        if (completed) return 1.0
        if (durationMs <= 0L) {
            return when {
                watchedMs >= 5 * 60_000L -> 0.7
                watchedMs >= 60_000L -> 0.4
                watchedMs >= 15_000L -> 0.1
                else -> -0.2
            }
        }
        val f = fraction(watchedMs, durationMs)
        return when {
            f >= 0.85 -> 1.0
            f >= 0.5 -> 0.7
            f >= 0.25 -> 0.4
            watchedMs >= 30_000L -> 0.15
            else -> -0.2
        }
    }

    /** Peluruhan eksponensial: setelah [halfLifeDays] hari bobot tinggal separuh. */
    fun decay(ageMs: Long, halfLifeDays: Double): Double {
        if (ageMs <= 0L) return 1.0
        val halfLifeMs = halfLifeDays * DAY_MS
        return 0.5.pow(ageMs / halfLifeMs)
    }
}
