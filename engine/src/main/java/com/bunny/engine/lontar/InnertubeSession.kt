package com.bunny.engine.lontar

/**
 * One anonymous InnerTube session: the API key, client version and visitor
 * data every request needs, harvested from YouTube's own homepage
 * configuration — the same bootstrap the official web client performs.
 * Harvesting (instead of hardcoding) lets the persona track what YouTube
 * currently serves.
 */
data class InnertubeSession(
    val apiKey: String,
    val clientVersion: String,
    val visitorData: String,
) {
    companion object {
        private val API_KEY_REGEX = Regex(""""INNERTUBE_API_KEY":\s*"([^"]+)"""")
        private val CLIENT_VERSION_REGEX = Regex(""""INNERTUBE_CLIENT_VERSION":\s*"([^"]+)"""")
        private val VISITOR_DATA_REGEX = Regex(""""visitorData":\s*"([^"]+?)"""")

        /**
         * Fallback when the homepage offers no client version marker. InnerTube
         * tolerates a range of client versions (verified 2026-11-10: this value
         * served search fine while the live version differed), so a missing
         * marker must not kill the whole session — the API key and visitor
         * data remain the truly required pieces.
         */
        const val FALLBACK_CLIENT_VERSION = "2.20241126.01.00"

        /** Extracts a session from homepage HTML; null when key markers are absent. */
        fun fromHomepageHtml(html: String): InnertubeSession? {
            val apiKey = API_KEY_REGEX.find(html)?.groupValues?.get(1) ?: return null
            val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.get(1) ?: return null
            val clientVersion = CLIENT_VERSION_REGEX.find(html)?.groupValues?.get(1)
                ?: FALLBACK_CLIENT_VERSION
            return InnertubeSession(
                apiKey = apiKey,
                clientVersion = clientVersion,
                visitorData = visitorData,
            )
        }
    }
}

/**
 * The client persona Lontar presents to InnerTube: the WEB client.
 *
 * Field finding (2026-11-10, reproduced from server error bodies): the
 * ANDROID client fails against the web API key with FAILED_PRECONDITION,
 * and browseId FEtrending answers INVALID_ARGUMENT on every client since
 * YouTube retired the trending page — while the WEB client serves search
 * and browse normally with the homepage-harvested key and visitor data.
 * Playback personas are a separate Fase 2 question; the metadata plane
 * (home, search, details) speaks WEB.
 */
object LontarPersona {
    const val CLIENT_NAME = "WEB"
    const val CLIENT_NAME_ID = "1"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val HL = "id"
    const val GL = "ID"
}

class LontarException(message: String) : Exception(message)
