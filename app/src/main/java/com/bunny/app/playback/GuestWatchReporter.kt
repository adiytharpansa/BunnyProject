package com.bunny.app.playback

import com.bunny.core.model.PlaybackTrackingUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selera Tamu (Langkah 1): the guest watch reporter. While a
 * video plays, it sends the two reports the player response
 * itself addressed for this view — "playback started" once, then
 * "watch time" roughly every fifteen seconds of actual playing,
 * carrying the watched segment's start and end (st/et, the pair
 * the web client's own reports carry). The addresses are the
 * server's own, tied to the session's persistent visitor
 * identity; nothing is fabricated, nothing is sent for local
 * files, and a failed report is silent — playback never waits
 * on bookkeeping.
 *
 * Whether these reports warm the guest identity is precisely
 * what the Langkah 2 probe judges; this class only makes the
 * reports honest and countable.
 */
@Singleton
class GuestWatchReporter @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Reports actually sent this process, for the probe panel. */
    private val _sentCount = MutableStateFlow(0)
    val sentCount: StateFlow<Int> = _sentCount.asStateFlow()

    fun start(
        tracking: PlaybackTrackingUrls,
        userAgent: String?,
        positionMs: () -> Long,
        isPlaying: () -> Boolean,
    ) {
        stop()
        job = scope.launch {
            tracking.playbackUrl?.let { fire(it, userAgent) }
            val watchtime = tracking.watchtimeUrl ?: return@launch
            var lastSentPositionMs = 0L
            while (isActive) {
                delay(TICK_MS)
                if (!isPlaying()) continue
                val nowMs = positionMs()
                val startSec = lastSentPositionMs / 1000.0
                val endSec = nowMs / 1000.0
                if (endSec <= startSec) continue
                fire(withSegment(watchtime, startSec, endSec), userAgent)
                lastSentPositionMs = nowMs
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun withSegment(url: String, startSec: Double, endSec: Double): String {
        if ("st=" in url || "et=" in url) return url
        val separator = if ("?" in url) "&" else "?"
        return url + separator + String.format(
            Locale.US,
            "st=%.1f&et=%.1f",
            startSec,
            endSec,
        )
    }

    private suspend fun fire(url: String, userAgent: String?) {
        try {
            val builder = Request.Builder().url(url)
            if (!userAgent.isNullOrBlank()) {
                builder.header("User-Agent", userAgent)
            }
            httpClient.newCall(builder.build()).execute().use { response ->
                // Any answer counts as "sent" — the research is
                // explicit that a 204 is NOT proof of recording;
                // the probe panel judges recording, not sending.
                if (response.code in 100..599) {
                    _sentCount.value = _sentCount.value + 1
                }
            }
        } catch (e: Exception) {
            // Bookkeeping never disturbs playback.
        }
    }

    companion object {
        private const val TICK_MS = 15_000L
    }
}
