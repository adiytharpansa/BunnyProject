package com.bunny.app.presentation.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bunny.app.R
import com.bunny.app.data.AppSettings
import com.bunny.app.presentation.components.SectionCard
import com.bunny.app.presentation.components.SettingsRow
import com.bunny.app.presentation.components.SwitchRow
import com.bunny.app.presentation.screens.profile.EngineHealthPanel
import com.bunny.app.presentation.screens.profile.GuestWarmingPanel
import kotlinx.coroutines.launch

private const val CONFIRM_FEED = "feed"
private const val CONFIRM_POSITIONS = "positions"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }

    // Di halaman Diagnostik, Kembali menutup halaman itu dulu.
    BackHandler(enabled = showDiagnostics) { showDiagnostics = false }

    val versionName = remember {
        try {
            context.packageManager
                .getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        } ?: "-"
    }
    val feedClearedMessage = stringResource(R.string.settings_cache_cleared)
    val positionsClearedMessage = stringResource(R.string.settings_positions_cleared)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (showDiagnostics) {
                                R.string.profile_technical_title
                            } else {
                                R.string.settings_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (showDiagnostics) showDiagnostics = false else onBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (showDiagnostics) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GuestWarmingPanel()
                    EngineHealthPanel()
                }
            } else {
                SectionCard(title = stringResource(R.string.settings_section_display)) {
                    SwitchRow(
                        icon = Icons.Outlined.Animation,
                        title = stringResource(R.string.settings_animations),
                        supporting = stringResource(R.string.settings_animations_desc),
                        checked = AppSettings.pageAnimations,
                        onCheckedChange = { AppSettings.setPageAnimations(it) },
                    )
                    SwitchRow(
                        icon = Icons.Outlined.Vibration,
                        title = stringResource(R.string.settings_haptics),
                        supporting = stringResource(R.string.settings_haptics_desc),
                        checked = AppSettings.haptics,
                        onCheckedChange = { AppSettings.setHaptics(it) },
                        showDivider = false,
                    )
                }

                SectionCard(title = stringResource(R.string.settings_section_playback)) {
                    SwitchRow(
                        icon = Icons.Outlined.PlayCircle,
                        title = stringResource(R.string.settings_resume),
                        supporting = stringResource(R.string.settings_resume_desc),
                        checked = AppSettings.resumePlayback,
                        onCheckedChange = { AppSettings.setResumePlayback(it) },
                        showDivider = false,
                    )
                }

                SectionCard(title = stringResource(R.string.settings_section_notifications)) {
                    SettingsRow(
                        icon = Icons.Outlined.Notifications,
                        title = stringResource(R.string.settings_notifications),
                        supporting = stringResource(R.string.settings_notifications_desc),
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            runCatching { context.startActivity(intent) }
                        },
                        showDivider = false,
                    )
                }

                SectionCard(title = stringResource(R.string.settings_section_storage)) {
                    SettingsRow(
                        icon = Icons.Outlined.Cached,
                        title = stringResource(R.string.settings_clear_cache),
                        supporting = stringResource(R.string.settings_clear_cache_desc),
                        onClick = { confirm = CONFIRM_FEED },
                    )
                    SettingsRow(
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.settings_clear_positions),
                        supporting = stringResource(R.string.settings_clear_positions_desc),
                        onClick = { confirm = CONFIRM_POSITIONS },
                        showDivider = false,
                    )
                }

                SectionCard(title = stringResource(R.string.settings_section_advanced)) {
                    SwitchRow(
                        icon = Icons.Outlined.BugReport,
                        title = stringResource(R.string.settings_dev_tools),
                        supporting = stringResource(R.string.settings_dev_tools_desc),
                        checked = AppSettings.developerTools,
                        onCheckedChange = { AppSettings.setDeveloperTools(it) },
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Build,
                        title = stringResource(R.string.profile_technical_button),
                        supporting = stringResource(R.string.settings_diagnostics_desc),
                        onClick = { showDiagnostics = true },
                        showDivider = false,
                    )
                }

                SectionCard(title = stringResource(R.string.settings_section_about)) {
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.settings_version),
                        trailing = {
                            Text(
                                versionName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        showDivider = false,
                    )
                }
            }
        }
    }

    when (confirm) {
        CONFIRM_FEED -> ConfirmDialog(
            title = stringResource(R.string.settings_clear_cache_confirm_title),
            message = stringResource(R.string.settings_clear_cache_confirm_message),
            onConfirm = {
                viewModel.clearFeedCache()
                confirm = null
                scope.launch { snackbar.showSnackbar(feedClearedMessage) }
            },
            onDismiss = { confirm = null },
        )
        CONFIRM_POSITIONS -> ConfirmDialog(
            title = stringResource(R.string.settings_clear_positions_confirm_title),
            message = stringResource(R.string.settings_clear_positions_confirm_message),
            onConfirm = {
                viewModel.clearWatchPositions()
                confirm = null
                scope.launch { snackbar.showSnackbar(positionsClearedMessage) }
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.settings_confirm_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}
