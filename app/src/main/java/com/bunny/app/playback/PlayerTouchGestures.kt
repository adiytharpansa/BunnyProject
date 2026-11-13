package com.bunny.app.playback

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Player gestures (adapted from the user's player document §8,
 * 2026-11-12, onto Bunny's PlayerView): double-tap the right or
 * left half to jump ±10 seconds, press-and-hold for a temporary
 * 2x, drag vertically on the right half for volume and on the
 * left half for brightness.
 *
 * The listener's contract with the PlayerView is conservative:
 * it consumes ONLY the gestures it owns (a recognised double
 * tap, an active hold, a classified vertical drag). Every other
 * touch is left unconsumed so the built-in controller keeps its
 * own tap and scrub behaviour — the gestures are an addition,
 * never the only way (the document's own accessibility rule).
 */
class PlayerTouchGestures(
    context: Context,
    private val callbacks: Callbacks,
) : View.OnTouchListener {

    interface Callbacks {
        fun onDoubleTapSeek(forward: Boolean)
        fun onHoldSpeed(holding: Boolean)
        fun onVolumeDelta(fraction: Float)
        fun onBrightnessDelta(fraction: Float)
        fun onGestureEnd()
    }

    private var holding = false
    private var scrollMode = MODE_UNDECIDED
    private var cumulativeX = 0f
    private var cumulativeY = 0f

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                callbacks.onDoubleTapSeek(event.x >= lastWidth / 2f)
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                holding = true
                callbacks.onHoldSpeed(true)
            }

            override fun onScroll(
                down: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (down == null || holding) return false
                cumulativeX += distanceX
                cumulativeY += distanceY
                if (scrollMode == MODE_UNDECIDED) {
                    val adx = abs(cumulativeX)
                    val ady = abs(cumulativeY)
                    scrollMode = when {
                        ady > CLASSIFY_PX && ady > adx ->
                            if (down.x >= lastWidth / 2f) {
                                MODE_VOLUME
                            } else {
                                MODE_BRIGHTNESS
                            }
                        adx > CLASSIFY_PX && adx >= ady -> MODE_IGNORE
                        else -> MODE_UNDECIDED
                    }
                    // The classifying move itself does not turn a dial.
                    return scrollMode == MODE_VOLUME ||
                        scrollMode == MODE_BRIGHTNESS
                }
                // distanceY is positive as the finger moves up:
                // up means more volume / more brightness.
                val fraction = distanceY / lastHeight.coerceAtLeast(1f)
                return when (scrollMode) {
                    MODE_VOLUME -> {
                        callbacks.onVolumeDelta(fraction)
                        true
                    }
                    MODE_BRIGHTNESS -> {
                        callbacks.onBrightnessDelta(fraction)
                        true
                    }
                    else -> false
                }
            }
        },
    )

    private var lastWidth = 0f
    private var lastHeight = 0f

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        lastWidth = view.width.toFloat()
        lastHeight = view.height.toFloat()
        var consumed = detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cumulativeX = 0f
            cumulativeY = 0f
            scrollMode = MODE_UNDECIDED
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            if (holding) {
                holding = false
                callbacks.onHoldSpeed(false)
                consumed = true
            }
            if (scrollMode == MODE_VOLUME || scrollMode == MODE_BRIGHTNESS) {
                callbacks.onGestureEnd()
                consumed = true
            }
            scrollMode = MODE_UNDECIDED
        }
        return consumed
    }

    companion object {
        private const val MODE_UNDECIDED = 0
        private const val MODE_VOLUME = 1
        private const val MODE_BRIGHTNESS = 2
        private const val MODE_IGNORE = 3
        private const val CLASSIFY_PX = 48f
    }
}
