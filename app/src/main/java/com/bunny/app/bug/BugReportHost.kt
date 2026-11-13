package com.bunny.app.bug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Taruh sekali di akar UI. Bila tidak ada bug, fungsi ini tidak menggambar apa pun.
 */
@Composable
fun BugReportHost() {
    val reports by BugRecorder.reports.collectAsState()
    val current = reports.firstOrNull() ?: return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { /* sengaja kosong: pengguna harus memilih tombol */ },
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(current.severity.label) },
        text = {
            Column {
                Text(
                    current.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                val more = if (reports.size > 1) " · +${reports.size - 1} laporan lain" else ""
                Text(
                    "${current.formatTime()} · ${current.source}$more",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    current.detail.take(4_000),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { copy(context, current) }) { Text("Salin") }
                TextButton(onClick = { share(context, current) }) { Text("Bagikan") }
                TextButton(onClick = { BugRecorder.dismiss(current.id) }) { Text("Tutup") }
            }
        },
    )
}

private fun copy(context: Context, r: BugReport) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Laporan bug Bunny", r.toText()))
    Toast.makeText(context, "Laporan disalin", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, r: BugReport) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Laporan bug Bunny: ${r.severity.name}")
        putExtra(Intent.EXTRA_TEXT, r.toText())
    }
    context.startActivity(Intent.createChooser(send, "Bagikan laporan"))
}
