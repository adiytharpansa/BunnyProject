package com.bunny.app.playback

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the most recent uncaught exception (plus the diagnostics ring)
 * so a hard crash survives the process death that erases the in-memory
 * log. Installed first in BunnyApplication.onCreate; the stored report is
 * shown on the home screen at next launch for the user to screenshot.
 * The previous default handler still runs afterwards, so the system's
 * own crash flow is unchanged.
 */
object CrashRecorder {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 16_000

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = StringWriter().also { sw ->
                    throwable.printStackTrace(PrintWriter(sw))
                }.toString()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val events = PlaybackDiagnostics.events.value.takeLast(30)
                    .joinToString("\n").ifEmpty { "—" }
                val text = buildString {
                    appendLine("Waktu: $stamp")
                    appendLine("Benang: ${thread.name}")
                    appendLine()
                    appendLine(trace.trim())
                    appendLine()
                    appendLine("Diagnostik terakhir:")
                    append(events)
                }.take(MAX_CHARS)
                File(appContext.filesDir, FILE_NAME).writeText(text)
            } catch (ignored: Exception) {
                // Recording must never mask the original crash.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
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
