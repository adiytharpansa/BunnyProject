package com.bunny.app.presentation.components

import android.content.Intent
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Brush
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
import coil.compose.SubcomposeAsyncImage
import com.bunny.app.R
import com.bunny.core.model.DataCost
import com.bunny.core.model.VideoItem
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(20.dp)
private val AvatarSize = 40.dp
private val AvatarGap = 12.dp
private val LiveRed = Color(0xFFD32F2F)

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
    val context = LocalContext.current
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
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = CardShape,
            )
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
            val thumbUrl = item.thumbnailUrl
            if (thumbUrl != null && thumbAlpha > 0f) {
                val request = remember(thumbUrl) { thumbnailRequest(context, thumbUrl) }
                AsyncImage(
                    model = request,
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
            // Soft scrim so the badges stay legible on bright thumbnails.
            if (badge != null || sizeBadge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.45f),
                                ),
                            ),
                        ),
                )
            }
            if (badge != null) {
                ThumbBadge(badge, Modifier.align(Alignment.BottomEnd))
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
                .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 14.dp),
        ) {
            Text(
                text = item.title,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(AvatarSize)
                        .clip(CircleShape)
                        .background(avatarColor(item.channelName)),
                    contentAlignment = Alignment.Center,
                ) {
                    // The channel's real photo when the engine brought one;
                    // the initial letter stays as the loading/error fallback.
                    val initial = item.channelName.firstOrNull()?.uppercase() ?: "?"
                    if (item.channelAvatarUrl != null) {
                        SubcomposeAsyncImage(
                            model = item.channelAvatarUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            loading = {
                                Text(
                                    initial,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFFF5F1EA),
                                )
                            },
                            error = {
                                Text(
                                    initial,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFFF5F1EA),
                                )
                            },
                        )
                    } else {
                        Text(
                            initial,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFFF5F1EA),
                        )
                    }
                }
                Spacer(Modifier.width(AvatarGap))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    if (item.channelName.isNotEmpty()) {
                        Text(
                            text = item.channelName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
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
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                CardMenu(videoId = item.id)
            }
            // Chip Alasan: why this video is on the list, stated plainly.
            // Aligned with the channel text, not the avatar.
            if (showReason) {
                item.reason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .padding(top = 12.dp, start = AvatarSize + AvatarGap)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
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

/** Duration / size badge on the thumbnail: dark pill, white text; LIVE is red. */
@Composable
private fun ThumbBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (text == "LIVE") LiveRed else Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
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
