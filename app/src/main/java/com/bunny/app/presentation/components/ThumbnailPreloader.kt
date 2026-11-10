package com.bunny.app.presentation.components

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import com.bunny.core.model.VideoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Ukuran tetap thumbnail: HARUS sama antara pra-muat dan tampilan kartu. */
private const val THUMB_WIDTH = 960
private const val THUMB_HEIGHT = 540

/** Jumlah thumbnail yang ditunggu sebelum daftar dimunculkan. */
private const val FIRST_SCREEN_COUNT = 8

/** Batas tunggu; jaringan lambat tidak boleh menahan daftar selamanya. */
private const val PRELOAD_TIMEOUT_MS = 2_500L

/**
 * Request thumbnail tunggal yang dipakai pra-muat DAN VideoCard.
 * Ukuran identik membuat kunci memory cache sama, jadi kartu
 * langsung menampilkan gambar yang sudah di-decode.
 */
fun thumbnailRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(THUMB_WIDTH, THUMB_HEIGHT)
        .crossfade(false)
        .build()

/**
 * Memuat thumbnail beberapa video sekaligus (paralel) dan menunggu
 * semuanya selesai atau habis waktu. Dipanggil ViewModel sebelum
 * daftar dipublikasikan, supaya gambar muncul bersamaan.
 */
@Singleton
class ThumbnailPreloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun preload(
        items: List<VideoItem>,
        count: Int = FIRST_SCREEN_COUNT,
        timeoutMs: Long = PRELOAD_TIMEOUT_MS,
    ) {
        val urls = items.asSequence()
            .mapNotNull { it.thumbnailUrl }
            .distinct()
            .take(count)
            .toList()
        if (urls.isEmpty()) return
        val loader = context.imageLoader
        // Gagal atau lambat bukan error: kartu cukup memuat sendiri nanti.
        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                urls.map { url ->
                    async { loader.execute(thumbnailRequest(context, url)) }
                }.awaitAll()
            }
        }
    }
}
