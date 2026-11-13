package com.bunny.app.presentation.components

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.bunny.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Pojok: bit 0 = kanan, bit 1 = bawah. 3 = kanan bawah (bawaan, seperti YouTube). */
const val MINI_CORNER_BOTTOM_RIGHT = 3

/**
 * Pemutar mini melayang gaya YouTube: jendela video 16:9 selebar setengah layar,
 * jeda (kiri atas) dan ✕ (kanan atas) di atas video, progres di bawah.
 * Ketuk = membesar ([onExpand]). Seret = pindah, lepas = menempel ke pojok terdekat.
 *
 * Pasang di Box layar penuh. Komposabel ini TIDAK menangkap sentuhan di luar
 * jendelanya, jadi halaman di bawahnya tetap bisa digulir.
 *
 * [bottomInset]: ruang yang harus dijauhi di bawah (bar tab, atau bilah navigasi
 * sistem bila bar tab tidak tampil).
 */
@UnstableApi
@Composable
fun FloatingMiniPlayer(
    player: Player?,
    corner: Int,
    onCornerChange: (Int) -> Unit,
    bottomInset: Dp,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember(player) { mutableStateOf(player?.isPlaying == true) }
    DisposableEffect(player) {
        val p = player
        if (p == null) {
            onDispose { }
        } else {
            isPlaying = p.isPlaying
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            }
            p.addListener(listener)
            onDispose { p.removeListener(listener) }
        }
    }

    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(player, lifecycleOwner) {
        // Polling hanya selama layar tampil; di latar tidak perlu.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                player?.let {
                    positionMs = it.currentPosition.coerceAtLeast(0L)
                    durationMs = it.duration.coerceAtLeast(0L)
                }
                delay(500)
            }
        }
    }

    // Ikon jeda/✕ memudar setelah 3 detik saat video diputar; muncul lagi saat
    // jendela disentuh atau diseret. Saat dijeda, ikon selalu tampil.
    var controlsVisible by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    fun showControls() {
        controlsVisible = true
        touchTick++
    }
    LaunchedEffect(controlsVisible, isPlaying, touchTick) {
        if (controlsVisible && isPlaying) {
            delay(3000)
            controlsVisible = false
        }
    }
    val controlsShown = controlsVisible || !isPlaying
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsShown) 1f else 0f,
        animationSpec = tween(200),
        label = "miniControlsAlpha",
    )

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val parentW = with(density) { maxWidth.toPx() }
        val parentH = with(density) { maxHeight.toPx() }
        val winW = (maxWidth * 0.5f).coerceIn(180.dp, 320.dp)
        val winH = winW * 9f / 16f
        val wPx = with(density) { winW.toPx() }
        val hPx = with(density) { winH.toPx() }
        val marginPx = with(density) { 12.dp.toPx() }
        val topPx = with(density) { topInset.toPx() } + marginPx
        val bottomPx = with(density) { bottomInset.toPx() } + marginPx

        fun target(c: Int): Offset {
            val right = (c and 1) == 1
            val bottom = (c and 2) == 2
            return Offset(
                x = if (right) parentW - wPx - marginPx else marginPx,
                y = if (bottom) parentH - hPx - bottomPx else topPx,
            )
        }

        val start = target(corner)
        val ax = remember { Animatable(start.x) }
        val ay = remember { Animatable(start.y) }

        // Ukuran layar / inset berubah (putar layar, bar muncul): geser halus ke
        // posisi pojok yang baru. Saat pertama tampil, posisinya sudah sama (tanpa gerak).
        LaunchedEffect(parentW, parentH, topPx, bottomPx, wPx, hPx) {
            val t = target(corner)
            launch { ax.animateTo(t.x, tween(220)) }
            launch { ay.animateTo(t.y, tween(220)) }
        }

        val shape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .offset { IntOffset(ax.value.roundToInt(), ay.value.roundToInt()) }
                .size(winW, winH)
                .shadow(8.dp, shape)
                .clip(shape)
                // Garis tepi tipis supaya jendela tetap terlihat di tema gelap.
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .background(Color.Black)
                // Ketuk pertama (ikon tersembunyi) = tampilkan ikon; ketuk berikutnya = membesar.
                .clickable {
                    if (controlsShown) onExpand() else showControls()
                }
                .pointerInput(parentW, parentH, topPx, bottomPx, wPx, hPx) {
                    fun release() {
                        // Diseret keluar layar (lebih dari 40% jendela melewati tepi kiri/kanan): tutup.
                        val dismissLeft = ax.value < -wPx * 0.4f
                        val dismissRight = ax.value > parentW - wPx * 0.6f
                        if (dismissLeft || dismissRight) {
                            val outX = if (dismissRight) parentW else -wPx
                            scope.launch {
                                ax.animateTo(outX, tween(160))
                                onClose()
                            }
                            return
                        }
                        val cx = ax.value + wPx / 2f
                        val cy = ay.value + hPx / 2f
                        val newCorner =
                            (if (cx > parentW / 2f) 1 else 0) or (if (cy > parentH / 2f) 2 else 0)
                        val t = target(newCorner)
                        scope.launch {
                            launch {
                                ax.animateTo(
                                    t.x,
                                    spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow),
                                )
                            }
                            launch {
                                ay.animateTo(
                                    t.y,
                                    spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow),
                                )
                            }
                        }
                        onCornerChange(newCorner)
                    }
                    detectDragGestures(
                        onDragStart = { showControls() },
                        onDrag = { change, drag ->
                            change.consume()
                            scope.launch {
                                ax.snapTo(ax.value + drag.x)
                                ay.snapTo(ay.value + drag.y)
                            }
                        },
                        onDragEnd = { release() },
                        onDragCancel = { release() },
                    )
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx)
                        .inflate(R.layout.bunny_mini_player_view, FrameLayout(ctx), false)
                        as PlayerView).also { it.player = player }
                },
                update = { if (it.player !== player) it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )

            // Selubung tipis di atas supaya ikon putih terbaca di video terang.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .alpha(controlsAlpha)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .alpha(controlsAlpha)
                    // Jarak dari tepi supaya ✕ tidak mudah tersalah ketuk dengan "membesar".
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // enabled = false saat ikon tersembunyi: ketukan diteruskan ke jendela
                // (menampilkan ikon), bukan memicu jeda/tutup tak terlihat.
                IconButton(
                    onClick = {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        touchTick++
                    },
                    enabled = controlsShown,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.mini_pause else R.string.mini_play,
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(
                    onClick = onClose,
                    enabled = controlsShown,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.mini_close),
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            // Garis progres merah di dasar jendela (dibaca saat menggambar saja).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .drawBehind {
                        drawRect(Color.White.copy(alpha = 0.25f))
                        if (durationMs > 0L) {
                            val f = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                            drawRect(
                                color = Color(0xFFFF2D2D),
                                size = Size(size.width * f, size.height),
                            )
                        }
                    },
            )
        }
    }
}
