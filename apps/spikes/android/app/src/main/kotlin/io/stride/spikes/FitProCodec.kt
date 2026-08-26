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
     * The longest frame this protocol allows, header and checksum included.
     *
     * GlassOS rejects any reply whose length byte exceeds this outright, naming it `maxMsgLength`
     * (`ai/b.a`: *"Second byte is N, which exceeds maxMsgLength (64)"*). It is also exactly the size
     * of the buffer the USB path reads into (`rj/p.f14325l = new byte[64]`), so a longer declared
     * length cannot be honest.
     */
    const val MAX_FRAME_LENGTH: Int = 64

    /**
     * The software version above which a console demands `VERIFY_SECURITY` before it will accept
     * writes. VERIFIED (`xh/n0.smali`: `const/16 v13, 0x4b` then `if-le … :cond_c`, skipping the
     * security branch for anything at or below it).
     */
    const val SECURITY_REQUIRED_ABOVE: Int = 75

    /** Length of the security hash `VERIFY_SECURITY` carries. VERIFIED (`EquipmentUtil.CalculateSecurityHash`). */
    const val SECURITY_HASH_LENGTH: Int = 32

    /**
     * The constant the master library version is multiplied by to make the secret key. VERIFIED
     * (`VerifySecurityCmd`: `secretKey = 8 * masterLibraryVersion`, where 8 is the class's
     * `MinorVersion` constant).
     */
    const val SECURITY_KEY_MULTIPLIER: Int = 8

    /** `VERIFY_SECURITY`'s body: the 32-byte hash then the 4-byte key. VERIFIED (`ContentLength => 36`). */
    const val SECURITY_CONTENT_LENGTH: Int = SECURITY_HASH_LENGTH + 4

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

        /**
         * How many bytes of body this command's request carries.
         *
         * Not every handshake command is bodiless, which this codec previously assumed. FitPro1
         * declares a `ContentLength` per command and `SYSTEM_INFO` and `VERSION_INFO` both declare
         * **2** — a pair of flag bytes asking whether to also return the MCU name and the console
         * name (`SystemInfoCmd`/`VersionInfoCmd.RequestContentBytes`). Everything else in our
         * handshake genuinely is zero: `DEVICE_INFO`, `SERIAL_NUMBER`, `SUPPORTED_COMMANDS` and
         * `SUPPORTED_DEVICES` all declare 0.
         *
         * This is not cosmetic. A console reading a declared length that disagrees with the bytes
         * that follow does not answer. In the field report that prompted this, `DEVICE_INFO`
         * (length 0, which we got right) succeeded and `SYSTEM_INFO` — the first command we
         * under-filled — got nothing back, as did everything after it.
         *
         * The silence of the *later*, correctly-framed commands is not explained by the malformed
         * frame alone: this link is a USB HID interrupt endpoint, where each report is its own
         * framed transfer, so there is no shared byte stream for a bad length to desync. The likely
         * explanation is that the malformed frame wedges the console's command processor until the
         * link is re-established, but that mechanism is inferred, not confirmed. What is confirmed
         * is that iFit sends two bytes here and we sent none.
         *
         * Zero-filled is the right content: both flags mean "no, don't also send me the name".
         */
        val requestContentLength: Int
            get() = when (this) {
                // Three, not two, and the difference is measured rather than reasoned about.
                // GlassOS's own SYSTEM_INFO command object declares ContentLength 3 and returns
                // `{0, 0, 0}` as its body (`vh/e.java`: the no-arg constructor sets `f16662i = 3`,
                // and `g()` answers `new byte[]{0, 0, 0}` for that case). VERSION_INFO alongside it
                // really is two — `vh/j.java` declares 2 and sends `{false, false}` — so this was
                // right about one command and wrong about the other.
                //
                // The consequence of getting it wrong is a frame whose length byte disagrees with
                // its own contents, which is the class of error a console answers by ignoring.
                SYSTEM_INFO -> 3
                VERSION_INFO -> 2
                VERIFY_SECURITY -> SECURITY_CONTENT_LENGTH
                else -> 0
            }

        /**
         * How long to wait after writing this command before reading its reply, in milliseconds.
         *
         * iFit does not read straight away: every command carries its own `ReadDelayMs` and the
         * connection is driven through `SendBytesWithReadDelay`, which waits before the bulk read
         * (`RetryingConnection`). The figures are the vendor's, per command, and they differ by a
         * factor of five — telemetry is polled far more often than the console is interrogated, and
         * is given correspondingly less slack.
         */
        val readDelayMs: Long
            get() = when (this) {
                READ_WRITE_DATA -> 80L
                DEVICE_INFO, SYSTEM_INFO, SUPPORTED_COMMANDS, SUPPORTED_DEVICES -> 300L
                else -> 400L
            }

        /**
         * How long the console may take to answer this command, in milliseconds, before it counts
         * as absent.
         *
         * A **base plus a per-command allowance**, which is how iFit composes it: the transport
         * contributes 1 s on USB and 2 s on BLE (`FitProCommunication.Timeout`), and the command's
         * own `ResponseTimeoutMs` is added on top as `AdditionalDelay`. So a `ReadWriteData` on USB
         * gets 2 s, not 1 s, and a `SerialNumber` gets 3.5 s.
         *
         * This matters more than it looks. Stride used a flat 400 ms for everything, which is
         * shorter than the vendor's *read delay* for most of these commands, never mind its
         * deadline — a console answering exactly as designed was being written off as not there.
         */
        fun timeoutMs(onBle: Boolean): Long {
            val base = if (onBle) 2_000L else 1_000L
            val allowance = when (this) {
                READ_WRITE_DATA -> 1_000L
                DEVICE_INFO, SYSTEM_INFO, SUPPORTED_COMMANDS, SUPPORTED_DEVICES -> 1_000L
                else -> 2_500L
            }
            return base + allowance
        }

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

        /**
         * Field 95 in FitPro1's own `BitField` enum (`Sindarin.FitPro1.Bits.BitField`, decompiled
         * from `ifit-standalone.apk`'s Xamarin assemblies). GlassOS/FitPro2 has no binding for it —
         * this device never saw it before this investigation. `FitPro1Console.InitializeConsole`
         * writes it right after unlock, from `!IsBitFieldSupported(RequireStartRequested) ||
         * !IsBeltBasedMachine()`, in its own `ReadWriteDataCmd` after [REQUIRE_START_REQUESTED]'s.
         *
         * `DeviceExtensions.IsBeltBasedMachine` is true for `Treadmill` and `InclineTrainer` alike,
         * taken from the primary device in `DeviceInfoCmd` rather than from a capability bit — see
         * [DirectMachineSession.BELT_BASED_MACHINE] for why Stride assumes it instead of reading it.
         *
         * Worth noting the contrast: FitPro2's own initialization sets the idle lockout and nothing
         * else, which is why [REQUIRE_START_REQUESTED] has no GlassOS equivalent at all.
         */
        IDLE_MODE_LOCKOUT(95, 1, readOnly = false),
        START_REQUESTED(96, 1, readOnly = true),

        /**
         * Field 108, same source as [IDLE_MODE_LOCKOUT], and FitPro1-only.
         * `FitPro1Console.InitializeConsole` writes it (echoing whatever the console's own
         * supported-fields bitmap already says about it) immediately after unlock and before
         * anything else that touches workout state:
         * `SetRequireStartRequested(IsBitFieldSupported(RequireStartRequested))`.
         *
         * This is why `WORKOUT_MODE = RUNNING` was refused with a clean `FAILED` on the X22i even
         * after every other precondition (unlock, supported-field checks) held: the console had
         * never been told to leave whatever state it starts in. Observed once on real hardware —
         * the belt ran for about two minutes — against an earlier revision that batched this write
         * with field 95. Stride now sends the two separately and in iFit's order, so that single
         * observation does not describe the current sequence; see
         * [DirectMachineSession.initializeStartGate] and `DIRECT_MACHINE_PROTOCOL.md`.
         */
        REQUIRE_START_REQUESTED(108, 1, readOnly = false),
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

    /**
     * Decodes `CURRENT_CALORIES`: a raw 4-byte count scaled by `1024 / 100,000,000`, not a plain
     * integer. VERIFIED against iFit's own `CaloriesConverter` (decompiled from
     * `ifit-standalone.apk`): `(double)(uint)raw * 1024.0 / 100000000.0`.
     *
     * This register was previously read with the generic [decodeInt], which is right for most
     * 4-byte fields and wrong here specifically. The raw value genuinely does climb by a few
     * thousand per second while a workout runs — that is correct, granular firmware behaviour —
     * and reading it as whole calories rather than applying this scale is exactly what produced
     * calorie counts in the millions.
     */
    fun decodeCalories(bytes: ByteArray): Double = decodeInt(bytes) * 1024.0 / 100_000_000.0

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
     * Which generation of the FitPro protocol a console speaks.
     *
     * The two share this codec's frame (`[device][length][command][body][checksum]`), the bitfield
     * register model and the value converters — verified line for line against iFit's own
     * `Sindarin.FitPro1.Core` and `Sindarin.FitPro2.Core`. What they do **not** share is how that
     * frame reaches the wire, and the difference is total rather than cosmetic: a console spoken to
     * in the wrong generation's framing answers nothing at all.
     *
     * The console declares which it is in its USB product id, so this is read from the hardware
     * rather than guessed.
     */
    enum class Variant(val usbProductId: Int) {
        /**
         * The pre-GlassOS consoles — a NordicTrack X22i and its siblings, running
         * `com.ifit.launcher` into `com.ifit.standalone`.
         *
         * Verified line for line against `Sindarin.FitPro1.Core`: this codec's frame, its register
         * ids and its converters are that assembly's.
         */
        FITPRO1(2),

        /**
         * The GlassOS-era consoles, such as the Commercial 1750.
         *
         * ## A naming caution, because two different things are called "FitPro2"
         *
         * This codec was recovered from **GlassOS 6.14.6**, the console software on a 1750, and it
         * is a register protocol: `[device][length][command][bitfield masks][values][checksum]`.
         * iFit's own `Sindarin.FitPro2.Core` assembly is a *different* protocol entirely —
         * `[communicationType][device|command][payloadLength][payload]`, no checksum, `FeatureId`
         * lookups and four-byte floats rather than bitfield masks and hundredths.
         *
         * They are not the same wire and must not be conflated. What reconciles them is that they
         * describe different links: this codec is the console talking **down to its motor board**,
         * which is the same register protocol on both generations — which is precisely why the
         * register ids recovered from GlassOS match `Sindarin.FitPro1.Core`'s `BitField` enum
         * exactly. Sindarin's FitPro2 is an app-to-console link, one layer up.
         *
         * Practically: this value selects the same register codec, and that is deliberate, but it
         * has never been confirmed against a product-3 board on real hardware. A 1750 is defaulted
         * to GlassOS for exactly that reason and only reaches here if a rider opts in.
         */
        FITPRO2(3),
        ;

        companion object {
            /**
             * The variant a USB product id names, or null for a device this codec does not describe.
             *
             * Null rather than a default: writing register frames at an unrecognised peripheral is
             * exactly what the vendor lock exists to prevent, and "assume the newer one" would do it.
             */
            fun fromUsbProductId(productId: Int): Variant? =
                entries.firstOrNull { it.usbProductId == productId }
        }
    }

    /**
     * Wraps a frame in the envelope `[0x02, 0x04, 0x02, frameLength]`. VERIFIED (`th/q.java`, and
     * `Sindarin.FitPro1.Communication.FitProCommunication`'s raw-bytes constructor).
     *
     * **BLE only.** FitPro1 builds every request with this prefix and then strips it again the
     * moment the link turns out not to be BLE — `Format`'s setter calls `RemoveBleBytes`, which
     * drops exactly these four bytes. So a serial console is written the bare frame and a BLE
     * console is written the enveloped one, which is the rule [FitProTransport] follows.
     */
    fun fitPro2Envelope(frame: ByteArray): ByteArray {
        require(frame.size <= 0xFF) { "FitPro2 length byte cannot exceed 255, got ${frame.size}" }
        return byteArrayOf(0x02, 0x04, 0x02, (frame.size and 0xFF).toByte()) + frame
    }

    /**
     * Splits [payload] into packets: a lead `[0xFE, 0x02, len, chunkCount]` then 20-byte data
     * packets `[index, dataLength, …up to 18 bytes]`, the last of which is indexed `0xFF`.
     * VERIFIED (`th/o.java`, and byte-for-byte against
     * `FitProCommunicationGroup.CreateMessages` / `CreateInitMessage`).
     *
     * `chunkCount` counts the lead packet as well as the data packets, which is why the source adds
     * one. Data packets are padded to a full 20 bytes.
     *
     * Named for the framing rather than for BLE, which is what it used to be called, because the
     * framing itself is not BLE-specific — it is what `FitProCommunicationGroup.CreateMessages`
     * produces for any caller. In practice only the BLE adapter uses it: FitPro1's USB adapter
     * overrides `SendBytes` to write `OriginalBytes`, the unchunked frame, and FitPro2 never chunks
     * at all. Worth stating because reading only the shared `CommAdapter.SendBytes` gives the
     * opposite impression.
     */
    fun chunkMessages(payload: ByteArray): List<ByteArray> {
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
     * Rebuilds a frame from the console's notification fragments — the inverse of [chunkMessages].
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
    class MessageReassembler(private val onFrame: (ByteArray) -> Unit) {

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

        // Reject a reply that did not come from the device this session is talking to. This is peer
        // validation, not request/reply correlation, and the difference is worth stating because the
        // stronger claim was written here once: FitPro carries no request id, and two answers from
        // the *same* console to two successive READ_WRITE_DATA frames carry an identical address
        // byte and an identical command byte — so a late reply to an earlier frame is exactly what
        // these bytes cannot separate. On a console that stamps every reply with its own address
        // (see DirectMachineSession.replyAddress) that is every frame it sends. What this does buy
        // is that another device on the same wire cannot answer for the console we handshook with,
        // which is what licenses the write in DirectMachineSession.authorisedWrite.
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
     * A frame for a handshake command, carrying the zero-filled body its
     * [Command.requestContentLength] declares.
     *
     * The body used to be unconditionally empty, which is right for most of these commands and
     * wrong for `SYSTEM_INFO` and `VERSION_INFO` — see [Command.requestContentLength].
     *
     * `VERIFY_SECURITY` is refused rather than zero-filled: a hash of 32 zero bytes is a
     * well-formed frame carrying the wrong answer, so the console would reject the handshake
     * instead of rejecting the frame, and the failure would look like a machine that refuses to
     * unlock rather than like a caller that forgot to compute the hash.
     */
    fun commandFrame(command: Command, address: Int): ByteArray {
        require(command != Command.VERIFY_SECURITY) {
            "VERIFY_SECURITY carries a computed hash; build it with verifySecurityFrame"
        }
        return frame(
            body = ByteArray(command.requestContentLength),
            address = address,
            command = command,
        )
    }

    /**
     * The 32-byte security hash a console above [SECURITY_REQUIRED_ABOVE] demands before it will
     * accept writes. VERIFIED — transcribed statement for statement from
     * `EquipmentUtil.CalculateSecurityHash(serialNumber, partNumber, modelNumber)`.
     *
     * Each byte starts as its own one-based index, then the corresponding bit of the serial number
     * chooses how it is mixed: a set bit folds in the part number, a clear bit folds in the seed
     * scaled by the model number. The part number is pre-rotated by 16 for the low half and used
     * as-is for the high half, so that both halves of a 32-bit part number reach the output.
     *
     * Three details are load-bearing and easy to get wrong:
     *  - the shifts are **arithmetic**, as C#'s `>>` on `int` is, so a part number with its top bit
     *    set sign-extends. Kotlin's `shr` matches; `ushr` would not.
     *  - the multiply in the clear-bit branch uses the *seed*, `b + 1`, not a running value.
     *  - the multiply is allowed to overflow. Both languages wrap, and the result is truncated to
     *    a byte anyway.
     */
    fun calculateSecurityHash(serialNumber: Int, partNumber: Int, modelNumber: Int): ByteArray {
        val out = ByteArray(SECURITY_HASH_LENGTH)
        for (index in 0 until SECURITY_HASH_LENGTH) {
            val seed = index + 1
            val mixed = if ((serialNumber shr index) and 1 == 1) {
                if (index < 16) ((partNumber shl 16) or (partNumber shr 16)) shr index
                else partNumber shr index
            } else {
                seed * (index + modelNumber)
            }
            out[index] = (seed xor mixed).toByte()
        }
        return out
    }

    /**
     * Builds the `VERIFY_SECURITY` frame: the 32-byte hash followed by the secret key, which is
     * [SECURITY_KEY_MULTIPLIER] times the console's own master library version, little-endian.
     * VERIFIED (`VerifySecurityCmd.RequestContentBytes`; `BinaryWriter.Write(int)` is little-endian).
     *
     * The console is being asked to confirm a number derived from facts it already told us about
     * itself, which is why every input here comes from a preceding handshake reply and none of it
     * can be guessed.
     */
    fun verifySecurityFrame(address: Int, securityHash: ByteArray, masterLibraryVersion: Int): ByteArray {
        require(securityHash.size == SECURITY_HASH_LENGTH) {
            "security hash must be $SECURITY_HASH_LENGTH bytes, got ${securityHash.size}"
        }
        val body = ByteArray(SECURITY_CONTENT_LENGTH)
        securityHash.copyInto(body, 0)
        intToLe(SECURITY_KEY_MULTIPLIER * masterLibraryVersion, 4).copyInto(body, SECURITY_HASH_LENGTH)
        return frame(body = body, address = address, command = Command.VERIFY_SECURITY)
    }

    /** What a console answered to `VERIFY_SECURITY`. */
    data class SecurityInfo(val unlocked: Boolean, val unlockedKey: Int, val status: Status?)

    /**
     * Parses a `VERIFY_SECURITY` reply. VERIFIED (`VerifySecurityCmd.SetResponseBytes`: skip the
     * 4-byte header, read one key byte; unlocked is `Status == Done`).
     */
    fun parseSecurityInfo(bytes: ByteArray): SecurityInfo? {
        if (bytes.size <= RESPONSE_VALUE_OFFSET) return null
        val status = statusOf(bytes)
        return SecurityInfo(
            unlocked = status == Status.DONE,
            unlockedKey = bytes[RESPONSE_VALUE_OFFSET].toInt() and 0xFF,
            status = status,
        )
    }

    /**
     * The part of a `SYSTEM_INFO` reply the security hash needs. VERIFIED
     * (`SystemInfoCmd.SetResponseBytes`).
     *
     * Layout after the 4-byte header: a 2-byte config size, a configuration byte, then the model
     * and part numbers as 4-byte little-endian values in that order. The reply carries more after
     * this — CPU use, task counts, timings — that nothing here needs.
     */
    data class SystemInfo(val model: Int, val partNumber: Int)

    fun parseSystemInfo(bytes: ByteArray): SystemInfo? {
        if (bytes.size < 15) return null
        val model = leToInt(bytes.copyOfRange(7, 11), 4)
        val partNumber = leToInt(bytes.copyOfRange(11, 15), 4)
        return SystemInfo(
            model = model,
            // One console reports a part number its own firmware then corrects. VERIFIED
            // (`SystemInfoCmd.SetResponseBytes`, last statement before the return). This looks like
            // a fudge worth skipping until you notice *where* it sits: iFit applies it while
            // parsing, so `Unlock()` reads the corrected value and hashes it. Leaving it out would
            // hash the raw number on that one model and get a SECURITY_BLOCK indistinguishable
            // from a genuine rejection.
            partNumber = if (partNumber == 370357 && model == 39915) 374677 else partNumber,
        )
    }

    /**
     * The master library version from a `VERSION_INFO` reply — a **single byte** after the 4-byte
     * header, not the 2-byte build number that follows it. VERIFIED
     * (`VersionInfoCmd.SetResponseBytes`: `ReadByte()` then `ReadUInt16()`).
     */
    fun parseMasterLibraryVersion(bytes: ByteArray): Int? {
        if (bytes.size <= RESPONSE_VALUE_OFFSET) return null
        return bytes[RESPONSE_VALUE_OFFSET].toInt() and 0xFF
    }

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
        /**
         * The address byte this reply carried.
         *
         * Not necessarily the address to send to: [DirectMachineSession.handshake] overwrites this
         * with the address it *asked*, matching GlassOS, and keeps the reply's own address in
         * [DirectMachineSession.replyAddress] for validating what comes back. On a console that
         * stamps its replies with its own bus address the two differ, and treating this field as an
         * outgoing address would send every later frame to a device iFit never addresses.
         */
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
         * Whether this console demands `VERIFY_SECURITY` before honouring writes. VERIFIED twice
         * over: `xh/n0.smali` ~line 610 skips the security call for `softwareVersion <= 0x4b`, and
         * FitPro1's own `Connect` reads `if (PrimaryDevice.SoftwareVersion > 75) await Unlock()`.
         *
         * Stride now satisfies that exchange — see [DirectMachineSession.unlock]. This used to say
         * it could not, which was true only for as long as the hash algorithm was unknown.
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
        // Checked before a single field is read, because this is the reply the session learns an
        // address *from*: everything downstream validates its replies against what this one said,
        // so a frame accepted here on nothing but its length decides who the peer is for the rest
        // of the session. [replyMatches] is the check GlassOS runs over every frame — command byte,
        // declared length, checksum, a non-zero address and the all-0xFF unwritten buffer. It costs
        // nothing on the live path, where [DirectMachineSession.handshake] has already run it.
        if (!replyMatches(bytes, Command.DEVICE_INFO)) return null
        val declared = bytes[1].toInt() and 0xFF
        if (declared < 13) return null
        val maskCount = bytes[12].toInt() and 0xFF
        // Bounded by the frame the console declared, not by the array: a reply is zero-padded to the
        // transport's packet size, so measuring against `bytes.size` lets a console that
        // under-declares have its own checksum byte and that padding decoded as supported-field
        // bits — inventing registers the machine never claimed, which is how a rider gets an incline
        // control that writes into nothing.
        if (13 + maskCount > declared - 1) return null

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

    /**
     * Whether [reply] is a well-formed answer to [command] — right shape, right command byte, sound
     * checksum, and from *some* device rather than from an unwritten buffer.
     *
     * Note what it does not check: which device. The address byte is only required to be non-zero,
     * for the reason spelled out below. Deciding that a reply came from the console this session
     * handshook with is [parseResponse]'s `expectAddress` and [DirectMachineSession.replyAddress].
     *
     * Transcribed from `ai/b.a`, which GlassOS runs over **every** frame before anything reads a
     * status out of it. Stride was reading the status straight off byte 3 with no such check, and on
     * a shared, packetised pipe that is how one command's answer gets attributed to another: a stale
     * telemetry frame, or a buffer the board never filled, decodes as a perfectly plausible refusal.
     * "The console says CMD_NOT_SUPPORTED" and "we read a byte that was never an answer" look
     * identical from here, and only this tells them apart.
     *
     * The all-`0xFF` buffer is rejected first and by name. GlassOS keeps a 64-byte `0xFF` sentinel
     * (`ai.b.f824b`, with index 3 left as a wildcard) and treats a matching read as *no device*
     * rather than as data — an unwritten USB buffer reads exactly like that.
     */
    fun replyMatches(reply: ByteArray, command: Command): Boolean {
        if (reply.size < FRAME_OVERHEAD) return false
        // Index 3 is deliberately skipped, as GlassOS's own sentinel does: the board may leave a
        // status in an otherwise untouched buffer.
        if (reply.indices.all { it == 3 || reply[it] == 0xFF.toByte() }) return false
        // Non-zero, and nothing stronger. Address 0 is NONE and cannot begin a frame, which is the
        // check `ai/b.a` makes — but it does **not** compare the reply's address to the one the
        // request was sent to, and neither may this. An X22i stamps every reply with its own bus
        // address (5) rather than the MAIN it was asked, so requiring an echo here would reject
        // every frame that console ever sends. Matching a reply to its request is the *command*
        // byte's job below; whether the sender is the expected device is `replyAddress`'s, once
        // DEVICE_INFO has established what that address actually is.
        if (reply[0].toInt() and 0xFF == 0) return false
        val declared = reply[1].toInt() and 0xFF
        if (declared < FRAME_OVERHEAD || declared > MAX_FRAME_LENGTH || declared > reply.size) {
            return false
        }
        if (reply[2] != command.value.toByte()) return false
        return reply[declared - 1] == checksum(reply, declared - 1)
    }
}
