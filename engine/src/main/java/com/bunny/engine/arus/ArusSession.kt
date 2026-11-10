package com.bunny.engine.arus

import org.json.JSONObject
import java.util.Base64

/**
 * Arus A1 — one format as the player response describes it. [directUrl]
 * and the init range matter beyond the classic path: A0 found the SABR
 * conversation carries NO initialization segment for this persona —
 * the init bytes come from the format's own URL at [initRangeStart] ..
 * [initRangeEnd], from the very same player response.
 */
data class ArusFormat(
    val itag: Int,
    val mimeType: String,
    val bitrate: Long,
    val lastModified: Long,
    val width: Int = 0,
    val height: Int = 0,
    val directUrl: String? = null,
    val initRangeStart: Long = 0,
    val initRangeEnd: Long = -1,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video")
    val isAudio: Boolean get() = mimeType.startsWith("audio")
    val formatId: FormatId get() = FormatId(itag, lastModified)
}

/**
 * Everything one SABR conversation needs, harvested from one player
 * response: the conversation URL, the ustreamer config blob the server
 * expects echoed back in every request, and the format table.
 */
class ArusSession(
    val videoId: String,
    val sabrUrl: String,
    val ustreamerConfig: ByteArray,
    val formats: List<ArusFormat>,
    val approxDurationMs: Long?,
) {
    fun videoFormats(): List<ArusFormat> = formats.filter { it.isVideo }
    fun audioFormats(): List<ArusFormat> = formats.filter { it.isAudio }

    companion object {

        /**
         * Parses a player response into a session, or returns null when
         * the response carries no SABR conversation (the classic ladder
         * remains the answer for those). The ustreamer config arrives
         * base64'd — URL-safe in practice; both alphabets are tried.
         */
        fun parsePlayerResponse(json: JSONObject, videoId: String): ArusSession? {
            val streaming = json.optJSONObject("streamingData") ?: return null
            val sabrUrl = streaming.optString("serverAbrStreamingUrl")
                .takeIf { it.isNotBlank() } ?: return null
            val ustreamerB64 = json.optJSONObject("playerConfig")
                ?.optJSONObject("mediaCommonConfig")
                ?.optJSONObject("mediaUstreamerRequestConfig")
                ?.optString("videoPlaybackUstreamerConfig")
                ?.takeIf { it.isNotBlank() } ?: return null
            val ustreamer = decodeBase64(ustreamerB64) ?: return null

            val formats = mutableListOf<ArusFormat>()
            val array = streaming.optJSONArray("adaptiveFormats")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val f = array.optJSONObject(i) ?: continue
                    val init = f.optJSONObject("initRange")
                    formats += ArusFormat(
                        itag = f.optInt("itag"),
                        mimeType = f.optString("mimeType"),
                        bitrate = f.optLong("bitrate"),
                        lastModified = f.optString("lastModified").toLongOrNull()
                            ?: f.optLong("lastModified"),
                        width = f.optInt("width"),
                        height = f.optInt("height"),
                        directUrl = f.optString("url").takeIf { it.isNotBlank() },
                        initRangeStart = init?.optString("start")?.toLongOrNull() ?: 0,
                        initRangeEnd = init?.optString("end")?.toLongOrNull() ?: -1,
                    )
                }
            }
            val durationMs = json.optJSONObject("videoDetails")
                ?.optString("lengthSeconds")?.toLongOrNull()?.times(1000)
            return ArusSession(videoId, sabrUrl, ustreamer, formats, durationMs)
        }

        private fun decodeBase64(value: String): ByteArray? {
            val cleaned = value.trim()
            return try {
                Base64.getUrlDecoder().decode(cleaned)
            } catch (e: IllegalArgumentException) {
                try {
                    Base64.getDecoder().decode(cleaned)
                } catch (e2: IllegalArgumentException) {
                    null
                }
            }
        }
    }
}
