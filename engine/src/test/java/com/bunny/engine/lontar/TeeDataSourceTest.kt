package com.bunny.engine.lontar

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.bunny.engine.lontar.sabr.TeeDataSource
import com.bunny.engine.lontar.sabr.itagFromUriString
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The M14 tee: bytes forwarded untouched, bytes reported exactly.
 * The JVM suite is Android-free (the mockable android.jar cannot
 * mint a Uri), so DataSpecs are allocated directly with their
 * position field set by reflection, and the itag resolution —
 * a TeeDataSource constructor seam whose default parses the real
 * URI string — is substituted; the default's parsing rule is
 * asserted separately against plain strings.
 */
class TeeDataSourceTest {

    /** Serves [content] as a file-like source from the spec's
     * position, in reads capped at [chunkCap]; read number
     * [failOnRead] (0-based) throws instead. */
    private class FakeUpstream(
        private val content: ByteArray,
        private val chunkCap: Int = Int.MAX_VALUE,
        private val failOnRead: Int = -1,
    ) : DataSource {
        private var cursor = 0
        private var reads = 0

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            cursor = dataSpec.position.toInt()
            return (content.size - cursor).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (reads == failOnRead) {
                reads++
                throw IOException("upstream read gagal")
            }
            reads++
            if (cursor >= content.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, chunkCap, content.size - cursor)
            content.copyInto(buffer, offset, cursor, cursor + n)
            cursor += n
            return n
        }

        override fun getUri(): Uri? = null

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() {}
    }

    private class RecordedSink {
        val calls = ArrayList<Triple<Int, Long, ByteArray>>()
        val sink: (Int, Long, ByteArray) -> Unit = { itag, position, data ->
            calls.add(Triple(itag, position, data))
        }
    }

    /** A DataSpec with only its position set (uri stays null). */
    private fun dataSpec(position: Long): DataSpec {
        val unsafeField = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val spec = Class.forName("sun.misc.Unsafe")
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, DataSpec::class.java) as DataSpec
        DataSpec::class.java.getDeclaredField("position")
            .apply { isAccessible = true }
            .setLong(spec, position)
        return spec
    }

    private fun drain(tee: TeeDataSource): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64)
        while (true) {
            val n = tee.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun reportsExactBytesAtAbsoluteOffsetsAcrossReads() {
        val content = ByteArray(200) { (it * 7 % 251).toByte() }
        val recorded = RecordedSink()
        val tee = TeeDataSource(
            FakeUpstream(content, chunkCap = 30),
            recorded.sink,
        ) { 251 }
        tee.open(dataSpec(position = 7))
        val served = drain(tee)
        // The player received the upstream's bytes from position 7
        // on, untouched and complete.
        assertArrayEquals(content.copyOfRange(7, content.size), served)
        // The sink saw the same bytes, chunk for chunk, each at its
        // absolute position.
        val expected = ArrayList<Triple<Int, Long, ByteArray>>()
        var at = 7
        while (at < content.size) {
            val n = minOf(30, content.size - at)
            expected.add(Triple(251, at.toLong(), content.copyOfRange(at, at + n)))
            at += n
        }
        assertEquals(expected.size, recorded.calls.size)
        for ((want, got) in expected zip recorded.calls) {
            assertEquals(want.first, got.first)
            assertEquals(want.second, got.second)
            assertArrayEquals(want.third, got.third)
        }
        tee.close()
    }

    @Test
    fun readExceptionPropagatesAndStopsReporting() {
        val content = ByteArray(200) { it.toByte() }
        val recorded = RecordedSink()
        val tee = TeeDataSource(
            FakeUpstream(content, chunkCap = 30, failOnRead = 1),
            recorded.sink,
        ) { 251 }
        tee.open(dataSpec(position = 0))
        val buffer = ByteArray(64)
        assertEquals(30, tee.read(buffer, 0, buffer.size))
        try {
            tee.read(buffer, 0, buffer.size)
            fail("the upstream's exception must propagate")
        } catch (e: IOException) {
            assertEquals("upstream read gagal", e.message)
        }
        // Later reads still serve bytes (the upstream recovered),
        // but nothing is reported any more.
        assertEquals(30, tee.read(buffer, 0, buffer.size))
        assertEquals(1, recorded.calls.size)
        tee.close()
    }

    @Test
    fun sinkFailureDisablesRecordingButNeverPlayback() {
        val content = ByteArray(90) { it.toByte() }
        var sinkCalls = 0
        val tee = TeeDataSource(
            FakeUpstream(content, chunkCap = 30),
            { _, _, _ -> sinkCalls++; throw RuntimeException("sink rusak") },
        ) { 251 }
        tee.open(dataSpec(position = 0))
        val served = drain(tee)
        assertArrayEquals(content, served)
        // The first failure disabled the sink: exactly one attempt.
        assertEquals(1, sinkCalls)
        tee.close()
    }

    @Test
    fun uriWithoutItagRecordsNothing() {
        // The production default resolves no itag from a null URI.
        val content = ByteArray(50) { it.toByte() }
        val recorded = RecordedSink()
        val tee = TeeDataSource(FakeUpstream(content), recorded.sink)
        tee.open(dataSpec(position = 0))
        assertArrayEquals(content, drain(tee))
        assertTrue(recorded.calls.isEmpty())
        tee.close()
    }

    @Test
    fun itagParsesFromTheUriQuery() {
        assertEquals(
            251,
            itagFromUriString("https://rr.example/videoplayback?itag=251&mime=audio"),
        )
        assertEquals(
            137,
            itagFromUriString("https://rr.example/videoplayback?mime=video&itag=137"),
        )
        assertNull(itagFromUriString("https://rr.example/videoplayback?mime=video"))
        assertNull(itagFromUriString("https://rr.example/videoplayback?itag=abc"))
        assertNull(itagFromUriString("https://rr.example/videoplayback"))
        assertNull(itagFromUriString(null))
    }
}
