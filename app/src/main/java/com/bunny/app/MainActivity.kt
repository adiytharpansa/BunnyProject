package com.bunny.app

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bunny.app.playback.CrashRecorder
import com.bunny.core.model.VideoItem
import com.bunny.app.playback.PlaybackDiagnostics
import com.bunny.app.playback.PlaybackManager
import com.bunny.app.presentation.screens.channel.ChannelScreen
import com.bunny.app.presentation.screens.home.HomeScreen
import com.bunny.app.presentation.screens.kanal.KanalScreen
import com.bunny.app.presentation.screens.live.LiveScreen
import com.bunny.app.presentation.screens.notifications.NotificationsScreen
import com.bunny.app.presentation.screens.player.PlayerScreen
import com.bunny.app.presentation.screens.profile.ProfileScreen
import com.bunny.app.presentation.screens.search.SearchScreen
import com.bunny.app.presentation.theme.BunnyTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.media3.common.util.UnstableApi
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playbackManager: PlaybackManager

    /** True while a video is open — gates automatic PiP on leaving. */
    private var pipEligible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BunnyTheme {
                // Navigation: four tabs (Beranda, Live, Kanal, Profil) under
                // one optional open video. Still a plain state machine — the
                // screens own their scaffolds, this one only owns the tab
                // bar and the player overlay.
                var openVideoId by remember { mutableStateOf<String?>(null) }
                var openSearch by remember { mutableStateOf(false) }
                var openNotifications by remember { mutableStateOf(false) }
                var openChannel by remember {
                    mutableStateOf<Pair<String, String>?>(null)
                }
                var selectedTab by remember { mutableStateOf(0) }
                // Isi feed Beranda yang dilaporkan HomeScreen; Live dan Kanal
                // memakainya untuk daftar tab dan (di Kanal) untuk isi video.
                var feedItems by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
                val topics = remember(feedItems) {
                    feedItems.mapNotNull { it.reason }.distinct()
                }
                var crashReport by remember {
                    mutableStateOf(CrashRecorder.read(this@MainActivity))
                }
                // 0 = bottom bar fully shown, 1 = fully hidden. Written by
                // HomeScreen while its header collapses; read only inside
                // graphicsLayer, so it redraws without recomposing.
                val barsFraction = remember { mutableFloatStateOf(0f) }
                var barHeightPx by remember { mutableFloatStateOf(0f) }
                val barHeightDp = with(LocalDensity.current) { barHeightPx.toDp() }

                val videoId = openVideoId
                SideEffect { pipEligible = videoId != null }
                val barVisible =
                    videoId == null && !openSearch && !openNotifications && openChannel == null
                // System Back walks the page stack back to Beranda
                // instead of leaving the app; the player's own
                // BackHandler owns Back while a video is open.
                val canGoBack = videoId == null &&
                    (openChannel != null || openSearch || openNotifications || selectedTab != 0)
                BackHandler(enabled = canGoBack) {
                    when {
                        openChannel != null -> openChannel = null
                        openSearch -> openSearch = false
                        openNotifications -> openNotifications = false
                        else -> selectedTab = 0
                    }
                }
                Scaffold { scaffoldPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        // While the bottom bar is on screen it overlays the
                        // content (so it can slide away), so the content only
                        // takes the top inset; each tab pads for the bar.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    if (barVisible) {
                                        PaddingValues(
                                            top = scaffoldPadding.calculateTopPadding(),
                                        )
                                    } else {
                                        scaffoldPadding
                                    },
                                ),
                        ) {
                            if (videoId != null) {
                                PlayerScreen(
                                    videoId = videoId,
                                    onBack = { openVideoId = null },
                                    onChannelClick = { id, name ->
                                        openChannel = id to name
                                    },
                                    onPipClick = { enterPip() },
                                    onVideoClick = { openVideoId = it },
                                )
                            } else if (openChannel != null) {
                                val (channelId, channelName) = openChannel!!
                                ChannelScreen(
                                    channelId = channelId,
                                    channelName = channelName,
                                    onBack = { openChannel = null },
                                    onVideoClick = { openVideoId = it },
                                )
                            } else if (openSearch) {
                                SearchScreen(
                                    onBack = { openSearch = false },
                                    onVideoClick = { openVideoId = it },
                                )
                            } else if (openNotifications) {
                                NotificationsScreen(
                                    onBack = { openNotifications = false },
                                    onVideoClick = { openVideoId = it },
                                )
                            } else if (selectedTab == 0) {
                                HomeScreen(
                                    onVideoClick = { openVideoId = it },
                                    onSearchClick = { openSearch = true },
                                    onNotificationsClick = { openNotifications = true },
                                    onFeedChanged = { feedItems = it },
                                    onBarsFraction = {
                                        barsFraction.floatValue = it
                                    },
                                    bottomPadding = barHeightDp,
                                )
                                val report = crashReport
                                if (report != null) {
                                    CrashReportBlock(
                                        report = report,
                                        onDismiss = {
                                            CrashRecorder.clear(this@MainActivity)
                                            crashReport = null
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .padding(horizontal = 8.dp, vertical = 56.dp),
                                    )
                                }
                            } else if (selectedTab == 1) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = barHeightDp),
                                ) {
                                    LiveScreen(
                                        onVideoClick = { openVideoId = it },
                                        onSearchClick = { openSearch = true },
                                        onNotificationsClick = { openNotifications = true },
                                        topics = topics,
                                    )
                                }
                            } else if (selectedTab == 2) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = barHeightDp),
                                ) {
                                    KanalScreen(
                                        onChannelClick = { id, name ->
                                            openChannel = id to name
                                        },
                                        onVideoClick = { openVideoId = it },
                                        onSearchClick = { openSearch = true },
                                        onNotificationsClick = { openNotifications = true },
                                        topics = topics,
                                        feedItems = feedItems,
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = barHeightDp),
                                ) {
                                    ProfileScreen()
                                }
                            }
                        }

                        if (barVisible) {
                            // Slides down and fades with Home's header.
                            BunnyBottomBar(
                                selectedTab = selectedTab,
                                onSelect = { selectedTab = it },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .onSizeChanged { barHeightPx = it.height.toFloat() }
                                    .graphicsLayer {
                                        val f = barsFraction.floatValue
                                        translationY = f * size.height
                                        alpha = 1f - f
                                    },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Picture-in-picture with the playing video's aspect ratio (PiP
     * only accepts 1:2.39 … 2.39:1, so wide/short extremes clamp).
     * Reached from the player's PiP chip and automatically when the
     * user leaves the app mid-video (onUserLeaveHint).
     */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val size = playbackManager.videoWidthHeight()
        val aspect = if (size != null) {
            val (w, h) = size
            val percent = w * 100 / h
            when {
                percent > 239 -> Rational(239, 100)
                percent < 41 -> Rational(41, 100)
                else -> Rational(w, h)
            }
        } else {
            Rational(16, 9)
        }
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(aspect).build(),
            )
        } catch (e: IllegalStateException) {
            PlaybackDiagnostics.log("PiP gagal: ${e.message}")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible) enterPip()
    }

    override fun onDestroy() {
        // Tripwire paired with PlayerViewModel.onCleared: if the activity
        // dies mid-load, the diagnostics ring shows whether it finished,
        // was re-created for a config change, or was destroyed outright.
        PlaybackDiagnostics.log(
            "MainActivity hancur (finishing=$isFinishing, gantiKonfigurasi=$isChangingConfigurations)",
        )
        super.onDestroy()
    }
}

@Composable
private fun CrashReportBlock(
    report: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.crash_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(R.string.crash_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Box(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_dismiss))
            }
        }
    }
}

/**
 * Bottom bar: icon-only, a soft tonal pill behind the selected icon,
 * hairline on top. Built by hand because the Material NavigationBar has
 * a fixed 80dp height. Tabs: Beranda · Live · Kanal · Profil.
 */
@Composable
private fun BunnyBottomBar(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(58.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomBarItem(
                selected = selectedTab == 0,
                onClick = { onSelect(0) },
                filled = Icons.Filled.Home,
                outlined = Icons.Outlined.Home,
                description = stringResource(R.string.nav_home),
                modifier = Modifier.weight(1f),
            )
            BottomBarItem(
                selected = selectedTab == 1,
                onClick = { onSelect(1) },
                filled = Icons.Filled.Sensors,
                outlined = Icons.Outlined.Sensors,
                description = stringResource(R.string.nav_live),
                modifier = Modifier.weight(1f),
            )
            BottomBarItem(
                selected = selectedTab == 2,
                onClick = { onSelect(2) },
                filled = Icons.Filled.Subscriptions,
                outlined = Icons.Outlined.Subscriptions,
                description = stringResource(R.string.nav_kanal),
                modifier = Modifier.weight(1f),
            )
            BottomBarItem(
                selected = selectedTab == 3,
                onClick = { onSelect(3) },
                filled = Icons.Filled.AccountCircle,
                outlined = Icons.Outlined.AccountCircle,
                description = stringResource(R.string.nav_profile),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BottomBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    filled: ImageVector,
    outlined: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val tint by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "navTint",
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200),
        label = "navPillAlpha",
    )
    val pillScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.4f,
        animationSpec = tween(200),
        label = "navPillScale",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(34.dp)
                .graphicsLayer {
                    alpha = pillAlpha
                    scaleX = pillScale
                }
                .clip(RoundedCornerShape(17.dp))
                .background(accent.copy(alpha = 0.20f)),
        )
        Icon(
            imageVector = if (selected) filled else outlined,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
    }
}
