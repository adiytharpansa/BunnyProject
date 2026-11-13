package com.bunny.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bunny.app.bug.BugRecorder
import com.bunny.app.bug.MinorCategory
import com.bunny.app.bug.MinorIssueRecorder
import com.bunny.core.backend.ExtractionBackend
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class BunnyApplication : Application(), ImageLoaderFactory {

    /** Lazy: mesin dibangun di thread latar, bukan di onCreate. */
    @Inject
    lateinit var backend: dagger.Lazy<ExtractionBackend>

    override fun onCreate() {
        super.onCreate()
        // Perekam Bug: dipasang paling awal — crash/ANR hanya untuk
        // laporan pengguna, tidak menampilkan apa pun saat sehat.
        BugRecorder.install(this)
        // Perekam masalah ringan: tidak menampilkan apa pun saat tidak ada temuan.
        MinorIssueRecorder.install(this)
        // Pemanasan: DNS, TLS, dan sesi InnerTube siap sebelum Beranda
        // memintanya. Gagal pun tidak apa-apa; Beranda mengulang sendiri.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                backend.get().healthCheck()
            } catch (e: Exception) {
                // pemanasan hanya percepatan, bukan syarat — cukup dicatat ringan
                MinorIssueRecorder.report(
                    MinorCategory.CAUGHT, "BunnyApplication", "Pemanasan mesin gagal", e,
                )
            }
        }
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
            .eventListener(MinorIssueRecorder.imageListener)
            .build()
}
