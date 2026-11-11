package com.bunny.engine.lontar

import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.ChannelInfo
import com.bunny.core.model.CommentsPage
import com.bunny.core.model.Page
import com.bunny.core.model.PlaybackStreams
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    override suspend fun getLiveFeed(): Page<VideoItem> {
        // The Live tab is the home mixture's mirror image: topic
        // searches through the search endpoint's Live feature filter,
        // keeping ONLY live items. The filter param was probed against
        // YouTube with this project's own session bootstrap on
        // 2026-11-10: for the query "berita", 20 of 20 results carried
        // the LIVE badge (the plain videos-only param returned 1 of
        // 20), and the parser flags them via that badge. A channel-hub
        // browse was probed too and rejected: its rows carry no badges.
        // The topic searches are independent: run them in parallel so
        // the tab costs one round trip, not five in a row.
        val pages = coroutineScope {
            LIVE_SEED_QUERIES.map { (query, label) ->
                async {
                    try {
                        searchLive(query).map { item ->
                            item.copy(reason = "Topik: $label")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        return Page(items = roundRobin(pages, MAX_LIVE_ITEMS), nextPage = null)
    }

    /** One live-filtered search; only badge-flagged live items survive. */
    private suspend fun searchLive(query: String): List<VideoItem> {
        val body = baseBody()
            .put("query", query)
            .put("params", SEARCH_PARAMS_LIVE)
        return InnertubeParser.parseFeedPage(post("search", body)).items
            .filter { it.isLive }
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

    /**
     * The channel page's own browse carries its real header metadata.
     * Best-effort by contract: any failure means "no header data",
     * never an error on the channel page.
     */
    override suspend fun getChannelInfo(channelId: String): ChannelInfo? {
        if (!channelId.startsWith("UC") || channelId.length < 4) return null
        return try {
            val body = baseBody().put("browseId", channelId)
            InnertubeParser.parseChannelInfo(post("browse", body), channelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Comments ride the watch page's own continuation chain: the first
     * page opens with a next(videoId) call whose response carries both
     * the comment section's token and the owner's photo; later pages
     * follow the token each page hands back. Best-effort like the other
     * decorations — any failure reads as "no comments".
     */
    override suspend fun getComments(videoId: String, page: String?): CommentsPage {
        return try {
            if (page != null) {
                val body = baseBody().put("continuation", page)
                InnertubeParser.parseCommentsPage(post("next", body))
            } else {
                val watch = post("next", baseBody().put("videoId", videoId))
                val ownerAvatar = InnertubeParser.parseOwnerAvatarUrl(watch)
                val token = InnertubeParser.findCommentsToken(watch)
                    ?: return CommentsPage(ownerAvatarUrl = ownerAvatar)
                val body = baseBody().put("continuation", token)
                InnertubeParser.parseCommentsPage(post("next", body))
                    .copy(ownerAvatarUrl = ownerAvatar)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommentsPage()
        }
    }

    override suspend fun getSearchSuggestions(query: String): List<String> {
        // Best-effort decoration for the search box (see SearchSuggestions):
        // a failed suggestion fetch must never surface as a search error.
        if (query.isBlank()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(SearchSuggestions.urlFor(query))
                    .header("User-Agent", LontarPersona.USER_AGENT)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        SearchSuggestions.parse(response.body?.string().orEmpty())
                    } else {
                        emptyList()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
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
        // The topic searches are independent: run them in parallel so
        // the home feed costs one round trip, not five in a row.
        val pages = coroutineScope {
            config.seedQueries.map { (query, label) ->
                async {
                    try {
                        search(query, null).items
                            .filter { !it.isLive }
                            .map { item -> item.copy(reason = "Topik: $label") }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        return Page(items = roundRobin(pages, MAX_SEED_ITEMS), nextPage = null)
    }

    /** Round-robin interleave of per-topic pages, duplicates removed. */
    private fun roundRobin(
        pages: List<List<VideoItem>>,
        limit: Int,
    ): List<VideoItem> {
        val merged = mutableListOf<VideoItem>()
        val seen = mutableSetOf<String>()
        var index = 0
        var added = true
        while (added) {
            added = false
            for (list in pages) {
                val item = list.getOrNull(index) ?: continue
                added = true
                if (seen.add(item.id)) merged += item
            }
            index++
        }
        return merged.take(limit)
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
        // Distribution hardening (2026-11-10): every persona's outcome
        // is summarised into the final error, so a failure log can tell
        // the bot gate (LOGIN_REQUIRED) apart from a retired client
        // (ERROR) or an empty answer — they have entirely different
        // fixes, and confusing them cost a debugging round before.
        val outcomes = mutableListOf<String>()
        var lastError: LontarException? = null
        for (persona in PlayerPersonas.chain(config)) {
            var outcome = "tidak ada respons"
            repeat(2) {
                val response = postPlayer(persona, videoId, visitorData)
                val playability = response.optJSONObject("playabilityStatus")
                val status = playability?.optString("status")
                val reason = playability?.optString("reason")
                    ?.takeIf { it.isNotEmpty() }
                // Playability OK alone is not acceptance: an OK with no
                // usable stream (seen from TV personas in the probe) is
                // a refusal wearing a smile — the next persona may hold
                // real formats.
                if (status == "OK" && hasPlayableStreams(response)) {
                    val accepted = response to persona
                    if (playerCache.size >= MAX_PLAYER_CACHE_ENTRIES) playerCache.clear()
                    playerCache[videoId] = accepted
                    return ChainOutcome(accepted, null)
                }
                outcome = when {
                    status == "OK" -> "OK tanpa aliran yang dapat diputar"
                    reason != null -> "${status ?: "?"}: $reason"
                    else -> status ?: "respons player tidak dikenal"
                }
                lastError = LontarException(
                    "YouTube menolak memutar video ini: " +
                        (reason ?: status ?: "respons player tidak dikenal"),
                )
            }
            outcomes += "${persona.name} → $outcome"
        }
        val summary = outcomes.joinToString("; ")
        return ChainOutcome(
            null,
            LontarException(
                (lastError?.message ?: "Tidak ada persona player yang berhasil") +
                    " [rantai: $summary]",
            ),
        )
    }

    /** True when a player response carries at least one playable stream. */
    private fun hasPlayableStreams(response: JSONObject): Boolean {
        val streaming = response.optJSONObject("streamingData") ?: return false
        if (streaming.optString("hlsManifestUrl").isNotEmpty()) return true
        for (key in listOf("formats", "adaptiveFormats")) {
            val formats = streaming.optJSONArray(key) ?: continue
            for (i in 0 until formats.length()) {
                if (formats.optJSONObject(i)?.optString("url")?.isNotEmpty() == true) {
                    return true
                }
            }
        }
        return false
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
    ): JSONObject {
        // Distribution hardening (2026-11-10): transient refusals —
        // HTTP 429 (rate limit), 5xx (server trouble) and network I/O
        // failures — earn a short backoff and another attempt instead
        // of surfacing instantly; a feed built from five parallel
        // searches is exactly the shape that trips rate limits. Client
        // errors (4xx other than 429) are answers, not weather: they
        // throw at once, with the server's own message included (a bare
        // HTTP code cost a full debugging round on 2026-11-10 — the 400
        // hid INVALID_ARGUMENT vs FAILED_PRECONDITION).
        var attempt = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) {
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
                            val detail = runCatching {
                                JSONObject(text).getJSONObject("error").getString("message")
                            }.getOrNull()
                            throw HttpRefusal(
                                response.code,
                                "InnerTube $endpointLabel menjawab HTTP ${response.code}" +
                                    (detail?.let { ": $it" } ?: ""),
                            )
                        }
                        JSONObject(text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRefusal) {
                if (e.code !in RETRYABLE_HTTP_CODES || attempt >= RETRY_DELAYS_MS.size) {
                    throw LontarException(e.message ?: "InnerTube $endpointLabel gagal")
                }
                delay(RETRY_DELAYS_MS[attempt])
                attempt++
            } catch (e: java.io.IOException) {
                if (attempt >= RETRY_DELAYS_MS.size) throw e
                delay(RETRY_DELAYS_MS[attempt])
                attempt++
            }
        }
    }

    /** An HTTP refusal, carrying its code so retry policy can see it. */
    private class HttpRefusal(val code: Int, message: String) : Exception(message)

    private suspend fun ensureSession(): InnertubeSession {
        session?.let { return it }
        return sessionMutex.withLock {
            session ?: coroutineScope {
                // The remote config and the session bootstrap are two
                // independent fetches; running them together saves a
                // full round trip on cold start. The session only borrows
                // the config's fallback client version, and only when the
                // homepage lacks a version marker — so while the config
                // is still in flight the built-in default serves, and the
                // fetched config applies to everything after (seed
                // queries, player personas). Best-effort by design: the
                // defaults are always serviceable.
                val configJob = async {
                    if (!configFetched) {
                        configFetched = true
                        config = fetchRemoteConfig()
                    }
                }
                // Whatever config is already in hand (the defaults on a
                // cold start, the fetched values on a session refresh).
                val fallback = config.fallbackClientVersion
                val newSession = async { fetchSession(fallback) }
                configJob.await()
                newSession.await().also { session = it }
            }
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

    private suspend fun fetchSession(
        fallbackClientVersion: String,
    ): InnertubeSession = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.youtube.com/")
            .header("User-Agent", LontarPersona.USER_AGENT)
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            InnertubeSession.fromHomepageHtml(html, fallbackClientVersion)
                ?: throw LontarException("Gagal memperoleh sesi InnerTube dari beranda YouTube")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** InnerTube search param filter: videos only. */
        private const val SEARCH_PARAMS_VIDEO_ONLY = "EgIQAQ%3D%3D"

        /** InnerTube search param filter: the Live feature (probed 2026-11-10). */
        private const val SEARCH_PARAMS_LIVE = "EgJAAQ%3D%3D"

        private const val MAX_SEED_ITEMS = 60
        private const val MAX_LIVE_ITEMS = 40

        /** The player-response cache is a courtesy, not an archive. */
        private const val MAX_PLAYER_CACHE_ENTRIES = 100

        /** HTTP codes that mean "weather", not "answer": worth a backoff. */
        private val RETRYABLE_HTTP_CODES = setOf(429, 500, 502, 503, 504)

        /** Backoff schedule between postJson attempts (two retries). */
        private val RETRY_DELAYS_MS = longArrayOf(600L, 1_800L)

        /** Live seed topics: query to Chip Alasan label. */
        private val LIVE_SEED_QUERIES: List<Pair<String, String>> = listOf(
            "berita" to "Berita",
            "musik" to "Musik",
            "sepak bola" to "Bola",
            "gaming" to "Gaming",
            "ceramah" to "Ceramah",
        )
    }
}
