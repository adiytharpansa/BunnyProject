package com.bunny.core.model

/**
 * One video as the UI sees it. Deliberately free of any extractor types:
 * every backend maps its own models into this shape.
 */
data class VideoItem(
    val id: String,
    val title: String,
    val channelId: String?,
    val channelName: String,
    /** The channel's real avatar image, when the source renderer carried one. */
    val channelAvatarUrl: String? = null,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val viewCount: Long?,
    /**
     * Teks jumlah tayang ASLI dari YouTube ("304,6 rb x ditonton",
     * "1,2 rb sedang menonton") — yang ditampilkan apa adanya.
     * Aplikasi tidak pernah menyusun teks ini dari angka.
     */
    val viewCountText: String? = null,
    val uploadedText: String?,
    val isLive: Boolean = false,
    /**
     * True bila baris sumbernya memang video pendek vertikal
     * (perender reel/shorts di jawaban YouTube). Video begini tidak
     * membawa teks durasi, jadi aturan durasi saja tidak bisa
     * mengenalinya.
     */
    val isShort: Boolean = false,
    /**
     * Chip Alasan: why this item is being shown, in the user's language
     * ("Topik: Musik", "Dari langganan: Blender Foundation"). Computed
     * locally by whoever composed the list; null means no claim is made.
     */
    val reason: String? = null,
)
