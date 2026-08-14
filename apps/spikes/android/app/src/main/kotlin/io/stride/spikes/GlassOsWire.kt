package io.stride.spikes

import java.io.ByteArrayOutputStream

/**
 * Just enough protobuf and gRPC to read a treadmill.
 *
 * We call six methods on a service whose messages have at most six scalar fields each, and every
 * metric message shares the same shape: field 1 is the workout id, field 2 is a time in seconds,
 * field 3 is the current value. Pulling in grpc-java and running protoc over 184 interdependent
 * `.proto` files through a Gradle plugin to generate stubs for that is a lot of moving parts to
 * own, especially on AGP 9 where the codegen plugin's support is unproven.
 *
 * So this decodes the wire format directly. The field numbers are not guesses — they are read from
 * the `.proto` files extracted from the console itself, in `protocol/glassos/`. If the firmware
 * ever changes them, re-extract and diff rather than patching constants here.
 *
 * The one thing this file must get right, and the reason it is written by hand rather than
 * generated, is that **a field which is absent must stay absent**. Generated protobuf classes hand
 * you a non-null `0.0` for a field the server never sent, and on a treadmill that becomes a
 * confident "0.0 mph" beside a belt that may be moving. Every accessor here returns null for an
 * absent field, and [GlassOsTelemetry] decides what absence means.
 */
object GlassOsWire {

    /** Wire types we can encounter. Groups (3, 4) are deprecated and unused by these protos. */
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5

    /**
     * A decoded message: field number to raw value. Absent fields are simply not present, which is
     * the whole point — see the class note.
     */
    class Fields(private val varints: Map<Int, Long>, private val bytes: Map<Int, ByteArray>) {

        /** A `double` field, or null if the server did not send it. */
        fun double(field: Int): Double? =
            varints[field]?.let { java.lang.Double.longBitsToDouble(it) }

        /** An `int32`/`int64` field, or null if absent. */
        fun long(field: Int): Long? = varints[field]

        /** A `bool` field, or null if absent. Callers must not default this to true. */
        fun bool(field: Int): Boolean? = varints[field]?.let { it != 0L }

        /** An enum field as its raw number, or null if absent. */
        fun enum(field: Int): Int? = varints[field]?.toInt()

        /** A `string` field, or null if absent. */
        fun string(field: Int): String? = bytes[field]?.toString(Charsets.UTF_8)
    }

    /**
     * Parse a protobuf message. Unknown fields are skipped rather than treated as errors, so a
     * firmware that adds fields does not break a client that does not know about them.
     */
    fun parse(input: ByteArray): Fields {
        val varints = HashMap<Int, Long>()
        val blobs = HashMap<Int, ByteArray>()
        var i = 0
        while (i < input.size) {
            val (tag, afterTag) = readVarint(input, i) ?: break
            i = afterTag
            val field = (tag ushr 3).toInt()
            when ((tag and 0x7L).toInt()) {
                WIRE_VARINT -> {
                    val (v, next) = readVarint(input, i) ?: return Fields(varints, blobs)
                    varints[field] = v
                    i = next
                }
                // doubles and fixed64 arrive little-endian; keep the raw bits and let the accessor
                // decide how to read them.
                WIRE_FIXED64 -> {
                    if (i + 8 > input.size) return Fields(varints, blobs)
                    var bits = 0L
                    for (b in 7 downTo 0) bits = (bits shl 8) or (input[i + b].toLong() and 0xFF)
                    varints[field] = bits
                    i += 8
                }
                WIRE_LENGTH -> {
                    val (len, afterLen) = readVarint(input, i) ?: return Fields(varints, blobs)
                    val end = afterLen + len.toInt()
                    if (len < 0 || end > input.size) return Fields(varints, blobs)
                    blobs[field] = input.copyOfRange(afterLen, end)
                    i = end
                }
                WIRE_FIXED32 -> {
                    if (i + 4 > input.size) return Fields(varints, blobs)
                    var bits = 0L
                    for (b in 3 downTo 0) bits = (bits shl 8) or (input[i + b].toLong() and 0xFF)
                    varints[field] = bits
                    i += 4
                }
                else -> return Fields(varints, blobs) // group or corrupt; stop rather than guess
            }
        }
        return Fields(varints, blobs)
    }

    private fun readVarint(input: ByteArray, from: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = from
        while (i < input.size && shift <= 63) {
            val b = input[i].toInt()
            result = result or ((b.toLong() and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    /**
     * Wrap a message in a gRPC length-prefixed frame: one compression byte, then a big-endian
     * four-byte length. An `Empty` request is therefore five zero bytes, not an empty body.
     */
    fun frame(message: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(message.size + 5)
        out.write(0) // not compressed
        out.write((message.size ushr 24) and 0xFF)
        out.write((message.size ushr 16) and 0xFF)
        out.write((message.size ushr 8) and 0xFF)
        out.write(message.size and 0xFF)
        out.write(message)
        return out.toByteArray()
    }

    /** The five-byte frame for a request carrying no fields, which is most of what we send. */
    val EMPTY_FRAME: ByteArray = frame(ByteArray(0))

    /**
     * Strip the gRPC frame from a response body, returning the message payload.
     *
     * Returns null for a body too short to contain a frame, rather than throwing: a truncated
     * response must surface as "no reading", not as a crash on a device with no Back button.
     */
    fun unframe(body: ByteArray): ByteArray? {
        if (body.size < 5) return null
        val length = ((body[1].toInt() and 0xFF) shl 24) or
            ((body[2].toInt() and 0xFF) shl 16) or
            ((body[3].toInt() and 0xFF) shl 8) or
            (body[4].toInt() and 0xFF)
        val end = 5 + length
        if (length < 0 || end > body.size) return null
        return body.copyOfRange(5, end)
    }

    /**
     * Split a server-streaming body into its individual messages.
     *
     * A subscription delivers many frames back to back, and a read may land mid-frame, so this
     * returns only the frames that are complete and reports where it stopped. The caller keeps the
     * remainder and prepends it to the next read.
     */
    fun unframeAll(buffer: ByteArray, size: Int): Pair<List<ByteArray>, Int> {
        val messages = ArrayList<ByteArray>()
        var offset = 0
        while (size - offset >= 5) {
            val length = ((buffer[offset + 1].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 3].toInt() and 0xFF) shl 8) or
                (buffer[offset + 4].toInt() and 0xFF)
            if (length < 0) return messages to size // corrupt; drop the buffer rather than spin
            val end = offset + 5 + length
            if (end > size) break // frame not fully arrived yet
            messages.add(buffer.copyOfRange(offset + 5, end))
            offset = end
        }
        return messages to offset
    }
}
