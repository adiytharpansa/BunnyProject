package com.bunny.engine.lontar

import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.AccountInfo
import com.bunny.core.model.ChannelInfo
import com.bunny.core.model.CommentsPage
import com.bunny.core.model.DownloadFormats
import com.bunny.core.model.Page
import com.bunny.core.model.PlaybackStreams
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem
import com.bunny.core.model.GuestWarmingReport
import com.bunny.core.model.PersonaHealthReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    /** Fase 5 login cookies for the metadata plane (app-owned). */
    val auth: LontarAuth = LontarAuth(),
    /** Lontar Hidup (Fase 6): the Adaptive Ladder's on-device memory. */
    private val statsStore: PersonaStatsStore = PersonaStatsStore.NONE,
    /** Lontar Hidup: coarse network class for statistics bucketing. */
    private val networkClass: NetworkClassProvider = NetworkClassProvider.UNKNOWN,
    /** Selera Tamu: the persistent guest visitor identity's shelf. */
    private val visitorStore: VisitorStore = VisitorStore.NONE,
    /** Fase 7-A: the metadata surfaces' failure notebook. */
    private val failureReporter: SurfaceFailureReporter = SurfaceFailureReporter.NONE,
) : ExtractionBackend {

    override val id: String = "lontar"

    private val sessionMutex = Mutex()
    @Volatile private var session: InnertubeSession? = null

    /** Fase 4 remote configuration; defaults until the fetch lands. */
    @Volatile private var config: EngineConfig.Values = EngineConfig.DEFAULTS
    @Volatile private var configFetched = false

    /** Player responses are shared by details + streams calls for one video. */
    private val playerCache = ConcurrentHashMap<String, Pair<JSONObject, PlayerPersona>>()

    // ----------------------------------------------------- lontar hidup

    /** Background lane for warm-session harvests; never user-blocking. */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Sesi Cadangan Hangat: a spare session, harvested while idle. */
    @Volatile private var backupSession: InnertubeSession? = null
    @Volatile private var backupHarvestedAtMs: Long = 0L

    /** The Radar's session verdicts, by persona name. */
    private val radarState = ConcurrentHashMap<String, PersonaHealthReport>()

    /** The active ladder + its reasons, for the Diagnostics block. */
    @Volatile private var lastLadderDescription: String = ""

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
        // Fase 7-A: both spellings came back empty or refused — the
        // channel page will read "no videos"; leave why in the log.
        failureReporter.record(
            SURFACE_KANAL,
            KIND_KOSONG,
            "daftar unggahan kosong pada kedua ejaan browseId",
        )
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
            val info = InnertubeParser.parseChannelInfo(post("browse", body), channelId)
            // Fase 7-A: a header that parsed to nothing, or one whose
            // real photo is missing, is evidence — not silence.
            if (info == null) {
                failureReporter.record(
                    SURFACE_KANAL,
                    KIND_URAI,
                    "kepala kanal tidak terbaca dari jawaban browse",
                )
            } else if (info.avatarUrl.isNullOrBlank()) {
                failureReporter.record(
                    SURFACE_KANAL,
                    KIND_URAI,
                    "kepala kanal terbaca tanpa foto asli",
                )
            }
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteFailure(SURFACE_KANAL, e)
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
                if (token == null) {
                    // Fase 7-A: a missing token is a parse-shape
                    // failure, not "this video has no comments" —
                    // leave the evidence before going quiet.
                    failureReporter.record(
                        SURFACE_KOMENTAR,
                        KIND_URAI,
                        "token komentar tidak ditemukan di halaman tonton",
                    )
                    return CommentsPage(ownerAvatarUrl = ownerAvatar)
                }
                val body = baseBody().put("continuation", token)
                val firstPage = InnertubeParser.parseCommentsPage(post("next", body))
                    .copy(ownerAvatarUrl = ownerAvatar)
                if (firstPage.items.isEmpty()) {
                    failureReporter.record(
                        SURFACE_KOMENTAR,
                        KIND_KOSONG,
                        "halaman pertama terbaca kosong",
                    )
                }
                firstPage
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteFailure(SURFACE_KOMENTAR, e)
            CommentsPage()
        }
    }

    /** One page of a thread's replies, via the thread's own token. */
    override suspend fun getCommentReplies(continuation: String): CommentsPage {
        return try {
            InnertubeParser.parseRepliesPage(
                post("next", baseBody().put("continuation", continuation)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteFailure(SURFACE_BALASAN, e)
            CommentsPage()
        }
    }

    /**
     * The related list rides the same watch-page call as comments: one
     * next(videoId) response already carries YouTube's own ranked list
     * in secondaryResults. Best-effort — any failure reads as "no
     * server list" and the caller composes its local fallback instead.
     */
    override suspend fun getRelatedVideos(videoId: String): List<VideoItem> {
        return try {
            val watch = post("next", baseBody().put("videoId", videoId))
            val items = InnertubeParser.parseRelatedVideos(watch)
            if (items.isEmpty()) {
                failureReporter.record(
                    SURFACE_TERKAIT,
                    KIND_KOSONG,
                    "daftar server terbaca kosong",
                )
            }
            items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteFailure(SURFACE_TERKAIT, e)
            emptyList()
        }
    }

    /** The account's own header, from its authenticated account menu. */
    override suspend fun getAccountInfo(): AccountInfo? {
        if (!auth.isSignedIn) return null
        return try {
            InnertubeParser.parseAccountInfo(post("account/account_menu", baseBody()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
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

    override suspend fun getVideoDetails(videoId: String): VideoDetails {
        return try {
            InnertubeParser.parseVideoDetails(playerResponse(videoId).first)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Details failures DO surface as player errors; the log
            // entry is what separates a refusal from a parse change.
            noteFailure(SURFACE_DETAIL, e)
            throw e
        }
    }

    /**
     * Panen Bertingkat's raw material: the winning player response's
     * own format files. Rides the same cached player response as
     * playback, so asking right after opening a video is free.
     */
    override suspend fun getDownloadFormats(videoId: String): DownloadFormats {
        val (response, persona) = playerResponse(videoId)
        return DownloadFormats(
            formats = InnertubeParser.parseDownloadFormats(response),
            userAgent = persona.userAgent,
        )
    }

    override suspend fun getPlaybackStreams(videoId: String): PlaybackStreams {
        val (response, persona) = playerResponse(videoId)
        val streams = InnertubeParser.parsePlaybackStreams(response, System.currentTimeMillis())
        // The URLs were issued to the persona that fetched them; playback
        // must present the same user agent (see PlaybackStreams contract).
        return streams.copy(
            playbackUserAgent = persona.userAgent,
            personaName = persona.name,
        )
    }

    /**
     * Selera Tamu (Langkah 2): the warming probe. Both browses ride
     * the ordinary metadata session — which, since Langkah 0,
     * carries the persistent guest visitor. A refusal on the home
     * browse reads as a cold zero; the history browse's refusal
     * keeps its HTTP code, because the code is the verdict's most
     * honest part.
     */
    override suspend fun probeGuestWarming(): GuestWarmingReport {
        ensureSession()
        val homeCount = try {
            val body = baseBody().put("browseId", "FEwhat_to_watch")
            InnertubeParser.parseFeedPage(post("browse", body)).items.size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
        var historyCount: Int? = null
        var historyHttp: Int? = null
        try {
            val body = baseBody().put("browseId", "FEhistory")
            historyCount =
                InnertubeParser.parseFeedPage(post("browse", body)).items.size
        } catch (e: CancellationException) {
            throw e
        } catch (e: LontarException) {
            historyHttp = e.httpCode
        } catch (e: Exception) {
            // An unparseable answer is still an answer of zero.
            historyCount = 0
        }
        return GuestWarmingReport(
            homeItemCount = homeCount,
            historyItemCount = historyCount,
            historyHttpCode = historyHttp,
            checkedAtEpochMs = System.currentTimeMillis(),
        )
    }

    // ----------------------------------------------------- lontar hidup

    override suspend fun engineHealth(): List<PersonaHealthReport> {
        val order = PlayerPersonas.chain(config).map { it.name }
        return radarState.values.sortedBy { report ->
            order.indexOf(report.persona).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }

    /**
     * Radar Kesehatan Mesin: for every ladder persona, a canary player
     * call PLUS byte probes past the cold-start allowance — because a
     * player OK alone has been proven (2026-11-10) to hide a GVS gate
     * at the 3 MB mark. Verdicts land in the session Radar, in the
     * on-device statistics, and in the returned reports. Costs roughly
     * a kilobyte of media per probe; the format metadata dominates.
     */
    override suspend fun runEngineHealthCheck(): List<PersonaHealthReport> {
        ensureSession()
        val visitor = sessionVisitorData()
        val net = networkClass.current()
        val reports = mutableListOf<PersonaHealthReport>()
        for (persona in PlayerPersonas.chain(config)) {
            val state = probePersona(persona, visitor)
            val report = PersonaHealthReport(
                persona = persona.name,
                state = state.name,
                checkedAtEpochMs = System.currentTimeMillis(),
                networkClass = net,
            )
            radarState[persona.name] = report
            recordStat(persona.name, net, state)
            reports += report
        }
        return reports
    }

    private suspend fun probePersona(
        persona: PlayerPersona,
        visitorData: String?,
    ): PersonaHealthState {
        for (canary in config.canaryVideoIds) {
            val response = try {
                postPlayer(persona, canary, visitorData)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return PersonaHealthState.GAGAL_JARINGAN
            }
            val status = response.optJSONObject("playabilityStatus")
                ?.optString("status")
            if (status != "OK") {
                // Persona-level refusals convict at once; a canary-
                // specific UNPLAYABLE says nothing about the persona,
                // so the next canary gets its say.
                if (status == "LOGIN_REQUIRED" || status == "ERROR") {
                    return PersonaHealthState.DITOLAK_PLAYER
                }
                continue
            }
            val format = InnertubeParser.parseDownloadFormats(response)
                .filter {
                    !it.isAudioOnly && (it.contentLength ?: 0) > MIN_PROBE_BYTES
                }
                .maxByOrNull { it.contentLength ?: 0 }
                ?: continue
            val first = rangeProbe(format.url, persona.userAgent, 0L)
            val deep = rangeProbe(
                format.url,
                persona.userAgent,
                config.radarDeepProbeOffsetBytes,
            )
            if (first == null || deep == null) {
                return PersonaHealthState.GAGAL_JARINGAN
            }
            return RadarClassifier.classify("OK", first, deep, networkFailed = false)
        }
        return PersonaHealthState.TAK_DIKETAHUI
    }

    /** One ranged byte probe; returns the HTTP status, null on I/O failure. */
    private suspend fun rangeProbe(url: String, userAgent: String, offset: Long): Int? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Range", "bytes=$offset-${offset + 1023}")
                    .build()
                httpClient.newCall(request).execute().use { it.code }
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun reportPlaybackSuspectedGvs(personaName: String) {
        val net = networkClass.current()
        radarState[personaName] = PersonaHealthReport(
            persona = personaName,
            state = PersonaHealthState.TERGERBANG_GVS.name,
            checkedAtEpochMs = System.currentTimeMillis(),
            networkClass = net,
        )
        recordStat(personaName, net, PersonaHealthState.TERGERBANG_GVS)
    }

    override suspend fun getRefreshedPlaybackStreams(
        videoId: String,
        failedPersonaName: String?,
    ): PlaybackStreams {
        if (failedPersonaName != null) {
            reportPlaybackSuspectedGvs(failedPersonaName)
        }
        playerCache.remove(videoId)
        return getPlaybackStreams(videoId)
    }

    override fun ladderDescription(): String = lastLadderDescription

    // -------------------------------------------------------- satu ketuk

    /**
     * The three doors the Langkah 1 probe knocks on, in evidence
     * order: TVHTML5 (the only context with published acceptance),
     * WEB (the session's own), ANDROID (the token's birth family —
     * an Android OAuth client's token may pair with its own context
     * even where the TV pairing fails). Versions and agents are the
     * live-verified shapes from the 2026-11-11 research references.
     */
    private data class BearerDoor(
        val contextName: String,
        val clientNameId: String,
        val clientVersion: String,
        val userAgent: String,
        val referer: String?,
        val androidContext: Boolean,
    )

    private fun bearerDoors(webVersion: String): List<BearerDoor> = listOf(
        BearerDoor(
            contextName = "TVHTML5",
            clientNameId = "7",
            clientVersion = "7.20260707.07.00",
            userAgent = "Mozilla/5.0 (Linux armeabi-v7a; Android 7.1.2; " +
                "Fire OS 6.0) Cobalt/22.lts.3.306369-gold (unlike Gecko) " +
                "v8/8.8.278.8-jit gles Starboard/13, " +
                "Amazon_ATV_mediatek8695_2019/NS6294 (Amazon, AFTMM, " +
                "Wireless) com.amazon.firetv.youtube/22.3.r2.v66.0",
            referer = "https://www.youtube.com/tv",
            androidContext = false,
        ),
        BearerDoor(
            contextName = "WEB",
            clientNameId = "1",
            clientVersion = webVersion,
            userAgent = LontarPersona.USER_AGENT,
            referer = null,
            androidContext = false,
        ),
        BearerDoor(
            contextName = "ANDROID",
            clientNameId = "3",
            clientVersion = "21.26.364",
            userAgent = "com.google.android.youtube/21.26.364 " +
                "(Linux; U; Android 11) gzip",
            referer = null,
            androidContext = true,
        ),
    )

    /**
     * Satu Ketuk Langkah 1 — token evidence. One honest attempt per
     * door against `account/accounts_list`: the verdict records the
     * HTTP status and, on acceptance, the account the door named.
     * The first accepting door becomes the engine's personal session
     * (token + context in [LontarAuth]). The token is never logged.
     */
    override suspend fun probeBearerContexts(
        accessToken: String,
    ): List<com.bunny.core.model.BearerContextVerdict> {
        val session = ensureSession()
        val verdicts = mutableListOf<com.bunny.core.model.BearerContextVerdict>()
        for (door in bearerDoors(session.clientVersion)) {
            val client = JSONObject()
                .put("clientName", door.contextName)
                .put("clientVersion", door.clientVersion)
                .put("hl", "id")
                .put("gl", "ID")
                .put("visitorData", session.visitorData)
            if (door.androidContext) {
                client.put("androidSdkVersion", 30)
                    .put("osName", "Android")
                    .put("osVersion", "11")
            }
            val body = JSONObject()
                .put("context", JSONObject().put("client", client))
            val verdict = try {
                val response = postBearerJson(
                    url = "https://www.youtube.com/youtubei/v1/account/accounts_list" +
                        "?key=${session.apiKey}&prettyPrint=false",
                    door = door,
                    body = body,
                    accessToken = accessToken,
                )
                val account = InnertubeParser.parseAccountItem(response)
                if (account != null) {
                    com.bunny.core.model.BearerContextVerdict(
                        contextName = door.contextName,
                        httpStatus = 200,
                        accepted = true,
                        accountName = account.name,
                        accountHandle = account.handle,
                        accountEmail = account.email,
                        avatarUrl = account.avatarUrl,
                    )
                } else {
                    com.bunny.core.model.BearerContextVerdict(
                        contextName = door.contextName,
                        httpStatus = 200,
                        accepted = false,
                    )
                }
            } catch (e: LontarException) {
                com.bunny.core.model.BearerContextVerdict(
                    contextName = door.contextName,
                    httpStatus = e.httpCode,
                    accepted = false,
                )
            } catch (e: java.io.IOException) {
                com.bunny.core.model.BearerContextVerdict(
                    contextName = door.contextName,
                    httpStatus = null,
                    accepted = false,
                )
            }
            verdicts += verdict
            if (verdict.accepted) break
        }
        verdicts.firstOrNull { it.accepted }?.let { winner ->
            auth.bearerToken = accessToken
            auth.bearerContext = winner.contextName
        }
        return verdicts
    }

    /** One Bearer-carrying InnerTube POST; the probe's measuring tool. */
    private suspend fun postBearerJson(
        url: String,
        door: BearerDoor,
        body: JSONObject,
        accessToken: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", door.userAgent)
            .header("X-YouTube-Client-Name", door.clientNameId)
            .header("X-YouTube-Client-Version", door.clientVersion)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Goog-AuthUser", "0")
            .header("X-YouTube-Bootstrap-Logged-In", "true")
        if (door.referer != null) builder.header("Referer", door.referer)
        val request = builder
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(text).getJSONObject("error").getString("message")
                }.getOrNull()
                throw LontarException(
                    "Pintu ${door.contextName} menjawab HTTP ${response.code}" +
                        (detail?.let { ": $it" } ?: ""),
                    httpCode = response.code,
                )
            }
            JSONObject(text)
        }
    }

    /** Statistics are evidence, never a failure surface of their own. */
    private suspend fun recordStat(
        persona: String,
        networkClass: String,
        state: PersonaHealthState,
    ) {
        try {
            statsStore.record(persona, networkClass, state)
        } catch (e: Exception) {
            // A broken statistics store must never break playback.
        }
    }

    private fun PersonaHealthReport.stateEnum(): PersonaHealthState =
        runCatching { PersonaHealthState.valueOf(state) }
            .getOrDefault(PersonaHealthState.TAK_DIKETAHUI)

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
        // that may be answering deliberately. Lontar Hidup (Fase 6):
        // the fresh session is the warm spare when one is ready, so
        // the swap costs no bootstrap round trip.
        swapSession()
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
        //
        // Lontar Hidup (Fase 6): the configured ladder is re-ordered by
        // evidence — this session's Radar verdicts first, then the
        // on-device statistics for this network class (Tangga Adaptif);
        // the order + reasons are kept for the Diagnostics block.
        val configured = PlayerPersonas.chain(config)
        val net = networkClass.current()
        val stats = try {
            statsStore.snapshot(net)
        } catch (e: Exception) {
            emptyMap()
        }
        val radar = radarState.mapValues { it.value.stateEnum() }
        val orderedNames = LadderBuilder.order(
            configured.map { it.name },
            radar,
            stats,
        )
        val byName = configured.associateBy { it.name }
        val ladder = orderedNames.mapNotNull { byName[it] }
        lastLadderDescription = LadderBuilder.describe(orderedNames, radar, stats)
        val outcomes = mutableListOf<String>()
        var lastError: LontarException? = null
        for (persona in ladder) {
            var outcome = "tidak ada respons"
            var lastStatus: String? = null
            repeat(2) {
                val response = postPlayer(persona, videoId, visitorData)
                val playability = response.optJSONObject("playabilityStatus")
                val status = playability?.optString("status")
                lastStatus = status
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
                    recordStat(persona.name, net, PersonaHealthState.SEHAT)
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
            // A player-layer refusal is ladder evidence; an OK with no
            // streams is not the persona's verdict to carry.
            if (lastStatus != null && lastStatus != "OK") {
                recordStat(persona.name, net, PersonaHealthState.DITOLAK_PLAYER)
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

    /**
     * Fase 7-A: writes one failure-notebook entry for a metadata
     * surface, classifying the cause the way the Diagnostics block
     * reads best — the HTTP code when the server refused, "jaringan"
     * for transport trouble, "urai" for everything else (a changed
     * response shape is the usual suspect there).
     */
    private fun noteFailure(surface: String, e: Exception) {
        val kind = when (e) {
            is LontarException -> e.httpCode?.let { "HTTP $it" } ?: "mesin"
            is java.io.IOException -> "jaringan"
            else -> KIND_URAI
        }
        failureReporter.record(
            surface,
            kind,
            (e.message ?: e.javaClass.simpleName).take(MAX_FAILURE_DETAIL_CHARS),
        )
    }

    private suspend fun post(endpoint: String, body: JSONObject): JSONObject {
        suspend fun attempt(activeSession: InnertubeSession): JSONObject =
            postJson(
                url = "https://www.youtube.com/youtubei/v1/$endpoint" +
                    "?key=${activeSession.apiKey}&prettyPrint=false",
                userAgent = LontarPersona.USER_AGENT,
                clientNameId = LontarPersona.CLIENT_NAME_ID,
                clientVersion = activeSession.clientVersion,
                body = body,
                endpointLabel = endpoint,
                auth = auth.cookies,
            )
        return try {
            attempt(ensureSession())
        } catch (e: LontarException) {
            // Metadata self-healing (Lontar Hidup §7.3): a 401/403 that
            // matches a session refusal earns ONE session swap and ONE
            // retry with the body rebuilt on the new session (its only
            // session-bound field is the context visitorData). Ordinary
            // query errors (a bad browseId's 400, say) are NOT healed —
            // masking them would hide the real cause.
            if (e.httpCode != 401 && e.httpCode != 403) throw e
            swapSession()
            val healed = ensureSession()
            body.optJSONObject("context")?.optJSONObject("client")
                ?.put("visitorData", healed.visitorData)
            attempt(healed)
        }
    }

    private suspend fun postJson(
        url: String,
        userAgent: String,
        clientNameId: String,
        clientVersion: String,
        body: JSONObject,
        endpointLabel: String,
        auth: AuthCookies? = null,
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
                    val requestBuilder = Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .header("X-YouTube-Client-Name", clientNameId)
                        .header("X-YouTube-Client-Version", clientVersion)
                        .header("Content-Type", "application/json")
                    // Fase 5: a signed-in session signs its WEB requests
                    // with the cookies + SAPISIDHASH, like YouTube's own
                    // web client. Cookie values are never logged.
                    if (auth != null) {
                        requestBuilder
                            .header("Cookie", auth.cookieHeader)
                            .header(
                                "Authorization",
                                SapisidHash.authorizationHeader(
                                    auth.sapisid,
                                    System.currentTimeMillis() / 1000,
                                ),
                            )
                            .header("X-Origin", SapisidHash.ORIGIN)
                    }
                    val request = requestBuilder
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
                    throw LontarException(
                        e.message ?: "InnerTube $endpointLabel gagal",
                        httpCode = e.code,
                    )
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
                newSession.await().also { created ->
                    session = created
                    // Sesi Cadangan Hangat: while this session serves,
                    // quietly harvest a spare, so a session refusal is
                    // healed by a swap instead of a full bootstrap at
                    // the worst possible moment (Lontar Hidup §7).
                    scheduleBackupHarvest()
                }
            }
        }
    }

    /** Harvests one spare session in the background; failures are silent. */
    private fun scheduleBackupHarvest() {
        engineScope.launch {
            try {
                val spare = fetchSession(config.fallbackClientVersion)
                backupSession = spare
                backupHarvestedAtMs = System.currentTimeMillis()
            } catch (e: Exception) {
                // A missing spare only means the next heal pays the
                // ordinary bootstrap cost — never an error surface.
            }
        }
    }

    /**
     * Replaces the active session: the warm spare when it is still
     * fresh (instant), a fresh bootstrap otherwise. Used by the player
     * chain's second pass and by metadata self-healing — one swap, one
     * retry, never a loop (Lontar Hidup §7.2).
     */
    private suspend fun swapSession() {
        val spare = backupSession
        val fresh = spare != null &&
            System.currentTimeMillis() - backupHarvestedAtMs < BACKUP_TTL_MS
        if (fresh) {
            session = spare
            backupSession = null
            scheduleBackupHarvest()
        } else {
            // A full re-bootstrap follows a refusal: if the refused
            // session carried the shelved visitor, that identity is
            // suspect — the next bootstrap adopts a fresh one.
            if (session?.visitorData != null &&
                session?.visitorData == visitorStore.load()
            ) {
                visitorSuspect = true
            }
            session = null
            ensureSession()
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

    /**
     * Selera Tamu (Langkah 0): set when a session carrying the
     * STORED visitor had to be swapped out — the stored identity
     * is suspect, so the next bootstrap adopts the freshly
     * harvested visitor and re-shelves it. Consumed once.
     */
    @Volatile private var visitorSuspect = false

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
            val harvested = InnertubeSession.fromHomepageHtml(
                html,
                fallbackClientVersion,
            ) ?: throw LontarException(
                "Gagal memperoleh sesi InnerTube dari beranda YouTube",
            )
            // One guest identity per install: the harvested visitor
            // is only adopted when the shelf is empty or suspect;
            // otherwise the shelved identity rides the fresh
            // session's key and version.
            val stored = visitorStore.load()
            val suspect = visitorSuspect
            visitorSuspect = false
            when {
                stored.isNullOrBlank() || suspect -> {
                    visitorStore.save(harvested.visitorData)
                    harvested
                }
                else -> harvested.copy(visitorData = stored)
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Fase 7-A: metadata surface names + failure classes written
        // to the failure notebook (user-facing, hence Indonesian).
        private const val SURFACE_KOMENTAR = "komentar"
        private const val SURFACE_BALASAN = "balasan komentar"
        private const val SURFACE_TERKAIT = "terkait"
        private const val SURFACE_KANAL = "kanal"
        private const val SURFACE_DETAIL = "detail"
        private const val KIND_URAI = "urai"
        private const val KIND_KOSONG = "kosong"
        private const val MAX_FAILURE_DETAIL_CHARS = 120

        /** InnerTube search param filter: videos only. */
        private const val SEARCH_PARAMS_VIDEO_ONLY = "EgIQAQ%3D%3D"

        /** InnerTube search param filter: the Live feature (probed 2026-11-10). */
        private const val SEARCH_PARAMS_LIVE = "EgJAAQ%3D%3D"

        private const val MAX_SEED_ITEMS = 60
        private const val MAX_LIVE_ITEMS = 40

        /** The player-response cache is a courtesy, not an archive. */
        private const val MAX_PLAYER_CACHE_ENTRIES = 100

        /** A warm spare session is trusted for half an hour (design §7.2). */
        private const val BACKUP_TTL_MS = 30L * 60L * 1000L

        /** The Radar only probes formats bigger than the cold-start allowance. */
        private const val MIN_PROBE_BYTES = 4_000_000L

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
