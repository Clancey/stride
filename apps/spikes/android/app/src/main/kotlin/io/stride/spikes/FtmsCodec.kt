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

        /** Indoor Bike Data. Parsed for capability reporting; Stride does not drive bikes yet. */
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

    // -------------------------------------------------------- treadmill data

    /**
     * One Treadmill Data notification, decoded.
     *
     * Every field is nullable and null means **the machine did not send it**, never zero. That
     * distinction is load-bearing in this project: `MachineLink.NO_READING` exists because a
     * fabricated `0.0` next to a treadmill reads as "the belt is stopped", and the same rule that
     * governs the GlassOS parser governs this one.
     */
    data class TreadmillData(
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
    )

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
     * "Data Not Available" for a uint16 energy field, per the spec.
     *
     * Distinguished from a real value because 65535 kcal rendered as a calorie count is nonsense the
     * rider would have to interpret, and because it is genuinely "unknown" — the same thing GlassOS
     * spells as `NaN`.
     */
    private const val ENERGY_NOT_AVAILABLE = 0xFFFF

    /**
     * Decode a Treadmill Data notification.
     *
     * Returns null when the payload is too short to hold even the flags, or when a declared field
     * runs past the end of the buffer. **A truncated packet is discarded whole rather than parsed up
     * to the break**: the fields already read are valid, but publishing a partial sample would let a
     * stale incline sit beside a fresh speed, and the pair would describe a machine that never
     * existed. One consistent sample or none.
     */
    fun parseTreadmillData(bytes: ByteArray?): TreadmillData? {
        if (bytes == null || bytes.size < 2) return null
        val flags = u16(bytes, 0)
        var at = 2

        // A field that would run off the end means the packet disagrees with its own flags. Bail out
        // rather than guess, and let the caller keep the previous sample until a whole one arrives.
        fun room(n: Int): Boolean = at + n <= bytes.size

        var speed: Double? = null
        if (flags and TreadmillFlag.MORE_DATA == 0) {
            if (!room(2)) return null
            speed = u16(bytes, at) / 100.0
            at += 2
        }

        var averageSpeed: Double? = null
        if (flags and TreadmillFlag.AVERAGE_SPEED != 0) {
            if (!room(2)) return null
            averageSpeed = u16(bytes, at) / 100.0
            at += 2
        }

        var distance: Int? = null
        if (flags and TreadmillFlag.TOTAL_DISTANCE != 0) {
            // uint24. The only three-byte field in the characteristic, and the usual reason an
            // otherwise-correct parser drifts by one byte from here on.
            if (!room(3)) return null
            distance = u24(bytes, at)
            at += 3
        }

        var incline: Double? = null
        var rampAngle: Double? = null
        if (flags and TreadmillFlag.INCLINATION != 0) {
            // One flag, two fields, four bytes.
            if (!room(4)) return null
            incline = s16(bytes, at) / 10.0
            rampAngle = s16(bytes, at + 2) / 10.0
            at += 4
        }

        var positiveGain: Double? = null
        var negativeGain: Double? = null
        if (flags and TreadmillFlag.ELEVATION_GAIN != 0) {
            if (!room(4)) return null
            positiveGain = u16(bytes, at) / 10.0
            negativeGain = u16(bytes, at + 2) / 10.0
            at += 4
        }

        var instantaneousPace: Double? = null
        if (flags and TreadmillFlag.INSTANTANEOUS_PACE != 0) {
            if (!room(1)) return null
            instantaneousPace = u8(bytes, at) / 10.0
            at += 1
        }

        var averagePace: Double? = null
        if (flags and TreadmillFlag.AVERAGE_PACE != 0) {
            if (!room(1)) return null
            averagePace = u8(bytes, at) / 10.0
            at += 1
        }

        var totalEnergy: Int? = null
        var energyPerHour: Int? = null
        var energyPerMinute: Int? = null
        if (flags and TreadmillFlag.EXPENDED_ENERGY != 0) {
            // One flag, three fields, five bytes.
            if (!room(5)) return null
            totalEnergy = u16(bytes, at).takeIf { it != ENERGY_NOT_AVAILABLE }
            energyPerHour = u16(bytes, at + 2).takeIf { it != ENERGY_NOT_AVAILABLE }
            energyPerMinute = u8(bytes, at + 4).takeIf { it != 0xFF }
            at += 5
        }

        var heartRate: Int? = null
        if (flags and TreadmillFlag.HEART_RATE != 0) {
            if (!room(1)) return null
            heartRate = u8(bytes, at)
            at += 1
        }

        var met: Double? = null
        if (flags and TreadmillFlag.METABOLIC_EQUIVALENT != 0) {
            if (!room(1)) return null
            met = u8(bytes, at) / 10.0
            at += 1
        }

        var elapsed: Int? = null
        if (flags and TreadmillFlag.ELAPSED_TIME != 0) {
            if (!room(2)) return null
            elapsed = u16(bytes, at)
            at += 2
        }

        var remaining: Int? = null
        if (flags and TreadmillFlag.REMAINING_TIME != 0) {
            if (!room(2)) return null
            remaining = u16(bytes, at)
            at += 2
        }

        var force: Int? = null
        var power: Int? = null
        if (flags and TreadmillFlag.FORCE_ON_BELT != 0) {
            if (!room(4)) return null
            force = s16(bytes, at)
            power = s16(bytes, at + 2)
            at += 4
        }

        return TreadmillData(
            speedKph = speed,
            averageSpeedKph = averageSpeed,
            totalDistanceMetres = distance,
            inclinePercent = incline,
            rampAngleDegrees = rampAngle,
            positiveElevationGainMetres = positiveGain,
            negativeElevationGainMetres = negativeGain,
            instantaneousPaceKmPerMin = instantaneousPace,
            averagePaceKmPerMin = averagePace,
            totalEnergyKcal = totalEnergy,
            energyPerHourKcal = energyPerHour,
            energyPerMinuteKcal = energyPerMinute,
            heartRateBpm = heartRate,
            metabolicEquivalent = met,
            elapsedSeconds = elapsed,
            remainingSeconds = remaining,
            forceOnBeltNewtons = force,
            powerWatts = power,
        )
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
            supportsInclineReporting = machine and (1L shl 1) != 0L,
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
