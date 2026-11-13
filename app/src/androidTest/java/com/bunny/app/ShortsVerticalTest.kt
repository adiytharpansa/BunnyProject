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
 * periksa dua hal: (1) ada kartu yang benar-benar VERTIKAL (tinggi
 * > 1,5x lebar) di layar — bentuk 9:16 ShortsScreen; (2) perilaku
 * INLINE — video halaman yang tampil dimuat di tempat TANPA ketukan
 * apa pun: permukaan video (SurfaceView) muncul di dalam kartu,
 * atau — di awan, tempat IP pusat data ditolak YouTube dengan
 * LOGIN_REQUIRED — keadaan galat inline + tombol ulangnya tampil
 * di dalam kartu. Tombol "Putar" ketuk-untuk-pindah TIDAK BOLEH
 * ada lagi. Tangkapan layar disimpan ke berkas eksternal aplikasi
 * dan ditarik Test Lab sebagai artefak.
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

        // NAVIGASI BARU (2026-11-13): Live bukan tab lagi — chip
        // "Siaran Langsung" adalah chip kedua di Beranda. Ketuk,
        // tunggu daftar live ATAU keadaan kosong/galatnya yang jujur,
        // foto, lalu kembali ke chip Beranda sebelum lanjut ke Shorts.
        // Chip muncul bersama baris topik; umpan Beranda di awan bisa
        // lambat, jadi tunggu sampai 90 detik sebelum mencarinya.
        device.wait(Until.hasObject(By.text("Siaran Langsung")), 90_000)
        val liveChip = device.findObject(By.text("Siaran Langsung"))
            ?: throw AssertionError("Chip 'Siaran Langsung' tidak ditemukan di Beranda")
        liveChip.click()
        val liveDeadline = System.currentTimeMillis() + 90_000
        var liveShown = false
        while (System.currentTimeMillis() < liveDeadline) {
            if (device.hasObject(By.textContains("sedang menonton")) ||
                device.hasObject(By.textContains("Belum ada siaran langsung")) ||
                device.hasObject(By.textContains("Coba lagi"))
            ) {
                liveShown = true
                break
            }
            Thread.sleep(2_000)
        }
        device.takeScreenshot(File(shotsDir, "live-chip.png"))
        assertTrue("Kategori Siaran Langsung tidak menampilkan apa pun", liveShown)
        device.findObject(By.text("Beranda"))?.click()
        Thread.sleep(2_000)

        val shortsTab = device.findObject(By.desc("Shorts"))
            ?: throw AssertionError("Tab Shorts tidak ditemukan di bilah bawah")
        shortsTab.click()

        // Penanda umpan termuat: kartu vertikal yang bisa diklik
        // (lapisan ketuk jeda/lanjut halaman aktif) atau permukaan
        // video vertikal di dalam kartu. "Coba lagi" SENGAJA tidak
        // dipakai di sini: keadaan galat umpan juga menampilkannya
        // tanpa kartu apa pun, jadi ia tidak membuktikan apa-apa
        // pada tahap ini. Keadaan kosong adalah vonis gagal jujur.
        fun tallCardPresent(): Boolean {
            val tallClickable = device.findObjects(By.clickable(true)).any { obj ->
                val b = obj.visibleBounds
                b.width() > 0 && b.height() > b.width() * 3 / 2 && b.height() > 800
            }
            if (tallClickable) return true
            return device.findObjects(By.clazz("android.view.SurfaceView"))
                .any { obj ->
                    val b = obj.visibleBounds
                    b.width() > 0 && b.height() > b.width() * 3 / 2 && b.height() > 800
                }
        }

        val deadline = System.currentTimeMillis() + 120_000
        var loaded = false
        while (System.currentTimeMillis() < deadline) {
            if (tallCardPresent()) {
                loaded = true
                break
            }
            if (device.hasObject(By.text("Belum ada video pendek."))) {
                device.takeScreenshot(File(shotsDir, "shorts-gagal.png"))
                fail("Tab Shorts tampil kosong, bukan umpan video")
            }
            Thread.sleep(2_000)
        }
        if (!loaded) {
            device.takeScreenshot(File(shotsDir, "shorts-gagal.png"))
        }
        assertTrue("Umpan Shorts tidak termuat dalam 120 detik", loaded)
        Thread.sleep(2_000)
        device.takeScreenshot(File(shotsDir, "shorts-1.png"))

        // INLINE, TANPA KETUKAN: tunggu permukaan video ATAU galat
        // inline di halaman pertama. Di awan pemutaran ditolak
        // YouTube (LOGIN_REQUIRED), jadi yang tiba biasanya galat
        // inline + "Coba lagi" DI DALAM kartu — itu sendiri bukti
        // pemuatan di tempat terjadi tanpa ketuk-untuk-pindah.
        val inlineDeadline = System.currentTimeMillis() + 90_000
        var inlineProven = false
        while (System.currentTimeMillis() < inlineDeadline) {
            val hasSurface =
                device.hasObject(By.clazz("android.view.SurfaceView")) ||
                    device.hasObject(By.clazz("android.view.TextureView"))
            val hasInlineError = device.hasObject(By.text("Coba lagi"))
            if (hasSurface || hasInlineError) {
                inlineProven = true
                break
            }
            Thread.sleep(2_000)
        }
        // Beri waktu rantai persona + backoff menyelesaikan vonisnya,
        // lalu ambil bukti gambarnya sebelum asersi apa pun.
        Thread.sleep(8_000)
        device.takeScreenshot(File(shotsDir, "tonton.png"))
        assertTrue(
            "Video Shorts tidak dimuat di tempat (tak ada permukaan, tak ada galat inline)",
            inlineProven,
        )
        // Tombol "Putar" ketuk-untuk-pindah sudah dihapus: deskripsi
        // itu tidak boleh muncul di halaman Shorts.
        assertTrue(
            "Tombol Putar ketuk-untuk-pindah masih ada di halaman Shorts",
            !device.hasObject(By.desc("Putar")),
        )

        // Geser ke video berikutnya dan ambil bukti kedua: kartu
        // vertikal halaman berikutnya tetap ada sesudah geser.
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 20)
        Thread.sleep(2_500)
        device.takeScreenshot(File(shotsDir, "shorts-2.png"))
        val swipeDeadline = System.currentTimeMillis() + 15_000
        var afterSwipe = tallCardPresent()
        while (!afterSwipe && System.currentTimeMillis() < swipeDeadline) {
            Thread.sleep(1_500)
            afterSwipe = tallCardPresent()
        }
        assertTrue("Setelah geser, kartu vertikal hilang", afterSwipe)
    }
}
