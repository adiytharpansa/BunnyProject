package com.bunny.app.presentation.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bunny.app.R
import com.bunny.app.data.AuthRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onDownloadsClick: () -> Unit = {},
    onPersonalClick: (String) -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val authState by viewModel.state.collectAsState()
    // Satu Ketuk: the consent sheet belongs to the system; the screen
    // only launches the PendingIntent the authorization call returns.
    val oneTap by viewModel.oneTap.collectAsState()
    val verdicts by viewModel.verdicts.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current
        as? android.app.Activity
    val consentLauncher = androidx.activity.compose
        .rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .StartIntentSenderForResult(),
        ) { result ->
            if (activity != null) {
                viewModel.onConsentResult(activity, result.data)
            }
        }
    androidx.compose.runtime.LaunchedEffect(oneTap) {
        val pending = oneTap as? AuthViewModel.OneTap.NeedsConsent
            ?: return@LaunchedEffect
        viewModel.consumeConsent()
        consentLauncher.launch(
            androidx.activity.result.IntentSenderRequest
                .Builder(pending.intentSender).build(),
        )
    }
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
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(56.dp),
                            )
                        }
                    }
                    Text(
                        state.name ?: stringResource(R.string.profile_account_fallback),
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
                    // Jalan A: the account's real personal layer,
                    // served by the official Data API.
                    OutlinedButton(onClick = { onPersonalClick("subs") }) {
                        Text(stringResource(R.string.personal_subs_button))
                    }
                    OutlinedButton(onClick = { onPersonalClick("latest") }) {
                        Text(stringResource(R.string.personal_latest_button))
                    }
                    OutlinedButton(onClick = { onPersonalClick("liked") }) {
                        Text(stringResource(R.string.personal_liked_button))
                    }
                    OutlinedButton(onClick = { viewModel.signOut() }) {
                        Text(stringResource(R.string.profile_signout))
                    }
                }

                AuthRepository.State.SignedOut -> {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
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
                        stringResource(R.string.profile_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.profile_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    val busy = oneTap is AuthViewModel.OneTap.Working ||
                        oneTap is AuthViewModel.OneTap.Probing
                    Button(
                        onClick = {
                            if (activity != null) viewModel.beginOneTap(activity)
                        },
                        enabled = !busy,
                    ) {
                        Text(
                            stringResource(
                                when (oneTap) {
                                    is AuthViewModel.OneTap.Probing ->
                                        R.string.onetap_probing
                                    is AuthViewModel.OneTap.Working ->
                                        R.string.onetap_working
                                    else -> R.string.profile_login_button
                                },
                            ),
                        )
                    }
                    (oneTap as? AuthViewModel.OneTap.Failed)?.let { failed ->
                        Text(
                            failed.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        stringResource(R.string.profile_login_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (verdicts.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.onetap_verdicts_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    verdicts.forEach { verdict ->
                        // Local copy: a public property from another
                        // module cannot be smart-cast (CI #113).
                        val status = verdict.httpStatus
                        Text(
                            when {
                                verdict.accepted -> stringResource(
                                    R.string.onetap_verdict_accepted,
                                    verdict.contextName,
                                    verdict.accountName ?: "",
                                )
                                status != null -> stringResource(
                                    R.string.onetap_verdict_rejected,
                                    verdict.contextName,
                                    status,
                                )
                                else -> stringResource(
                                    R.string.onetap_verdict_network,
                                    verdict.contextName,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            OutlinedButton(onClick = onDownloadsClick) {
                Text(stringResource(R.string.profile_downloads_button))
            }
            EngineHealthPanel()
        }
    }
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
