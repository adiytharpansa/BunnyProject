package com.bunny.app.miniplayer // TODO: sesuaikan package proyek Anda

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pasang SEKALI di root, di dalam Box yang sama dengan NavHost:
 *
 *   Box(Modifier.fillMaxSize()) {
 *       NavHost(...)
 *       MiniPlayerOverlay(viewModel = playbackViewModel)
 *   }
 *
 * Bila app punya bottom navigation bar, isi [bottomInset] dengan tingginya agar
 * mini-player tidak menutupi bar tersebut.
 */
@UnstableApi
@Composable
fun BoxScope.MiniPlayerOverlay(
    viewModel: PlaybackViewModel,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val state = viewModel.uiState
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val dismissThresholdPx = with(density) { 96.dp.toPx() }
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    // Setiap kali mini-player muncul lagi, posisinya selalu mulai dari tengah (bug sebelumnya:
    // offset sisa swipe-dismiss membuatnya muncul bergeser/di luar layar).
    LaunchedEffect(state.sheetState) {
        if (state.sheetState == PlayerSheetState.MINI) offsetX.snapTo(0f)
    }

    AnimatedVisibility(
        visible = state.sheetState == PlayerSheetState.MINI,
        modifier = modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(12.dp)
            .padding(bottom = bottomInset),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = fadeOut(tween(120)),
    ) {
        var progress by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(state.isPlaying) {
            while (true) {
                val duration = viewModel.player.duration
                progress = if (duration > 0) {
                    (viewModel.player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                } else 0f
                if (!state.isPlaying) break
                delay(500)
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .width(296.dp)
                .graphicsLayer {
                    translationX = offsetX.value
                    alpha = 1f - (abs(offsetX.value) / screenWidthPx).coerceIn(0f, 1f)
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offsetX.snapTo(offsetX.value + delta) }
                    },
                    onDragStopped = { velocity ->
                        scope.launch {
                            val farEnough = abs(offsetX.value) > dismissThresholdPx
                            val fastFling = abs(velocity) > 1500f
                            if (farEnough || fastFling) {
                                val direction = if (offsetX.value != 0f) {
                                    if (offsetX.value > 0) 1f else -1f
                                } else if (velocity > 0) 1f else -1f
                                offsetX.animateTo(direction * screenWidthPx, tween(150))
                                viewModel.dismiss()
                            } else {
                                offsetX.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                            }
                        }
                    },
                )
                .clickable { viewModel.expandToFull() },
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                player = viewModel.player
                            }
                        },
                        onRelease = { it.player = null },
                        modifier = Modifier
                            .width(112.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(topStart = 12.dp)),
                    )

                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )

                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Jeda" else "Putar",
                        )
                    }
                    IconButton(
                        onClick = { viewModel.dismiss() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Tutup")
                    }
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
