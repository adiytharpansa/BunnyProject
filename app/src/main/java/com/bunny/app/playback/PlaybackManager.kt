package com.bunny.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import com.bunny.core.model.PlaybackStreams
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns Bunny's single ExoPlayer and its MediaSession for the whole app.
 *
 * One player, created lazily, shared by the player screen (which binds a
 * PlayerView to it) and by [PlaybackService] (which exposes the session so
 * playback continues in the background with a media notification). The
 * foreground-service contract is Media3's own: MediaSessionService plus
 * DefaultMediaNotification — the arrangement whose absence crashed CLEAR.
 *
 * Source order per video: generated DASH (adaptive quality) -> HLS ->
 * progressive file. A playback error on one source falls through to the
 * next exactly once each; the final error is what the UI reports.
 */
@UnstableApi
@Singleton
class PlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var userAgent: String = DEFAULT_USER_AGENT

    private val _player: ExoPlayer by lazy { buildPlayer() }
    val player: ExoPlayer get() = _player

    private var _session: MediaSession? = null

    /** The session for [PlaybackService]; built once, with the player. */
    fun session(): MediaSession {
        return _session ?: MediaSession.Builder(context, player).build().also { _session = it }
    }

    /**
     * Stream URLs are issued to a specific client persona; its user agent
     * must be presented when fetching them. Call before [play].
     */
    fun configure(playbackUserAgent: String?) {
        if (!playbackUserAgent.isNullOrBlank()) {
            userAgent = playbackUserAgent
            PlaybackDiagnostics.log("User-Agent pemutaran: persona mesin")
        }
    }

    /** Loads the best available source for [streams] and starts playing. */
    fun play(streams: PlaybackStreams, title: String) {
        val sources = buildSourceChain(streams)
        if (sources.isEmpty()) {
            PlaybackDiagnostics.log("Tidak ada sumber yang dapat diputar untuk \"$title\"")
            return
        }
        playChain(sources, title)
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        PlaybackDiagnostics.log("Pemutaran dihentikan")
    }

    // -------------------------------------------------------------- internals

    private fun buildPlayer(): ExoPlayer {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            // The buffer contract that kept CLEAR's player stable: generous
            // floor and ceiling, playback resumes quickly after a stall.
            .setBufferDurationsMs(15_000, 40_000, 2_500, 5_000)
            .setTargetBufferBytes(24 * 1024 * 1024)
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val name = when (state) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "STATE_$state"
                        }
                        PlaybackDiagnostics.log("Player: $name")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        PlaybackDiagnostics.log("Player error: ${error.errorCodeName} — ${error.message}")
                        fallback?.invoke()
                    }
                })
            }
    }

    private var fallback: (() -> Unit)? = null
    private var chainGeneration = 0

    private fun playChain(sources: List<Pair<String, MediaSource>>, title: String) {
        val (label, source) = sources.first()
        val rest = sources.drop(1)
        val generation = ++chainGeneration
        fallback = if (rest.isEmpty()) {
            null
        } else {
            {
                // A late error event from the previous source must not
                // advance the chain a second time.
                if (generation == chainGeneration) playChain(rest, title)
            }
        }
        PlaybackDiagnostics.log("Memutar \"$title\" via $label")
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    private fun buildSourceChain(streams: PlaybackStreams): List<Pair<String, MediaSource>> {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
        val chain = mutableListOf<Pair<String, MediaSource>>()

        streams.dashManifestContent?.takeIf { it.isNotBlank() }?.let { manifest ->
            // The manifest exists only in memory (Lontar generated it), so
            // the manifest URI is served from bytes while every segment URI
            // goes to the network — one delegating DataSource decides.
            val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
            val manifestUri = Uri.parse(MANIFEST_URI)
            val delegatingFactory = DataSource.Factory {
                val upstream = httpFactory.createDataSource()
                object : DataSource {
                    private var active: DataSource = upstream

                    override fun open(dataSpec: DataSpec): Long {
                        active = if (dataSpec.uri == manifestUri) {
                            ByteArrayDataSource(manifestBytes)
                        } else {
                            upstream
                        }
                        return active.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        active.read(buffer, offset, length)

                    override fun getUri(): Uri? = active.uri

                    override fun getResponseHeaders(): Map<String, List<String>> =
                        active.responseHeaders

                    override fun close() = active.close()

                    override fun addTransferListener(
                        transferListener: androidx.media3.datasource.TransferListener,
                    ) {
                        upstream.addTransferListener(transferListener)
                    }
                }
            }
            val source = DashMediaSource.Factory(delegatingFactory)
                .createMediaSource(MediaItem.fromUri(manifestUri))
            chain += "DASH (manifest buatan Lontar)" to source
        }
        streams.dashManifestUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val source = DashMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(url))
            chain += "DASH" to source
        }
        streams.hlsUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val source = HlsMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(url))
            chain += "HLS" to source
        }
        streams.progressiveUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val source = DefaultMediaSourceFactory(httpFactory)
                .createMediaSource(MediaItem.fromUri(url))
            chain += "progresif" to source
        }
        return chain
    }

    companion object {
        private const val MANIFEST_URI = "bunny://dash/manifest.mpd"
        private const val DEFAULT_USER_AGENT = "Bunny/0.1 (Android)"
    }
}
