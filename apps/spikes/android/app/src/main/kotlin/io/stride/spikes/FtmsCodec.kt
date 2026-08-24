package io.stride.spikes

/**
 * Pure codec for the Bluetooth SIG **Fitness Machine Service** (FTMS, `0x1826`).
 *
 * ## Why this exists next to a working GlassOS client
 *
 * GlassOS and the FitPro register path are both iFit-specific: they reach exactly the machines iFit
 * ships. FTMS is the one protocol a *standards body* defined, and it is what most non-iFit equipment
 * speaks. Measured against `qdomyos-zwift`'s device catalog, 128 of its 282 recognised device-name
 * patterns route to a single generic FTMS driver — one implementation covering more hardware than
 * the other hundred-odd drivers in that project combined. That ratio is the entire argument for
 * writing this before any proprietary driver.
 *
 * ## This file is a codec and nothing else
 *
 * No Android imports, no I/O, no connection state — the same rule [FitProCodec] lives under, for the
 * same reason: the field-offset arithmetic below is where FTMS parsers get things wrong, and it must
 * be testable without a treadmill or a Bluetooth radio in the room.
 *
 * ## The trap that breaks most FTMS parsers
 *
 * Treadmill Data (`0x2ACD`) has **no fixed layout**. A 16-bit flags word says which fields are
 * present, and every field's offset depends on which fields before it were included. Get one flag
 * wrong and every subsequent value is read from the wrong bytes — and it does not fail loudly, it
 * produces plausible numbers. Next to a treadmill, "plausible but wrong speed" is the dangerous
 * failure, not a crash.
 *
 * **Bit 0 is inverted relative to every other bit.** It is named "More Data", and Instantaneous
 * Speed is present when it is **clear**, not when it is set. Every other bit means "present when
 * set". This is the single most common FTMS bug and it is why [parseTreadmillData] spells the test
 * out rather than folding it into the general flag loop.
 *
 * Two more layout facts that are easy to miss, both pinned by tests:
 * - **Bit 3 carries two fields.** Inclination *and* Ramp Angle Setting, four bytes, not two.
 * - **Bit 7 carries three fields.** Total Energy, Energy Per Hour, Energy Per Minute — five bytes.
 *
 * And one that bites only if this file ever grows an elliptical: **Cross Trainer Data (`0x2ACE`)
 * has a 24-bit flags field**, not 16. Reusing [parseTreadmillData]'s two-byte read on it would put
 * every field one byte out. It is a different characteristic with a different header, not a variant.
 *
 * ## Cross-checked against qdomyos-zwift
 *
 * The layout above was written from the SIG specification, then verified field-for-field against
 * `qdomyos-zwift` — the reference implementation in this space. Its `0x2ACD` bitfield
 * (`horizontreadmill.cpp`) declares the same thirteen flags in the same order, reads the same
 * two-byte header, gates speed on `!moreData`, advances four bytes for inclination, four for
 * elevation and five for expended energy, and decodes inclination as `int16_t`. Its Control Point
 * op codes and result codes (`ftmsbike.h`) are numerically identical to the ones here.
 *
 * `FtmsCodecTest` additionally decodes the exact frame its `CharacteristicNotifier2ACD` publishes to
 * Zwift, in both of that function's flag layouts. That is the closest thing to a reference vector
 * available without hardware.
 *
 * ## Which revision this follows, and why
 *
 * **FTMS 1.0 (2017), as shipped in equipment — not the later GSS revision.** The two disagree on
 * two field widths, and the disagreement shifts every field after them:
 *
 * | Field | FTMS 1.0 (used here) | Later GSS |
 * |---|---|---|
 * | Treadmill instantaneous / average pace | `uint8` | `uint16` |
 * | Resistance level (bike, cross trainer, rower) | `sint16` | `uint8` |
 *
 * FTMS 1.0 is what `qdomyos-zwift` implements and it is validated against a large amount of real
 * hardware: its treadmill parser advances **one** byte per pace field, and its indoor bike parser
 * advances **two** for resistance. Following the newer document would put this codec one byte out
 * against every machine that project has ever been tested on. A spec revision does not re-flash
 * equipment that is already in people's houses, so the wire wins over the document.
 *
 * If a machine ever turns up that uses the newer widths, the discriminator is the packet length
 * implied by its flags — not a guess from the values, which decode plausibly either way.
 *
 * ## Units are the spec's, not the app's
 *
 * Everything here stays in the units FTMS defines — km/h, metres, percent. Conversion to the mph and
 * miles the UI shows happens one layer up, in [FtmsClient], exactly as [FitProValues] does it for the
 * register path. A codec that converted would make the wire values untestable against the spec.
 */
object FtmsCodec {

    // ------------------------------------------------------------------ UUIDs

    /** The 16-bit UUIDs of everything this driver touches, as SIG assigned numbers. */
    object Uuid {
        /** Fitness Machine Service. */
        const val SERVICE = 0x1826

        /** Fitness Machine Feature — what the machine can do and can be told to do. */
        const val FEATURE = 0x2ACC

        /** Treadmill Data — notified telemetry. */
        const val TREADMILL_DATA = 0x2ACD

        /** Cross Trainer (elliptical) Data. **24-bit flags field**, unlike every sibling. */
        const val CROSS_TRAINER_DATA = 0x2ACE

        /** Rower Data. */
        const val ROWER_DATA = 0x2AD1

        /** Indoor Bike Data. */
        const val INDOOR_BIKE_DATA = 0x2AD2

        /** Training Status. */
        const val TRAINING_STATUS = 0x2AD3

        /** Supported Speed Range. */
        const val SPEED_RANGE = 0x2AD4

        /** Supported Inclination Range. */
        const val INCLINATION_RANGE = 0x2AD5

        /** Fitness Machine Control Point — the only writable characteristic, and the one that moves a belt. */
        const val CONTROL_POINT = 0x2AD9

        /** Fitness Machine Status — unsolicited notifications about what the *machine* did. */
        const val STATUS = 0x2ADA
    }

    // ---------------------------------------------------------- control point

    /**
     * Control Point op codes.
     *
     * Only the ones Stride uses are named. An op code Stride cannot issue is one it cannot get
     * wrong, so the list is deliberately short rather than a transcription of the whole spec.
     */
    object OpCode {
        const val REQUEST_CONTROL = 0x00
        const val RESET = 0x01
        const val SET_TARGET_SPEED = 0x02
        const val SET_TARGET_INCLINATION = 0x03
        const val START_OR_RESUME = 0x07
        const val STOP_OR_PAUSE = 0x08

        /** Every Control Point response begins with this, then the op code it answers. */
        const val RESPONSE = 0x80
    }

    /** The parameter byte on [OpCode.STOP_OR_PAUSE]. The spec gives no default; one must be sent. */
    object StopParam {
        const val STOP = 0x01
        const val PAUSE = 0x02
    }

    /** Control Point result codes. [SUCCESS] is the only one that means the machine accepted. */
    object Result {
        const val SUCCESS = 0x01
        const val NOT_SUPPORTED = 0x02
        const val INVALID_PARAMETER = 0x03
        const val OPERATION_FAILED = 0x04
        const val CONTROL_NOT_PERMITTED = 0x05

        fun describe(code: Int): String = when (code) {
            SUCCESS -> "success"
            NOT_SUPPORTED -> "op code not supported"
            INVALID_PARAMETER -> "invalid parameter"
            OPERATION_FAILED -> "operation failed"
            CONTROL_NOT_PERMITTED -> "control not permitted"
            else -> "unknown result 0x${code.toString(16)}"
        }
    }

    /**
     * `RequestControl`. Must succeed before any setpoint is accepted.
     *
     * FTMS machines reject every write until a client has taken control, and they drop that control
     * on disconnect. This is not a handshake we can do once at startup and forget — see
     * [FtmsMachineCommands.connect].
     */
    fun encodeRequestControl(): ByteArray = byteArrayOf(OpCode.REQUEST_CONTROL.toByte())

    fun encodeReset(): ByteArray = byteArrayOf(OpCode.RESET.toByte())

    /**
     * `SetTargetSpeed`, in 0.01 km/h units.
     *
     * **No clamping happens here.** The value is put on the wire as given, because clamping in a
     * driver splits the safety rules across files and makes it ambiguous which one is authoritative.
     * `MachineCoordinator` owns the ceiling, the ramp limit and stop preemption; this function's only
     * job is to encode faithfully or fail loudly. See [MachineCommands] for the rule.
     *
     * @throws IllegalArgumentException if the value cannot be represented, which is a programming
     *   error rather than a value the machine would refuse — a silently truncated setpoint is a
     *   different speed than the one the rider asked for.
     */
    fun encodeSetTargetSpeed(kph: Double): ByteArray {
        // Checked before rounding, because Math.round(NaN) is 0 -- so a NaN speed would sail
        // through the range check below and be transmitted as a perfectly valid "0.00 km/h", a
        // command the caller never asked for and which the coordinator would be told succeeded.
        require(kph.isFinite()) { "speed must be a finite number, not $kph" }
        val raw = Math.round(kph * 100.0)
        require(raw in 0L..0xFFFFL) { "speed $kph km/h is outside the uint16 0.01 km/h range" }
        return byteArrayOf(OpCode.SET_TARGET_SPEED.toByte()) + uint16Le(raw.toInt())
    }

    /**
     * `SetTargetInclination`, in 0.1 percent units, signed.
     *
     * Signed because a decline is a negative grade and is carried in two's complement. Sign handling
     * is the easiest thing to get wrong here, so it is pinned by a negative-value test.
     */
    fun encodeSetTargetInclination(percent: Double): ByteArray {
        require(percent.isFinite()) { "incline must be a finite number, not $percent" }
        val raw = Math.round(percent * 10.0)
        require(raw in -0x8000L..0x7FFFL) { "incline $percent% is outside the sint16 0.1% range" }
        return byteArrayOf(OpCode.SET_TARGET_INCLINATION.toByte()) + sint16Le(raw.toInt())
    }

    fun encodeStartOrResume(): ByteArray = byteArrayOf(OpCode.START_OR_RESUME.toByte())

    /** `StopOrPause`. The parameter is mandatory; see [StopParam]. */
    fun encodeStop(): ByteArray =
        byteArrayOf(OpCode.STOP_OR_PAUSE.toByte(), StopParam.STOP.toByte())

    fun encodePause(): ByteArray =
        byteArrayOf(OpCode.STOP_OR_PAUSE.toByte(), StopParam.PAUSE.toByte())

    /** What the machine said about one Control Point write. */
    data class ControlResponse(
        /** The op code this answers, so a reply to a previous command is not read as this one's. */
        val requestOpCode: Int,
        val result: Int,
    ) {
        val success: Boolean get() = result == Result.SUCCESS
    }

    /**
     * Decode a Control Point indication, or null if it is not a well-formed response.
     *
     * Null means "we do not know what the machine said", which callers must not treat as refusal:
     * a command whose reply was lost may still have landed. [MachineAck.NoAnswer] exists for exactly
     * this distinction.
     */
    fun parseControlResponse(bytes: ByteArray?): ControlResponse? {
        if (bytes == null || bytes.size < 3) return null
        if (u8(bytes, 0) != OpCode.RESPONSE) return null
        return ControlResponse(requestOpCode = u8(bytes, 1), result = u8(bytes, 2))
    }

    // ----------------------------------------------------------- machine data

    /**
     * One sample from any FTMS machine-data characteristic, normalised.
     *
     * One type for all four characteristics rather than four look-alike types, because the consumer
     * — Stride's overlay — wants speed, distance, heart rate and calories regardless of whether they
     * came off a treadmill, a bike, an elliptical or a rower. Each parser fills the subset its
     * characteristic actually carries and leaves the rest null; a rower has no incline and a
     * treadmill has no stroke rate, and saying so with null is the honest encoding.
     *
     * Every field is nullable and null means **the machine did not send it**, never zero. That
     * distinction is load-bearing in this project: `MachineLink.NO_READING` exists because a
     * fabricated `0.0` next to a treadmill reads as "the belt is stopped", and the same rule that
     * governs the GlassOS parser governs this one.
     */
    data class MachineData(
        val speedKph: Double? = null,
        val averageSpeedKph: Double? = null,
        val totalDistanceMetres: Int? = null,
        val inclinePercent: Double? = null,
        val rampAngleDegrees: Double? = null,
        val positiveElevationGainMetres: Double? = null,
        val negativeElevationGainMetres: Double? = null,
        val instantaneousPaceKmPerMin: Double? = null,
        val averagePaceKmPerMin: Double? = null,
        val totalEnergyKcal: Int? = null,
        val energyPerHourKcal: Int? = null,
        val energyPerMinuteKcal: Int? = null,
        val heartRateBpm: Int? = null,
        val metabolicEquivalent: Double? = null,
        val elapsedSeconds: Int? = null,
        val remainingSeconds: Int? = null,
        val forceOnBeltNewtons: Int? = null,
        val powerWatts: Int? = null,
        val averagePowerWatts: Int? = null,
        /** Revolutions per minute. The wire carries half-RPM units; this is already halved. */
        val cadenceRpm: Double? = null,
        val averageCadenceRpm: Double? = null,
        val resistanceLevel: Int? = null,
        /** Rower strokes per minute. The wire carries half-stroke units; already halved. */
        val strokeRatePerMin: Double? = null,
        val strokeCount: Int? = null,
        /** Rower pace, seconds per 500 m. */
        val paceSecondsPer500m: Int? = null,
        val averageStrokeRatePerMin: Double? = null,
        val averagePaceSecondsPer500m: Int? = null,
        val stepsPerMinute: Int? = null,
        val averageStepRate: Int? = null,
        val strideCount: Int? = null,
    )

    /** Which characteristic a sample came from, and therefore which parser reads it. */
    enum class MachineType(val characteristic: Int) {
        TREADMILL(Uuid.TREADMILL_DATA),
        CROSS_TRAINER(Uuid.CROSS_TRAINER_DATA),
        ROWER(Uuid.ROWER_DATA),
        INDOOR_BIKE(Uuid.INDOOR_BIKE_DATA),
        ;

        /** What to call this in the diagnostics screen. */
        val label: String
            get() = when (this) {
                TREADMILL -> "treadmill"
                CROSS_TRAINER -> "cross trainer"
                ROWER -> "rower"
                INDOOR_BIKE -> "indoor bike"
            }
    }

    /**
     * Decode a notification from [type]'s characteristic.
     *
     * The one entry point the transport uses, so that "which parser for which characteristic" is
     * decided once here rather than at every call site.
     */
    fun parseMachineData(type: MachineType, bytes: ByteArray?): MachineData? = when (type) {
        MachineType.TREADMILL -> parseTreadmillData(bytes)
        MachineType.CROSS_TRAINER -> parseCrossTrainerData(bytes)
        MachineType.ROWER -> parseRowerData(bytes)
        MachineType.INDOOR_BIKE -> parseIndoorBikeData(bytes)
    }

    /**
     * A bounds-checked little-endian cursor over one notification.
     *
     * The four parsers below are structurally identical — read a flags word, then walk fields whose
     * offsets depend on which flags were set — and writing that walk out four times by hand is
     * exactly where an off-by-one hides. Every read advances the cursor by the width it consumed, so
     * a field can never be read at an offset the parser merely *believed* was right.
     *
     * Once a read runs past the end, [truncated] latches and every later read returns null. Callers
     * check it once at the end rather than after every field.
     */
    private class Reader(private val b: ByteArray, start: Int) {
        private var at = start

        var truncated = false
            private set

        private fun room(n: Int): Boolean {
            if (at + n > b.size) {
                truncated = true
                return false
            }
            return true
        }

        private fun byte(i: Int): Int = b[i].toInt() and 0xFF

        fun u8(): Int? {
            if (!room(1)) return null
            return byte(at).also { at += 1 }
        }

        fun u16(): Int? {
            if (!room(2)) return null
            return (byte(at) or (byte(at + 1) shl 8)).also { at += 2 }
        }

        fun s16(): Int? = u16()?.toShort()?.toInt()

        fun u24(): Int? {
            if (!room(3)) return null
            return (byte(at) or (byte(at + 1) shl 8) or (byte(at + 2) shl 16)).also { at += 3 }
        }
    }

    /**
     * "Data Not Available" for a uint16 energy field, per the spec.
     *
     * Distinguished from a real value because 65535 kcal rendered as a calorie count is nonsense the
     * rider would have to interpret, and because it is genuinely "unknown" — the same thing GlassOS
     * spells as `NaN`.
     */
    private const val ENERGY_NOT_AVAILABLE = 0xFFFF

    private const val ENERGY_PER_MINUTE_NOT_AVAILABLE = 0xFF

    /**
     * "Data Not Available" for a signed 16-bit field.
     *
     * Machines send this for an inclination, ramp angle or power they cannot measure. Taken at face
     * value it decodes to 3276.7% or 32767 W — numbers a rider would have to know to disbelieve, and
     * which `MachineCoordinator` would intersect into its limits as though the machine had declared
     * them.
     */
    private const val SINT16_NOT_AVAILABLE = 0x7FFF

    /** A signed 16-bit reading, or null when the machine said it has none. */
    private fun Int?.availableS16(): Int? = this?.takeIf { it != SINT16_NOT_AVAILABLE }

    /** The expended-energy triple every machine-data characteristic shares: total, /hour, /minute. */
    private fun MachineData.withEnergy(r: Reader): MachineData = copy(
        totalEnergyKcal = r.u16()?.takeIf { it != ENERGY_NOT_AVAILABLE },
        energyPerHourKcal = r.u16()?.takeIf { it != ENERGY_NOT_AVAILABLE },
        energyPerMinuteKcal = r.u8()?.takeIf { it != ENERGY_PER_MINUTE_NOT_AVAILABLE },
    )

    // ---- treadmill (0x2ACD) ----

    private object TreadmillFlag {
        /** Inverted: speed is present when this is **clear**. See the class note. */
        const val MORE_DATA = 1 shl 0
        const val AVERAGE_SPEED = 1 shl 1
        const val TOTAL_DISTANCE = 1 shl 2
        const val INCLINATION = 1 shl 3
        const val ELEVATION_GAIN = 1 shl 4
        const val INSTANTANEOUS_PACE = 1 shl 5
        const val AVERAGE_PACE = 1 shl 6
        const val EXPENDED_ENERGY = 1 shl 7
        const val HEART_RATE = 1 shl 8
        const val METABOLIC_EQUIVALENT = 1 shl 9
        const val ELAPSED_TIME = 1 shl 10
        const val REMAINING_TIME = 1 shl 11
        const val FORCE_ON_BELT = 1 shl 12
    }

    /**
     * Decode a Treadmill Data notification.
     *
     * Returns null when the payload is too short to hold even the flags, or when a declared field
     * runs past the end of the buffer. **A truncated packet is discarded whole rather than parsed up
     * to the break**: the fields already read are valid, but publishing a partial sample would let a
     * stale incline sit beside a fresh speed, and the pair would describe a machine that never
     * existed. One consistent sample or none.
     */
    fun parseTreadmillData(bytes: ByteArray?): MachineData? {
        if (bytes == null || bytes.size < 2) return null
        val flags = u16(bytes, 0)
        val r = Reader(bytes, 2)
        var out = MachineData()

        if (flags and TreadmillFlag.MORE_DATA == 0) {
            out = out.copy(speedKph = r.u16()?.div(100.0))
        }
        if (flags and TreadmillFlag.AVERAGE_SPEED != 0) {
            out = out.copy(averageSpeedKph = r.u16()?.div(100.0))
        }
        if (flags and TreadmillFlag.TOTAL_DISTANCE != 0) {
            // uint24. The only three-byte field in the characteristic, and the usual reason an
            // otherwise-correct parser drifts by one byte from here on.
            out = out.copy(totalDistanceMetres = r.u24())
        }
        if (flags and TreadmillFlag.INCLINATION != 0) {
            // One flag, two fields, four bytes.
            out = out.copy(
                inclinePercent = r.s16().availableS16()?.div(10.0),
                rampAngleDegrees = r.s16().availableS16()?.div(10.0),
            )
        }
        if (flags and TreadmillFlag.ELEVATION_GAIN != 0) {
            out = out.copy(
                positiveElevationGainMetres = r.u16()?.div(10.0),
                negativeElevationGainMetres = r.u16()?.div(10.0),
            )
        }
        if (flags and TreadmillFlag.INSTANTANEOUS_PACE != 0) {
            out = out.copy(instantaneousPaceKmPerMin = r.u8()?.div(10.0))
        }
        if (flags and TreadmillFlag.AVERAGE_PACE != 0) {
            out = out.copy(averagePaceKmPerMin = r.u8()?.div(10.0))
        }
        if (flags and TreadmillFlag.EXPENDED_ENERGY != 0) {
            // One flag, three fields, five bytes.
            out = out.withEnergy(r)
        }
        if (flags and TreadmillFlag.HEART_RATE != 0) {
            out = out.copy(heartRateBpm = r.u8())
        }
        if (flags and TreadmillFlag.METABOLIC_EQUIVALENT != 0) {
            out = out.copy(metabolicEquivalent = r.u8()?.div(10.0))
        }
        if (flags and TreadmillFlag.ELAPSED_TIME != 0) {
            out = out.copy(elapsedSeconds = r.u16())
        }
        if (flags and TreadmillFlag.REMAINING_TIME != 0) {
            out = out.copy(remainingSeconds = r.u16())
        }
        if (flags and TreadmillFlag.FORCE_ON_BELT != 0) {
            out = out.copy(
                forceOnBeltNewtons = r.s16().availableS16(),
                powerWatts = r.s16().availableS16(),
            )
        }

        return if (r.truncated) null else out
    }

    // ---- indoor bike (0x2AD2) ----

    private object BikeFlag {
        /** Inverted, exactly as on the treadmill. */
        const val MORE_DATA = 1 shl 0
        const val AVERAGE_SPEED = 1 shl 1
        const val INSTANTANEOUS_CADENCE = 1 shl 2
        const val AVERAGE_CADENCE = 1 shl 3
        const val TOTAL_DISTANCE = 1 shl 4
        const val RESISTANCE_LEVEL = 1 shl 5
        const val INSTANTANEOUS_POWER = 1 shl 6
        const val AVERAGE_POWER = 1 shl 7
        const val EXPENDED_ENERGY = 1 shl 8
        const val HEART_RATE = 1 shl 9
        const val METABOLIC_EQUIVALENT = 1 shl 10
        const val ELAPSED_TIME = 1 shl 11
        const val REMAINING_TIME = 1 shl 12
    }

    /**
     * Decode an Indoor Bike Data notification.
     *
     * The flag *positions* differ from the treadmill's even where the field names match — cadence
     * occupies bits 2 and 3 here, which on a treadmill are inclination and elevation. Sharing one
     * flag table between the two would decode a cadence as an incline, so the tables are separate on
     * purpose rather than by omission.
     *
     * Cadence is carried in half-RPM units and is halved here, so callers never have to remember.
     */
    fun parseIndoorBikeData(bytes: ByteArray?): MachineData? {
        if (bytes == null || bytes.size < 2) return null
        val flags = u16(bytes, 0)
        val r = Reader(bytes, 2)
        var out = MachineData()

        if (flags and BikeFlag.MORE_DATA == 0) {
            out = out.copy(speedKph = r.u16()?.div(100.0))
        }
        if (flags and BikeFlag.AVERAGE_SPEED != 0) {
            out = out.copy(averageSpeedKph = r.u16()?.div(100.0))
        }
        if (flags and BikeFlag.INSTANTANEOUS_CADENCE != 0) {
            out = out.copy(cadenceRpm = r.u16()?.div(2.0))
        }
        if (flags and BikeFlag.AVERAGE_CADENCE != 0) {
            out = out.copy(averageCadenceRpm = r.u16()?.div(2.0))
        }
        if (flags and BikeFlag.TOTAL_DISTANCE != 0) {
            out = out.copy(totalDistanceMetres = r.u24())
        }
        if (flags and BikeFlag.RESISTANCE_LEVEL != 0) {
            out = out.copy(resistanceLevel = r.s16())
        }
        if (flags and BikeFlag.INSTANTANEOUS_POWER != 0) {
            out = out.copy(powerWatts = r.s16())
        }
        if (flags and BikeFlag.AVERAGE_POWER != 0) {
            out = out.copy(averagePowerWatts = r.s16())
        }
        if (flags and BikeFlag.EXPENDED_ENERGY != 0) {
            out = out.withEnergy(r)
        }
        if (flags and BikeFlag.HEART_RATE != 0) {
            out = out.copy(heartRateBpm = r.u8())
        }
        if (flags and BikeFlag.METABOLIC_EQUIVALENT != 0) {
            out = out.copy(metabolicEquivalent = r.u8()?.div(10.0))
        }
        if (flags and BikeFlag.ELAPSED_TIME != 0) {
            out = out.copy(elapsedSeconds = r.u16())
        }
        if (flags and BikeFlag.REMAINING_TIME != 0) {
            out = out.copy(remainingSeconds = r.u16())
        }

        return if (r.truncated) null else out
    }

    // ---- cross trainer (0x2ACE) ----

    private object CrossTrainerFlag {
        const val MORE_DATA = 1 shl 0
        const val AVERAGE_SPEED = 1 shl 1
        const val TOTAL_DISTANCE = 1 shl 2
        const val STEP_COUNT = 1 shl 3
        const val STRIDE_COUNT = 1 shl 4
        const val ELEVATION_GAIN = 1 shl 5
        const val INCLINATION = 1 shl 6
        const val RESISTANCE_LEVEL = 1 shl 7
        const val INSTANTANEOUS_POWER = 1 shl 8
        const val AVERAGE_POWER = 1 shl 9
        const val EXPENDED_ENERGY = 1 shl 10
        const val HEART_RATE = 1 shl 11
        const val METABOLIC_EQUIVALENT = 1 shl 12
        const val ELAPSED_TIME = 1 shl 13
        const val REMAINING_TIME = 1 shl 14
    }

    /**
     * Decode a Cross Trainer (elliptical) Data notification.
     *
     * **This characteristic's flags field is 24 bits, not 16.** It is the only one in the family
     * that is, and it is the single most dangerous thing in this file: pointing
     * [parseTreadmillData] at a cross trainer packet reads a two-byte header, leaves the cursor one
     * byte early, and decodes every field from the wrong offset while throwing nothing. That is why
     * the transport picks a parser from the characteristic UUID rather than from anything in the
     * payload.
     */
    fun parseCrossTrainerData(bytes: ByteArray?): MachineData? {
        if (bytes == null || bytes.size < 3) return null
        val flags = u24(bytes, 0)
        val r = Reader(bytes, 3)
        var out = MachineData()

        if (flags and CrossTrainerFlag.MORE_DATA == 0) {
            out = out.copy(speedKph = r.u16()?.div(100.0))
        }
        if (flags and CrossTrainerFlag.AVERAGE_SPEED != 0) {
            out = out.copy(averageSpeedKph = r.u16()?.div(100.0))
        }
        if (flags and CrossTrainerFlag.TOTAL_DISTANCE != 0) {
            out = out.copy(totalDistanceMetres = r.u24())
        }
        if (flags and CrossTrainerFlag.STEP_COUNT != 0) {
            out = out.copy(stepsPerMinute = r.u16(), averageStepRate = r.u16())
        }
        if (flags and CrossTrainerFlag.STRIDE_COUNT != 0) {
            out = out.copy(strideCount = r.u16())
        }
        if (flags and CrossTrainerFlag.ELEVATION_GAIN != 0) {
            out = out.copy(
                positiveElevationGainMetres = r.u16()?.div(10.0),
                negativeElevationGainMetres = r.u16()?.div(10.0),
            )
        }
        if (flags and CrossTrainerFlag.INCLINATION != 0) {
            out = out.copy(
                inclinePercent = r.s16()?.div(10.0),
                rampAngleDegrees = r.s16()?.div(10.0),
            )
        }
        if (flags and CrossTrainerFlag.RESISTANCE_LEVEL != 0) {
            out = out.copy(resistanceLevel = r.s16())
        }
        if (flags and CrossTrainerFlag.INSTANTANEOUS_POWER != 0) {
            out = out.copy(powerWatts = r.s16())
        }
        if (flags and CrossTrainerFlag.AVERAGE_POWER != 0) {
            out = out.copy(averagePowerWatts = r.s16())
        }
        if (flags and CrossTrainerFlag.EXPENDED_ENERGY != 0) {
            out = out.withEnergy(r)
        }
        if (flags and CrossTrainerFlag.HEART_RATE != 0) {
            out = out.copy(heartRateBpm = r.u8())
        }
        if (flags and CrossTrainerFlag.METABOLIC_EQUIVALENT != 0) {
            out = out.copy(metabolicEquivalent = r.u8()?.div(10.0))
        }
        if (flags and CrossTrainerFlag.ELAPSED_TIME != 0) {
            out = out.copy(elapsedSeconds = r.u16())
        }
        if (flags and CrossTrainerFlag.REMAINING_TIME != 0) {
            out = out.copy(remainingSeconds = r.u16())
        }

        return if (r.truncated) null else out
    }

    // ---- rower (0x2AD1) ----

    private object RowerFlag {
        /** Inverted, and it gates **two** fields: stroke rate and stroke count. */
        const val MORE_DATA = 1 shl 0
        const val AVERAGE_STROKE_RATE = 1 shl 1
        const val TOTAL_DISTANCE = 1 shl 2
        const val INSTANTANEOUS_PACE = 1 shl 3
        const val AVERAGE_PACE = 1 shl 4
        const val INSTANTANEOUS_POWER = 1 shl 5
        const val AVERAGE_POWER = 1 shl 6
        const val RESISTANCE_LEVEL = 1 shl 7
        const val EXPENDED_ENERGY = 1 shl 8
        const val HEART_RATE = 1 shl 9
        const val METABOLIC_EQUIVALENT = 1 shl 10
        const val ELAPSED_TIME = 1 shl 11
        const val REMAINING_TIME = 1 shl 12
    }

    /**
     * Decode a Rower Data notification.
     *
     * The inverted bit 0 gates a *pair* here — Stroke Rate (one byte, half-stroke units) and Stroke
     * Count (two bytes) — so it consumes three bytes rather than the two its treadmill counterpart
     * does. A rower reports no speed at all; its distance and pace are the useful figures.
     */
    fun parseRowerData(bytes: ByteArray?): MachineData? {
        if (bytes == null || bytes.size < 2) return null
        val flags = u16(bytes, 0)
        val r = Reader(bytes, 2)
        var out = MachineData()

        if (flags and RowerFlag.MORE_DATA == 0) {
            out = out.copy(strokeRatePerMin = r.u8()?.div(2.0), strokeCount = r.u16())
        }
        if (flags and RowerFlag.AVERAGE_STROKE_RATE != 0) {
            // Read unconditionally into its own field. This was written as
            // `out.strokeRatePerMin ?: r.u8()`, and Kotlin's elvis short-circuits: when an
            // instantaneous rate was already present the read never happened, the cursor never
            // advanced, and every field after it decoded one byte early. A declared field must
            // always be consumed, whether or not its value is wanted.
            out = out.copy(averageStrokeRatePerMin = r.u8()?.div(2.0))
        }
        if (flags and RowerFlag.TOTAL_DISTANCE != 0) {
            out = out.copy(totalDistanceMetres = r.u24())
        }
        if (flags and RowerFlag.INSTANTANEOUS_PACE != 0) {
            out = out.copy(paceSecondsPer500m = r.u16())
        }
        if (flags and RowerFlag.AVERAGE_PACE != 0) {
            out = out.copy(averagePaceSecondsPer500m = r.u16())
        }
        if (flags and RowerFlag.INSTANTANEOUS_POWER != 0) {
            out = out.copy(powerWatts = r.s16())
        }
        if (flags and RowerFlag.AVERAGE_POWER != 0) {
            out = out.copy(averagePowerWatts = r.s16())
        }
        if (flags and RowerFlag.RESISTANCE_LEVEL != 0) {
            out = out.copy(resistanceLevel = r.s16())
        }
        if (flags and RowerFlag.EXPENDED_ENERGY != 0) {
            out = out.withEnergy(r)
        }
        if (flags and RowerFlag.HEART_RATE != 0) {
            out = out.copy(heartRateBpm = r.u8())
        }
        if (flags and RowerFlag.METABOLIC_EQUIVALENT != 0) {
            out = out.copy(metabolicEquivalent = r.u8()?.div(10.0))
        }
        if (flags and RowerFlag.ELAPSED_TIME != 0) {
            out = out.copy(elapsedSeconds = r.u16())
        }
        if (flags and RowerFlag.REMAINING_TIME != 0) {
            out = out.copy(remainingSeconds = r.u16())
        }

        return if (r.truncated) null else out
    }

    // --------------------------------------------------------------- ranges

    /**
     * What the machine says it will accept.
     *
     * Reported, never enforced here. `MachineCoordinator.applyMachineLimits` intersects this with
     * Stride's own fixed ceiling, so a machine advertising an implausible top speed does not get one.
     */
    data class SpeedRange(val minKph: Double, val maxKph: Double, val stepKph: Double)

    data class InclinationRange(
        val minPercent: Double,
        val maxPercent: Double,
        val stepPercent: Double,
    )

    /** Supported Speed Range (`0x2AD4`): three uint16s in 0.01 km/h. */
    fun parseSpeedRange(bytes: ByteArray?): SpeedRange? {
        if (bytes == null || bytes.size < 6) return null
        return SpeedRange(
            minKph = u16(bytes, 0) / 100.0,
            maxKph = u16(bytes, 2) / 100.0,
            stepKph = u16(bytes, 4) / 100.0,
        )
    }

    /**
     * Supported Inclination Range (`0x2AD5`): two **signed** 0.1% values then an unsigned step.
     *
     * Min is signed because a machine that declines advertises a negative minimum, and reading it
     * unsigned turns -3% into +6553.3%, which would then be intersected with our ceiling and quietly
     * become "this machine has no lower bound".
     */
    fun parseInclinationRange(bytes: ByteArray?): InclinationRange? {
        if (bytes == null || bytes.size < 6) return null
        return InclinationRange(
            minPercent = s16(bytes, 0) / 10.0,
            maxPercent = s16(bytes, 2) / 10.0,
            stepPercent = u16(bytes, 4) / 10.0,
        )
    }

    // -------------------------------------------------------------- features

    /**
     * Fitness Machine Feature (`0x2ACC`): two uint32 bitfields.
     *
     * The second word is the one that matters for control. A machine can *report* speed while
     * refusing to be *told* a speed, and those are different bits — conflating them puts a control on
     * screen that always refuses.
     */
    data class Features(
        val supportsInclineReporting: Boolean,
        val supportsSpeedTarget: Boolean,
        val supportsInclineTarget: Boolean,
    )

    fun parseFeatures(bytes: ByteArray?): Features? {
        if (bytes == null || bytes.size < 8) return null
        val machine = u32(bytes, 0)
        val target = u32(bytes, 4)
        return Features(
            // Bit 3, not bit 1. Bit 1 is Cadence Supported, so reading it here reported "this
            // machine measures incline" for every bike that reports cadence and none of the
            // treadmills that do not. The target bits below are separately numbered and are the
            // ones that gate commands, which is why this only ever mis-described a machine rather
            // than enabling a control it would refuse.
            supportsInclineReporting = machine and (1L shl 3) != 0L,
            supportsSpeedTarget = target and (1L shl 0) != 0L,
            supportsInclineTarget = target and (1L shl 1) != 0L,
        )
    }

    // ---------------------------------------------------------------- status

    /**
     * Fitness Machine Status (`0x2ADA`) op codes that change what Stride believes about the belt.
     *
     * This characteristic is how the machine reports what *it* or the *rider* did — a console button,
     * the safety key, an emergency stop. Stride's session state must treat it as an input rather than
     * assume Stride is the only actor, which is the same rule `PLAN.md` §3.5 sets for the GlassOS
     * workout stream.
     */
    object Status {
        const val RESET = 0x01
        const val STOPPED_OR_PAUSED_BY_USER = 0x02
        const val STOPPED_BY_SAFETY_KEY = 0x03
        const val STARTED_OR_RESUMED_BY_USER = 0x04

        /**
         * The machine has taken control back.
         *
         * Sent when something else claimed the machine, or when it timed the grant out. It is not a
         * workout transition, which is why [workoutStateFromStatus] ignores it and
         * [controlPermissionLost] exists separately: the consequence is that the *next* command is
         * refused unless control is requested again, and if that next command is a stop, the belt
         * keeps running.
         */
        const val CONTROL_PERMISSION_LOST = 0xFF
    }

    /** Whether a Status notification says the machine has revoked our control grant. */
    fun controlPermissionLost(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) return false
        return u8(bytes, 0) == Status.CONTROL_PERMISSION_LOST
    }

    /** What a Status notification implies about the workout, as a GlassOS `WORKOUT_*` value. */
    fun workoutStateFromStatus(bytes: ByteArray?): Int? {
        if (bytes == null || bytes.isEmpty()) return null
        return when (u8(bytes, 0)) {
            Status.RESET -> GlassOsCommands.WORKOUT_IDLE
            // Pause and stop arrive as the same op code with a parameter that distinguishes them,
            // and machines disagree about whether they send it. Treating an unparameterised stop as
            // PAUSED would leave Stride believing a workout is live on a stopped belt, so the
            // narrower reading wins: only an explicit pause parameter means paused.
            Status.STOPPED_OR_PAUSED_BY_USER ->
                if (bytes.size >= 2 && u8(bytes, 1) == StopParam.PAUSE) {
                    GlassOsCommands.WORKOUT_PAUSED
                } else {
                    GlassOsCommands.WORKOUT_IDLE
                }
            // The safety key is the one true emergency stop. Nothing about it is recoverable in
            // software, and reporting anything but "not running" here would be the exact dishonesty
            // this project forbids.
            Status.STOPPED_BY_SAFETY_KEY -> GlassOsCommands.WORKOUT_IDLE
            Status.STARTED_OR_RESUMED_BY_USER -> GlassOsCommands.WORKOUT_RUNNING
            else -> null
        }
    }

    // ------------------------------------------------------- byte primitives

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

    private fun u16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)

    private fun s16(b: ByteArray, at: Int): Int = u16(b, at).toShort().toInt()

    private fun u24(b: ByteArray, at: Int): Int =
        u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16)

    private fun u32(b: ByteArray, at: Int): Long =
        (u8(b, at).toLong()) or
            (u8(b, at + 1).toLong() shl 8) or
            (u8(b, at + 2).toLong() shl 16) or
            (u8(b, at + 3).toLong() shl 24)

    private fun uint16Le(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun sint16Le(v: Int): ByteArray = uint16Le(v and 0xFFFF)
}
