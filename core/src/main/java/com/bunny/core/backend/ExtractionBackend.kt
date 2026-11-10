package com.bunny.core.backend

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
}
