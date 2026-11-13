package com.bunny.engine.lontar

/**
 * Fase 7-A — failure evidence for the metadata plane.
 *
 * The user's rule (2026-11-11) is that everything Bunny shows is
 * REAL data with no fallbacks. The honest weakness of that rule is
 * silence: the engine's best-effort surfaces (comments, related,
 * channel headers) read a failure as "empty", so a broken pipe and
 * a genuinely empty answer look identical from the outside.
 *
 * This reporter is the notebook those surfaces write in BEFORE
 * they go quiet: surface name ("komentar", "terkait", "kanal",
 * "detail", "balasan komentar"), failure class ("HTTP 403",
 * "jaringan", "urai", "kosong") and a short detail. The app owns
 * the implementation (an on-device file shown in the player's
 * Diagnostics block); the engine only reports. Nothing here ever
 * leaves the device.
 */
fun interface SurfaceFailureReporter {
    fun record(surface: String, kind: String, detail: String)

    companion object {
        /** The default: evidence goes nowhere (tests, previews). */
        val NONE: SurfaceFailureReporter = SurfaceFailureReporter { _, _, _ -> }
    }
}
