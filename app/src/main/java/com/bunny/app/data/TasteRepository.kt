package com.bunny.app.data

import com.bunny.core.model.VideoItem
import com.bunny.core.rank.SatisfactionPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Jenis umpan balik eksplisit yang disimpan di tabel feed_feedback. */
object FeedbackKind {
    const val NOT_INTERESTED = "NOT_INTERESTED"
    const val DONT_RECOMMEND_CHANNEL = "DONT_RECOMMEND_CHANNEL"
    const val LIKE = "LIKE"
    const val DISLIKE = "DISLIKE"
    const val SURVEY_YES = "SURVEY_YES"
    const val SURVEY_NO = "SURVEY_NO"
}

/** Pertanyaan kepuasan yang menunggu di Beranda. */
data class SurveyPrompt(
    val videoId: String,
    val channelId: String?,
    val channelName: String,
    val title: String,
)

/** Channel yang diblokir pemirsa (untuk layar pengelolaan). */
data class BlockedChannel(
    val channelId: String?,
    val channelName: String,
)

/**
 * Sisi data selera: mencatat sinyal tontonan dan umpan balik, dan
 * menjalankan survei kepuasan lewat SatisfactionPolicy. Semuanya
 * lokal: tidak ada akun Bunny dan tidak ada server Bunny.
 */
@Singleton
class TasteRepository @Inject constructor(
    private val engagementDao: WatchEngagementDao,
    private val feedbackDao: FeedFeedbackDao,
    private val settingDao: SettingDao,
) {

    private val mutex = Mutex()

    // ------------------------------------------------------------- tontonan

    /** Dipanggil sekali per pembukaan video (di samping recordChannelWatch). */
    suspend fun recordOpen(
        videoId: String,
        channelId: String?,
        channelName: String,
        title: String,
    ) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val existing = engagementDao.get(videoId)
            val row = if (existing != null) {
                existing.copy(
                    channelId = channelId ?: existing.channelId,
                    opens = existing.opens + 1,
                    lastOpenedAtEpochMs = now,
                )
            } else {
                WatchEngagementEntity(
                    videoId = videoId,
                    channelId = channelId,
                    channelName = channelName.trim(),
                    title = title,
                    durationMs = 0L,
                    watchedMs = 0L,
                    opens = 1,
                    completed = false,
                    firstOpenedAtEpochMs = now,
                    lastOpenedAtEpochMs = now,
                )
            }
            engagementDao.upsert(row)
        }
    }

    /** Menambah waktu putar nyata ([elapsedMs]) untuk video yang sedang diputar. */
    suspend fun addWatchTime(
        videoId: String,
        channelId: String?,
        channelName: String,
        title: String,
        elapsedMs: Long,
        durationMs: Long,
    ) {
        if (elapsedMs <= 0L) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            val existing = engagementDao.get(videoId)
            val row = if (existing != null) {
                existing.copy(
                    watchedMs = existing.watchedMs + elapsedMs,
                    durationMs = maxOf(existing.durationMs, durationMs),
                    lastOpenedAtEpochMs = now,
                )
            } else {
                WatchEngagementEntity(
                    videoId = videoId,
                    channelId = channelId,
                    channelName = channelName.trim(),
                    title = title,
                    durationMs = durationMs,
                    watchedMs = elapsedMs,
                    opens = 1,
                    completed = false,
                    firstOpenedAtEpochMs = now,
                    lastOpenedAtEpochMs = now,
                )
            }
            engagementDao.upsert(row)
        }
    }

    suspend fun markCompleted(videoId: String) {
        mutex.withLock {
            val existing = engagementDao.get(videoId) ?: return@withLock
            if (!existing.completed) {
                engagementDao.upsert(existing.copy(completed = true))
            }
        }
    }

    // ---------------------------------------------------------- umpan balik

    suspend fun recordFeedback(kind: String, item: VideoItem) {
        recordFeedback(kind, item.id, item.channelId, item.channelName, item.title)
    }

    suspend fun recordFeedback(
        kind: String,
        videoId: String?,
        channelId: String?,
        channelName: String,
        title: String,
    ) {
        feedbackDao.insert(
            FeedFeedbackEntity(
                kind = kind,
                videoId = videoId,
                channelId = channelId,
                channelName = channelName.trim(),
                title = title,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Mencabut blokir sebuah channel. */
    suspend fun unblockChannel(channelName: String, channelId: String?) {
        feedbackDao.deleteChannelBlock(
            FeedbackKind.DONT_RECOMMEND_CHANNEL,
            channelName.trim(),
            channelId,
        )
    }

    suspend fun blockedChannels(): List<BlockedChannel> =
        feedbackDao.byKind(FeedbackKind.DONT_RECOMMEND_CHANNEL)
            .map { BlockedChannel(it.channelId, it.channelName) }
            .distinctBy { it.channelId ?: it.channelName }

    // -------------------------------------------------------------- survei

    /**
     * Memutuskan (lewat SatisfactionPolicy) apakah tontonan ini layak
     * ditanyakan, dan bila ya, menyimpannya sebagai pertanyaan yang
     * menunggu di Beranda. Hanya SATU pertanyaan menunggu pada satu waktu.
     */
    suspend fun maybeQueueSurvey(videoId: String) {
        mutex.withLock {
            val row = engagementDao.get(videoId) ?: return@withLock
            val meaningful = SatisfactionPolicy.isMeaningfulWatch(
                row.watchedMs,
                row.durationMs,
                row.completed,
            )
            if (!meaningful) return@withLock

            val now = System.currentTimeMillis()
            val today = LocalDate.now().toString()
            val lastAsk = readLong(KEY_SURVEY_LAST_ASK) ?: 0L
            val askedToday = if (readString(KEY_SURVEY_DAY) == today) {
                (readLong(KEY_SURVEY_COUNT) ?: 0L).toInt()
            } else {
                0
            }
            val ask = SatisfactionPolicy.shouldAsk(
                meaningful = true,
                sinceLastAskMs = now - lastAsk,
                askedToday = askedToday,
                roll = Random.nextDouble(),
            )
            if (!ask) return@withLock

            val prompt = SurveyPrompt(row.videoId, row.channelId, row.channelName, row.title)
            write(KEY_SURVEY_PENDING, encode(prompt))
            write(KEY_SURVEY_LAST_ASK, now.toString())
            write(KEY_SURVEY_DAY, today)
            write(KEY_SURVEY_COUNT, (askedToday + 1).toString())
        }
    }

    suspend fun pendingSurvey(): SurveyPrompt? = decode(readString(KEY_SURVEY_PENDING))

    /** [worthIt]: true = sepadan, false = kurang, null = dilewati. */
    suspend fun answerSurvey(prompt: SurveyPrompt, worthIt: Boolean?) {
        write(KEY_SURVEY_PENDING, "")
        if (worthIt == null) return
        recordFeedback(
            kind = if (worthIt) FeedbackKind.SURVEY_YES else FeedbackKind.SURVEY_NO,
            videoId = prompt.videoId,
            channelId = prompt.channelId,
            channelName = prompt.channelName,
            title = prompt.title,
        )
    }

    // -------------------------------------------------------------- privasi

    /**
     * Menghapus seluruh data selera lokal: waktu tonton, umpan balik,
     * hitungan buka lama, memori tayangan, dan status survei. Riwayat
     * pencarian dihapus terpisah (LibraryRepository.clearRecentSearches).
     */
    suspend fun clearAll() {
        mutex.withLock {
            engagementDao.clear()
            feedbackDao.clear()
            for (key in listOf(
                KEY_SURVEY_PENDING,
                KEY_SURVEY_LAST_ASK,
                KEY_SURVEY_DAY,
                KEY_SURVEY_COUNT,
                KEY_TASTE_CHANNELS,
                KEY_FEED_IMPRESSIONS,
            )) {
                write(key, "")
            }
        }
    }

    // ------------------------------------------------------------- pembantu

    private suspend fun readString(key: String): String? =
        settingDao.observeValue(key).first()?.takeIf { it.isNotEmpty() }

    private suspend fun readLong(key: String): Long? = readString(key)?.toLongOrNull()

    private suspend fun write(key: String, value: String) {
        settingDao.upsert(SettingEntity(key, value))
    }


    private fun encode(prompt: SurveyPrompt): String =
        listOf(prompt.videoId, prompt.channelId.orEmpty(), prompt.channelName, prompt.title)
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }

    private fun decode(raw: String?): SurveyPrompt? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('\t')
        if (parts.size != 4 || parts[0].isEmpty()) return null
        return SurveyPrompt(
            videoId = parts[0],
            channelId = parts[1].ifEmpty { null },
            channelName = parts[2],
            title = parts[3],
        )
    }

    private companion object {

        const val KEY_SURVEY_PENDING = "survey_pending"
        const val KEY_SURVEY_LAST_ASK = "survey_last_ask"
        const val KEY_SURVEY_DAY = "survey_day"
        const val KEY_SURVEY_COUNT = "survey_count_today"

        // Harus sama dengan konstanta privat di LibraryRepository.
        const val KEY_TASTE_CHANNELS = "taste_channel_counts"
        const val KEY_FEED_IMPRESSIONS = "feed_impressions"
    }
}
