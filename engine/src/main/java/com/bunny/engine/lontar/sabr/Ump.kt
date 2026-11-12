package com.bunny.engine.lontar.sabr

/** A SABR wire problem the caller must hear about (truncated stream). */
class SabrParseException(message: String) : Exception(message)

/** One UMP part: a type varint, a size varint, then the payload. */
class UmpPart(val type: Int, val payload: ByteArray)

/**
 * UMP framing — the envelope every SABR response arrives in.
 *
 * The UMP varint is NOT the protobuf varint: the leading byte's count of
 * leading one-bits selects the total width (1–5 bytes), the first byte's
 * REMAINING bits are the value's LOWEST bits, and each following byte
 * contributes 8 more bits at growing shifts. Proven byte-exact against
 * live SABR_REDIRECT responses in the Fase 8 probes (size bytes a5 0f
 * decode to 997, ab 0f to 1003 — the big-endian-style layout found in
 * some write-ups decodes those as 9487/11023 and is wrong; probe jilid 1
 * paid for that lesson so this code does not have to).
 */
object Ump {

    const val PART_MEDIA_HEADER = 20
    const val PART_MEDIA = 21
    const val PART_MEDIA_END = 22
    const val PART_NEXT_REQUEST_POLICY = 35
    const val PART_FORMAT_INIT_METADATA = 42
    const val PART_SABR_REDIRECT = 43
    const val PART_SABR_ERROR = 44
    const val PART_SABR_SEEK = 45
    const val PART_RELOAD_PLAYER_RESPONSE = 46
    const val PART_SABR_CONTEXT_UPDATE = 57
    const val PART_STREAM_PROTECTION_STATUS = 58

    class Varint(val value: Long, val nextPos: Int)

    /** Reads one UMP varint at [start]. Throws on truncation. */
    fun readVarint(bytes: ByteArray, start: Int): Varint {
        if (start >= bytes.size) {
            throw SabrParseException("UMP varint at $start: out of bounds")
        }
        fun at(i: Int): Long {
            if (start + i >= bytes.size) {
                throw SabrParseException("UMP varint at $start: truncated")
            }
            return bytes[start + i].toLong() and 0xFF
        }
        val b0 = bytes[start].toInt() and 0xFF
        return when {
            b0 < 0x80 -> Varint(b0.toLong(), start + 1)
            b0 < 0xC0 -> Varint((b0 and 0x3F).toLong() or (at(1) shl 6), start + 2)
            b0 < 0xE0 -> Varint(
                (b0 and 0x1F).toLong() or (at(1) shl 5) or (at(2) shl 13), start + 3
            )
            b0 < 0xF0 -> Varint(
                (b0 and 0x0F).toLong() or (at(1) shl 4) or (at(2) shl 12) or
                    (at(3) shl 20),
                start + 4
            )
            else -> Varint(
                (b0 and 0x07).toLong() or (at(1) shl 3) or (at(2) shl 11) or
                    (at(3) shl 19) or (at(4) shl 27),
                start + 5
            )
        }
    }

    /**
     * Splits a whole UMP response into its parts. Unknown part types are
     * returned like any other — skipping by length is the tolerance law;
     * deciding what a part MEANS is [SabrPartDecoder]'s job. Throws
     * [SabrParseException] only when the framing itself is truncated.
     */
    fun parseParts(bytes: ByteArray): List<UmpPart> {
        val parts = ArrayList<UmpPart>()
        var pos = 0
        while (pos < bytes.size) {
            val type = readVarint(bytes, pos)
            val size = readVarint(bytes, type.nextPos)
            val start = size.nextPos
            if (size.value < 0 || size.value > bytes.size - start) {
                throw SabrParseException(
                    "UMP part type ${type.value}: size ${size.value} exceeds " +
                        "remaining ${bytes.size - start}"
                )
            }
            parts.add(
                UmpPart(
                    type.value.toInt(),
                    bytes.copyOfRange(start, start + size.value.toInt())
                )
            )
            pos = start + size.value.toInt()
        }
        return parts
    }
}
