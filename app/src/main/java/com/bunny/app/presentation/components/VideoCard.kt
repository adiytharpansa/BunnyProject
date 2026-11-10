package com.bunny.app.presentation.components

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
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
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.channelName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.channelName.isNotEmpty()) {
                    Text(
                        text = item.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = listOfNotNull(
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
    }
}

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
