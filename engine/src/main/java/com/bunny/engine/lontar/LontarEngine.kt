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
 * Scope: session bootstrap, home (locally composed seed mixture), search,
 * video details, and playback — player-persona chain for direct-URL format
 * sets plus a self-generated DASH manifest (Fase 2). See docs/DESIGN.md.
 */
class LontarEngine(
    private val httpClient: OkHttpClient,
) : ExtractionBackend {

    override val id: String = "lontar"

    private val sessionMutex = Mutex()
    @Volatile private var session: InnertubeSession? = null

    /** Player responses are shared by details + streams calls for one video. */
    private val playerCache = ConcurrentHashMap<String, Pair<JSONObject, PlayerPersona>>()

    override suspend fun healthCheck(): String = try {
        val active = ensureSession()
        "Mesin Lontar siap — sesi InnerTube aktif (persona ${LontarPersona.CLIENT_NAME} " +
            "${active.clientVersion})"
    } catch (e: Exception) {
        "Mesin Lontar belum siap: ${e.message}"
    }

    override suspend fun getHomeFeed(page: String?): Page<VideoItem> {
        // Bunny's home is its seed mixture by design AND by necessity:
        // YouTube retired the trending page (browseId FEtrending answers
        // HTTP 400 INVALID_ARGUMENT on every client — verified 2026-11-10),
        // and the anonymous FEwhat_to_watch shelf comes back empty. So the
        // home feed is composed locally from topic searches: interleaved
        // round-robin, live streams and duplicates removed. The mixture has
        // no continuation token; later pages are simply empty.
        if (page != null) return Page(items = emptyList(), nextPage = null)
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
        InnertubeParser.parseVideoDetails(playerResponse(videoId).first)

    override suspend fun getPlaybackStreams(videoId: String): PlaybackStreams {
        val (response, persona) = playerResponse(videoId)
        val streams = InnertubeParser.parsePlaybackStreams(response, System.currentTimeMillis())
        // The URLs were issued to the persona that fetched them; playback
        // must present the same user agent (see PlaybackStreams contract).
        return streams.copy(playbackUserAgent = persona.userAgent)
    }

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

    /**
     * Player responses come from the player-persona chain (see
     * [PlayerPersonas]), not from the WEB metadata session: only those
     * clients receive full direct-URL format sets. A persona's response is
     * accepted when its playability is OK; anything else (notably the
     * intermittent LOGIN_REQUIRED bot gate) earns one retry, then the next
     * persona, and the last refusal is what the caller sees.
     */
    private suspend fun playerResponse(videoId: String): Pair<JSONObject, PlayerPersona> {
        playerCache[videoId]?.let { return it }
        var lastError: LontarException? = null
        for (persona in PlayerPersonas.CHAIN) {
            repeat(2) {
                val response = postPlayer(persona, videoId)
                val playability = response.optJSONObject("playabilityStatus")
                if (playability?.optString("status") == "OK") {
                    val accepted = response to persona
                    playerCache[videoId] = accepted
                    return accepted
                }
                lastError = LontarException(
                    "YouTube menolak memutar video ini: " +
                        (playability?.optString("reason")?.takeIf { it.isNotEmpty() }
                            ?: playability?.optString("status")
                            ?: "respons player tidak dikenal"),
                )
            }
        }
        throw lastError ?: LontarException("Tidak ada persona player yang berhasil")
    }

    private suspend fun postPlayer(persona: PlayerPersona, videoId: String): JSONObject {
        val body = JSONObject()
            .put("context", JSONObject().put("client", persona.clientJson()))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val url = "${persona.host}/youtubei/v1/player?key=${persona.apiKey}&prettyPrint=false"
        return postJson(
            url = url,
            userAgent = persona.userAgent,
            clientNameId = persona.clientNameId,
            clientVersion = persona.clientVersion,
            body = body,
            endpointLabel = "player/${persona.name}",
        )
    }

    private suspend fun baseBody(): JSONObject {
        val activeSession = ensureSession()
        val client = JSONObject()
            .put("clientName", LontarPersona.CLIENT_NAME)
            .put("clientVersion", activeSession.clientVersion)
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
        return postJson(
            url = "https://www.youtube.com/youtubei/v1/$endpoint" +
                "?key=${activeSession.apiKey}&prettyPrint=false",
            userAgent = LontarPersona.USER_AGENT,
            clientNameId = LontarPersona.CLIENT_NAME_ID,
            clientVersion = activeSession.clientVersion,
            body = body,
            endpointLabel = endpoint,
        )
    }

    private suspend fun postJson(
        url: String,
        userAgent: String,
        clientNameId: String,
        clientVersion: String,
        body: JSONObject,
        endpointLabel: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientNameId)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Include the server's own error message: a bare HTTP code
                // cost a full debugging round on 2026-11-10 (the 400 hid
                // INVALID_ARGUMENT vs FAILED_PRECONDITION, which point to
                // entirely different causes).
                val detail = runCatching {
                    JSONObject(text).getJSONObject("error").getString("message")
                }.getOrNull()
                throw LontarException(
                    "InnerTube $endpointLabel menjawab HTTP ${response.code}" +
                        (detail?.let { ": $it" } ?: ""),
                )
            }
            JSONObject(text)
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
            .header("User-Agent", LontarPersona.USER_AGENT)
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
