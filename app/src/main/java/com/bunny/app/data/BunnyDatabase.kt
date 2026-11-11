package com.bunny.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Bunny's local library (Fase 3): everything here lives on the device by
 * design — subscriptions for Terbit, watch charges for Jatah accounting,
 * and small settings. There is no Bunny account and no Bunny server.
 */

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val channelName: String,
    val subscribedAtEpochMs: Long,
    /** The channel's real photo, captured when subscribing (semua asli). */
    val avatarUrl: String? = null,
    /** Subscription group this channel belongs to (Fase 6); null = none. */
    val groupId: Long? = null,
)

/** A named subscription group ("Musik", "Berita", …), Fase 6. */
@Entity(tableName = "channel_groups")
data class ChannelGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtEpochMs: Long,
)

/**
 * One Panen Bertingkat download. Stage flow: MENGUNDUH_AUDIO ->
 * MENUNGGU_WIFI (audio saved; video waits for an unmetered network)
 * -> MENGUNDUH_VIDEO -> LENGKAP; GAGAL at any download error.
 * Metadata is the video's real data captured at download time, so
 * offline playback shows the same title, channel and description.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String,
    val description: String,
    val thumbnailUrl: String?,
    val stage: String,
    val audioPath: String? = null,
    val videoPath: String? = null,
    /** True when the video file is a progressive file carrying its own audio. */
    val videoWithAudio: Boolean = false,
    val videoHeight: Int = 0,
    val audioBytes: Long = 0,
    val videoBytes: Long = 0,
    /** Bytes of the file currently downloading (progress display). */
    val progressBytes: Long = 0,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "watch_charges")
data class WatchChargeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title: String,
    val estimatedBytes: Long,
    val chargedAtEpochMs: Long,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface SubscriptionDao {

    @Query("SELECT * FROM subscriptions ORDER BY subscribedAtEpochMs ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE channelId = :channelId)")
    fun observeIsSubscribed(channelId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun delete(channelId: String)

    @Query("UPDATE subscriptions SET groupId = :groupId WHERE channelId = :channelId")
    suspend fun setGroup(channelId: String, groupId: Long?)

    @Query("UPDATE subscriptions SET groupId = NULL WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: Long)
}

@Dao
interface ChannelGroupDao {

    @Query("SELECT * FROM channel_groups ORDER BY createdAtEpochMs ASC")
    fun observeAll(): Flow<List<ChannelGroupEntity>>

    @Insert
    suspend fun insert(entity: ChannelGroupEntity): Long

    @Query("UPDATE channel_groups SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM channel_groups WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    fun observe(videoId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE videoId = :videoId")
    suspend fun get(videoId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE stage = :stage")
    suspend fun byStage(stage: String): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}

@Dao
interface WatchChargeDao {

    @Query(
        "SELECT COALESCE(SUM(estimatedBytes), 0) FROM watch_charges " +
            "WHERE chargedAtEpochMs >= :sinceEpochMs",
    )
    fun observeTotalSince(sinceEpochMs: Long): Flow<Long>

    @Insert
    suspend fun insert(entity: WatchChargeEntity)

    /** Every video ever opened (one charge per open) — Mesin Selera's "watched" set. */
    @Query("SELECT DISTINCT videoId FROM watch_charges")
    fun observeOpenedVideoIds(): Flow<List<String>>
}

@Dao
interface SettingDao {

    @Query("SELECT value FROM settings WHERE key = :key")
    fun observeValue(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingEntity)
}

@Database(
    entities = [
        SubscriptionEntity::class,
        WatchChargeEntity::class,
        SettingEntity::class,
        ChannelGroupEntity::class,
        DownloadEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class BunnyDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun watchChargeDao(): WatchChargeDao
    abstract fun settingDao(): SettingDao
    abstract fun channelGroupDao(): ChannelGroupDao
    abstract fun downloadDao(): DownloadDao
}
