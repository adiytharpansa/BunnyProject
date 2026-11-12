package com.bunny.engine.lontar

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a DASH manifest (MPD) from InnerTube adaptive formats — Lontar's
 * own generator, written for this project.
 *
 * YouTube's adaptive formats are DASH segments in disguise: every format
 * carries its media URL plus init/index byte ranges, which is exactly what
 * a static SegmentBase MPD describes. Formats are grouped into one
 * AdaptationSet per exact mimeType (codecs included), so the player sees
 * clean VP9 / AV1 / AVC video ladders and MP4 / WebM audio ladders.
 *
 * Only formats with direct URLs qualify; ciphered ones are skipped.
 * Returns null when nothing usable remains.
 */
object DashManifestBuilder {

    fun build(adaptiveFormats: JSONArray?, durationSeconds: Long?): String? {
        if (adaptiveFormats == null || adaptiveFormats.length() == 0) return null

        val videoSets = linkedMapOf<String, MutableList<JSONObject>>()
        val audioSets = linkedMapOf<String, MutableList<JSONObject>>()
        for (format in includedFormats(adaptiveFormats)) {
            val mime = format.optString("mimeType")
            when {
                mime.startsWith("video/") -> videoSets.getOrPut(mime) { mutableListOf() } += format
                mime.startsWith("audio/") -> audioSets.getOrPut(mime) { mutableListOf() } += format
            }
        }
        if (videoSets.isEmpty() && audioSets.isEmpty()) return null

        val durationAttr = durationSeconds?.takeIf { it > 0 }
            ?.let { " mediaPresentationDuration=\"PT${it}S\"" }
            .orEmpty()
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append(
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" " +
                "profiles=\"urn:mpeg:dash:profile:full:2011\" " +
                "minBufferTime=\"PT1.5S\" type=\"static\"$durationAttr>\n",
        )
        out.append(" <Period>\n")
        var setId = 0
        for ((mime, formats) in videoSets) appendSet(out, setId++, mime, formats, isVideo = true)
        for ((mime, formats) in audioSets) appendSet(out, setId++, mime, formats, isVideo = false)
        out.append(" </Period>\n</MPD>")
        return out.toString()
    }

    /**
     * The formats [build] would include, in response order — the
     * single inclusion rule both the manifest and [renditions]
     * read: a direct URL, an init range, an index range, and a
     * video/ or audio/ mime type. Ciphered formats (no URL) never
     * qualify.
     */
    private fun includedFormats(adaptiveFormats: JSONArray?): List<JSONObject> {
        if (adaptiveFormats == null) return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            if (format.optString("url").isEmpty()) continue
            if (format.optJSONObject("initRange") == null) continue
            if (format.optJSONObject("indexRange") == null) continue
            val mime = format.optString("mimeType")
            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
            out += format
        }
        return out
    }

    /**
     * Fase 8 mechanism 14: itag → lastModified for exactly the
     * formats [build] includes — the rendition identity of every
     * representation in the generated manifest, which the classic
     * road's spool recording is gated on. Formats whose response
     * named no rendition (or no itag) are simply absent: absent
     * means not recordable, never guessed.
     */
    fun renditions(adaptiveFormats: JSONArray?): Map<Int, Long> {
        val out = LinkedHashMap<Int, Long>()
        for (format in includedFormats(adaptiveFormats)) {
            val itag = format.optInt("itag", 0)
            val lastModified = format.opt("lastModified")
                ?.toString()?.toLongOrNull() ?: 0L
            if (itag > 0 && lastModified > 0) out[itag] = lastModified
        }
        return out
    }

    private fun appendSet(
        out: StringBuilder,
        id: Int,
        mime: String,
        formats: List<JSONObject>,
        isVideo: Boolean,
    ) {
        out.append("  <AdaptationSet id=\"$id\" mimeType=\"${escape(mime)}\" subsegmentAlignment=\"true\">\n")
        for (format in formats) {
            appendRepresentation(out, format, isVideo)
        }
        out.append("  </AdaptationSet>\n")
    }

    private fun appendRepresentation(out: StringBuilder, format: JSONObject, isVideo: Boolean) {
        val itag = format.optInt("itag")
        val bitrate = format.optLong("bitrate", 0)
        val codecs = codecsOf(format.optString("mimeType"))
        val init = format.getJSONObject("initRange")
        val index = format.getJSONObject("indexRange")
        out.append("   <Representation id=\"$itag\" bandwidth=\"$bitrate\"")
        if (codecs.isNotEmpty()) out.append(" codecs=\"${escape(codecs)}\"")
        if (isVideo) {
            val width = format.optInt("width")
            val height = format.optInt("height")
            val fps = format.optInt("fps")
            if (width > 0) out.append(" width=\"$width\"")
            if (height > 0) out.append(" height=\"$height\"")
            if (fps > 0) out.append(" frameRate=\"$fps\"")
        } else {
            val sampleRate = format.optString("audioSampleRate")
            if (sampleRate.isNotEmpty()) out.append(" audioSamplingRate=\"$sampleRate\"")
        }
        out.append(">\n")
        if (!isVideo) {
            val channels = format.optInt("audioChannels", 2)
            out.append(
                "    <AudioChannelConfiguration " +
                    "schemeIdUri=\"urn:mpeg:dash:23003-3:audio_channel_configuration:2011\" " +
                    "value=\"$channels\" />\n",
            )
        }
        out.append(
            "    <SegmentBase indexRange=\"${index.optString("start")}-${index.optString("end")}\">\n",
        )
        out.append(
            "     <Initialization range=\"${init.optString("start")}-${init.optString("end")}\" />\n",
        )
        out.append("    </SegmentBase>\n")
        out.append("    <BaseURL>${escape(format.optString("url"))}</BaseURL>\n")
        out.append("   </Representation>\n")
    }

    /** mimeType looks like: video/mp4; codecs="av01.0.08M.08" — pull the codecs out. */
    fun codecsOf(mimeType: String): String {
        val match = Regex("""codecs="([^"]+)"""").find(mimeType) ?: return ""
        return match.groupValues[1]
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
