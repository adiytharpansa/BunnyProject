package com.bunny.app.presentation.screens.player

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.bunny.app.R
import com.bunny.app.data.DownloadManager
import com.bunny.app.presentation.components.VideoCard
import com.bunny.app.playback.PlaybackDiagnostics
import com.bunny.app.playback.PlayerTouchGestures
import com.bunny.core.model.Chapters
import kotlin.math.roundToInt
import com.bunny.app.playback.PlaybackFailureLog
import com.bunny.core.model.DataCost
import com.bunny.core.model.VideoComment

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    onChannelClick: (channelId: String, channelName: String) -> Unit = { _, _ -> },
    onPipClick: () -> Unit = {},
    onVideoClick: (videoId: String) -> Unit = {},
    /** Shrink to the mini player (playback continues); NOT a stop. */
    onMinimize: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val controller by viewModel.controllerPlayer.collectAsState()
    val related by viewModel.related.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val download by remember(videoId) { viewModel.downloadState(videoId) }
        .collectAsState(null)
    // Scroll kembali ke atas setiap kali pindah ke video lain.
    val scrollState = remember(videoId) { ScrollState(0) }

    // Izin notifikasi diminta sekali saat Bunny pertama kali dibuka
    // (MainActivity); halaman pemutar tidak meminta izin.
    val context = LocalContext.current

    // ---- Layar penuh: landscape = layar penuh. Tombol layar penuh dan
    // putaran fisik HP sama-sama berujung pada orientasi layar.
    val activity = LocalContext.current.findActivity()
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Ketuk tombol layar penuh / panah kembali saat landscape. Dihitung dari
    // orientasi SAAT INI, bukan dari ikon, jadi tetap benar walau ikonnya
    // belum menyusul setelah HP diputar.
    val toggleFullscreen: () -> Unit = {
        activity?.let { act ->
            val landscapeNow =
                act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            act.requestedOrientation = if (landscapeNow) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    // Sembunyikan bilah sistem saat landscape, tampilkan lagi saat portrait.
    DisposableEffect(isLandscape) {
        val window = activity?.window
        if (window != null) {
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                insets.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insets.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insets.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    // Setelah keluar dari layar penuh lewat tombol, orientasi terkunci ke
    // portrait. Begitu HP benar-benar tegak lagi, kembalikan ke putar otomatis.
    // Saat halaman pemutar ditutup, orientasi dan bilah sistem dipulihkan.
    DisposableEffect(activity) {
        val listener = object : OrientationEventListener(activity ?: context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val upright = degrees <= 30 || degrees >= 330 || degrees in 150..210
                if (upright &&
                    activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                ) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        if (activity != null) listener.enable()
        onDispose {
            listener.disable()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ---- Layar menyala hanya saat video benar-benar diputar.
    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(controller) {
        val player = controller
        if (player == null) {
            isPlaying = false
            onDispose { }
        } else {
            isPlaying = player.isPlaying
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }

    // Fase 4 telemetry: terminal playback failures persist on-device
    // across restarts; read once when this screen opens.
    var pastFailures by remember { mutableStateOf(PlaybackFailureLog.read(context)) }

    // Player controls (S5): quality / speed dialogs over the options
    // state; PiP is delegated to the activity via onPipClick.
    val playerOptions by viewModel.options.collectAsState()
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }

    // Gestur pemutar (paket §8): teks HUD sesaat + penanda tahan-2x.
    val gestureHud = remember { mutableStateOf<String?>(null) }
    val speedBoost = remember { mutableStateOf(false) }
    LaunchedEffect(gestureHud.value) {
        if (gestureHud.value != null) {
            kotlinx.coroutines.delay(700)
            gestureHud.value = null
        }
    }

    // YouTube-style: the title clamps to two lines and the grey
    // description box expands in place.
    var infoExpanded by remember(videoId) { mutableStateOf(false) }

    LaunchedEffect(videoId) { viewModel.open(videoId) }
    BackHandler {
        viewModel.stopPlayback()
        onBack()
    }

    // Di layar penuh, Back keluar dari layar penuh, bukan menutup video.
    BackHandler(enabled = isLandscape) { toggleFullscreen() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Video first, edge to edge; the back button floats on it
        // (no separate app bar, like the official watch page).
        Box(
            modifier = (
                if (isLandscape) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                }
                ).background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        // keepScreenOn TIDAK dipasang di sini; lihat `update`.
                        // Media3 1.5 made the Compose surface-sync workaround
                        // opt-in; without it, video rendered inside AndroidView
                        // can stretch/crop on API 34 devices.
                        setEnableComposeSurfaceSyncWorkaround(true)

                        // Tombol sebelumnya/berikutnya DIPAKAI sekarang:
                        // diarahkan ke antrean rekomendasi/riwayat oleh
                        // QueueSkipPlayer di PlaybackManager.

                        // Munculkan tombol layar penuh di bilah kontrol.
                        setFullscreenButtonClickListener { toggleFullscreen() }

                        // Gestur (paket §8): ketuk dua kali ±10 dtk,
                        // tahan = 2x sementara, geser vertikal kanan =
                        // volume, kiri = kecerahan. Listener hanya
                        // mengonsumsi gestur yang ia kenal; sentuhan
                        // lain tetap milik kontrol bawaan PlayerView.
                        val gestureActivity = generateSequence(ctx) {
                            (it as? ContextWrapper)?.baseContext
                        }.filterIsInstance<Activity>().firstOrNull()
                        val gestureAudio =
                            ctx.getSystemService(AudioManager::class.java)
                        setOnTouchListener(
                            PlayerTouchGestures(
                                ctx,
                                object : PlayerTouchGestures.Callbacks {
                                    override fun onDoubleTapSeek(forward: Boolean) {
                                        viewModel.seekBy(
                                            if (forward) 10_000 else -10_000,
                                        )
                                        gestureHud.value = ctx.getString(
                                            if (forward) {
                                                R.string.gesture_forward
                                            } else {
                                                R.string.gesture_back
                                            },
                                        )
                                    }

                                    override fun onHoldSpeed(holding: Boolean) {
                                        viewModel.setHoldSpeed(holding)
                                        speedBoost.value = holding
                                    }

                                    override fun onVolumeDelta(fraction: Float) {
                                        val am = gestureAudio ?: return
                                        val max = am.getStreamMaxVolume(
                                            AudioManager.STREAM_MUSIC,
                                        )
                                        if (max <= 0) return
                                        val now = am.getStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                        ).toFloat() / max
                                        val next = (now + fraction)
                                            .coerceIn(0f, 1f)
                                        am.setStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                            (next * max).roundToInt(),
                                            0,
                                        )
                                        gestureHud.value = ctx.getString(
                                            R.string.gesture_volume,
                                            (next * 100).toInt(),
                                        )
                                    }

                                    override fun onBrightnessDelta(fraction: Float) {
                                        val act = gestureActivity ?: return
                                        val attrs = act.window.attributes
                                        val now = if (attrs.screenBrightness < 0f) {
                                            0.5f
                                        } else {
                                            attrs.screenBrightness
                                        }
                                        val next = (now + fraction)
                                            .coerceIn(0.01f, 1f)
                                        attrs.screenBrightness = next
                                        act.window.attributes = attrs
                                        gestureHud.value = ctx.getString(
                                            R.string.gesture_brightness,
                                            (next * 100).toInt(),
                                        )
                                    }

                                    override fun onGestureEnd() = Unit
                                },
                            ),
                        )

                        // Bilah progres diberi jarak 16dp dari tepi layar supaya
                        // pegangannya tidak menempel ke tepi (dan bentrok dengan
                        // gestur kembali Android). Tidak berbuat apa-apa bila
                        // tata letak bawaan tidak punya bilah ber-id ini.
                        findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.let { bar ->
                            val params = bar.layoutParams as? ViewGroup.MarginLayoutParams
                            if (params != null) {
                                val margin = (16 * resources.displayMetrics.density).toInt()
                                params.marginStart = margin
                                params.marginEnd = margin
                                bar.layoutParams = params
                            }
                        }
                    }
                },
                update = { view ->
                    // Bind the MediaController once it connects; it drives
                    // the same underlying player PlaybackManager loads.
                    if (view.player !== controller) view.player = controller
                    // Layar tetap menyala hanya selama video diputar.
                    view.keepScreenOn = isPlaying
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (state is PlayerUiState.Loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            gestureHud.value?.let { hud ->
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                ) {
                    Text(
                        hud,
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 8.dp,
                        ),
                        color = Color.White,
                    )
                }
            }
            if (speedBoost.value) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(alpha = 0.7f),
                ) {
                    Text(
                        stringResource(R.string.gesture_speed_boost),
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 8.dp,
                        ),
                        color = Color.White,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = {
                    if (isLandscape) {
                        toggleFullscreen()
                    } else {
                        viewModel.stopPlayback()
                        onBack()
                    }
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_back),
                        tint = Color.White,
                    )
                }
            }
            // Kecilkan ke pemutar mini: putar berlanjut, halaman lain
            // bisa dijelajahi. Berbeda dari tombol kembali, yang
            // menghentikan putar.
            if (!isLandscape) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = onMinimize) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.player_minimize),
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        if (!isLandscape) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val current = state) {
                is PlayerUiState.Loading -> Text(
                    stringResource(R.string.player_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is PlayerUiState.Error -> {
                    Text(
                        current.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { viewModel.retry() }) {
                        Text(stringResource(R.string.retry))
                    }
                    DiagnosticsBlock(
                        events = diagnostics,
                        pastFailures = pastFailures,
                        onClearFailures = {
                            PlaybackFailureLog.clear(context)
                            pastFailures = null
                        },
                    )
                }

                is PlayerUiState.Ready -> {
                    // ---- Title (2 lines, expands with the info box)
                    Text(
                        current.details.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (infoExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { infoExpanded = !infoExpanded },
                    )

                    // ---- Info box: views + date, data estimate, source
                    val channelId = current.details.channelId
                    val meta = listOfNotNull(
                        // The channel has its own row below; it only
                        // stays in the meta line when no row can be built.
                        if (channelId == null) {
                            current.details.channelName.takeIf { it.isNotEmpty() }
                        } else {
                            null
                        },
                        current.details.viewCount?.let { formatCount(it) + " x ditonton" },
                        current.details.uploadedText,
                    ).joinToString(" • ")
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { infoExpanded = !infoExpanded },
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (meta.isNotEmpty()) {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            // Timbangan at the player: the estimate comes
                            // from the real formats, not the card ladder.
                            current.estimatedSizeBytes?.let { bytes ->
                                Text(
                                    stringResource(
                                        R.string.player_data_estimate,
                                        DataCost.formatSize(bytes),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (infoExpanded) {
                                // The video's real description, as written
                                // by the channel (from the player data).
                                if (current.details.description.isNotBlank()) {
                                    Text(
                                        current.details.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Text(
                                    current.sourceNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                stringResource(
                                    if (infoExpanded) R.string.player_less
                                    else R.string.player_more,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (current.jatahExceeded && current.quotaBytes != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    R.string.player_jatah_warning,
                                    DataCost.formatSize(current.monthUsedBytes),
                                    DataCost.formatSize(current.quotaBytes),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }

                    // ---- Channel row: avatar + name (tap -> channel
                    // page) + subscribe pill. Langganan lokal remains
                    // the source of the Kanal tab.
                    if (channelId != null) {
                        val subFlow = remember(channelId) {
                            viewModel.subscriptionState(channelId)
                        }
                        val subscribed by subFlow.collectAsState(initial = false)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onChannelClick(
                                        channelId,
                                        current.details.channelName,
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // The channel's real photo from the watch
                            // page; plain until it arrives (no letter
                            // stand-in — user rule 2026-11-11).
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                    ),
                            ) {
                                comments.ownerAvatarUrl?.let { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                            Text(
                                current.details.channelName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (subscribed) {
                                FilledTonalButton(
                                    shape = CircleShape,
                                    onClick = {
                                        viewModel.toggleSubscription(
                                            channelId = channelId,
                                            channelName = current.details.channelName,
                                            subscribed = true,
                                        )
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        stringResource(R.string.player_unsubscribe),
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            } else {
                                Button(
                                    shape = CircleShape,
                                    onClick = {
                                        viewModel.toggleSubscription(
                                            channelId = channelId,
                                            channelName = current.details.channelName,
                                            subscribed = false,
                                        )
                                    },
                                ) {
                                    Text(stringResource(R.string.player_subscribe))
                                }
                            }
                        }
                    }

                    // ---- Action strip: pill buttons with icons
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            ActionPill(
                                icon = Icons.Outlined.HighQuality,
                                label = stringResource(R.string.player_quality) +
                                    ": " + (
                                        playerOptions.selectedQuality?.let { "${it}p" }
                                            ?: stringResource(R.string.player_quality_auto)
                                        ),
                                onClick = {
                                    viewModel.refreshOptions()
                                    showQualityDialog = true
                                },
                            )
                        }
                        item {
                            ActionPill(
                                icon = Icons.Outlined.Speed,
                                label = formatSpeed(playerOptions.speed),
                                onClick = { showSpeedDialog = true },
                            )
                        }
                        item {
                            ActionPill(
                                icon = Icons.Outlined.PictureInPictureAlt,
                                label = stringResource(R.string.player_pip),
                                onClick = onPipClick,
                            )
                        }
                        item {
                            // Panen Bertingkat: audio diambil dulu;
                            // video menyusul sendiri saat Wi-Fi ada.
                            val dl = download
                            ActionPill(
                                icon = Icons.Outlined.Download,
                                label = when (dl?.stage) {
                                    DownloadManager.STAGE_AUDIO ->
                                        if (dl != null && dl.totalBytes > 0) {
                                            stringResource(
                                                R.string.download_stage_audio_pct,
                                                (dl.progressBytes * 100 / dl.totalBytes).toInt(),
                                            )
                                        } else {
                                            stringResource(R.string.download_stage_audio)
                                        }
                                    DownloadManager.STAGE_WAITING_WIFI ->
                                        stringResource(R.string.download_stage_waiting_wifi)
                                    DownloadManager.STAGE_VIDEO ->
                                        if (dl != null && dl.totalBytes > 0) {
                                            stringResource(
                                                R.string.download_stage_video_pct,
                                                (dl.progressBytes * 100 / dl.totalBytes).toInt(),
                                            )
                                        } else {
                                            stringResource(R.string.download_stage_video)
                                        }
                                    DownloadManager.STAGE_COMPLETE ->
                                        stringResource(R.string.download_stage_complete)
                                    DownloadManager.STAGE_FAILED ->
                                        stringResource(R.string.download_stage_failed)
                                    else -> stringResource(R.string.download_action)
                                },
                                onClick = {
                                    if (dl?.stage == DownloadManager.STAGE_COMPLETE) {
                                        viewModel.deleteDownload()
                                    } else {
                                        viewModel.enqueueDownload()
                                    }
                                },
                            )
                        }
                        item {
                            ActionPill(
                                icon = Icons.Outlined.Share,
                                label = stringResource(R.string.player_share),
                                onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_SUBJECT,
                                            current.details.title,
                                        )
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "${current.details.title}\n" +
                                                "https://youtu.be/${current.details.id}",
                                        )
                                    }
                                    context.startActivity(
                                        Intent.createChooser(
                                            send,
                                            context.getString(
                                                R.string.share_chooser,
                                            ),
                                        ),
                                    )
                                },
                            )
                        }
                        item {
                            ActionPill(
                                icon = Icons.Outlined.Repeat,
                                label = stringResource(R.string.player_loop) +
                                    ": " + stringResource(
                                        if (playerOptions.loop) {
                                            R.string.state_on
                                        } else {
                                            R.string.state_off
                                        },
                                    ),
                                onClick = { viewModel.toggleLoop() },
                            )
                        }
                        item {
                            ActionPill(
                                icon = Icons.Outlined.Bedtime,
                                label = stringResource(R.string.player_sleep) +
                                    (
                                        playerOptions.sleepMinutes?.let {
                                            ": $it mnt"
                                        } ?: ""
                                        ),
                                onClick = { showSleepDialog = true },
                            )
                        }
                    }

                    if (showQualityDialog) {
                        AlertDialog(
                            onDismissRequest = { showQualityDialog = false },
                            title = { Text(stringResource(R.string.player_quality)) },
                            text = {
                                Column {
                                    PlayerOptionRow(
                                        label = stringResource(
                                            R.string.player_quality_auto,
                                        ),
                                        selected = playerOptions.selectedQuality == null,
                                        onClick = {
                                            viewModel.setQuality(null)
                                            showQualityDialog = false
                                        },
                                    )
                                    playerOptions.qualities.forEach { height ->
                                        PlayerOptionRow(
                                            label = "${height}p",
                                            selected =
                                                playerOptions.selectedQuality == height,
                                            onClick = {
                                                viewModel.setQuality(height)
                                                showQualityDialog = false
                                            },
                                        )
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showQualityDialog = false }) {
                                    Text(stringResource(R.string.dialog_close))
                                }
                            },
                        )
                    }
                    if (showSpeedDialog) {
                        AlertDialog(
                            onDismissRequest = { showSpeedDialog = false },
                            title = { Text(stringResource(R.string.player_speed)) },
                            text = {
                                Column {
                                    PLAYER_SPEEDS.forEach { speed ->
                                        PlayerOptionRow(
                                            label = formatSpeed(speed),
                                            selected = playerOptions.speed == speed,
                                            onClick = {
                                                viewModel.setSpeed(speed)
                                                showSpeedDialog = false
                                            },
                                        )
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showSpeedDialog = false }) {
                                    Text(stringResource(R.string.dialog_close))
                                }
                            },
                        )
                    }

                    if (showSleepDialog) {
                        AlertDialog(
                            onDismissRequest = { showSleepDialog = false },
                            title = { Text(stringResource(R.string.player_sleep)) },
                            text = {
                                Column {
                                    PlayerOptionRow(
                                        label = stringResource(
                                            R.string.player_sleep_off,
                                        ),
                                        selected = playerOptions.sleepMinutes == null,
                                        onClick = {
                                            viewModel.setSleepTimer(null)
                                            showSleepDialog = false
                                        },
                                    )
                                    listOf(15, 30, 45, 60).forEach { minutes ->
                                        PlayerOptionRow(
                                            label = stringResource(
                                                R.string.player_sleep_minutes,
                                                minutes,
                                            ),
                                            selected =
                                                playerOptions.sleepMinutes == minutes,
                                            onClick = {
                                                viewModel.setSleepTimer(minutes)
                                                showSleepDialog = false
                                            },
                                        )
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showSleepDialog = false }) {
                                    Text(stringResource(R.string.dialog_close))
                                }
                            },
                        )
                    }

                    // Bab video dari deskripsi ASLI (paket §9): hanya
                    // tampil bila daftar cap waktunya memenuhi aturan
                    // bab YouTube; mengetuk bab = loncat ke detiknya.
                    val chapters = remember(
                        current.details.id,
                        current.details.description,
                    ) {
                        Chapters.parse(current.details.description, 0)
                    }
                    if (chapters.isNotEmpty()) {
                        Text(
                            stringResource(R.string.player_chapters),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(chapters) { chapter ->
                                OutlinedButton(
                                    onClick = {
                                        viewModel.seekTo(chapter.startMs)
                                    },
                                ) {
                                    Text(
                                        "${formatChapterTime(chapter.startMs)}" +
                                            " · ${chapter.title}",
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DiagnosticsBlock(
                        events = diagnostics,
                        pastFailures = pastFailures,
                        onClearFailures = {
                            PlaybackFailureLog.clear(context)
                            pastFailures = null
                        },
                    )
                    CommentsSection(
                        state = comments,
                        onLoadMore = { viewModel.loadMoreComments() },
                        onToggleReplies = { viewModel.toggleReplies(it) },
                        onLoadMoreReplies = { viewModel.loadMoreReplies(it) },
                    )
                    RelatedSection(
                        state = related,
                        channelName = current.details.channelName,
                        onVideoClick = onVideoClick,
                        onRetry = { viewModel.retryRelated() },
                    )
                }      // menutup `is PlayerUiState.Ready ->`
            }          // menutup `when`
        }              // menutup Column kedua
        }              // menutup `if (!isLandscape)`
    }                  // menutup Column luar
}                      // menutup fungsi PlayerScreen

/**
 * Komentar asli dari halaman tonton: penulis, foto, waktu, isi dan
 * jumlah suka persis seperti diterbitkan. Balasan bisa dibuka di
 * tempat: utas dimuat lewat token lanjutannya sendiri saat diketuk.
 */
@Composable
private fun CommentsSection(
    state: CommentsUiState,
    onLoadMore: () -> Unit,
    onToggleReplies: (VideoComment) -> Unit,
    onLoadMoreReplies: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.player_comments_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            state.isLoading -> CircularProgressIndicator()
            state.items.isEmpty() -> Text(
                stringResource(R.string.player_comments_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                state.items.forEach { comment ->
                    CommentRow(
                        comment = comment,
                        replies = state.replies[comment.id],
                        onToggleReplies = { onToggleReplies(comment) },
                        onLoadMoreReplies = { onLoadMoreReplies(comment.id) },
                    )
                }
                if (state.nextPage != null) {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.isLoadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.player_comments_load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: VideoComment,
    replies: RepliesUiState? = null,
    onToggleReplies: () -> Unit = {},
    onLoadMoreReplies: () -> Unit = {},
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            comment.authorAvatarUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            val head = listOfNotNull(
                comment.authorName.takeIf { it.isNotEmpty() },
                comment.publishedText,
            ).joinToString(" • ")
            if (head.isNotEmpty()) {
                Text(
                    head,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(comment.text, style = MaterialTheme.typography.bodyMedium)
            val meta = listOfNotNull(
                comment.likeCountText?.takeIf { it != "0" }?.let {
                    stringResource(R.string.player_comment_likes, it)
                },
                if (comment.replyCount > 0) {
                    stringResource(R.string.player_comment_replies, comment.replyCount)
                } else {
                    null
                },
                if (comment.isCreatorHearted) {
                    stringResource(R.string.player_comment_hearted)
                } else {
                    null
                },
            ).joinToString(" • ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Balasan: tombol buka/tutup hanya bila server memberi
            // pintu masuknya (token) — utas tanpa token tidak berpura-
            // pura bisa dibuka.
            if (comment.replyCount > 0 && comment.repliesContinuation != null) {
                TextButton(onClick = onToggleReplies) {
                    Text(
                        if (replies?.expanded == true) {
                            stringResource(R.string.player_replies_hide)
                        } else {
                            stringResource(R.string.player_replies_show, comment.replyCount)
                        },
                    )
                }
            }
            if (replies?.expanded == true) {
                when {
                    replies.isLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                    )
                    replies.failed -> Text(
                        stringResource(R.string.player_replies_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        replies.items.forEach { reply -> CommentRow(reply) }
                        if (replies.nextPage != null) {
                            TextButton(
                                onClick = onLoadMoreReplies,
                                enabled = !replies.isLoadingMore,
                            ) {
                                Text(stringResource(R.string.player_replies_load_more))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Rounded grey pill with a leading icon, like YouTube's action strip. */
@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DiagnosticsBlock(
    events: List<String>,
    pastFailures: String? = null,
    onClearFailures: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(R.string.player_diagnostics),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        Text(
            events.takeLast(12).joinToString("\n").ifEmpty { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pastFailures != null) {
            Text(
                stringResource(R.string.player_failures_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                pastFailures,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClearFailures) {
                Text(stringResource(R.string.player_failures_clear))
            }
        }
    }
}

@Composable
private fun PlayerOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private val PLAYER_SPEEDS =
    listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

private fun formatChapterTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000_000 -> "%.1f M".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.1f jt".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1f rb".format(count / 1_000.0)
    else -> count.toString()
}

private enum class RelatedFilter { SEMUA, KANAL, TERKAIT }

/**
 * Rekomendasi gaya YouTube: chip filter lalu kartu full-width. Kartu
 * dibuat memenuhi lebar layar (menembus padding 12dp kolom induk).
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun RelatedSection(
    state: RelatedUiState,
    channelName: String,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var filter by remember { mutableStateOf(RelatedFilter.SEMUA) }
    val items = when (filter) {
        RelatedFilter.SEMUA -> state.all
        RelatedFilter.KANAL -> state.fromChannel
        RelatedFilter.TERKAIT -> state.similar
    }

    Column(
        modifier = Modifier.fullBleed(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = filter == RelatedFilter.SEMUA,
                    onClick = { filter = RelatedFilter.SEMUA },
                    label = { Text(stringResource(R.string.related_all)) },
                )
            }
            if (channelName.isNotEmpty()) {
                item {
                    FilterChip(
                        selected = filter == RelatedFilter.KANAL,
                        onClick = { filter = RelatedFilter.KANAL },
                        label = {
                            Text(
                                stringResource(R.string.related_from_channel, channelName),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp),
                            )
                        },
                    )
                }
            }
            item {
                FilterChip(
                    selected = filter == RelatedFilter.TERKAIT,
                    onClick = { filter = RelatedFilter.TERKAIT },
                    label = { Text(stringResource(R.string.related_similar)) },
                )
            }
        }

        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            items.isEmpty() -> Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    state.error ?: stringResource(R.string.related_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }

            else -> items.forEach { item ->
                key(item.id) {
                    VideoCard(
                        item = item,
                        onClick = { onVideoClick(item.id) },
                        showReason = filter == RelatedFilter.SEMUA,
                    )
                }
            }
        }
    }
}

/** Melebarkan konten keluar sebesar [inset] di kiri dan kanan. */
private fun Modifier.fullBleed(inset: Dp): Modifier = layout { measurable, constraints ->
    val insetPx = inset.roundToPx()
    val width = constraints.maxWidth + insetPx * 2
    val placeable = measurable.measure(
        Constraints(minWidth = width, maxWidth = width),
    )
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-insetPx, 0)
    }
}

/** Mencari Activity dari Context yang mungkin dibungkus. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
