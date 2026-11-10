package com.bunny.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackSelectionOverride
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
import com.bunny.app.MainActivity
import com.bunny.core.model.PlaybackStreams
import com.bunny.engine.arus.ArusFormat
import com.bunny.engine.arus.ArusStreamStore
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
        return _session ?: MediaSession.Builder(context, player)
            // Tapping the notification (or lock-screen card) brings the
            // running app back to the front instead of doing nothing.
            .setSessionActivity(openAppIntent())
            .build()
            .also { _session = it }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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

    /**
     * Loads the best available source for [streams] and starts playing.
     * Title, channel and artwork ride on every item as session metadata:
     * the media notification and lock-screen controls read them from
     * there — the same information the official app's playback
     * notification shows (REA dissection 2026-11-10).
     */
    fun play(
        streams: PlaybackStreams,
        title: String,
        channelName: String = "",
        artworkUrl: String? = null,
    ) {
        closeArusStore()
        clearQualityOverride()
        currentChainIsArus = false
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(channelName.takeIf { it.isNotEmpty() })
            .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
            .build()
        val sources = buildSourceChain(streams, metadata)
        if (sources.isEmpty()) {
            PlaybackDiagnostics.log("Tidak ada sumber yang dapat diputar untuk \"$title\"")
            return
        }
        playChain(sources, title)
    }

    fun stop() {
        closeArusStore()
        player.stop()
        player.clearMediaItems()
        PlaybackDiagnostics.log("Pemutaran dihentikan")
    }

    // ------------------------------------------------------------ Arus (SABR)

    private var arusStore: ArusStreamStore? = null

    private fun closeArusStore() {
        arusStore?.close()
        arusStore = null
    }

    private fun clearQualityOverride() {
        // A manual quality override names track groups of the PREVIOUS
        // video's manifest; it must never leak into a new video.
        if (selectedQualityHeight != null) {
            selectedQualityHeight = null
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .build()
        }
    }

    /**
     * Arus Fase A2: plays ONE video through a SABR conversation. The
     * store is owned here and closed by the next play/stop — a SABR
     * conversation must never outlive the playback it feeds. Falls
     * back through [playChain]'s error path like any other source:
     * if the conversation dies, the player's error is the report.
     */
    fun playArus(
        store: ArusStreamStore,
        video: ArusFormat,
        audio: ArusFormat,
        title: String,
        channelName: String = "",
        artworkUrl: String? = null,
    ) {
        closeArusStore()
        arusStore = store
        clearQualityOverride()
        currentChainIsArus = true
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(channelName.takeIf { it.isNotEmpty() })
            .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
            .build()
        val source = ArusPlayback.createMediaSource(store, video, audio, metadata)
        playChain(listOf("SABR (Arus)" to source), title)
    }

    // ------------------------------------------------- kontrol pemutar

    /** Distinct video heights the current source offers, best first. */
    fun videoQualities(): List<Int> {
        val heights = sortedSetOf(Comparator.reverseOrder<Int>())
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val height = group.getTrackFormat(i).height
                if (height > 0) heights += height
            }
        }
        return heights.toList()
    }

    private var selectedQualityHeight: Int? = null

    /** The manually chosen height, or null while adaptation is automatic. */
    fun selectedQuality(): Int? = selectedQualityHeight

    /**
     * Pins video selection to one height (null returns to adaptive).
     * Only meaningful for the DASH source; a progressive file simply
     * has the one height it has.
     */
    fun setVideoQuality(height: Int?) {
        selectedQualityHeight = height
        val builder = player.trackSelectionParameters.buildUpon()
        if (height == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            for (group in player.currentTracks.groups) {
                if (group.type != C.TRACK_TYPE_VIDEO) continue
                val indices = (0 until group.length).filter { index ->
                    group.getTrackFormat(index).height == height
                }
                if (indices.isNotEmpty()) {
                    builder.setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, indices),
                    )
                    break
                }
            }
        }
        player.trackSelectionParameters = builder.build()
        PlaybackDiagnostics.log(
            "Kualitas dipilih: ${height?.let { "${it}p" } ?: "Otomatis"}",
        )
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        PlaybackDiagnostics.log("Kecepatan putar: ${speed}x")
    }

    /** Current video size for the PiP aspect ratio; null when unknown. */
    fun videoWidthHeight(): Pair<Int, Int>? {
        val size = player.videoSize
        return if (size.width > 0 && size.height > 0) {
            size.width to size.height
        } else {
            null
        }
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
                        if (PlaybackWallPolicy.isWallSignature(
                                isArusChain = currentChainIsArus,
                                errorCode = error.errorCode,
                                positionMs = player.currentPosition,
                            )
                        ) {
                            // The wall: the remaining classic sources
                            // were minted by the same player response
                            // and share the refused URLs' fate (device
                            // evidence 2026-11-11: all rejected within
                            // seconds of the first), so skip them and
                            // go straight to the terminal recovery.
                            PlaybackDiagnostics.log(
                                "Dinding token: penolakan HTTP di tengah putar — sisa sumber klasik dilewati",
                            )
                            fallback = null
                        }
                        val step = fallback
                        if (step != null) {
                            step.invoke()
                        } else {
                            // Terminal: the chain has no next source, so
                            // this failure is the one the user experiences.
                            // Persist it as on-device telemetry (Fase 4).
                            PlaybackFailureLog.record(
                                context,
                                stage = "putar",
                                subject = currentTitle,
                                detail = "${error.errorCodeName} — ${error.message}",
                            )
                            // Merged paths (Fase A3): a dead chain earns
                            // one rescue from the OTHER engine, owned by
                            // the player screen through these hooks —
                            // classic death offers a SABR attempt, Arus
                            // death offers the classic chain. The screen
                            // gates each direction to one shot per video,
                            // so the hooks cannot loop.
                            if (currentChainIsArus) {
                                onArusChainDied?.invoke()
                            } else {
                                onClassicChainDied?.invoke(currentTitle)
                            }
                        }
                    }
                })
            }
    }

    private var fallback: (() -> Unit)? = null
    private var chainGeneration = 0
    private var currentTitle: String = ""
    private var currentChainIsArus = false

    /**
     * Arus recovery hook (Fase A3): invoked once when a CLASSIC chain
     * dies completely — every source refused, or the stream cut by
     * the mid-play token wall. The player screen owns the recovery
     * attempt; the manager only reports the death. Null while no
     * player screen is alive, in which case a dead chain stays dead.
     */
    var onClassicChainDied: ((String) -> Unit)? = null

    /** The reverse hook: invoked once when an ARUS chain dies. */
    var onArusChainDied: (() -> Unit)? = null

    /**
     * Session circuit breaker for Arus-first playback: set by the
     * player screen when fetching a SABR session fails at transport
     * level (the engine/persona is unreachable, not a per-video
     * refusal). While set, videos open on the classic chain directly
     * instead of paying a doomed SABR attempt each time. Lives here
     * because the manager is the app-session singleton; cleared only
     * by process restart, deliberately — a walled session rarely
     * heals mid-run.
     */
    @Volatile
    var arusSessionBroken: Boolean = false

    private fun playChain(sources: List<Pair<String, MediaSource>>, title: String) {
        currentTitle = title
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

    private fun buildSourceChain(
        streams: PlaybackStreams,
        metadata: MediaMetadata,
    ): List<Pair<String, MediaSource>> {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
        val chain = mutableListOf<Pair<String, MediaSource>>()
        fun item(uri: Uri): MediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()

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
                .createMediaSource(item(manifestUri))
            chain += "DASH (manifest buatan Lontar)" to source
        }
        streams.dashManifestUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val source = DashMediaSource.Factory(httpFactory)
                .createMediaSource(item(Uri.parse(url)))
            chain += "DASH" to source
        }
        streams.hlsUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val source = HlsMediaSource.Factory(httpFactory)
                .createMediaSource(item(Uri.parse(url)))
            chain += "HLS" to source
        }
        streams.progressiveUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val source = DefaultMediaSourceFactory(httpFactory)
                .createMediaSource(item(Uri.parse(url)))
            chain += "progresif" to source
        }
        return chain
    }

    companion object {
        private const val MANIFEST_URI = "bunny://dash/manifest.mpd"
        private const val DEFAULT_USER_AGENT = "Bunny/0.1 (Android)"
    }
}
