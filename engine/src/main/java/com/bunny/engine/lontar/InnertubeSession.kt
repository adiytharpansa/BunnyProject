package com.bunny.engine.lontar

/**
 * One anonymous InnerTube session: the API key and visitor data every
 * request needs, harvested from YouTube's own homepage configuration —
 * the same bootstrap the official web client performs.
 */
data class InnertubeSession(
    val apiKey: String,
    val visitorData: String,
) {
    companion object {
        private val API_KEY_REGEX = Regex(""""INNERTUBE_API_KEY":\s*"([^"]+)"""")
        private val VISITOR_DATA_REGEX = Regex(""""visitorData":\s*"([^"]+)"""")

        /** Extracts a session from homepage HTML; null when markers are absent. */
        fun fromHomepageHtml(html: String): InnertubeSession? {
            val apiKey = API_KEY_REGEX.find(html)?.groupValues?.get(1) ?: return null
            val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.get(1) ?: return null
            return InnertubeSession(apiKey = apiKey, visitorData = visitorData)
        }
    }
}

/** The client persona Lontar presents to InnerTube (Android official app). */
object LontarPersona {
    const val CLIENT_NAME = "ANDROID"
    const val CLIENT_NAME_ID = "3"
    const val CLIENT_VERSION = "19.44.38"
    const val ANDROID_SDK_VERSION = 34
    const val USER_AGENT =
        "com.google.android.youtube/$CLIENT_VERSION (Linux; U; Android 14; id_ID)"
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val HL = "id"
    const val GL = "ID"
}

class LontarException(message: String) : Exception(message)
