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
    ],
    version = 1,
    exportSchema = false,
)
abstract class BunnyDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun watchChargeDao(): WatchChargeDao
    abstract fun settingDao(): SettingDao
}
