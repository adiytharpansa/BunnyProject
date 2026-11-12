package com.bunny.engine.lontar.sabr

import java.io.File
import java.io.RandomAccessFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Spool (Gulungan) — Fase 8 design §3/§4, mechanism 1:
 * ONE spool, TWO roads.
 *
 * A spool is plain files on disk: per video, one data file per track
 * (itag) in which every segment lives at the ABSOLUTE byte offset its
 * SABR header announced (start_range) — shifted by the segment's
 * rendition anchor ([SabrSegment.spoolDelta]) when the server
 * switched the rendition backing the track mid-stream, so the file
 * stays one unbroken whole. That addressing is the whole point: it
 * is the same addressing the classic road's DASH segments use, so a
 * video whose SABR road dies mid-stream continues on the classic
 * road into the SAME files, at the same offsets, keeping both the
 * viewer's position and every byte already fetched. And because
 * watching fills the spool, a later Download adopts it instead of
 * re-fetching (mechanism 2).
 *
 * Beside each data file sits a small JSON manifest — the track's
 * segment map (sequence -> offset, length, timeline), its init
 * segment length, and its known end segment number. The manifest is
 * the truth; data without a manifest entry is not trusted.
 *
 * Sanity before trust (mechanism 5): on load, segments reaching past
 * the real file length are dropped, and an init segment that does not
 * begin with a known container head — an fMP4 `ftyp` box or a WebM
 * EBML header (the opus audio track is WebM; both arrive over SABR) —
 * is struck from the manifest (the media segments are kept — they
 * are repairable data; playback honesty is the caller's check via
 * [TrackSpool.hasPlayableHead]).
 *
 * The store enforces a total storage budget by evicting whole video
 * spools, least-recently-touched first.
 */
class SpoolStore(
    val rootDir: File,
    val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {
    init {
        rootDir.mkdirs()
    }

    fun open(videoId: String): VideoSpool {
        val dir = File(rootDir, sanitize(videoId))
        dir.mkdirs()
        return VideoSpool(videoId, dir).also { it.touch() }
    }

    fun exists(videoId: String): Boolean = File(rootDir, sanitize(videoId)).isDirectory

    fun delete(videoId: String) {
        File(rootDir, sanitize(videoId)).deleteRecursively()
    }

    fun totalBytes(): Long = videoDirs().sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /**
     * Evicts whole spools (LRU by last touch) until the store fits
     * the budget. [protectVideoId] is never evicted. Returns the
     * evicted video ids, oldest first.
     */
    fun enforceBudget(protectVideoId: String? = null): List<String> {
        val evicted = ArrayList<String>()
        var total = totalBytes()
        if (total <= budgetBytes) return evicted
        val candidates = videoDirs()
            .filter { it.name != sanitize(protectVideoId ?: "") }
            .sortedBy { lastTouched(it) }
        for (dir in candidates) {
            if (total <= budgetBytes) break
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            dir.deleteRecursively()
            total -= size
            evicted.add(dir.name)
        }
        return evicted
    }

    private fun videoDirs(): List<File> =
        rootDir.listFiles()?.filter { it.isDirectory } ?: emptyList()

    private fun lastTouched(dir: File): Long {
        val marker = File(dir, ACCESS_MARKER)
        return if (marker.exists()) marker.lastModified() else dir.lastModified()
    }

    companion object {
        const val DEFAULT_BUDGET_BYTES: Long = 512L * 1024 * 1024
        const val ACCESS_MARKER = "lastaccess"

        fun sanitize(videoId: String): String =
            videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}

class VideoSpool internal constructor(
    val videoId: String,
    val dir: File,
) {
    private val tracks = HashMap<Int, TrackSpool>()

    @Synchronized
    fun track(itag: Int): TrackSpool = tracks.getOrPut(itag) {
        TrackSpool(dir, itag)
    }

    /** Itags with a manifest on disk, including ones this instance
     * has not opened yet (a previous session's tracks). */
    fun trackItags(): Set<Int> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            ?.toSet()
            ?: emptySet()

    fun touch() {
        val marker = File(dir, SpoolStore.ACCESS_MARKER)
        if (!marker.exists()) marker.createNewFile()
        marker.setLastModified(System.currentTimeMillis())
    }

    fun totalBytes(): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Synchronized
    fun close() {
        tracks.values.forEach { it.close() }
        tracks.clear()
    }
}

/**
 * One track's spool file + manifest. Writes land at absolute offsets
 * and are immediately readable; the manifest is updated in memory by
 * [noteInit]/[noteSegment] and persisted by [flushManifest] (atomic
 * tmp-then-rename).
 */
class TrackSpool internal constructor(
    private val dir: File,
    val itag: Int,
) {
    val dataFile: File = File(dir, "$itag.bin")
    private val manifestFile: File = File(dir, "$itag.json")
    private var raf: RandomAccessFile? = null

    var formatId: SabrFormatId? = null
        private set
    var endSegmentNumber: Int? = null
        private set
    var initLength: Long = 0
        private set

    /**
     * Rendition state mirrored from the session (the pump syncs
     * it with every persist): the lastModified of the rendition
     * being appended, and its spool anchor. Persisted so a spool
     * reopened later resumes the same byte space; both zero in
     * manifests written before rendition anchoring existed.
     */
    var wireRendition: Long = 0
        private set
    var currentDelta: Long = 0
        private set
    private val _segments = LinkedHashMap<Int, SabrSegment>()
    val segments: Map<Int, SabrSegment> get() = _segments

    init {
        loadManifest()
    }

    // ------------------------------------------------------------ data io

    @Synchronized
    fun write(offset: Long, data: ByteArray) {
        val file = raf ?: RandomAccessFile(dataFile, "rw").also { raf = it }
        file.seek(offset)
        file.write(data)
    }

    @Synchronized
    fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        RandomAccessFile(dataFile, "r").use { file ->
            file.seek(offset)
            file.readFully(out)
        }
        servedBytes += out.size
        return out
    }

    /**
     * Bytes ever handed to readers. Diagnostics evidence: it tells a
     * spool the player never touches (counter near zero) from one it
     * drains happily — the pump-versus-reader question, in one number.
     */
    @Volatile
    var servedBytes: Long = 0
        private set

    fun fileLength(): Long = if (dataFile.exists()) dataFile.length() else 0

    /** Merged [start, end) byte intervals the manifest vouches for. */
    fun writtenRanges(): List<LongRange> {
        val intervals = ArrayList<LongRange>()
        if (initLength > 0) intervals.add(0L until initLength)
        for (seg in _segments.values) {
            if (seg.length > 0) {
                intervals.add(seg.startRange until seg.startRange + seg.length)
            }
        }
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.first }
        val merged = ArrayList<LongRange>()
        var cur = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.first <= cur.last + 1) {
                cur = cur.first..maxOf(cur.last, next.last)
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        return merged
    }

    fun hasRange(offset: Long, length: Long): Boolean =
        writtenRanges().any { it.first <= offset && offset + length <= it.last + 1 }

    /**
     * The playable frontier: bytes readable from the very start —
     * the end of the first merged range when it begins at offset 0,
     * else 0. SABR fills sequentially, so this is what a reader may
     * consume right now.
     */
    fun frontier(): Long {
        val first = writtenRanges().firstOrNull() ?: return 0
        return if (first.first == 0L) first.last + 1 else 0
    }

    /** True when the spool head is a playable container start. */
    fun hasPlayableHead(): Boolean = initLength > 0 && hasRange(0, initLength)

    fun storedSegmentBytes(): Long = _segments.values.sumOf { it.length }

    // ------------------------------------------------------- bookkeeping

    fun noteFormat(formatId: SabrFormatId) {
        this.formatId = formatId
    }

    fun noteEndSegmentNumber(value: Int) {
        if (endSegmentNumber == null) endSegmentNumber = value
    }

    fun noteInit(length: Long) {
        initLength = length
    }

    fun noteSegment(segment: SabrSegment) {
        _segments[segment.sequence] = segment
    }

    /** Mirrors the session track's rendition state (see above). */
    fun noteWireState(wireRendition: Long, currentDelta: Long) {
        this.wireRendition = wireRendition
        this.currentDelta = currentDelta
    }

    @Synchronized
    fun flushManifest() {
        val root = JSONObject()
        root.put("itag", itag)
        formatId?.let { fmt ->
            root.put("lastModified", fmt.lastModified ?: 0)
            root.put("xtags", fmt.xtags ?: "")
        }
        root.put("endSegmentNumber", endSegmentNumber ?: -1)
        root.put("initLength", initLength)
        root.put("wr", wireRendition)
        root.put("cd", currentDelta)
        val arr = JSONArray()
        for (seg in _segments.values.sortedBy { it.sequence }) {
            val o = JSONObject()
            o.put("seq", seg.sequence)
            o.put("startRange", seg.startRange)
            o.put("length", seg.length)
            o.put("startMs", seg.startMs)
            o.put("durMs", seg.durationMs)
            o.put("d", seg.spoolDelta)
            arr.put(o)
        }
        root.put("segments", arr)
        val tmp = File(dir, "$itag.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(manifestFile)
    }

    @Synchronized
    fun close() {
        raf?.close()
        raf = null
    }

    // ------------------------------------------------------------ loading

    private fun loadManifest() {
        if (!manifestFile.exists()) return
        val root = try {
            JSONObject(manifestFile.readText())
        } catch (e: Exception) {
            return // a corrupt manifest vouches for nothing
        }
        val fileLen = fileLength()
        if (root.has("lastModified") || root.has("xtags")) {
            val lm = root.optLong("lastModified", 0)
            val xt = root.optString("xtags", "")
            formatId = SabrFormatId(
                itag = itag,
                lastModified = if (lm != 0L) lm else null,
                xtags = xt.ifEmpty { null },
            )
        }
        val end = root.optInt("endSegmentNumber", -1)
        if (end > 0) endSegmentNumber = end
        var init = root.optLong("initLength", 0)
        if (init > 0) {
            // Sanity: the init must fit the file and open with a
            // known container head. Otherwise it is struck —
            // segments stay, the head is marked unplayable.
            val ok = init <= fileLen && hasContainerHead()
            if (!ok) init = 0
        }
        initLength = init
        wireRendition = root.optLong("wr", 0)
        currentDelta = root.optLong("cd", 0)
        val arr = root.optJSONArray("segments") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val seg = SabrSegment(
                sequence = o.getInt("seq"),
                startMs = o.getLong("startMs"),
                durationMs = o.getLong("durMs"),
                startRange = o.getLong("startRange"),
                length = o.getLong("length"),
                spoolDelta = o.optLong("d", 0),
            )
            // Sanity: never vouch for bytes past the real file end.
            if (seg.startRange + seg.length <= fileLen) {
                _segments[seg.sequence] = seg
            }
        }
    }

    private fun hasContainerHead(): Boolean {
        if (!dataFile.exists() || dataFile.length() < 8) return false
        RandomAccessFile(dataFile, "r").use { file ->
            val head = ByteArray(8)
            file.readFully(head)
            val ftyp = head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
            val ebml = head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
            return ftyp || ebml
        }
    }
}
