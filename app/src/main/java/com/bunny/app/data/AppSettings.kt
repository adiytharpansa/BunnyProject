package com.bunny.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    /** Transisi antar halaman. Mati = perpindahan instan. */
    var pageAnimations by mutableStateOf(true)
        private set

    /** Getar singkat saat menahan kartu/baris. */
    var haptics by mutableStateOf(true)
        private set

    /** Lanjutkan video dari posisi terakhir ditonton. */
    var resumePlayback by mutableStateOf(true)
        private set

    /** Lencana perekam masalah (alat pengembang) di layar. */
    var developerTools by mutableStateOf(true)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        pageAnimations = p.getBoolean(KEY_ANIMATIONS, true)
        haptics = p.getBoolean(KEY_HAPTICS, true)
        resumePlayback = p.getBoolean(KEY_RESUME, true)
        developerTools = p.getBoolean(KEY_DEV_TOOLS, true)
    }

    fun setPageAnimations(value: Boolean) {
        pageAnimations = value
        prefs?.edit()?.putBoolean(KEY_ANIMATIONS, value)?.apply()
    }

    fun setHaptics(value: Boolean) {
        haptics = value
        prefs?.edit()?.putBoolean(KEY_HAPTICS, value)?.apply()
    }

    fun setResumePlayback(value: Boolean) {
        resumePlayback = value
        prefs?.edit()?.putBoolean(KEY_RESUME, value)?.apply()
    }

    fun setDeveloperTools(value: Boolean) {
        developerTools = value
        prefs?.edit()?.putBoolean(KEY_DEV_TOOLS, value)?.apply()
    }
}
