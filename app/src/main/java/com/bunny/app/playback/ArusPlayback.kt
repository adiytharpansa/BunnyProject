package com.bunny.app.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bunny.engine.arus.ArusClient
import com.bunny.engine.arus.ArusDataSource
import com.bunny.engine.arus.ArusFormat
import com.bunny.engine.arus.ArusSession
import com.bunny.engine.arus.ArusStreamStore
import com.bunny.engine.arus.OkHttpArusTransport
import com.bunny.engine.arus.OkHttpInitSegmentFetcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Arus Fase A2 — assembles a playable MediaSource from a SABR session:
 * one [ArusStreamStore] (one conversation) behind two progressive
 * sources — video and audio, each an ordinary fMP4 byte stream of
 * init segment + fragments, the layout Fase A0 verified with ffprobe —
 * merged into a single item for the player.
 *
 * Deliberately NOT in PlaybackManager's automatic chain yet: A2's gate
 * is a deliberate device test from the player's diagnostics, and the
 * ordering policy (Arus first? fallback only?) is Fase A3's decision,
 * made with A2's soak results in hand.
 */
@UnstableApi
object ArusPlayback {

    /**
     * Arus's own HTTP stack. SABR answers are long streams; the read
     * timeout is per POST (each returns a burst), set well above the
     * app client's so a slow burst is not mistaken for a dead server.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun newStore(
        session: ArusSession,
        video: ArusFormat,
        audio: ArusFormat,
        userAgent: String,
    ): ArusStreamStore = ArusStreamStore(
        session = session,
        video = video,
        audio = audio,
        conversation = ArusClient(OkHttpArusTransport(httpClient, userAgent)),
        initFetcher = OkHttpInitSegmentFetcher(httpClient, userAgent),
    )

    fun createMediaSource(
        store: ArusStreamStore,
        video: ArusFormat,
        audio: ArusFormat,
        metadata: MediaMetadata,
    ): MediaSource {
        fun progressive(format: ArusFormat, tag: String): MediaSource {
            val item = MediaItem.Builder()
                .setUri(Uri.parse("arus://$tag"))
                .setMediaMetadata(metadata)
                .build()
            return ProgressiveMediaSource.Factory(ArusDataSource.Factory(store, format))
                .createMediaSource(item)
        }
        return MergingMediaSource(
            progressive(video, "video"),
            progressive(audio, "audio"),
        )
    }

    /**
     * A2's pinned single quality: the tallest video at or under the
     * Timbangan default (480p); the smallest above it when nothing
     * fits under. Audio prefers itag 140 (AAC — every Android decoder
     * speaks it), else the first audio format offered.
     */
    fun pickFormats(session: ArusSession): Pair<ArusFormat, ArusFormat>? {
        val videos = session.videoFormats()
        val audios = session.audioFormats()
        if (videos.isEmpty() || audios.isEmpty()) return null
        val video = videos.filter { it.height in 1..480 }.maxByOrNull { it.height }
            ?: videos.minByOrNull { it.height }
            ?: return null
        val audio = audios.firstOrNull { it.itag == 140 } ?: audios.first()
        return video to audio
    }
}
