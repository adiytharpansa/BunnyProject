package com.bunny.app.bug

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BugSeverity(val label: String) {
    FATAL("Crash: aplikasi tertutup paksa"),
    ANR("ANR: aplikasi tidak merespons"),
    CRITICAL("Kritis: fitur inti gagal"),
}

data class BugReport(
    val id: String,
    val time: Long,
    val severity: BugSeverity,
    val source: String,
    val title: String,
    val detail: String,
    val breadcrumbs: List<String>,
    val device: String,
) {
    fun formatTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))

    fun toText(): String = buildString {
        appendLine("[${severity.name}] $title")
        appendLine("Waktu     : ${formatTime()}")
        appendLine("Sumber    : $source")
        appendLine("Perangkat : $device")
        appendLine()
        appendLine(detail)
        if (breadcrumbs.isNotEmpty()) {
            appendLine()
            appendLine("--- Jejak terakhir ---")
            breadcrumbs.forEach { appendLine(it) }
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("time", time)
        put("severity", severity.name)
        put("source", source)
        put("title", title)
        put("detail", detail)
        put("device", device)
        put("breadcrumbs", JSONArray(breadcrumbs))
    }.toString()

    companion object {
        fun fromJson(json: String): BugReport {
            val o = JSONObject(json)
            val b = o.optJSONArray("breadcrumbs") ?: JSONArray()
            return BugReport(
                id = o.getString("id"),
                time = o.getLong("time"),
                severity = runCatching { BugSeverity.valueOf(o.getString("severity")) }
                    .getOrDefault(BugSeverity.CRITICAL),
                source = o.optString("source"),
                title = o.optString("title"),
                detail = o.optString("detail"),
                device = o.optString("device"),
                breadcrumbs = List(b.length()) { b.getString(it) },
            )
        }
    }
}
