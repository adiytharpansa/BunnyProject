package com.bunny.app.core.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.bunny.app.data.PersonaStatDao
import com.bunny.app.playback.AppSurfaceFailureReporter
import com.bunny.app.data.PrefsVisitorStore
import com.bunny.app.data.RoomPersonaStatsStore
import com.bunny.core.backend.ExtractionBackend
import com.bunny.engine.lontar.LontarAuth
import com.bunny.engine.lontar.LontarEngine
import com.bunny.engine.lontar.NetworkClassProvider
import com.bunny.engine.lontar.PersonaStatsStore
import com.bunny.engine.lontar.VisitorStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
    fun provideLontarAuth(): LontarAuth = LontarAuth()

    @Provides
    @Singleton
    fun providePersonaStatsStore(dao: PersonaStatDao): PersonaStatsStore =
        RoomPersonaStatsStore(dao)

    /** Coarse network class (WIFI / SELULER / LAINNYA) — never an SSID. */
    @Provides
    @Singleton
    fun provideNetworkClassProvider(
        @ApplicationContext context: Context,
    ): NetworkClassProvider = NetworkClassProvider {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "LAINNYA"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "SELULER"
            else -> "LAINNYA"
        }
    }

    @Provides
    @Singleton
    fun provideVisitorStore(store: PrefsVisitorStore): VisitorStore = store

    @Provides
    @Singleton
    fun provideLontarEngine(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        auth: LontarAuth,
        statsStore: PersonaStatsStore,
        networkClass: NetworkClassProvider,
        visitorStore: VisitorStore,
    ): LontarEngine = LontarEngine(
        httpClient,
        auth,
        statsStore,
        networkClass,
        visitorStore,
        failureReporter = AppSurfaceFailureReporter(context),
    )

    @Provides
    @Singleton
    fun provideExtractionBackend(engine: LontarEngine): ExtractionBackend =
        engine

    /**
     * Fase 8 (Anyam): the SABR playback coordinator. Spools live
     * under the app's cache directory — media bytes in transit,
     * never user data, evictable by the system and by the spool
     * store's own budget alike.
     */
    @androidx.media3.common.util.UnstableApi
    @Provides
    @Singleton
    fun provideSabrPlaybackCoordinator(
        @ApplicationContext context: Context,
        engine: LontarEngine,
    ): com.bunny.engine.lontar.sabr.SabrPlaybackCoordinator =
        com.bunny.engine.lontar.sabr.SabrPlaybackCoordinator(
            engine,
            java.io.File(context.cacheDir, "sabr-spool"),
        )

    /** Jalan A: the official Data API client for the personal layer. */
    @Provides
    @Singleton
    fun provideYouTubeDataApi(
        httpClient: OkHttpClient,
    ): com.bunny.app.data.YouTubeDataApi =
        com.bunny.app.data.YouTubeDataApi(httpClient)
}
