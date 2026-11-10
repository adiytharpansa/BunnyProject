package com.bunny.engine.lontar

import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.Page
import com.bunny.core.model.PlaybackStreams
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Lontar — Bunny's own YouTube engine, written from scratch for this project.
 *
 * It speaks InnerTube (the API YouTube's own apps use) directly over HTTPS:
 * no NewPipeExtractor, no YouTube.js, no third-party YouTube code at all.
 * Platform libraries only (OkHttp, coroutines, org.json).
 *
 * Fase 1 scope: session bootstrap, home (trending + seed mixture), search,
 * video details, and playback for streams that carry direct URLs. Ciphered
 * formats are Fase 2 (Lontar's own cipher solver) — see docs/DESIGN.md.
 */
class LontarEngine(
    private val httpClient: OkHttpClient,
) : ExtractionBackend {

    override val id: String = "lontar"

    private val sessionMutex = Mutex()
    @Volatile private var session: InnertubeSession? = null

    /** Player responses are shared by details + streams calls for one video. */
    private val playerCache = ConcurrentHashMap<String, JSONObject>()

    override suspend fun healthCheck(): String = try {
        ensureSession()
        "Mesin Lontar siap — sesi InnerTube aktif (persona ${LontarPersona.CLIENT_NAME} " +
            "${LontarPersona.CLIENT_VERSION})"
    } catch (e: Exception) {
        "Mesin Lontar belum siap: ${e.message}"
    }

    override suspend fun getHomeFeed(page: String?): Page<VideoItem> {
        if (page != null) {
            return InnertubeParser.parseFeedPage(browse(continuation = page))
        }
        val trending = InnertubeParser.parseFeedPage(browse(browseId = "FEtrending"))
        if (trending.items.isNotEmpty()) return trending
        // Anonymous visitors get an empty trending shelf on some networks, so
        // Lontar falls back to its seed mixture: parallel topic searches,
        // interleaved round-robin, duplicates removed. This mixture — not a
        // copied homepage — is Bunny's home by design.
        return seedMixture()
    }

    override suspend fun search(query: String, page: String?): Page<VideoItem> {
        val body = baseBody()
        if (page != null) {
            body.put("continuation", page)
        } else {
            body.put("query", query)
            body.put("params", SEARCH_PARAMS_VIDEO_ONLY)
        }
        return InnertubeParser.parseFeedPage(post("search", body))
    }

    override suspend fun getVideoDetails(videoId: String): VideoDetails =
        InnertubeParser.parseVideoDetails(playerResponse(videoId))

    override suspend fun getPlaybackStreams(videoId: String): PlaybackStreams =
        InnertubeParser.parsePlaybackStreams(playerResponse(videoId), System.currentTimeMillis())

    // -------------------------------------------------------------- internals

    private suspend fun seedMixture(): Page<VideoItem> {
        val pages = SEED_QUERIES.map { query ->
            runCatching { search(query, null).items }.getOrDefault(emptyList())
        }
        val merged = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        var added = true
        while (added) {
            added = false
            for (list in pages) {
                val item = list.getOrNull(index) ?: continue
                added = true
                if (seen.add(item.id) && !item.isLive) merged += item
            }
            index++
        }
        return Page(items = merged.take(MAX_SEED_ITEMS), nextPage = null)
    }

    private suspend fun playerResponse(videoId: String): JSONObject {
        playerCache[videoId]?.let { return it }
        val body = baseBody()
        body.put("videoId", videoId)
        body.put("contentCheckOk", true)
        body.put("racyCheckOk", true)
        val response = post("player", body)
        playerCache[videoId] = response
        return response
    }

    private suspend fun browse(browseId: String? = null, continuation: String? = null): JSONObject {
        val body = baseBody()
        when {
            continuation != null -> body.put("continuation", continuation)
            browseId != null -> body.put("browseId", browseId)
        }
        return post("browse", body)
    }

    private suspend fun baseBody(): JSONObject {
        val activeSession = ensureSession()
        val client = JSONObject()
            .put("clientName", LontarPersona.CLIENT_NAME)
            .put("clientVersion", LontarPersona.CLIENT_VERSION)
            .put("androidSdkVersion", LontarPersona.ANDROID_SDK_VERSION)
            .put("deviceMake", "Google")
            .put("deviceModel", "Pixel 8")
            .put("osName", "Android")
            .put("osVersion", "14")
            .put("hl", LontarPersona.HL)
            .put("gl", LontarPersona.GL)
            .put("visitorData", activeSession.visitorData)
        return JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("racyCheckOk", true)
            .put("contentCheckOk", true)
    }

    private suspend fun post(endpoint: String, body: JSONObject): JSONObject {
        val activeSession = ensureSession()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/$endpoint?key=${activeSession.apiKey}&prettyPrint=false")
                .header("User-Agent", LontarPersona.USER_AGENT)
                .header("X-YouTube-Client-Name", LontarPersona.CLIENT_NAME_ID)
                .header("X-YouTube-Client-Version", LontarPersona.CLIENT_VERSION)
                .header("X-Goog-Visitor-Id", activeSession.visitorData)
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LontarException("InnerTube $endpoint menjawab HTTP ${response.code}")
                }
                JSONObject(text)
            }
        }
    }

    private suspend fun ensureSession(): InnertubeSession {
        session?.let { return it }
        return sessionMutex.withLock {
            session ?: fetchSession().also { session = it }
        }
    }

    private suspend fun fetchSession(): InnertubeSession = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.youtube.com/")
            .header("User-Agent", LontarPersona.DESKTOP_USER_AGENT)
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            InnertubeSession.fromHomepageHtml(html)
                ?: throw LontarException("Gagal memperoleh sesi InnerTube dari beranda YouTube")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** InnerTube search param filter: videos only. */
        private const val SEARCH_PARAMS_VIDEO_ONLY = "EgIQAQ%3D%3D"

        private const val MAX_SEED_ITEMS = 60

        private val SEED_QUERIES = listOf(
            "berita hari ini",
            "musik indonesia terbaru",
            "gaming",
            "film pendek",
            "podcast indonesia",
        )
    }
}
