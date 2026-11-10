package com.bunny.app.presentation.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bunny.app.R
import com.bunny.app.presentation.components.VideoCard
import com.bunny.core.model.DataCost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVideoClick: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val jatah by viewModel.jatah.collectAsState()
    var showQuotaDialog by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )

                state.error != null -> Column(
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

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        JatahBar(
                            jatah = jatah,
                            onSetQuota = { showQuotaDialog = true },
                        )
                    }
                    items(state.items, key = { it.id }) { item ->
                        VideoCard(item = item, onClick = { onVideoClick(item.id) })
                    }
                }
            }
        }
    }
}

/**
 * Jatah bar: this month's estimated usage, and the budget when one is
 * set. Estimates, not metering — the copy says so.
 */
@Composable
private fun JatahBar(
    jatah: JatahState,
    onSetQuota: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_jatah_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSetQuota) {
                Text(stringResource(R.string.home_jatah_set))
            }
        }
        val usedText = stringResource(
            R.string.home_jatah_used,
            DataCost.formatSize(jatah.usedBytes),
        )
        Text(
            text = if (jatah.quotaBytes != null) {
                usedText + " " + stringResource(
                    R.string.home_jatah_of,
                    DataCost.formatSize(jatah.quotaBytes),
                )
            } else {
                usedText
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (jatah.quotaBytes != null && jatah.quotaBytes > 0) {
            val fraction = (jatah.usedBytes.toFloat() / jatah.quotaBytes.toFloat())
                .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
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
