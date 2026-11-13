package com.bunny.app.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Pusat pengaturan gerak: satu tempat durasi + kurva supaya seluruh
 * aplikasi terasa seragam. Bunny tidak memakai NavHost (layar dipilih
 * oleh keadaan di MainActivity), jadi transisinya berbentuk
 * ContentTransform atas kunci layar string.
 */
object AppMotion {
    const val Fast = 90
    const val Medium = 220
    const val Slow = 300

    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // Fade through: pindah antar tab utama.
    private val fadeThroughEnter: EnterTransition =
        fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing)) +
            scaleIn(
                tween(210, delayMillis = 90, easing = LinearOutSlowInEasing),
                initialScale = 0.92f,
            )
    private val fadeThroughExit: ExitTransition =
        fadeOut(tween(Fast, easing = FastOutLinearInEasing))

    // Shared axis X: masuk ke halaman dalam / kembali ke tab.
    private val sharedAxisEnter: EnterTransition =
        slideInHorizontally(tween(Slow, easing = Emphasized)) { it / 10 } +
            fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing))
    private val sharedAxisExit: ExitTransition =
        slideOutHorizontally(tween(Slow, easing = Emphasized)) { -it / 10 } +
            fadeOut(tween(Fast, easing = FastOutLinearInEasing))
    private val sharedAxisPopEnter: EnterTransition =
        slideInHorizontally(tween(Slow, easing = Emphasized)) { -it / 10 } +
            fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing))
    private val sharedAxisPopExit: ExitTransition =
        slideOutHorizontally(tween(Slow, easing = Emphasized)) { it / 10 } +
            fadeOut(tween(Fast, easing = FastOutLinearInEasing))

    /**
     * Aturan transisi antar kunci layar MainActivity: pemutar tidak
     * ikut (animasi naik/meluncurnya sendiri), antar tab fade through,
     * ke halaman dalam shared axis maju, kembali ke tab shared axis
     * mundur.
     */
    fun screenTransition(initial: String, target: String): ContentTransform = when {
        initial == "player" || target == "player" ->
            ContentTransform(EnterTransition.None, ExitTransition.None)
        initial.startsWith("tab") && target.startsWith("tab") ->
            ContentTransform(fadeThroughEnter, fadeThroughExit)
        target.startsWith("tab") ->
            ContentTransform(sharedAxisPopEnter, sharedAxisPopExit)
        else ->
            ContentTransform(sharedAxisEnter, sharedAxisExit)
    }
}
