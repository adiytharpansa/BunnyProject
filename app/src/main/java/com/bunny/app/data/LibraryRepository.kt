package com.bunny.app.data

import kotlinx.coroutines.flow.Flow
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

    private companion object {
        const val KEY_QUOTA_BYTES = "jatah_quota_bytes"
    }
}
