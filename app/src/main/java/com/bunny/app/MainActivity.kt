package com.bunny.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.bunny.app.data.AppSettings
import com.bunny.app.presentation.screens.settings.SettingsScreen
import com.bunny.app.ui.motion.AppMotion
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bunny.core.model.VideoItem
import com.bunny.app.playback.PlaybackManager
import androidx.hilt.navigation.compose.hiltViewModel
import com.bunny.app.presentation.components.LiveChip
import com.bunny.app.presentation.components.LocalLiveChip
import com.bunny.app.presentation.components.FloatingMiniPlayer
import com.bunny.app.presentation.components.MINI_CORNER_BOTTOM_RIGHT
import com.bunny.app.presentation.components.ShortsIcons
import com.bunny.app.presentation.screens.shorts.ShortsScreen
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
import com.bunny.app.bug.BugReportHost
import com.bunny.app.bug.MinorCategory
import com.bunny.app.bug.MinorIssueHost
import com.bunny.app.bug.MinorIssueRecorder
import com.bunny.app.presentation.theme.BunnyTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.media3.common.util.UnstableApi
import javax.inject.Inject

private val PasanganSaver = Saver<Pair<String, String>?, List<String>>(
    save = { p -> p?.let { listOf(it.first, it.second) } ?: emptyList() },
    restore = { l -> if (l.size == 2) l[0] to l[1] else null },
)

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

    /** True selama jendela PiP tampil; dibaca PlayerScreen. */
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A cold start can BE the OAuth redirect (the browser sheet
        // returns to a fresh process); hand it to the same door.
        if (savedInstanceState == null) {
            intent?.data?.let { desktopAuth.handleRedirect(it) }
        }
        AppSettings.init(applicationContext)
        enableEdgeToEdge(
            // Bar sistem transparan (keputusan pengguna 2026-11-13):
            // ikon gelap di tema terang; keterbacaan saat menggulir
            // dijaga selubung gradien di akar tata letak.
            statusBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
        )
        setContent {
            BunnyTheme {
                BugReportHost()
                // Izin notifikasi diminta SEKALI saat Bunny pertama kali
                // dibuka (Android 13+). Dialognya dibuat oleh sistem. Tanda
                // "sudah diminta" disimpan sebelum dialog tampil, jadi tidak
                // ada permintaan ulang di pembukaan berikutnya. Ini satu-satunya
                // tempat Bunny meminta izin notifikasi.
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
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
                var openVideoId by rememberSaveable { mutableStateOf<String?>(null) }
                var openSearch by rememberSaveable { mutableStateOf(false) }
                var openNotifications by rememberSaveable { mutableStateOf(false) }
                var openDownloads by rememberSaveable { mutableStateOf(false) }
                var openSettings by rememberSaveable { mutableStateOf(false) }
                var openPersonal by rememberSaveable { mutableStateOf<String?>(null) }
                // Pemutar mini (Fase 6): sesi video tetap hidup, halaman
                // penuhnya dilipat jadi bilah di atas bar bawah.
                var playerMinimized by rememberSaveable { mutableStateOf(false) }
                // Pojok terakhir jendela mini (bit 0 = kanan, bit 1 = bawah); bertahan
                // saat membesar lalu mengecil lagi dan saat layar diputar.
                var miniCorner by rememberSaveable { mutableStateOf(MINI_CORNER_BOTTOM_RIGHT) }

                // Membuka video selalu membesarkan pemutar — termasuk
                // ketukan pada video yang sedang dilipat (nilainya
                // sama, jadi tanpa ini tidak ada yang berubah).
                val openVideo: (String) -> Unit = { id ->
                    openVideoId = id
                    playerMinimized = false
                }
                val playerViewModel: PlayerViewModel = hiltViewModel()
                val playerUiState by playerViewModel.uiState.collectAsState()
                val playerController by playerViewModel.controllerPlayer.collectAsState()
                var openChannel by rememberSaveable(stateSaver = PasanganSaver) {
                    mutableStateOf<Pair<String, String>?>(null)
                }
                var selectedTab by rememberSaveable { mutableStateOf(0) }
                // Isi feed Beranda yang dilaporkan HomeScreen; Live dan Kanal
                // memakainya untuk daftar tab dan (di Kanal) untuk isi video.
                var feedItems by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
                val topics = remember(feedItems) {
                    feedItems.mapNotNull { it.reason }.distinct()
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

                // Jepretan muatan terakhir yang tidak kosong.
                // Cabang-cabang AnimatedContent di bawah masih
                // tersusun selama transisi KELUAR, sesudah keadaan
                // sumbernya dikosongkan (video ditutup, kanal
                // ditutup) — membaca !! pada keadaan sumber di sana
                // mogok (NPE MainActivity:276, laporan perangkat
                // 2026-11-13). Jepretan ini diperbarui setiap
                // komposisi saat sumbernya terisi, jauh sebelum
                // cabangnya bisa ada, jadi !! pada jepretan aman
                // menurut konstruksi.
                var lastVideoId by rememberSaveable { mutableStateOf<String?>(null) }
                var lastChannel by rememberSaveable(stateSaver = PasanganSaver) {
                    mutableStateOf<Pair<String, String>?>(null)
                }
                var lastPersonal by rememberSaveable { mutableStateOf<String?>(null) }
                if (videoId != null) lastVideoId = videoId
                if (openChannel != null) lastChannel = openChannel
                if (openPersonal != null) lastPersonal = openPersonal
                // PiP hanya saat halaman tonton yang tampil — dalam
                // keadaan mini, jendela PiP akan berisi UI tab, bukan
                // video (permukaan pemutar sedang tidak terpasang).
                SideEffect { pipEligible = videoId != null && !playerMinimized }
                // Membuka video lain selalu membesarkan pemutar lagi.
                LaunchedEffect(videoId) { playerMinimized = false }

                // Posisi gulir halaman tonton tinggal di sini (di luar
                // layar yang dilipat), jadi mengecil lalu membesar
                // tidak mengulang gulir dari atas.
                val playerScrollState = remember(videoId) {
                    androidx.compose.foundation.ScrollState(0)
                }

                // Pemutar mini memegang permukaan video (video langsung
                // di bar bawah), jadi jalur video tetap menyala selama
                // dilipat; hanya latar aplikasi yang mematikannya.
                val barVisible =
                    (videoId == null || playerMinimized) && !openSearch && !openNotifications && openChannel == null && !openDownloads && openPersonal == null && !openSettings
                // System Back walks the page stack back to Beranda
                // instead of leaving the app; the player's own
                // BackHandler owns Back while a video is open.
                val playerExpanded = videoId != null && !playerMinimized
                val canGoBack = !playerExpanded &&
                    (openChannel != null || openSearch || openNotifications ||
                        openDownloads || openSettings || openPersonal != null || selectedTab != 0)
                BackHandler(enabled = canGoBack) {
                    when {
                        openSettings -> openSettings = false
                        openPersonal != null -> openPersonal = null
                        openDownloads -> openDownloads = false
                        openChannel != null -> openChannel = null
                        openSearch -> openSearch = false
                        openNotifications -> openNotifications = false
                        else -> selectedTab = 0
                    }
                }
                // Chip "Siaran Langsung" di sebelah "Beranda" pada header
                // bersama; menekannya membuka tab Live.
                CompositionLocalProvider(
                    LocalLiveChip provides LiveChip(
                        liveSelected = selectedTab == 4,
                        onOpenLive = { selectedTab = 4 },
                        onOpenHome = { selectedTab = 0 },
                    ),
                ) {
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
                            val screenKey = when {
                                videoId != null && !playerMinimized -> "player"
                                openChannel != null -> "channel"
                                openSearch -> "search"
                                openNotifications -> "notifications"
                                openDownloads -> "downloads"
                                openSettings -> "settings"
                                openPersonal != null -> "personal"
                                else -> "tab$selectedTab"
                            }
                            AnimatedContent(
                                targetState = screenKey,
                                transitionSpec = {
                                    AppMotion.screenTransition(initialState, targetState)
                                },
                                label = "screen",
                            ) { key ->
                                when (key) {
                                    "player" -> {
                                PlayerScreen(
                                    videoId = videoId ?: lastVideoId!!,
                                    scrollState = playerScrollState,
                                    onBack = { openVideoId = null },
                                    onChannelClick = { id, name ->
                                        openChannel = id to name
                                    },
                                    onPipClick = { enterPip() },
                                    onEnterPip = { enterPip() },
                                    isInPip = inPip,
                                    onVideoClick = openVideo,
                                    onMinimize = { playerMinimized = true },
                                )
                                                                }
                                    "channel" -> {
                                val (channelId, channelName) = openChannel ?: lastChannel!!
                                ChannelScreen(
                                    channelId = channelId,
                                    channelName = channelName,
                                    onBack = { openChannel = null },
                                    onVideoClick = openVideo,
                                )
                                                                }
                                    "search" -> {
                                SearchScreen(
                                    onBack = { openSearch = false },
                                    onVideoClick = openVideo,
                                )
                                                                }
                                    "notifications" -> {
                                NotificationsScreen(
                                    onBack = { openNotifications = false },
                                    onVideoClick = openVideo,
                                )
                                                                }
                                    "downloads" -> {
                                DownloadsScreen(
                                    onBack = { openDownloads = false },
                                    onVideoClick = openVideo,
                                )
                                                                }
                                    "settings" -> {
                                        SettingsScreen(onBack = { openSettings = false })
                                    }
                                    "personal" -> {
                                when (openPersonal ?: lastPersonal) {
                                    "subs" -> SubscriptionsScreen(
                                        onBack = { openPersonal = null },
                                        onChannelClick = { id, name ->
                                            openChannel = id to name
                                        },
                                    )
                                    else -> PersonalFeedScreen(
                                        mode = (openPersonal ?: lastPersonal)!!,
                                        onBack = { openPersonal = null },
                                        onVideoClick = openVideo,
                                    )
                                }
                                                                }
                                    "tab0" -> {
                                HomeScreen(
                                    onVideoClick = openVideo,
                                    onSearchClick = { openSearch = true },
                                    onNotificationsClick = { openNotifications = true },
                                    notificationsUnread = notifUnread,
                                    onFeedChanged = { feedItems = it },
                                    onBarsFraction = {
                                        barsFraction.floatValue = it
                                    },
                                    bottomPadding = barHeightDp,
                                )
                                                                }
                                    "tab1" -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(bottom = barHeightDp),
                                        ) {
                                            ShortsScreen(
                                                onVideoClick = openVideo,
                                                onSearchClick = { openSearch = true },
                                            )
                                        }
                                    }
                                    "tab4" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = barHeightDp),
                                ) {
                                    LiveScreen(
                                        onVideoClick = openVideo,
                                        onSearchClick = { openSearch = true },
                                        onNotificationsClick = { openNotifications = true },
                                        topics = topics,
                                        notificationsUnread = notifUnread,
                                    )
                                }
                                                                                                    }

                                    "tab2" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = barHeightDp),
                                ) {
                                    KanalScreen(
                                        onChannelClick = { id, name ->
                                            openChannel = id to name
                                        },
                                        onVideoClick = openVideo,
                                        onSearchClick = { openSearch = true },
                                        onNotificationsClick = { openNotifications = true },
                                        topics = topics,
                                        feedItems = feedItems,
                                        notificationsUnread = notifUnread,
                                    )
                                }
                                                                }
                                    else -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = barHeightDp),
                                ) {
                                    ProfileScreen(
                                        onSettingsClick = { openSettings = true },
                                        onDownloadsClick = { openDownloads = true },
                                        onPersonalClick = { openPersonal = it },
                                    )
                                }
                                                                }
                                }
                            }

                        }

                        val readyNow = playerUiState as? PlayerUiState.Ready
                        // Jepretan terakhir: isi tetap ada selama animasi KELUAR.
                        var lastReady by remember { mutableStateOf<PlayerUiState.Ready?>(null) }
                        if (readyNow != null) lastReady = readyNow

                        val navBarBottom = WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding()
                        AnimatedVisibility(
                            visible = videoId != null && playerMinimized && readyNow != null,
                            modifier = Modifier.fillMaxSize(),
                            enter = fadeIn(tween(160)) +
                                slideInVertically(tween(220)) { it / 10 },
                            exit = fadeOut(tween(140)) +
                                slideOutVertically(tween(160)) { it / 10 },
                        ) {
                            lastReady?.let {
                                FloatingMiniPlayer(
                                    player = playerController,
                                    corner = miniCorner,
                                    onCornerChange = { miniCorner = it },
                                    // Jauhi bar tab; tanpa bar tab, jauhi bilah navigasi sistem.
                                    bottomInset = if (barVisible) barHeightDp else navBarBottom,
                                    onExpand = { playerMinimized = false },
                                    onClose = {
                                        playerViewModel.stopPlayback()
                                        openVideoId = null
                                        playerMinimized = false
                                    },
                                )
                            }
                        }

                        // Perekam masalah ringan: tidak menggambar apa pun bila
                        // tidak ada yang terdeteksi. Alat pengembangan; dihapus saat rilis.
                        if (AppSettings.developerTools) {
                            MinorIssueHost(
                                bottomPadding = (if (barVisible) barHeightDp else 0.dp) +
                                    (if (videoId != null && playerMinimized) 72.dp else 0.dp),
                            )
                        }

                        AnimatedVisibility(
                            visible = barVisible,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            enter = AppMotion.BarEnter,
                            exit = AppMotion.BarExit,
                        ) {
                            // Meluncur turun dan memudar bersama header Beranda.
                            BunnyBottomBar(
                                selectedTab = selectedTab,
                                onSelect = { selectedTab = it },
                                modifier = Modifier
                                    .onSizeChanged { barHeightPx = it.height.toFloat() }
                                    .graphicsLayer {
                                        val f = barsFraction.floatValue
                                        translationY = f * size.height
                                        alpha = 1f - f
                                    },
                            )
                        }

                        // Selubung bar status: bar sistem kini transparan,
                        // jadi gradien krem tipis ini menjaga ikon
                        // jam/baterai tetap terbaca saat isi menggulir di
                        // bawahnya. Hanya di layar tab — bukan halaman
                        // tonton, bukan PiP. Box polos tanpa clickable:
                        // ketukan lolos ke unsur di bawahnya.
                        if (!playerExpanded && !inPip) {
                            val statusBarTop = WindowInsets.statusBars
                                .asPaddingValues().calculateTopPadding()
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .height(statusBarTop)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.background
                                                    .copy(alpha = 0.92f),
                                                MaterialTheme.colorScheme.background
                                                    .copy(alpha = 0f),
                                            ),
                                        ),
                                    ),
                            )
                        }
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
    private fun enterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return false
        }
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
        return try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(aspect).build(),
            )
        } catch (e: IllegalStateException) {
            MinorIssueRecorder.report(
                MinorCategory.CAUGHT, "MainActivity", "Mode PiP gagal dimasuki", e,
            )
            false
        }
    }

    override fun onStart() {
        super.onStart()
        // Kembali ke depan: jalur video menyala lagi.
        playbackManager.setBackgroundMode(false)
    }

    override fun onStop() {
        // Masuk latar atau layar mati: matikan jalur video (suara tetap
        // jalan). Saat PiP, jendelanya masih terlihat, jadi video harus
        // tetap menyala.
        if (!isInPictureInPictureMode) {
            playbackManager.setBackgroundMode(true)
        }
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // Jendela PiP ditutup dengan ✕: putar berhenti total, sama
        // seperti YouTube (keputusan pengguna 2026-11-13; memperbesar
        // kembali jendela TIDAK masuk kondisi ini karena Activity
        // tidak berhenti di CREATED). Activity sudah berhenti, tetapi
        // onStop mungkin lebih dulu melihat mode PiP aktif, jadi
        // jalur video juga dipastikan mati di sini.
        if (!isInPictureInPictureMode &&
            lifecycle.currentState == Lifecycle.State.CREATED
        ) {
            playbackManager.player.pause()
            playbackManager.setBackgroundMode(true)
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
        super.onDestroy()
    }
}

/**
 * Bottom bar: icon-only, a soft tonal pill behind the selected icon,
 * hairline on top. Built by hand because the Material NavigationBar has
 * a fixed 80dp height. Tabs: Beranda · Shorts · Kanal · Profil.
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
                filled = ShortsIcons.Filled,
                outlined = ShortsIcons.Outlined,
                description = stringResource(R.string.nav_shorts),
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
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val tint by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(AppMotion.Tint),
        label = "navTint",
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(AppMotion.Tint),
        label = "navPillAlpha",
    )
    val pillScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.4f,
        animationSpec = AppMotion.PillSpring,
        label = "navPillScale",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = AppMotion.PressSpring,
        label = "navPress",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = source,
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
        Crossfade(
            targetState = selected,
            modifier = Modifier.graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            },
            animationSpec = tween(AppMotion.Tint),
            label = "navIcon",
        ) { isSelected ->
            Icon(
                imageVector = if (isSelected) filled else outlined,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
