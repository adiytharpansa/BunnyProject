package com.bunny.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * Pengaturan pengguna, disimpan di berkas "bunny_prefs" (berkas yang
 * sama dengan tanda izin notifikasi). Nilainya berupa state Compose,
 * jadi layar yang membacanya ikut diperbarui saat berubah.
 * Panggil [init] sekali di MainActivity.onCreate.
 */
object AppSettings {
    private const val PREFS = "bunny_prefs"
    private const val KEY_ANIMATIONS = "pref_page_animations"
    private const val KEY_HAPTICS = "pref_haptics"
    private const val KEY_RESUME = "pref_resume_playback"
    private const val KEY_DEV_TOOLS = "pref_developer_tools"

    private var prefs: SharedPreferences? = null

    // Keadaan disimpan di pemegang privat dan properti publiknya
    // bacaan-saja: penulisan lewat fungsi setX di bawah (yang juga
    // menyimpan ke berkas). Properti `var` bernama sama dengan fungsi
    // setX akan bentrok tanda tangan di tingkat JVM, jadi bentuk
    // inilah yang dipakai.
    private val pageAnimationsState = mutableStateOf(true)
    private val hapticsState = mutableStateOf(true)
    private val resumePlaybackState = mutableStateOf(true)
    private val developerToolsState = mutableStateOf(true)

    /** Transisi antar halaman. Mati = perpindahan instan. */
    val pageAnimations: Boolean get() = pageAnimationsState.value

    /** Getar singkat saat menahan kartu/baris. */
    val haptics: Boolean get() = hapticsState.value

    /** Lanjutkan video dari posisi terakhir ditonton. */
    val resumePlayback: Boolean get() = resumePlaybackState.value

    /** Lencana perekam masalah (alat pengembang) di layar. */
    val developerTools: Boolean get() = developerToolsState.value

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        pageAnimationsState.value = p.getBoolean(KEY_ANIMATIONS, true)
        hapticsState.value = p.getBoolean(KEY_HAPTICS, true)
        resumePlaybackState.value = p.getBoolean(KEY_RESUME, true)
        developerToolsState.value = p.getBoolean(KEY_DEV_TOOLS, true)
    }

    fun setPageAnimations(value: Boolean) {
        pageAnimationsState.value = value
        prefs?.edit()?.putBoolean(KEY_ANIMATIONS, value)?.apply()
    }

    fun setHaptics(value: Boolean) {
        hapticsState.value = value
        prefs?.edit()?.putBoolean(KEY_HAPTICS, value)?.apply()
    }

    fun setResumePlayback(value: Boolean) {
        resumePlaybackState.value = value
        prefs?.edit()?.putBoolean(KEY_RESUME, value)?.apply()
    }

    fun setDeveloperTools(value: Boolean) {
        developerToolsState.value = value
        prefs?.edit()?.putBoolean(KEY_DEV_TOOLS, value)?.apply()
    }
}
