package com.bunny.app.presentation.screens.search

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.bunny.app.R
import com.bunny.app.presentation.components.VideoCard

/**
 * The search page: its own screen like the official app's
 * MediaSearchActivity → ResultsActivity pair (REA dissection
 * 2026-11-10). Before submitting it shows recent searches (empty
 * query) or live suggestions (while typing); after submitting it
 * shows paged results.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val recent by viewModel.recentSearches.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Kembali",
                    )
                }
                TextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { viewModel.submit() },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.search_clear_query),
                        )
                    }
                }
            }
        },
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

                state.submittedQuery != null && state.error != null &&
                    state.results.isEmpty() -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.search_error))
                    Button(onClick = { viewModel.submit(state.submittedQuery!!) }) {
                        Text(stringResource(R.string.retry))
                    }
                }

                state.submittedQuery != null -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (state.results.isEmpty()) {
                        item {
                            Text(
                                stringResource(
                                    R.string.search_empty,
                                    state.submittedQuery!!,
                                ),
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(state.results, key = { it.id }) { item ->
                        VideoCard(item = item, onClick = { onVideoClick(item.id) })
                    }
                    if (state.nextPage != null) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator()
                                } else {
                                    OutlinedButton(onClick = viewModel::loadMore) {
                                        Text(stringResource(R.string.search_more))
                                    }
                                }
                            }
                        }
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    if (state.query.isBlank()) {
                        if (recent.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.search_recent),
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 8.dp,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            items(recent) { term ->
                                SuggestionRow(
                                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                                    text = term,
                                    onClick = { viewModel.submit(term) },
                                )
                            }
                            item {
                                TextButton(
                                    onClick = viewModel::clearHistory,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                ) {
                                    Text(stringResource(R.string.search_clear_history))
                                }
                            }
                        }
                    } else {
                        items(state.suggestions) { suggestion ->
                            SuggestionRow(
                                icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                text = suggestion,
                                onClick = { viewModel.submit(suggestion) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon()
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
