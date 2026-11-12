package com.bunny.app.presentation.screens.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.bunny.app.R
import com.bunny.app.data.DownloadEntity
import com.bunny.app.data.DownloadManager
import com.bunny.core.model.DataCost

/**
 * Unduhan — hasil Panen Bertingkat. Mengetuk baris membuka video di
 * pemutar, yang memutarnya dari berkas lokal. Tahap setiap unduhan
 * ditampilkan apa adanya: audio dulu, video menunggu Wi-Fi.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsState()
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.downloads_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            Text(
                stringResource(R.string.downloads_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(downloads, key = { it.videoId }) { item ->
                    DownloadRow(
                        item = item,
                        onClick = { onVideoClick(item.videoId) },
                        onRetry = { viewModel.retry(item.videoId) },
                        onDelete = { viewModel.delete(item.videoId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadEntity,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 120.dp, height = 68.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stageText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.stage == DownloadManager.STAGE_FAILED) {
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.downloads_retry),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.downloads_delete),
                )
            }
        }
        if (item.stage == DownloadManager.STAGE_AUDIO ||
            item.stage == DownloadManager.STAGE_VIDEO
        ) {
            if (item.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = {
                        (item.progressBytes.toFloat() / item.totalBytes)
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun stageText(item: DownloadEntity): String = when (item.stage) {
    DownloadManager.STAGE_AUDIO ->
        if (item.totalBytes > 0) {
            stringResource(
                R.string.downloads_row_audio_progress_size,
                DataCost.formatSize(item.progressBytes),
                DataCost.formatSize(item.totalBytes),
            )
        } else {
            stringResource(R.string.downloads_row_audio_progress)
        }
    DownloadManager.STAGE_WAITING_WIFI -> stringResource(
        R.string.downloads_row_waiting_wifi,
        DataCost.formatSize(item.audioBytes),
    )
    DownloadManager.STAGE_VIDEO ->
        if (item.totalBytes > 0) {
            stringResource(
                R.string.downloads_row_video_progress_size,
                DataCost.formatSize(item.progressBytes),
                DataCost.formatSize(item.totalBytes),
            )
        } else {
            stringResource(R.string.downloads_row_video_progress)
        }
    DownloadManager.STAGE_COMPLETE -> stringResource(
        R.string.downloads_row_complete,
        item.videoHeight,
        DataCost.formatSize(item.audioBytes + item.videoBytes),
    )
    DownloadManager.STAGE_FAILED ->
        stringResource(R.string.downloads_row_failed)
    else -> ""
}
