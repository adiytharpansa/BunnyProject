package com.bunny.app.miniplayer // TODO: sesuaikan package proyek Anda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** HIDDEN = tidak tampil, MINI = kotak kecil mengambang, FULL = layar pemutar penuh. */
enum class PlayerSheetState { HIDDEN, MINI, FULL }

data class MiniPlayerUiState(
    val sheetState: PlayerSheetState = PlayerSheetState.HIDDEN,
    val title: String = "",
    val thumbnailUrl: String? = null,
    val isPlaying: Boolean = false,
)

/**
 * Satu ExoPlayer untuk seluruh aplikasi (ViewModel ini di-scope ke Activity), sehingga
 * video tidak berhenti/diulang saat pindah antara layar penuh dan mini-player.
 * ExoPlayer disediakan oleh PlayerModule dan di-release otomatis di onCleared().
 *
 * `sheetState` adalah SATU-SATUNYA sumber kebenaran. Navigasi ke/dari layar "player"
 * mengikuti state ini (lihat LaunchedEffect di MainActivityExample).
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    val player: ExoPlayer,
) : ViewModel() {

    var uiState by mutableStateOf(MiniPlayerUiState())
        private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            uiState = uiState.copy(isPlaying = isPlaying)
        }
    }

    init {
        player.addListener(listener)
    }

    private fun MediaItem.key(): String =
        mediaId.takeIf { it != MediaItem.DEFAULT_MEDIA_ID }
            ?: localConfiguration?.uri?.toString().orEmpty()

    /** Buka video dari feed → tampil FULL. Judul diambil dari parameter, bukan dari metadata. */
    fun play(item: MediaItem, title: String, thumbnailUrl: String? = null) {
        val sameItem = player.currentMediaItem?.key() == item.key()
        val state = player.playbackState
        if (!sameItem || state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
            player.setMediaItem(item)
            player.prepare()
        }
        player.playWhenReady = true
        uiState = uiState.copy(
            sheetState = PlayerSheetState.FULL,
            title = title,
            thumbnailUrl = thumbnailUrl,
        )
    }

    /** Keluar dari layar pemutar (back / panah) → jadi MINI, video TETAP main. */
    fun collapseToMini() {
        if (uiState.sheetState == PlayerSheetState.FULL) {
            uiState = uiState.copy(sheetState = PlayerSheetState.MINI)
        }
    }

    /** Tap mini-player → balik ke FULL. */
    fun expandToFull() {
        if (uiState.sheetState == PlayerSheetState.MINI) {
            uiState = uiState.copy(sheetState = PlayerSheetState.FULL)
        }
    }

    /** Swipe / tombol tutup pada mini-player → hentikan video dan sembunyikan semuanya. */
    fun dismiss() {
        player.stop()
        player.clearMediaItems()
        uiState = MiniPlayerUiState() // reset penuh: HIDDEN, judul kosong, isPlaying=false
    }

    /** Dipanggil saat aplikasi ditutup/ditinggalkan: video berhenti & mini-player hilang. */
    fun onAppExit() {
        if (uiState.sheetState != PlayerSheetState.HIDDEN || player.isPlaying) dismiss()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun onCleared() {
        player.removeListener(listener)
        player.release()
        super.onCleared()
    }
}
