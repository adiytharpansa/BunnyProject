package com.bunny.app.miniplayer // TODO: sesuaikan package proyek Anda

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * Layar pemutar penuh. Dua jalur keluar (tombol back sistem & panah toolbar) HANYA
 * memanggil collapseToMini(). Navigasi keluar dilakukan otomatis oleh LaunchedEffect
 * di MainActivityExample karena sheetState berubah jadi MINI — jadi tidak ada
 * popBackStack ganda dan state tidak mungkin tidak sinkron.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlayerScreen(
    viewModel: PlaybackViewModel,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.uiState

    BackHandler { viewModel.collapseToMini() }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = state.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = { viewModel.collapseToMini() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Kembali (jadi mini-player)",
                    )
                }
            },
        )

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    keepScreenOn = true // layar tidak mati saat menonton
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = viewModel.player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )

        // Konten di bawah video: bisa di-scroll, tidak lagi melebihi tinggi layar.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = state.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Deskripsi, komentar, video terkait, dsb. ditaruh di sini.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
