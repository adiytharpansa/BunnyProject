package com.bunny.app.presentation.screens.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.bunny.app.R
import com.bunny.app.presentation.components.ResilientImage
import com.bunny.app.presentation.components.ShortsIcons
import com.bunny.app.presentation.screens.player.PlayerUiState
import com.bunny.core.model.VideoItem

/**
 * Halaman Shorts: gulir vertikal satu video per layar (rasio 9:16),
 * meniru cara menonton Shorts yang sebenarnya — video halaman yang
 * sedang tampil DIPUTAR DI TEMPAT oleh pemutar Bunny, geser ke atas
 * atau bawah untuk pindah video. Tidak ada lagi ketuk-untuk-pindah
 * ke halaman pemutar; ketuk pada video yang sedang main hanya
 * menjeda/melanjutkan.
 */
@UnstableApi
@Composable
fun ShortsScreen(
    player: Player?,
    playerUiState: PlayerUiState,
    activeShortId: String?,
    onShortActive: (String) -> Unit,
    onRetryPlayback: () -> Unit,
    onSearchClick: () -> Unit = {},
    viewModel: ShortsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            state.isLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )

            state.error != null -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = state.error ?: stringResource(R.string.error_generic),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { viewModel.load() }) {
                    Text(stringResource(R.string.retry))
                }
            }

            state.items.isEmpty() -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    ShortsIcons.Filled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    stringResource(R.string.shorts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                val items = state.items
                val pagerState = rememberPagerState(pageCount = { items.size })
                // Halaman yang berhenti digeser menjadi video aktif:
                // laporkan ke pemilik pemutar supaya dimuat & diputar
                // di tempat. Selama masih menggeser, jangan memuat
                // apa pun — halaman yang lewat hanya gambar diam.
                LaunchedEffect(
                    pagerState.currentPage,
                    pagerState.isScrollInProgress,
                    items,
                ) {
                    if (!pagerState.isScrollInProgress && items.isNotEmpty()) {
                        onShortActive(items[pagerState.currentPage].id)
                    }
                }
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    key = { index -> items[index].id },
                ) { page ->
                    val item = items[page]
                    ShortsPage(
                        item = item,
                        isActive = item.id == activeShortId,
                        player = player,
                        playerUiState = playerUiState,
                        onRetryPlayback = onRetryPlayback,
                    )
                }
            }
        }

        // Bilah atas: judul + cari, melayang di atas halaman.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.nav_shorts),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSearchClick) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun ShortsPage(
    item: VideoItem,
    isActive: Boolean,
    player: Player?,
    playerUiState: PlayerUiState,
    onRetryPlayback: () -> Unit,
) {
    // Niat main/jeda halaman aktif, mengikuti pemutar bersama.
    // Memakai playWhenReady (bukan isPlaying) supaya jeda muat
    // (buffering) tidak disalahartikan sebagai jeda oleh pengguna.
    var wantsPlay by remember { mutableStateOf(false) }
    DisposableEffect(player, isActive) {
        val activePlayer = if (isActive) player else null
        if (activePlayer == null) {
            wantsPlay = false
            onDispose { }
        } else {
            wantsPlay = activePlayer.playWhenReady
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    wantsPlay = playWhenReady
                }
            }
            activePlayer.addListener(listener)
            onDispose { activePlayer.removeListener(listener) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 52.dp, bottom = 12.dp)
                .fillMaxHeight()
                .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            ResilientImage(
                url = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                requestBuilder = {
                    size(960, 540)
                    crossfade(false)
                },
            )

            if (isActive && player != null) {
                // Permukaan video hidup: halaman ini yang sedang
                // diputar. Tanpa kontrol bawaan — Shorts memang
                // tanpa bilah geser; ketuk = jeda/lanjut.
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setEnableComposeSurfaceSyncWorkaround(true)
                            this.player = player
                        }
                    },
                    update = { view -> view.player = player },
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(interactionSource = null, indication = null) {
                            if (player.playWhenReady) player.pause() else player.play()
                        },
                )

                when (playerUiState) {
                    PlayerUiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )

                    is PlayerUiState.Error -> Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = playerUiState.message,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp,
                        )
                        Button(onClick = onRetryPlayback) {
                            Text(stringResource(R.string.retry))
                        }
                    }

                    is PlayerUiState.Ready -> {
                        if (!wantsPlay) {
                            // Penanda jeda: video sudah termuat, ketukan
                            // pengguna sendiri yang menjedanya.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.shorts_play),
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                    }
                }
            } else if (!isActive) {
                // Halaman tetangga: gambar diam + durasi, tanpa tombol
                // apa pun — tidak ada lagi ketuk-untuk-pindah.
                item.durationSeconds?.let { seconds ->
                    Text(
                        text = formatDuration(seconds),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // Gradasi + info di bawah.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Baris kanal hanya bila sumber membawa nama kanal;
                // grid Shorts tidak membawanya — tidak dikarang.
                if (item.channelName.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                    ) {
                        item.channelAvatarUrl?.let { url ->
                            ResilientImage(
                                url = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.channelName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                }
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    item.viewCountText?.takeIf { it.isNotEmpty() },
                    item.uploadedText,
                ).joinToString(" • ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}
