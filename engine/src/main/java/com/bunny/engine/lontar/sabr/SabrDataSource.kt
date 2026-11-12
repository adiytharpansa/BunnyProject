package com.bunny.engine.lontar.sabr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * The Media3 face of the spool (design Bagian 3): a [BaseDataSource]
 * serving one spool track per open, addressed by URIs of the form
 * `bunny-sabr://<videoId>/<itag>`. All serving logic lives in
 * [SpoolReader]; this adapter only translates between Media3's
 * DataSpec accounting and the reader's absolute positions.
 *
 * Stream lifetime deliberately does NOT live here: Media3 re-opens
 * data sources on every seek, so the pump and the session are owned
 * by [SabrPlayback], and instances of this class are disposable
 * windows onto their shared spool.
 */
class SabrDataSource(
    private val spool: VideoSpool,
    private val road: SpoolRoadState,
) : BaseDataSource(/* isNetwork = */ false) {

    private var reader: SpoolReader? = null
    private var uri: Uri? = null
    private var position: Long = 0
    private var remaining: Long = UNKNOWN_LENGTH
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val specUri = dataSpec.uri
        val itag = specUri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("URI gulungan tidak dikenal: $specUri")
        road.checkAlive()
        val trackReader = SpoolReader(spool.track(itag), road)
        reader = trackReader
        uri = specUri
        position = dataSpec.position
        val known = trackReader.knownLength()
        val length = when {
            dataSpec.length != UNKNOWN_LENGTH -> dataSpec.length
            known >= 0 -> maxOf(0L, known - position)
            else -> UNKNOWN_LENGTH
        }
        remaining = length
        opened = true
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val active = reader ?: throw IOException("SabrDataSource belum dibuka")
        val wanted = if (remaining != UNKNOWN_LENGTH) {
            minOf(length.toLong(), remaining).toInt()
        } else {
            length
        }
        val read = active.readAt(position, buffer, offset, wanted)
        if (read == -1) return C.RESULT_END_OF_INPUT
        position += read
        if (remaining != UNKNOWN_LENGTH) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    private companion object {
        /**
         * "Length not yet known" as a Long. Media3's C.LENGTH_UNSET is
         * an Int constant in 1.11 while DataSpec lengths are Long —
         * comparing them directly does not compile, so the accounting
         * here carries its own sentinel (same value, -1).
         */
        const val UNKNOWN_LENGTH = -1L
    }

    override fun close() {
        reader?.close()
        reader = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
