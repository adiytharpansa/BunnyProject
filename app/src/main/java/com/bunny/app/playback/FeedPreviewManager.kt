package com.bunny.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.bunny.core.model.PlaybackStreams
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feed previews, the official app's inline playback translated to
 * Bunny: while the user dwells on a Home card, a muted, low-resolution
 * preview plays inside the card. Deliberately a SEPARATE player from
 * [PlaybackManager] — a preview must never touch the media session,
 * the notification, or a real playback's state.
 *
 * Data honesty (Timbangan's spirit): previews are capped at 240p and
 * start only after a dwell, so a scrolled-past video costs nothing.
 */
@UnstableApi
@Singleton
class FeedPreviewManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val playerLazy = lazy { buildPlayer() }

    /** The shared preview player; attach it to one card at a time. */
    val player: ExoPlayer get() = playerLazy.value

    private var chain: List<MediaSource> = emptyList()
    private var chainIndex = 0
    private var generation = 0

    fun start(streams: PlaybackStreams) {
        val sources = buildSources(streams)
        if (sources.isEmpty()) return
        generation++
        chain = sources
        chainIndex = 0
        playCurrent()
    }

    fun stop() {
        generation++
        chain = emptyList()
        if (playerLazy.isInitialized()) {
            player.stop()
            player.clearMediaItems()
        }
    }

    private fun playCurrent() {
        val source = chain.getOrNull(chainIndex) ?: return
        val gen = generation
        val exo = player
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        PlaybackDiagnostics.log("Pratinjau mulai (sumber ${chainIndex + 1}/${chain.size}, gen $gen)")
    }

    private fun buildPlayer(): ExoPlayer =
        ExoPlayer.Builder(context).build().also { exo ->
            exo.volume = 0f
            // Preview frugality: never pull more than a small picture.
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(426, 240)
                .build()
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // A failed preview source quietly tries the next
                    // one; a failed preview is never user-facing.
                    if (chainIndex + 1 < chain.size) {
                        chainIndex++
                        playCurrent()
                    }
                }
            })
        }

    private fun buildSources(streams: PlaybackStreams): List<MediaSource> {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(streams.playbackUserAgent ?: DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val out = mutableListOf<MediaSource>()

        streams.dashManifestContent?.takeIf { it.isNotBlank() }?.let { manifest ->
            // Same in-memory manifest serving as the real player: the
            // manifest URI resolves from bytes, segments from network.
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
            out += DashMediaSource.Factory(delegatingFactory)
                .createMediaSource(MediaItem.fromUri(manifestUri))
        }
        streams.dashManifestUrl?.takeIf { it.isNotBlank() }?.let { url ->
            out += DashMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(url))
        }
        streams.hlsUrl?.takeIf { it.isNotBlank() }?.let { url ->
            out += HlsMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(url))
        }
        streams.progressiveUrl?.takeIf { it.isNotBlank() }?.let { url ->
            out += DefaultMediaSourceFactory(httpFactory)
                .createMediaSource(MediaItem.fromUri(url))
        }
        return out
    }

    private companion object {
        const val MANIFEST_URI = "bunny://dash/manifest.mpd"
        const val DEFAULT_USER_AGENT = "Bunny/0.1 (Android)"
    }
}
