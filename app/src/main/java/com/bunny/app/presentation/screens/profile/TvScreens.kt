package com.bunny.app.presentation.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.R
import com.bunny.app.data.AuthRepository
import com.bunny.app.data.DeviceCode
import com.bunny.app.data.PollResult
import com.bunny.app.data.TvAuth
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.BearerContextVerdict
import com.bunny.core.model.VideoItem
import com.bunny.engine.lontar.LontarException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Layar Besar sign-in: ask Google for a device code, show it big,
 * and poll quietly until the user approves on another screen. The
 * granted token is not trusted on arrival — it is proven against
 * accounts_list (the engine probe), and only the winning identity
 * is stored.
 */
@HiltViewModel
class TvLoginViewModel @Inject constructor(
    private val tvAuth: TvAuth,
    private val auth: AuthRepository,
    private val backend: ExtractionBackend,
) : ViewModel() {

    sealed interface TvState {
        data object Idle : TvState
        data object Requesting : TvState
        data class ShowingCode(val code: DeviceCode) : TvState
        data object Granted : TvState
        data object Denied : TvState
        data object Expired : TvState
        data class Failed(val message: String) : TvState
    }

    private val _state = MutableStateFlow<TvState>(TvState.Idle)
    val state: StateFlow<TvState> = _state.asStateFlow()

    private val _verdicts = MutableStateFlow<List<BearerContextVerdict>>(emptyList())
    val verdicts: StateFlow<List<BearerContextVerdict>> = _verdicts.asStateFlow()

    val tvSignedIn: StateFlow<Boolean> = auth.tvSignedIn

    private var pollJob: Job? = null

    fun start() {
        if (_state.value is TvState.Requesting ||
            _state.value is TvState.ShowingCode
        ) {
            return
        }
        if (!com.bunny.app.data.TvClientConfig.isConfigured) {
            _state.value = TvState.Failed(
                "Build ini belum membawa klien TV.",
            )
            return
        }
        _state.value = TvState.Requesting
        viewModelScope.launch {
            val code = try {
                tvAuth.requestCode()
            } catch (e: Exception) {
                _state.value = TvState.Failed(
                    e.message ?: "Gagal meminta kode ke Google.",
                )
                return@launch
            }
            _state.value = TvState.ShowingCode(code)
            poll(code)
        }
    }

    fun cancel() {
        pollJob?.cancel()
        pollJob = null
        _state.value = TvState.Idle
    }

    private fun poll(code: DeviceCode) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var intervalSec = code.intervalSec
            val deadline = System.currentTimeMillis() +
                code.expiresInSec * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(intervalSec * 1000L)
                when (val result = tvAuth.poll(code.deviceCode)) {
                    is PollResult.Granted -> {
                        proveAndStore(result)
                        return@launch
                    }
                    PollResult.Pending -> Unit
                    PollResult.SlowDown -> intervalSec += 5L
                    PollResult.Denied -> {
                        _state.value = TvState.Denied
                        return@launch
                    }
                    PollResult.Expired -> {
                        _state.value = TvState.Expired
                        return@launch
                    }
                    is PollResult.Failed -> {
                        _state.value = TvState.Failed(result.message)
                        return@launch
                    }
                }
            }
            _state.value = TvState.Expired
        }
    }

    private suspend fun proveAndStore(granted: PollResult.Granted) {
        tvAuth.adoptAccessToken(granted.accessToken, granted.expiresInSec)
        val verdicts = try {
            backend.probeBearerContexts(granted.accessToken)
        } catch (e: Exception) {
            emptyList()
        }
        _verdicts.value = verdicts
        val winner = verdicts.firstOrNull { it.accepted }
        if (winner != null) {
            auth.signInTv(
                refreshToken = granted.refreshToken,
                name = winner.accountName,
                avatarUrl = winner.avatarUrl,
            )
            _state.value = TvState.Granted
        } else {
            _state.value = TvState.Failed(
                "Kode disetujui, tetapi pintu TV tetap menolak kuncinya.",
            )
        }
    }
}

@HiltViewModel
class TvFeedViewModel @Inject constructor(
    private val tvAuth: TvAuth,
    private val backend: ExtractionBackend,
) : ViewModel() {

    private val _state =
        MutableStateFlow<LoadState<List<VideoItem>>>(LoadState.Loading)
    val state: StateFlow<LoadState<List<VideoItem>>> = _state.asStateFlow()

    fun load(mode: String) {
        _state.value = LoadState.Loading
        viewModelScope.launch {
            if (!tvAuth.ensureFresh()) {
                _state.value = LoadState.Failed(401)
                return@launch
            }
            _state.value = try {
                LoadState.Ready(
                    when (mode) {
                        "tvsubs" -> backend.getTvSubscriptionsFeed()
                        "tvhistory" -> backend.getTvHistory()
                        else -> backend.getTvHomeFeed()
                    },
                )
            } catch (e: LontarException) {
                LoadState.Failed(e.httpCode)
            } catch (e: Exception) {
                LoadState.Failed(null)
            }
        }
    }
}

/**
 * One of the three TV personal surfaces. The gate is the TV
 * session's freshness; a refusal asks for a fresh code from
 * Profile — in words, on screen.
 */
@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvFeedScreen(
    mode: String,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    viewModel: TvFeedViewModel = hiltViewModel(),
) {
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(mode, attempt) { viewModel.load(mode) }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            when (mode) {
                                "tvsubs" -> R.string.tv_subs_title
                                "tvhistory" -> R.string.tv_history_title
                                else -> R.string.tv_home_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            val state by viewModel.state.collectAsState()
            when (val current = state) {
                is LoadState.Loading -> LoadingBox()
                is LoadState.Failed -> {
                    if (current.httpCode == 401 || current.httpCode == 403) {
                        TvExpiredBox { attempt++ }
                    } else {
                        FailedBox(current.httpCode) { attempt++ }
                    }
                }
                is LoadState.Ready -> {
                    if (current.data.isEmpty()) {
                        EmptyBox(
                            when (mode) {
                                "tvsubs" -> R.string.tv_empty_subs
                                "tvhistory" -> R.string.tv_empty_history
                                else -> R.string.tv_empty_home
                            },
                        )
                    } else {
                        LazyColumn {
                            items(current.data) { item ->
                                com.bunny.app.presentation.components.VideoCard(
                                    item = item,
                                    onClick = { onVideoClick(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvExpiredBox(retry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.tv_need_login),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = retry) {
            Text(stringResource(R.string.personal_retry))
        }
    }
}
