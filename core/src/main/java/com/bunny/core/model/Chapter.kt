package com.bunny.core.model

/** One chapter of a video, as the creator wrote it in the description. */
data class Chapter(
    val startMs: Long,
    val title: String,
)

/**
 * Chapters read from a video's REAL description, under YouTube's
 * own rules for when a timestamp list counts as chapters: the
 * first stamp is 0:00, there are at least three, and they ascend.
 * Anything else is just a description with numbers in it, and the
 * honest answer is no chapters. (Adapted from the user's player
 * document §9, 2026-11-12; the parser is pure so the rules are
 * unit-tested in :core.)
 */
object Chapters {

    private val TIMESTAMP = Regex(
        """(?m)^\s*((?:\d{1,2}:)?\d{1,2}:\d{2})\s+(?:[-–—:]\s*)?(.+?)\s*$""",
    )

    private fun parseTimestamp(ts: String): Long? {
        val parts = ts.split(":").map { it.toLongOrNull() ?: return null }
        val seconds = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> return null
        }
        return seconds * 1000
    }

    /**
     * [durationMs] filters stamps past the end when it is known;
     * pass 0 when the caller does not know the duration.
     */
    fun parse(description: String, durationMs: Long): List<Chapter> {
        val found = TIMESTAMP.findAll(description)
            .mapNotNull { match ->
                val start = parseTimestamp(match.groupValues[1])
                    ?: return@mapNotNull null
                Chapter(start, match.groupValues[2])
            }
            .filter { durationMs <= 0 || it.startMs < durationMs }
            .toList()
        val valid = found.size >= 3 &&
            found.first().startMs == 0L &&
            found.zipWithNext().all { (a, b) -> b.startMs > a.startMs }
        return if (valid) found else emptyList()
    }
}
