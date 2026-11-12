package com.bunny.engine.lontar.sabr

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Mechanism 14 — the tee on the classic road: a [DataSource]
 * wrapper that forwards every call to its upstream untouched and,
 * on the side, reports the bytes it handed the player to a sink —
 * (itag, absolute position, a COPY of the bytes) — so the classic
 * playback simultaneously writes the video's spool.
 *
 * Recording must never harm playback, by construction:
 * - upstream results and exceptions propagate exactly as upstream
 *   produced them; an open/read failure also stops the reporting
 *   (a half-open stream's bytes are not a faithful record);
 * - a sink that throws has its failure swallowed and is disabled
 *   for the rest of this source's life — the spool loses a
 *   contribution, the viewer loses nothing.
 *
 * The itag comes from the request URI's `itag` query parameter
 * (every googlevideo media URL carries one); a URI without a
 * parseable itag passes through unrecorded. How the URI is read
 * is the injectable [itagOf] — the default parses the URI string;
 * tests substitute it because the JVM's Android stubs cannot mint
 * a Uri at all (the suite is Android-free by design).
 */
@UnstableApi
class TeeDataSource(
    private val upstream: DataSource,
    private val sink: (itag: Int, position: Long, data: ByteArray) -> Unit,
    private val itagOf: (DataSpec) -> Int? = { spec ->
        itagFromUriString(spec.uri?.toString())
    },
) : DataSource {

    private var itag: Int? = null
    private var position: Long = 0
    private var reporting = true

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        itag = itagOf(dataSpec)
        position = dataSpec.position
        return try {
            upstream.open(dataSpec)
        } catch (e: Exception) {
            reporting = false
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = try {
            upstream.read(buffer, offset, length)
        } catch (e: Exception) {
            reporting = false
            throw e
        }
        if (read > 0) {
            val at = position
            position += read
            val currentItag = itag
            if (reporting && currentItag != null) {
                try {
                    sink(
                        currentItag,
                        at,
                        buffer.copyOfRange(offset, offset + read),
                    )
                } catch (e: Exception) {
                    // The sink failed: recording stops, playback
                    // continues — never the other way round.
                    reporting = false
                }
            }
        }
        return read
    }

    override fun getUri(): android.net.Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream.responseHeaders

    override fun close() {
        reporting = false
        upstream.close()
    }

    /** Creates tees over an upstream factory's sources, one sink. */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val sink: (itag: Int, position: Long, data: ByteArray) -> Unit,
    ) : DataSource.Factory {
        override fun createDataSource(): TeeDataSource =
            TeeDataSource(upstreamFactory.createDataSource(), sink)
    }
}

/**
 * The itag a media URI names, from its query string — null when
 * the URI carries none or it does not parse. Deliberately string
 * work (not Uri.getQueryParameter): an itag is digits, so percent
 * decoding could never change the answer, and the rule stays
 * testable off-device.
 */
internal fun itagFromUriString(uri: String?): Int? {
    if (uri == null) return null
    val query = uri.substringAfter('?', "")
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        if (pair.substring(0, eq) == "itag") {
            return pair.substring(eq + 1).toIntOrNull()
        }
    }
    return null
}
