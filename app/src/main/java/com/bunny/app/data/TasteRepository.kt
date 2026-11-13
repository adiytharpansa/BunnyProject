package com.bunny.app.data

import com.bunny.core.model.VideoItem
import com.bunny.core.rank.Engagement
import com.bunny.core.rank.SatisfactionPolicy
import com.bunny.core.rank.TasteProfile
import com.bunny.core.rank.TasteText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.min
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
 * Mesin Selera, sisi data. Mencatat sinyal tontonan dan umpan balik,
 * lalu membangun profil selera (dengan peluruhan waktu) untuk FeedRanker.
 * Semuanya lokal: tidak ada akun Bunny dan tidak ada server Bunny.
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

    // --------------------------------------------------------------- profil

    /**
     * Melengkapi [base] (langganan, pencarian, Selera Akun, churn — dibangun
     * HomeViewModel) dengan sinyal baru: minat channel dan topik yang
     * meluruh menurut waktu, penolakan, dan daftar sembunyi/blokir.
     */
    suspend fun enrich(
        base: TasteProfile,
        nowMs: Long = System.currentTimeMillis(),
    ): TasteProfile {
        engagementDao.prune(nowMs - ENGAGEMENT_RETENTION_MS)
        feedbackDao.prune(nowMs - FEEDBACK_RETENTION_MS, FeedbackKind.DONT_RECOMMEND_CHANNEL)

        val channel = HashMap<String, Double>()
        val topics = HashMap<String, Double>()
        val rejected = HashMap<String, Double>()
        val hiddenIds = HashSet<String>()
        val blockedIds = HashSet<String>()
        val blockedNames = HashSet<String>()

        // 1) Tontonan: bobot kepuasan × peluruhan × bonus tonton ulang.
        for (row in engagementDao.recent(MAX_ENGAGEMENT_ROWS)) {
            val decay = Engagement.decay(nowMs - row.lastOpenedAtEpochMs, HALF_LIFE_DAYS)
            val rewatch = 1.0 + min(ln(row.opens.coerceAtLeast(1).toDouble()), 1.0)
            val weight = Engagement.weight(row.watchedMs, row.durationMs, row.completed) *
                decay * rewatch
            if (row.channelName.isNotEmpty()) channel.add(row.channelName, weight)
            if (weight > 0.0) {
                for (word in TasteText.words(row.title)) topics.add(word, weight)
            }
        }

        // 2) Umpan balik eksplisit.
        for (fb in feedbackDao.recent(MAX_FEEDBACK_ROWS)) {
            val decay = if (fb.kind == FeedbackKind.DONT_RECOMMEND_CHANNEL) {
                1.0
            } else {
                Engagement.decay(nowMs - fb.createdAtEpochMs, FEEDBACK_HALF_LIFE_DAYS)
            }
            val words = TasteText.words(fb.title)
            val name = fb.channelName
            when (fb.kind) {
                FeedbackKind.NOT_INTERESTED -> {
                    fb.videoId?.let { hiddenIds += it }
                    if (name.isNotEmpty()) channel.add(name, -0.8 * decay)
                    for (w in words) rejected.add(w, 1.0 * decay)
                }
                FeedbackKind.DONT_RECOMMEND_CHANNEL -> {
                    fb.channelId?.takeIf { it.isNotBlank() }?.let { blockedIds += it }
                    if (name.isNotEmpty()) blockedNames += name
                    fb.videoId?.let { hiddenIds += it }
                }
                FeedbackKind.LIKE -> {
                    if (name.isNotEmpty()) channel.add(name, 2.0 * decay)
                    for (w in words) topics.add(w, 1.5 * decay)
                }
                FeedbackKind.DISLIKE -> {
                    fb.videoId?.let { hiddenIds += it }
                    if (name.isNotEmpty()) channel.add(name, -1.5 * decay)
                    for (w in words) rejected.add(w, 1.0 * decay)
                }
                FeedbackKind.SURVEY_YES -> {
                    if (name.isNotEmpty()) channel.add(name, 1.0 * decay)
                    for (w in words) topics.add(w, 0.8 * decay)
                }
                FeedbackKind.SURVEY_NO -> {
                    if (name.isNotEmpty()) channel.add(name, -1.0 * decay)
                    for (w in words) rejected.add(w, 0.6 * decay)
                }
            }
        }

        // 3) Rapikan: topik teratas saja, penolakan dikurangi minat positif.
        val topicMap = topics.entries
            .sortedByDescending { it.value }
            .take(MAX_TOPIC_WORDS)
            .associate { it.key to it.value.coerceAtMost(TOPIC_VALUE_CAP) }
        val rejectedMap = HashMap<String, Double>()
        for ((word, value) in rejected) {
            val net = value - 0.5 * (topics[word] ?: 0.0)
            if (net > 0.0) rejectedMap[word] = net
        }
        val rejectedTop = rejectedMap.entries
            .sortedByDescending { it.value }
            .take(MAX_TOPIC_WORDS)
            .associate { it.key to it.value }
        val channelMap = channel.mapValues {
            it.value.coerceIn(-CHANNEL_VALUE_CAP, CHANNEL_VALUE_CAP)
        }

        return base.copy(
            channelInterest = channelMap,
            topicInterest = topicMap,
            rejectedTopics = rejectedTop,
            hiddenVideoIds = hiddenIds,
            blockedChannelIds = blockedIds,
            blockedChannelNames = blockedNames,
        )
    }

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

    private fun MutableMap<String, Double>.add(key: String, weight: Double) {
        this[key] = (this[key] ?: 0.0) + weight
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
        const val HALF_LIFE_DAYS = 30.0
        const val FEEDBACK_HALF_LIFE_DAYS = 60.0
        const val ENGAGEMENT_RETENTION_MS = 180L * 24 * 60 * 60 * 1000
        const val FEEDBACK_RETENTION_MS = 365L * 24 * 60 * 60 * 1000
        const val MAX_ENGAGEMENT_ROWS = 400
        const val MAX_FEEDBACK_ROWS = 600
        const val MAX_TOPIC_WORDS = 80
        const val TOPIC_VALUE_CAP = 10.0
        const val CHANNEL_VALUE_CAP = 8.0

        const val KEY_SURVEY_PENDING = "survey_pending"
        const val KEY_SURVEY_LAST_ASK = "survey_last_ask"
        const val KEY_SURVEY_DAY = "survey_day"
        const val KEY_SURVEY_COUNT = "survey_count_today"

        // Harus sama dengan konstanta privat di LibraryRepository.
        const val KEY_TASTE_CHANNELS = "taste_channel_counts"
        const val KEY_FEED_IMPRESSIONS = "feed_impressions"
    }
}
