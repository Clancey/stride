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

        /** A nested message field, decoded, or null if absent. */
        fun message(field: Int): Fields? = bytes[field]?.let { parse(it) }

        /**
         * Whether the message carried [field] at all, of any wire type.
         *
         * Needed to read a `oneof`, where "which branch is set" is answered by presence rather than
         * by value. `WorkoutResult` is exactly this shape: an absent error field is the difference
         * between a command that succeeded and one that failed.
         */
        fun hasField(field: Int): Boolean = varints.containsKey(field) || bytes.containsKey(field)

        /**
         * Human text for an `IFitError` nested in field 1.
         *
         * `WorkoutResult.error` is an `IFitError`, which is itself a oneof over nine error types,
         * each of which carries `errorCode = 1` and `message = 2`. So the useful text is two levels
         * down, not one. `WorkoutError` additionally reports the state it saw and the states it
         * expected, which is the difference between "the machine said no" and "the machine wanted
         * to be IDLE and was in RESULTS" — the second is actionable, the first is not.
         */
        fun errorDetail(): String {
            val blob = bytes[1] ?: return "refused"
            val error = parse(blob)
            // Field numbers are IFitError's oneof branches, in its own declaration order.
            val branches = mapOf(
                1 to "network", 2 to "user", 3 to "auth", 4 to "connection", 5 to "workout",
                6 to "input", 7 to "activityLog", 8 to "console", 9 to "programmedSession",
            )
            for ((field, name) in branches) {
                val nested = error.bytes[field]?.let { parse(it) } ?: continue
                val parts = mutableListOf<String>()
                parts += name
                nested.long(1)?.let { parts += "code $it" }
                nested.string(2)?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (field == 5) {
                    nested.long(3)?.let { parts += "state ${workoutStateName(it)}" }
                    nested.long(4)?.let { parts += "expected ${workoutStateName(it)}" }
                }
                return parts.joinToString(", ")
            }
            return "refused"
        }
    }

    /** `WorkoutState` enum names, so a refusal reads as a state rather than a bare number. */
    private fun workoutStateName(value: Long): String = when (value.toInt()) {
        0 -> "UNKNOWN"
        1 -> "IDLE"
        2 -> "DMK"
        3 -> "RUNNING"
        4 -> "PAUSED"
        5 -> "RESULTS"
        else -> "state $value"
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
     * Encode a single proto3 `double` field as a complete message body.
     *
     * Both control requests we send — `SpeedRequest{double kph = 1}` and
     * `InclineRequest{double percent = 1}` — are exactly one double in field 1, so this is the only
     * encoder the command path needs. Doubles are wire type 1 (64-bit), little-endian IEEE-754.
     *
     * Proto3 would normally omit a field equal to zero, since the default is indistinguishable from
     * absent. This writes it anyway: `SetSpeed(0.0)` is a request to stop the belt, and dropping it
     * on the floor because zero is the default would turn a stop into a no-op.
     */
    fun encodeDouble(field: Int, value: Double): ByteArray {
        val out = ByteArrayOutputStream(9)
        writeVarint(out, (field.toLong() shl 3) or 1L)
        val bits = java.lang.Double.doubleToRawLongBits(value)
        for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt())
        return out.toByteArray()
    }

    /**
     * One varint field — used for enums such as FanState.
     *
     * Writes the value even when it is zero, for the same reason [encodeDouble] does: proto3 omits
     * zero on the wire, but FAN_STATE_OFF *is* zero, and a fan that cannot be turned off is worse
     * than one that was never controllable.
     */
    fun encodeVarintField(field: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream(6)
        writeVarint(out, (field.toLong() shl 3))
        writeVarint(out, value.toLong())
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

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
