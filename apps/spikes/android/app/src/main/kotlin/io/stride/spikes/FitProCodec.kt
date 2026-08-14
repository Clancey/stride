package io.stride.spikes

/**
 * Pure codec for the Sindarin / FitPro register protocol that iFit's `com.ifit.glassos_service`
 * uses to drive the treadmill hardware directly (see `docs/DIRECT_MACHINE_PROTOCOL.md`).
 *
 * # This codec cannot move the treadmill, and that is deliberate.
 *
 * Nothing here opens a USB device, a BLE GATT connection, or a socket. There is no transport, no
 * reference to `UsbDeviceConnection`, `BluetoothGatt`, or any Android I/O type. The functions turn
 * numbers into `ByteArray`s and back; a `ByteArray` sitting in memory drives no motor.
 *
 * Two independent reasons keep it that way:
 *
 * 1. **Standing safety order.** The project rule is that no code path may command belt movement.
 *    A frame that reaches the machine's write endpoint could do exactly that, so this file must
 *    stay unreachable from any Service, from `OverlayService`, `SpikeBridge`, `MachineLink`, or
 *    `GlassOsClient`. It is a leaf with no callers in the running app.
 * 2. **The framing is unverified.** The source hand-off derived the register map and the value
 *    serializers from clean decompiled code, but the *core send routine* (`th/n`, `hc/g0`,
 *    `wh/c`) was JADX-corrupted. The exact in-frame byte order and any CRC/checksum are therefore
 *    guesses. Shipping a write path built on a guess is how you drive a belt with the wrong value.
 *
 * # Verified vs unverified
 *
 * Each public function's KDoc states whether its wire format is **VERIFIED** (transcribed from
 * uncorrupted decompiled serializers) or **UNVERIFIED** (reconstructed, must be confirmed against
 * captured traffic). Treat that tag as load-bearing: a future reader must never mistake a
 * reconstructed guess for a confirmed format.
 *
 * Before any of this is wired to a transport, the checklist in `docs/DIRECT_MACHINE_PROTOCOL.md`
 * must be satisfied: capture real device traffic, confirm the frame byte order and checksum, and
 * only then, with the user's explicit authorisation, consider a write path.
 */
object FitProCodec {

    /** Device address for a treadmill; occupies frame byte 0. VERIFIED (`yh/a.java`). */
    const val TREADMILL_ADDRESS: Int = 4

    /** Frame status byte 3: the machine accepted the command. VERIFIED (`vh/b.java`). */
    const val STATUS_DONE: Int = 2

    /** Frame status byte 3: the machine does not support the command. VERIFIED (`vh/b.java`). */
    const val STATUS_CMD_NOT_SUPPORTED: Int = 1

    /**
     * Workout lifecycle states written to / read from [Register.WORKOUT_MODE].
     *
     * VERIFIED numeric values (`yh/n.java`). Note that the ordinal is not the wire value —
     * `PAUSE_OVERRIDE` is 20, not 15 — so [value] must always be used on the wire.
     */
    enum class WorkoutMode(val value: Int) {
        UNKNOWN(0),
        IDLE(1),
        RUNNING(2),
        PAUSE(3),
        RESULTS(4),
        DEBUG(5),
        LOG(6),
        MAINTENANCE(7),
        DMK(8),
        DEMO(9),
        WARM_UP(10),
        COOL_DOWN(11),
        SLEEP(12),
        RESUME(13),
        LOCKED(14),
        PAUSE_OVERRIDE(20);

        companion object {
            /**
             * Maps a raw wire value to a [WorkoutMode]. An unrecognised value resolves to
             * [UNKNOWN] rather than throwing, because a firmware revision can introduce modes this
             * table has never seen and telemetry decoding must not crash on one.
             */
            fun fromValue(value: Int): WorkoutMode = entries.firstOrNull { it.value == value } ?: UNKNOWN
        }
    }

    /**
     * The register (bit-field) descriptors the protocol addresses.
     *
     * [fieldId] and [readOnly] are VERIFIED (`sh/a.java`). [byteLength] is VERIFIED where the
     * source stated it explicitly (KPH, GRADE, the distance registers, RUNNING_TIME,
     * ACTUAL_DISTANCE) and otherwise defaults to the protocol's common 4-byte width; a width that
     * matters for decoding a specific register should be confirmed against captured traffic.
     *
     * [readOnly] is the safety-relevant flag: a `true` register is machine-reported telemetry, and
     * attempting to encode a write to it is a programming error, not a value the machine will honour
     * (`th/a.java` throws "trying to write to a read only field").
     */
    enum class Register(val fieldId: Int, val byteLength: Int, val readOnly: Boolean) {
        // Writable setpoints.
        KPH(0, 4, readOnly = false),
        GRADE(1, 4, readOnly = false), // incline
        RESISTANCE(2, 4, readOnly = false),
        FAN_SPEED(8, 4, readOnly = false),
        VOLUME(9, 4, readOnly = false),
        PULSE(10, 4, readOnly = false),
        WORKOUT_MODE(12, 4, readOnly = false),
        AUDIO_SOURCE(14, 4, readOnly = false),

        // Read-only telemetry reported by the machine.
        WATTS(3, 4, readOnly = true),
        CURRENT_DISTANCE(4, 4, readOnly = true),
        RPM(5, 4, readOnly = true),
        DISTANCE(6, 4, readOnly = true),
        KEY_OBJECT(7, 4, readOnly = true),
        RUNNING_TIME(11, 8, readOnly = true),
        CALORIES(13, 4, readOnly = true),
        LAP_TIME(15, 4, readOnly = true),
        ACTUAL_KPH(16, 4, readOnly = true),
        ACTUAL_INCLINE(17, 4, readOnly = true),
        ACTUAL_DISTANCE(18, 8, readOnly = true),
        RECOVERABLE_CONSOLE_TIME(19, 4, readOnly = true),
        CURRENT_CALORIES(20, 4, readOnly = true),
    }

    // ---- value serializers ------------------------------------------------------------------

    /**
     * Encodes a speed setpoint: `(short)(kph * 100)`, **big-endian**, 2 content bytes.
     *
     * VERIFIED (`g7/z`). The cast truncates toward zero exactly as the Java `(short)` cast does.
     *
     * Note the endianness asymmetry with the rest of the protocol: speed is big-endian while
     * incline and distance are little-endian. This is genuinely what the source serializers do and
     * must not be "tidied" into consistency.
     */
    fun encodeSpeed(kph: Double): ByteArray {
        val hundredths = (kph * 100).toInt() and 0xFFFF // (short) width
        return byteArrayOf(
            ((hundredths ushr 8) and 0xFF).toByte(),
            (hundredths and 0xFF).toByte(),
        )
    }

    /**
     * Encodes an incline setpoint: `(int)(grade * 100)`, **little-endian**, 4 bytes.
     *
     * VERIFIED (`g7/s`). Grade may be negative (a decline), so the value is a signed 32-bit int in
     * two's complement; the little-endian layout places the low byte first.
     */
    fun encodeIncline(gradePercent: Double): ByteArray = intToLeBytes((gradePercent * 100).toInt(), 4)

    /**
     * Encodes a workout-mode setpoint as a single byte carrying [WorkoutMode.value].
     *
     * VERIFIED (`g7/v`): the serializer emits exactly one byte.
     */
    fun encodeWorkoutMode(mode: WorkoutMode): ByteArray = byteArrayOf((mode.value and 0xFF).toByte())

    /**
     * Encodes a distance value as a 4-byte **little-endian** int.
     *
     * VERIFIED (`uh/d`, `e() = 4`). The machine's distance registers are read-only telemetry; this
     * encoder exists to round-trip against [decodeDistance] in tests, not to write distance to the
     * machine (which the protocol does not permit).
     */
    fun encodeDistance(value: Int): ByteArray = intToLeBytes(value, 4)

    // ---- value deserializers ----------------------------------------------------------------

    /**
     * Decodes a 4-byte **little-endian** distance int, as `uh/d.h(byte[])` does.
     *
     * VERIFIED. This is the register the machine reports; the app does not integrate speed × time
     * for live distance. See `docs/DIRECT_MACHINE_PROTOCOL.md`.
     */
    fun decodeDistance(bytes: ByteArray): Int {
        require(bytes.size >= 4) { "distance needs 4 bytes, got ${bytes.size}" }
        return leBytesToLong(bytes, 0, 4).toInt()
    }

    /**
     * Reads [length] bytes at [offset] as a **little-endian** unsigned integer into a `Long`.
     *
     * VERIFIED primitive: little-endian is the protocol's default integer order (incline, all
     * distance registers). Returning a `Long` keeps 8-byte registers (RUNNING_TIME, ACTUAL_DISTANCE)
     * representable without sign surprises.
     */
    fun leBytesToLong(bytes: ByteArray, offset: Int = 0, length: Int): Long {
        require(length in 1..8) { "length must be 1..8, got $length" }
        require(offset + length <= bytes.size) { "want $length bytes at $offset, have ${bytes.size}" }
        var acc = 0L
        for (i in 0 until length) {
            acc = acc or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return acc
    }

    /**
     * Reads [length] bytes at [offset] as a **big-endian** unsigned integer into a `Long`.
     *
     * VERIFIED primitive: big-endian is used only by the speed serializer, so this is the
     * counterpart to [encodeSpeed] for decoding an echoed speed value.
     */
    fun beBytesToLong(bytes: ByteArray, offset: Int = 0, length: Int): Long {
        require(length in 1..8) { "length must be 1..8, got $length" }
        require(offset + length <= bytes.size) { "want $length bytes at $offset, have ${bytes.size}" }
        var acc = 0L
        for (i in 0 until length) {
            acc = (acc shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return acc
    }

    /** Serializes [value] into [length] **little-endian** bytes. VERIFIED primitive. */
    fun intToLeBytes(value: Int, length: Int): ByteArray {
        require(length in 1..4) { "length must be 1..4, got $length" }
        val out = ByteArray(length)
        for (i in 0 until length) {
            out[i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
        return out
    }

    // ---- read-only guard --------------------------------------------------------------------

    /**
     * Builds a register-write payload, refusing outright to write a read-only register.
     *
     * The guard is **VERIFIED intent** — the real implementation throws "trying to write to a read
     * only field" (`th/a.java`) — and is the safety-relevant part of this function.
     *
     * The **framing is UNVERIFIED**. The layout returned here (`[fieldId] + value`) is a plausible
     * reconstruction of the BitFieldCommItem body, but the corrupted send routine means the true
     * on-wire order and any checksum are unknown. This must not be transmitted; it exists so tests
     * can prove the read-only guard fires.
     */
    fun encodeRegisterWrite(register: Register, value: ByteArray): ByteArray {
        require(!register.readOnly) {
            "trying to write to a read only field: ${register.name} (field ${register.fieldId})"
        }
        return byteArrayOf((register.fieldId and 0xFF).toByte()) + value
    }

    // ---- frame envelopes --------------------------------------------------------------------

    /**
     * Wraps [payload] in the FitPro2 envelope `[0x02, 0x04, 0x02, len] + payload` (`th/q.java`).
     *
     * The **envelope prefix and length byte are VERIFIED**; the **byte order and any checksum of the
     * payload itself are UNVERIFIED** (the send routine was corrupted). The associated 400 ms
     * response timeout is a transport concern and is intentionally not represented here.
     */
    fun encodeFitPro2Frame(payload: ByteArray): ByteArray {
        require(payload.size <= 0xFF) { "FitPro2 length byte cannot exceed 255, got ${payload.size}" }
        val header = byteArrayOf(0x02, 0x04, 0x02, (payload.size and 0xFF).toByte())
        return header + payload
    }

    /**
     * Splits [payload] into the BLE chunk sequence (`th/o.java`): a lead packet
     * `[0xFE, 0x02, len, chunkCount]` followed by up to 18 payload bytes per 20-byte data packet
     * `[idx, dataLen, <=18 payload bytes]`, with the final data packet's index set to `0xFF`.
     *
     * The **chunk framing is VERIFIED**; the payload it carries is only as trustworthy as whatever
     * produced it (see [encodeFitPro2Frame] / [encodeRegisterWrite]).
     *
     * Returns the lead packet first, then the data packets in order. An empty payload still yields a
     * single terminating (`0xFF`) data packet so the sequence is always well-formed.
     */
    fun chunkForBle(payload: ByteArray): List<ByteArray> {
        require(payload.size <= 0xFF) { "BLE length byte cannot exceed 255, got ${payload.size}" }
        val maxData = 18
        val segments = if (payload.isEmpty()) {
            listOf(ByteArray(0))
        } else {
            (payload.indices step maxData).map { start ->
                payload.copyOfRange(start, minOf(start + maxData, payload.size))
            }
        }

        val chunkCount = segments.size
        val lead = byteArrayOf(0xFE.toByte(), 0x02, (payload.size and 0xFF).toByte(), (chunkCount and 0xFF).toByte())

        val packets = ArrayList<ByteArray>(chunkCount + 1)
        packets.add(lead)
        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.lastIndex
            val idx = if (isLast) 0xFF else index
            packets.add(byteArrayOf((idx and 0xFF).toByte(), (segment.size and 0xFF).toByte()) + segment)
        }
        return packets
    }
}
