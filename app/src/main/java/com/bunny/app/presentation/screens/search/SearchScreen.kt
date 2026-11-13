package com.bunny.app.presentation.screens.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bunny.app.R

/**
 * Halaman pencarian ala YouTube.
 *  - Mengetik: riwayat cocok + saran langsung (tebal di bagian lanjutan),
 *    tombol ↖ mengisi kolom tanpa mencari.
 *  - Hasil: daftar video penuh lebar, filter, gulir tak terbatas.
 *  - Menyentuh kolom saat hasil tampil membuka saran lagi; kembali dari
 *    saran memulihkan hasil.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val recent by viewModel.recentSearches.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var showFilterSheet by remember { mutableStateOf(false) }
    var historyToRemove by remember { mutableStateOf<String?>(null) }

    // Teks kolom disimpan sebagai TextFieldValue agar kursor selalu di
    // ujung saat teks diisi dari luar (tombol ↖, riwayat, suara).
    var field by remember {
        mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length)))
    }
    LaunchedEffect(state.query) {
        if (state.query != field.text) {
            field = TextFieldValue(state.query, TextRange(state.query.length))
        }
    }

    // Keyboard hanya terbuka otomatis pada halaman baru (belum ada hasil).
    LaunchedEffect(Unit) {
        if (state.submittedQuery == null) focusRequester.requestFocus()
    }
    // Pencarian atau filter baru → daftar kembali ke atas.
    LaunchedEffect(state.submittedQuery, state.filter) {
        listState.scrollToItem(0)
    }

    val submit: (String) -> Unit = { text ->
        keyboard?.hide()
        focusManager.clearFocus()
        viewModel.submit(text)
    }

    val leaveEditing = state.submittedQuery != null && state.isEditing
    val handleBack: () -> Unit = {
        if (leaveEditing) {
            keyboard?.hide()
            focusManager.clearFocus()
            viewModel.backToResults()
        } else {
            onBack()
        }
    }
    BackHandler(enabled = leaveEditing, onBack = handleBack)

    // Pencarian suara memakai pengenal ucapan bawaan sistem (tanpa izin).
    val voicePrompt = stringResource(R.string.search_voice_prompt)
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) submit(spoken)
        }
    }
    val startVoice: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // Perangkat tanpa pengenal ucapan: ikon mikrofon diam saja.
        }
    }

    Scaffold(
        topBar = {
            SearchTopBar(
                field = field,
                onFieldChange = { value ->
                    val textChanged = value.text != field.text
                    field = value
                    if (textChanged) viewModel.onQueryChange(value.text)
                },
                focusRequester = focusRequester,
                onBack = handleBack,
                onFocused = viewModel::onSearchFieldFocused,
                onSubmit = { submit(field.text) },
                onClear = {
                    field = TextFieldValue("")
                    viewModel.onQueryChange("")
                    focusRequester.requestFocus()
                },
                onVoice = startVoice,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.showResults) {
                SearchFilterBar(
                    filter = state.filter,
                    onFilterChange = viewModel::applyFilter,
                    onOpenSheet = { showFilterSheet = true },
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    !state.showResults -> SuggestionList(
                        query = state.query,
                        recent = recent,
                        remote = state.suggestions,
                        onSubmit = submit,
                        onFill = { text ->
                            field = TextFieldValue(text, TextRange(text.length))
                            viewModel.onQueryChange(text)
                        },
                        onLongPressHistory = { historyToRemove = it },
                        onClearHistory = viewModel::clearHistory,
                    )

                    state.isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )

                    state.error != null && state.results.isEmpty() -> Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.search_error),
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = viewModel::retry) {
                            Text(stringResource(R.string.retry))
                        }
                    }

                    else -> ResultsList(
                        state = state,
                        listState = listState,
                        onVideoClick = onVideoClick,
                        onLoadMore = viewModel::loadMore,
                    )
                }
            }
        }
    }

    if (showFilterSheet) {
        SearchFilterSheet(
            initial = state.filter,
            onApply = {
                showFilterSheet = false
                viewModel.applyFilter(it)
            },
            onDismiss = { showFilterSheet = false },
        )
    }

    historyToRemove?.let { term ->
        AlertDialog(
            onDismissRequest = { historyToRemove = null },
            title = { Text(stringResource(R.string.search_remove_history_title)) },
            text = { Text(stringResource(R.string.search_remove_history_message, term)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeRecent(term)
                    historyToRemove = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { historyToRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// ------------------------------------------------------------------ bar atas

@Composable
private fun SearchTopBar(
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onFocused: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onVoice: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.player_back),
            )
        }
        TextField(
            value = field,
            onValueChange = onFieldChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocused() },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            trailingIcon = {
                if (field.text.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.search_clear_query),
                        )
                    }
                } else {
                    IconButton(onClick = onVoice) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.search_voice),
                        )
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
    }
}

// ---------------------------------------------------------------------- saran

private data class SuggestionItem(val text: String, val isHistory: Boolean)

private fun buildSuggestionItems(
    query: String,
    recent: List<String>,
    remote: List<String>,
): List<SuggestionItem> {
    val typed = query.trim()
    if (typed.isEmpty()) return recent.map { SuggestionItem(it, isHistory = true) }
    // Riwayat yang cocok ikut tampil di atas, seperti YouTube.
    val history = recent
        .filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
        .take(3)
    val seen = history.map { it.lowercase() }.toMutableSet()
    val fromServer = remote.filter { seen.add(it.lowercase()) }
    return history.map { SuggestionItem(it, isHistory = true) } +
        fromServer.map { SuggestionItem(it, isHistory = false) }
}

@Composable
private fun SuggestionList(
    query: String,
    recent: List<String>,
    remote: List<String>,
    onSubmit: (String) -> Unit,
    onFill: (String) -> Unit,
    onLongPressHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val rows = remember(query, recent, remote) { buildSuggestionItems(query, recent, remote) }
    val typing = query.isNotBlank()

    if (rows.isEmpty() && !typing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.search_idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        if (!typing && rows.isNotEmpty()) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.search_recent),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = onClearHistory) {
                        Text(stringResource(R.string.search_clear_all))
                    }
                }
            }
        }
        items(rows, key = { "${it.isHistory}:${it.text}" }) { row ->
            SuggestionRow(
                row = row,
                query = query,
                onClick = { onSubmit(row.text) },
                onFill = { onFill(row.text) },
                onLongClick = if (row.isHistory) {
                    { onLongPressHistory(row.text) }
                } else {
                    null
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionRow(
    row: SuggestionItem,
    query: String,
    onClick: () -> Unit,
    onFill: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            if (row.isHistory) Icons.Filled.History else Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = highlightCompletion(row.text, query),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onFill) {
            Icon(
                Icons.Filled.NorthWest,
                contentDescription = stringResource(R.string.search_fill),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bagian yang sudah diketik normal, lanjutan saran dicetak tebal. */
private fun highlightCompletion(text: String, query: String): AnnotatedString {
    val typed = query.trim()
    return buildAnnotatedString {
        if (typed.isNotEmpty() && text.startsWith(typed, ignoreCase = true)) {
            append(text.substring(0, typed.length))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(typed.length))
            }
        } else {
            append(text)
        }
    }
}

// ---------------------------------------------------------------------- hasil

@Composable
private fun ResultsList(
    state: SearchUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onVideoClick: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    // Gulir tak terbatas: muat halaman berikutnya saat mendekati ujung.
    LaunchedEffect(nearEnd, state.results.size, state.nextPage, state.loadMoreFailed) {
        if (nearEnd && state.nextPage != null && !state.loadMoreFailed) onLoadMore()
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.results.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.search_empty, state.submittedQuery.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(state.results, key = { it.id }) { item ->
            SearchResultItem(item = item, onClick = { onVideoClick(item.id) })
        }
        if (state.isLoadingMore) {
            item(key = "loading_more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        } else if (state.loadMoreFailed) {
            item(key = "load_more_failed") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(onClick = onLoadMore) {
                        Text(stringResource(R.string.search_load_more_failed))
                    }
                }
            }
        }
    }
}
