package com.bunny.engine.arus

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Arus A2 — where a format's initialization segment comes from. A0's
 * finding: the SABR conversation itself carries no init segment for
 * this persona; the bytes live at the format's own URL, in the range
 * the same player response declares.
 */
fun interface InitSegmentFetcher {
    /** The init bytes, or null when unavailable (the stream then fails honestly). */
    fun fetch(format: ArusFormat): ByteArray?
}

/** Fetches init segments over HTTP with a Range request, persona UA attached. */
class OkHttpInitSegmentFetcher(
    private val client: OkHttpClient,
    private val userAgent: String,
) : InitSegmentFetcher {
    override fun fetch(format: ArusFormat): ByteArray? {
        val url = format.directUrl ?: return null
        if (format.initRangeEnd < format.initRangeStart) return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=${format.initRangeStart}-${format.initRangeEnd}")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: IOException) {
            null
        }
    }
}

/**
 * One format's blocking byte pipe: the conversation writes, the player
 * reads. Writers block past [MAX_BUFFERED_BYTES] so a fast network
 * cannot outrun a slow player into unbounded memory — the backpressure
 * runs through the sink into the conversation loop itself.
 */
internal class BytePipe {
    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var queuedBytes = 0
    private var ended = false
    private var closed = false
    private var failure: IOException? = null

    fun write(chunk: ByteArray) {
        synchronized(lock) {
            while (queuedBytes > MAX_BUFFERED_BYTES && !closed) lock.wait()
            if (closed) throw IOException("pipe closed")
            failure?.let { throw it }
            queue.addLast(chunk)
            queuedBytes += chunk.size
            lock.notifyAll()
        }
    }

    /** Blocking read; returns -1 at end of stream (after the queue drains). */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            while (true) {
                failure?.let { throw it }
                val head = queue.firstOrNull()
                if (head != null) {
                    val n = minOf(length, head.size - headOffset)
                    System.arraycopy(head, headOffset, buffer, offset, n)
                    headOffset += n
                    queuedBytes -= n
                    if (headOffset == head.size) {
                        queue.removeFirst()
                        headOffset = 0
                    }
                    lock.notifyAll()
                    return n
                }
                if (ended || closed) return -1
                lock.wait()
            }
        }
    }

    fun end() {
        synchronized(lock) {
            ended = true
            lock.notifyAll()
        }
    }

    fun fail(error: IOException) {
        synchronized(lock) {
            if (failure == null) failure = error
            lock.notifyAll()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    companion object {
        const val MAX_BUFFERED_BYTES = 4 * 1024 * 1024
    }
}

/**
 * Arus A2 — owns ONE SABR conversation and serves its two format
 * streams to the player as blocking byte pipes: init segment first
 * (fetched from the format's own URL), then the conversation's media
 * in arrival order. This is the bridge the design calls MediaBridge,
 * taken in its DataSource shape: one shared conversation behind two
 * ordinary byte streams the player's own fMP4 extractor parses — the
 * exact byte layout A0 verified with ffprobe (init + fragments).
 *
 * The conversation runs on its own daemon thread, started lazily by
 * the first reader ([start] is idempotent). [close] is the owner's
 * stop button: pipes close, readers wake, writers fail fast.
 */
class ArusStreamStore(
    private val session: ArusSession,
    private val video: ArusFormat,
    private val audio: ArusFormat,
    private val conversation: ArusConversation,
    private val initFetcher: InitSegmentFetcher,
) : Closeable {

    private val pipes = mapOf(video.itag to BytePipe(), audio.itag to BytePipe())
    private val started = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    private var result: ArusResult? = null

    /** Media chunks that arrived without a selected format to land on. */
    @Volatile
    var droppedChunks: Int = 0
        private set

    /** The finished conversation's outcome, once it ends (diagnostics, Jatah). */
    fun streamResult(): ArusResult? = result

    fun start() {
        if (!started.compareAndSet(false, true)) return
        thread = Thread({ runConversation() }, "arus-stream").apply {
            isDaemon = true
            start()
        }
    }

    private fun runConversation() {
        try {
            for (format in listOf(video, audio)) {
                initFetcher.fetch(format)?.let { pipes.getValue(format.itag).write(it) }
            }
            val sink = object : ArusMediaSink {
                override fun onChunk(headerId: Int, format: FormatId?, data: ByteArray) {
                    val pipe = format?.let { pipes[it.itag] }
                    if (pipe == null) {
                        droppedChunks++
                    } else {
                        pipe.write(data)
                    }
                }
            }
            result = conversation.stream(session, video, audio, sink)
            pipes.values.forEach { it.end() }
        } catch (e: Exception) {
            val error = if (e is IOException) e else IOException(
                "Arus conversation failed: ${e.javaClass.simpleName}: ${e.message}", e,
            )
            pipes.values.forEach { it.fail(error) }
        }
    }

    /** Blocking read of [format]'s stream; -1 at end, throws on conversation failure. */
    fun read(format: ArusFormat, buffer: ByteArray, offset: Int, length: Int): Int =
        pipes.getValue(format.itag).read(buffer, offset, length)

    override fun close() {
        pipes.values.forEach { it.close() }
        thread?.interrupt()
    }
}
