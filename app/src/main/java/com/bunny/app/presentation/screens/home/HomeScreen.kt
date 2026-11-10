package com.bunny.app.presentation.screens.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
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
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val jatah by viewModel.jatah.collectAsState()
    var showQuotaDialog by remember { mutableStateOf(false) }
    // Topic chips, like the official app's home tab bar (REA dissection
    // 2026-11-10): they filter the mixture already on screen — Bunny's
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

    // The official app's chip treatment: the selected chip is a solid
    // light pill with dark text; unselected chips stay outlined.
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.onBackground,
        selectedLabelColor = MaterialTheme.colorScheme.background,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.home_title),
                            fontWeight = FontWeight.Bold,
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
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                // First load: skeleton cards in the feed's own
                // geometry — no bare spinner on an empty screen.
                state.isLoading && state.items.isEmpty() -> SkeletonFeed()

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
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item {
                            JatahBar(
                                jatah = jatah,
                                onSetQuota = { showQuotaDialog = true },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                        if (topics.size > 1) {
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    item {
                                        FilterChip(
                                            selected = topicFilter == null,
                                            onClick = { topicFilter = null },
                                            label = {
                                                Text(stringResource(R.string.topic_all))
                                            },
                                            colors = chipColors,
                                        )
                                    }
                                    items(topics) { topic ->
                                        FilterChip(
                                            selected = topicFilter == topic,
                                            onClick = { topicFilter = topic },
                                            label = {
                                                Text(topic.removePrefix("Topik: "))
                                            },
                                            colors = chipColors,
                                        )
                                    }
                                }
                            }
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
                        IntOffset(0, travel.toInt())
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
private fun SkeletonFeed() {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
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
        modifier = modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
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
