package com.bunny.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.DownloadFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
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
        if (activeJobs.containsKey(videoId)) return
        activeJobs[videoId] = scope.launch {
            try {
                runStages(videoId)
            } finally {
                activeJobs.remove(videoId)
            }
        }
    }

    /** Deletes a download: job, files, and record. */
    fun delete(videoId: String) {
        activeJobs.remove(videoId)?.cancel()
        scope.launch {
            val rec = downloadDao.get(videoId)
            rec?.audioPath?.let { File(it).delete() }
            rec?.videoPath?.let { File(it).delete() }
            File(dir(), "$videoId-audio.part").delete()
            File(dir(), "$videoId-video.part").delete()
            downloadDao.delete(videoId)
        }
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
                    updatedAtEpochMs = now,
                )
                downloadDao.upsert(rec)
                val dest = File(dir(), "$videoId-audio")
                downloadFile(audio.url, formats.userAgent, dest) { done ->
                    updateProgress(videoId, done)
                }
                rec = rec.copy(
                    audioPath = dest.absolutePath,
                    audioBytes = dest.length(),
                    progressBytes = 0,
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
        var rec = before.copy(
            stage = STAGE_VIDEO,
            progressBytes = 0,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        downloadDao.upsert(rec)
        // Fresh formats: the URLs from stage 1 may already be expired.
        val formats = backend.getDownloadFormats(videoId)
        val videos = formats.formats.filter { !it.isAudioOnly && it.height > 0 }
        val video: DownloadFormat = videos
            .filter { !it.isProgressive && it.height <= MAX_VIDEO_HEIGHT }
            .maxByOrNull { it.height }
            ?: videos.filter { !it.isProgressive }.minByOrNull { it.height }
            ?: videos.filter { it.height <= MAX_VIDEO_HEIGHT }.maxByOrNull { it.height }
            ?: videos.minByOrNull { it.height }
            ?: throw IllegalStateException("Tidak ada format video")
        val dest = File(dir(), "$videoId-video")
        downloadFile(video.url, formats.userAgent, dest) { done ->
            updateProgress(videoId, done)
        }
        rec = rec.copy(
            stage = STAGE_COMPLETE,
            videoPath = dest.absolutePath,
            videoWithAudio = video.isProgressive,
            videoHeight = video.height,
            videoBytes = dest.length(),
            progressBytes = 0,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        downloadDao.upsert(rec)
    }

    private suspend fun updateProgress(videoId: String, done: Long) {
        val rec = downloadDao.get(videoId) ?: return
        if (done - rec.progressBytes < PROGRESS_STEP_BYTES && done != 0L) return
        downloadDao.upsert(rec.copy(progressBytes = done))
    }

    // ------------------------------------------------------------ network

    private suspend fun downloadFile(
        url: String,
        userAgent: String?,
        dest: File,
        onProgress: suspend (Long) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
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
    }

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
    }
}
