package com.bunny.app.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

/**
 * Pusat pengaturan gerak: satu tempat durasi, kurva, dan spring supaya
 * seluruh aplikasi terasa seragam. Bunny tidak memakai NavHost (layar
 * dipilih oleh keadaan di MainActivity), jadi transisinya berbentuk
 * ContentTransform atas kunci layar string.
 *
 * Aturan: JANGAN menulis angka durasi / kurva langsung di layar.
 * Pakai token di sini.
 */
object AppMotion {
    // ---- Durasi (ms) ----
    const val Fast = 90          // fade keluar, umpan balik cepat
    const val Medium = 220       // bilah, chip, pergantian kecil
    const val Slow = 300         // geser antar halaman
    const val FadeIn = 210       // fade masuk (menunggu fade keluar selesai)
    const val FadeInDelay = Fast
    const val PlayerOpen = 240   // halaman tonton naik (dipakai PlayerScreen)
    const val PlayerClose = 200  // halaman tonton turun (dipakai PlayerScreen)
    const val Tint = 200         // warna ikon bar bawah

    // ---- Kurva ----
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // ---- Spring (untuk sentuhan: terasa hidup, bukan kaku) ----
    /** Tekan kartu/baris: mengecil cepat tanpa memantul. */
    val PressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Pil tab terpilih: sedikit melenting lalu tenang. */
    val PillSpring = spring<Float>(
        dampingRatio = 0.75f,
        stiffness = Spring.StiffnessMediumLow,
    )

    // ---- Fade through: pindah antar tab utama ----
    private val fadeThroughEnter: EnterTransition =
        fadeIn(tween(FadeIn, delayMillis = FadeInDelay, easing = LinearOutSlowInEasing)) +
            scaleIn(
                tween(FadeIn, delayMillis = FadeInDelay, easing = LinearOutSlowInEasing),
                initialScale = 0.92f,
            )
    private val fadeThroughExit: ExitTransition =
        fadeOut(tween(Fast, easing = FastOutLinearInEasing))

    // ---- Shared axis X: masuk ke halaman dalam / kembali ----
    private val sharedAxisEnter: EnterTransition =
        slideInHorizontally(tween(Slow, easing = Emphasized)) { it / 10 } +
            fadeIn(tween(FadeIn, delayMillis = FadeInDelay, easing = LinearOutSlowInEasing))
    private val sharedAxisExit: ExitTransition =
        slideOutHorizontally(tween(Slow, easing = Emphasized)) { -it / 10 } +
            fadeOut(tween(Fast, easing = FastOutLinearInEasing))
    private val sharedAxisPopEnter: EnterTransition =
        slideInHorizontally(tween(Slow, easing = Emphasized)) { -it / 10 } +
            fadeIn(tween(FadeIn, delayMillis = FadeInDelay, easing = LinearOutSlowInEasing))
    private val sharedAxisPopExit: ExitTransition =
        slideOutHorizontally(tween(Slow, easing = Emphasized)) { it / 10 } +
            fadeOut(tween(Fast, easing = FastOutLinearInEasing))

    // ---- Bilah yang muncul/hilang (bar bawah, pemutar mini) ----
    val BarEnter: EnterTransition =
        slideInVertically(tween(Medium, easing = EmphasizedDecelerate)) { it } +
            fadeIn(tween(Medium, easing = LinearOutSlowInEasing))
    val BarExit: ExitTransition =
        slideOutVertically(tween(Fast + 60, easing = EmphasizedAccelerate)) { it } +
            fadeOut(tween(Fast + 60, easing = FastOutLinearInEasing))

    /**
     * Pergantian dua keadaan kecil di tempat yang sama (mis. tombol
     * Subscribe <-> Subscribed). Fungsi, bukan val: ContentTransform
     * menyimpan keadaan, jangan dibagi antar AnimatedContent.
     */
    fun fadeSwap(): ContentTransform =
        fadeIn(tween(FadeIn - 60, delayMillis = FadeInDelay, easing = LinearOutSlowInEasing)) togetherWith
            fadeOut(tween(Fast, easing = FastOutLinearInEasing))

    /**
     * Aturan transisi antar kunci layar MainActivity:
     *  - ke pemutar: halaman lama TETAP terlihat di bawah selama
     *    pemutar naik (PlayerScreen menganimasikan dirinya sendiri),
     *    jadi tidak ada kedipan latar kosong;
     *  - dari pemutar: pemutar sudah meluncur turun sendiri, halaman
     *    tujuan memudar masuk (tidak muncul tiba-tiba);
     *  - antar tab: fade through;
     *  - ke halaman dalam: shared axis maju;
     *  - kembali ke tab: shared axis mundur, halaman yang keluar
     *    digambar DI ATAS halaman tujuan.
     * sizeTransform dimatikan: semua layar memenuhi ruang, tidak perlu
     * animasi ukuran (lebih ringan, tanpa pemotongan aneh).
     */
    fun screenTransition(initial: String, target: String): ContentTransform = when {
        target == "player" -> ContentTransform(
            targetContentEnter = EnterTransition.None,
            // alpha 0.999 menjaga isi lama tetap hidup selama animasi
            // (EnterTransition/ExitTransition.None akan membuangnya seketika).
            initialContentExit = fadeOut(
                tween(PlayerOpen, easing = LinearEasing),
                targetAlpha = 0.999f,
            ),
            sizeTransform = null,
        )
        initial == "player" -> ContentTransform(
            targetContentEnter = fadeIn(tween(FadeIn, easing = LinearOutSlowInEasing)),
            initialContentExit = ExitTransition.None,
            sizeTransform = null,
        )
        initial.startsWith("tab") && target.startsWith("tab") -> ContentTransform(
            targetContentEnter = fadeThroughEnter,
            initialContentExit = fadeThroughExit,
            sizeTransform = null,
        )
        target.startsWith("tab") -> ContentTransform(
            targetContentEnter = sharedAxisPopEnter,
            initialContentExit = sharedAxisPopExit,
            targetContentZIndex = -1f,
            sizeTransform = null,
        )
        else -> ContentTransform(
            targetContentEnter = sharedAxisEnter,
            initialContentExit = sharedAxisExit,
            sizeTransform = null,
        )
    }
}
