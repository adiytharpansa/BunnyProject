package com.bunny.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.DownloadFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Panen Bertingkat (Fase 3 design, built in Fase 6): a download takes
 * the AUDIO first — small, fast, playable on its own — and only raises
 * itself to video in the background once an unmetered network (Wi-Fi)
 * is present. Signed stream URLs expire, so every stage re-asks the
 * backend for fresh formats instead of hoarding URLs.
 *
 * Everything lives on the device: files in the app's private folder,
 * records in the library database. If the process dies mid-download
 * the record is resumed at the next start; a failed stage is marked
 * GAGAL and waits for the user's own retry tap — no silent loops.
 *
 * Transfer engine (rebuilt 2026-11-12 after a device report of a
 * download that crawled): googlevideo throttles a plain whole-file
 * GET hard (measured on the probe: ~25 KB/s with multi-second
 * stalls), while ranged reads — the way the player itself fetches —
 * run an order of magnitude faster. Files are therefore pulled in
 * 5 MB segments over several parallel connections, each segment
 * stored as its own file so an interrupted download resumes where
 * it stopped instead of starting over, and a segment refused with
 * HTTP 403 (an expired signed URL) re-asks the backend for a fresh
 * URL for the same format and carries on.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backend: ExtractionBackend,
    private val downloadDao: DownloadDao,
    private val httpClient: OkHttpClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    val downloads: Flow<List<DownloadEntity>> = downloadDao.observeAll()

    fun observe(videoId: String): Flow<DownloadEntity?> = downloadDao.observe(videoId)

    suspend fun record(videoId: String): DownloadEntity? = downloadDao.get(videoId)

    init {
        registerNetworkCallback()
        scope.launch { resumeInterrupted() }
    }

    /** Starts (or restarts) the staged download of one video. */
    fun enqueue(videoId: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runStages(videoId)
            } finally {
                // hanya hapus entri milik job ini sendiri
                activeJobs.remove(videoId, coroutineContext.job)
            }
        }
        if (activeJobs.putIfAbsent(videoId, job) != null) {
            job.cancel() // sudah ada job aktif
            return
        }
        job.start()
    }

    /** Deletes a download: job, files, and record. */
    fun delete(videoId: String) {
        activeJobs.remove(videoId)?.cancel()
        scope.launch {
            val rec = downloadDao.get(videoId)
            rec?.audioPath?.let { File(it).delete() }
            rec?.videoPath?.let { File(it).delete() }
            deletePartials("$videoId-audio")
            deletePartials("$videoId-video")
            downloadDao.delete(videoId)
        }
    }

    /** Removes the .part file and every segment file of one stem. */
    private fun deletePartials(stem: String) {
        val folder = dir()
        File(folder, "$stem.part").delete()
        folder.listFiles { f -> f.name.startsWith("$stem.seg") }
            ?.forEach { it.delete() }
    }

    // ------------------------------------------------------------ stages

    private suspend fun runStages(videoId: String) {
        var rec = downloadDao.get(videoId)
        try {
            // ---- Stage 1: audio first.
            if (rec?.audioPath == null || !File(rec.audioPath!!).exists()) {
                val details = try {
                    backend.getVideoDetails(videoId)
                } catch (e: Exception) {
                    null
                }
                if (details == null && rec == null) {
                    // Not even the video's real name is knowable right
                    // now; refuse to invent a record.
                    return
                }
                val formats = backend.getDownloadFormats(videoId)
                val audio = formats.formats
                    .filter { it.isAudioOnly }
                    .maxByOrNull { it.bitrate }
                    ?: throw IllegalStateException("Tidak ada format audio")
                val now = System.currentTimeMillis()
                val base = rec ?: DownloadEntity(
                    videoId = videoId,
                    title = details!!.title,
                    channelId = details.channelId,
                    channelName = details.channelName,
                    description = details.description,
                    thumbnailUrl = details.thumbnailUrl,
                    stage = STAGE_AUDIO,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
                rec = base.copy(
                    title = details?.title ?: base.title,
                    channelId = details?.channelId ?: base.channelId,
                    channelName = details?.channelName ?: base.channelName,
                    description = details?.description ?: base.description,
                    thumbnailUrl = details?.thumbnailUrl ?: base.thumbnailUrl,
                    stage = STAGE_AUDIO,
                    progressBytes = 0,
                    totalBytes = audio.contentLength ?: 0,
                    updatedAtEpochMs = now,
                )
                downloadDao.upsert(rec)
                val dest = File(dir(), "$videoId-audio")
                downloadFile(
                    initialUrl = audio.url,
                    userAgent = formats.userAgent,
                    dest = dest,
                    totalHint = audio.contentLength,
                    refreshSource = { freshSource(videoId, audio.itag) },
                ) { done ->
                    updateProgress(videoId, done)
                }
                rec = rec.copy(
                    audioPath = dest.absolutePath,
                    audioBytes = dest.length(),
                    progressBytes = 0,
                    totalBytes = 0,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                downloadDao.upsert(rec)
            }
            // ---- Stage 2: video, only on an unmetered network.
            val current: DownloadEntity = rec
                ?: throw IllegalStateException("Catatan unduhan tidak ditemukan")
            if (current.videoPath != null && File(current.videoPath!!).exists()) {
                downloadDao.upsert(
                    current.copy(
                        stage = STAGE_COMPLETE,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                return
            }
            if (!isUnmetered()) {
                downloadDao.upsert(
                    current.copy(
                        stage = STAGE_WAITING_WIFI,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                return
            }
            upgradeToVideo(videoId, current)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val current = downloadDao.get(videoId) ?: rec
            if (current != null) {
                downloadDao.upsert(
                    current.copy(
                        stage = STAGE_FAILED,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun upgradeToVideo(videoId: String, before: DownloadEntity) {
        // Fresh formats first: the total is needed on the record
        // before the stage flips, so the UI can show real progress.
        val formats = backend.getDownloadFormats(videoId)
        val videos = formats.formats.filter { !it.isAudioOnly && it.height > 0 }
        val video: DownloadFormat = videos
            .filter { !it.isProgressive && it.height <= MAX_VIDEO_HEIGHT }
            .maxByOrNull { it.height }
            ?: videos.filter { !it.isProgressive }.minByOrNull { it.height }
            ?: videos.filter { it.height <= MAX_VIDEO_HEIGHT }.maxByOrNull { it.height }
            ?: videos.minByOrNull { it.height }
            ?: throw IllegalStateException("Tidak ada format video")
        var rec = before.copy(
            stage = STAGE_VIDEO,
            progressBytes = 0,
            totalBytes = video.contentLength ?: 0,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        downloadDao.upsert(rec)
        val dest = File(dir(), "$videoId-video")
        downloadFile(
            initialUrl = video.url,
            userAgent = formats.userAgent,
            dest = dest,
            totalHint = video.contentLength,
            refreshSource = { freshSource(videoId, video.itag) },
        ) { done ->
            updateProgress(videoId, done)
        }
        rec = rec.copy(
            stage = STAGE_COMPLETE,
            videoPath = dest.absolutePath,
            videoWithAudio = video.isProgressive,
            videoHeight = video.height,
            videoBytes = dest.length(),
            progressBytes = 0,
            totalBytes = 0,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        downloadDao.upsert(rec)
    }

    /** A fresh signed URL (+ its user agent) for the same format. */
    private suspend fun freshSource(
        videoId: String,
        itag: Int,
    ): Pair<String, String?>? = try {
        val fresh = backend.getDownloadFormats(videoId)
        fresh.formats.firstOrNull { it.itag == itag }
            ?.let { it.url to fresh.userAgent }
    } catch (e: Exception) {
        null
    }

    private suspend fun updateProgress(videoId: String, done: Long) {
        val rec = downloadDao.get(videoId) ?: return
        if (done - rec.progressBytes < PROGRESS_STEP_BYTES && done != 0L) return
        downloadDao.upsert(rec.copy(progressBytes = done))
    }

    // ------------------------------------------------- transfer engine

    /**
     * Pulls one file in ranged segments. [totalHint] is the format's
     * own contentLength when the player response carried one; a
     * one-byte probe settles the total (and whether the server honors
     * ranges at all) before any segment starts. When the server
     * answers the probe with a plain 200, the file is streamed whole
     * as the only honest option left.
     */
    private suspend fun downloadFile(
        initialUrl: String,
        userAgent: String?,
        dest: File,
        totalHint: Long?,
        refreshSource: suspend () -> Pair<String, String?>?,
        onProgress: suspend (Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val currentUrl = AtomicReference(initialUrl)
            val currentUa = AtomicReference(userAgent)
            val total = resolveTotal(currentUrl, currentUa, totalHint, refreshSource)
            if (total == null) {
                streamWhole(currentUrl.get(), currentUa.get(), dest, onProgress)
                return@withContext
            }
            val ranges = segmentRanges(total, SEGMENT_BYTES)
            val downloaded = AtomicLong(
                ranges.withIndex().sumOf { (index, range) ->
                    segmentFile(dest, index)
                        .takeIf { it.exists() && it.length() == rangeLength(range) }
                        ?.length() ?: 0L
                },
            )
            onProgress(downloaded.get())
            val nextIndex = AtomicInteger(0)
            coroutineScope {
                val workers = (1..PARALLEL_CONNECTIONS).map {
                    async {
                        while (true) {
                            val index = nextIndex.getAndIncrement()
                            if (index >= ranges.size) break
                            downloadSegment(
                                index = index,
                                range = ranges[index],
                                dest = dest,
                                currentUrl = currentUrl,
                                currentUa = currentUa,
                                refreshSource = refreshSource,
                                downloaded = downloaded,
                                onProgress = onProgress,
                            )
                        }
                    }
                }
                workers.awaitAll()
            }
            assemble(dest, ranges, total)
        }
    }

    /**
     * Settles the file's total size. Returns null when the server
     * does not honor ranges (probe answered 200) or the total stays
     * unknowable — the caller then streams the file whole.
     */
    private suspend fun resolveTotal(
        currentUrl: AtomicReference<String>,
        currentUa: AtomicReference<String?>,
        totalHint: Long?,
        refreshSource: suspend () -> Pair<String, String?>?,
    ): Long? {
        // The probe is one byte; it confirms both the total and that
        // this server honors Range for this URL before we commit to
        // the segmented path.
        repeat(2) { attempt ->
            // code to total: 206 carries the total (or the hint), 403
            // asks for one refresh, anything else ends the probe.
            val probe: Pair<Int, Long?> = try {
                val request = Request.Builder()
                    .url(currentUrl.get())
                    .header("Range", "bytes=0-0")
                    .apply {
                        currentUa.get()?.takeIf { it.isNotBlank() }
                            ?.let { header("User-Agent", it) }
                    }
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val rangeTotal = response.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                    response.code to
                        (rangeTotal ?: totalHint?.takeIf { it > 0 })
                }
            } catch (e: IOException) {
                if (attempt == 1) return null
                -1 to null
            }
            when (probe.first) {
                206 -> return probe.second
                403 -> {
                    if (attempt == 1) return null
                    // Expired before we even started: refresh once.
                    val fresh = refreshSource() ?: return null
                    currentUrl.set(fresh.first)
                    currentUa.set(fresh.second)
                }
                -1 -> Unit // network hiccup: the loop tries once more
                else -> return null
            }
        }
        return null
    }

    /** Downloads one segment, retrying with backoff; a 403 refreshes the URL. */
    private suspend fun downloadSegment(
        index: Int,
        range: LongRange,
        dest: File,
        currentUrl: AtomicReference<String>,
        currentUa: AtomicReference<String?>,
        refreshSource: suspend () -> Pair<String, String?>?,
        downloaded: AtomicLong,
        onProgress: suspend (Long) -> Unit,
    ) {
        val segFile = segmentFile(dest, index)
        val expected = rangeLength(range)
        if (segFile.exists() && segFile.length() == expected) return
        val tmp = File(segFile.parentFile, segFile.name + ".part")
        var lastError: Exception? = null
        for (attempt in 1..SEGMENT_ATTEMPTS) {
            var attemptBytes = 0L
            try {
                val request = Request.Builder()
                    .url(currentUrl.get())
                    .header("Range", "bytes=${range.first}-${range.last}")
                    .apply {
                        currentUa.get()?.takeIf { it.isNotBlank() }
                            ?.let { header("User-Agent", it) }
                    }
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 403) {
                        refreshSource()?.let { fresh ->
                            currentUrl.set(fresh.first)
                            currentUa.set(fresh.second)
                        }
                        throw IOException("HTTP 403")
                    }
                    if (response.code != 206) {
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body
                        ?: throw IOException("Respons kosong")
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                attemptBytes += read
                                downloaded.addAndGet(read.toLong())
                                onProgress(downloaded.get())
                            }
                        }
                    }
                }
                check(tmp.length() == expected) {
                    "Potongan $index tidak utuh: ${tmp.length()} dari $expected byte"
                }
                if (segFile.exists()) segFile.delete()
                check(tmp.renameTo(segFile)) { "Gagal menyimpan potongan $index" }
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                downloaded.addAndGet(-attemptBytes)
                throw e
            } catch (e: Exception) {
                downloaded.addAndGet(-attemptBytes)
                tmp.delete()
                lastError = e
                if (attempt < SEGMENT_ATTEMPTS) {
                    delay(SEGMENT_BACKOFF_MS * attempt)
                }
            }
        }
        throw lastError ?: IOException("Potongan $index gagal diunduh")
    }

    /** Concatenates all segments into the destination and cleans up. */
    private fun assemble(dest: File, ranges: List<LongRange>, total: Long) {
        val part = File(dest.parentFile, dest.name + ".part")
        FileOutputStream(part).use { output ->
            ranges.indices.forEach { index ->
                val seg = segmentFile(dest, index)
                seg.inputStream().use { it.copyTo(output) }
            }
        }
        check(part.length() == total) {
            "Berkas rakitan tidak utuh: ${part.length()} dari $total byte"
        }
        if (dest.exists()) dest.delete()
        check(part.renameTo(dest)) { "Gagal menyimpan berkas unduhan" }
        ranges.indices.forEach { segmentFile(dest, it).delete() }
    }

    /** The pre-engine path, kept only for servers that refuse ranges. */
    private suspend fun streamWhole(
        url: String,
        userAgent: String?,
        dest: File,
        onProgress: suspend (Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .apply {
                if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
            }
            .build()
        val part = File(dest.parentFile, dest.name + ".part")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body
                ?: throw IllegalStateException("Respons kosong")
            body.byteStream().use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done)
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        check(part.renameTo(dest)) { "Gagal menyimpan berkas unduhan" }
    }

    private fun segmentFile(dest: File, index: Int): File =
        File(dest.parentFile, "${dest.name}.seg$index")

    private fun rangeLength(range: LongRange): Long = range.last - range.first + 1

    // ------------------------------------------------------------ network

    private fun isUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        caps: NetworkCapabilities,
                    ) {
                        if (caps.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                            )
                        ) {
                            scope.launch { upgradeWaiting() }
                        }
                    }
                },
            )
        } catch (e: Exception) {
            // A missing callback only loses the automatic upgrade;
            // opening the app still resumes waiting downloads.
        }
    }

    private suspend fun upgradeWaiting() {
        downloadDao.byStage(STAGE_WAITING_WIFI)
            .forEach { enqueue(it.videoId) }
    }

    private suspend fun resumeInterrupted() {
        val stuck = downloadDao.byStage(STAGE_AUDIO) +
            downloadDao.byStage(STAGE_VIDEO)
        stuck.forEach { enqueue(it.videoId) }
        if (isUnmetered()) upgradeWaiting()
    }

    private fun dir(): File =
        File(context.filesDir, "downloads").apply { mkdirs() }

    companion object {
        const val STAGE_AUDIO = "MENGUNDUH_AUDIO"
        const val STAGE_WAITING_WIFI = "MENUNGGU_WIFI"
        const val STAGE_VIDEO = "MENGUNDUH_VIDEO"
        const val STAGE_COMPLETE = "LENGKAP"
        const val STAGE_FAILED = "GAGAL"
        const val MAX_VIDEO_HEIGHT = 720
        private const val PROGRESS_STEP_BYTES = 512L * 1024L

        /** One ranged segment: 5 MB, the size class the probe read fastest. */
        const val SEGMENT_BYTES = 5L * 1024L * 1024L

        /** Parallel segment connections per file. */
        const val PARALLEL_CONNECTIONS = 4

        private const val SEGMENT_ATTEMPTS = 4
        private const val SEGMENT_BACKOFF_MS = 600L

        /** Splits [totalBytes] into consecutive inclusive byte ranges. */
        fun segmentRanges(totalBytes: Long, segmentSize: Long): List<LongRange> {
            if (totalBytes <= 0L || segmentSize <= 0L) return emptyList()
            val out = mutableListOf<LongRange>()
            var start = 0L
            while (start < totalBytes) {
                val end = minOf(start + segmentSize - 1, totalBytes - 1)
                out += start..end
                start = end + 1
            }
            return out
        }
    }
}
