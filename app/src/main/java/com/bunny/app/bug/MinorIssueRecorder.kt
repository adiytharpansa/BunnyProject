package com.bunny.app.bug

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import coil.EventListener
import coil.request.ErrorResult
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pendamping [BugRecorder] untuk masalah RINGAN: aplikasi tetap jalan, tidak ada dialog.
 * [issues] kosong = tidak ada UI yang tampil (lihat MinorIssueHost).
 */
object MinorIssueRecorder {
    private const val FILE = "minor_issues.json"
    private const val MAX_ISSUES = 50
    private const val FREEZE_MS = 700L
    private const val HEAP_RATIO = 0.90
    private const val HEAP_TICK_MS = 30_000L
    private const val NET_GRACE_MS = 3_000L

    @Volatile private var app: Application? = null

    // Satu antrean kejadian: SETIAP deteksi (termasuk pengulangan) dikirim ke sini
    // dan ditampilkan satu per satu oleh MinorIssueHost. Channel menyimpan kejadian
    // yang terjadi sebelum UI siap (mis. saat startup). Penuh (32) = yang tertua dibuang.
    private val _events = Channel<MinorIssue>(32, BufferOverflow.DROP_OLDEST)
    val events: Flow<MinorIssue> = _events.receiveAsFlow()

    private val lock = Any()
    private val map = LinkedHashMap<String, MinorIssue>()

    private val _issues = MutableStateFlow<List<MinorIssue>>(emptyList())

    /** Masalah ringan yang belum dihapus pengguna, terbaru di atas. */
    val issues: StateFlow<List<MinorIssue>> = _issues.asStateFlow()

    private val io = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "minor-recorder").apply { isDaemon = true }
    }
    private val savePending = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val frameHandler by lazy {
        Handler(HandlerThread("minor-frames").apply { start() }.looper)
    }

    /** Jumlah Activity yang sedang started; hanya disentuh dari thread utama. */
    private var started = 0

    /** Panggil di Application.onCreate(), tepat setelah BugRecorder.install(). */
    fun install(application: Application) {
        if (app != null) return
        // ALAT PENGEMBANGAN (perintah pengguna 2026-11-13): tidak
        // pernah aktif di build rilis — install() berhenti di sini
        // dan semua report() menjadi no-op karena app tetap null.
        // Berkas ini tetap dijadwalkan DIHAPUS sebelum rilis.
        if ((application.applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE) == 0
        ) return
        app = application
        loadSaved()
        installLifecycle(application)
        installMemoryWatcher(application)
        installNetworkWatcher(application)
    }

    // ------------------------------------------------------------ API publik

    /** Catat satu masalah ringan. Aman dipanggil dari thread mana pun. */
    fun report(
        category: MinorCategory,
        source: String,
        title: String,
        error: Throwable? = null,
        extra: String? = null,
    ) {
        if (app == null) return
        val now = System.currentTimeMillis()
        val detail = buildString {
            if (!extra.isNullOrBlank()) appendLine(extra)
            if (error != null) append(Log.getStackTraceString(error))
        }.take(2_000)
        val key = "${category.name}|$source|$title"
        var isNew = false
        var latest: MinorIssue? = null
        synchronized(lock) {
            val old = map[key]
            isNew = old == null
            val updated = old?.copy(
                detail = detail.ifBlank { old.detail },
                lastTime = now,
                count = old.count + 1,
            ) ?: MinorIssue(key, category, source, title, detail, now, now, 1)
            map[key] = updated
            latest = updated
            while (map.size > MAX_ISSUES) {
                val oldest = map.values.minByOrNull { it.lastTime } ?: break
                map.remove(oldest.key)
            }
            publishLocked()
        }
        // Tampilkan langsung di UI, setiap kali terdeteksi.
        latest?.let { _events.trySend(it) }
        // Jejak untuk laporan crash berikutnya; hanya kemunculan pertama agar ring 150 tidak penuh.
        if (isNew) BugRecorder.note("minor", "${category.name}: $title")
        scheduleSave()
    }

    /**
     * Pengganti blok catch yang kosong: jalankan [block]; bila gagal, catat sebagai
     * masalah ringan dan kembalikan null. CancellationException tetap dilempar ulang.
     */
    inline fun <T> guard(source: String, title: String, block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(MinorCategory.CAUGHT, source, title, e)
            null
        }

    fun dismiss(key: String) {
        synchronized(lock) {
            map.remove(key)
            publishLocked()
        }
        scheduleSave()
    }

    fun dismissAll() {
        synchronized(lock) {
            map.clear()
            publishLocked()
        }
        scheduleSave()
    }

    /** Teks gabungan untuk Salin/Bagikan. */
    fun reportText(): String = buildString {
        appendLine("Laporan masalah ringan Bunny")
        appendLine("Perangkat : ${BugRecorder.deviceInfo()}")
        appendLine()
        issues.value.forEach {
            appendLine(it.toText())
            appendLine("----------------------------------------")
        }
    }

    /** Pasang di ImageLoader: gambar yang gagal dimuat jadi masalah ringan. */
    val imageListener: EventListener = object : EventListener {
        override fun onError(request: ImageRequest, result: ErrorResult) {
            // Query string dibuang agar token/parameter tidak ikut tersimpan.
            val url = request.data.toString().substringBefore('?').take(200)
            report(
                MinorCategory.IMAGE, "Coil", "Gambar gagal dimuat",
                error = result.throwable, extra = "Sumber: $url",
            )
        }
    }

    // ---------------------------------------------------------------- internal

    private fun publishLocked() {
        _issues.value = map.values.sortedByDescending { it.lastTime }
    }

    private fun file(): File? = app?.let { File(it.filesDir, FILE) }

    private fun scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            io.schedule({
                savePending.set(false)
                save()
            }, 2, TimeUnit.SECONDS)
        }
    }

    private fun save() {
        runCatching {
            val f = file() ?: return
            val snapshot = synchronized(lock) { map.values.toList() }
            if (snapshot.isEmpty()) {
                f.delete()
            } else {
                f.writeText(JSONArray(snapshot.map { it.toJson() }).toString())
            }
        }
    }

    private fun loadSaved() {
        io.execute {
            runCatching {
                val f = file()
                if (f != null && f.exists()) {
                    val arr = JSONArray(f.readText())
                    synchronized(lock) {
                        for (i in 0 until arr.length()) {
                            val issue = MinorIssue.fromJson(arr.getJSONObject(i))
                            map.putIfAbsent(issue.key, issue)
                        }
                        publishLocked()
                    }
                }
            }
        }
    }

    // ---- layar macet: satu frame >= 700 ms (frame pertama dilewati)

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        val totalMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
        if (totalMs < FREEZE_MS || Debug.isDebuggerConnected()) return@OnFrameMetricsAvailableListener
        val stages = listOf(
            "input" to FrameMetrics.INPUT_HANDLING_DURATION,
            "animasi" to FrameMetrics.ANIMATION_DURATION,
            "ukur/tata letak" to FrameMetrics.LAYOUT_MEASURE_DURATION,
            "gambar" to FrameMetrics.DRAW_DURATION,
            "sinkron" to FrameMetrics.SYNC_DURATION,
            "perintah GPU" to FrameMetrics.COMMAND_ISSUE_DURATION,
            "swap buffer" to FrameMetrics.SWAP_BUFFERS_DURATION,
            "tunda tak diketahui" to FrameMetrics.UNKNOWN_DELAY_DURATION,
        )
        val top = stages.maxByOrNull { metrics.getMetric(it.second) }
        val topText = top?.let { "${it.first} ${metrics.getMetric(it.second) / 1_000_000} ms" } ?: "?"
        report(
            MinorCategory.LAG, "Render", "Layar sempat macet (> 0,7 dtk)",
            extra = "Durasi frame: $totalMs ms\nTahap terlama: $topText",
        )
    }

    private fun installLifecycle(a: Application) {
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) mainHandler.postDelayed(heapTick, HEAP_TICK_MS)
                runCatching {
                    activity.window.addOnFrameMetricsAvailableListener(frameListener, frameHandler)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                runCatching { activity.window.removeOnFrameMetricsAvailableListener(frameListener) }
                if (--started <= 0) {
                    started = 0
                    mainHandler.removeCallbacks(heapTick)
                }
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // ---- memori

    private val heapTick = object : Runnable {
        override fun run() {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            val max = rt.maxMemory()
            if (max > 0 && used.toDouble() / max >= HEAP_RATIO) {
                report(
                    MinorCategory.MEMORY, "Heap", "Heap aplikasi hampir penuh",
                    extra = "Terpakai ${used shr 20}/${max shr 20} MB",
                )
            }
            mainHandler.postDelayed(this, HEAP_TICK_MS)
        }
    }

    private fun installMemoryWatcher(a: Application) {
        a.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // 10 = TRIM_MEMORY_RUNNING_LOW, 15 = TRIM_MEMORY_RUNNING_CRITICAL
                // (angka literal agar tidak terkena peringatan deprecation).
                if (level == 10 || level == 15) {
                    report(
                        MinorCategory.MEMORY, "Sistem", "Memori perangkat menipis",
                        extra = "Level trim: $level",
                    )
                }
            }

            override fun onLowMemory() {
                report(MinorCategory.MEMORY, "Sistem", "Memori perangkat menipis", extra = "onLowMemory")
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
        })
    }

    // ---- jaringan

    private val offlineCheck = Runnable {
        val cm = app?.getSystemService(ConnectivityManager::class.java)
        if (started > 0 && cm != null && cm.activeNetwork == null) {
            report(MinorCategory.NETWORK, "Konektivitas", "Koneksi internet terputus")
        }
    }

    private fun installNetworkWatcher(a: Application) {
        val cm = a.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.removeCallbacks(offlineCheck)
                }

                override fun onLost(network: Network) {
                    // Masa tunggu: pindah Wi-Fi ke seluler bukan masalah.
                    mainHandler.postDelayed(offlineCheck, NET_GRACE_MS)
                }
            })
        }
        // Dibuka saat sudah offline: callback di atas tidak akan memanggil onLost.
        if (cm.activeNetwork == null) mainHandler.postDelayed(offlineCheck, NET_GRACE_MS)
    }
}
