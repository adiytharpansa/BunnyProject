package com.bunny.app.bug

import androidx.annotation.StringRes
import com.bunny.app.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MinorCategory(@StringRes val labelRes: Int) {
    LAG(R.string.minor_lag),
    MEMORY(R.string.minor_memory),
    NETWORK(R.string.minor_network),
    PLAYER(R.string.minor_player),
    IMAGE(R.string.minor_image),
    CAUGHT(R.string.minor_caught),
}

/** Satu jenis masalah ringan; kejadian berulang digabung lewat [count]. */
data class MinorIssue(
    val key: String,
    val category: MinorCategory,
    val source: String,
    val title: String,
    val detail: String,
    val firstTime: Long,
    val lastTime: Long,
    val count: Int,
) {
    fun formatLastTime(): String = fmt(lastTime)

    fun toText(): String = buildString {
        appendLine("[MINOR/${category.name}] $title")
        appendLine("Sumber  : $source")
        appendLine("Jumlah  : $count kali")
        appendLine("Pertama : ${fmt(firstTime)}")
        appendLine("Terakhir: ${fmt(lastTime)}")
        if (detail.isNotBlank()) {
            appendLine()
            appendLine(detail)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("category", category.name)
        put("source", source)
        put("title", title)
        put("detail", detail)
        put("firstTime", firstTime)
        put("lastTime", lastTime)
        put("count", count)
    }

    companion object {
        private fun fmt(t: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

        fun fromJson(o: JSONObject): MinorIssue = MinorIssue(
            key = o.getString("key"),
            category = runCatching { MinorCategory.valueOf(o.getString("category")) }
                .getOrDefault(MinorCategory.CAUGHT),
            source = o.optString("source"),
            title = o.optString("title"),
            detail = o.optString("detail"),
            firstTime = o.getLong("firstTime"),
            lastTime = o.getLong("lastTime"),
            count = o.optInt("count", 1),
        )
    }
}
