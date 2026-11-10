package com.bunny.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-layer facade over [BunnyDatabase]: subscriptions (Terbit's
 * source), Jatah (the monthly data budget and this month's estimated
 * usage), and playback charges. Charges are ESTIMATES from Timbangan's
 * arithmetic, recorded once per video open — the budget is a planning
 * tool, not a meter, and the UI labels it as such.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val watchChargeDao: WatchChargeDao,
    private val settingDao: SettingDao,
) {

    // --------------------------------------------------------- langganan

    val subscriptions: Flow<List<SubscriptionEntity>> = subscriptionDao.observeAll()

    fun isSubscribed(channelId: String): Flow<Boolean> =
        subscriptionDao.observeIsSubscribed(channelId)

    suspend fun subscribe(channelId: String, channelName: String) {
        subscriptionDao.upsert(
            SubscriptionEntity(
                channelId = channelId,
                channelName = channelName,
                subscribedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun unsubscribe(channelId: String) {
        subscriptionDao.delete(channelId)
    }

    // -------------------------------------------------------------- jatah

    /** Monthly data budget in bytes; null means no budget set. */
    val quotaBytes: Flow<Long?> = settingDao.observeValue(KEY_QUOTA_BYTES)
        .map { raw -> raw?.toLongOrNull() }

    suspend fun setQuotaBytes(bytes: Long?) {
        settingDao.upsert(SettingEntity(KEY_QUOTA_BYTES, bytes?.toString().orEmpty()))
    }

    /** Estimated usage in the current calendar month. */
    fun monthUsageBytes(): Flow<Long> {
        val monthStart = LocalDate.now()
            .withDayOfMonth(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return watchChargeDao.observeTotalSince(monthStart)
    }

    /** Records one playback's estimated cost against the month's Jatah. */
    suspend fun charge(videoId: String, title: String, estimatedBytes: Long) {
        if (estimatedBytes <= 0) return
        watchChargeDao.insert(
            WatchChargeEntity(
                videoId = videoId,
                title = title,
                estimatedBytes = estimatedBytes,
                chargedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    // ------------------------------------------------- riwayat pencarian

    /**
     * Recent searches, newest first — shown on the search page before
     * typing (the official app's "zero-prefix" list; REA dissection
     * 2026-11-10) and one of Mesin Selera's local signals. Stored as one
     * newline-joined settings value, capped at [MAX_RECENT_SEARCHES];
     * never leaves the device.
     */
    val recentSearches: Flow<List<String>> = settingDao.observeValue(KEY_RECENT_SEARCHES)
        .map { raw ->
            raw?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
        }

    suspend fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = recentSearches.first()
        val updated = (
            listOf(trimmed) +
                current.filterNot { it.equals(trimmed, ignoreCase = true) }
            ).take(MAX_RECENT_SEARCHES)
        settingDao.upsert(SettingEntity(KEY_RECENT_SEARCHES, updated.joinToString("\n")))
    }

    suspend fun clearRecentSearches() {
        settingDao.upsert(SettingEntity(KEY_RECENT_SEARCHES, ""))
    }

    // ------------------------------------------------------ mesin selera

    /**
     * Mesin Selera's revealed-interest signal: how often a video from
     * each channel has been opened. Stored as tab-separated
     * "count<TAB>name" lines in one settings value, best channels
     * first, capped — a deliberately tiny, inspectable profile that
     * never leaves the device.
     */
    val channelAffinity: Flow<Map<String, Int>> =
        settingDao.observeValue(KEY_TASTE_CHANNELS).map { raw -> parseChannelCounts(raw) }

    suspend fun recordChannelWatch(channelName: String) {
        val name = channelName.trim()
        if (name.isEmpty()) return
        val current = channelAffinity.first().toMutableMap()
        current[name] = (current[name] ?: 0) + 1
        val encoded = current.entries
            .sortedByDescending { it.value }
            .take(MAX_TASTE_CHANNELS)
            .joinToString("\n") { entry -> "${entry.value}\t${entry.key}" }
        settingDao.upsert(SettingEntity(KEY_TASTE_CHANNELS, encoded))
    }

    private fun parseChannelCounts(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (line in raw.split('\n')) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val count = line.substring(0, tab).toIntOrNull() ?: continue
            val name = line.substring(tab + 1)
            if (name.isNotEmpty()) out[name] = count
        }
        return out
    }

    private companion object {
        const val KEY_QUOTA_BYTES = "jatah_quota_bytes"
        const val KEY_RECENT_SEARCHES = "recent_searches"
        const val MAX_RECENT_SEARCHES = 10
        const val KEY_TASTE_CHANNELS = "taste_channel_counts"
        const val MAX_TASTE_CHANNELS = 50
    }
}
