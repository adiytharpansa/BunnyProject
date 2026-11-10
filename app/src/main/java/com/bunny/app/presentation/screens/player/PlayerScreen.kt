package com.bunny.app.presentation.screens.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.bunny.app.R
import com.bunny.app.playback.PlaybackDiagnostics
import com.bunny.app.playback.PlaybackFailureLog
import com.bunny.core.model.DataCost

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    onChannelClick: (channelId: String, channelName: String) -> Unit = { _, _ -> },
    onPipClick: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val controller by viewModel.controllerPlayer.collectAsState()

    // The media notification cannot appear on Android 13+ until the user
    // grants POST_NOTIFICATIONS. The system dialog is never shown on its
    // own: it appears only when the user taps the "Aktifkan putar latar &
    // notifikasi" button below, i.e. at the moment they choose to enable
    // the feature (owner decision 2026-11-10). Devices below Android 13
    // need no runtime grant.
    val context = LocalContext.current
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
        PlaybackDiagnostics.log("Izin notifikasi: ${if (granted) "diberikan" else "ditolak"}")
    }

    // Fase 4 telemetry: terminal playback failures persist on-device
    // across restarts; read once when this screen opens.
    var pastFailures by remember { mutableStateOf(PlaybackFailureLog.read(context)) }

    // Player controls (S5): quality / speed dialogs over the options
    // state; PiP is delegated to the activity via onPipClick.
    val playerOptions by viewModel.options.collectAsState()
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(videoId) { viewModel.open(videoId) }
    BackHandler {
        viewModel.stopPlayback()
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                viewModel.stopPlayback()
                onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
            }
            Text("Bunny", style = MaterialTheme.typography.titleMedium)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = true
                        keepScreenOn = true
                    }
                },
                update = { view ->
                    // Bind the MediaController once it connects; it drives
                    // the same underlying player PlaybackManager loads.
                    if (view.player !== controller) view.player = controller
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (state is PlayerUiState.Loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val current = state) {
                is PlayerUiState.Loading -> Text(
                    stringResource(R.string.player_loading),
                    style = MaterialTheme.typography.bodyMedium,
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
                    Text(
                        current.details.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val channelId = current.details.channelId
                    val meta = listOfNotNull(
                        // The channel gets its own row below (the
                        // official layout); it only stays in the meta
                        // line when no channel row can be built.
                        if (channelId == null) {
                            current.details.channelName.takeIf { it.isNotEmpty() }
                        } else {
                            null
                        },
                        current.details.viewCount?.let { formatCount(it) + " x ditonton" },
                        current.details.uploadedText,
                    ).joinToString(" • ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        current.sourceNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Timbangan at the player: the estimate now comes
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
                    if (current.jatahExceeded && current.quotaBytes != null) {
                        Text(
                            stringResource(
                                R.string.player_jatah_warning,
                                DataCost.formatSize(current.monthUsedBytes),
                                DataCost.formatSize(current.quotaBytes),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // Channel row, the official watch-page layout:
                    // avatar + name (tap → channel page) + subscribe
                    // pill. Langganan lokal remains Terbit's source.
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
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    current.details.channelName
                                        .firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme
                                        .onPrimaryContainer,
                                )
                            }
                            Text(
                                current.details.channelName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (subscribed) {
                                OutlinedButton(onClick = {
                                    viewModel.toggleSubscription(
                                        channelId = channelId,
                                        channelName = current.details.channelName,
                                        subscribed = true,
                                    )
                                }) {
                                    Text(stringResource(R.string.player_unsubscribe))
                                }
                            } else {
                                Button(onClick = {
                                    viewModel.toggleSubscription(
                                        channelId = channelId,
                                        channelName = current.details.channelName,
                                        subscribed = false,
                                    )
                                }) {
                                    Text(stringResource(R.string.player_subscribe))
                                }
                            }
                        }
                    }
                    // Action row: the player's controls as chips, like
                    // the official app's horizontal action strip.
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        item {
                            AssistChip(
                                onClick = {
                                    viewModel.refreshOptions()
                                    showQualityDialog = true
                                },
                                label = {
                                    Text(
                                        stringResource(R.string.player_quality) +
                                            ": " + (
                                                playerOptions.selectedQuality
                                                    ?.let { "${it}p" }
                                                    ?: stringResource(
                                                        R.string.player_quality_auto,
                                                    )
                                                ),
                                    )
                                },
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { showSpeedDialog = true },
                                label = {
                                    Text(
                                        stringResource(R.string.player_speed) +
                                            ": " + formatSpeed(playerOptions.speed),
                                    )
                                },
                            )
                        }
                        item {
                            AssistChip(
                                onClick = onPipClick,
                                label = { Text(stringResource(R.string.player_pip)) },
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
                    if (!notificationsGranted) {
                        Button(onClick = {
                            PlaybackDiagnostics.log("Izin notifikasi: diminta dari tombol aktivasi")
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) {
                            Text(stringResource(R.string.player_enable_background))
                        }
                        Text(
                            stringResource(R.string.player_enable_background_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            }
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
        Text(stringResource(R.string.player_diagnostics))
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

private fun formatCount(count: Long): String = when {
    count >= 1_000_000_000 -> "%.1f M".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.1f jt".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1f rb".format(count / 1_000.0)
    else -> count.toString()
}
