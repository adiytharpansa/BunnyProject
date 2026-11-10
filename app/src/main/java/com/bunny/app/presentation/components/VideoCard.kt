package com.bunny.app.presentation.components

import android.content.Intent
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.bunny.app.R
import com.bunny.core.model.DataCost
import com.bunny.core.model.VideoItem
import kotlin.math.roundToInt

/**
 * One feed item, laid out like the official app's feed card: a
 * full-bleed 16:9 thumbnail, then a rounded panel holding the title
 * (full width, up to two lines) and a row with the channel avatar,
 * channel + meta lines and the ⋮ menu. Bunny's signatures stay: the
 * Timbangan ≈MB badge on the thumbnail and the Chip Alasan pill.
 * The engine supplies no channel avatars, so the avatar is an honest
 * letter tile.
 *
 * Inline preview: the thumbnail stays on top of the video surface until
 * the first frame is rendered ([previewReady]) and then fades out, so
 * the card never flashes black while the preview loads or when it fails.
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
    /** True once the preview has rendered its first frame. */
    previewReady: Boolean = false,
    /** Display aspect ratio (w/h) of the preview video; null until known. */
    previewAspect: Float? = null,
    /**
     * Preview playback fraction 0..1 for the progress hairline. A lambda
     * on purpose: it is read at draw time, so progress ticks redraw only
     * the hairline instead of recomposing the whole card.
     */
    previewProgress: (() -> Float?)? = null,
    /** Hide the Chip Alasan pill when the caller already shows it (active topic filter). */
    showReason: Boolean = true,
) {
    val surfaceActive = isPreviewing && previewPlayer != null
    val previewPlaying = surfaceActive && previewReady
    val animatedThumbAlpha by animateFloatAsState(
        targetValue = if (previewPlaying) 0f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "thumbAlpha",
    )
    // Outside an active preview the thumbnail is always fully opaque.
    val thumbAlpha = if (surfaceActive) animatedThumbAlpha else 1f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clipToBounds(),
        ) {
            if (isPreviewing && previewPlayer != null) {
                PreviewSurface(
                    player = previewPlayer,
                    videoAspect = previewAspect,
                    modifier = Modifier.matchParentSize(),
                )
            }
            // The thumbnail is drawn ABOVE the surface and fades out once
            // the first frame is on screen — no black flash.
            if (item.thumbnailUrl != null && thumbAlpha > 0f) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(thumbAlpha),
                )
            }
            if (previewPlaying) {
                // The preview is always muted: show that, like the
                // official app's inline playback.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.VolumeOff,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // durationSeconds lives in :core, so no smart cast across modules:
            // bind it with let instead.
            val badge = when {
                previewPlaying -> null
                item.isLive -> "LIVE"
                else -> item.durationSeconds?.let { formatDuration(it) }
            }
            if (badge != null) {
                ThumbBadge(badge, Modifier.align(Alignment.BottomEnd))
            }
            // Timbangan: the estimated data cost of watching, visible
            // BEFORE the tap. Ladder-based estimate (see DataCost); the
            // player replaces it with real format bitrates.
            val sizeBadge = if (item.isLive || previewPlaying) {
                null
            } else {
                item.durationSeconds?.let {
                    "≈ " + DataCost.formatSize(DataCost.estimateBytes(it))
                }
            }
            if (sizeBadge != null) {
                ThumbBadge(sizeBadge, Modifier.align(Alignment.BottomStart))
            }
            // Preview progress hairline: a thin track with an accent fill.
            if (previewPlaying && previewProgress != null) {
                PreviewProgressBar(
                    progress = previewProgress,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Text(
                text = item.title,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
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
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (item.channelName.isNotEmpty()) {
                        Text(
                            text = item.channelName,
                            fontSize = 14.sp,
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
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                CardMenu(videoId = item.id)
            }
            // Chip Alasan: why this video is on the list, stated plainly.
            if (showReason) {
                item.reason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .padding(top = 10.dp, start = 52.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * The preview video surface, center-cropped to fill its box.
 *
 * A TextureView, not a SurfaceView: surfaces punch holes that break
 * scrolling lists. The player is shared — exactly one card attaches it
 * at a time.
 *
 * The player is attached ONCE, in `factory`. Attaching it from `update`
 * re-ran on every recomposition and re-set the player's output surface
 * each time, which made the picture stutter and re-fire "first frame".
 */
@UnstableApi
@Composable
private fun PreviewSurface(
    player: ExoPlayer,
    videoAspect: Float?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clipToBounds()) {
        AndroidView(
            factory = { context ->
                TextureView(context).also { view -> player.setVideoTextureView(view) }
            },
            onRelease = { view -> player.clearVideoTextureView(view) },
            modifier = Modifier.layout { measurable, constraints ->
                val boxW = constraints.maxWidth
                val boxH = constraints.maxHeight
                if (boxW <= 0 || boxH <= 0) {
                    return@layout layout(0, 0) {}
                }
                val boxAspect = boxW.toFloat() / boxH
                val aspect = videoAspect?.takeIf { it > 0f } ?: boxAspect
                // Size the view to the VIDEO's aspect (a TextureView stretches
                // the picture to its own bounds), large enough to cover the
                // box, and center it: center-crop, never distortion.
                val (viewW, viewH) = if (aspect >= boxAspect) {
                    (boxH * aspect).roundToInt() to boxH
                } else {
                    boxW to (boxW / aspect).roundToInt()
                }
                val placeable = measurable.measure(Constraints.fixed(viewW, viewH))
                layout(boxW, boxH) {
                    placeable.place((boxW - viewW) / 2, (boxH - viewH) / 2)
                }
            },
        )
    }
}

/** Thin progress hairline; [progress] is read at draw time only. */
@Composable
private fun PreviewProgressBar(progress: () -> Float?, modifier: Modifier = Modifier) {
    val fillColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.25f))
                val fraction = (progress() ?: 0f).coerceIn(0f, 1f)
                drawRect(
                    color = fillColor,
                    size = Size(size.width * fraction, size.height),
                )
            },
    )
}

/** Duration / size badge on the thumbnail: black pill, white text. */
@Composable
private fun ThumbBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
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
                modifier = Modifier.size(22.dp),
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

// Avatar tile colors: a stable per-channel pick from warm tones taken
// from the app icon's browns, so the feed reads as many channels
// instead of one repeated brand block. The letter stays near-white
// for contrast on every tone.
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
