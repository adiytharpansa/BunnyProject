package com.bunny.app.presentation.screens.profile

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bunny.app.R
import com.bunny.app.data.AuthRepository
import com.bunny.app.presentation.components.ResilientImage
import com.bunny.app.presentation.components.SectionCard
import com.bunny.app.presentation.components.SettingsRow
import androidx.compose.runtime.remember

/**
 * Profil. Masuk lewat SATU pintu: tombol "Masuk dengan Google" membuka
 * halaman resmi Google di lembar peramban sistem. Halaman ini hanya
 * memuat hal tentang akun. Alat diagnostik ada di Pengaturan > Lanjutan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onPersonalClick: (String) -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
    desktopViewModel: DesktopSignInViewModel = hiltViewModel(),
) {
    val authState by viewModel.state.collectAsState()
    val desktopState by desktopViewModel.state.collectAsState()
    val activity = LocalContext.current as? Activity
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }
    val signedIn = authState as? AuthRepository.State.SignedIn

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.profile_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---- Kepala akun
            if (signedIn != null) {
                AccountHeader(
                    avatarUrl = signedIn.avatarUrl,
                    title = signedIn.name
                        ?: stringResource(R.string.profile_account_fallback),
                    subtitle = stringResource(R.string.profile_signed_in_hint),
                )
            } else {
                AccountHeader(
                    avatarUrl = null,
                    title = stringResource(R.string.profile_guest_title),
                    subtitle = null,
                )
                // ---- Kartu masuk
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.profile_login_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (desktopViewModel.configured) {
                            Button(
                                onClick = {
                                    if (activity != null) {
                                        desktopViewModel.start(activity)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.profile_login_button))
                            }
                            DesktopStatusText(desktopState)
                        }
                        Text(
                            stringResource(R.string.profile_login_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- Akun saya (hanya saat sudah masuk)
            if (signedIn != null) {
                SectionCard(title = stringResource(R.string.profile_section_account)) {
                    SettingsRow(
                        icon = Icons.Outlined.Subscriptions,
                        title = stringResource(R.string.personal_subs_button),
                        onClick = { onPersonalClick("subs") },
                    )
                    SettingsRow(
                        icon = Icons.Outlined.NewReleases,
                        title = stringResource(R.string.personal_latest_button),
                        onClick = { onPersonalClick("latest") },
                    )
                    SettingsRow(
                        icon = Icons.Outlined.ThumbUp,
                        title = stringResource(R.string.personal_liked_button),
                        onClick = { onPersonalClick("liked") },
                        showDivider = false,
                    )
                }
            }

            // ---- Umum
            SectionCard(title = stringResource(R.string.profile_section_general)) {
                SettingsRow(
                    icon = Icons.Outlined.Download,
                    title = stringResource(R.string.profile_downloads_button),
                    onClick = onDownloadsClick,
                )
                SettingsRow(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.profile_settings_button),
                    onClick = onSettingsClick,
                    showDivider = false,
                )
            }

            // ---- Keluar
            if (signedIn != null) {
                TextButton(
                    onClick = { showSignOutDialog = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        stringResource(R.string.profile_signout),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.profile_signout_confirm_title)) },
            text = { Text(stringResource(R.string.profile_signout_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        viewModel.signOut()
                    },
                ) {
                    Text(
                        stringResource(R.string.profile_signout),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/** Foto (atau ikon bawaan), nama, dan satu baris keterangan. */
@Composable
private fun AccountHeader(
    avatarUrl: String?,
    title: String,
    subtitle: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                ResilientImage(
                    url = avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(52.dp),
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Selera Tamu (Langkah 2): the warming probe panel — the two
 * oracles read aloud. "Reports sent" is the reporter's own count
 * and is shown separately from "watches recorded": the research
 * behind this feature is explicit that a sent report (even a
 * 204 answer) is not proof of recording; only the history
 * browse answering is.
 */
@Composable
internal fun GuestWarmingPanel(
    viewModel: GuestWarmingViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsState()
    val running by viewModel.running.collectAsState()
    val sent by viewModel.sentCount.collectAsState()
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text(
        stringResource(R.string.guest_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.guest_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Text(
        stringResource(R.string.guest_sent, sent),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    val current = report
    if (current == null) {
        Text(
            stringResource(R.string.guest_never),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    } else {
        Text(
            stringResource(
                if (current.homeItemCount > 0) {
                    R.string.guest_home_warm
                } else {
                    R.string.guest_home_cold
                },
                current.homeItemCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        val historyCount = current.historyItemCount
        Text(
            when {
                historyCount == null -> stringResource(
                    R.string.guest_history_refused,
                    current.historyHttpCode ?: 0,
                )
                historyCount == 0 ->
                    stringResource(R.string.guest_history_empty)
                else -> stringResource(
                    R.string.guest_history_ok,
                    historyCount,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(
                R.string.guest_last,
                healthTime(current.checkedAtEpochMs),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
    OutlinedButton(
        onClick = { viewModel.runProbe() },
        enabled = !running,
    ) {
        Text(
            stringResource(
                if (running) R.string.guest_checking else R.string.guest_check,
            ),
        )
    }
}

/** The one door's quiet status line: waiting, exchanging, failure. */
@Composable
private fun DesktopStatusText(state: DesktopSignInViewModel.State) {
    val text = when (state) {
        DesktopSignInViewModel.State.Idle -> null
        DesktopSignInViewModel.State.Waiting ->
            stringResource(R.string.desktop_waiting)
        DesktopSignInViewModel.State.Exchanging ->
            stringResource(R.string.desktop_exchanging)
        is DesktopSignInViewModel.State.Failed -> state.message
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state is DesktopSignInViewModel.State.Failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

/**
 * Kesehatan Mesin: the Radar's verdicts in plain words — one row per
 * persona, a coloured dot, when it was measured and on what network.
 */
@Composable
internal fun EngineHealthPanel(
    viewModel: EngineHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.health_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.health_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        if (state.reports.isEmpty()) {
            Text(
                stringResource(R.string.health_never),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        } else {
            state.reports.forEach { report ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(healthColor(report.state)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${healthPersonaLabel(report.persona)} — ${healthLabel(report.state)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(
                                R.string.health_last,
                                healthTime(report.checkedAtEpochMs),
                                report.networkClass,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // Langkah 7, mechanism 7: the SABR road's standing — the
        // remote config's switch and the governance's verdict for
        // this network class. No silent deaths: a road the
        // governance disabled says so here, with its evidence.
        val sabrGovernance = state.sabrGovernance
        if (sabrGovernance != null) {
            Text(
                stringResource(R.string.health_sabr_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                when {
                    !state.sabrConfigEnabled -> stringResource(
                        R.string.health_sabr_config_off,
                    )
                    sabrGovernance.disabled -> stringResource(
                        R.string.health_sabr_governed,
                        sabrGovernance.networkClass,
                        sabrGovernance.attempts,
                        sabrGovernance.failures,
                    )
                    else -> stringResource(
                        R.string.health_sabr_active,
                        sabrGovernance.networkClass,
                        sabrGovernance.attempts,
                        sabrGovernance.failures,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        OutlinedButton(
            onClick = { viewModel.check() },
            enabled = !state.isChecking,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                stringResource(
                    if (state.isChecking) {
                        R.string.health_checking
                    } else {
                        R.string.health_check
                    },
                ),
            )
        }
    }
}

/**
 * The floor's machine label reads as what it is — the spare tire,
 * checked like every other road but never part of the ladder.
 */
@Composable
private fun healthPersonaLabel(persona: String): String =
    if (persona == com.bunny.engine.lontar.LontarEngine.FLOOR_PERSONA_NAME) {
        stringResource(R.string.health_floor_name)
    } else {
        persona
    }

private fun healthColor(state: String): Color = when (state) {
    "SEHAT" -> Color(0xFF43A047)
    "TERGERBANG_GVS" -> Color(0xFFFB8C00)
    "DITOLAK_PLAYER" -> Color(0xFFE53935)
    else -> Color(0xFF9E9E9E)
}

@Composable
private fun healthLabel(state: String): String = stringResource(
    when (state) {
        "SEHAT" -> R.string.health_state_sehat
        "TERGERBANG_GVS" -> R.string.health_state_gvs
        "DITOLAK_PLAYER" -> R.string.health_state_ditolak
        "GAGAL_JARINGAN" -> R.string.health_state_jaringan
        else -> R.string.health_state_unknown
    },
)

private fun healthTime(epochMs: Long): String =
    java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale("id", "ID"))
        .format(java.util.Date(epochMs))
