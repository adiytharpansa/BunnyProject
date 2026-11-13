package com.bunny.app.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import com.bunny.app.data.AppSettings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Pengganti `.clickable` untuk kartu, chip, dan baris.
 * Saat ditekan: mengecil sedikit (3%), riak terpotong sesuai [shape], dan getar singkat saat ditahan.
 * Taruh SEBELUM `.padding(...)` agar seluruh area bisa diketuk.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tappable(
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    pressedScale: Float = 0.97f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = AppMotion.PressSpring,
        label = "pressScale",
    )
    val haptic = LocalHapticFeedback.current

    Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.shape = shape
            clip = shape != RectangleShape
        }
        .combinedClickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onLongClick = onLongClick?.let { callback ->
                {
                    if (AppSettings.haptics) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    callback()
                }
            },
            onClick = onClick,
        )
}
