package com.bunny.app.bug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bunny.app.R
import kotlinx.coroutines.delay

/**
 * Taruh sekali di akar UI, di dalam Box (BoxScope). Bila tidak ada masalah ringan,
 * fungsi ini tidak menggambar apa pun. Setiap kali ada deteksi (baru atau
 * berulang), langsung muncul kartu di bawah layar selama 3,5 detik; ketuk kartu untuk
 * membuka daftar lengkap. Chip bernomor tetap ada selama masih ada temuan.
 * Tidak pernah memblokir layar (beda dengan BugReportHost).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.MinorIssueHost(
    bottomPadding: Dp = 0.dp,
) {
    val issues by MinorIssueRecorder.issues.collectAsState()
    var open by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<MinorIssue?>(null) }
    val context = LocalContext.current

    // Daftar habis (mis. "Hapus semua"): tutup lembar dan kartunya.
    LaunchedEffect(issues.isEmpty()) {
        if (issues.isEmpty()) {
            open = false
            banner = null
        }
    }

    // SETIAP deteksi (baru maupun berulang) tampil langsung. Kejadian yang datang
    // beruntun diantre dan ditampilkan satu per satu, masing-masing 3,5 detik.
    // Temuan lama dari disk tidak masuk antrean (hanya chip).
    LaunchedEffect(Unit) {
        MinorIssueRecorder.events.collect { e ->
            banner = e
            delay(3_500)
            banner = null
        }
    }

    if (issues.isEmpty()) return

    banner?.let { b ->
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = bottomPadding + 56.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
        ) {
            Column(
                Modifier
                    .clickable {
                        banner = null
                        open = true
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    "${stringResource(b.category.labelRes)} · " +
                        stringResource(R.string.minor_count, b.count),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    b.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (b.detail.isNotBlank()) {
                    Text(
                        b.detail.trim(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    val desc = stringResource(R.string.minor_chip_desc, issues.size)
    Surface(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 12.dp, bottom = bottomPadding + 12.dp)
            .semantics { contentDescription = desc },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(issues.size.toString(), style = MaterialTheme.typography.labelMedium)
        }
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Text(
                    stringResource(R.string.minor_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.minor_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(issues, key = { it.key }) { issue -> MinorIssueRow(issue) }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { copyText(context, MinorIssueRecorder.reportText()) }) {
                        Text(stringResource(R.string.minor_copy_all))
                    }
                    TextButton(onClick = { shareText(context, MinorIssueRecorder.reportText()) }) {
                        Text(stringResource(R.string.minor_share_all))
                    }
                    TextButton(onClick = { MinorIssueRecorder.dismissAll() }) {
                        Text(stringResource(R.string.minor_clear_all))
                    }
                }
            }
        }
    }
}

@Composable
private fun MinorIssueRow(issue: MinorIssue) {
    var expanded by remember(issue.key) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
    ) {
        Text(
            issue.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${stringResource(issue.category.labelRes)} · ${issue.source} · " +
                "${stringResource(R.string.minor_count, issue.count)} · ${issue.formatLastTime()}",
            style = MaterialTheme.typography.labelSmall,
        )
        if (expanded) {
            if (issue.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    issue.detail.take(1_500),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
            TextButton(onClick = { MinorIssueRecorder.dismiss(issue.key) }) {
                Text(stringResource(R.string.action_delete))
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Bunny minor issues", text))
    Toast.makeText(context, R.string.bug_copied, Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.minor_subject))
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.bug_share_title)),
    )
}
