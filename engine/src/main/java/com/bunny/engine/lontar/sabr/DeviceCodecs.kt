package com.bunny.engine.lontar.sabr

import android.media.MediaCodecList
import android.os.Build

/** Codec families the SABR pin choice distinguishes (mechanism 15). */
enum class CodecFamily { AV1, VP9, AVC, OTHER }

/** The codec family a format's mimeType declares. */
fun codecFamilyOf(mimeType: String): CodecFamily = when {
    mimeType.contains("av01") -> CodecFamily.AV1
    mimeType.contains("vp09") || mimeType.contains("vp9") -> CodecFamily.VP9
    mimeType.contains("avc1") -> CodecFamily.AVC
    else -> CodecFamily.OTHER
}

/**
 * What this device can decode in HARDWARE, per family — the codec
 * ceiling of design mechanism 15: a format the chip can only decode
 * in software costs battery and heat for no picture gain, so pins
 * prefer hardware families (on the A15: VP9/AVC first; AV1 only
 * when a hardware decoder actually exists).
 *
 * [UNKNOWN] filters nothing: an unprobed ceiling must never
 * silently narrow the user's choices.
 */
data class DeviceCodecCeiling(
    val av1Hardware: Boolean,
    val vp9Hardware: Boolean,
) {
    fun hardwareOk(family: CodecFamily): Boolean = when (family) {
        CodecFamily.AV1 -> av1Hardware
        CodecFamily.VP9 -> vp9Hardware
        CodecFamily.AVC, CodecFamily.OTHER -> true
    }

    companion object {
        val UNKNOWN = DeviceCodecCeiling(av1Hardware = true, vp9Hardware = true)
    }
}

/**
 * Chooses the opening video pin (mechanisms 6 + 15): among the
 * response's video formats, prefer — in this order — formats that
 * are BOTH hardware-decodable on this device AND under the Jatah
 * height cap; then hardware-only; then cap-only; then anything.
 * Inside a tier, the highest bitrate wins (the server's ABR still
 * steers from there; the pin is the opening ask). A format with no
 * recorded height cannot be proven over the cap and stays eligible.
 * Returns null only when there are no video formats at all.
 */
fun chooseVideoPin(
    formats: List<SabrFormat>,
    capHeightPx: Int?,
    ceiling: DeviceCodecCeiling,
): SabrFormat? {
    val videos = formats.filter { !it.isAudio }
    if (videos.isEmpty()) return null
    fun underCap(f: SabrFormat): Boolean =
        capHeightPx == null || f.height <= 0 || f.height <= capHeightPx
    fun hw(f: SabrFormat): Boolean = ceiling.hardwareOk(codecFamilyOf(f.mimeType))
    val tiers = listOf(
        videos.filter { hw(it) && underCap(it) },
        videos.filter { hw(it) },
        videos.filter { underCap(it) },
        videos,
    )
    return tiers.firstOrNull { it.isNotEmpty() }
        ?.maxByOrNull { it.bitrate }
}

/**
 * Probes [DeviceCodecCeiling] from the platform's codec registry,
 * once per process. Hardware on API 29+ is the platform's own
 * flag; below that, the codec's name carries it (Google's software
 * codecs announce themselves; vendor codecs do not).
 */
object DeviceCodecProbe {
    @Volatile
    private var cached: DeviceCodecCeiling? = null

    fun ceiling(): DeviceCodecCeiling {
        cached?.let { return it }
        val probed = try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filter { !it.isEncoder }
            fun hasHardware(mime: String): Boolean = infos.any { info ->
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    isHardware(info)
            }
            DeviceCodecCeiling(
                av1Hardware = hasHardware("video/av01"),
                vp9Hardware = hasHardware("video/x-vnd.on2.vp9"),
            )
        } catch (e: Exception) {
            DeviceCodecCeiling.UNKNOWN
        }
        cached = probed
        return probed
    }

    private fun isHardware(info: android.media.MediaCodecInfo): Boolean {
        // The platform flags exist only from API 29; reading them
        // on an older device would throw, so the name heuristic
        // below is the only path there. A codec must be BOTH
        // hardware-accelerated AND not software-only: the dav1d
        // AV1 decoder bundled as c2.android.av1-dav1d is software
        // wearing a platform name, and the 2026-11-12 device log
        // shows the player itself choosing it for AV1 — a ceiling
        // that calls that "hardware" would be lying.
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated && !info.isSoftwareOnly
        }
        val n = info.name.lowercase()
        return !n.contains("google") && !n.contains("ffmpeg") &&
            !n.endsWith(".sw")
    }
}
