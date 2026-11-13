package com.bunny.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bunny.app.playback.CrashRecorder
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BunnyApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // First thing after Hilt: make hard crashes leave a readable trace
        // behind (the in-memory diagnostics ring dies with the process).
        CrashRecorder.install(this)
    }

    /**
     * Satu ImageLoader untuk seluruh aplikasi (dipakai AsyncImage dan
     * ThumbnailPreloader). Cache memori 25% heap agar thumbnail yang
     * sudah dimuat tidak di-decode ulang saat scroll; cache disk
     * menyimpan thumbnail antar sesi.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnails"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .crossfade(false)
            .build()
}
