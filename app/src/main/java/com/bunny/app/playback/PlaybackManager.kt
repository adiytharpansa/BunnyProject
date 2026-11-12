package com.bunny.app.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import androidx.media3.session.MediaSession
import com.bunny.app.MainActivity
import com.bunny.core.model.PlaybackStreams
import com.bunny.engine.lontar.sabr.SabrPlayback
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
    private val watchReporter: GuestWatchReporter,
) {

    private var userAgent: String = DEFAULT_USER_AGENT

    private val _player: ExoPlayer by lazy { buildPlayer() }
    val player: ExoPlayer get() = _player

    /**
     * True setelah pemutar (yang dibuat malas) benar-benar ada, supaya
     * mode latar tidak memaksa pemutar dibuat saat aplikasi baru dibuka.
     */
    @Volatile
    private var playerBuilt = false

    /** Aplikasi sedang di latar: jalur video dimatikan, suara tetap jalan. */
    @Volatile
    private var backgroundMode = false

    private var _session: MediaSession? = null

    /**
     * Pindah video dari kontrol mana pun (notifikasi, layar kunci,
     * headset, kontrol pemutar). Diisi PlayerViewModel selama hidup;
     * null berarti tidak ada yang bisa memuat video berikutnya.
     */
    interface QueueHandler {
        fun onSkipNext()
        fun onSkipPrevious()
    }

    @Volatile
    var queueHandler: QueueHandler? = null

    /** The session for [PlaybackService]; built once, with the player. */
    fun session(): MediaSession {
        return _session ?: MediaSession.Builder(context, QueueSkipPlayer(player, this))
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
        closeSabr()
        currentPersonaName = streams.personaName
        currentSourceIsLocal = false
        clearQualityOverride()
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
        // Selera Tamu: report this view to the addresses the
        // player response itself supplied, under the session's
        // persistent guest identity.
        val tracking = streams.tracking
        if (tracking != null) {
            watchReporter.start(
                tracking = tracking,
                userAgent = streams.playbackUserAgent,
                positionMs = { player.currentPosition },
                isPlaying = { player.isPlaying },
            )
        } else {
            watchReporter.stop()
        }
    }

    fun stop() {
        closeSabr()
        watchReporter.stop()
        player.stop()
        player.clearMediaItems()
        PlaybackDiagnostics.log("Pemutaran dihentikan")
    }

    /** The SABR playback currently feeding the player, if any. */
    private var currentSabr: SabrPlayback? = null

    private fun closeSabr() {
        cancelSabrPositionReporter()
        currentSabr?.close()
        currentSabr = null
    }

    // ------------------------------------------------------------ pengawas

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sabrWatchdog: Runnable? = null

    private fun cancelSabrWatchdog() {
        sabrWatchdog?.let { mainHandler.removeCallbacks(it) }
        sabrWatchdog = null
    }

    private var sabrPositionReporter: Runnable? = null

    private fun cancelSabrPositionReporter() {
        sabrPositionReporter?.let { mainHandler.removeCallbacks(it) }
        sabrPositionReporter = null
    }

    /**
     * Feeds the player's position to the live SABR handle every two
     * seconds: the pump keeps its request cadence hungry while the
     * spool lead over the playhead is thin, and relaxes only when
     * the lead is fat (device verdict 2026-11-12: polite 20-second
     * sleeps after the cold window starved a playing video).
     */
    private fun armSabrPositionReporter(sabr: SabrPlayback) {
        cancelSabrPositionReporter()
        val reporter = object : Runnable {
            override fun run() {
                if (currentSabr !== sabr) return
                sabr.playerPositionMs = player.currentPosition
                mainHandler.postDelayed(this, 2_000)
            }
        }
        sabrPositionReporter = reporter
        mainHandler.post(reporter)
    }

    /**
     * The watchdog (device verdict 2026-11-12), grown into the
     * hedged start of Fase 8 mechanism 4: it ticks while the SABR
     * head starts, and concedes the start to the classic road on
     * WHICHEVER limit arrives first — the 25-second deadline, or
     * the race budget: a spool already holding megabytes of media
     * whose player still cannot start is not byte-starved, so
     * waiting longer buys nothing. The decision is written into
     * the diagnostics either way; the viewer never hangs on a
     * silent stall. A head that leaves BUFFERING disarms it.
     */
    private fun armSabrWatchdog(sabr: SabrPlayback) {
        cancelSabrWatchdog()
        val startedAt = SystemClock.uptimeMillis()
        val watchdog = object : Runnable {
            override fun run() {
                if (currentSabr !== sabr) return
                if (player.playbackState != Player.STATE_BUFFERING) return
                val elapsedMs = SystemClock.uptimeMillis() - startedAt
                val spooled = sabr.spooledBytes()
                val budgetHit = spooled >= sabr.raceBudgetBytes
                if (elapsedMs < SABR_READY_WATCHDOG_MS && !budgetHit) {
                    mainHandler.postDelayed(this, SABR_WATCHDOG_TICK_MS)
                    return
                }
                val trigger = if (budgetHit) {
                    "anggaran balapan ${sabr.raceBudgetBytes / 1024} KB " +
                        "tercapai (${spooled / 1024} KB tergulung)"
                } else {
                    "batas waktu 25 detik"
                }
                PlaybackDiagnostics.log(
                    "SABR tidak kunjung siap — $trigger; " +
                        "beralih ke jalan klasik (${sabr.statusLine()})",
                )
                closeSabr()
                fallback?.invoke()
            }
        }
        sabrWatchdog = watchdog
        mainHandler.postDelayed(watchdog, SABR_WATCHDOG_TICK_MS)
    }

    /**
     * Fase 8 (Anyam), Langkah 3: plays [sabr] FIRST, with the whole
     * classic chain behind it as the fallthrough. The spool road and
     * the classic road are two ways into the same video: if SABR
     * dies before or during playback, the chain advances to the
     * classic sources and the position is carried over — the spool
     * keeps every byte it already holds for the next open. Stats
     * reporting rides the addresses the SABR response itself handed
     * out, exactly as on the classic road.
     */
    fun playWithSabr(
        sabr: SabrPlayback,
        streams: PlaybackStreams,
        title: String,
        channelName: String = "",
        artworkUrl: String? = null,
    ) {
        closeSabr()
        currentSabr = sabr
        currentPersonaName = streams.personaName
        currentSourceIsLocal = false
        clearQualityOverride()
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(channelName.takeIf { it.isNotEmpty() })
            .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
            .build()
        val chain = listOf("SABR (Anyam)" to buildSabrSource(sabr, metadata)) +
            buildSourceChain(streams, metadata)
        PlaybackDiagnostics.log(
            "Jalan untuk \"$title\": SABR (Anyam) lebih dulu, " +
                "klasik di belakangnya",
        )
        playChain(chain, title, resumeOnFirstFallthrough = true)
        armSabrWatchdog(sabr)
        armSabrPositionReporter(sabr)
        val tracking = sabr.openData.tracking ?: streams.tracking
        if (tracking != null) {
            watchReporter.start(
                tracking = tracking,
                userAgent = sabr.openData.userAgent,
                positionMs = { player.currentPosition },
                isPlaying = { player.isPlaying },
            )
        } else {
            watchReporter.stop()
        }
    }

    /** The player's view of the spool: two progressive fMP4 streams
     * (video + audio tracks) merged, served by [SabrDataSource]. */
    private fun buildSabrSource(
        sabr: SabrPlayback,
        metadata: MediaMetadata,
    ): MediaSource {
        fun source(itag: Int): MediaSource {
            val item = MediaItem.Builder()
                .setUri(Uri.parse("bunny-sabr://${sabr.videoId}/$itag"))
                .setMediaMetadata(metadata)
                .build()
            return ProgressiveMediaSource.Factory(sabr.dataSourceFactory)
                .createMediaSource(item)
        }
        return MergingMediaSource(
            true,
            source(sabr.servedVideoItag),
            source(sabr.servedAudioItag),
        )
    }

    /**
     * Offline playback of a Panen Bertingkat download: local files
     * only, no network. A video file without its own audio is merged
     * with the separate audio file (the adaptive pair the download
     * stages fetched); a progressive video file plays alone.
     */
    fun playLocal(
        videoFile: File?,
        audioFile: File?,
        title: String,
        channelName: String = "",
    ) {
        // Local files earn no reports — the addresses belong to a
        // streamed view, and this is not one.
        closeSabr()
        watchReporter.stop()
        currentPersonaName = null
        currentSourceIsLocal = true
        clearQualityOverride()
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(channelName.takeIf { it.isNotEmpty() })
            .build()
        val factory = DefaultDataSource.Factory(context)
        fun source(file: File): MediaSource {
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(metadata)
                .build()
            return ProgressiveMediaSource.Factory(factory)
                .createMediaSource(item)
        }
        val merged = when {
            videoFile != null && audioFile != null ->
                MergingMediaSource(true, source(videoFile), source(audioFile))
            videoFile != null -> source(videoFile)
            audioFile != null -> source(audioFile)
            else -> {
                PlaybackDiagnostics.log("Tidak ada berkas unduhan untuk \"$title\"")
                return
            }
        }
        playChain(listOf("unduhan lokal" to merged), title)
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

    /**
     * Dipanggil MainActivity saat aplikasi masuk/keluar latar (layar mati
     * juga dihitung). Di latar, jalur video dimatikan: tanpa layar yang
     * menampilkannya, video tetap diunduh dan didekode kalau dibiarkan, dan
     * itu membuang data (Jatah) dan baterai. Suara tidak terpengaruh.
     *
     * Pilihan kualitas yang dipilih pengguna tidak hilang: matinya jalur
     * video tidak menyentuh override kualitas.
     *
     * Efek hemat data nyata hanya pada sumber DASH/HLS yang memisahkan audio
     * dan video (sumber utama Bunny). File progresif menyatukan keduanya
     * sehingga tetap terunduh utuh.
     */
    fun setBackgroundMode(inBackground: Boolean) {
        if (backgroundMode == inBackground) return
        backgroundMode = inBackground
        if (!playerBuilt) return
        applyVideoTrackState(player)
        PlaybackDiagnostics.log(
            if (inBackground) "Mode latar: jalur video dimatikan" else "Mode latar selesai: jalur video dinyalakan",
        )
    }

    private fun applyVideoTrackState(exo: ExoPlayer) {
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, backgroundMode)
            .build()
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
            // Lompat 10 detik ke dua arah, seperti YouTube (bawaan Media3
            // 5 dan 15). Dipakai tombol mundur/maju di halaman pemutar.
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            // Fokus audio: berhenti/menurunkan suara saat telepon masuk atau
            // aplikasi media lain mulai, dan melanjutkan dengan benar.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Headset/Bluetooth dicabut: berhenti, jangan keluar lewat speaker.
            .setHandleAudioBecomingNoisy(true)
            // Layar mati: CPU dan Wi-Fi tetap bangun selama memutar, supaya
            // suara tidak putus-putus. Butuh izin WAKE_LOCK (lihat manifest).
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
                            // Pemutus arus Lontar Hidup (Fase 6): an HTTP
                            // 403 from the media host AFTER real playback
                            // (past the cold-start allowance) is the GVS
                            // gate's signature. The ViewModel's recovery
                            // takes it from here — demote the persona,
                            // re-resolve, continue from this position.
                            val persona = currentPersonaName
                            if (persona != null &&
                                !currentSourceIsLocal &&
                                httpStatusOf(error) == 403 &&
                                exo.currentPosition > GVS_POSITION_THRESHOLD_MS
                            ) {
                                PlaybackDiagnostics.log(
                                    "Dugaan gerbang GVS: HTTP 403 pada posisi " +
                                        "${exo.currentPosition}ms (persona $persona)",
                                )
                                onSuspectedGvs?.invoke(persona)
                            }
                        }
                    }
                })
                playerBuilt = true
                if (backgroundMode) applyVideoTrackState(exo)
            }
    }

    private var fallback: (() -> Unit)? = null
    private var chainGeneration = 0
    private var currentTitle: String = ""

    // ----------------------------------------------------- lontar hidup

    /** Persona that issued the current network streams (Radar evidence). */
    @Volatile
    private var currentPersonaName: String? = null

    /** True while the current source is a local download (never GVS). */
    @Volatile
    private var currentSourceIsLocal: Boolean = false

    /**
     * Circuit breaker callback, owned by PlayerViewModel while a video
     * is open: a mid-stream HTTP 403 past the cold-start allowance,
     * attributed to the persona that issued the streams.
     */
    @Volatile
    var onSuspectedGvs: ((personaName: String) -> Unit)? = null

    /** The HTTP status inside a playback error's cause chain, if any. */
    private fun httpStatusOf(error: Throwable?): Int? {
        var e = error
        while (e != null) {
            if (e is androidx.media3.datasource.HttpDataSource
                    .InvalidResponseCodeException
            ) {
                return e.responseCode
            }
            e = e.cause
        }
        return null
    }

    private fun playChain(
        sources: List<Pair<String, MediaSource>>,
        title: String,
        resumeOnFirstFallthrough: Boolean = false,
    ) {
        cancelSabrWatchdog()
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
                if (generation == chainGeneration) {
                    // Anyam (Fase 8): when the head source was the SABR
                    // road, the classic road continues from the same
                    // position — the viewer keeps their place, the
                    // spool keeps its bytes.
                    val resumeAt = if (resumeOnFirstFallthrough) {
                        player.currentPosition
                    } else {
                        0L
                    }
                    playChain(rest, title)
                    if (resumeAt > 0) player.seekTo(resumeAt)
                }
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

        /** How long a SABR head may stay buffering before the
         * watchdog hands the video to the classic road. */
        private const val SABR_READY_WATCHDOG_MS = 25_000L

        /** How often the hedged-start watchdog re-reads the spool. */
        private const val SABR_WATCHDOG_TICK_MS = 2_000L

        /**
         * Past this position, an HTTP 403 is no longer a cold-start
         * refusal — real playback happened, so the GVS gate is the
         * working explanation (Lontar Hidup §8; allowance ≈ 60 s).
         */
        private const val GVS_POSITION_THRESHOLD_MS = 45_000L
    }
}

/** Lewat batas ini, ⏮ mengulang video dari awal (seperti Spotify). */
private const val RESTART_THRESHOLD_MS = 3_000L

/**
 * Pemutar yang dilihat MediaSession (notifikasi, layar kunci, headset,
 * kontrol pemutar). Bunny memutar satu video per waktu, jadi ExoPlayer
 * menganggap "Berikutnya" tidak ada dan tombol ⏭ hilang. Di sini
 * Sebelumnya/Berikutnya dinyatakan tersedia selama ada video dimuat dan
 * ada yang bisa melayaninya, lalu diarahkan ke antrean di PlayerViewModel,
 * bukan ke daftar putar ExoPlayer.
 */
@UnstableApi
private class QueueSkipPlayer(
    player: Player,
    private val manager: PlaybackManager,
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (manager.queueHandler == null || mediaItemCount == 0) return base
        return base.buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        availableCommands.contains(command)

    override fun seekToNext() {
        manager.queueHandler?.onSkipNext()
    }

    override fun seekToNextMediaItem() {
        manager.queueHandler?.onSkipNext()
    }

    override fun seekToPrevious() = skipPrevious()

    override fun seekToPreviousMediaItem() = skipPrevious()

    /** Lewat 3 detik: ulang dari awal; kalau tidak: video sebelumnya. */
    private fun skipPrevious() {
        val handler = manager.queueHandler
        if (handler == null || currentPosition > RESTART_THRESHOLD_MS) {
            seekTo(0L)
        } else {
            handler.onSkipPrevious()
        }
    }
}
