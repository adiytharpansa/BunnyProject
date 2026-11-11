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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import coil.compose.AsyncImage
import com.bunny.app.R
import com.bunny.app.data.AuthRepository

/**
 * Profile. Sign-in is ONE door (unified 2026-11-12 on the user's
 * order — "satukan jadi satu tombol", "jangan pernah ada
 * cadangan"): the "Masuk dengan Google" button opens Google's own
 * page in the system browser sheet (Satu Pintu, Desktop client +
 * PKCE), the returning key is proven at the official Data API, and
 * that is the whole sign-in. Every earlier door — the Satu Ketuk
 * system sheet (its key was refused by the internal doors), the TV
 * code (kept briefly as an optional upgrade, then removed), the
 * dead Fase 5 cookie harvest — is gone, not parked as a spare.
 *
 * Signed in, the personal rooms it powers are the three Data API
 * surfaces: the account's own subscriptions, the newest uploads
 * from them, and the videos the account liked. Server home and
 * watch history are not among them — no honest door to them was
 * ever proven, and Bunny shows nothing it cannot serve for real.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onPersonalClick: (String) -> Unit = {},
    onDownloadsClick: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
    desktopViewModel: DesktopSignInViewModel = hiltViewModel(),
) {
    val authState by viewModel.state.collectAsState()
    val desktopState by desktopViewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.profile_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = authState) {
                is AuthRepository.State.SignedIn -> {
                    // The account's REAL photo and name, from YouTube's
                    // own account response — no letter stand-ins.
                    if (state.avatarUrl != null) {
                        AsyncImage(
                            model = state.avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme
                                    .onPrimaryContainer,
                                modifier = Modifier.size(56.dp),
                            )
                        }
                    }
                    Text(
                        state.name
                            ?: stringResource(R.string.profile_account_fallback),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.profile_signed_in_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = { onPersonalClick("subs") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.personal_subs_button))
                    }
                    OutlinedButton(
                        onClick = { onPersonalClick("latest") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.personal_latest_button))
                    }
                    OutlinedButton(
                        onClick = { onPersonalClick("liked") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.personal_liked_button))
                    }
                    TextButton(onClick = { viewModel.signOut() }) {
                        Text(stringResource(R.string.profile_signout))
                    }
                }
                AuthRepository.State.SignedOut -> {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.profile_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.profile_login_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
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
                }
            }
            OutlinedButton(onClick = onDownloadsClick) {
                Text(stringResource(R.string.profile_downloads_button))
            }
            GuestWarmingPanel()
            EngineHealthPanel()
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
private fun GuestWarmingPanel(
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
        Text(
            when {
                current.historyItemCount == null -> stringResource(
                    R.string.guest_history_refused,
                    current.historyHttpCode ?: 0,
                )
                current.historyItemCount == 0 ->
                    stringResource(R.string.guest_history_empty)
                else -> stringResource(
                    R.string.guest_history_ok,
                    current.historyItemCount,
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
private fun EngineHealthPanel(
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
                            "${report.persona} — ${healthLabel(report.state)}",
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
