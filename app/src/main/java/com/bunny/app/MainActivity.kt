package com.bunny.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.ContextCompat
import com.bunny.app.pip.PipController
import com.bunny.app.playback.CrashRecorder
import com.bunny.core.model.VideoItem
import com.bunny.app.playback.PlaybackDiagnostics
import com.bunny.app.playback.PlaybackManager
import androidx.hilt.navigation.compose.hiltViewModel
import com.bunny.app.presentation.components.MiniPlayerBar
import com.bunny.app.presentation.screens.channel.ChannelScreen
import com.bunny.app.presentation.screens.home.HomeScreen
import com.bunny.app.presentation.screens.kanal.KanalScreen
import com.bunny.app.presentation.screens.live.LiveScreen
import com.bunny.app.presentation.screens.downloads.DownloadsScreen
import com.bunny.app.presentation.screens.notifications.NotificationsScreen
import com.bunny.app.presentation.screens.player.PlayerScreen
import com.bunny.app.presentation.screens.player.PlayerUiState
import com.bunny.app.presentation.screens.player.PlayerViewModel
import com.bunny.app.presentation.screens.profile.PersonalFeedScreen
import com.bunny.app.presentation.screens.profile.ProfileScreen
import com.bunny.app.presentation.screens.profile.SubscriptionsScreen
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

    @Inject
    lateinit var desktopAuth: com.bunny.app.data.DesktopAuth

    @Inject
    lateinit var notificationsBadge: com.bunny.app.data.NotificationsBadge

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { desktopAuth.handleRedirect(it) }
    }

    /** True while a video is open — gates automatic PiP on leaving. */
    private var pipEligible = false

    /**
     * Paket pip_24: PiP ala YouTube di atas pemutar yang sudah ada —
     * tombol mundur/putar-jeda/maju di jendela PiP, auto-enter
     * Android 12+, rasio mengikuti video. Player-nya milik
     * PlaybackManager; controller hanya meminjamnya.
     */
    private val pipController by lazy {
        PipController(
            activity = this,
            player = { playbackManager.player },
            sourceView = { null },
            lockTo16x9 = false,
            enabled = { pipEligible },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A cold start can BE the OAuth redirect (the browser sheet
        // returns to a fresh process); hand it to the same door.
        intent?.data?.let { desktopAuth.handleRedirect(it) }
        enableEdgeToEdge()
        setContent {
            BunnyTheme {
                // Izin notifikasi diminta SEKALI saat Bunny pertama kali
                // dibuka (Android 13+). Dialognya dibuat oleh sistem. Tanda
                // "sudah diminta" disimpan sebelum dialog tampil, jadi tidak
                // ada permintaan ulang di pembukaan berikutnya. Ini satu-satunya
                // tempat Bunny meminta izin notifikasi.
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    PlaybackDiagnostics.log(
                        "Izin notifikasi (saat buka): " +
                            if (granted) "diberikan" else "ditolak",
                    )
                }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val prefs = getSharedPreferences("bunny_prefs", MODE_PRIVATE)
                        val alreadyAsked = prefs.getBoolean("notif_asked", false)
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!alreadyAsked && !granted) {
                            prefs.edit().putBoolean("notif_asked", true).apply()
                            notificationLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        }
                    }
                }

                // Navigation: four tabs (Beranda, Live, Kanal, Profil) under
                // one optional open video. Still a plain state machine — the
                // screens own their scaffolds, this one only owns the tab
                // bar and the player overlay.
                var openVideoId by remember { mutableStateOf<String?>(null) }
                var openSearch by remember { mutableStateOf(false) }
                var openNotifications by remember { mutableStateOf(false) }
                var openDownloads by remember { mutableStateOf(false) }
                var openPersonal by remember { mutableStateOf<String?>(null) }
                // Pemutar mini (Fase 6): sesi video tetap hidup, halaman
                // penuhnya dilipat jadi bilah di atas bar bawah.
                var playerMinimized by remember { mutableStateOf(false) }
                val playerViewModel: PlayerViewModel = hiltViewModel()
                val playerUiState by playerViewModel.uiState.collectAsState()
                val playerController by playerViewModel.controllerPlayer.collectAsState()
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
                // Fase 7-C: the bell's unread dot — one local check
                // when the app starts; opening the page clears it.
                val notifUnread by notificationsBadge.hasUnread.collectAsState()
                LaunchedEffect(Unit) { notificationsBadge.refresh() }
                // 0 = bottom bar fully shown, 1 = fully hidden. Written by
                // HomeScreen while its header collapses; read only inside
                // graphicsLayer, so it redraws without recomposing.
                val barsFraction = remember { mutableFloatStateOf(0f) }
                var barHeightPx by remember { mutableFloatStateOf(0f) }
                val barHeightDp = with(LocalDensity.current) { barHeightPx.toDp() }

                val videoId = openVideoId
                // PiP hanya saat halaman tonton yang tampil — dalam
                // keadaan mini, jendela PiP akan berisi UI tab, bukan
                // video (permukaan pemutar sedang tidak terpasang).
                SideEffect { pipEligible = videoId != null && !playerMinimized }
                // Membuka video lain selalu membesarkan pemutar lagi.
                LaunchedEffect(videoId) { playerMinimized = false }

                // Pemutar mini tidak memegang permukaan video: matikan jalur video
                // selama dilipat, nyalakan lagi saat halaman tonton dibuka.
                LaunchedEffect(playerMinimized) {
                    playbackManager.setVideoSurfaceVisible(!playerMinimized)
                }
                val barVisible =
                    (videoId == null || playerMinimized) && !openSearch && !openNotifications && openChannel == null && !openDownloads && openPersonal == null
                // System Back walks the page stack back to Beranda
                // instead of leaving the app; the player's own
                // BackHandler owns Back while a video is open.
                val canGoBack = videoId == null &&
                    (openChannel != null || openSearch || openNotifications || openDownloads || openPersonal != null || selectedTab != 0)
                BackHandler(enabled = canGoBack) {
                    when {
                        openPersonal != null -> openPersonal = null
                        openDownloads -> openDownloads = false
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
                            if (videoId != null && !playerMinimized) {
                                PlayerScreen(
                                    videoId = videoId,
                                    onBack = { openVideoId = null },
                                    onChannelClick = { id, name ->
                                        openChannel = id to name
                                    },
                                    onPipClick = { enterPip() },
                                    onVideoClick = { openVideoId = it },
                                    onMinimize = { playerMinimized = true },
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
                            } else if (openDownloads) {
                                DownloadsScreen(
                                    onBack = { openDownloads = false },
                                    onVideoClick = { openVideoId = it },
                                )
                            } else if (openPersonal != null) {
                                when (openPersonal) {
                                    "subs" -> SubscriptionsScreen(
                                        onBack = { openPersonal = null },
                                        onChannelClick = { id, name ->
                                            openChannel = id to name
                                        },
                                    )
                                    else -> PersonalFeedScreen(
                                        mode = openPersonal!!,
                                        onBack = { openPersonal = null },
                                        onVideoClick = { openVideoId = it },
                                    )
                                }
                            } else if (selectedTab == 0) {
                                HomeScreen(
                                    onVideoClick = { openVideoId = it },
                                    onSearchClick = { openSearch = true },
                                    onNotificationsClick = { openNotifications = true },
                                    notificationsUnread = notifUnread,
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
                                        notificationsUnread = notifUnread,
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
                                        notificationsUnread = notifUnread,
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = barHeightDp),
                                ) {
                                    ProfileScreen(
                                        onDownloadsClick = { openDownloads = true },
                                        onPersonalClick = { openPersonal = it },
                                    )
                                }
                            }
                        }

                        if (videoId != null && playerMinimized) {
                            val ready = playerUiState as? PlayerUiState.Ready
                            if (ready != null) {
                                MiniPlayerBar(
                                    title = ready.details.title,
                                    channelName = ready.details.channelName,
                                    thumbnailUrl = ready.details.thumbnailUrl,
                                    player = playerController,
                                    onExpand = { playerMinimized = false },
                                    onClose = {
                                        playerViewModel.stopPlayback()
                                        openVideoId = null
                                        playerMinimized = false
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(
                                            bottom = if (barVisible) {
                                                barHeightDp
                                            } else {
                                                0.dp
                                            },
                                        ),
                                )
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
     * Picture-in-picture, reached from the player's PiP chip.
     * Delegates to [pipController] (paket pip_24): the params now
     * also carry the PiP window's remote actions (mundur 10 dtk,
     * putar/jeda, maju 10 dtk) and, on Android 12+, the auto-enter
     * flag; the aspect ratio follows the playing video with the
     * same 1:2.39 … 2.39:1 clamp PiP demands.
     */
    private fun enterPip() {
        pipController.enter()
    }

    override fun onStart() {
        super.onStart()
        // Kembali ke depan: jalur video menyala lagi.
        playbackManager.setBackgroundMode(false)
        pipController.attach()
    }

    override fun onStop() {
        // Masuk latar atau layar mati: matikan jalur video (suara tetap
        // jalan). Saat PiP, jendelanya masih terlihat, jadi video harus
        // tetap menyala.
        if (!isInPictureInPictureMode) {
            playbackManager.setBackgroundMode(true)
        }
        pipController.detach()
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Jendela PiP ditutup: Activity sudah berhenti (CREATED), tetapi onStop
        // mungkin lebih dulu melihat mode PiP aktif. Pastikan jalur video mati.
        if (!isInPictureInPictureMode &&
            lifecycle.currentState == Lifecycle.State.CREATED
        ) {
            playbackManager.setBackgroundMode(true)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+: sistem yang melakukan auto-enter dari params
        // yang dipelihara PipController. Di bawahnya, controller
        // masuk sendiri — keduanya digerbang pipEligible + keadaan
        // putar, persis perilaku YouTube.
        pipController.onUserLeaveHint()
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
