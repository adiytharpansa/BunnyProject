package com.bunny.app.presentation.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.bunny.app.R
import com.bunny.app.presentation.components.BunnyHeader
import com.bunny.app.presentation.components.VideoCard
import com.bunny.app.presentation.screens.home.SkeletonCard

@UnstableApi
@Composable
fun LiveScreen(
    onVideoClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    /** Daftar topik dari feed Beranda (dipakai sebagai tab). */
    topics: List<String> = emptyList(),
    /** Fase 7-C: whether the bell wears its unread dot. */
    notificationsUnread: Boolean = false,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Filter milik halaman Live sendiri: menekan tab TIDAK pindah halaman.
    var topicFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = topicFilter
    // Cadangan: bila feed Beranda belum selesai, pakai topik dari siaran Live.
    val tabTopics = topics.ifEmpty { state.items.mapNotNull { it.reason }.distinct() }
    val visibleItems = if (filter == null) {
        state.items
    } else {
        state.items.filter { it.reason == filter }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BunnyHeader(
            topics = tabTopics,
            selectedTopic = filter,
            onTopicSelect = { topicFilter = it },
            onNotificationsClick = onNotificationsClick,
            onSearchClick = onSearchClick,
            notificationsUnread = notificationsUnread,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                state.isLoading -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false,
                ) {
                    items(3) { SkeletonCard() }
                }

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
                        Icons.Filled.Sensors,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        stringResource(R.string.live_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                // Ada siaran Live, tetapi tidak ada untuk topik yang dipilih.
                visibleItems.isEmpty() -> Text(
                    text = stringResource(
                        R.string.live_empty_topic,
                        filter?.removePrefix("Topik: ").orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleItems, key = { it.id }) { item ->
                        VideoCard(
                            item = item,
                            onClick = { onVideoClick(item.id) },
                            // Label topik aktif tidak perlu diulang di kartu.
                            showReason = item.reason != filter,
                        )
                    }
                }
            }
        }
    }
}
