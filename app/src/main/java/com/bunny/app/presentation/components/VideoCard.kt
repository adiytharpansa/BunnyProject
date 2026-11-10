package com.bunny.app.presentation.components

import android.content.Intent
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.bunny.app.R
import com.bunny.core.model.DataCost
import com.bunny.core.model.VideoItem

/**
 * One feed item, laid out the way the official app lays out its feed
 * (owner instruction 2026-11-10, from the REA dissection): a
 * full-bleed 16:9 thumbnail, then a row with the channel's avatar on
 * the left and title + two-line meta on the right. Bunny's two
 * signatures stay: the Timbangan ≈MB badge on the thumbnail and the
 * Chip Alasan pill under the meta. The engine supplies no channel
 * avatars, so the avatar is an honest letter tile.
 */
@UnstableApi
@Composable
fun VideoCard(
    item: VideoItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    /** Inline preview: when [isPreviewing], the card plays [previewPlayer]. */
    previewPlayer: ExoPlayer? = null,
    isPreviewing: Boolean = false,
    /** Preview playback fraction 0..1 for the progress hairline. */
    previewProgress: Float? = null,
    /** Hide the Chip Alasan pill when the caller already shows it (active topic filter). */
    showReason: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (isPreviewing && previewPlayer != null) {
                // A TextureView, not a SurfaceView: surfaces punch
                // holes that break scrolling lists. The player is
                // shared — exactly one card attaches it at a time.
                AndroidView(
                    factory = { context -> TextureView(context) },
                    update = { view -> previewPlayer.setVideoTextureView(view) },
                    onRelease = { view -> previewPlayer.clearVideoTextureView(view) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                Text(
                    text = "Pratinjau",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            } else if (item.thumbnailUrl != null) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
            // durationSeconds lives in :core, so no smart cast across modules:
            // bind it with let instead.
            val badge = when {
                isPreviewing -> null
                item.isLive -> "LIVE"
                else -> item.durationSeconds?.let { formatDuration(it) }
            }
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            // Timbangan: the estimated data cost of watching, visible
            // BEFORE the tap. Ladder-based estimate (see DataCost); the
            // player replaces it with real format bitrates.
            val sizeBadge = if (item.isLive || isPreviewing) {
                null
            } else {
                item.durationSeconds?.let {
                    "≈ " + DataCost.formatSize(DataCost.estimateBytes(it))
                }
            }
            if (sizeBadge != null) {
                Text(
                    text = sizeBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            // Preview progress hairline, like the official app's
            // inline playback: a thin track with an orange fill.
            if (isPreviewing && previewProgress != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(previewProgress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(avatarColor(item.channelName)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.channelName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFF5F1EA),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // One meta line, the official feed's rhythm:
                // channel • views • age on a single ellipsized line.
                val meta = listOfNotNull(
                    item.channelName.ifEmpty { null },
                    item.viewCount?.let { "${formatCount(it)} x ditonton" },
                    item.uploadedText,
                ).joinToString(" • ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Chip Alasan: why this video is on the list, stated plainly.
                if (showReason) {
                    item.reason?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            CardMenu(videoId = item.id)
        }
    }
}

/**
 * The card's ⋮ menu (the official feed has one on every card): share
 * the canonical watch URL or copy it. Both are pure Android intents —
 * no engine involvement.
 */
@Composable
private fun CardMenu(videoId: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    val url = "https://www.youtube.com/watch?v=$videoId"
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.menu_more),
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_share)) },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    expanded = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_copy_link)) },
                leadingIcon = {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    clipboard.setText(AnnotatedString(url))
                },
            )
        }
    }
}

// Avatar tile colors: a stable per-channel pick from muted tones, so
// the feed reads as many channels (the official app's avatars are all
// different) instead of one repeated brand block. The letter stays
// near-white for contrast on every tone.
private val AvatarPalette = listOf(
    Color(0xFF8F481C),
    Color(0xFF6B3A1E),
    Color(0xFF7A5230),
    Color(0xFF5A3A24),
    Color(0xFFA5602A),
    Color(0xFF4A3020),
)

private fun avatarColor(channelName: String): Color =
    AvatarPalette[channelName.hashCode().mod(AvatarPalette.size)]

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1f jt".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1f rb".format(count / 1_000.0)
    else -> count.toString()
}
