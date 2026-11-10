package com.bunny.engine.arus

import java.io.ByteArrayOutputStream

/**
 * Arus A1 — UMP (Unified Media Protocol) framing: the envelope every
 * SABR response arrives in. A stream of parts, each framed as
 * `umpVarint(type) + umpVarint(size) + payload`.
 *
 * The UMP varint is NOT the protobuf varint and NOT the UTF-8-style
 * "leading bits hold the high bits" scheme either: the first byte's
 * leading bits declare the total width, and its REMAINING bits are the
 * value's LOW bits; each following byte contributes a full 8 bits above
 * them. Fase A0 proved this against a live SABR_REDIRECT: the size
 * bytes `8C 0F` must decode to 972 — exactly the payload length that
 * followed — where the UTF-8-style reading (3087) desynchronised the
 * whole stream (see LAPORAN-PROBE-A0-ARUS.md). [UmpVarintTest] pins
 * that vector so the mistake cannot come back.
 */
object UmpVarint {

    fun write(value: Long): ByteArray {
        require(value >= 0) { "UMP varint is unsigned" }
        val out = ByteArrayOutputStream()
        when {
            value < 0x80 -> out.write(value.toInt())
            value < 0x4000 -> {
                out.write(0x80 or (value and 0x3F).toInt())
                out.write((value shr 6).toInt())
            }
            value < 0x200000 -> {
                out.write(0xC0 or (value and 0x1F).toInt())
                out.write((value shr 5).toInt() and 0xFF)
                out.write((value shr 13).toInt())
            }
            value < 0x10000000 -> {
                out.write(0xE0 or (value and 0x0F).toInt())
                out.write((value shr 4).toInt() and 0xFF)
                out.write((value shr 12).toInt() and 0xFF)
                out.write((value shr 20).toInt())
            }
            else -> {
                out.write(0xF0 or (value and 0x07).toInt())
                out.write((value shr 3).toInt() and 0xFF)
                out.write((value shr 11).toInt() and 0xFF)
                out.write((value shr 19).toInt() and 0xFF)
                out.write((value shr 27).toInt())
            }
        }
        return out.toByteArray()
    }

    /** Reads one varint at [offset]; returns the value and the next offset. */
    fun read(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        if (offset >= bytes.size) throw UmpFormatException("varint past end at $offset")
        val b = bytes[offset].toInt() and 0xFF
        fun byteAt(i: Int): Long {
            if (offset + i >= bytes.size) {
                throw UmpFormatException("truncated varint at $offset")
            }
            return (bytes[offset + i].toInt() and 0xFF).toLong()
        }
        return when {
            b < 0x80 -> b.toLong() to offset + 1
            b < 0xC0 -> ((b and 0x3F).toLong() or (byteAt(1) shl 6)) to offset + 2
            b < 0xE0 -> ((b and 0x1F).toLong() or (byteAt(1) shl 5) or (byteAt(2) shl 13)) to offset + 3
            b < 0xF0 -> ((b and 0x0F).toLong() or (byteAt(1) shl 4) or (byteAt(2) shl 12) or (byteAt(3) shl 20)) to offset + 4
            else -> ((b and 0x07).toLong() or (byteAt(1) shl 3) or (byteAt(2) shl 11) or (byteAt(3) shl 19) or (byteAt(4) shl 27)) to offset + 5
        }
    }
}

/** Part type numbers observed in the Fase A0 census, plus the documented rest. */
object UmpParts {
    const val MEDIA_HEADER = 20
    const val MEDIA = 21
    const val MEDIA_END = 22
    const val NEXT_REQUEST_POLICY = 35
    const val FORMAT_INITIALIZATION_METADATA = 42
    const val SABR_REDIRECT = 43
    const val SABR_ERROR = 44
    const val SABR_SEEK = 45
    const val RELOAD_PLAYER_RESPONSE = 46
    const val PLAYBACK_START_POLICY = 47

    /** Empty part that opens the media burst; meaning unknown (A0 census). */
    const val PART_49 = 49

    /** The conversation's format roster (A0 census; parsed by [FormatRoster]). */
    const val FORMAT_ROSTER = 51

    /** Small opaque session token (A0 census); retained raw, never interpreted in A1. */
    const val PART_52 = 52

    const val SABR_CONTEXT_UPDATE = 57
    const val STREAM_PROTECTION_STATUS = 58
    const val SABR_CONTEXT_SENDING_POLICY = 59
}

/** One UMP part: a type number and its raw payload. */
class UmpPart(val type: Int, val payload: ByteArray) {
    override fun toString(): String = "UmpPart(type=$type, size=${payload.size})"
}

class UmpFormatException(message: String) : Exception(message)

object UmpReader {

    /**
     * Parses a whole response body into parts. Strict on purpose: a size
     * that overruns the body is truncation or desynchronisation, never
     * something to silently clamp — A0 showed a single wrong varint bit
     * turns the rest of the stream into garbage that LOOKS parseable.
     */
    fun parseAll(bytes: ByteArray): List<UmpPart> {
        val parts = mutableListOf<UmpPart>()
        var i = 0
        while (i < bytes.size) {
            val (type, afterType) = UmpVarint.read(bytes, i)
            val (size, afterSize) = UmpVarint.read(bytes, afterType)
            if (size < 0 || afterSize + size > bytes.size) {
                throw UmpFormatException(
                    "part type=$type declares $size bytes with ${bytes.size - afterSize} available",
                )
            }
            parts += UmpPart(type.toInt(), bytes.copyOfRange(afterSize, afterSize + size.toInt()))
            i = afterSize + size.toInt()
        }
        return parts
    }

    /** Serialises one part (used by tests and fixtures; the client only reads). */
    fun writePart(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(UmpVarint.write(type.toLong()))
        out.write(UmpVarint.write(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }
}
