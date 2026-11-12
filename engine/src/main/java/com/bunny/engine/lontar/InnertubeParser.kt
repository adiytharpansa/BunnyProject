package com.bunny.engine.lontar

import com.bunny.core.model.AccountInfo
import com.bunny.core.model.ChannelInfo
import com.bunny.core.model.CommentsPage
import com.bunny.core.model.DownloadFormat
import com.bunny.core.model.Page
import com.bunny.core.model.PlaybackStreams
import com.bunny.core.model.VideoDetails
import com.bunny.core.model.VideoComment
import com.bunny.core.model.VideoItem
import com.bunny.engine.lontar.sabr.SabrClientInfo
import com.bunny.engine.lontar.sabr.SabrFormat
import com.bunny.engine.lontar.sabr.SabrOpenData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns raw InnerTube JSON into Bunny domain models.
 *
 * The browse/search responses are deep renderer trees whose exact nesting
 * shifts between client versions, so video collection is a recursive walk:
 * any object holding a "videoRenderer" is a video, wherever it sits.
 * Player responses are flat by comparison and parsed directly.
 *
 * Pure functions over org.json — unit-tested with hand-written fixtures.
 */
object InnertubeParser {

    // ------------------------------------------------------------------ feeds

    fun parseFeedPage(response: JSONObject): Page<VideoItem> {
        val items = mutableListOf<VideoItem>()
        collectVideos(response, items)
        return Page(
            items = items.distinctBy { it.id },
            nextPage = findContinuation(response),
        )
    }

    private fun collectVideos(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                if (node.has("videoRenderer")) {
                    parseVideoRenderer(node.getJSONObject("videoRenderer"))?.let(out::add)
                }
                if (node.has("playlistVideoRenderer")) {
                    parsePlaylistVideoRenderer(node.getJSONObject("playlistVideoRenderer"))
                        ?.let(out::add)
                }
                if (node.has("lockupViewModel")) {
                    // Newer grids (the signed-in home among them) carry
                    // the same rows as lockups instead of videoRenderers.
                    parseLockupVideo(node.getJSONObject("lockupViewModel"))
                        ?.let(out::add)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectVideos(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideos(node.opt(i), out)
                }
            }
        }
    }

    private fun parseVideoRenderer(renderer: JSONObject): VideoItem? {
        val videoId = renderer.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val viewCountText = textOf(renderer.optJSONObject("viewCountText"))
        val isLive = isLiveRenderer(renderer, viewCountText)
        return VideoItem(
            id = videoId,
            title = textOf(renderer.optJSONObject("title")).ifEmpty {
                renderer.optJSONObject("headline")?.let { textOf(it) }.orEmpty()
            },
            channelId = channelBrowseId(renderer),
            channelName = textOf(renderer.optJSONObject("longBylineText")).ifEmpty {
                textOf(renderer.optJSONObject("shortBylineText"))
            },
            channelAvatarUrl = channelAvatarUrl(renderer),
            thumbnailUrl = bestThumbnail(renderer.optJSONObject("thumbnail")),
            durationSeconds = renderer.optJSONObject("lengthText")
                ?.let { parseDuration(textOf(it)) },
            viewCount = viewCountText?.let { parseCount(it) },
            uploadedText = renderer.optJSONObject("publishedTimeText")
                ?.let { textOf(it) }
                ?.takeIf { it.isNotEmpty() },
            isLive = isLive,
        )
    }

    /**
     * Playlist rows (a channel's uploads playlist, used by Terbit) carry a
     * slimmer renderer than search/browse video rows: no publishedTimeText,
     * views and age are combined in "videoInfo".
     */
    private fun parsePlaylistVideoRenderer(renderer: JSONObject): VideoItem? {
        val videoId = renderer.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val infoText = textOf(renderer.optJSONObject("videoInfo"))
        return VideoItem(
            id = videoId,
            title = textOf(renderer.optJSONObject("title")),
            channelId = channelBrowseId(renderer),
            channelName = textOf(renderer.optJSONObject("shortBylineText")),
            thumbnailUrl = bestThumbnail(renderer.optJSONObject("thumbnail")),
            durationSeconds = renderer.optJSONObject("lengthText")
                ?.let { parseDuration(textOf(it)) },
            viewCount = infoText.takeIf { it.isNotEmpty() }?.let { parseCount(it) },
            uploadedText = infoText.takeIf { it.contains("•") }
                ?.substringAfter("•")?.trim()?.takeIf { it.isNotEmpty() },
            isLive = false,
        )
    }

    private fun isLiveRenderer(renderer: JSONObject, viewCountText: String?): Boolean {
        val badges = renderer.optJSONArray("badges") ?: JSONArray()
        for (i in 0 until badges.length()) {
            val badge = badges.optJSONObject(i)?.optJSONObject("metadataBadgeRenderer")
                ?: continue
            if (badge.optString("label") == "LIVE" ||
                badge.optJSONObject("icon")?.optString("iconType") == "LIVE"
            ) {
                return true
            }
        }
        return viewCountText?.contains("watching", ignoreCase = true) == true
    }

    /**
     * Search/browse video rows carry the channel's real avatar under
     * channelThumbnailSupportedRenderers (verified live 2026-11-11: present
     * on every search videoRenderer). Playlist rows do not carry it.
     */
    private fun channelAvatarUrl(renderer: JSONObject): String? =
        bestThumbnail(
            renderer.optJSONObject("channelThumbnailSupportedRenderers")
                ?.optJSONObject("channelThumbnailWithLinkRenderer")
                ?.optJSONObject("thumbnail"),
        )

    private fun channelBrowseId(renderer: JSONObject): String? {
        val byline = renderer.optJSONObject("longBylineText")
            ?: renderer.optJSONObject("shortBylineText")
            ?: return null
        val run = byline.optJSONArray("runs")?.optJSONObject(0) ?: return null
        return run.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun bestThumbnail(thumbnail: JSONObject?): String? {
        val thumbs = thumbnail?.optJSONArray("thumbnails") ?: return null
        if (thumbs.length() == 0) return null
        return thumbs.optJSONObject(thumbs.length() - 1)
            ?.optString("url")
            ?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------- related (watch next)

    /**
     * The watch page's own related list: the `next` response carries it
     * ready-made in secondaryResults as lockupViewModel rows — YouTube's
     * recommender has already ranked them, so ORDER IS PRESERVED here
     * and never re-sorted (verified live 2026-11-11: 20 rows for an
     * anonymous WEB session, real titles, channel avatars and counts).
     * Only video lockups become items; playlist/mix lockups are skipped.
     */
    fun parseRelatedVideos(response: JSONObject): List<VideoItem> {
        val secondary = findObject(response) { it.has("secondaryResults") }
            ?.optJSONObject("secondaryResults") ?: return emptyList()
        val lockups = mutableListOf<JSONObject>()
        walkObjects(secondary) { obj ->
            obj.optJSONObject("lockupViewModel")?.let(lockups::add)
        }
        return lockups.mapNotNull(::parseLockupVideo).distinctBy { it.id }
    }

    private fun parseLockupVideo(lockup: JSONObject): VideoItem? {
        val type = lockup.optString("contentType")
        if (type.isNotEmpty() && type != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val videoId = lockup.optString("contentId").takeIf { it.isNotEmpty() }
            ?: return null
        val meta = lockup.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content").orEmpty()
        if (title.isEmpty()) return null

        // The duration (or a live marker) rides a badge over the
        // thumbnail; lockup durations use dots ("12.34" / "1.23.21").
        var durationSeconds: Long? = null
        var isLive = false
        val overlays = lockup.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONArray("overlays") ?: JSONArray()
        for (i in 0 until overlays.length()) {
            val badges = overlays.optJSONObject(i)
                ?.optJSONObject("thumbnailBottomOverlayViewModel")
                ?.optJSONArray("badges") ?: continue
            for (j in 0 until badges.length()) {
                val badgeText = badges.optJSONObject(j)
                    ?.optJSONObject("thumbnailBadgeViewModel")
                    ?.optString("text").orEmpty()
                if (badgeText.isEmpty()) continue
                if (badgeText.contains("LIVE", ignoreCase = true) ||
                    badgeText.contains("langsung", ignoreCase = true)
                ) {
                    isLive = true
                } else {
                    durationSeconds =
                        parseDuration(badgeText.replace('.', ':')) ?: durationSeconds
                }
            }
        }

        // Metadata rows: row 0 is the channel name; row 1 carries
        // "views • age" as separate parts whose accessibility labels hold
        // the full localized text ("702 x ditonton", "6 hari yang lalu")
        // while the visible content is abbreviated ("702", "6 h lalu").
        // The labels are what the UI shows.
        val rows = meta?.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows") ?: JSONArray()
        fun rowParts(index: Int): List<JSONObject> {
            val parts = rows.optJSONObject(index)?.optJSONArray("metadataParts")
                ?: return emptyList()
            return (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
        }
        fun partLabel(part: JSONObject): String =
            part.optString("accessibilityLabel").ifEmpty {
                part.optJSONObject("text")?.optString("content").orEmpty()
            }
        val statsParts = rowParts(1)
        val viewsLabel = statsParts.getOrNull(0)?.let { partLabel(it) }.orEmpty()
        val uploadedText = statsParts.getOrNull(1)?.let { partLabel(it) }
            ?.takeIf { it.isNotEmpty() }
        if (viewsLabel.contains("menonton") ||
            viewsLabel.contains("watching", ignoreCase = true)
        ) {
            isLive = true
        }

        return VideoItem(
            id = videoId,
            title = title,
            channelId = meta?.optJSONObject("rendererContext")
                ?.optJSONObject("commandContext")
                ?.optJSONObject("onTap")
                ?.optJSONObject("innertubeCommand")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
                ?.takeIf { it.isNotEmpty() },
            channelName = rowParts(0).firstOrNull()
                ?.optJSONObject("text")?.optString("content").orEmpty(),
            channelAvatarUrl = meta?.optJSONObject("image")
                ?.optJSONObject("decoratedAvatarViewModel")
                ?.optJSONObject("avatar")
                ?.optJSONObject("avatarViewModel")
                ?.optJSONObject("image")
                ?.let { bestSourceUrl(it) },
            thumbnailUrl = lockup.optJSONObject("contentImage")
                ?.optJSONObject("thumbnailViewModel")
                ?.optJSONObject("image")
                ?.let { bestSourceUrl(it) },
            durationSeconds = durationSeconds,
            viewCount = viewsLabel.takeIf { it.isNotEmpty() }
                ?.let { parseCount(it) },
            uploadedText = uploadedText,
            isLive = isLive,
        )
    }

    /** Widest image source of a view-model image object ({"sources":[...]}). */
    private fun bestSourceUrl(image: JSONObject): String? {
        val sources = image.optJSONArray("sources") ?: return null
        var best: String? = null
        var bestWidth = -1
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            if (source.optInt("width") >= bestWidth) {
                source.optString("url").takeIf { it.isNotEmpty() }?.let {
                    best = it
                    bestWidth = source.optInt("width")
                }
            }
        }
        return best
    }

    /**
     * Every directly downloadable file a player response offers
     * (Panen Bertingkat): adaptive formats first, then the combined
     * progressive ones. Entries without a direct URL (cipher-gated)
     * are skipped — a download cannot use a URL that is not there.
     */
    fun parseDownloadFormats(response: JSONObject): List<DownloadFormat> {
        val streaming = response.optJSONObject("streamingData") ?: return emptyList()
        val out = mutableListOf<DownloadFormat>()
        fun collect(array: JSONArray?, progressive: Boolean) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val f = array.optJSONObject(i) ?: continue
                val url = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
                val mime = f.optString("mimeType")
                    .substringBefore(';').trim().lowercase()
                if (mime.isEmpty()) continue
                out += DownloadFormat(
                    itag = f.optInt("itag"),
                    url = url,
                    mimeType = mime,
                    isAudioOnly = mime.startsWith("audio/"),
                    isProgressive = progressive,
                    height = f.optInt("height"),
                    bitrate = f.optLong("bitrate"),
                    contentLength = f.optString("contentLength")
                        .toLongOrNull(),
                )
            }
        }
        collect(streaming.optJSONArray("adaptiveFormats"), progressive = false)
        collect(streaming.optJSONArray("formats"), progressive = true)
        return out
    }

    /**
     * The signed-in account's own header from the account-menu
     * response: name and photo as YouTube reports them. Best-effort —
     * any shape drift reads as null, never as an invented identity.
     */
    fun parseAccountInfo(response: JSONObject): AccountInfo? {
        val header = findObject(response) { it.has("activeAccountHeaderRenderer") }
            ?.optJSONObject("activeAccountHeaderRenderer") ?: return null
        val name = textOf(header.optJSONObject("accountName"))
            .takeIf { it.isNotEmpty() } ?: return null
        val avatar = bestThumbnail(header.optJSONObject("accountPhoto"))
        return AccountInfo(name = name, avatarUrl = avatar)
    }

    /**
     * A channel page's real identity. Name, description and avatar come
     * from metadata.channelMetadataRenderer; the published counts live
     * in the page header's metadata parts as localized text
     * ("2,86 jt subscriber", "158 rb video") — both verified live
     * 2026-11-11 against a real channel browse.
     */
    fun parseChannelInfo(response: JSONObject, channelId: String): ChannelInfo? {
        val meta = response.optJSONObject("metadata")
            ?.optJSONObject("channelMetadataRenderer") ?: return null
        val name = meta.optString("title").takeIf { it.isNotEmpty() } ?: return null
        val headerTexts = mutableListOf<String>()
        collectMetadataPartTexts(response.optJSONObject("header"), headerTexts)
        return ChannelInfo(
            channelId = channelId,
            name = name,
            avatarUrl = bestThumbnail(meta.optJSONObject("avatar")),
            description = meta.optString("description"),
            subscriberText = headerTexts.firstOrNull {
                it.contains("subscriber", ignoreCase = true)
            },
            videoCountText = headerTexts.firstOrNull {
                !it.contains("subscriber", ignoreCase = true) &&
                    it.contains("video", ignoreCase = true) &&
                    it.any { ch -> ch.isDigit() }
            },
        )
    }

    /** Every metadataParts text.content in the tree, in document order. */
    private fun collectMetadataPartTexts(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                val parts = node.optJSONArray("metadataParts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        parts.optJSONObject(i)?.optJSONObject("text")
                            ?.optString("content")
                            ?.takeIf { it.isNotEmpty() }
                            ?.let(out::add)
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectMetadataPartTexts(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectMetadataPartTexts(node.opt(i), out)
                }
            }
        }
    }

    /**
     * The token that opens a video's comment section, from a watch-page
     * (next) response: the continuation inside the section whose
     * sectionIdentifier is "comment-item-section". Null when the video
     * has no comment section (e.g. comments are turned off).
     */
    fun findCommentsToken(response: JSONObject): String? {
        val section = findObject(response) {
            it.optString("sectionIdentifier") == "comment-item-section"
        } ?: return null
        return findContinuation(section)
    }

    /**
     * The watch page's owner photo: videoSecondaryInfoRenderer's
     * videoOwnerRenderer thumbnails (verified live 2026-11-11).
     */
    fun parseOwnerAvatarUrl(response: JSONObject): String? {
        val secondary = findObject(response) {
            it.optJSONObject("videoSecondaryInfoRenderer") != null
        }?.optJSONObject("videoSecondaryInfoRenderer") ?: return null
        val owner = secondary.optJSONObject("owner")
            ?.optJSONObject("videoOwnerRenderer") ?: return null
        return bestThumbnail(owner.optJSONObject("thumbnail"))
    }

    /**
     * One page of comments from a comment-section continuation response.
     * Threads arrive as view models carrying only keys; the bodies live
     * in commentEntityPayload objects (framework updates) joined by
     * that key — both halves verified live 2026-11-11 (20 threads, 20
     * payloads, keys matching).
     */
    fun parseCommentsPage(response: JSONObject): CommentsPage {
        val payloads = commentPayloads(response)
        val items = mutableListOf<VideoComment>()
        var nextPage: String? = null
        // A real continuation response carries SEVERAL item containers:
        // one endpoint reloads just the comments header, another the
        // threads themselves (both verified live 2026-11-11). Every
        // container must be read — taking only the first one found
        // yields the header alone and zero comments (device test
        // 2026-11-11: "tidak tersedia" on every video).
        val children = continuationChildren(response)
        if (children.isNotEmpty()) {
            for (child in children) {
                child.optJSONObject("commentThreadRenderer")?.let { thread ->
                    parseCommentThread(thread, payloads)?.let(items::add)
                }
                if (child.has("continuationItemRenderer")) {
                    nextPage = findContinuation(child) ?: nextPage
                }
            }
        } else {
            walkObjects(response) { obj ->
                obj.optJSONObject("commentThreadRenderer")?.let { thread ->
                    parseCommentThread(thread, payloads)?.let(items::add)
                }
            }
        }
        return CommentsPage(
            items = items.distinctBy { it.id },
            nextPage = nextPage,
        )
    }

    /**
     * One page of a thread's replies. Verified live 2026-11-11: the
     * replies continuation answers with ONE append container whose
     * items are BARE comment view models (no thread wrapper) joined
     * to entity payloads exactly like the parent page, plus a trailing
     * continuation item for the next replies page.
     */
    fun parseRepliesPage(response: JSONObject): CommentsPage {
        val payloads = commentPayloads(response)
        val items = mutableListOf<VideoComment>()
        var nextPage: String? = null
        for (child in continuationChildren(response)) {
            child.optJSONObject("commentViewModel")?.let { vm ->
                parseCommentFromViewModel(vm, payloads)?.let(items::add)
            }
            if (child.has("continuationItemRenderer")) {
                nextPage = findContinuation(child) ?: nextPage
            }
        }
        return CommentsPage(
            items = items.distinctBy { it.id },
            nextPage = nextPage,
        )
    }

    /** Every entity payload in the response, keyed by its join key. */
    private fun commentPayloads(response: JSONObject): Map<String, JSONObject> {
        val payloads = HashMap<String, JSONObject>()
        walkObjects(response) { obj ->
            obj.optJSONObject("commentEntityPayload")?.let { payload ->
                payload.optString("key").takeIf { it.isNotEmpty() }
                    ?.let { key -> payloads[key] = payload }
            }
        }
        return payloads
    }

    /**
     * The item children of EVERY continuation container in the
     * response, in order (see the two-container lesson in
     * [parseCommentsPage]).
     */
    private fun continuationChildren(response: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        walkObjects(response) { obj ->
            val container = obj.optJSONObject("appendContinuationItemsAction")
                ?: obj.optJSONObject("reloadContinuationItemsCommand")
                ?: return@walkObjects
            val list = container.optJSONArray("continuationItems")
                ?: return@walkObjects
            for (i in 0 until list.length()) {
                list.optJSONObject(i)?.let(out::add)
            }
        }
        return out
    }

    private fun parseCommentThread(
        thread: JSONObject,
        payloads: Map<String, JSONObject>,
    ): VideoComment? {
        val base = parseCommentFromViewModel(
            thread.optJSONObject("commentViewModel"),
            payloads,
        ) ?: return null
        // The thread's replies door: a continuation token inside the
        // replies renderer (verified live 2026-11-11).
        val repliesToken = thread.optJSONObject("replies")?.let { replies ->
            var token: String? = null
            walkObjects(replies) { obj ->
                if (token == null) {
                    token = obj.optJSONObject("continuationCommand")
                        ?.optString("token")
                        ?.takeIf { it.isNotEmpty() }
                }
            }
            token
        }
        return base.copy(repliesContinuation = repliesToken)
    }

    /**
     * One comment from its view model, which arrives in two spellings:
     * nested (`commentViewModel.commentViewModel`, inside a thread) or
     * bare (a replies page item). The body always comes from the
     * entity payload the view model's key points at.
     */
    private fun parseCommentFromViewModel(
        viewModel: JSONObject?,
        payloads: Map<String, JSONObject>,
    ): VideoComment? {
        if (viewModel == null) return null
        val vm = viewModel.optJSONObject("commentViewModel") ?: viewModel
        val payload = vm.optString("commentKey")
            .takeIf { it.isNotEmpty() }
            ?.let { payloads[it] } ?: return null
        val props = payload.optJSONObject("properties") ?: return null
        val author = payload.optJSONObject("author")
        val toolbar = payload.optJSONObject("toolbar")
        return VideoComment(
            id = props.optString("commentId")
                .ifEmpty { vm.optString("commentId") },
            authorName = author?.optString("displayName").orEmpty(),
            authorAvatarUrl = author?.optString("avatarThumbnailUrl")
                ?.takeIf { it.isNotEmpty() },
            authorChannelId = author?.optString("channelId")
                ?.takeIf { it.isNotEmpty() },
            publishedText = props.optString("publishedTime")
                .takeIf { it.isNotEmpty() },
            text = props.optJSONObject("content")?.optString("content").orEmpty(),
            likeCountText = toolbar?.optString("likeCountNotliked")
                ?.takeIf { it.isNotEmpty() },
            replyCount = toolbar?.optString("replyCount")?.toIntOrNull() ?: 0,
            isCreatorHearted = !toolbar?.optString("heartActiveTooltip")
                .isNullOrEmpty(),
        )
    }

    /** First object in the tree matching [predicate], depth-first. */
    private fun findObject(
        node: Any?,
        predicate: (JSONObject) -> Boolean,
    ): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (predicate(node)) return node
                val keys = node.keys()
                while (keys.hasNext()) {
                    findObject(node.opt(keys.next()), predicate)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findObject(node.opt(i), predicate)?.let { return it }
                }
            }
        }
        return null
    }

    /** Visits every object in the tree, parents before children. */
    private fun walkObjects(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visit(node)
                val keys = node.keys()
                while (keys.hasNext()) {
                    walkObjects(node.opt(keys.next()), visit)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkObjects(node.opt(i), visit)
                }
            }
        }
    }

    /** First continuation token found anywhere in the tree, if any. */
    fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
                node.optJSONObject("nextContinuationData")
                    ?.optString("continuation")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findContinuation(node.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findContinuation(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    // ----------------------------------------------------------------- player

    fun parseVideoDetails(player: JSONObject): VideoDetails {
        val details = player.optJSONObject("videoDetails")
            ?: throw LontarException("Respons player tidak memuat videoDetails")
        val microformat = player.optJSONObject("microformat")
            ?.optJSONObject("playerMicroformatRenderer")
        return VideoDetails(
            id = details.optString("videoId"),
            title = details.optString("title"),
            description = details.optString("shortDescription"),
            channelId = details.optString("channelId").takeIf { it.isNotEmpty() },
            channelName = details.optString("author"),
            viewCount = details.optString("viewCount").toLongOrNull(),
            likeCount = null, // Not exposed by the player endpoint.
            uploadedText = microformat?.optString("publishDate")?.takeIf { it.isNotEmpty() },
            isLive = details.optBoolean("isLiveContent", false),
            thumbnailUrl = bestThumbnail(details.optJSONObject("thumbnail")),
        )
    }

    fun parsePlaybackStreams(player: JSONObject, nowEpochMs: Long): PlaybackStreams {
        val playability = player.optJSONObject("playabilityStatus")
        if (playability != null && playability.optString("status") != "OK") {
            val reason = playability.optString("reason", "Video tidak dapat diputar")
            throw LontarException("YouTube menolak memutar video ini: $reason")
        }
        val streaming = player.optJSONObject("streamingData")
            ?: return PlaybackStreams(null, null, null, null)
        // InnerTube sends expiresInSeconds as a JSON string, and org.json's
        // optLong does not coerce strings — read it flexibly (CI run #1
        // caught this: expiry silently came back null).
        val expiresAt = streaming.opt("expiresInSeconds")?.toString()?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { nowEpochMs + it * 1000 }

        // Progressive (muxed) formats carrying a direct URL can play as-is.
        // Ciphered formats (signatureCipher) are skipped rather than faked:
        // the ladder's player personas return direct URLs, so no cipher
        // solver is on the playback path (see PlayerPersona).
        var progressive: String? = null
        var bestBitrate = -1L
        val formats = streaming.optJSONArray("formats") ?: JSONArray()
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            val url = format.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val bitrate = format.optLong("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                progressive = url
            }
        }

        // Adaptive formats become Bunny's own DASH manifest (Fase 2): the
        // server rarely hands one out, so Lontar generates it from the
        // formats' URLs and byte ranges. See DashManifestBuilder.
        val durationSeconds = player.optJSONObject("videoDetails")
            ?.opt("lengthSeconds")?.toString()?.toLongOrNull()
        val adaptiveFormats = streaming.optJSONArray("adaptiveFormats")
        val dashContent = DashManifestBuilder.build(
            adaptiveFormats,
            durationSeconds,
        )

        return PlaybackStreams(
            dashManifestUrl = streaming.optString("dashManifestUrl").takeIf { it.isNotEmpty() },
            hlsUrl = streaming.optString("hlsManifestUrl").takeIf { it.isNotEmpty() },
            progressiveUrl = progressive,
            expiresAtEpochMs = expiresAt,
            dashManifestContent = dashContent,
            estimatedSizeBytes = estimateAdaptiveSize(
                adaptiveFormats,
                durationSeconds,
            ),
            tracking = parsePlaybackTracking(player),
            dashRenditions = DashManifestBuilder.renditions(adaptiveFormats),
        )
    }

    /**
     * Fase 8 (Anyam): lifts the SABR offer out of a player response —
     * the serverAbrStreamingUrl, the ustreamer configuration (found by
     * a bounded recursive search, the way the proving probe found it:
     * its exact parentage inside playerConfig is not a contract), the
     * adaptive format registry, and the opening pins (richest audio,
     * richest video — the server may adopt its own pins after init,
     * and the Jatah ceiling that will discipline this choice arrives
     * in Langkah 5).
     *
     * Null when the road is not offered: unplayable, live (SABR stays
     * off live by design), or any required field missing. A null here
     * is not an error — the classic road simply remains the only one.
     */
    fun parseSabrOpen(
        player: JSONObject,
        persona: PlayerPersona,
        capHeightPx: Int? = null,
        ceiling: com.bunny.engine.lontar.sabr.DeviceCodecCeiling =
            com.bunny.engine.lontar.sabr.DeviceCodecCeiling.UNKNOWN,
    ): SabrOpenData? {
        val playability = player.optJSONObject("playabilityStatus") ?: return null
        if (playability.optString("status") != "OK") return null
        val details = player.optJSONObject("videoDetails") ?: return null
        if (details.optBoolean("isLive", false)) return null
        val videoId = details.optString("videoId").takeIf { it.isNotEmpty() }
            ?: return null
        val streaming = player.optJSONObject("streamingData") ?: return null
        val sabrUrl = streaming.optString("serverAbrStreamingUrl")
            .takeIf { it.isNotEmpty() } ?: return null
        val ustreamerB64 = findStringRecursive(
            player.optJSONObject("playerConfig"),
            "videoPlaybackUstreamerConfig",
            depth = 0,
        ) ?: return null
        val ustreamer = decodeBase64Lenient(ustreamerB64) ?: return null

        val formatsArray = streaming.optJSONArray("adaptiveFormats") ?: return null
        val formats = ArrayList<SabrFormat>()
        for (i in 0 until formatsArray.length()) {
            val f = formatsArray.optJSONObject(i) ?: continue
            val itag = f.optInt("itag", 0)
            val mimeType = f.optString("mimeType")
            if (itag <= 0 || mimeType.isEmpty()) continue
            formats += SabrFormat(
                itag = itag,
                mimeType = mimeType,
                bitrate = f.optLong("bitrate", 0).toInt(),
                lastModified = f.opt("lastModified")?.toString()?.toLongOrNull(),
                xtags = f.optString("xtags").takeIf { it.isNotEmpty() },
                height = f.optInt("height", 0),
            )
        }
        if (formats.isEmpty()) return null
        val audioPin = formats.filter { it.isAudio }.maxByOrNull { it.bitrate }
            ?: return null
        // Mechanisms 6 + 15: the opening video pin is chosen under
        // the Jatah cap and the device's hardware codec ceiling,
        // not by bitrate alone.
        val videoPin = com.bunny.engine.lontar.sabr.chooseVideoPin(
            formats, capHeightPx, ceiling,
        ) ?: return null
        val clientInfo = SabrClientInfo(
            clientName = persona.clientNameId.toIntOrNull() ?: return null,
            clientVersion = persona.clientVersion,
            osName = persona.clientFields["osName"] as? String ?: return null,
            osVersion = persona.clientFields["osVersion"] as? String ?: return null,
        )
        return SabrOpenData(
            videoId = videoId,
            sabrUrl = sabrUrl,
            ustreamerConfig = ustreamer,
            formats = formats,
            audioPin = audioPin,
            videoPin = videoPin,
            durationSeconds = details.opt("lengthSeconds")?.toString()
                ?.toIntOrNull() ?: 0,
            clientInfo = clientInfo,
            userAgent = persona.userAgent,
            tracking = parsePlaybackTracking(player),
            capHeightPx = capHeightPx,
            // M11: the session adopts pins under this same ceiling.
            ceiling = ceiling,
        )
    }

    /** Bounded recursive key search over a JSON subtree. */
    private fun findStringRecursive(
        node: JSONObject?,
        key: String,
        depth: Int,
    ): String? {
        if (node == null || depth > 6) return null
        val direct = node.optString(key).takeIf { it.isNotEmpty() }
        if (direct != null) return direct
        val names = node.names() ?: return null
        for (i in 0 until names.length()) {
            val child = node.optJSONObject(names.getString(i)) ?: continue
            val found = findStringRecursive(child, key, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /** URL-safe or standard base64, padding optional (probe recipe). */
    private fun decodeBase64Lenient(value: String): ByteArray? {
        val padded = value.trim().let {
            it + "=".repeat((4 - it.length % 4) % 4)
        }
        return try {
            java.util.Base64.getUrlDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            try {
                java.util.Base64.getDecoder().decode(padded)
            } catch (e2: IllegalArgumentException) {
                null
            }
        }
    }

    /**
     * Selera Tamu (Langkah 1): the stats addresses the player
     * response itself hands out for reporting this view — the
     * playback-start and watchtime URLs under `playbackTracking`.
     * Null when the response carries neither.
     */
    fun parsePlaybackTracking(
        player: JSONObject,
    ): com.bunny.core.model.PlaybackTrackingUrls? {
        val tracking = player.optJSONObject("playbackTracking")
            ?: return null
        fun baseUrl(name: String): String? =
            tracking.optJSONObject(name)
                ?.optString("baseUrl")
                ?.takeIf { it.isNotEmpty() }
        val playback = baseUrl("videostatsPlaybackUrl")
        val watchtime = baseUrl("videostatsWatchtimeUrl")
        if (playback == null && watchtime == null) return null
        return com.bunny.core.model.PlaybackTrackingUrls(playback, watchtime)
    }

    /**
     * Timbangan at the player: what watching this video costs at the
     * reference quality, from the REAL adaptive formats on offer — the
     * richest video format at or below 480p (or the leanest above it)
     * plus the richest audio format, bitrates times duration. Null when
     * the inputs are missing; a wrong number is worse than none.
     */
    private fun estimateAdaptiveSize(
        adaptiveFormats: JSONArray?,
        durationSeconds: Long?,
    ): Long? {
        if (adaptiveFormats == null || durationSeconds == null || durationSeconds <= 0) {
            return null
        }
        var bestAtOrBelow480 = 0L
        var leanestVideo = Long.MAX_VALUE
        var bestAudio = 0L
        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val bitrate = format.optLong("bitrate", 0)
            if (bitrate <= 0) continue
            val mimeType = format.optString("mimeType")
            when {
                mimeType.startsWith("audio/") -> {
                    if (bitrate > bestAudio) bestAudio = bitrate
                }
                mimeType.startsWith("video/") -> {
                    if (bitrate < leanestVideo) leanestVideo = bitrate
                    if (format.optInt("height", 0) in 1..480 && bitrate > bestAtOrBelow480) {
                        bestAtOrBelow480 = bitrate
                    }
                }
            }
        }
        val videoBitrate = when {
            bestAtOrBelow480 > 0 -> bestAtOrBelow480
            leanestVideo != Long.MAX_VALUE -> leanestVideo
            else -> return null
        }
        return (videoBitrate + bestAudio) * durationSeconds / 8
    }

    // ---------------------------------------------------------------- helpers

    /** InnerTube text object: {"simpleText": "..."} or {"runs":[{"text":...}]}. */
    fun textOf(textObject: JSONObject?): String {
        if (textObject == null) return ""
        textObject.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = textObject.optJSONArray("runs") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until runs.length()) {
            builder.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return builder.toString()
    }

    /** "12:34" / "1:02:03" -> seconds. Null when unparseable. */
    fun parseDuration(text: String): Long? {
        val parts = text.trim().split(":").map { it.toLongOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    /**
     * "1,234,567 views" / "1.2M views" / "321K watching" / "No views" -> count.
     * Indonesian counts abbreviate with a SEPARATE word — "1,2 rb" (ribu),
     * "3,5 jt" (juta), "1,1 M" (miliar) — while English glues the suffix
     * to the number; both shapes are handled. Full numbers use '.' or ','
     * as thousands separators; abbreviated ones use them as the decimal
     * separator.
     */
    /**
     * The identity inside an `account/accounts_list` reply (Satu
     * Ketuk probe): accountItem objects sit under
     * accountSectionListRenderer → accountItemSectionRenderer; the
     * walk is recursive so the exact nesting depth is not assumed.
     * The selected account wins; otherwise the first one is read.
     * Shape distilled from a live-verified reference (2026-07-27):
     * accountName/channelHandle/accountByline are simpleText fields,
     * the byline carries the account's email.
     */
    fun parseAccountItem(root: JSONObject): BearerAccount? {
        val items = mutableListOf<JSONObject>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("accountItem")?.let { items += it }
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        val chosen = items.firstOrNull { it.optBoolean("isSelected") }
            ?: items.firstOrNull()
            ?: return null
        val name = chosen.optJSONObject("accountName")
            ?.optString("simpleText")?.takeIf { it.isNotEmpty() }
            ?: return null
        val photo = chosen.optJSONObject("accountPhoto")
            ?.optJSONArray("thumbnails")
        var avatar: String? = null
        var bestWidth = -1
        if (photo != null) {
            for (i in 0 until photo.length()) {
                val thumb = photo.optJSONObject(i) ?: continue
                val width = thumb.optInt("width")
                if (width >= bestWidth) {
                    bestWidth = width
                    avatar = thumb.optString("url").takeIf { it.isNotEmpty() }
                }
            }
        }
        return BearerAccount(
            name = name,
            handle = chosen.optJSONObject("channelHandle")
                ?.optString("simpleText")?.takeIf { it.isNotEmpty() },
            email = chosen.optJSONObject("accountByline")
                ?.optString("simpleText")?.takeIf { it.isNotEmpty() },
            avatarUrl = avatar,
        )
    }


    fun parseCount(text: String): Long? {
        val cleaned = text.trim().lowercase()
        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith("no ")) return 0L
        val tokens = cleaned.split(" ").filter { it.isNotEmpty() }
        val first = tokens.firstOrNull() ?: return null
        val multiplier = when {
            first.endsWith("k") || tokens.getOrNull(1) == "rb" -> 1_000L
            first.endsWith("m") || tokens.getOrNull(1) == "jt" -> 1_000_000L
            first.endsWith("b") || tokens.getOrNull(1) == "m" -> 1_000_000_000L
            else -> 1L
        }
        val numeric = first.trimEnd('k', 'm', 'b')
        return if (multiplier > 1) {
            (numeric.replace(",", ".").toDoubleOrNull()?.times(multiplier))?.toLong()
        } else {
            numeric.replace(",", "").replace(".", "").toLongOrNull()
        }
    }
}

/** The identity an `account/accounts_list` reply names (Satu Ketuk). */
data class BearerAccount(
    val name: String,
    val handle: String?,
    val email: String?,
    val avatarUrl: String?,
)
