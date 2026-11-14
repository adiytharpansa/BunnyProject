package com.bunny.app.presentation.screens.shorts

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.bunny.app.R
import com.bunny.app.presentation.components.ResilientImage
import com.bunny.app.presentation.screens.player.CommentsUiState
import com.bunny.app.presentation.screens.player.RepliesUiState
import com.bunny.core.model.VideoComment
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem
import kotlinx.coroutines.delay
import java.util.Locale

// ----------------------------------------------------------------------
// Pembantu
// ----------------------------------------------------------------------

/**
 * Bayangan lembut untuk teks di atas video: tetap terbaca walau
 * latarnya terang (mis. baju biru muda) dan gradasi bawah belum
 * cukup gelap.
 */
private val ShortsTextShadow = TextStyle(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.65f),
        offset = Offset(0f, 1.5f),
        blurRadius = 8f,
    ),
)

internal fun shortUrl(id: String): String = "https://www.youtube.com/shorts/$id"

/** Angka asli dari sumber, diringkas gaya Indonesia: 1,2 rb · 3,4 jt. */
internal fun formatCount(n: Long): String {
    fun one(v: Double, unit: String): String {
        val text = String.format(Locale.forLanguageTag("id-ID"), "%.1f", v)
            .removeSuffix(",0")
        return "$text $unit"
    }
    return when {
        n < 1_000L -> n.toString()
        n < 1_000_000L -> one(n / 1_000.0, "rb")
        n < 1_000_000_000L -> one(n / 1_000_000.0, "jt")
        else -> one(n / 1_000_000_000.0, "M")
    }
}

internal fun shareShort(context: Context, item: VideoItem, details: VideoDetails?) {
    val title = details?.title ?: item.title
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "$title\n${shortUrl(item.id)}")
    }
    try {
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        // Tidak ada aplikasi pembagi: tidak ada yang bisa dilakukan.
    }
}

internal fun copyShortLink(context: Context, id: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Shorts", shortUrl(id)))
    Toast.makeText(
        context,
        context.getString(R.string.shorts_link_copied),
        Toast.LENGTH_SHORT,
    ).show()
}

// ----------------------------------------------------------------------
// Rel tombol kanan: Suka · Tidak suka · Komentar · Bagikan
// ----------------------------------------------------------------------

@Composable
internal fun ShortsActionRail(
    liked: Boolean,
    disliked: Boolean,
    likeLabel: String,
    commentLabel: String,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onComments: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RailButton(
            icon = if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            label = likeLabel,
            description = stringResource(R.string.shorts_like),
            active = liked,
            onClick = onLike,
        )
        RailButton(
            icon = if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
            label = stringResource(R.string.shorts_dislike),
            description = stringResource(R.string.shorts_dislike),
            active = disliked,
            onClick = onDislike,
        )
        RailButton(
            icon = Icons.Filled.ChatBubbleOutline,
            label = commentLabel,
            description = stringResource(R.string.shorts_comments),
            active = false,
            onClick = onComments,
        )
        RailButton(
            icon = Icons.Filled.Share,
            label = stringResource(R.string.shorts_share),
            description = stringResource(R.string.shorts_share),
            active = false,
            onClick = onShare,
        )
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    // Lebar kolom dikunci 68dp supaya label panjang ("Tidak suka",
    // "Komentar") tidak mendorong rel keluar dari tepi layar, dan
    // seluruh kolom (ikon + label) menjadi area ketuk ≥ 48dp.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(68.dp)
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (active) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = ShortsTextShadow,
        )
    }
}

// ----------------------------------------------------------------------
// Info kiri-bawah: kanal + Langganan, judul (ketuk = lebarkan), meta
// ----------------------------------------------------------------------

@Composable
internal fun ShortsInfoBlock(
    channelName: String,
    avatarUrl: String?,
    showSubscribe: Boolean,
    subscribed: Boolean,
    onToggleSubscribe: () -> Unit,
    title: String,
    description: String?,
    meta: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onChannelClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val channelTap = if (onChannelClick != null) {
        Modifier.clickable(
            interactionSource = null,
            indication = null,
            onClick = onChannelClick,
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Baris kanal hanya bila sumber membawa nama kanal;
        // tidak dikarang.
        if (channelName.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                        .then(channelTap),
                ) {
                    if (avatarUrl != null) {
                        ResilientImage(
                            url = avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = channelName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = ShortsTextShadow,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(channelTap),
                )
                if (showSubscribe) {
                    Spacer(Modifier.width(12.dp))
                    // Belum berlangganan: oranye Bunny (aksen utama tema)
                    // supaya selaras dengan Beranda; sudah berlangganan:
                    // tombol tembus pandang yang tenang.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (subscribed) {
                                    Color.White.copy(alpha = 0.22f)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                onClick = onToggleSubscribe,
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (subscribed) {
                                stringResource(R.string.shorts_subscribed)
                            } else {
                                stringResource(R.string.player_subscribe)
                            },
                            color = if (subscribed) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (expanded) 6 else 2,
            overflow = TextOverflow.Ellipsis,
            style = ShortsTextShadow,
            modifier = Modifier.clickable(
                interactionSource = null,
                indication = null,
                onClick = onToggleExpanded,
            ),
        )
        if (expanded && !description.isNullOrBlank()) {
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                style = ShortsTextShadow,
            )
        }
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = ShortsTextShadow,
            )
        }
    }
}

// ----------------------------------------------------------------------
// Bilah progres tipis di dasar; seret mendatar untuk menggeser posisi
// ----------------------------------------------------------------------

@Composable
internal fun ShortsProgressBar(
    player: Player,
    modifier: Modifier = Modifier,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            if (!dragging) {
                val duration = player.duration
                progress = if (duration != C.TIME_UNSET && duration > 0L) {
                    (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
            delay(100)
        }
    }

    // Saat disentuh, bilah menebal (2dp → 8dp) dan area sentuhnya
    // 32dp tinggi supaya gampang diseret dengan jempol.
    val barHeight = if (dragging) 8.dp else 3.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(player) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    val duration = player.duration
                    if (duration != C.TIME_UNSET && duration > 0L && size.width > 0) {
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        progress = fraction
                        player.seekTo((duration * fraction).toLong())
                    }
                }
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .background(Color.White.copy(alpha = 0.28f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = progress)
                .height(barHeight)
                .background(
                    if (dragging) MaterialTheme.colorScheme.primary else Color.White,
                ),
        )
    }
}

// ----------------------------------------------------------------------
// Sheet komentar (komentar & balasan asli dari PlayerViewModel)
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShortsCommentsSheet(
    state: CommentsUiState,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onToggleReplies: (VideoComment) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.shorts_comments),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.shorts_close),
                    )
                }
            }
            when {
                state.isLoading -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.items.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.shorts_comments_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.items, key = { comment -> comment.id }) { comment ->
                        val replies = state.replies[comment.id]
                        CommentRow(
                            comment = comment,
                            replies = replies,
                            nested = false,
                            onToggleReplies = { onToggleReplies(comment) },
                        )
                        if (replies != null && replies.expanded) {
                            replies.items.forEach { reply ->
                                CommentRow(
                                    comment = reply,
                                    replies = null,
                                    nested = true,
                                    onToggleReplies = {},
                                )
                            }
                        }
                    }
                    if (state.nextPage != null) {
                        item(key = "more") {
                            LaunchedEffect(state.items.size) { onLoadMore() }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: VideoComment,
    replies: RepliesUiState?,
    nested: Boolean,
    onToggleReplies: () -> Unit,
) {
    val avatarSize = if (nested) 24.dp else 34.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (nested) 58.dp else 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            comment.authorAvatarUrl?.let { url ->
                ResilientImage(
                    url = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val header = listOfNotNull(
                comment.authorName.takeIf { it.isNotBlank() },
                comment.publishedText?.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
            if (header.isNotEmpty()) {
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = comment.text,
                style = MaterialTheme.typography.bodyMedium,
            )
            val likes = comment.likeCountText?.takeIf { it.isNotBlank() }
            if (likes != null || comment.isCreatorHearted) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (likes != null) {
                        Icon(
                            Icons.Outlined.ThumbUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = likes,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (comment.isCreatorHearted) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = Color(0xFFFF2D55),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            if (!nested && comment.replyCount > 0 && comment.repliesContinuation != null) {
                Text(
                    text = if (replies?.expanded == true) {
                        stringResource(R.string.shorts_replies_hide)
                    } else {
                        stringResource(R.string.shorts_replies_count, comment.replyCount)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onToggleReplies,
                        ),
                )
                if (replies != null && replies.expanded) {
                    when {
                        replies.isLoading -> CircularProgressIndicator(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )

                        replies.failed -> Text(
                            text = stringResource(R.string.shorts_replies_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
