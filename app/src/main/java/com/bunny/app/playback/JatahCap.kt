package com.bunny.app.playback

/**
 * Fase 8, mechanism 6 — the Jatah quality ceiling: as the month's
 * remaining data budget thins, the SABR road's opening video pin
 * (and its adoption preference) is capped at a lower height. The
 * ladder is deliberately coarse — four honest steps, computed
 * from the same quota/usage figures the Jatah warning shows:
 *
 *   more than half left   → no cap
 *   a quarter to a half   → 720p
 *   a tenth to a quarter  → 480p
 *   under a tenth / over  → 360p
 *
 * No quota set means no cap: the ceiling is the user's own
 * budget speaking, never an invented limit.
 */
object JatahCap {

    fun heightCapPx(quotaBytes: Long?, usedBytes: Long): Int? {
        if (quotaBytes == null || quotaBytes <= 0) return null
        val remaining = quotaBytes - usedBytes
        return when {
            remaining > quotaBytes / 2 -> null
            remaining > quotaBytes / 4 -> 720
            remaining > quotaBytes / 10 -> 480
            else -> 360
        }
    }
}
