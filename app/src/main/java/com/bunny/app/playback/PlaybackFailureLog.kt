package com.bunny.app.playback

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fase 4 — on-device playback failure telemetry.
 *
 * Every terminal playback failure (a load the engine refused, a stream
 * error the source chain could not fall back from) is appended here with
 * its timestamp, stage, subject and error. The log is shown in the
 * player's diagnostics block so the user can screenshot it — the same
 * evidence loop CrashRecorder established. It NEVER leaves the device:
 * Bunny has no server, so this is a notebook, not a phone-home.
 */
object PlaybackFailureLog {

    private const val FILE_NAME = "playback_failures.txt"
    private const val MAX_ENTRIES = 40
    private const val MAX_CHARS = 16_000

    @Synchronized
    fun record(context: Context, stage: String, subject: String, detail: String) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val line = "$stamp | $stage | $subject | $detail"
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val existing = if (file.exists()) file.readLines() else emptyList()
            file.writeText((existing + line).takeLast(MAX_ENTRIES).joinToString("\n"))
        } catch (ignored: Exception) {
            // Telemetry must never break playback.
        }
    }

    fun read(context: Context): String? = try {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (file.exists()) file.readText().take(MAX_CHARS).ifBlank { null } else null
    } catch (e: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        } catch (ignored: Exception) {
            // Nothing depends on the delete succeeding.
        }
    }
}
