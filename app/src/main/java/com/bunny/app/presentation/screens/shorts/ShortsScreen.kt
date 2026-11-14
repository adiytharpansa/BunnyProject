package com.bunny.app.presentation.screens.shorts

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bunny.app.R
import com.bunny.app.presentation.components.ResilientImage
import com.bunny.app.presentation.components.ShortsIcons
import com.bunny.app.presentation.screens.player.PlayerUiState
import com.bunny.app.presentation.screens.player.PlayerViewModel
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Halaman Shorts setara YouTube Shorts: gulir vertikal satu video per
 * layar, video memenuhi layar (tanpa kartu), video halaman yang tampil
 * DIPUTAR DI TEMPAT oleh pemutar Bunny.
 *
 * - Ketuk = jeda/lanjut, ketuk dua kali = suka (dengan hati melayang).
 * - Rel kanan: Suka (angka asli), Tidak suka, Komentar, Bagikan.
 * - Kiri-bawah: kanal + Langganan, judul (ketuk melebarkan dan
 *   menampilkan deskripsi), jumlah tayang.
 * - Bilah progres tipis di dasar; seret mendatar untuk menggeser.
 * - Menu titik tiga: Tidak tertarik, Jangan rekomendasikan kanal ini,
 *   Unduh, Salin tautan.
 * - Pojok kanan atas: suara (bisukan), cari, menu. Berada di bawah
 *   status bar (video sendiri menembus sampai tepi atas layar).
 *
 * Semua data berasal dari sumber (detail video, komentar, status
 * langganan lokal); angka yang tidak diketahui tidak dikarang.
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
    onChannelClick: (channelId: String, channelName: String) -> Unit = { _, _ -> },
    viewModel: ShortsViewModel = hiltViewModel(),
    // Instans yang sama dengan milik MainActivity (sama-sama
    // berpemilik Activity), jadi komentar & langganan menempel
    // pada video Shorts yang sedang diputar.
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val comments by playerViewModel.comments.collectAsState()
    val context = LocalContext.current
    var showComments by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // Bisu berlaku untuk seluruh sesi Shorts; saat halaman ini
    // ditinggalkan, volume pemutar bersama dikembalikan ke penuh
    // supaya video biasa tidak ikut bisu.
    var muted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(player, muted) {
        player?.volume = if (muted) 0f else 1f
    }
    DisposableEffect(player) {
        onDispose { player?.volume = 1f }
    }

    val items = state.items
    val pagerState = rememberPagerState(pageCount = { items.size })
    val activeItem = items.getOrNull(pagerState.currentPage)
    val activeDetails: VideoDetails? =
        (playerUiState as? PlayerUiState.Ready)?.details
            ?.takeIf { it.id == activeItem?.id }

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

            items.isEmpty() -> Column(
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
                // Halaman yang berhenti digeser menjadi video aktif:
                // laporkan ke pemilik pemutar supaya dimuat & diputar
                // di tempat. Selama masih menggeser, jangan memuat
                // apa pun — halaman yang lewat hanya gambar diam.
                LaunchedEffect(
                    pagerState.currentPage,
                    pagerState.isScrollInProgress,
                    items,
                ) {
                    if (!pagerState.isScrollInProgress) {
                        items.getOrNull(pagerState.currentPage)?.let { onShortActive(it.id) }
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
                        ownerAvatarUrl = comments.ownerAvatarUrl,
                        subscriptionState = playerViewModel::subscriptionState,
                        onToggleSubscription = playerViewModel::toggleSubscription,
                        onLike = { details -> viewModel.like(item, details) },
                        onDislike = { details -> viewModel.dislike(item, details) },
                        onOpenComments = { showComments = true },
                        onShare = { details -> shareShort(context, item, details) },
                        onChannelClick = onChannelClick,
                        onHoldSpeed = playerViewModel::setHoldSpeed,
                        onRetryPlayback = onRetryPlayback,
                    )
                }
            }
        }

        // Pojok kanan atas: suara + cari + menu titik tiga, melayang di
        // atas halaman dan berada di bawah status bar. Tiap tombol diberi
        // latar bundar tipis agar terbaca di atas video terang.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (player != null && activeItem != null && state.error == null && !state.isLoading) {
                IconButton(
                    onClick = { muted = !muted },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.28f)),
                ) {
                    Icon(
                        if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = stringResource(
                            if (muted) R.string.shorts_unmute else R.string.shorts_mute,
                        ),
                        tint = Color.White,
                    )
                }
            }
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.28f)),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.shorts_search),
                    tint = Color.White,
                )
            }
            if (activeItem != null && state.error == null && !state.isLoading) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.28f)),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.shorts_more),
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shorts_menu_not_interested)) },
                            onClick = {
                                menuOpen = false
                                viewModel.notInterested(activeItem, activeDetails)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.shorts_hidden),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shorts_menu_block_channel)) },
                            onClick = {
                                menuOpen = false
                                viewModel.dontRecommendChannel(activeItem, activeDetails)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.shorts_channel_blocked),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                        if (activeDetails != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shorts_menu_download)) },
                                onClick = {
                                    menuOpen = false
                                    playerViewModel.enqueueDownload()
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.shorts_download_started),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shorts_menu_copy_link)) },
                            onClick = {
                                menuOpen = false
                                copyShortLink(context, activeItem.id)
                            },
                        )
                    }
                }
            }
        }

        if (showComments) {
            ShortsCommentsSheet(
                state = comments,
                onDismiss = { showComments = false },
                onLoadMore = playerViewModel::loadMoreComments,
                onToggleReplies = playerViewModel::toggleReplies,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@UnstableApi
@Composable
private fun ShortsPage(
    item: VideoItem,
    isActive: Boolean,
    player: Player?,
    playerUiState: PlayerUiState,
    ownerAvatarUrl: String?,
    subscriptionState: (String) -> Flow<Boolean>,
    onToggleSubscription: (String, String, Boolean) -> Unit,
    onLike: (VideoDetails?) -> Unit,
    onDislike: (VideoDetails?) -> Unit,
    onOpenComments: () -> Unit,
    onShare: (VideoDetails?) -> Unit,
    onChannelClick: (String, String) -> Unit,
    onHoldSpeed: (Boolean) -> Unit,
    onRetryPlayback: () -> Unit,
) {
    // Detail asli video ini, hanya bila halaman ini yang sedang aktif
    // DAN pemutar sudah siap untuk video ini.
    val details: VideoDetails? =
        (playerUiState as? PlayerUiState.Ready)?.details
            ?.takeIf { isActive && it.id == item.id }

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

    // Bentuk video aktif: tegak (9:16) → ZOOM supaya mengisi layar tanpa
    // pita hitam; mendatar → FIT supaya tidak terpotong parah.
    var portrait by remember { mutableStateOf(true) }
    DisposableEffect(player, isActive) {
        val activePlayer = if (isActive) player else null
        if (activePlayer == null) {
            onDispose { }
        } else {
            val size = activePlayer.videoSize
            portrait = size.width == 0 || size.height >= size.width
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    portrait = videoSize.width == 0 || videoSize.height >= videoSize.width
                }
            }
            activePlayer.addListener(listener)
            onDispose { activePlayer.removeListener(listener) }
        }
    }

    // Status suka/tidak suka hanya untuk sesi ini; yang dicatat ke
    // selera lokal cukup sekali per tindakan.
    var liked by rememberSaveable(item.id) { mutableStateOf(false) }
    var disliked by rememberSaveable(item.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var showHeart by remember { mutableStateOf(false) }
    // Tahan layar = 2x selama ditahan (sama seperti YouTube).
    var holding by remember { mutableStateOf(false) }
    var suppressTap by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(isActive) {
        if (!isActive && holding) {
            holding = false
            onHoldSpeed(false)
        }
    }
    LaunchedEffect(showHeart) {
        if (showHeart) {
            delay(700)
            showHeart = false
        }
    }

    fun doLike() {
        if (!liked) {
            liked = true
            disliked = false
            onLike(details)
        }
    }

    fun doDislike() {
        if (!disliked) {
            disliked = true
            liked = false
            onDislike(details)
        }
    }

    // Kanal: detail asli bila ada, kalau tidak yang dibawa sumber.
    val channelId = details?.channelId ?: item.channelId
    val channelName = details?.channelName?.takeIf { it.isNotBlank() } ?: item.channelName
    val subscribed by remember(channelId) {
        channelId?.let { subscriptionState(it) } ?: flowOf(false)
    }.collectAsState(initial = false)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        ResilientImage(
            url = item.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            requestBuilder = {
                size(960, 540)
                crossfade(false)
            },
        )

        if (isActive && player != null) {
            // Permukaan video hidup: halaman ini yang sedang
            // diputar. Tanpa kontrol bawaan — ketuk = jeda/lanjut,
            // ketuk dua kali = suka.
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        // Bingkai pertama belum datang: biarkan gambar
                        // sampul di bawahnya terlihat, bukan hitam.
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setEnableComposeSurfaceSyncWorkaround(true)
                        this.player = player
                    }
                },
                update = { view ->
                    view.player = player
                    view.resizeMode = if (portrait) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(player) {
                        // Hanya mengamati (tidak menelan peristiwa): tahan
                        // >450 ms menyalakan 2x, lepas mengembalikannya.
                        // Geser vertikal membatalkan pengamatan sehingga
                        // pager tetap bebas berpindah video.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val held = try {
                                withTimeout(450L) { waitForUpOrCancellation() }
                                false
                            } catch (e: PointerEventTimeoutCancellationException) {
                                true
                            }
                            if (held) {
                                suppressTap = true
                                holding = true
                                onHoldSpeed(true)
                                waitForUpOrCancellation()
                                holding = false
                                onHoldSpeed(false)
                                scope.launch {
                                    delay(200)
                                    suppressTap = false
                                }
                            }
                        }
                    }
                    .combinedClickable(
                        interactionSource = null,
                        indication = null,
                        onDoubleClick = {
                            doLike()
                            showHeart = true
                        },
                        onClick = {
                            if (!suppressTap) {
                                if (player.playWhenReady) player.pause() else player.play()
                            }
                        },
                    ),
            )
        }

        // Gradasi atas (supaya ikon suara/cari/menu terbaca, termasuk di
        // bawah status bar) dan bawah (supaya teks terbaca di atas video
        // terang).
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(340.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                    ),
                ),
        )

        if (isActive && player != null) {
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
        }

        if (holding) {
            Text(
                text = "2x",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // Hati melayang saat ketuk dua kali.
        AnimatedVisibility(
            visible = showHeart,
            modifier = Modifier.align(Alignment.Center),
            enter = scaleIn(initialScale = 0.4f) + fadeIn(),
            exit = fadeOut(),
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color(0xFFFF2D55),
                modifier = Modifier.size(110.dp),
            )
        }

        // Info kiri-bawah.
        val meta = listOfNotNull(
            item.viewCountText?.takeIf { it.isNotEmpty() },
            item.uploadedText,
        ).joinToString(" • ")
        ShortsInfoBlock(
            channelName = channelName,
            avatarUrl = if (details != null) {
                ownerAvatarUrl ?: item.channelAvatarUrl
            } else {
                item.channelAvatarUrl
            },
            showSubscribe = channelId != null && channelName.isNotBlank(),
            subscribed = subscribed,
            onToggleSubscribe = {
                if (channelId != null) {
                    onToggleSubscription(channelId, channelName, subscribed)
                }
            },
            title = details?.title ?: item.title,
            description = details?.description,
            meta = meta,
            expanded = expanded,
            onToggleExpanded = { expanded = !expanded },
            onChannelClick = if (channelId != null) {
                { onChannelClick(channelId, channelName) }
            } else {
                null
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 92.dp, bottom = 24.dp),
        )

        // Rel tombol kanan.
        ShortsActionRail(
            liked = liked,
            disliked = disliked,
            likeLabel = details?.likeCount?.let { formatCount(it) }
                ?: stringResource(R.string.shorts_like),
            onLike = { doLike() },
            onDislike = { doDislike() },
            onComments = { if (isActive) onOpenComments() },
            onShare = { onShare(details) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 32.dp),
        )

        // Bilah progres di dasar.
        if (isActive && player != null && playerUiState is PlayerUiState.Ready) {
            ShortsProgressBar(
                player = player,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
