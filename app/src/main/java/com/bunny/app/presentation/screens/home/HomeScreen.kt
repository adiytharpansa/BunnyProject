package com.bunny.app.presentation.screens.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bunny.app.R
import com.bunny.app.presentation.components.VideoCard
import com.bunny.core.model.DataCost

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVideoClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    /** Reports how hidden the header is (0 = shown, 1 = hidden) so the host can hide the bottom bar in sync. */
    onBarsFraction: (Float) -> Unit = {},
    /** Height of the host's bottom bar, so the last card is not covered by it. */
    bottomPadding: Dp = 0.dp,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val jatah by viewModel.jatah.collectAsState()
    var showQuotaDialog by remember { mutableStateOf(false) }
    // Topic chips: they filter the mixture already on screen — Bunny's
    // Chip Alasan reasons ARE the topics, so no extra fetch is needed.
    var topicFilter by remember { mutableStateOf<String?>(null) }
    val topics = state.items.mapNotNull { it.reason }.distinct()
    val visibleItems = if (topicFilter == null) {
        state.items
    } else {
        state.items.filter { it.reason == topicFilter }
    }

    // Inline previews (the official app's feed playback): the card
    // occupying most of the viewport starts a muted preview after a
    // short dwell; scrolling away or leaving Home stops it.
    val listState = rememberLazyListState()
    val previewingId by viewModel.previewingId.collectAsState()
    val previewCandidate by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            var bestId: String? = null
            var bestFraction = 0.6f
            for (itemInfo in info.visibleItemsInfo) {
                val id = itemInfo.key as? String ?: continue
                val start = maxOf(itemInfo.offset, info.viewportStartOffset)
                val end = minOf(
                    itemInfo.offset + itemInfo.size,
                    info.viewportEndOffset,
                )
                val fraction = if (itemInfo.size > 0) {
                    (end - start).toFloat() / itemInfo.size
                } else {
                    0f
                }
                if (fraction >= bestFraction) {
                    bestFraction = fraction
                    bestId = id
                }
            }
            bestId
        }
    }
    LaunchedEffect(previewCandidate) {
        val candidate = previewCandidate
        if (candidate == null) {
            viewModel.stopPreview()
        } else {
            delay(700)
            viewModel.startPreview(candidate)
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
    }
    // Preview progress for the hairline on the previewing card.
    var previewProgress by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(previewingId) {
        if (previewingId == null) {
            previewProgress = null
            return@LaunchedEffect
        }
        while (true) {
            val player = viewModel.previewPlayer
            val duration = player.duration
            previewProgress = if (duration > 0) {
                (player.currentPosition.toFloat() / duration)
                    .coerceIn(0f, 1f)
            } else {
                null
            }
            delay(250)
        }
    }

    if (showQuotaDialog) {
        JatahQuotaDialog(
            currentBytes = jatah.quotaBytes,
            onSelect = { bytes ->
                viewModel.setQuota(bytes)
                showQuotaDialog = false
            },
            onDismiss = { showQuotaDialog = false },
        )
    }

    // Collapsing header (app bar + topic chips): hides while the feed
    // scrolls down (finger moves up) and slides back in as soon as the
    // user scrolls up, like the official home tab. The offset follows
    // the finger 1:1 and settles (snaps) when the gesture ends.
    val density = LocalDensity.current
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    var headerOffsetPx by remember { mutableFloatStateOf(0f) } // -height..0
    val headerPadDp = with(density) { headerHeightPx.toDp() }
    val headerConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (headerHeightPx > 0f) {
                    headerOffsetPx = (headerOffsetPx + available.y)
                        .coerceIn(-headerHeightPx, 0f)
                }
                // Never consume: the list (and pull-to-refresh) scroll as usual.
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                val target = if (atTop || headerOffsetPx > -headerHeightPx / 2f) {
                    0f
                } else {
                    -headerHeightPx
                }
                if (headerOffsetPx != target) {
                    animate(
                        initialValue = headerOffsetPx,
                        targetValue = target,
                        animationSpec = tween(180),
                    ) { value, _ -> headerOffsetPx = value }
                }
                return Velocity.Zero
            }
        }
    }
    // Mirror the header's collapse to the host so the bottom bar hides and
    // shows in lockstep; reset when Home leaves the screen.
    LaunchedEffect(Unit) {
        snapshotFlow {
            if (headerHeightPx > 0f) {
                (-headerOffsetPx / headerHeightPx).coerceIn(0f, 1f)
            } else {
                0f
            }
        }.collect { onBarsFraction(it) }
    }
    DisposableEffect(Unit) {
        onDispose { onBarsFraction(0f) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(headerConnection),
    ) {
        when {
            // First load: skeleton cards in the feed's own
            // geometry — no bare spinner on an empty screen.
            state.isLoading && state.items.isEmpty() ->
                SkeletonFeed(topPadding = headerPadDp)

            state.error != null && state.items.isEmpty() -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.backendStatus.isNotEmpty()) {
                    Text(
                        text = state.backendStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = state.error ?: stringResource(R.string.error_generic),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { viewModel.load() }) {
                    Text(stringResource(R.string.retry))
                }
            }

            else -> PullToRefreshBox(
                isRefreshing = state.isLoading,
                onRefresh = { viewModel.load() },
                topInsetPx = headerHeightPx,
            ) {
                LazyColumn(
                    state = listState,
                    // Top padding = header height, so the first item
                    // starts right under the header and the list can
                    // scroll up beneath it when the header hides.
                    contentPadding = PaddingValues(
                        top = headerPadDp + 8.dp,
                        bottom = 12.dp + bottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        JatahBar(
                            jatah = jatah,
                            onSetQuota = { showQuotaDialog = true },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    items(visibleItems, key = { it.id }) { item ->
                        VideoCard(
                            item = item,
                            onClick = { onVideoClick(item.id) },
                            previewPlayer = if (previewingId == item.id) {
                                viewModel.previewPlayer
                            } else {
                                null
                            },
                            isPreviewing = previewingId == item.id,
                            previewProgress = if (previewingId == item.id) {
                                previewProgress
                            } else {
                                null
                            },
                            // The active topic's pill would repeat
                            // the filter the user already picked.
                            showReason = item.reason != topicFilter,
                        )
                    }
                }
            }
        }

        // The header floats above the list and is moved/faded by the
        // scroll offset above.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { headerHeightPx = it.height.toFloat() }
                .graphicsLayer {
                    translationY = headerOffsetPx
                    alpha = if (headerHeightPx > 0f) {
                        (1f + headerOffsetPx / headerHeightPx).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                }
                .background(MaterialTheme.colorScheme.background),
        ) {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    // Wordmark: red play badge + tight bold "Bunny".
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.home_title),
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1).sp,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search_open),
                        )
                    }
                },
            )
            if (topics.size > 1) {
                HomeTabs(
                    topics = topics,
                    selected = topicFilter,
                    onSelect = { topicFilter = it },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

/**
 * Pull-to-refresh, hand-rolled on the nested-scroll protocol:
 * material3 1.2 (this project's BOM) ships no pull-to-refresh, and a
 * BOM bump just for this was not worth the churn. Downward overscroll
 * the list cannot consume accumulates as pull distance; releasing
 * past the threshold refreshes. The indicator is a small circular
 * badge that follows the pull.
 */
@Composable
private fun PullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    topInsetPx: Float = 0f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val badgePx = with(density) { 36.dp.toPx() }
    var pullPx by remember { mutableFloatStateOf(0f) }
    val connection = remember(isRefreshing) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Dragging back up while pulled: give the distance
                // back before the list itself starts scrolling.
                if (source == NestedScrollSource.Drag && available.y < 0f &&
                    pullPx > 0f && !isRefreshing
                ) {
                    val returned = minOf(pullPx, -available.y)
                    pullPx -= returned
                    return Offset(0f, -returned)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0f &&
                    !isRefreshing
                ) {
                    pullPx = (pullPx + available.y).coerceAtMost(thresholdPx * 1.5f)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullPx >= thresholdPx && !isRefreshing) onRefresh()
                pullPx = 0f
                return Velocity.Zero
            }
        }
    }
    Box(modifier = Modifier.nestedScroll(connection)) {
        content()
        if (isRefreshing || pullPx > 0f) {
            val fraction = (pullPx / thresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        val travel = if (isRefreshing) {
                            badgePx * 0.75f
                        } else {
                            pullPx - badgePx
                        }
                        IntOffset(0, (travel + topInsetPx).toInt())
                    }
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                    )
                }
            }
        }
    }
}

/** First-load placeholder: three ghost cards in the feed's geometry. */
@Composable
private fun SkeletonFeed(topPadding: Dp = 0.dp) {
    LazyColumn(
        contentPadding = PaddingValues(top = topPadding + 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(3) { SkeletonCard() }
    }
}

@Composable
private fun SkeletonCard() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    val block = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(block),
        )
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
                    .background(block),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(block),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(block),
                )
            }
        }
    }
}

/**
 * Jatah bar: this month's estimated usage, and the budget when one is
 * set. One structured row — title + usage stacked on the left, the
 * Atur action centered on the right, the budget track underneath.
 * Estimates, not metering — the copy says so.
 */
@Composable
private fun JatahBar(
    jatah: JatahState,
    onSetQuota: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_jatah_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val usageText = if (jatah.usedBytes <= 0L) {
                    stringResource(R.string.home_jatah_empty)
                } else {
                    val used = stringResource(
                        R.string.home_jatah_used,
                        DataCost.formatSize(jatah.usedBytes),
                    )
                    if (jatah.quotaBytes != null) {
                        used + " " + stringResource(
                            R.string.home_jatah_of,
                            DataCost.formatSize(jatah.quotaBytes),
                        )
                    } else {
                        used
                    }
                }
                Text(
                    text = usageText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSetQuota) {
                Text(stringResource(R.string.home_jatah_set))
            }
        }
        if (jatah.quotaBytes != null && jatah.quotaBytes > 0) {
            val fraction = (jatah.usedBytes.toFloat() / jatah.quotaBytes.toFloat())
                .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 10.dp, bottom = 4.dp),
            )
        }
    }
}

/** Budget choices for Jatah — presets keep it a one-tap decision. */
@Composable
private fun JatahQuotaDialog(
    currentBytes: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options: List<Pair<String, Long?>> = listOf(
        stringResource(R.string.home_jatah_unlimited) to null,
        "1 GB" to 1_000_000_000L,
        "2 GB" to 2_000_000_000L,
        "5 GB" to 5_000_000_000L,
        "10 GB" to 10_000_000_000L,
        "25 GB" to 25_000_000_000L,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_jatah_dialog_title)) },
        text = {
            Column {
                options.forEach { (label, bytes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentBytes == bytes,
                                onClick = { onSelect(bytes) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentBytes == bytes,
                            onClick = { onSelect(bytes) },
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

/**
 * Text tabs like the official home row (Beranda / Musik / Live …):
 * medium-weight labels, the selected one bold with a rounded accent
 * underline. The topics are Bunny's Chip Alasan reasons, so selecting
 * one filters the feed already on screen.
 */
@Composable
private fun HomeTabs(
    topics: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all = listOf<String?>(null) + topics
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(all) { topic ->
            val isSelected = topic == selected
            val label = topic?.removePrefix("Topik: ")
                ?: stringResource(R.string.nav_home)
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(topic) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    fontSize = 17.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                        .copy(alpha = if (isSelected) 1f else 0.72f),
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        ),
                )
            }
        }
    }
}
