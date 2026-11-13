package com.bunny.app.bug

import android.os.Debug
import android.os.Handler
import android.os.Looper

/** Memeriksa tiap [timeoutMs] apakah thread utama masih memproses pesan. */
internal class AnrWatchdog(
    private val timeoutMs: Long = 5_000L,
    private val onAnr: (mainThreadTrace: String) -> Unit,
) : Thread("anr-watchdog") {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var tick = 0L
    @Volatile private var reported = false

    private val ticker = Runnable {
        tick++
        reported = false
    }

    init { isDaemon = true }

    override fun run() {
        while (!isInterrupted) {
            val before = tick
            main.post(ticker)
            try {
                sleep(timeoutMs)
            } catch (_: InterruptedException) {
                return
            }
            if (tick == before && !reported && !Debug.isDebuggerConnected()) {
                reported = true
                val trace = Looper.getMainLooper().thread.stackTrace
                    .joinToString("\n") { "    at $it" }
                onAnr(trace)
            }
        }
    }
}
