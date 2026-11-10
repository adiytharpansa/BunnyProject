package com.bunny.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bunny.app.playback.CrashRecorder
import com.bunny.app.playback.PlaybackDiagnostics
import com.bunny.app.presentation.screens.home.HomeScreen
import com.bunny.app.presentation.screens.player.PlayerScreen
import com.bunny.app.presentation.theme.BunnyTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.media3.common.util.UnstableApi

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BunnyTheme {
                // Fase 2 navigation is deliberately a single state: home, or
                // one open video. A nav graph earns its place in Fase 3 when
                // Terbit and channel pages exist.
                var openVideoId by remember { mutableStateOf<String?>(null) }
                var crashReport by remember {
                    mutableStateOf(CrashRecorder.read(this@MainActivity))
                }
                val videoId = openVideoId
                Box(modifier = Modifier.fillMaxSize()) {
                    if (videoId == null) {
                        HomeScreen(onVideoClick = { openVideoId = it })
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
                    } else {
                        PlayerScreen(
                            videoId = videoId,
                            onBack = { openVideoId = null },
                        )
                    }
                }
            }
        }
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
