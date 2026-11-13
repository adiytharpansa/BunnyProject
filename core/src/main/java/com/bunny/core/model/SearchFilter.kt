package com.bunny.core.model

/**
 * Filter pencarian ala YouTube. Jenis hasil selalu "video" (parser
 * Bunny hanya memetakan video); sisanya diterjemahkan engine ke
 * parameter `params` InnerTube.
 */
data class SearchFilter(
    val uploadDate: UploadDate = UploadDate.ANY,
    val duration: DurationRange = DurationRange.ANY,
    val sort: SearchSort = SearchSort.RELEVANCE,
    val live: Boolean = false,
    val hd: Boolean = false,
    val fourK: Boolean = false,
    val subtitles: Boolean = false,
) {
    val isDefault: Boolean get() = this == DEFAULT

    /** Jumlah kriteria yang aktif (untuk lencana tombol Filter). */
    val activeCount: Int
        get() = listOf(
            uploadDate != UploadDate.ANY,
            duration != DurationRange.ANY,
            sort != SearchSort.RELEVANCE,
            live,
            hd,
            fourK,
            subtitles,
        ).count { it }

    companion object {
        val DEFAULT = SearchFilter()
    }
}

enum class UploadDate { ANY, HOUR, TODAY, WEEK, MONTH, YEAR }

/** SHORT < 4 menit, MEDIUM 4–20 menit, LONG > 20 menit. */
enum class DurationRange { ANY, SHORT, MEDIUM, LONG }

enum class SearchSort { RELEVANCE, RATING, DATE, VIEWS }
