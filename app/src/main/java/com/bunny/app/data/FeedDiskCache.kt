package com.bunny.app.data

import android.content.Context
import com.bunny.core.model.VideoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Salinan terakhir Beranda di disk, supaya aplikasi yang baru dibuka
 * langsung punya isi sambil menunggu jaringan. Hanya cermin dari
 * daftar yang pernah tampil; tidak ada data baru di sini.
 */
@Singleton
class FeedDiskCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    // Nama berkas membawa bahasa konten: cache berbahasa lama tidak
    // ikut tampil sesudah bahasa perangkat berganti.
    private val file = File(
        context.filesDir,
        "feed-cache-v1-" +
            com.bunny.app.i18n.ContentLocale.fromDevice().hl
                .replace(Regex("[^A-Za-z0-9]"), "_") +
            ".json",
    )

    suspend fun load(): List<VideoItem> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext emptyList()
            val array = JSONArray(file.readText())
            val out = ArrayList<VideoItem>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                out += VideoItem(
                    id = id,
                    title = o.optString("title"),
                    channelId = o.optString("channelId").ifEmpty { null },
                    channelName = o.optString("channelName"),
                    channelAvatarUrl = o.optString("channelAvatarUrl").ifEmpty { null },
                    thumbnailUrl = o.optString("thumbnailUrl").ifEmpty { null },
                    durationSeconds =
                        if (o.has("durationSeconds")) o.optLong("durationSeconds") else null,
                    viewCount =
                        if (o.has("viewCount")) o.optLong("viewCount") else null,
                    viewCountText = o.optString("viewCountText").ifEmpty { null },
                    uploadedText = o.optString("uploadedText").ifEmpty { null },
                    isLive = o.optBoolean("isLive", false),
                    reason = o.optString("reason").ifEmpty { null },
                )
            }
            out
        } catch (e: Exception) {
            // Cache rusak atau tak terbaca = anggap kosong.
            emptyList()
        }
    }

    suspend fun save(items: List<VideoItem>) {
        withContext(Dispatchers.IO) {
            try {
                val array = JSONArray()
                for (v in items.asSequence().filter { !it.isLive }.take(MAX_ITEMS)) {
                    val o = JSONObject()
                    o.put("id", v.id)
                    o.put("title", v.title)
                    v.channelId?.let { o.put("channelId", it) }
                    o.put("channelName", v.channelName)
                    v.channelAvatarUrl?.let { o.put("channelAvatarUrl", it) }
                    v.thumbnailUrl?.let { o.put("thumbnailUrl", it) }
                    v.durationSeconds?.let { o.put("durationSeconds", it) }
                    v.viewCount?.let { o.put("viewCount", it) }
                    v.viewCountText?.let { o.put("viewCountText", it) }
                    v.uploadedText?.let { o.put("uploadedText", it) }
                    o.put("isLive", v.isLive)
                    v.reason?.let { o.put("reason", it) }
                    array.put(o)
                }
                // Tulis ke berkas sementara lalu ganti, agar tidak
                // pernah tersisa berkas setengah jadi.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(array.toString())
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                // Cache hanya percepatan; gagal menulis tidak apa-apa.
            }
        }
    }

    private companion object {
        const val MAX_ITEMS = 40
    }
}
