package com.bunny.app.presentation.screens.profile

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.bunny.app.R
import com.bunny.app.data.DataApiException
import com.bunny.app.data.PersonalDataRepository
import com.bunny.app.data.SubscriptionEntry
import com.bunny.app.presentation.components.VideoCard
import com.bunny.core.model.VideoItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Load state for the personal screens — failures stay honest. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Failed(val httpCode: Int?) : LoadState<Nothing>
    data class Ready<T>(val data: T) : LoadState<T>
}

@HiltViewModel
class PersonalViewModel @Inject constructor(
    private val repo: PersonalDataRepository,
) : ViewModel() {

    private val _subs =
        MutableStateFlow<LoadState<List<SubscriptionEntry>>>(LoadState.Loading)
    val subs: StateFlow<LoadState<List<SubscriptionEntry>>> = _subs.asStateFlow()

    private val _latest =
        MutableStateFlow<LoadState<List<VideoItem>>>(LoadState.Loading)
    val latest: StateFlow<LoadState<List<VideoItem>>> = _latest.asStateFlow()

    private val _liked =
        MutableStateFlow<LoadState<List<VideoItem>>>(LoadState.Loading)
    val liked: StateFlow<LoadState<List<VideoItem>>> = _liked.asStateFlow()

    fun loadSubs() = load(_subs) { repo.subscriptions() }

    fun loadLatest() = load(_latest) { repo.latestFeed() }

    fun loadLiked() = load(_liked) { repo.liked() }

    private fun <T> load(
        flow: MutableStateFlow<LoadState<T>>,
        block: suspend () -> T,
    ) {
        flow.value = LoadState.Loading
        viewModelScope.launch {
            flow.value = try {
                LoadState.Ready(block())
            } catch (e: DataApiException) {
                if (e.httpCode == 401) repo.invalidateToken()
                LoadState.Failed(e.httpCode)
            } catch (e: Exception) {
                LoadState.Failed(null)
            }
        }
    }
}

/**
 * Shared gate for the personal screens: a live token is fetched
 * silently first; only a refused fetch asks the user to sign in
 * again from Profile. [content] receives a retry trigger.
 */
@Composable
private fun PersonalGate(
    authViewModel: AuthViewModel,
    load: () -> Unit,
    content: @Composable (retry: () -> Unit) -> Unit,
) {
    val activity = LocalContext.current as? Activity
    var needLogin by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        if (activity == null) {
            needLogin = true
        } else {
            authViewModel.ensureToken(activity) { ok ->
                if (ok) {
                    needLogin = false
                    load()
                } else {
                    needLogin = true
                }
            }
        }
    }
    if (needLogin) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.personal_need_login),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { attempt++ }) {
                Text(stringResource(R.string.personal_retry))
            }
        }
    } else {
        content { attempt++ }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    onBack: () -> Unit,
    onChannelClick: (String, String) -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
    viewModel: PersonalViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.personal_subs_button)) },
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
            PersonalGate(authViewModel, load = { viewModel.loadSubs() }) { retry ->
                val state by viewModel.subs.collectAsState()
                when (val current = state) {
                    is LoadState.Loading -> LoadingBox()
                    is LoadState.Failed -> FailedBox(current.httpCode, retry)
                    is LoadState.Ready -> {
                        if (current.data.isEmpty()) {
                            EmptyBox(R.string.personal_empty_subs)
                        } else {
                            LazyColumn {
                                items(current.data) { entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onChannelClick(
                                                    entry.channelId,
                                                    entry.title,
                                                )
                                            }
                                            .padding(
                                                horizontal = 16.dp,
                                                vertical = 10.dp,
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement =
                                            Arrangement.spacedBy(12.dp),
                                    ) {
                                        if (entry.avatarUrl != null) {
                                            AsyncImage(
                                                model = entry.avatarUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(CircleShape),
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        MaterialTheme.colorScheme
                                                            .surfaceVariant,
                                                    ),
                                            )
                                        }
                                        Text(
                                            entry.title,
                                            style = MaterialTheme.typography
                                                .bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalFeedScreen(
    mode: String,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(),
    viewModel: PersonalViewModel = hiltViewModel(),
) {
    val isLatest = mode == "latest"
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isLatest) {
                                R.string.personal_latest_title
                            } else {
                                R.string.personal_liked_title
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
            PersonalGate(
                authViewModel,
                load = {
                    if (isLatest) viewModel.loadLatest() else viewModel.loadLiked()
                },
            ) { retry ->
                val state by (if (isLatest) {
                    viewModel.latest
                } else {
                    viewModel.liked
                }).collectAsState()
                when (val current = state) {
                    is LoadState.Loading -> LoadingBox()
                    is LoadState.Failed -> FailedBox(current.httpCode, retry)
                    is LoadState.Ready -> {
                        if (current.data.isEmpty()) {
                            EmptyBox(
                                if (isLatest) {
                                    R.string.personal_empty_feed
                                } else {
                                    R.string.personal_empty_liked
                                },
                            )
                        } else {
                            LazyColumn {
                                items(current.data) { item ->
                                    VideoCard(
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
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                stringResource(R.string.personal_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedBox(httpCode: Int?, retry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (httpCode != null) {
                stringResource(R.string.personal_failed, httpCode)
            } else {
                stringResource(R.string.personal_failed_network)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = retry) {
            Text(stringResource(R.string.personal_retry))
        }
    }
}

@Composable
private fun EmptyBox(textRes: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
