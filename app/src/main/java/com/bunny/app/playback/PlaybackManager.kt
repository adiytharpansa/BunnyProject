package com.bunny.app.playback

import android.app.PendingIntent
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.File
import androidx.media3.session.MediaSession
import com.bunny.app.MainActivity
import com.bunny.app.bug.BugPlayerListener
import com.bunny.core.model.PlaybackStreams
import com.bunny.engine.lontar.LontarEngine
import com.bunny.engine.lontar.sabr.ClassicSpoolRecorder
import com.bunny.engine.lontar.sabr.SabrPlayback
import com.bunny.engine.lontar.sabr.TeeDataSource
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
 * DefaultMediaNotificationProvider — the arrangement whose absence crashed CLEAR.
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

    /**
     * Klien HTTP khusus pemutaran: HTTP/2 dan penggunaan ulang koneksi
     * (penting untuk segmen DASH yang kecil-kecil), dengan batas waktu
     * eksplisit. Pengalihan https -> http tidak diikuti.
     */
    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }

    private val _player: ExoPlayer by lazy { buildPlayer() }
    val player: ExoPlayer get() = _player

    /**
     * True setelah pemutar (yang dibuat malas) benar-benar ada, supaya
     * mode latar tidak memaksa pemutar dibuat saat aplikasi baru dibuka.
     */
    @Volatile
    private var playerBuilt = false

    /** Activity berhenti (latar / layar mati): jalur video dimatikan, suara tetap jalan. */
    @Volatile
    private var activityInBackground = false

    private val videoTrackDisabled: Boolean
        get() = activityInBackground

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
    private var _queueHandler: QueueHandler? = null

    var queueHandler: QueueHandler?
        get() = _queueHandler
        set(value) {
            _queueHandler = value
            // Session membaca perintah yang tersedia saat pemutar
            // dipasang; pasang ulang pembungkus agar tombol
            // next/previous ikut berubah.
            mainHandler.post { _session?.setPlayer(QueueSkipPlayer(player, this)) }
        }

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
        }
    }

    /**
     * Loads the best available source for [streams] and starts playing.
     * Title, channel and artwork ride on every item as session metadata:
     * the media notification and lock-screen controls read them from
     * there — the same information the official app's playback
     * notification shows (REA dissection 2026-11-10).
     */
    /** @return false bila [streams] tidak punya sumber yang bisa diputar. */
    fun play(
        streams: PlaybackStreams,
        title: String,
        channelName: String = "",
        artworkUrl: String? = null,
        classicRecorder: ClassicSpoolRecorder? = null,
    ): Boolean {
        closeSabr()
        currentClassicRecorder = classicRecorder
        currentPersonaName = streams.personaName
        currentSourceIsLocal = false
        clearQualityOverride()
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(channelName.takeIf { it.isNotEmpty() })
            .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
            .build()
        val sources = buildSourceChain(streams, metadata, classicRecorder)
        if (sources.isEmpty()) {
            return false
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
        return true
    }

    fun stop() {
        // Batalkan retry/fallback tertunda supaya video yang sudah
        // ditutup tidak menyala lagi.
        chainGeneration++
        fallback = null
        closeSabr()
        releaseTransitionHold()
        watchReporter.stop()
        player.stop()
        player.clearMediaItems()
    }

    /**
     * Menahan CPU selama peralihan video (autoplay / ⏭ saat layar mati).
     * Dilepas begitu pemutaran dimulai, atau otomatis lewat batas waktu.
     */
    private val transitionWakeLock: PowerManager.WakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bunny:pindah-video")
            .apply { setReferenceCounted(false) }
    }

    fun holdForTransition() {
        try {
            transitionWakeLock.acquire(TRANSITION_WAKELOCK_MS)
        } catch (e: SecurityException) {
        }
    }

    private fun releaseTransitionHold() {
        if (transitionWakeLock.isHeld) transitionWakeLock.release()
    }

    /** The SABR playback currently feeding the player, if any. */
    private var currentSabr: SabrPlayback? = null

    /**
     * Fase 8 mechanism 14: the classic playback's spool recorder,
     * when the classic road is filling the spool as it plays. Its
     * lifetime is the classic playback's: it closes wherever the
     * SABR handle closes (see [closeSabr]) — a recorder closed
     * late would be harmless anyway, since its sink only fires
     * while its tee is being read, and the tee dies with the
     * source it wraps.
     */
    private var currentClassicRecorder: ClassicSpoolRecorder? = null

    private fun closeClassicRecorder() {
        currentClassicRecorder?.close()
        currentClassicRecorder = null
    }

    // ------------------------------------------------- anti-buffering M2

    /** True once the current SABR session has reached READY. */
    private var sabrHasBeenReady = false

    /** A SABR buffering episode is in progress (M15 catch-up gate). */
    private var sabrWasBuffering = false

    // ------------------------------------------------ anti-buffering M15

    /** Catch-up speed (1,05x) is currently engaged. */
    private var catchUpActive = false

    /** A SABR buffering episode ended since the last reset. */
    private var catchUpEpisodeSeen = false

    /** M15: every new road starts at honest speed, flags cleared. */
    private fun resetCatchUp() {
        if (catchUpActive && playerBuilt) player.setPlaybackSpeed(1.0f)
        catchUpActive = false
        catchUpEpisodeSeen = false
    }

    /** True while the player's current item is the SABR source. */
    private fun playerOnSabrSource(): Boolean =
        player.currentMediaItem?.localConfiguration?.uri?.scheme == "bunny-sabr"

    private fun closeSabr() {
        cancelSabrPositionReporter()
        cancelSabrStallRescue()
        resetCatchUp()
        currentSabr?.close()
        currentSabr = null
        // M14: the classic recorder shares the SABR handle's
        // closing points — every road change closes both.
        closeClassicRecorder()
    }

    // ------------------------------------------------------------ pengawas

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sabrWatchdog: Runnable? = null

    private fun cancelSabrWatchdog() {
        sabrWatchdog?.let { mainHandler.removeCallbacks(it) }
        sabrWatchdog = null
        // The chain just moved (playChain calls this): the mid-play
        // rescue belongs to the road the chain is leaving.
        cancelSabrStallRescue()
    }

    private var sabrStallRescue: Runnable? = null

    /**
     * The M12 rescue's buffering clock: when the current
     * continuous BUFFERING stretch began (uptime), -1 while not
     * buffering. A field rather than a local of the rescue
     * runnable because a SEEK must be able to restart it
     * (Langkah 6, mechanism 23 — see the position-discontinuity
     * hook in the player listener): buffering measured across a
     * seek would charge the road for the viewer's own travel
     * time, and a seek past the frontier earns the pump a fresh
     * threshold before the rescue may judge it.
     */
    private var rescueBufferingSinceMs: Long = -1

    private fun cancelSabrStallRescue() {
        sabrStallRescue?.let { mainHandler.removeCallbacks(it) }
        sabrStallRescue = null
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
                // M15's check rides this tick: main thread, every
                // two seconds, only while a SABR handle is current.
                updateCatchUpSpeed()
                mainHandler.postDelayed(this, 2_000)
            }
        }
        sabrPositionReporter = reporter
        mainHandler.post(reporter)
    }

    /**
     * Anti-buffering M15 — catch-up recovery: after a SABR
     * buffering episode has ENDED (M2's flag), play at 1,05x
     * until the player's own buffer lead is healthy again
     * ([CATCH_UP_TARGET_MS]), then return to honest 1,0x. A speed
     * the user chose is never overridden: engagement requires the
     * current speed to be exactly 1,0x, and the restore only
     * touches a speed that is still our 1,05x.
     */
    private fun updateCatchUpSpeed() {
        if (!playerOnSabrSource()) return
        val leadMs = player.bufferedPosition - player.currentPosition
        if (catchUpActive) {
            if (leadMs >= CATCH_UP_TARGET_MS) {
                if (player.playbackParameters.speed == CATCH_UP_SPEED) {
                    player.setPlaybackSpeed(1.0f)
                }
                catchUpActive = false
                catchUpEpisodeSeen = false
            }
        } else if (catchUpEpisodeSeen &&
            player.playbackParameters.speed == 1.0f &&
            leadMs < CATCH_UP_TARGET_MS
        ) {
            player.setPlaybackSpeed(CATCH_UP_SPEED)
            catchUpActive = true
        }
    }

    /**
     * Anti-buffering M12 — the mid-play guarantee. The start
     * watchdog owns the road only until first READY; this one
     * owns it afterwards: a CONTINUOUS buffering stretch past
     * [SABR_STALL_RESCUE_MS] on a handle that has already played
     * hands the video to the classic road at the same position,
     * through the chain's own fallthrough — the viewer never
     * stares at the spinner longer than the threshold, whatever
     * the cause. Fires at most once per handle: firing closes
     * the handle, and a closed handle fails every guard here.
     */
    private fun armSabrStallRescue(sabr: SabrPlayback) {
        cancelSabrStallRescue()
        rescueBufferingSinceMs = -1
        var hasBeenReady = false
        val rescue = object : Runnable {
            override fun run() {
                if (currentSabr !== sabr) return
                // The chain already fell through (a source error
                // beat us to it): this road is no longer ours. A
                // missing item is only the prepare window — keep
                // watching; a foreign item ends the watch.
                val itemScheme = player.currentMediaItem
                    ?.localConfiguration?.uri?.scheme
                if (itemScheme != null && itemScheme != "bunny-sabr") return
                when (player.playbackState) {
                    Player.STATE_READY -> {
                        hasBeenReady = true
                        rescueBufferingSinceMs = -1
                    }
                    Player.STATE_BUFFERING -> {
                        if (hasBeenReady && player.playWhenReady) {
                            val now = SystemClock.uptimeMillis()
                            if (rescueBufferingSinceMs < 0) {
                                rescueBufferingSinceMs = now
                            }
                            if (now - rescueBufferingSinceMs >= SABR_STALL_RESCUE_MS) {
                                closeSabr()
                                fallback?.invoke()
                                return
                            }
                        }
                    }
                    else -> rescueBufferingSinceMs = -1
                }
                mainHandler.postDelayed(this, SABR_WATCHDOG_TICK_MS)
            }
        }
        sabrStallRescue = rescue
        mainHandler.postDelayed(rescue, SABR_WATCHDOG_TICK_MS)
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
        sabrHasBeenReady = false
        sabrWasBuffering = false
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
        playChain(chain, title)
        // Mechanism 24 (SABR Mandiri L3): when the engine proves a
        // pin switch on the wire, it calls the handle back — the
        // player is re-pointed at the new itag carrying the running
        // position. The generation stamp is this chain's: any later
        // chain (a fallthrough, a new video, a stop) makes a late
        // callback a no-op.
        val sabrGeneration = chainGeneration
        sabr.onServingSwitch = { _, _ ->
            mainHandler.post {
                applySabrServingSwitch(sabr, sabrGeneration)
            }
        }
        armSabrWatchdog(sabr)
        armSabrPositionReporter(sabr)
        armSabrStallRescue(sabr)
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

    /**
     * Mechanism 24 (SABR Mandiri L3): the player side of a proven
     * pin switch. Runs on the main thread. Both guards must hold —
     * the handle is still this playback's, and the source chain
     * has not advanced since the SABR source was set — otherwise
     * the callback is stale and does nothing. The re-point itself
     * reuses the chain's own position-carrying shape
     * (setMediaSource with the running position, then prepare;
     * playWhenReady is deliberately untouched, so a paused video
     * stays paused). A failure here is not a new failure mode:
     * the old source's video track stops growing, the stall
     * rescue and the classic chain behind it take over with the
     * position carried, exactly as designed.
     */
    private fun applySabrServingSwitch(sabr: SabrPlayback, generation: Int) {
        if (currentSabr !== sabr || chainGeneration != generation) return
        val metadata = player.currentMediaItem?.mediaMetadata ?: return
        try {
            player.setMediaSource(
                buildSabrSource(sabr, metadata),
                player.currentPosition,
            )
            player.prepare()
        } catch (e: Exception) {
            // Left to the stall rescue / classic chain by design.
        }
    }

    /** The player's view of the spool: two progressive fMP4 streams
     * (video + audio tracks) merged, served by [SabrDataSource]. */
    private fun buildSabrSource(
        sabr: SabrPlayback,
        metadata: MediaMetadata,
    ): MediaSource {
        // Anti-buffering M4/M5: on a GROWING spool, an extractor
        // that hunts tail metadata parks a loader thread — and
        // with it the whole merged player (readiness is the
        // minimum of the two tracks). WebM's Cues sit near
        // end-of-file in YouTube's files; the fMP4 sidx/mfra
        // mergers read the same neighbourhood. The SABR source
        // therefore disables the Matroska cues seek outright and
        // pins the fragmented-MP4 flags to none: the spool is
        // read strictly front-to-back, which is all playback does.
        val extractorsFactory = DefaultExtractorsFactory()
            .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            .setFragmentedMp4ExtractorFlags(0)
        fun source(itag: Int): MediaSource {
            val item = MediaItem.Builder()
                .setUri(Uri.parse("bunny-sabr://${sabr.videoId}/$itag"))
                .setMediaMetadata(metadata)
                .build()
            return ProgressiveMediaSource.Factory(
                sabr.dataSourceFactory,
                extractorsFactory,
            ).createMediaSource(item)
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
    }

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
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
     * menampilkannya, video tetap didekode kalau dibiarkan, dan itu membuang
     * baterai. Suara tidak terpengaruh. Pilihan kualitas pengguna tidak hilang.
     *
     * Penghematan data bergantung pada sumbernya: DASH/HLS dan file video
     * terpisah berhenti dimuat ExoPlayer saat tidak ada jalur yang aktif.
     * Pump SABR berada di :engine dan tidak diatur dari sini.
     */
    fun setBackgroundMode(inBackground: Boolean) {
        if (activityInBackground == inBackground) return
        activityInBackground = inBackground
        refreshVideoTrackState()
    }

    /**
     * Dipanggil MainActivity saat halaman tonton penuh tampil (true) atau
     * dilipat menjadi pemutar mini (false). Tanpa permukaan video, ExoPlayer
     * tetap mendekode ke permukaan pengganti.
     */
    private fun refreshVideoTrackState() {
        if (!playerBuilt) return
        applyVideoTrackState(player)
    }

    private fun applyVideoTrackState(exo: ExoPlayer) {
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoTrackDisabled)
            .build()
    }

    // -------------------------------------------------------------- internals

    private fun buildPlayer(): ExoPlayer {
        // Anti-buffering M6/M7/M16: on both roads this player is a
        // file-like consumer (the SABR road literally reads a
        // growing local spool), so TIME — not bytes — is the
        // binding limit (M6), and the durations follow the SABR
        // cadence instead of the inherited ExoPlayer 2 shape
        // (15/40 s, 2,5/5 s, 24 MB — under Media3's own 144 MB
        // target the byte cap bound first at HD bitrates and the
        // extractor could never hold what the spool had rolled).
        // Batas byte mengikuti kelas memori perangkat (seperempatnya, 24-64 MB),
        // bukan angka tetap 128 MB. Catatan: dengan prioritas-waktu aktif, batas
        // byte baru mengikat setelah penyangga minimum terpenuhi, karena itu
        // durasi minimum juga diturunkan.
        val memoryClassMb =
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.memoryClass ?: 128
        val targetBufferBytes = (memoryClassMb / 4).coerceIn(24, 64) * 1024 * 1024
        // RAM kecil: penyangga lebih pendek (buffer ExoPlayer hidup
        // di heap Java) dan resolusi dibatasi 1080p agar bitrate
        // tinggi tidak menggembungkan memori.
        val lowRam = memoryClassMb <= 192
        val minBufferMs = if (lowRam) 20_000 else MIN_BUFFER_MS
        val maxBufferMs = if (lowRam) 45_000 else MAX_BUFFER_MS
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs, 1_000, 5_000)
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(BACK_BUFFER_MS, true)
            .build()
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(
            context,
            androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(
                15_000, // naik kualitas hanya jika penyangga >= 15 dtk
                25_000, // turun kualitas hanya jika penyangga < 25 dtk
                25_000, // pertahankan data kualitas lama yang sudah terunduh
                0.65f,  // pakai 65% bandwidth terukur, sisanya pengaman
            ),
        )
        if (lowRam) {
            trackSelector.setParameters(
                trackSelector.buildUponParameters().setMaxVideoSize(1920, 1080).build(),
            )
        }
        return ExoPlayer.Builder(context)
            // Jika decoder utama gagal init (VP9/AV1 hardware), coba
            // decoder lain.
            .setRenderersFactory(
                androidx.media3.exoplayer.DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true),
            )
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
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
                exo.addListener(BugPlayerListener("player"))
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val name = when (state) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "STATE_$state"
                        }
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        // Langkah 6, mechanism 23 — panic-free
                        // seek: a seek on the SABR road is a named
                        // event, and it restarts every buffering
                        // clock that could otherwise convict the
                        // road for the viewer's travel time: the
                        // M12 rescue measures its stretch from the
                        // seek, and an episode already open in the
                        // AnalyticsListener's accounting restarts
                        // (with its dropped-frame baseline) so the
                        // post-seek wait is judged on its own.
                        if (reason != Player.DISCONTINUITY_REASON_SEEK ||
                            currentSabr == null
                        ) {
                            return
                        }
                        rescueBufferingSinceMs = -1
                        // Mechanism 23, full form: hand the
                        // target to the road itself. If the spool
                        // does not cover it, the drive jumps — a
                        // fresh session seeded at the target —
                        // instead of rolling sequentially from
                        // zero and racing the rescue clock.
                        currentSabr?.onSeekObserved(newPosition.positionMs)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            networkRetries = 0
                            releaseTransitionHold()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // Live HLS yang tertinggal dari jendela
                        // siaran: lompat ke posisi live, bukan error.
                        if (error.errorCode ==
                            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
                        ) {
                            exo.seekToDefaultPosition()
                            exo.prepare()
                            return
                        }
                        if (isTransientNetworkError(error) &&
                            !currentSourceIsLocal &&
                            !playerOnSabrSource() &&
                            networkRetries < MAX_NETWORK_RETRIES
                        ) {
                            networkRetries++
                            val gen = chainGeneration
                            mainHandler.postDelayed({
                                if (gen == chainGeneration) exo.prepare()
                            }, 1_000L * networkRetries)
                            return
                        }
                        val step = fallback
                        if (step != null) {
                            step.invoke()
                        } else {
                            // Terminal: the chain has no next source, so
                            // this failure is the one the user experiences.
                            // Persist it as on-device telemetry (Fase 4).
                            // Pemutus arus Lontar Hidup (Fase 6): an HTTP
                            // 403 from the media host AFTER real playback
                            // (past the cold-start allowance) is the GVS
                            // gate's signature. The ViewModel's recovery
                            // takes it from here — demote the persona,
                            // re-resolve, continue from this position.
                            val persona = currentPersonaName
                            if (persona != null &&
                                persona != LontarEngine.FLOOR_PERSONA_NAME &&
                                !currentSourceIsLocal &&
                                httpStatusOf(error) == 403 &&
                                exo.currentPosition > GVS_POSITION_THRESHOLD_MS
                            ) {
                                onSuspectedGvs?.invoke(persona)
                            } else if (!currentSourceIsLocal) {
                                // Lantai Penyelamat: a terminal error
                                // that is not the GVS signature means
                                // the classic chain is exhausted — the
                                // ViewModel's floor attempt takes it
                                // from here. The floor's own persona is
                                // excluded from the GVS branch above:
                                // a floor failure is final, never a
                                // recovery loop.
                                onTerminalFailure?.invoke(exo.currentPosition)
                            }
                        }
                    }
                })
                // Anti-buffering M2: the player's own evidence —
                // which decoder really runs (a name settles the
                // hardware/software question the web sources argue
                // about), how many frames it drops, and how the
                // SABR road's buffering episodes count and last.
                // Without these, "buffering" stays a feeling; with
                // them it is a measurement.
                exo.addAnalyticsListener(object : AnalyticsListener {


override fun onPlaybackStateChanged(
                        eventTime: AnalyticsListener.EventTime,
                        state: Int,
                    ) {
                        if (currentSabr == null) return
                        when (state) {
                            Player.STATE_READY -> {
                                sabrHasBeenReady = true
                                if (sabrWasBuffering) {
                                    sabrWasBuffering = false
                                    // M15: an episode ENDED — the
                                    // catch-up speed may engage.
                                    catchUpEpisodeSeen = true
                                }
                            }
                            Player.STATE_BUFFERING -> {
                                if (sabrHasBeenReady) sabrWasBuffering = true
                            }
                        }
                    }
                })
                playerBuilt = true
                if (videoTrackDisabled) applyVideoTrackState(exo)
            }
    }

    private var fallback: (() -> Unit)? = null
    private var chainGeneration = 0

    /** Percobaan ulang berturut-turut untuk error jaringan sesaat. */
    private var networkRetries = 0

    private fun isTransientNetworkError(e: PlaybackException): Boolean =
        when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
            // 429 dan 5xx = cuaca server, bukan jawaban. 403 sengaja
            // TIDAK masuk: itu tanda gerbang GVS yang ditangani
            // jalur pemulihan.
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                httpStatusOf(e).let { it == 429 || (it != null && it in 500..599) }
            else -> false
        }
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

    /**
     * Lantai Penyelamat callback, owned by PlayerViewModel while a
     * video is open: the source chain ended in a terminal error that
     * is NOT the GVS signature above (that one belongs to the
     * recovery path) and not a local file — every classic road is
     * exhausted, so the floor gets its one attempt, continuing from
     * the position carried here.
     */
    @Volatile
    var onTerminalFailure: ((positionMs: Long) -> Unit)? = null

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
        startPositionMs: Long = 0L,
    ) {
        cancelSabrWatchdog()
        networkRetries = 0
        currentTitle = title
        val (label, source) = sources.first()
        val rest = sources.drop(1)
        val generation = ++chainGeneration
        fallback = if (rest.isEmpty()) {
            null
        } else {
            {
                // Kejadian error terlambat dari sumber sebelumnya tidak boleh
                // memajukan rantai untuk kedua kalinya.
                if (generation == chainGeneration) {
                    // Posisi selalu dibawa, bukan hanya pada fallthrough
                    // pertama dari SABR: DASH -> HLS -> progresif pun
                    // melanjutkan dari tempat penonton berada.
                    val resumeAt = player.currentPosition
                    // Jalur error pemutar sebelumnya tidak menutup SABR, jadi
                    // pump tetap mengunduh dan setiap seek di jalur klasik
                    // diteruskan ke handle yang sudah mati. closeSabr()
                    // idempoten, aman dipanggil lagi dari watchdog/rescue.
                    closeSabr()
                    playChain(rest, title, startPositionMs = resumeAt)
                }
            }
        }
        if (startPositionMs > 0) {
            player.setMediaSource(source, startPositionMs)
        } else {
            player.setMediaSource(source)
        }
        player.prepare()
        player.playWhenReady = true
    }

    private fun buildSourceChain(
        streams: PlaybackStreams,
        metadata: MediaMetadata,
        classicRecorder: ClassicSpoolRecorder? = null,
    ): List<Pair<String, MediaSource>> {
        val httpFactory = OkHttpDataSource.Factory(playbackHttpClient)
            .setUserAgent(userAgent)
        val chain = mutableListOf<Pair<String, MediaSource>>()
        val loadPolicy =
            androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(6)
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
                // Fase 8 mechanism 14: only THIS source — the
                // generated DASH, whose representations are the
                // spool's own tracks — is teed into the spool
                // recorder. The manifest bytes themselves are not
                // media and never pass the tee; HLS and progressive
                // sources are NOT wrapped (different containers —
                // their bytes are not the spool tracks' bytes).
                val network: DataSource = if (classicRecorder != null) {
                    TeeDataSource(upstream, classicRecorder.sink)
                } else {
                    upstream
                }
                object : DataSource {
                    private var active: DataSource = network

                    override fun open(dataSpec: DataSpec): Long {
                        active = if (dataSpec.uri == manifestUri) {
                            ByteArrayDataSource(manifestBytes)
                        } else {
                            network
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
                .setLoadErrorHandlingPolicy(loadPolicy)
                .createMediaSource(item(manifestUri))
            chain += "DASH (manifest buatan Lontar)" to source
        }
        streams.dashManifestUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val source = DashMediaSource.Factory(httpFactory)
                .setLoadErrorHandlingPolicy(loadPolicy)
                .createMediaSource(item(Uri.parse(url)))
            chain += "DASH" to source
        }
        streams.hlsUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val source = HlsMediaSource.Factory(httpFactory)
                .setLoadErrorHandlingPolicy(loadPolicy)
                .createMediaSource(item(Uri.parse(url)))
            chain += "HLS" to source
        }
        streams.progressiveUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val source = DefaultMediaSourceFactory(httpFactory)
                .setLoadErrorHandlingPolicy(loadPolicy)
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
         * Anti-buffering M12: a continuous mid-play BUFFERING on
         * the SABR road longer than this hands the video to the
         * classic road at the same position — the hard ceiling on
         * how long a viewer may stare at the spinner.
         */
        private const val SABR_STALL_RESCUE_MS = 12_000L

        /** Anti-buffering M15: the healthy player-buffer lead the
         *  catch-up speed plays toward, in milliseconds. */
        private const val CATCH_UP_TARGET_MS = 30_000L

        /** Anti-buffering M15: the catch-up playback speed. */
        private const val CATCH_UP_SPEED = 1.05f

        /**
         * Past this position, an HTTP 403 is no longer a cold-start
         * refusal — real playback happened, so the GVS gate is the
         * working explanation (Lontar Hidup §8; allowance ≈ 60 s).
         */
        private const val GVS_POSITION_THRESHOLD_MS = 45_000L

        /** Penyangga pemutar: cukup dalam untuk meredam macet, cukup dangkal
         *  untuk tidak mengancam heap (penyangga hidup di heap Java). */
        private const val MIN_BUFFER_MS = 40_000
        private const val MAX_BUFFER_MS = 90_000

        /** Penyangga belakang: tombol mundur 10 dtk tidak mengunduh ulang. */
        private const val BACK_BUFFER_MS = 15_000

        /** Batas waktu wake lock peralihan video. */
        private const val TRANSITION_WAKELOCK_MS = 30_000L

        /** Batas percobaan ulang error jaringan sesaat sebelum pindah sumber. */
        private const val MAX_NETWORK_RETRIES = 4
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
