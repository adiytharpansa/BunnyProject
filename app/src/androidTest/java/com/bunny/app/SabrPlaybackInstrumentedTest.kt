package com.bunny.app

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bunny.engine.lontar.sabr.BandwidthMeter
import com.bunny.engine.lontar.sabr.SabrClientInfo
import com.bunny.engine.lontar.sabr.SabrDataSource
import com.bunny.engine.lontar.sabr.SabrFormat
import com.bunny.engine.lontar.sabr.SabrHttpException
import com.bunny.engine.lontar.sabr.SabrOutcome
import com.bunny.engine.lontar.sabr.SabrPump
import com.bunny.engine.lontar.sabr.SabrSession
import com.bunny.engine.lontar.sabr.SabrTransport
import com.bunny.engine.lontar.sabr.SpoolRoadState
import com.bunny.engine.lontar.sabr.SpoolStore
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 8 (Anyam), Langkah 3 — the device-layer verdict, automated.
 *
 * The A15 rounds of 2026-11-12 proved the pump and the spool right
 * and found the faults in the last layer: the real Media3 player
 * consuming the spool through [SabrDataSource]. That layer cannot
 * run in a JVM unit test, so this instrumented test runs it on a
 * real Android runtime (Firebase Test Lab virtual device):
 *
 * 1. the recorded SABR responses (assets/sabr, captured 2026-11-12
 *    from a real VISIONOS session, video 2Jk6C3Asn1U) are pumped
 *    into a real spool by the real [SabrPump];
 * 2. the spool tracks are marked complete (the spooled ~30 s stand
 *    as a finished short video — the offline/Panen case);
 * 3. the EXACT source chain PlaybackManager.buildSabrSource builds
 *    — two ProgressiveMediaSources over bunny-sabr:// URIs merged
 *    with MergingMediaSource(true, …) — is handed to a real
 *    ExoPlayer;
 * 4. the player must reach READY and its position must advance
 *    past 3 s. Anything less is the round-1..4 stall, caught here
 *    instead of on the user's phone.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SabrPlaybackInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun assetText(name: String): String =
        instrumentation.context.assets.open("sabr/$name")
            .bufferedReader().use { it.readText() }

    private fun fixture(name: String): ByteArray =
        Base64.getMimeDecoder().decode(assetText("$name.bin.b64"))

    private val manifest: JSONObject by lazy { JSONObject(assetText("manifest.json")) }

    private fun spec(i: Int): JSONObject =
        manifest.getJSONArray("pairs").getJSONObject(i).getJSONObject("request")

    private fun optLong(o: JSONObject, key: String): Long =
        if (o.isNull(key)) 0L else o.getLong(key)

    /** Mirrors SabrReplayTest.buildSession — the recorded session. */
    private fun buildSession(): SabrSession {
        val audio = manifest.getJSONObject("audioFormat")
        val video = manifest.getJSONObject("videoFormat")
        val adopted = spec(2).getJSONArray("selected").getJSONObject(1)
        val formats = listOf(
            SabrFormat(
                itag = audio.getInt("itag"), mimeType = "audio/webm; codecs=\"opus\"",
                bitrate = 160_000,
                lastModified = optLong(audio, "lastModified"),
                xtags = if (audio.isNull("xtags")) null else audio.getString("xtags"),
            ),
            SabrFormat(
                itag = video.getInt("itag"), mimeType = "video/mp4; codecs=\"avc1.640028\"",
                bitrate = 2_500_000,
                lastModified = optLong(video, "lastModified"),
                xtags = if (video.isNull("xtags")) null else video.getString("xtags"),
            ),
            SabrFormat(
                itag = adopted.getInt("itag"),
                mimeType = "video/mp4; codecs=\"av01.0.08M.08\"",
                bitrate = 4_000_000,
                lastModified = optLong(adopted, "lastModified"),
                xtags = if (adopted.isNull("xtags")) null else adopted.getString("xtags"),
            ),
        )
        return SabrSession(
            videoId = manifest.getString("videoId"),
            initialUrl = "https://fixture.invalid/sabr",
            ustreamerConfig = Base64.getDecoder()
                .decode(spec(0).getString("ustreamerB64")),
            clientInfo = SabrClientInfo(101, "1.04", "visionOS", "2.0"),
            durationSeconds = manifest.getInt("durationSeconds"),
            formats = formats,
            audioItag = audio.getInt("itag"),
            videoItag = video.getInt("itag"),
            cpn = "REPLAYCPN0000000",
        )
    }

    private object FixedBandwidth : BandwidthMeter {
        override fun estimateBps(): Long = 5_000_000
        override fun observe(bytes: Int, durationMs: Long) = Unit
    }

    /** Serves the recordings in order, then ends the script (410). */
    private class QueueTransport(responses: List<ByteArray>) : SabrTransport {
        private val queue = ArrayDeque(responses)
        override suspend fun post(url: String, body: ByteArray): ByteArray =
            if (queue.isEmpty()) throw SabrHttpException(410) else queue.removeFirst()
    }

    @Test(timeout = 300_000)
    fun recordedSabrSpoolPlaysThroughTheRealPlayerStack() {
        val targetContext = instrumentation.targetContext
        val session = buildSession()

        // 1 — Pump the recordings into a real spool.
        val spoolDir = File(targetContext.cacheDir, "ftl-sabr-spool")
        spoolDir.deleteRecursively()
        val spool = SpoolStore(spoolDir).open(session.videoId)
        val pump = SabrPump(
            session = session,
            spool = spool,
            transport = QueueTransport(
                listOf(fixture("resp0"), fixture("resp1"), fixture("resp2"))
            ),
            bandwidth = FixedBandwidth,
            sleeper = { },
        )
        val outcome = runBlocking { pump.run(10) }
        assertTrue(
            "pump should end on the scripted 410, was $outcome",
            outcome is SabrOutcome.HttpFailed,
        )
        val audio = session.wireProvenFormat(audio = true)
        val video = session.wireProvenFormat(audio = false)
        assertNotNull("audio must be wire-proven", audio)
        assertNotNull("video must be wire-proven", video)
        assertTrue(spool.track(audio!!.itag).fileLength() > 0)
        assertTrue(spool.track(video!!.itag).fileLength() > 0)

        // 2 — The spooled media stands as a finished short video.
        val road = SpoolRoadState()
        road.markComplete(audio.itag)
        road.markComplete(video.itag)

        // 3 — The exact chain PlaybackManager.buildSabrSource builds.
        val factory = DataSource.Factory { SabrDataSource(spool, road) }
        fun source(itag: Int): ProgressiveMediaSource {
            val item = MediaItem.Builder()
                .setUri(Uri.parse("bunny-sabr://${session.videoId}/$itag"))
                .build()
            return ProgressiveMediaSource.Factory(factory).createMediaSource(item)
        }
        val merged = MergingMediaSource(true, source(video.itag), source(audio.itag))

        // 4 — A real player must reach READY and advance.
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(targetContext).build().also {
                    it.setMediaSource(merged)
                    it.prepare()
                    it.playWhenReady = true
                }
            }
            var sawReady = false
            var maxPositionMs = 0L
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                var state = Player.STATE_IDLE
                var position = 0L
                instrumentation.runOnMainSync {
                    state = player!!.playbackState
                    position = player!!.currentPosition
                }
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    sawReady = true
                }
                if (position > maxPositionMs) maxPositionMs = position
                if (sawReady && maxPositionMs > 3_000) break
                Thread.sleep(500)
            }
            assertTrue("player never became READY", sawReady)
            assertTrue(
                "player position never advanced (max $maxPositionMs ms)",
                maxPositionMs > 3_000,
            )
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
    }
}
