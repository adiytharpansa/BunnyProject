package com.bunny.engine.lontar.sabr

import java.io.ByteArrayOutputStream

/**
 * The smallest honest protobuf codec: just enough to speak SABR.
 *
 * Written from the wire format itself (varint + length-delimited fields),
 * the same primitives the Fase 8 probes used and verified against live
 * responses (see ~/workspace/research_notes/sabr-probe/). No protobuf
 * runtime, no generated code, no third-party YouTube code.
 *
 * The reader is total: [ProtoReader.parseOrNull] returns null on any
 * malformation instead of throwing, so a weird submessage can never crash
 * playback — the protocol tolerance law of the Fase 8 design.
 */
class ProtoField(
    val number: Int,
    val wireType: Int,
    /** Set when [wireType] == 0. */
    val varintValue: Long?,
    /** Set when [wireType] is 2 (length-delimited) or 1/5 (fixed). Raw bytes. */
    val bytesValue: ByteArray?,
) {
    fun asString(): String? = bytesValue?.let { String(it, Charsets.UTF_8) }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_BYTES = 2
        const val WIRE_FIXED32 = 5
    }
}

object ProtoReader {

    fun parseOrNull(bytes: ByteArray): List<ProtoField>? {
        val out = ArrayList<ProtoField>()
        var pos = 0

        fun readVarint(): Long? {
            var result = 0L
            var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos].toInt() and 0xFF
                pos++
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 70) return null
            }
            return null
        }

        while (pos < bytes.size) {
            val tag = readVarint() ?: return null
            val number = (tag shr 3).toInt()
            val wire = (tag and 0x7).toInt()
            if (number == 0) return null
            when (wire) {
                ProtoField.WIRE_VARINT -> {
                    val v = readVarint() ?: return null
                    out.add(ProtoField(number, wire, v, null))
                }
                ProtoField.WIRE_BYTES -> {
                    val len = readVarint() ?: return null
                    if (len < 0 || len > bytes.size - pos) return null
                    val v = bytes.copyOfRange(pos, pos + len.toInt())
                    pos += len.toInt()
                    out.add(ProtoField(number, wire, null, v))
                }
                ProtoField.WIRE_FIXED64 -> {
                    if (bytes.size - pos < 8) return null
                    out.add(ProtoField(number, wire, null, bytes.copyOfRange(pos, pos + 8)))
                    pos += 8
                }
                ProtoField.WIRE_FIXED32 -> {
                    if (bytes.size - pos < 4) return null
                    out.add(ProtoField(number, wire, null, bytes.copyOfRange(pos, pos + 4)))
                    pos += 4
                }
                // Groups (3/4) and reserved wire types: not parseable here.
                else -> return null
            }
        }
        return out
    }

    /** First varint value carried by [fieldNumber], or null. */
    fun List<ProtoField>.firstVarint(fieldNumber: Int): Long? =
        firstOrNull { it.number == fieldNumber && it.wireType == ProtoField.WIRE_VARINT }
            ?.varintValue

    /** First length-delimited value carried by [fieldNumber], or null. */
    fun List<ProtoField>.firstBytes(fieldNumber: Int): ByteArray? =
        firstOrNull { it.number == fieldNumber && it.wireType == ProtoField.WIRE_BYTES }
            ?.bytesValue
}

class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(value: Long): ProtoWriter {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return this
            }
            out.write(b or 0x80)
        }
    }

    fun tag(field: Int, wire: Int): ProtoWriter = varint((field.toLong() shl 3) or wire.toLong())

    fun fieldVarint(field: Int, value: Long): ProtoWriter {
        tag(field, ProtoField.WIRE_VARINT)
        return varint(value)
    }

    fun fieldBytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, ProtoField.WIRE_BYTES)
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    fun fieldString(field: Int, value: String): ProtoWriter =
        fieldBytes(field, value.toByteArray(Charsets.UTF_8))

    fun build(): ByteArray = out.toByteArray()
}
