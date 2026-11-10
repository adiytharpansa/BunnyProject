package com.bunny.app.core.di

import android.content.Context
import androidx.room.Room
import com.bunny.app.data.BunnyDatabase
import com.bunny.app.data.SettingDao
import com.bunny.app.data.SubscriptionDao
import com.bunny.app.data.WatchChargeDao
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
        Room.databaseBuilder(context, BunnyDatabase::class.java, "bunny.db").build()

    @Provides
    fun provideSubscriptionDao(database: BunnyDatabase): SubscriptionDao =
        database.subscriptionDao()

    @Provides
    fun provideWatchChargeDao(database: BunnyDatabase): WatchChargeDao =
        database.watchChargeDao()

    @Provides
    fun provideSettingDao(database: BunnyDatabase): SettingDao =
        database.settingDao()
}
