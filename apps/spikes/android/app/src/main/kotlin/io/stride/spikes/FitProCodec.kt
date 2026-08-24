package io.stride.spikes

/**
 * Codec for the Sindarin / FitPro register protocol that iFit's `com.ifit.glassos_service` uses to
 * drive treadmill hardware directly.
 *
 * # Provenance
 *
 * Every wire-format decision here was transcribed from decompiled `com.ifit.glassos` 6.14.6, and
 * where JADX gave up, from baksmali output of the same APK. The classes are cited per function.
 * Nothing in this file is a reconstruction or a plausible guess.
 *
 * That is a change of status, not just a change of confidence. An earlier version of this codec
 * carried an "UNVERIFIED framing" warning because `docs/DIRECT_MACHINE_PROTOCOL.md` reported that
 * the core send routine (`th/n`, `hc/g0`, `wh/c`) was JADX-corrupted. That report was accurate but
 * pointed at the wrong classes: `th/n` orchestrates *chunk transmission*, and the frame is not
 * built there. Frame construction lives in `vh/d.e()` and `vh/f.j()`, both of which decompile
 * cleanly and completely.
 *
 * ## Corrections to the earlier reading
 *
 * The hand-off document this codec was first written from contains four errors, each of which
 * would have produced a well-formed frame carrying the wrong number:
 *
 *  1. **Speed is little-endian, not big-endian.** `g7/z.g()` reads
 *     `tt.p.E2(new byte[]{(byte)(v >> 8), (byte)v})`, and `tt/p.E2` is `reversedArray()`
 *     (`tt/p.java:103`). Its own decoder `h()` reads low byte first, which settles it independently.
 *  2. **Incline is two bytes, not four.** `g7/s.e()` returns 2.
 *  3. **The trailing `4`/`8`/`12` in the register table are Kotlin default-argument masks**, emitted
 *     by the synthetic constructor at `sh/a.java:329`, not byte lengths. Widths come from the
 *     serializer's `e()`.
 *  4. **Field ids diverge from enum ordinals after `ACTUAL_INCLINE`.** `ACTUAL_DISTANCE` is
 *     ordinal 18 but field **19**; `CURRENT_CALORIES` is ordinal 20 but field **21**. Since the
 *     field id chooses a *bit position*, an off-by-one selects a different register outright.
 *
 * Error 1 is the instructive one. Big-endian and little-endian agree whenever both bytes are equal
 * and differ by a factor of 256 the rest of the time, so a wrong-endian speed does not fail — it
 * asks for 25.6 kph when the rider asked for 1.0.
 *
 * # This file still cannot move a treadmill
 *
 * There is no transport here: no `UsbDeviceConnection`, no `BluetoothGatt`, no socket, no Android
 * I/O type of any kind. These functions turn numbers into `ByteArray`s and back, and a `ByteArray`
 * in memory drives no motor. Transmission is [FitProTransport]'s job, and the decision to transmit
 * is [DirectMachineCommands]'.
 */
object FitProCodec {

    // ---- addressing -----------------------------------------------------------------------------

    /**
     * Device addresses. Frame byte 0. VERIFIED (`yh/a.java`).
     *
     * [ADDRESS_MAIN] is where the handshake starts, because it is the only address known before the
     * machine has told us anything: `xh/n0.F()` sends `DEVICE_INFO` to `yh.a.MAIN` and uses whatever
     * comes back to address everything afterwards. [ADDRESS_TREADMILL] is what a treadmill's motor
     * controller *usually* answers on, but it is a default, not an assumption — see
     * [DirectMachineSession.connect].
     */
    const val ADDRESS_MAIN: Int = 2

    /** Device address for a treadmill. VERIFIED (`yh/a.java`: `TREADMILL(4)`). */
    const val ADDRESS_TREADMILL: Int = 4

    /**
     * Bytes of frame overhead: a 3-byte header plus the trailing checksum. VERIFIED (`vh/d.f16655b`).
     *
     * Public because the serial transport needs it to know when a frame it is reassembling has
     * declared an impossible length.
     */
    const val FRAME_OVERHEAD: Int = 4

    /**
     * The software version above which a console demands `VERIFY_SECURITY` before it will accept
     * writes. VERIFIED (`xh/n0.smali`: `const/16 v13, 0x4b` then `if-le … :cond_c`, skipping the
     * security branch for anything at or below it).
     */
    const val SECURITY_REQUIRED_ABOVE: Int = 75

    /**
     * Offset of the first read value in a response.
     *
     * Three header bytes then the status byte, so values begin at 4 — the same number as
     * [FRAME_OVERHEAD] by coincidence of layout rather than by sharing a meaning, which is why they
     * are separate constants. VERIFIED (`vh/f.a()` seeds its cursor with `f16655b`).
     */
    private const val RESPONSE_VALUE_OFFSET: Int = 4

    /**
     * Command types. Frame byte 2. VERIFIED (`vh/c.java`).
     *
     * [CONNECT] and [DISCONNECT] are listed because they exist in the protocol, but note that
     * GlassOS never sends either — JADX marks both as referenced only from the enum's `values()`
     * array, whereas the info and security commands below have real call sites. "Connecting" to a
     * FitPro machine is not a command; it is the handshake in [DirectMachineSession.connect].
     */
    enum class Command(val value: Int) {
        READ_WRITE_DATA(2),
        CONNECT(4),
        DISCONNECT(5),
        SUPPORTED_DEVICES(-128),
        DEVICE_INFO(-127),
        SYSTEM_INFO(-126),
        VERSION_INFO(-124),
        SUPPORTED_COMMANDS(-120),
        VERIFY_SECURITY(-112),
        SERIAL_NUMBER(-107),
        ;

        companion object {
            /**
             * Resolves a command byte, accepting it either sign-extended (`-127`) or masked
             * (`0x81`).
             *
             * The high commands are negative as Kotlin `Int` literals because they are `byte`
             * constants in the source this was recovered from. That makes the obvious caller —
             * `bytes[i].toInt() and 0xFF`, which is how every other byte in this file is read —
             * silently fail to match. Normalising both sides to a byte removes the trap rather than
             * relying on every future caller to remember which convention this one enum uses.
             */
            fun fromValue(value: Int): Command? {
                val normalized = value.toByte()
                return entries.firstOrNull { it.value.toByte() == normalized }
            }
        }
    }

    /**
     * Machine reply status. Response byte 3. VERIFIED (`vh/b.java`).
     *
     * Only [DONE] means the machine acted. `vh/f.a()` treats everything else as a failed read/write
     * and abandons the rest of the response, and so does [parseResponse].
     */
    enum class Status(val value: Int) {
        DEV_NOT_SUPPORTED(0),
        CMD_NOT_SUPPORTED(1),
        DONE(2),
        IN_PROGRESS(3),
        FAILED(4),
        TIME_LEFT(5),
        UNKNOWN_FAILURE(7),
        SECURITY_BLOCK(8),
        COMM_FAILED(9),
        ;

        companion object {
            /**
             * Resolves a wire value, defaulting to [CMD_NOT_SUPPORTED] for anything unrecognised.
             *
             * Matches `th/c.a()`, and the default is the safe direction: an unknown status is
             * treated as "the machine did not do what was asked", never as success.
             */
            fun fromValue(value: Int): Status =
                entries.firstOrNull { it.value == value } ?: CMD_NOT_SUPPORTED
        }
    }

    /**
     * Workout lifecycle, written to and read from [Register.WORKOUT_MODE]. VERIFIED (`yh/n.java`).
     *
     * The ordinal is not the wire value — `PAUSE_OVERRIDE` is 20, not 15 — so [value] is always what
     * goes on the wire. This enum is also **not** `GlassOsClient`'s `WorkoutState`, which numbers
     * the same concepts differently; [FitProValues.glassOsWorkoutState] translates.
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
        PAUSE_OVERRIDE(20),
        ;

        companion object {
            /** Unrecognised values resolve to [UNKNOWN] so a firmware revision cannot crash telemetry. */
            fun fromValue(value: Int): WorkoutMode = entries.firstOrNull { it.value == value } ?: UNKNOWN
        }
    }

    /**
     * Fan setting written to [Register.FAN_SPEED]. VERIFIED (`hj/f.java`).
     *
     * These happen to be the same numbers GlassOS's `FanState` proto uses, but they are a different
     * enum reached over a different wire, so [FitProValues.fanStateFromGlassOs] does the conversion
     * explicitly rather than casting an int across the boundary.
     */
    enum class FanState(val value: Int) {
        OFF(0),
        LOW(1),
        MEDIUM(2),
        HIGH(3),
        AUTO(4),
        UNKNOWN(5),
        ;

        companion object {
            fun fromValue(value: Int): FanState = entries.firstOrNull { it.value == value } ?: UNKNOWN
        }
    }

    // ---- registers ------------------------------------------------------------------------------

    /**
     * The register (bit-field) descriptors the protocol addresses.
     *
     * Names, [fieldId] and [readOnly] are VERIFIED from the enum initialiser at `sh/a.java:145`,
     * whose constructor (`sh/a.java:329`) is `a(name, ordinal, serializer, fieldId, readOnly,
     * metric)`. Note that **ordinal is not fieldId**: they agree up to `ACTUAL_INCLINE` and diverge
     * after it, so `ACTUAL_DISTANCE` is ordinal 18 but field **19**.
     *
     * [width] is VERIFIED from the serializer each register was constructed with:
     *
     * | serializer | width | meaning                                     |
     * |------------|-------|---------------------------------------------|
     * | `g7/z`     | 2     | speed, `kph * 100`, LE                      |
     * | `g7/s`     | 2     | incline, `percent * 100`, LE **signed**     |
     * | `g7/y`     | 2     | resistance                                  |
     * | `uh/a`     | 1     | `uh.d(1)` — 1-byte LE int                   |
     * | `uh/g`     | 2     | `uh.d(2)` — 2-byte LE int                   |
     * | `uh/c`     | 4     | `uh.d(4)` — 4-byte LE int                   |
     * | `g7/q`     | 1     | [FanState]                                  |
     * | `g7/v`     | 1     | [WorkoutMode]                               |
     * | `g7/w`     | 4     | pulse: `[bpm, 0, 0, source]`                |
     * | `g7/r`     | 14    | key object; decode only, `g()` throws       |
     * | `f5/a0`    | 4     | calories                                    |
     * | `m1/h3`    | 1     | boolean / small enum                        |
     *
     * Registers whose serializer this codec does not model are deliberately absent rather than
     * present with a guessed width, because a wrong width does not just corrupt its own value — read
     * values are packed contiguously, so it shifts every value after it.
     *
     * [readOnly] is the safety-relevant flag. `th/a.java` throws "trying to write to a read only
     * field" rather than sending, and [writeOf] does the same.
     */
    enum class Register(val fieldId: Int, val width: Int, val readOnly: Boolean) {
        // ---- writable setpoints ----
        KPH(0, 2, readOnly = false),
        GRADE(1, 2, readOnly = false),
        RESISTANCE(2, 2, readOnly = false),

        /**
         * The legacy fan register. Carries no GlassOS metric binding in `ai/c.java`, which maps the
         * `FAN_STATE` metric to field **98** instead — see [FAN_STATE]. Kept because older consoles
         * are likely to implement this one and not 98; [FitProCodec] does not choose between them,
         * [DirectMachineCommands] does.
         */
        FAN_SPEED(8, 1, readOnly = false),
        VOLUME(9, 1, readOnly = false),
        PULSE(10, 4, readOnly = false),
        WORKOUT_MODE(12, 1, readOnly = false),
        SYSTEM_UNITS(36, 1, readOnly = false),

        /**
         * The fan register GlassOS actually drives: `ai/c.java` binds the `FAN_STATE` metric to
         * `sh.a.f14868w0`, which `sh/a.java` constructs as `("FAN_STATE", 84, qVar, 98, false,
         * d.FAN_STATE)`. Same 1-byte [FanState] encoding as [FAN_SPEED], different field.
         */
        FAN_STATE(98, 1, readOnly = false),

        // ---- machine-reported telemetry ----
        WATTS(3, 2, readOnly = true),
        CURRENT_DISTANCE(4, 4, readOnly = true),
        RPM(5, 2, readOnly = true),
        DISTANCE(6, 4, readOnly = true),

        /** 14 bytes describing the console's key/heart-rate object. Decoded opaquely. */
        KEY_OBJECT(7, 14, readOnly = true),
        RUNNING_TIME(11, 4, readOnly = true),
        CALORIES(13, 4, readOnly = true),
        LAP_TIME(15, 2, readOnly = true),
        ACTUAL_KPH(16, 2, readOnly = true),
        ACTUAL_INCLINE(17, 2, readOnly = true),
        ACTUAL_DISTANCE(19, 4, readOnly = true),
        RECOVERABLE_CONSOLE_TIME(20, 4, readOnly = true),
        CURRENT_CALORIES(21, 4, readOnly = true),

        // ---- the machine's own limits ----
        // Worth more than they look: these let the coordinator clamp to what this machine actually
        // supports instead of to a constant compiled in from a different treadmill's spec sheet.
        MAX_GRADE(27, 2, readOnly = true),
        MIN_GRADE(28, 2, readOnly = true),
        MAX_KPH(30, 2, readOnly = true),
        MIN_KPH(31, 2, readOnly = true),

        // ---- lifetime counters and console flags ----
        MOTOR_TOTAL_DISTANCE(69, 4, readOnly = true),
        TOTAL_TIME(70, 4, readOnly = true),
        START_REQUESTED(96, 1, readOnly = true),
        IS_READY_TO_DISCONNECT(116, 1, readOnly = true),
        ;

        /** Which mask byte carries this register's bit. VERIFIED (`vh/f.j`: `D / 8`). */
        internal val maskIndex: Int get() = fieldId / 8

        /** This register's bit within its mask byte. VERIFIED (`vh/f.j`: `1 << (D % 8)`). */
        internal val maskBit: Int get() = 1 shl (fieldId % 8)
    }

    /** A register paired with the bytes to write to it. */
    data class Write(val register: Register, val value: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Write && register == other.register && value.contentEquals(other.value))

        override fun hashCode(): Int = 31 * register.hashCode() + value.contentHashCode()
    }

    /**
     * Pairs [register] with [value], refusing to build a write to machine-reported telemetry.
     *
     * Mirrors `th/a.java`'s constructor, which throws rather than sending. Throwing is right here:
     * a read-only write is a programming error that no runtime condition can produce, and silently
     * dropping it would leave a caller believing it had commanded something.
     */
    fun writeOf(register: Register, value: ByteArray): Write {
        require(!register.readOnly) {
            "trying to write to a read only field: ${register.name} (field ${register.fieldId})"
        }
        require(value.size == register.width) {
            "${register.name} takes ${register.width} bytes, got ${value.size}"
        }
        return Write(register, value)
    }

    // ---- value serializers ----------------------------------------------------------------------

    /**
     * Encodes a speed as `(short)(kph * 100)`, **little-endian**, 2 bytes. VERIFIED (`g7/z.g`).
     *
     * The source builds `{(byte)(v >> 8), (byte)v}` and then passes it through `tt.p.E2`, which
     * reverses the array (`tt/p.java:103`) — so the low byte is transmitted first. Reading `g()`
     * without following `E2` is what produced the "speed is big-endian" claim in our docs.
     */
    fun encodeSpeed(kph: Double): ByteArray = intToLe((kph * 100).toInt(), 2)

    /**
     * Decodes a 2-byte little-endian speed in hundredths of a km/h. VERIFIED (`g7/z.h`).
     *
     * Divides by `100.0` where the source divides by `100` as **integers**, truncating to whole
     * km/h. That looks like a genuine defect in GlassOS rather than a protocol rule: the register
     * is plainly in hundredths, the sibling incline decoder divides by `100.0d`, and nothing else
     * about the format suggests whole-number speeds. Reproducing the truncation would throw away
     * real precision, so this returns the honest value — but callers comparing against a
     * GlassOS-reported speed must tolerate up to a full km/h of disagreement.
     */
    fun decodeSpeed(bytes: ByteArray): Double = leToInt(bytes, 2) / 100.0

    /**
     * Encodes an incline as `(int)(grade * 100)` truncated to 2 **little-endian** bytes.
     * VERIFIED (`g7/s.g`, `e() = 2`).
     *
     * Declines are negative, so this is a signed 16-bit value in two's complement.
     */
    fun encodeIncline(gradePercent: Double): ByteArray = intToLe((gradePercent * 100).toInt(), 2)

    /** Decodes a 2-byte little-endian **signed** incline in hundredths of a percent. VERIFIED (`g7/s.h`). */
    fun decodeIncline(bytes: ByteArray): Double = leToInt(bytes, 2).toShort() / 100.0

    /** Encodes a workout mode as one byte. VERIFIED (`g7/v.g`, `e() = 1`). */
    fun encodeWorkoutMode(mode: WorkoutMode): ByteArray = byteArrayOf((mode.value and 0xFF).toByte())

    /** Decodes a one-byte workout mode. VERIFIED (`g7/v.h`). */
    fun decodeWorkoutMode(bytes: ByteArray): WorkoutMode = WorkoutMode.fromValue(bytes[0].toInt() and 0xFF)

    /** Encodes a fan setting as one byte. VERIFIED (`g7/q.g`, `e() = 1`). */
    fun encodeFanState(state: FanState): ByteArray = byteArrayOf((state.value and 0xFF).toByte())

    /** Decodes a one-byte fan setting. VERIFIED (`g7/q.h`). */
    fun decodeFanState(bytes: ByteArray): FanState = FanState.fromValue(bytes[0].toInt() and 0xFF)

    /**
     * Decodes a little-endian integer register (distance, elapsed time, and friends).
     * VERIFIED (`uh/d.h`, with `uh/c` fixing the width at 4).
     */
    fun decodeInt(bytes: ByteArray): Int = leToInt(bytes, bytes.size)

    /** Serialises [value] into [length] little-endian bytes. VERIFIED primitive (`uh/d.g`). */
    fun intToLe(value: Int, length: Int): ByteArray {
        require(length in 1..4) { "length must be 1..4, got $length" }
        return ByteArray(length) { ((value ushr (8 * it)) and 0xFF).toByte() }
    }

    /** Reads the first [length] bytes as a little-endian **unsigned** integer. VERIFIED primitive (`uh/d.h`). */
    fun leToInt(bytes: ByteArray, length: Int): Int {
        require(length in 1..4) { "length must be 1..4, got $length" }
        require(bytes.size >= length) { "want $length bytes, have ${bytes.size}" }
        var acc = 0
        for (i in 0 until length) acc = acc or ((bytes[i].toInt() and 0xFF) shl (8 * i))
        return acc
    }

    // ---- payload assembly -------------------------------------------------------------------------

    /**
     * Builds one register block: `[maskByteCount][mask bytes…]` and, when [includeValues], the
     * serialized values appended in ascending field-id order. VERIFIED (`vh/f.j`).
     *
     * The mask spans **field id zero through the highest requested id**, so asking for one
     * high-numbered register carries every lower mask byte as zeroes. That is what
     * `(maxFieldId / 8) + 1` means, and it is why the count is transmitted: the receiver cannot
     * otherwise tell where the mask stops and the values start.
     *
     * An empty list encodes as a single zero byte — "no mask bytes follow" — rather than as nothing
     * at all, because a read/write body always carries both blocks and the receiver splits them
     * positionally.
     *
     * Ascending field-id order is not a convention this codec chose. The mask is emitted low bit
     * first, so it is the only order in which a receiver can pair values back to bits, and
     * `l1/k.compare` (case 8 for reads, case 9 for writes) sorts on `sh.a.D` ascending via
     * `compareValues` to match.
     */
    internal fun registerBlock(registers: List<Register>, values: Map<Register, ByteArray>?): ByteArray {
        if (registers.isEmpty()) return byteArrayOf(0)

        val sorted = registers.sortedBy { it.fieldId }
        val maskBytes = (sorted.last().fieldId / 8) + 1
        val payload = if (values == null) 0 else sorted.sumOf { values.getValue(it).size }

        val out = ByteArray(1 + maskBytes + payload)
        out[0] = maskBytes.toByte()
        for (register in sorted) {
            val at = 1 + register.maskIndex
            out[at] = (out[at].toInt() or register.maskBit).toByte()
        }
        if (values != null) {
            var cursor = 1 + maskBytes
            for (register in sorted) {
                val value = values.getValue(register)
                value.copyInto(out, cursor)
                cursor += value.size
            }
        }
        return out
    }

    /**
     * Builds a READ_WRITE_DATA body: the write block (with values) followed by the read block
     * (without). VERIFIED (`vh/f.g`, which concatenates via `tt.p.D2`).
     *
     * Order matters and is not symmetric: writes carry values and reads do not, so a receiver
     * parsing the blocks the other way round would read value bytes as a mask.
     */
    fun readWriteBody(writes: List<Write>, reads: List<Register>): ByteArray {
        writes.forEach {
            require(!it.register.readOnly) { "read-only register in write list: ${it.register.name}" }
        }
        require(writes.map { it.register }.toSet().size == writes.size) {
            "duplicate register in write list"
        }
        val writeBlock = registerBlock(writes.map { it.register }, writes.associate { it.register to it.value })
        val readBlock = registerBlock(reads.distinct(), null)
        return writeBlock + readBlock
    }

    // ---- framing ----------------------------------------------------------------------------------

    /**
     * Wraps [body] as `[address][totalLength][command][body…][checksum]`. VERIFIED (`vh/d.e`).
     *
     * `totalLength` counts the whole frame including the header and the checksum, which is why
     * [FRAME_OVERHEAD] is added rather than the body length being written directly.
     *
     * [address] has no default on purpose. The right address is the one the handshake answered on,
     * and defaulting it would let a caller who forgot to ask address a device that may not exist —
     * a failure that looks like a dead machine rather than like a bug.
     */
    fun frame(body: ByteArray, address: Int, command: Command = Command.READ_WRITE_DATA): ByteArray {
        val total = body.size + FRAME_OVERHEAD
        require(total <= 0xFF) { "frame length byte cannot exceed 255, got $total" }
        val out = ByteArray(total)
        out[0] = (address and 0xFF).toByte()
        out[1] = (total and 0xFF).toByte()
        out[2] = (command.value and 0xFF).toByte()
        body.copyInto(out, 3)
        out[total - 1] = checksum(out, total - 1)
        return out
    }

    /**
     * Sums [length] bytes of [bytes] modulo 256. VERIFIED (`vh/d.e`).
     *
     * A plain additive sum, not a CRC and not an XOR — both of which the earlier reading of this
     * protocol listed as live possibilities. The source loop bounds are `bArr[1] - 1`, the declared
     * total length minus one, so the checksum covers everything up to but excluding itself.
     */
    fun checksum(bytes: ByteArray, length: Int): Byte {
        var acc = 0
        for (i in 0 until length) acc = (acc + (bytes[i].toInt() and 0xFF)) and 0xFF
        return acc.toByte()
    }

    /**
     * Wraps a frame in the FitPro2 envelope `[0x02, 0x04, 0x02, frameLength]`. VERIFIED (`th/q.java`).
     *
     * The associated 400 ms reply timeout is a transport concern and lives in [FitProTransport].
     */
    fun fitPro2Envelope(frame: ByteArray): ByteArray {
        require(frame.size <= 0xFF) { "FitPro2 length byte cannot exceed 255, got ${frame.size}" }
        return byteArrayOf(0x02, 0x04, 0x02, (frame.size and 0xFF).toByte()) + frame
    }

    /**
     * Splits [payload] into BLE packets: a lead `[0xFE, 0x02, len, chunkCount]` then 20-byte data
     * packets `[index, dataLength, …up to 18 bytes]`, the last of which is indexed `0xFF`.
     * VERIFIED (`th/o.java`).
     *
     * `chunkCount` counts the lead packet as well as the data packets, which is why the source adds
     * one. Data packets are padded to a full 20 bytes.
     */
    fun chunkForBle(payload: ByteArray): List<ByteArray> {
        require(payload.size <= 0xFF) { "BLE length byte cannot exceed 255, got ${payload.size}" }
        val maxData = 18
        val segments = if (payload.isEmpty()) {
            emptyList()
        } else {
            (payload.indices step maxData).map {
                payload.copyOfRange(it, minOf(it + maxData, payload.size))
            }
        }
        val lead = byteArrayOf(
            0xFE.toByte(),
            0x02,
            (payload.size and 0xFF).toByte(),
            ((if (segments.isEmpty()) 1 else segments.size) + 1).toByte(),
        )
        val packets = ArrayList<ByteArray>(segments.size + 1)
        packets.add(lead)
        segments.forEachIndexed { index, segment ->
            val packet = ByteArray(20)
            packet[0] = (if (index == segments.lastIndex) 0xFF else index).toByte()
            packet[1] = segment.size.toByte()
            segment.copyInto(packet, 2)
            packets.add(packet)
        }
        return packets
    }

    /**
     * Rebuilds a frame from the console's notification fragments — the inverse of [chunkForBle].
     *
     * The console answers in the shape it is addressed in. GlassOS's own receive path (`th/q.g`)
     * concatenates the raw notifications and then drops **26** bytes from the front, removes two
     * bytes at every eighteenth offset from index 14, and drops `18 - lastPacket[1]` from the end.
     * Decoded, that is: a 20-byte lead packet, then `[index, dataLength, data…]` packets padded out
     * to 20 bytes, and the 26 also swallows the four-byte FitPro2 envelope — which is how we know
     * the reply carries the envelope and not just the bare frame.
     *
     * This is written as an incremental assembler rather than "concatenate then slice" because the
     * notifications arrive one callback at a time and the alternative is buffering with no idea when
     * to stop. Feeding fragments straight to the parser, which is what this replaced, hands it the
     * first 18 bytes of a frame and calls it an answer.
     *
     * Not thread-safe. Callers drive it from a single callback thread and [reset] between exchanges.
     */
    class BleReassembler(private val onFrame: (ByteArray) -> Unit) {

        private var buffer = ByteArray(0)
        private var expected = -1

        fun reset() {
            buffer = ByteArray(0)
            expected = -1
        }

        /** Offer one notification payload. Calls back once a whole frame has been rebuilt. */
        fun accept(packet: ByteArray) {
            if (packet.size < 2) return

            // A lead packet restarts assembly. It is recognised by its marker rather than by our own
            // state, so a console that re-sends after a dropped fragment recovers instead of
            // splicing the retry onto the tail of the abandoned frame. A data packet cannot be
            // mistaken for one: index bytes run 0,1,2,… with 0xFF last, so 0xFE would need 254
            // packets, and a frame is at most a few.
            if (packet.size >= 4 && packet[0] == LEAD_MARKER && packet[1] == LEAD_KIND) {
                buffer = ByteArray(0)
                expected = packet[2].toInt() and 0xFF
                return
            }
            if (expected < 0) return

            // Trust the declared length, not the array length: packets are zero-padded to 20 bytes
            // and treating the padding as data appends nulls to the frame.
            val length = packet[1].toInt() and 0xFF
            if (length > packet.size - 2) return
            buffer += packet.copyOfRange(2, 2 + length)

            val terminated = packet[0] == LAST_MARKER
            // The length from the lead is a second terminator so a lost 0xFF cannot strand a frame
            // that has, in fact, fully arrived.
            if (!terminated && buffer.size < expected) return

            val assembled = if (buffer.size > expected) buffer.copyOfRange(0, expected) else buffer
            reset()
            onFrame(stripFitPro2Envelope(assembled))
        }
    }

    /**
     * Remove the FitPro2 envelope from a reply, or return it unchanged if it has none.
     *
     * Unchanged rather than truncated: a console that answers without the envelope would otherwise
     * lose its first four bytes — address, length and command — and parse as garbage.
     */
    fun stripFitPro2Envelope(frame: ByteArray): ByteArray {
        if (frame.size < 5) return frame
        val enveloped = frame[0] == 0x02.toByte() &&
            frame[1] == 0x04.toByte() &&
            frame[2] == 0x02.toByte()
        if (!enveloped) return frame
        val declared = frame[3].toInt() and 0xFF
        if (declared == 0 || 4 + declared > frame.size) return frame
        return frame.copyOfRange(4, 4 + declared)
    }

    private const val LEAD_MARKER = 0xFE.toByte()
    private const val LEAD_KIND = 0x02.toByte()
    private const val LAST_MARKER = 0xFF.toByte()

    // ---- response parsing -------------------------------------------------------------------------

    /** A parsed machine reply. */
    data class Response(
        val address: Int,
        val status: Status,
        /** Decoded read values, keyed by the register that was requested. */
        val values: Map<Register, ByteArray>,
        /**
         * Whether the trailing checksum matched.
         *
         * Reported rather than enforced. GlassOS does not verify it (`th/c.a` reads the header and
         * hands the rest on), so requiring it would risk refusing replies that a working console
         * accepts — but it is real evidence when corroborating a link, so it is not discarded either.
         */
        val checksumValid: Boolean,
    ) {
        /** Whether the machine acted on the request. */
        val accepted: Boolean get() = status == Status.DONE

        /** The raw bytes returned for [register], or null if it wasn't asked for or wasn't returned. */
        fun value(register: Register): ByteArray? = values[register]
    }

    /**
     * Parses a reply to a request that asked for [reads]. VERIFIED (`th/c.a`, `vh/f.a`, `vh/f.l`).
     *
     * Read values are packed contiguously from [RESPONSE_VALUE_OFFSET], in ascending field-id
     * order, each exactly [Register.width] bytes — `vh/f.l` slices
     * `copyOfRange(response, cursor, cursor + serializer.e())` and returns the advanced cursor,
     * which is where a wrong width would silently corrupt every subsequent value.
     *
     * Returns null when the reply is too short to be a frame or too short to hold the values it
     * claims. A truncated reply is a broken link, and inventing values for the missing bytes would
     * turn that into plausible telemetry.
     */
    fun parseResponse(
        bytes: ByteArray,
        reads: List<Register>,
        expectAddress: Int? = null,
        expectCommand: Command? = Command.READ_WRITE_DATA,
    ): Response? {
        // Five is the shortest legal reply: address, length, command, status, checksum.
        if (bytes.size < FRAME_OVERHEAD + 1) return null
        val declared = bytes[1].toInt() and 0xFF
        if (declared < FRAME_OVERHEAD + 1 || declared > bytes.size) return null

        // Reject a reply that is not the answer to the question we asked. FitPro carries no request
        // id, so the address and command bytes are the only correlation available, and without this
        // check a late reply to a previous frame — the one thing a shared wire guarantees will
        // happen eventually — is indistinguishable from an acknowledgement of this one.
        if (expectAddress != null && (bytes[0].toInt() and 0xFF) != (expectAddress and 0xFF)) return null
        if (expectCommand != null && Command.fromValue(bytes[2].toInt()) != expectCommand) return null

        val status = Status.fromValue(bytes[3].toInt() and 0xFF)
        val checksumValid = bytes[declared - 1] == checksum(bytes, declared - 1)

        if (status != Status.DONE) {
            return Response(bytes[0].toInt() and 0xFF, status, emptyMap(), checksumValid)
        }

        val values = LinkedHashMap<Register, ByteArray>(reads.size)
        var cursor = RESPONSE_VALUE_OFFSET
        // Values live strictly between the header and the checksum byte at `declared - 1`. Bounding
        // by `bytes.size` instead would let a short reply be "parsed" out of the checksum, or out of
        // whatever padding the transport appended — a decode that silently returns a plausible
        // wrong number, which on a speed register is the worst failure this file can have.
        val end = declared - 1
        for (register in reads.distinct().sortedBy { it.fieldId }) {
            if (cursor + register.width > end) return null
            values[register] = bytes.copyOfRange(cursor, cursor + register.width)
            cursor += register.width
        }
        // The values must fill the frame exactly. Leftover bytes mean this reply carries a different
        // set of registers than we asked for — an older telemetry poll overtaking a write, say — and
        // every value we just decoded is then a guess about which field it came from.
        if (cursor != end) return null
        return Response(bytes[0].toInt() and 0xFF, status, values, checksumValid)
    }

    // ---- handshake --------------------------------------------------------------------------------

    /**
     * A frame for a command that carries no body — every handshake command except the register
     * read/write. VERIFIED (`vh/d.e` with `b() == 0`, e.g. `vh/e`, `vh/j`, `vh/g`).
     */
    fun commandFrame(command: Command, address: Int): ByteArray =
        frame(body = ByteArray(0), address = address, command = command)

    /** The machine's brand, from the `DEVICE_INFO` reply. VERIFIED (`hj/s.java`). */
    enum class Brand(val value: Int) {
        NONE(0),
        ICON(1),
        FREE_MOTION(2),
        PRO_FORM(3),
        NORDIC_TRACK(4),
        WEIDER(5),
        HEALTH_RIDER(6),
        REEBOK(7),
        WORKOUT_WAREHOUSE(8),
        WESLO(9),
        UTS(10),
        GOLDS_GYM(12),
        IFIT(13),
        ALTRA(14),
        SEARS(15),
        ;

        companion object {
            fun fromValue(value: Int): Brand = entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    /**
     * What the machine says about itself, and — the part that matters — which registers it actually
     * implements.
     *
     * [supportedFieldIds] is the machine's own answer to "will incline work on this treadmill", sent
     * as a bitmask in exactly the format [registerBlock] builds. Before this was decoded, Stride had
     * no way to answer that question except by trying; now it can be asked.
     */
    data class DeviceInfo(
        /** The address to use for every subsequent frame. */
        val address: Int,
        val softwareVersion: Int,
        val hardwareVersion: Int,
        val serialNumber: Int,
        val brand: Brand,
        val supportedFieldIds: Set<Int>,
    ) {
        /** The subset of registers this codec models that the machine also implements. */
        val supportedRegisters: Set<Register>
            get() = Register.entries.filter { it.fieldId in supportedFieldIds }.toSet()

        fun supports(register: Register): Boolean = register.fieldId in supportedFieldIds

        /**
         * Whether this console would demand `VERIFY_SECURITY` before honouring writes. VERIFIED
         * (`xh/n0.smali` ~line 610: `if-le softwareVersion, 0x4b` skips the security call).
         *
         * Stride cannot satisfy that exchange, so this is not a gate we can pass — it is a flag that
         * tells us *why* a machine might accept the handshake and then refuse every write, which is
         * otherwise an almost undiagnosable symptom.
         */
        val requiresSecurity: Boolean get() = softwareVersion > SECURITY_REQUIRED_ABOVE
    }

    /**
     * Parses a `DEVICE_INFO` reply. VERIFIED (`vh/e.a`, case 0, against the field names that
     * `yh/b.toString()` spells out).
     *
     * Layout after the 4-byte header: software version, hardware version, a 4-byte little-endian
     * serial number, a 2-byte little-endian manufacturer, a mask-byte count, then that many mask
     * bytes whose set bits are supported field ids.
     *
     * The first two bytes were previously labelled the other way round here, and byte 6 was called a
     * model number. `yh/b`'s constructor is `(device, softwareVersion, hardwareVersion, serialNumber,
     * manufacturer, sections, …)` and `vh/e` fills it in exactly that order, so the names above are
     * the machine's, not a guess. Only the mask is load-bearing, but the software version decides
     * whether a console demands security (see [DeviceInfo.requiresSecurity]) — so having these two
     * swapped would have made that test read the wrong byte.
     *
     * Note this reads the multi-byte fields as unsigned where iFit's own decoder sign-extends them
     * (`bArr[6] + (bArr[7] << 8)` on Java's signed bytes). Those fields are informational, so being
     * correct costs nothing; the mask, which is not informational, is byte-for-byte identical.
     */
    fun parseDeviceInfo(bytes: ByteArray): DeviceInfo? {
        if (bytes.size < 13) return null
        val maskCount = bytes[12].toInt() and 0xFF
        if (bytes.size < 13 + maskCount) return null

        val fields = HashSet<Int>()
        for (i in 0 until maskCount) {
            val maskByte = bytes[13 + i].toInt() and 0xFF
            for (bit in 0 until 8) {
                if (maskByte and (1 shl bit) != 0) fields.add(i * 8 + bit)
            }
        }
        return DeviceInfo(
            address = bytes[0].toInt() and 0xFF,
            softwareVersion = bytes[4].toInt() and 0xFF,
            hardwareVersion = bytes[5].toInt() and 0xFF,
            serialNumber = leToInt(bytes.copyOfRange(6, 10), 4),
            brand = Brand.fromValue(leToInt(bytes.copyOfRange(10, 12), 2)),
            supportedFieldIds = fields,
        )
    }

    /**
     * Parses a `SUPPORTED_COMMANDS` reply: one byte per command from [RESPONSE_VALUE_OFFSET] up to
     * the checksum. VERIFIED (`vh/e.a`, case 1).
     *
     * Unrecognised bytes are dropped rather than failing the parse — a machine advertising a command
     * this codec has no name for is not an error, it is a machine with more features than we use.
     */
    fun parseSupportedCommands(bytes: ByteArray): Set<Command> {
        if (bytes.size < FRAME_OVERHEAD) return emptySet()
        val declared = (bytes[1].toInt() and 0xFF).coerceAtMost(bytes.size)
        val end = declared - 1
        val result = LinkedHashSet<Command>()
        for (i in RESPONSE_VALUE_OFFSET until end) {
            Command.fromValue(bytes[i].toInt())?.let(result::add)
        }
        return result
    }

    /**
     * Parses a `SUPPORTED_DEVICES` reply: a count at offset 4, then that many device addresses.
     * VERIFIED (`vh/e.a`, case 2).
     */
    fun parseSupportedDevices(bytes: ByteArray): List<Int> {
        if (bytes.size < RESPONSE_VALUE_OFFSET + 1) return emptyList()
        val count = bytes[RESPONSE_VALUE_OFFSET].toInt() and 0xFF
        if (bytes.size < RESPONSE_VALUE_OFFSET + 1 + count) return emptyList()
        return (0 until count).map { bytes[RESPONSE_VALUE_OFFSET + 1 + it].toInt() and 0xFF }
    }

    /** The status byte of any reply, or null if it is too short to have one. */
    fun statusOf(bytes: ByteArray): Status? =
        if (bytes.size < FRAME_OVERHEAD) null else Status.fromValue(bytes[3].toInt() and 0xFF)
}
