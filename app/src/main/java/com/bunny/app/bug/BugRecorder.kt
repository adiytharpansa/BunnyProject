package com.bunny.app.bug

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object BugRecorder {
    private const val DIR = "bug_reports"
    private const val PREFS = "bug_recorder"
    private const val MAX_CRUMBS = 150
    private const val MAX_FILES = 20
    private const val DEDUP_MS = 10_000L

    private lateinit var app: Application
    @Volatile private var installed = false

    private val crumbs = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US) // hanya dipakai di dalam synchronized(crumbs)

    private val _reports = MutableStateFlow<List<BugReport>>(emptyList())
    /** Laporan yang belum ditutup pengguna. Kosong = tidak ada UI yang tampil. */
    val reports: StateFlow<List<BugReport>> = _reports.asStateFlow()

    private val io = Executors.newSingleThreadExecutor {
        Thread(it, "bug-recorder").apply { isDaemon = true }
    }
    private var lastKey = ""
    private var lastAt = 0L

    /** Panggil sedini mungkin di Application.onCreate(). */
    fun install(application: Application) {
        if (installed) return
        installed = true
        app = application

        loadSaved()
        if (Build.VERSION.SDK_INT >= 30) io.execute { collectExitReasons() }
        installCrashHandler()
        installLifecycleBreadcrumbs()
        AnrWatchdog { trace ->
            record(BugSeverity.ANR, "Watchdog", "Thread utama macet lebih dari 5 detik", trace)
        }.start()
        note("app", "BugRecorder aktif")
    }

    /** Catat jejak aktivitas biasa (tidak memunculkan dialog). */
    fun note(tag: String, message: String) {
        synchronized(crumbs) {
            if (crumbs.size >= MAX_CRUMBS) crumbs.removeFirst()
            crumbs.addLast("${timeFmt.format(Date())} [$tag] $message")
        }
    }

    /** Laporkan kegagalan fitur inti. Hanya untuk kasus yang benar-benar kritis. */
    fun reportCritical(
        source: String,
        title: String,
        error: Throwable? = null,
        extra: String? = null,
    ) {
        val detail = buildString {
            if (!extra.isNullOrBlank()) appendLine(extra)
            if (error != null) append(Log.getStackTraceString(error))
        }.ifBlank { "(tanpa detail)" }
        record(BugSeverity.CRITICAL, source, title, detail)
    }

    fun dismiss(id: String) {
        _reports.update { list -> list.filterNot { it.id == id } }
        io.execute { File(dir(), "$id.json").delete() }
    }

    fun dismissAll() {
        _reports.value = emptyList()
        io.execute { dir().listFiles()?.forEach { it.delete() } }
    }

    // ---------------------------------------------------------------- internal

    private fun record(
        severity: BugSeverity,
        source: String,
        title: String,
        detail: String,
        sync: Boolean = false,
        withCrumbs: Boolean = true,
    ) {
        if (!installed) return
        val now = System.currentTimeMillis()
        if (!sync) {
            synchronized(this) {
                val key = "$severity|$source|$title"
                if (key == lastKey && now - lastAt < DEDUP_MS) return // cegah banjir laporan sama
                lastKey = key
                lastAt = now
            }
        }
        val report = BugReport(
            id = "$now-${(0..999).random()}",
            time = now,
            severity = severity,
            source = source,
            title = title,
            detail = detail.take(60_000),
            breadcrumbs = if (withCrumbs) snapshot() else emptyList(),
            device = deviceInfo(),
        )
        if (sync) {
            // Jalur crash: proses akan mati, tulis langsung di thread ini.
            writeFile(report)
        } else {
            io.execute { writeFile(report) }
            _reports.update { listOf(report) + it }
        }
    }

    private fun snapshot(): List<String> = synchronized(crumbs) { crumbs.toList() }

    private fun dir(): File = File(app.filesDir, DIR).apply { mkdirs() }

    private fun writeFile(r: BugReport) {
        runCatching {
            File(dir(), "${r.id}.json").writeText(r.toJson())
            dir().listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_FILES)
                ?.forEach { it.delete() }
        }
    }

    private fun loadSaved() = io.execute {
        val files = dir().listFiles { f -> f.extension == "json" } ?: return@execute
        val saved = files
            .mapNotNull { f -> runCatching { BugReport.fromJson(f.readText()) }.getOrNull() }
        _reports.update { current ->
            (current + saved).distinctBy { it.id }.sortedByDescending { it.time }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                record(
                    severity = BugSeverity.FATAL,
                    source = "Thread ${thread.name}",
                    title = "${e.javaClass.simpleName}: ${e.message}".take(160),
                    detail = Log.getStackTraceString(e),
                    sync = true,
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun installLifecycleBreadcrumbs() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) = note("layar", "${a.javaClass.simpleName} dibuat")
            override fun onActivityResumed(a: Activity) = note("layar", "${a.javaClass.simpleName} aktif")
            override fun onActivityPaused(a: Activity) = note("layar", "${a.javaClass.simpleName} dijeda")
            override fun onActivityDestroyed(a: Activity) = note("layar", "${a.javaClass.simpleName} dihancurkan")
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
        })
    }

    /** Android 11+: tangkap ANR dan crash native yang hanya diketahui sistem, dari sesi sebelumnya. */
    private fun collectExitReasons() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (!prefs.contains("last_exit_ts")) {
                // Pemakaian pertama: jangan impor riwayat lama.
                prefs.edit().putLong("last_exit_ts", System.currentTimeMillis()).apply()
                return
            }
            val last = prefs.getLong("last_exit_ts", 0L)
            var newest = last
            for (e in am.getHistoricalProcessExitReasons(null, 0, 10)) {
                if (e.timestamp <= last) continue
                newest = maxOf(newest, e.timestamp)
                val severity = when (e.reason) {
                    ApplicationExitInfo.REASON_ANR -> BugSeverity.ANR
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> BugSeverity.FATAL
                    else -> continue // crash Java sudah ditangani handler di atas
                }
                val trace = runCatching {
                    e.traceInputStream?.bufferedReader()?.use { it.readText().take(30_000) }
                }.getOrNull().orEmpty()
                record(
                    severity = severity,
                    source = "Dilaporkan sistem (sesi sebelumnya)",
                    title = e.description?.takeIf { it.isNotBlank() }
                        ?: if (severity == BugSeverity.ANR) "ANR" else "Crash native",
                    detail = "Waktu kejadian: ${Date(e.timestamp)}\n$trace",
                    withCrumbs = false,
                )
            }
            prefs.edit().putLong("last_exit_ts", newest).apply()
        }
    }

    internal fun deviceInfo(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) shr 20
        val maxMb = rt.maxMemory() shr 20
        val version = runCatching {
            val p = app.packageManager.getPackageInfo(app.packageName, 0)
            "${p.versionName} (${PackageInfoCompat.getLongVersionCode(p)})"
        }.getOrDefault("?")
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}), app $version, heap $usedMb/$maxMb MB"
    }
}
