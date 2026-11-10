package com.bunny.engine.arus

import java.io.ByteArrayOutputStream

/**
 * Arus A1 — the smallest honest protobuf wire codec: just the writer and
 * reader the SABR messages need, hand-rolled the way Lontar hand-rolls
 * its JSON handling (platform libraries only, no codegen, no vendored
 * schemas). The field map for every message Arus speaks lives in
 * [AbrMessages] and is pinned there by fixture tests built from the
 * Fase A0 live census (see LAPORAN-PROBE-A0-ARUS.md).
 *
 * Only the wire types SABR actually uses are supported: varint (0),
 * length-delimited (2), fixed64 (1) and fixed32 (5) — the reader skips
 * what it does not model instead of failing on it.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(value: Long): ProtoWriter {
        var v = value
        while (true) {
            val bits = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(bits)
                return this
            }
            out.write(bits or 0x80)
        }
    }

    private fun tag(field: Int, wireType: Int): ProtoWriter =
        varint(((field shl 3) or wireType).toLong())

    fun varintField(field: Int, value: Long): ProtoWriter {
        tag(field, 0)
        return varint(value)
    }

    fun bytesField(field: Int, payload: ByteArray): ProtoWriter {
        tag(field, 2)
        varint(payload.size.toLong())
        out.write(payload)
        return this
    }

    fun stringField(field: Int, value: String): ProtoWriter =
        bytesField(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(): ByteArray = out.toByteArray()
}

/** One parsed protobuf field; [value] is Long, ByteArray, Int or Long by [wireType]. */
class ProtoField(val number: Int, val wireType: Int, val value: Any) {
    fun asVarint(): Long? = value as? Long
    fun asBytes(): ByteArray? = value as? ByteArray
    fun asString(): String? = (value as? ByteArray)?.toString(Charsets.UTF_8)
}

class ProtoFormatException(message: String) : Exception(message)

object ProtoReader {

    /** Parses [bytes] into fields; throws [ProtoFormatException] on truncation. */
    fun parse(bytes: ByteArray): List<ProtoField> {
        val fields = mutableListOf<ProtoField>()
        var i = 0
        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (i >= bytes.size) throw ProtoFormatException("varint past end at $i")
                val b = bytes[i].toInt() and 0xFF
                i++
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw ProtoFormatException("varint too long at $i")
            }
        }
        while (i < bytes.size) {
            val tag = readVarint()
            val number = (tag shr 3).toInt()
            when (val wire = (tag and 7).toInt()) {
                0 -> fields += ProtoField(number, wire, readVarint())
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || i + len > bytes.size) {
                        throw ProtoFormatException("bytes field $number overruns buffer")
                    }
                    fields += ProtoField(number, wire, bytes.copyOfRange(i, i + len))
                    i += len
                }
                1 -> {
                    if (i + 8 > bytes.size) throw ProtoFormatException("fixed64 overruns buffer")
                    var v = 0L
                    for (k in 0 until 8) v = v or ((bytes[i + k].toLong() and 0xFF) shl (8 * k))
                    fields += ProtoField(number, wire, v)
                    i += 8
                }
                5 -> {
                    if (i + 4 > bytes.size) throw ProtoFormatException("fixed32 overruns buffer")
                    var v = 0
                    for (k in 0 until 4) v = v or ((bytes[i + k].toInt() and 0xFF) shl (8 * k))
                    fields += ProtoField(number, wire, v)
                    i += 4
                }
                else -> throw ProtoFormatException("unsupported wire type $wire for field $number")
            }
        }
        return fields
    }

    /** First varint value of [number], or null. */
    fun List<ProtoField>.varint(number: Int): Long? =
        firstOrNull { it.number == number && it.wireType == 0 }?.asVarint()

    /** First bytes value of [number], or null. */
    fun List<ProtoField>.bytes(number: Int): ByteArray? =
        firstOrNull { it.number == number && it.wireType == 2 }?.asBytes()

    /** All bytes values of [number], in order. */
    fun List<ProtoField>.bytesList(number: Int): List<ByteArray> =
        filter { it.number == number && it.wireType == 2 }.mapNotNull { it.asBytes() }
}
