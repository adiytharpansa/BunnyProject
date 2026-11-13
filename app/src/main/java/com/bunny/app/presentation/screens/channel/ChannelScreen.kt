package com.bunny.app.presentation.screens.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.bunny.app.R
import com.bunny.app.presentation.components.VideoCard
import com.bunny.app.presentation.components.ResilientImage

/**
 * The channel page, shaped after the official app's channel header
 * (REA dissection 2026-11-10): identity block with the subscribe
 * action on top, the channel's uploads below. The header shows the
 * channel's real published identity (photo, counts, description)
 * from the engine whenever it arrives.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channelId: String,
    channelName: String,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit = {},
    viewModel: ChannelViewModel = hiltViewModel(),
) {
    LaunchedEffect(channelId) { viewModel.load(channelId, channelName) }
    val state by viewModel.uiState.collectAsState()
    val subFlow = remember(channelId) { viewModel.subscriptionState(channelId) }
    val subscribed by subFlow.collectAsState(initial = false)
    val displayName = state.channelName.ifEmpty { channelName }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // The channel's real photo when the engine brought
                    // it; the circle stays plain until then (no letter
                    // stand-in — user rule 2026-11-11).
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        state.info?.avatarUrl?.let { url ->
                            ResilientImage(
                                url = url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        // The published counts as served by the channel
                        // page itself; the loaded-list count only speaks
                        // when no header data arrived.
                        val infoLine = listOfNotNull(
                            state.info?.subscriberText,
                            state.info?.videoCountText,
                        ).joinToString(" • ")
                        if (infoLine.isNotEmpty()) {
                            Text(
                                infoLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (state.items.isNotEmpty()) {
                            Text(
                                stringResource(
                                    R.string.channel_video_count,
                                    state.items.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (subscribed) {
                            OutlinedButton(onClick = {
                                viewModel.toggleSubscription(
                                    channelId,
                                    displayName,
                                    subscribed = true,
                                )
                            }) {
                                Text(stringResource(R.string.player_unsubscribe))
                            }
                        } else {
                            Button(onClick = {
                                viewModel.toggleSubscription(
                                    channelId,
                                    displayName,
                                    subscribed = false,
                                )
                            }) {
                                Text(stringResource(R.string.player_subscribe))
                            }
                        }
                    }
                }
            }
            // The channel's own description, as published on its page;
            // three lines until tapped.
            state.info?.description?.takeIf { it.isNotBlank() }?.let { description ->
                item {
                    var expanded by remember(channelId) { mutableStateOf(false) }
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clickable { expanded = !expanded },
                    )
                }
            }
            when {
                state.isLoading -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                }

                state.error != null -> item {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            state.error ?: stringResource(R.string.error_generic),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = { viewModel.load(channelId, channelName) }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }

                state.items.isEmpty() -> item {
                    Text(
                        stringResource(R.string.channel_empty),
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> items(state.items, key = { it.id }) { item ->
                    VideoCard(item = item, onClick = { onVideoClick(item.id) })
                }
            }
        }
    }
}
