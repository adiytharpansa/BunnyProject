package com.bunny.app.presentation.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bunny.app.R
import com.bunny.app.presentation.components.ResilientImage
import com.bunny.core.model.VideoComment

/**
 * Kartu ringkas di halaman tonton (seperti YouTube): judul + satu
 * komentar teratas. Mengetuk membuka [CommentsPanel].
 */
@Composable
internal fun CommentsEntry(
    state: CommentsUiState,
    onOpen: () -> Unit,
) {
    val first = state.items.firstOrNull()
    Surface(
        onClick = onOpen,
        enabled = first != null,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.player_comments_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            when {
                state.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                first == null -> Text(
                    stringResource(R.string.player_comments_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CommentAvatar(first.authorAvatarUrl, 28.dp)
                    Text(
                        first.text.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Panel daftar komentar yang menutupi area di BAWAH video (video tetap
 * terlihat dan terus diputar). Taruh di dalam Box yang mengisi area
 * bawah video.
 */
@Composable
internal fun CommentsPanel(
    visible: Boolean,
    state: CommentsUiState,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
    onToggleReplies: (VideoComment) -> Unit,
    onLoadMoreReplies: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.player_comments_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.dialog_close),
                        )
                    }
                }
                HorizontalDivider()

                val listState = rememberLazyListState()
                // Muat lanjutan otomatis saat tiga butir terakhir mendekat.
                val nearEnd by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= info.totalItemsCount - 3
                    }
                }
                LaunchedEffect(nearEnd, state.items.size, state.nextPage) {
                    if (nearEnd && state.nextPage != null && !state.isLoadingMore) {
                        onLoadMore()
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    when {
                        state.isLoading -> item { Loader() }
                        state.items.isEmpty() -> item {
                            Text(
                                stringResource(R.string.player_comments_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> {
                            items(state.items, key = { it.id }) { comment ->
                                CommentItem(
                                    comment = comment,
                                    replies = state.replies[comment.id],
                                    onToggleReplies = { onToggleReplies(comment) },
                                    onLoadMoreReplies = { onLoadMoreReplies(comment.id) },
                                )
                            }
                            if (state.isLoadingMore) item { Loader() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loader() {
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun CommentAvatar(url: String?, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        url?.let {
            ResilientImage(
                url = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Isi komentar: 4 baris, lalu "selengkapnya" / "lebih sedikit". */
@Composable
private fun ExpandableText(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
    )
    if (overflows) {
        Text(
            stringResource(if (expanded) R.string.player_less else R.string.player_more),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { expanded = !expanded },
        )
    }
}

@Composable
private fun CommentItem(
    comment: VideoComment,
    replies: RepliesUiState?,
    onToggleReplies: () -> Unit,
    onLoadMoreReplies: () -> Unit,
    isReply: Boolean = false,
) {
    // Rapikan: buang spasi pinggir dan baris kosong beruntun.
    val body = remember(comment.text) {
        comment.text.trim().replace(Regex("\n{3,}"), "\n\n")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CommentAvatar(comment.authorAvatarUrl, if (isReply) 24.dp else 36.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val head = listOfNotNull(
                comment.authorName.takeIf { it.isNotEmpty() },
                comment.publishedText,
            ).joinToString(" • ")
            if (head.isNotEmpty()) {
                Text(
                    head,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ExpandableText(body)

            // Baris aksi: 👍 jumlah suka, ❤ dari pembuat video.
            val likes = comment.likeCountText?.takeIf { it.isNotBlank() && it != "0" }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.ThumbUp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (likes != null) {
                        Text(
                            likes,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (comment.isCreatorHearted) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = stringResource(R.string.player_comment_hearted),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFE53935),
                    )
                }
            }

            // Balasan: tombol hanya bila server memberi tokennya.
            if (!isReply && comment.replyCount > 0 && comment.repliesContinuation != null) {
                val open = replies?.expanded == true
                TextButton(
                    onClick = onToggleReplies,
                    contentPadding = PaddingValues(end = 12.dp),
                ) {
                    Icon(
                        if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        if (open) {
                            stringResource(R.string.player_replies_hide)
                        } else {
                            stringResource(R.string.player_replies_show, comment.replyCount)
                        },
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (replies?.expanded == true) {
                when {
                    replies.isLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    replies.failed -> Text(
                        stringResource(R.string.player_replies_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        replies.items.forEach { reply ->
                            CommentItem(
                                comment = reply,
                                replies = null,
                                onToggleReplies = {},
                                onLoadMoreReplies = {},
                                isReply = true,
                            )
                        }
                        if (replies.nextPage != null) {
                            TextButton(
                                onClick = onLoadMoreReplies,
                                enabled = !replies.isLoadingMore,
                                contentPadding = PaddingValues(end = 12.dp),
                            ) {
                                Text(stringResource(R.string.player_replies_load_more))
                            }
                        }
                    }
                }
            }
        }
    }
}
