package com.bunny.app.presentation.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.bunny.app.ui.motion.tappable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bunny.app.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bunny.app.presentation.components.CardMenu
import com.bunny.app.presentation.components.ResilientImage
import com.bunny.core.model.DataCost
import com.bunny.core.model.VideoItem
import kotlin.math.roundToInt

private val LiveRed = Color(0xFFD32F2F)

/**
 * Satu hasil pencarian ala YouTube: thumbnail penuh lebar tanpa kartu
 * berbingkai, lalu avatar kanal + judul + metadata satu blok, menu ⋮
 * di kanan. Lencana durasi/LIVE dan perkiraan data (Timbangan) tetap.
 */
@Composable
fun SearchResultItem(
    item: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tappable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clipToBounds(),
        ) {
            item.thumbnailUrl?.let { url ->
                ResilientImage(
                    url = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    requestBuilder = {
                        size(960, 540)
                        crossfade(false)
                    },
                )
            }
            if (item.isLive) {
                ThumbBadge("LIVE", Modifier.align(Alignment.BottomEnd), live = true)
            } else {
                item.durationSeconds?.let { seconds ->
                    ThumbBadge(formatDuration(seconds), Modifier.align(Alignment.BottomEnd))
                    ThumbBadge(
                        "≈ " + DataCost.formatSize(DataCost.estimateBytes(seconds)),
                        Modifier.align(Alignment.BottomStart),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                item.channelAvatarUrl?.let { url ->
                    ResilientImage(
                        url = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.title,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    item.channelName.takeIf { it.isNotEmpty() },
                    item.viewCountText?.takeIf { it.isNotEmpty() },
                    item.uploadedText?.takeIf { it.isNotEmpty() },
                ).joinToString(" • ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CardMenu(videoId = item.id)
        }
    }
}

@Composable
private fun ThumbBadge(text: String, modifier: Modifier, live: Boolean = false) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (live) LiveRed else Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

