package com.bunny.app.presentation.screens.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bunny.app.R
import com.bunny.app.presentation.components.BunnyHeader
import com.bunny.app.presentation.components.VideoCard
import com.bunny.core.model.DataCost
import com.bunny.core.model.VideoItem

/** How long a card must stay the best candidate before its preview starts. */
private const val PREVIEW_DWELL_MS = 700L

/** A card must be at least this visible (0..1) to be a preview candidate. */
private const val PREVIEW_MIN_VISIBLE_FRACTION = 0.6f

/** Poll interval for the preview progress hairline. */
private const val PREVIEW_PROGRESS_TICK_MS = 250L

/** Horizontal margin shared by the Jatah bar, the cards and the skeleton. */
private val FeedHorizontalPadding = 12.dp

/** Vertical gap between feed items. */
private val FeedItemSpacing = 16.dp

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVideoClick: (String) -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    /** Melaporkan isi feed ke host, agar Live/Kanal bisa memakai daftar topik dan video yang sama. */
    onFeedChanged: (List<VideoItem>) -> Unit = {},
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

    LaunchedEffect(state.items) { onFeedChanged(state.items) }

    // Inline previews (the official app's feed playback): the topmost card
    // showing most of its area starts a muted preview after a short dwell;
    // scrolling away, leaving Home or backgrounding the app stops it.
    val listState = rememberLazyListState()
    val previewingId by viewModel.previewingId.collectAsState()
    val previewStatus by viewModel.previewStatus.collectAsState()
    val previewCandidate by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            var bestId: String? = null
            var bestFraction = 0f
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
                // Strictly greater: on a tie the TOPMOST card wins
                // (visibleItemsInfo is ordered top to bottom).
                if (fraction >= PREVIEW_MIN_VISIBLE_FRACTION && fraction > bestFraction) {
                    bestFraction = fraction
                    bestId = id
                }
            }
            bestId
        }
    }

    // Backgrounding / screen-off must stop the preview (the player would
    // otherwise keep downloading); coming back restarts the dwell.
    var foregroundCount by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { foregroundCount++ }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }

    LaunchedEffect(previewCandidate, foregroundCount) {
        // Stop the previous card's preview IMMEDIATELY when the candidate
        // changes; otherwise it keeps playing (and then swaps to the new
        // video while still attached to the old card) during the dwell.
        viewModel.stopPreview()
        val candidate = previewCandidate ?: return@LaunchedEffect
        delay(PREVIEW_DWELL_MS)
        viewModel.startPreview(candidate)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopPreview() }
    }

    // Preview progress for the hairline on the previewing card. Exposed as
    // a lambda so only the hairline redraws on each tick.
    var previewProgress by remember { mutableStateOf<Float?>(null) }
    val previewProgressProvider = remember { { previewProgress } }
    LaunchedEffect(previewingId, previewStatus.firstFrameRendered) {
        previewProgress = null
        if (previewingId == null || !previewStatus.firstFrameRendered) {
            return@LaunchedEffect
        }
        val player = viewModel.previewPlayer
        while (true) {
            val duration = player.duration
            previewProgress = if (duration > 0) {
                (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                null
            }
            delay(PREVIEW_PROGRESS_TICK_MS)
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
    // A refresh must never start under a half-collapsed header: bring the
    // header (and, through onBarsFraction, the bottom bar) back first.
    LaunchedEffect(state.isLoading) {
        if (state.isLoading && headerOffsetPx != 0f) {
            animate(
                initialValue = headerOffsetPx,
                targetValue = 0f,
                animationSpec = tween(180),
            ) { value, _ -> headerOffsetPx = value }
        }
    }
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
                // Bottom edge of the header as it is RIGHT NOW (full
                // height minus however much it has collapsed).
                headerBottomPx = { headerHeightPx + headerOffsetPx },
            ) {
                LazyColumn(
                    state = listState,
                    // Top padding = header height, so the first item
                    // starts right under the header and the list can
                    // scroll up beneath it when the header hides.
                    contentPadding = PaddingValues(
                        top = headerPadDp + 4.dp,
                        bottom = 16.dp + bottomPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(FeedItemSpacing),
                ) {
                    item {
                        JatahBar(
                            jatah = jatah,
                            onSetQuota = { showQuotaDialog = true },
                            modifier = Modifier.padding(horizontal = FeedHorizontalPadding),
                        )
                    }
                    items(visibleItems, key = { it.id }) { item ->
                        val isPreviewingThis = previewingId == item.id
                        VideoCard(
                            item = item,
                            modifier = Modifier.padding(horizontal = FeedHorizontalPadding),
                            onClick = { onVideoClick(item.id) },
                            previewPlayer = if (isPreviewingThis) {
                                viewModel.previewPlayer
                            } else {
                                null
                            },
                            isPreviewing = isPreviewingThis,
                            previewReady = isPreviewingThis && previewStatus.firstFrameRendered,
                            previewAspect = if (isPreviewingThis) {
                                previewStatus.videoAspect
                            } else {
                                null
                            },
                            previewProgress = if (isPreviewingThis) {
                                previewProgressProvider
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
            BunnyHeader(
                topics = topics,
                selectedTopic = topicFilter,
                onTopicSelect = { topicFilter = it },
                onNotificationsClick = onNotificationsClick,
                onSearchClick = onSearchClick,
            )
        }
    }
}

/**
 * Pull-to-refresh, hand-rolled on the nested-scroll protocol:
 * material3 1.2 (this project's BOM) ships no pull-to-refresh, and a
 * BOM bump just for this was not worth the churn. Downward overscroll
 * the list cannot consume accumulates as pull distance; releasing
 * past the threshold refreshes. The indicator is a small circular
 * badge that emerges from behind the header's bottom edge, follows the
 * pull, and rests [INDICATOR_REST_GAP] below the header — like the
 * official app.
 *
 * [headerBottomPx] is read at layout time (a lambda) so the badge
 * tracks the collapsing header without recomposing.
 */
@Composable
private fun PullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    headerBottomPx: () -> Float = { 0f },
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val badgePx = with(density) { 36.dp.toPx() }
    val restGapPx = with(density) { INDICATOR_REST_GAP.toPx() }
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
                        // Offset from the header's bottom edge:
                        //   -badgePx          hidden right behind the header
                        //   restGapPx         resting spot while refreshing
                        // While pulling the badge travels between the two
                        // (a little overshoot past the threshold is fine).
                        val fromHeader = if (isRefreshing) {
                            restGapPx
                        } else {
                            val f = pullPx / thresholdPx
                            -badgePx + f * (badgePx + restGapPx)
                        }
                        IntOffset(0, (headerBottomPx() + fromHeader).toInt())
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

/** Gap between the header's bottom edge and the resting refresh badge. */
private val INDICATOR_REST_GAP = 12.dp

/** First-load placeholder: three ghost cards in the feed's geometry. */
@Composable
private fun SkeletonFeed(topPadding: Dp = 0.dp) {
    LazyColumn(
        contentPadding = PaddingValues(top = topPadding + 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(FeedItemSpacing),
    ) {
        items(3) { SkeletonCard() }
    }
}

@Composable
internal fun SkeletonCard() {
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
    Column(
        modifier = Modifier
            .padding(horizontal = FeedHorizontalPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(block),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
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
 * set. A card with a round icon, title + usage stacked, a tonal "Atur"
 * action and, when a budget exists, a rounded progress track with its
 * percentage. The accent turns to the error color at 90% or more.
 * Estimates, not metering — the copy says so.
 */
@Composable
private fun JatahBar(
    jatah: JatahState,
    onSetQuota: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val quota = jatah.quotaBytes
    val fraction = if (quota != null && quota > 0) {
        (jatah.usedBytes.toFloat() / quota.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    val accent = if (fraction != null && fraction >= 0.9f) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DataUsage,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
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
                    if (quota != null) {
                        used + " " + stringResource(
                            R.string.home_jatah_of,
                            DataCost.formatSize(quota),
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
            FilledTonalButton(
                onClick = onSetQuota,
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    stringResource(R.string.home_jatah_set),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (fraction != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(50)),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.16f),
                )
                Text(
                    text = "${(fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
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
