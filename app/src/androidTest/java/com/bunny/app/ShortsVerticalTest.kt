package com.bunny.app

import android.Manifest
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bukti perangkat untuk tab Shorts (Firebase Test Lab, perangkat
 * virtual): buka Bunny, ketuk tab Shorts, tunggu umpan termuat, lalu
 * periksa ada kartu yang benar-benar VERTIKAL (tinggi > 1,5x lebar)
 * di layar — bentuk 9:16 yang dirancang ShortsScreen. Tangkapan layar
 * disimpan ke berkas eksternal aplikasi dan ditarik Test Lab sebagai
 * artefak (shorts-1.png / shorts-2.png) supaya hasilnya bisa dilihat,
 * bukan hanya dipercaya.
 */
@RunWith(AndroidJUnit4::class)
class ShortsVerticalTest {

    @Test
    fun shortsFeed_showsVerticalCards() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        // Izin notifikasi diberikan duluan supaya dialog izin saat
        // pertama buka tidak menghalangi layar yang diuji.
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        val shotsDir = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }

        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        device.wait(Until.hasObject(By.desc("Shorts")), 30_000)
        device.takeScreenshot(File(shotsDir, "home.png"))

        val shortsTab = device.findObject(By.desc("Shorts"))
            ?: throw AssertionError("Tab Shorts tidak ditemukan di bilah bawah")
        shortsTab.click()

        // Umpan Shorts dimuat dari jaringan: beri waktu sampai tombol
        // Putar (penanda kartu video) muncul. Layar galat/kosong adalah
        // vonis gagal yang jujur, bukan keberhasilan.
        val deadline = System.currentTimeMillis() + 120_000
        var loaded = false
        while (System.currentTimeMillis() < deadline) {
            if (device.hasObject(By.desc("Putar"))) {
                loaded = true
                break
            }
            val errorText = device.findObject(By.textContains("Coba lagi"))
            val emptyText = device.findObject(By.text("Belum ada video pendek."))
            if (errorText != null || emptyText != null) {
                device.takeScreenshot(File(shotsDir, "shorts-gagal.png"))
                fail("Tab Shorts tampil galat/kosong, bukan umpan video")
            }
            Thread.sleep(2_000)
        }
        assertTrue("Umpan Shorts tidak termuat dalam 120 detik", loaded)
        Thread.sleep(2_000)
        device.takeScreenshot(File(shotsDir, "shorts-1.png"))

        // Ukur kartu: elemen bisa-ketik dengan tinggi jauh melebihi
        // lebar = kartu vertikal 9:16 (di layar 1080px: ~1000x1800).
        fun verticalCards(): List<String> =
            device.findObjects(By.clickable(true)).mapNotNull { obj ->
                val b = obj.visibleBounds
                val w = b.width()
                val h = b.height()
                if (w > 0 && h > w * 3 / 2 && h > 800) "${w}x$h" else null
            }
        val cards = verticalCards()
        assertTrue(
            "Tidak ada kartu vertikal di tab Shorts (terukur: $cards)",
            cards.isNotEmpty(),
        )

        // Geser ke video berikutnya dan ambil bukti kedua.
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 20)
        Thread.sleep(2_500)
        device.takeScreenshot(File(shotsDir, "shorts-2.png"))
        assertTrue(
            "Setelah geser, kartu vertikal hilang",
            verticalCards().isNotEmpty(),
        )
    }
}
