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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.bunny.app.R
import com.bunny.app.data.ChannelGroupEntity
import com.bunny.app.data.SubscriptionEntity
import com.bunny.app.presentation.components.BunnyHeader
import com.bunny.app.presentation.components.ResilientImage
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
    /** Fase 7-C: whether the bell wears its unread dot. */
    notificationsUnread: Boolean = false,
    viewModel: KanalViewModel = hiltViewModel(),
) {
    val subs by viewModel.subscriptions.collectAsState()
    val groups by viewModel.groups.collectAsState()

    // Filter milik halaman Kanal sendiri: menekan tab TIDAK pindah halaman.
    var topicFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = topicFilter
    val list = subs

    // Grup langganan (Fase 6): "all" | "none" | "g:<id>".
    var groupKey by rememberSaveable { mutableStateOf("all") }
    var showGroupManager by remember { mutableStateOf(false) }

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

    val shownSubs = remember(list, groupKey) {
        val all = list.orEmpty()
        when {
            groupKey == "all" -> all
            groupKey == "none" -> all.filter { it.groupId == null }
            groupKey.startsWith("g:") -> {
                val id = groupKey.removePrefix("g:").toLongOrNull()
                all.filter { it.groupId == id }
            }
            else -> all
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
            notificationsUnread = notificationsUnread,
        )
        if (filter == null && !list.isNullOrEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    FilterChip(
                        selected = groupKey == "all",
                        onClick = { groupKey = "all" },
                        label = { Text(stringResource(R.string.kanal_group_all)) },
                    )
                }
                items(groups, key = { it.id }) { group ->
                    FilterChip(
                        selected = groupKey == "g:${group.id}",
                        onClick = { groupKey = "g:${group.id}" },
                        label = { Text(group.name) },
                    )
                }
                item {
                    FilterChip(
                        selected = groupKey == "none",
                        onClick = { groupKey = "none" },
                        label = { Text(stringResource(R.string.kanal_group_none)) },
                    )
                }
                item {
                    TextButton(onClick = { showGroupManager = true }) {
                        Text(stringResource(R.string.kanal_groups_manage))
                    }
                }
            }
        }
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
                    items(shownSubs, key = { it.channelId }) { sub ->
                        ChannelRow(
                            sub = sub,
                            groups = groups,
                            onClick = { onChannelClick(sub.channelId, sub.channelName) },
                            onSetGroup = { groupId ->
                                viewModel.setChannelGroup(sub.channelId, groupId)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showGroupManager) {
        GroupManagerDialog(
            groups = groups,
            onDismiss = { showGroupManager = false },
            onCreate = { viewModel.createGroup(it) },
            onRename = { id, name -> viewModel.renameGroup(id, name) },
            onDelete = { viewModel.deleteGroup(it) },
        )
    }
}

@Composable
private fun ChannelRow(
    sub: SubscriptionEntity,
    groups: List<ChannelGroupEntity>,
    onClick: () -> Unit,
    onSetGroup: (Long?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Foto asli kanal bila tersimpan; lingkaran tetap polos
        // selagi fotonya belum ada (aturan semua asli).
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            sub.avatarUrl?.let { url ->
                ResilientImage(
                    url = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(
                sub.channelName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            groups.firstOrNull { it.id == sub.groupId }?.let { group ->
                Text(
                    group.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.kanal_group_move),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                Text(
                    stringResource(R.string.kanal_group_move),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.kanal_group_none)) },
                    trailingIcon = {
                        if (sub.groupId == null) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        onSetGroup(null)
                        menuOpen = false
                    },
                )
                groups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group.name) },
                        trailingIcon = {
                            if (sub.groupId == group.id) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            onSetGroup(group.id)
                            menuOpen = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupManagerDialog(
    groups: List<ChannelGroupEntity>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanal_group_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = {
                            Text(stringResource(R.string.kanal_group_new_hint))
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onCreate(newName)
                            newName = ""
                        },
                        enabled = newName.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.kanal_group_add))
                    }
                }
                if (groups.isEmpty()) {
                    Text(
                        stringResource(R.string.kanal_group_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                groups.forEach { group ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (editingId == group.id) {
                            OutlinedTextField(
                                value = editingText,
                                onValueChange = { editingText = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    onRename(group.id, editingText)
                                    editingId = null
                                },
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        } else {
                            Text(
                                group.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    editingId = group.id
                                    editingText = group.name
                                },
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null)
                            }
                        }
                        IconButton(onClick = { onDelete(group.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(
                                    R.string.kanal_group_delete,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.downloads_back))
            }
        },
    )
}
