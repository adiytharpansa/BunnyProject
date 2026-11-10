package com.bunny.app.core.di

import com.bunny.core.backend.ExtractionBackend
import com.bunny.engine.lontar.LontarEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The one place that wires Bunny's engine. The UI depends only on the
 * [ExtractionBackend] contract from :core; Lontar is interchangeable here
 * without touching a single screen.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackendModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideExtractionBackend(httpClient: OkHttpClient): ExtractionBackend =
        LontarEngine(httpClient)
}
