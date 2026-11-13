package com.bunny.app.playback

import android.content.Context
import com.bunny.engine.lontar.SurfaceFailureReporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fase 7-A — on-device metadata failure telemetry, the sibling of
 * [PlaybackFailureLog] for the data plane.
 *
 * Every time a best-effort metadata surface (comments, related,
 * channel header, details) fails or reads suspiciously empty, the
 * engine notes it here BEFORE the surface goes quiet: timestamp,
 * surface, failure class, detail. Shown in the player's
 * Diagnostics block, under its own heading, so "sometimes empty"
 * becomes a readable cause instead of a mystery. It NEVER leaves
 * the device.
 */
object MetadataFailureLog {

    private const val FILE_NAME = "metadata_failures.txt"
    private const val MAX_ENTRIES = 40
    private const val MAX_CHARS = 16_000

    @Synchronized
    fun record(context: Context, surface: String, kind: String, detail: String) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val line = "$stamp | $surface | $kind | $detail"
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val existing = if (file.exists()) file.readLines() else emptyList()
            file.writeText((existing + line).takeLast(MAX_ENTRIES).joinToString("\n"))
        } catch (ignored: Exception) {
            // Telemetry must never break a screen.
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

/** The engine's [SurfaceFailureReporter], backed by [MetadataFailureLog]. */
class AppSurfaceFailureReporter(context: Context) : SurfaceFailureReporter {

    private val appContext: Context = context.applicationContext

    override fun record(surface: String, kind: String, detail: String) {
        MetadataFailureLog.record(appContext, surface, kind, detail)
    }
}
