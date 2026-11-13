package com.bunny.app.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bunny.app.data.BunnyDatabase
import com.bunny.app.data.ChannelGroupDao
import com.bunny.app.data.DownloadDao
import com.bunny.app.data.FeedFeedbackDao
import com.bunny.app.data.PersonaStatDao
import com.bunny.app.data.SettingDao
import com.bunny.app.data.SubscriptionDao
import com.bunny.app.data.WatchChargeDao
import com.bunny.app.data.WatchEngagementDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wires Bunny's local library database (Fase 3) and its DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BunnyDatabase =
        Room.databaseBuilder(context, BunnyDatabase::class.java, "bunny.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun provideSubscriptionDao(database: BunnyDatabase): SubscriptionDao =
        database.subscriptionDao()

    @Provides
    fun provideChannelGroupDao(database: BunnyDatabase): ChannelGroupDao =
        database.channelGroupDao()

    @Provides
    fun provideDownloadDao(database: BunnyDatabase): DownloadDao =
        database.downloadDao()

    @Provides
    fun providePersonaStatDao(database: BunnyDatabase): PersonaStatDao =
        database.personaStatDao()

    @Provides
    fun provideWatchChargeDao(database: BunnyDatabase): WatchChargeDao =
        database.watchChargeDao()

    @Provides
    fun provideSettingDao(database: BunnyDatabase): SettingDao =
        database.settingDao()

    @Provides
    fun provideWatchEngagementDao(database: BunnyDatabase): WatchEngagementDao =
        database.watchEngagementDao()

    @Provides
    fun provideFeedFeedbackDao(database: BunnyDatabase): FeedFeedbackDao =
        database.feedFeedbackDao()

    /**
     * v1 -> v2 (Fase 6): subscriptions gain the channel's real avatar
     * and a group id; two new tables arrive (groups, downloads).
     * Purely additive — every existing row survives.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN avatarUrl TEXT")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN groupId INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS channel_groups (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "createdAtEpochMs INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS downloads (" +
                    "videoId TEXT NOT NULL PRIMARY KEY, " +
                    "title TEXT NOT NULL, " +
                    "channelId TEXT, " +
                    "channelName TEXT NOT NULL, " +
                    "description TEXT NOT NULL, " +
                    "thumbnailUrl TEXT, " +
                    "stage TEXT NOT NULL, " +
                    "audioPath TEXT, " +
                    "videoPath TEXT, " +
                    "videoWithAudio INTEGER NOT NULL, " +
                    "videoHeight INTEGER NOT NULL, " +
                    "audioBytes INTEGER NOT NULL, " +
                    "videoBytes INTEGER NOT NULL, " +
                    "progressBytes INTEGER NOT NULL, " +
                    "createdAtEpochMs INTEGER NOT NULL, " +
                    "updatedAtEpochMs INTEGER NOT NULL)",
            )
        }
    }

    /**
     * v3 -> v4 (mesin unduh potongan): downloads gain the total size
     * of the file in flight, so progress can be shown honestly.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE downloads ADD COLUMN totalBytes INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /** v2 -> v3 (Lontar Hidup): the Adaptive Ladder's statistics table. */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS persona_stats (" +
                    "persona TEXT NOT NULL, " +
                    "networkClass TEXT NOT NULL, " +
                    "sehat INTEGER NOT NULL, " +
                    "tergerbang INTEGER NOT NULL, " +
                    "ditolak INTEGER NOT NULL, " +
                    "gagal INTEGER NOT NULL, " +
                    "lastCheckEpochMs INTEGER NOT NULL, " +
                    "lastSuccessEpochMs INTEGER NOT NULL, " +
                    "PRIMARY KEY(persona, networkClass))",
            )
        }
    }

    /**
     * v4 -> v5 (Mesin Selera): dua tabel baru, waktu tonton per video dan
     * umpan balik eksplisit. Murni aditif — semua baris lama selamat.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS watch_engagement (" +
                    "videoId TEXT NOT NULL, " +
                    "channelId TEXT, " +
                    "channelName TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "durationMs INTEGER NOT NULL, " +
                    "watchedMs INTEGER NOT NULL, " +
                    "opens INTEGER NOT NULL, " +
                    "completed INTEGER NOT NULL, " +
                    "firstOpenedAtEpochMs INTEGER NOT NULL, " +
                    "lastOpenedAtEpochMs INTEGER NOT NULL, " +
                    "PRIMARY KEY(videoId))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS feed_feedback (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "kind TEXT NOT NULL, " +
                    "videoId TEXT, " +
                    "channelId TEXT, " +
                    "channelName TEXT NOT NULL, " +
                    "title TEXT NOT NULL, " +
                    "createdAtEpochMs INTEGER NOT NULL)",
            )
        }
    }
}
