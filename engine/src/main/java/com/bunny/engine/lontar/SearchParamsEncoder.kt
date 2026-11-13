package com.bunny.engine.lontar

import com.bunny.core.model.DurationRange
import com.bunny.core.model.SearchFilter
import com.bunny.core.model.SearchSort
import com.bunny.core.model.UploadDate
import java.io.ByteArrayOutputStream

/**
 * Menyusun parameter `params` pencarian InnerTube (protobuf lalu
 * base64) dari [SearchFilter].
 *
 * Skema (urut field naik, seperti yang dibuat YouTube sendiri):
 *   field 1 (varint)  = urutan: 1 rating, 2 tanggal unggah, 3 tayangan
 *   field 2 (pesan)   = filter:
 *       1 = waktu unggah (1 jam, 2 hari ini, 3 minggu, 4 bulan, 5 tahun)
 *       2 = jenis (1 = video)
 *       3 = durasi (1 = <4 mnt, 2 = >20 mnt, 3 = 4–20 mnt)
 *       4 = HD, 5 = subtitel, 8 = live, 14 = 4K
 *
 * Filter default menghasilkan "EgIQAQ%3D%3D" — persis konstanta
 * SEARCH_PARAMS_VIDEO_ONLY yang sudah dipakai mesin.
 */
internal object SearchParamsEncoder {

    fun encode(filter: SearchFilter): String {
        val inner = ByteArrayOutputStream()
        val upload = when (filter.uploadDate) {
            UploadDate.ANY -> 0
            UploadDate.HOUR -> 1
            UploadDate.TODAY -> 2
            UploadDate.WEEK -> 3
            UploadDate.MONTH -> 4
            UploadDate.YEAR -> 5
        }
        if (upload != 0) inner.varintField(1, upload)
        inner.varintField(2, 1) // jenis: video
        val duration = when (filter.duration) {
            DurationRange.ANY -> 0
            DurationRange.SHORT -> 1
            DurationRange.LONG -> 2
            DurationRange.MEDIUM -> 3
        }
        if (duration != 0) inner.varintField(3, duration)
        if (filter.hd) inner.varintField(4, 1)
        if (filter.subtitles) inner.varintField(5, 1)
        if (filter.live) inner.varintField(8, 1)
        if (filter.fourK) inner.varintField(14, 1)

        val outer = ByteArrayOutputStream()
        val sort = when (filter.sort) {
            SearchSort.RELEVANCE -> 0
            SearchSort.RATING -> 1
            SearchSort.DATE -> 2
            SearchSort.VIEWS -> 3
        }
        if (sort != 0) outer.varintField(1, sort)
        val innerBytes = inner.toByteArray()
        outer.write((2 shl 3) or 2) // field 2, tipe length-delimited
        outer.write(innerBytes.size)
        outer.write(innerBytes)

        return base64(outer.toByteArray())
            .replace("+", "%2B")
            .replace("/", "%2F")
            .replace("=", "%3D")
    }

    // Semua nilai & nomor field < 128, jadi tiap varint cukup 1 byte.
    private fun ByteArrayOutputStream.varintField(field: Int, value: Int) {
        write(field shl 3)
        write(value)
    }

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    // Base64 manual agar tidak bergantung pada level API Android.
    private fun base64(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(
                if (b1 >= 0) {
                    ALPHABET[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)]
                } else {
                    '='
                },
            )
            sb.append(if (b2 >= 0) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }
}
