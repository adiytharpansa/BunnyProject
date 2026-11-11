package com.bunny.core.backend

import com.bunny.core.model.AccountInfo
import com.bunny.core.model.ChannelInfo
import com.bunny.core.model.CommentsPage
import com.bunny.core.model.DownloadFormats
import com.bunny.core.model.PersonaHealthReport
import com.bunny.core.model.Page
import com.bunny.core.model.PlaybackStreams
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoItem

/**
 * The single contract between Bunny's UI and its YouTube engine.
 *
 * Rules every implementation must keep:
 * - Return Bunny domain models only. Engine internals (raw InnerTube JSON,
 *   renderer trees) never leak past the implementation module.
 * - Suspend functions; throw on failure and let the caller map errors.
 * - Pagination is an opaque [Page.nextPage] token owned by the backend.
 *
 * The one implementation is Lontar in :engine — Bunny's own from-scratch
 * InnerTube engine. No third-party YouTube code is used anywhere.
 * See docs/DESIGN.md.
 */
interface ExtractionBackend {

    /** Stable identifier for diagnostics, e.g. "lontar". */
    val id: String

    /** Cheap liveness probe for status displays; must not throw. */
    suspend fun healthCheck(): String = id

    suspend fun getHomeFeed(page: String?): Page<VideoItem>

    suspend fun search(query: String, page: String?): Page<VideoItem>

    /**
     * Live search suggestions for the search box — the official app
     * serves them from a dedicated provider fed by Google's
     * suggestqueries service (REA dissection 2026-11-10). Best-effort:
     * an empty list is a valid answer and must never fail the search.
     */
    suspend fun getSearchSuggestions(query: String): List<String> = emptyList()

    /**
     * Streams live right now — the Live tab (2026-11-10). A finite
     * composed list like [getChannelVideos]; no pagination. Backends
     * without a live source return an empty page.
     */
    suspend fun getLiveFeed(): Page<VideoItem> =
        Page(items = emptyList(), nextPage = null)

    suspend fun getVideoDetails(videoId: String): VideoDetails

    suspend fun getPlaybackStreams(videoId: String): PlaybackStreams

    /**
     * The newest uploads of one channel, newest first — the raw material
     * of Terbit (Fase 3). [channelId] is the UC… id from [VideoItem] or
     * [VideoDetails]. Pagination is not offered; an edition is finite.
     */
    suspend fun getChannelVideos(channelId: String): Page<VideoItem>

    /**
     * The channel's real page header: name, photo, description and the
     * published subscriber/video counts. Null when the backend has no
     * channel source or the page carried no metadata.
     */
    suspend fun getChannelInfo(channelId: String): ChannelInfo? = null

    /**
     * A video's real comments, newest page first; [page] is the opaque
     * token from the previous page (null = first page). Backends
     * without a comment source return an empty page. The first page
     * also carries the watch page's owner photo.
     */
    suspend fun getComments(videoId: String, page: String? = null): CommentsPage =
        CommentsPage()

    /**
     * One page of a comment thread's replies, loaded with the opaque
     * [continuation] a top-level comment carries. Replies arrive as
     * bare comment view models (no thread wrapper) joined to the same
     * entity payloads as the parent page — verified live 2026-11-11.
     * Best-effort: an empty page means the replies could not be read.
     */
    suspend fun getCommentReplies(continuation: String): CommentsPage =
        CommentsPage()

    /**
     * The downloadable files of one video (Panen Bertingkat): the
     * winning player response's formats with direct URLs. Empty when
     * the backend cannot offer files (e.g. cipher-gated sources).
     */
    suspend fun getDownloadFormats(videoId: String): DownloadFormats =
        DownloadFormats()

    // ------------------------------------------------------ lontar hidup

    /** The Radar's latest per-persona verdicts, oldest evidence included. */
    suspend fun engineHealth(): List<PersonaHealthReport> = emptyList()

    /**
     * Runs a fresh Engine Health Radar check now: for every ladder
     * persona, a canary player call plus byte probes past the
     * cold-start allowance. Costs on the order of a megabyte; the
     * caller decides when that is appropriate (unmetered by default,
     * or the user's own "check again" tap).
     */
    suspend fun runEngineHealthCheck(): List<PersonaHealthReport> = emptyList()

    /**
     * Playback evidence for the Radar: the stream of [personaName]
     * was refused mid-play (HTTP 403 after real bytes had flowed).
     * Recorded as a suspected GVS gating for this session and network.
     */
    suspend fun reportPlaybackSuspectedGvs(personaName: String) = Unit

    /**
     * Fresh streams for playback recovery: forgets any cached player
     * response for [videoId], demotes [failedPersonaName] in this
     * session's ladder, and resolves again — the recovery path then
     * continues the same video from the saved position.
     */
    suspend fun getRefreshedPlaybackStreams(
        videoId: String,
        failedPersonaName: String?,
    ): PlaybackStreams = getPlaybackStreams(videoId)

    /** Human-readable active ladder + why, for the Diagnostics block. */
    fun ladderDescription(): String = ""

    /**
     * The watch page's own related list for [videoId] — the videos
     * YouTube's recommender itself lines up next, IN THE SERVER'S ORDER
     * (that order is the algorithm's output; re-sorting it locally would
     * throw the ranking away). Best-effort: backends without a related
     * source return an empty list and the caller shows its empty state —
     * Bunny never substitutes a locally composed list (user rule:
     * everything shown is real data, no fallbacks).
     */
    suspend fun getRelatedVideos(videoId: String): List<VideoItem> = emptyList()

    /**
     * The signed-in account's own home list, in the server's order —
     * the personal counterpart of the watch page's related list
     * (Fase 5). Only meaningful while the engine holds login cookies;
     * backends or sessions without a login return an empty page and
     * the caller shows its empty state (no local substitute, by the
     * same no-fallback rule as [getRelatedVideos]).
     */
    suspend fun getPersonalHomeFeed(page: String? = null): Page<VideoItem> =
        Page(items = emptyList(), nextPage = null)

    /**
     * The signed-in account's name and photo as YouTube reports them.
     * Null when signed out or when the account response carried none.
     */
    suspend fun getAccountInfo(): AccountInfo? = null
}
