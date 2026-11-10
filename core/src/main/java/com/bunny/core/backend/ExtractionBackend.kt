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

    suspend fun getVideoDetails(videoId: String): VideoDetails

    suspend fun getPlaybackStreams(videoId: String): PlaybackStreams

    /**
     * The newest uploads of one channel, newest first — the raw material
     * of Terbit (Fase 3). [channelId] is the UC… id from [VideoItem] or
     * [VideoDetails]. Pagination is not offered; an edition is finite.
     */
    suspend fun getChannelVideos(channelId: String): Page<VideoItem>
}
