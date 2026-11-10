package com.bunny.app.presentation.screens.kanal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bunny.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanalScreen(
    onChannelClick: (channelId: String, channelName: String) -> Unit,
    onSearchClick: () -> Unit = {},
    viewModel: KanalViewModel = hiltViewModel(),
) {
    val subs by viewModel.subscriptions.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.kanal_title)) },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search_open),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val list = subs
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

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
