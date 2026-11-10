package com.bunny.engine.arus

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * Arus A2 — the player's door into an [ArusStreamStore]: an ordinary
 * Media3 DataSource whose bytes happen to come from a live SABR
 * conversation instead of a file or a socket. Length is unknown (the
 * conversation is still running), reads block on the pipe, and a
 * conversation failure surfaces as the IOException it is.
 *
 * One instance serves one format; a [Factory] binds a store + format
 * pair, so the video and audio sources of one playback share the ONE
 * conversation behind them.
 */
class ArusDataSource(
    private val store: ArusStreamStore,
    private val format: ArusFormat,
) : BaseDataSource(false) {

    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        store.start()
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = store.read(format, buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(
        private val store: ArusStreamStore,
        private val format: ArusFormat,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ArusDataSource(store, format)
    }
}
