package com.bunny.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.bunny.core.model.PlaybackStreams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the UI needs to know about the running preview.
 *
 * - [firstFrameRendered]: the thumbnail may fade out only after this is
 *   true; before it the video surface is just black.
 * - [videoAspect]: display aspect ratio (width / height) of the video,
 *   so the card can center-crop instead of stretching.
 * - [failed]: every source in the chain errored; the card must go back
 *   to its thumbnail and the video must not be retried.
 */
data class PreviewStatus(
    val firstFrameRendered: Boolean = false,
    val videoAspect: Float? = null,
    val failed: Boolean = false,
)

/**
 * Feed previews, the official app's inline playback translated to
 * Bunny: while the user dwells on a Home card, a muted, low-resolution
 * preview plays inside the card. Deliberately a SEPARATE player from
 * [PlaybackManager] — a preview must never touch the media session,
 * the notification, or a real playback's state.
 *
 * Data honesty (Timbangan's spirit): previews are capped at 240p, carry
 * no audio track, keep only a few seconds buffered, and start only after
 * a dwell, so a scrolled-past video costs next to nothing.
 */
@UnstableApi
@Singleton
class FeedPreviewManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val playerLazy = lazy { buildPlayer() }

    /** The shared preview player; attach it to one card at a time. */
    val player: ExoPlayer get() = playerLazy.value

    private val _status = MutableStateFlow(PreviewStatus())

    /** Observable preview state; reset on every [start] and [stop]. */
    val status: StateFlow<PreviewStatus> = _status.asStateFlow()

    private var chain: List<MediaSource> = emptyList()
    private var chainIndex = 0
    private var generation = 0

    /**
     * Starts a preview. Returns false when [streams] offers nothing
     * playable, so the caller never marks a card as "previewing" for a
     * preview that does not exist.
     */
    fun start(streams: PlaybackStreams): Boolean {
        val sources = buildSources(streams)
        if (sources.isEmpty()) {
            PlaybackDiagnostics.log("Pratinjau dilewati: tidak ada sumber yang dapat diputar")
            return false
        }
        generation++
        chain = sources
        chainIndex = 0
        _status.value = PreviewStatus()
        playCurrent()
        return true
    }

    fun stop() {
        generation++
        chain = emptyList()
        chainIndex = 0
        _status.value = PreviewStatus()
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

    private fun buildPlayer(): ExoPlayer {
        // A preview needs a couple of seconds of runway, not ExoPlayer's
        // default 50 s: a scrolled-past card must not drag megabytes.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                PREVIEW_MIN_BUFFER_MS,
                PREVIEW_MAX_BUFFER_MS,
                PREVIEW_START_BUFFER_MS,
                PREVIEW_REBUFFER_MS,
            )
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                exo.volume = 0f
                // Short videos loop instead of freezing on the last frame.
                exo.repeatMode = Player.REPEAT_MODE_ONE
                // Preview frugality: a small picture, and no audio or
                // subtitle tracks at all (a muted preview must not
                // download audio it will never play).
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(426, 240)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                exo.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (chain.isEmpty()) return
                        _status.update { it.copy(firstFrameRendered = true) }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (chain.isEmpty()) return
                        if (videoSize.width <= 0 || videoSize.height <= 0) return
                        var aspect =
                            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                        if (videoSize.unappliedRotationDegrees % 180 != 0) {
                            aspect = 1f / aspect
                        }
                        _status.update { it.copy(videoAspect = aspect) }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (chain.isEmpty()) return
                        PlaybackDiagnostics.log(
                            "Pratinjau gagal (sumber ${chainIndex + 1}/${chain.size}): " +
                                error.errorCodeName,
                        )
                        if (chainIndex + 1 < chain.size) {
                            // A failed preview source quietly tries the
                            // next one; a failed preview is never user-facing.
                            chainIndex++
                            playCurrent()
                        } else {
                            // Chain exhausted: tell the UI so the card
                            // returns to its thumbnail instead of a black box.
                            _status.update { it.copy(failed = true) }
                        }
                    }
                })
            }
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

        // Buffer contract for previews (ms). Invariant required by
        // DefaultLoadControl: max >= min >= start and min >= rebuffer.
        const val PREVIEW_MIN_BUFFER_MS = 3_000
        const val PREVIEW_MAX_BUFFER_MS = 10_000
        const val PREVIEW_START_BUFFER_MS = 500
        const val PREVIEW_REBUFFER_MS = 1_000
    }
}
