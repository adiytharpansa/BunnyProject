package com.bunny.core.model

/**
 * Timbangan — Bunny's data-cost arithmetic.
 *
 * Feed cards cannot know a video's real format bitrates without a player
 * call per card, so card badges estimate from the duration against this
 * reference ladder of typical total (video + audio) bitrates. The player
 * screen replaces the ladder with the real formats' bitrates whenever the
 * backend provides them (see [PlaybackStreams.estimatedSizeBytes]).
 * Everything here is an estimate and is always displayed with "≈".
 */
object DataCost {

    /** A named rung of the reference bitrate ladder. */
    data class Quality(val label: String, val totalBitrateKbps: Long)

    val QUALITY_LADDER: List<Quality> = listOf(
        Quality("144p", 150),
        Quality("240p", 350),
        Quality("360p", 650),
        Quality("480p", 1_150),
        Quality("720p", 2_600),
        Quality("1080p", 5_200),
    )

    /** The quality card badges and Jatah warnings plan against. */
    val DEFAULT_QUALITY: Quality = QUALITY_LADDER[3] // 480p

    /** Estimated bytes for [durationSeconds] at [quality]. */
    fun estimateBytes(durationSeconds: Long, quality: Quality = DEFAULT_QUALITY): Long {
        if (durationSeconds <= 0) return 0
        return durationSeconds * quality.totalBitrateKbps * 1_000 / 8
    }

    /** "850 MB", "1,25 GB", "640 KB" — Indonesian decimal comma, like the feed counts. */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
