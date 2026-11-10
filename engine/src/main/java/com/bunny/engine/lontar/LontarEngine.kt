package com.bunny.engine.lontar

import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.Page
import com.bunny.core.model.PlaybackStreams
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem
import kotlinx.coroutines.CancellationException
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

    /** Fase 4 remote configuration; defaults until the fetch lands. */
    @Volatile private var config: EngineConfig.Values = EngineConfig.DEFAULTS
    @Volatile private var configFetched = false

    /** Player responses are shared by details + streams calls for one video. */
    private val playerCache = ConcurrentHashMap<String, Pair<JSONObject, PlayerPersona>>()

    override suspend fun healthCheck(): String = try {
        val active = ensureSession()
        "Mesin Lontar siap — sesi InnerTube aktif (persona ${LontarPersona.CLIENT_NAME} " +
            "${active.clientVersion})"
    } catch (e: CancellationException) {
        throw e
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

    override suspend fun getChannelVideos(channelId: String): Page<VideoItem> {
        // A channel's uploads playlist is a system playlist whose id is
        // the channel id with UC swapped for UU; browsing it returns the
        // channel's videos newest first. Two browseId spellings exist in
        // the wild ("VL" + playlist id, or the playlist id itself), so
        // both are tried before the channel is declared empty.
        if (!channelId.startsWith("UC") || channelId.length < 4) {
            throw LontarException("ID kanal tidak dikenal: $channelId")
        }
        val uploadsPlaylist = "UU" + channelId.substring(2)
        for (browseId in listOf("VL$uploadsPlaylist", uploadsPlaylist)) {
            try {
                val body = baseBody().put("browseId", browseId)
                val page = InnertubeParser.parseFeedPage(post("browse", body))
                if (page.items.isNotEmpty()) return Page(items = page.items, nextPage = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fall through to the other spelling.
            }
        }
        return Page(items = emptyList(), nextPage = null)
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
        // Every mixture item is tagged with its Chip Alasan as it enters:
        // the topic whose seed search surfaced it. The tag is a fact about
        // how the list was composed, so it is attached here, not guessed
        // by the UI. The seed set itself is remotely updatable (Fase 4).
        val pages = config.seedQueries.map { (query, label) ->
            try {
                search(query, null).items.map { item -> item.copy(reason = "Topik: $label") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
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
        val firstPass = attemptPlayerChain(videoId, sessionVisitorData())
        if (firstPass.accepted != null) return firstPass.accepted
        // Fase 4 resilience: the bot gate is bound to the session (see
        // PlayerPersona), so when the whole chain refuses, one FRESH
        // session earns one more pass before the refusal reaches the
        // user. Exactly one refresh — no retry loops against a gate
        // that may be answering deliberately.
        session = null
        val secondPass = attemptPlayerChain(videoId, sessionVisitorData())
        if (secondPass.accepted != null) return secondPass.accepted
        throw secondPass.error ?: firstPass.error
            ?: LontarException("Tidak ada persona player yang berhasil")
    }

    /**
     * The session's visitorData is what separates a playable answer from
     * the LOGIN_REQUIRED bot gate (see PlayerPersona): fetched
     * best-effort — a session failure must not mask a player answer that
     * might still succeed bare.
     */
    private suspend fun sessionVisitorData(): String? = try {
        ensureSession().visitorData
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private class ChainOutcome(
        val accepted: Pair<JSONObject, PlayerPersona>?,
        val error: LontarException?,
    )

    private suspend fun attemptPlayerChain(
        videoId: String,
        visitorData: String?,
    ): ChainOutcome {
        var lastError: LontarException? = null
        for (persona in PlayerPersonas.chain(config.androidVrClientVersion)) {
            repeat(2) {
                val response = postPlayer(persona, videoId, visitorData)
                val playability = response.optJSONObject("playabilityStatus")
                if (playability?.optString("status") == "OK") {
                    val accepted = response to persona
                    playerCache[videoId] = accepted
                    return ChainOutcome(accepted, null)
                }
                lastError = LontarException(
                    "YouTube menolak memutar video ini: " +
                        (playability?.optString("reason")?.takeIf { it.isNotEmpty() }
                            ?: playability?.optString("status")
                            ?: "respons player tidak dikenal"),
                )
            }
        }
        return ChainOutcome(null, lastError)
    }

    private suspend fun postPlayer(
        persona: PlayerPersona,
        videoId: String,
        visitorData: String?,
    ): JSONObject {
        val body = JSONObject()
            .put("context", JSONObject().put("client", persona.clientJson(visitorData)))
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
            // The remote config is fetched once per process, before the
            // session, because the session bootstrap itself consumes it
            // (the fallback client version). Best-effort by design: the
            // defaults are always serviceable.
            if (!configFetched) {
                configFetched = true
                config = fetchRemoteConfig()
            }
            session ?: fetchSession().also { session = it }
        }
    }

    private suspend fun fetchRemoteConfig(): EngineConfig.Values = try {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(EngineConfig.REMOTE_URL)
                .header("User-Agent", LontarPersona.USER_AGENT)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    EngineConfig.parse(JSONObject(response.body?.string().orEmpty()))
                } else {
                    EngineConfig.DEFAULTS
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EngineConfig.DEFAULTS
    }

    private suspend fun fetchSession(): InnertubeSession = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.youtube.com/")
            .header("User-Agent", LontarPersona.USER_AGENT)
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            InnertubeSession.fromHomepageHtml(html, config.fallbackClientVersion)
                ?: throw LontarException("Gagal memperoleh sesi InnerTube dari beranda YouTube")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** InnerTube search param filter: videos only. */
        private const val SEARCH_PARAMS_VIDEO_ONLY = "EgIQAQ%3D%3D"

        private const val MAX_SEED_ITEMS = 60
    }
}
