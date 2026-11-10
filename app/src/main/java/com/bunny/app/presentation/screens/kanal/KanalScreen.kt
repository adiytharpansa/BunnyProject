package com.bunny.app.presentation.screens.kanal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.bunny.app.R
import com.bunny.app.presentation.components.BunnyHeader
import com.bunny.app.presentation.components.VideoCard
import com.bunny.core.model.VideoItem

@UnstableApi
@Composable
fun KanalScreen(
    onChannelClick: (channelId: String, channelName: String) -> Unit,
    onVideoClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    /** Daftar topik dari feed Beranda (dipakai sebagai tab). */
    topics: List<String> = emptyList(),
    /** Isi feed Beranda yang sudah dimuat; disaring ke kanal langganan saat tab topik dipilih. */
    feedItems: List<VideoItem> = emptyList(),
    viewModel: KanalViewModel = hiltViewModel(),
) {
    val subs by viewModel.subscriptions.collectAsState()

    // Filter milik halaman Kanal sendiri: menekan tab TIDAK pindah halaman.
    var topicFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = topicFilter
    val list = subs

    // Video bertopik itu, hanya dari kanal yang dilanggani.
    val topicVideos = remember(filter, feedItems, list) {
        if (filter == null || list == null) {
            emptyList()
        } else {
            val subscribedIds = list.map { it.channelId }.toSet()
            feedItems.filter { item ->
                item.reason == filter &&
                    item.channelId?.let { id -> id in subscribedIds } == true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        BunnyHeader(
            topics = topics,
            selectedTopic = filter,
            onTopicSelect = { topicFilter = it },
            onNotificationsClick = onNotificationsClick,
            onSearchClick = onSearchClick,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                // Tab topik dipilih: tampilkan video, atau pesan kosong.
                filter != null && topicVideos.isEmpty() -> Text(
                    text = stringResource(
                        R.string.kanal_empty_topic,
                        filter.removePrefix("Topik: "),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                filter != null -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(topicVideos, key = { it.id }) { item ->
                        VideoCard(
                            item = item,
                            onClick = { onVideoClick(item.id) },
                            showReason = false,
                        )
                    }
                }

                list.isEmpty() -> Text(
                    text = stringResource(R.string.kanal_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )

                else -> LazyColumn {
                    items(list, key = { it.channelId }) { sub ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChannelClick(sub.channelId, sub.channelName) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    sub.channelName.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Text(
                                sub.channelName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
