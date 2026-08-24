package io.stride.spikes

import android.os.SystemClock
import android.util.Log

/**
 * Stride's driver for any machine that speaks the Bluetooth SIG Fitness Machine Service.
 *
 * ## Why a standards driver earns its place next to two proprietary ones
 *
 * GlassOS and the FitPro register path both reach exactly one manufacturer's equipment. FTMS is the
 * protocol a standards body defined, and it is what most non-iFit equipment speaks. It is also the
 * first transport here that talks to a machine Stride is **not running on** — a treadmill or trainer
 * across a radio link rather than the console under the app.
 *
 * ## The safety rules do not change, and that is the point
 *
 * This is the third implementation of [MachineCommands], and like the other two it **validates
 * nothing**. No clamping, no ramp limiting, no stop preemption, no retry. Those live in
 * [MachineCoordinator], above this interface, so there is exactly one place to audit no matter which
 * wire is underneath. An implementation's whole job is to put the value it was handed onto the wire
 * or to say honestly that it could not.
 *
 * The one thing this driver refuses on its own is a command the *machine* said it will not accept —
 * see [requireControl] and the feature check in [setSpeedKph]. That is not a safety clamp on the
 * rider's value; it is a statement that the bytes would be rejected anyway.
 */
class FtmsMachineCommands(private val transport: FtmsLink) : MachineCommands {

    override val transportName: String get() = "FTMS (${transport.name})"

    /**
     * Whether this client currently holds the machine's control grant.
     *
     * FTMS machines reject every setpoint until a client sends `RequestControl`, and they drop that
     * grant on disconnect — and, on some machines, after an idle period or a rider touching the
     * console. So this is a belief that has to be re-established rather than a fact established once
     * at startup, which is why every setpoint goes through [requireControl] instead of assuming a
     * successful [connect] still holds.
     */
    @Volatile private var hasControl = false

    // ---- setpoints ----

    override fun setSpeedKph(kph: Double): MachineAck {
        // Asked of the machine rather than assumed: a machine can stream speed while refusing to be
        // told one, and those are different feature bits. Sending anyway would produce a control on
        // screen that always refuses, which reads to a rider as a broken app rather than a machine
        // that does not do this.
        transport.features?.let {
            if (!it.supportsSpeedTarget) {
                return MachineAck.Refused("this machine does not accept a target speed")
            }
        }
        return command("speed") { FtmsCodec.encodeSetTargetSpeed(kph) }
    }

    override fun setInclinePercent(percent: Double): MachineAck {
        transport.features?.let {
            if (!it.supportsInclineTarget) {
                return MachineAck.Refused("this machine does not accept a target incline")
            }
        }
        return command("incline") { FtmsCodec.encodeSetTargetInclination(percent) }
    }

    /**
     * FTMS has no fan.
     *
     * A definite refusal rather than [MachineAck.NoAnswer], because "we do not know" would make the
     * UI keep the control live and keep retrying something that can never work. The machine is not
     * silent about the fan; the protocol simply has no such concept, and saying so is the honest
     * answer.
     */
    override fun setFanState(state: Int): MachineAck =
        MachineAck.Refused("the fitness machine profile has no fan control")

    // ---- lifecycle ----

    /**
     * Take control of the machine and report what state it is in.
     *
     * Idempotent and cheap to repeat, because `MachineLink` calls it on a schedule rather than once.
     * A machine that has already granted control answers `SUCCESS` again.
     *
     * ## A deliberate divergence from qdomyos-zwift
     *
     * qz sends `StartOrResume` immediately after `RequestControl` inside its own speed command —
     * "start simulation" — so that a target speed always lands on a running machine. Stride does
     * **not**, and must not: `PLAN.md` §3.5 says the belt never begins moving without explicit
     * on-console confirmation, and a setpoint that silently starts a treadmill is exactly that rule
     * being broken.
     *
     * The cost is real and is handled elsewhere. Some machines ignore a target speed unless a
     * workout is running, which surfaces as a refusal rather than movement — the same situation
     * [MachineLink.CONTROL_NEEDS_WORKOUT_NOTICE] already explains to the rider on the GlassOS path.
     * A refusal a rider can act on is a better failure than a belt that starts on its own.
     */
    override fun connect(): Int? {
        if (!transport.connected) return GlassOsClient.ConsoleState.DISCONNECTED
        return if (requestControl()) consoleState() else GlassOsClient.ConsoleState.DISCONNECTED
    }

    override fun startWorkout(): MachineAck = command("start") { FtmsCodec.encodeStartOrResume() }

    override fun pause(): MachineAck = command("pause") { FtmsCodec.encodePause() }

    override fun resume(): MachineAck = command("resume") { FtmsCodec.encodeStartOrResume() }

    /**
     * Stop the machine.
     *
     * Uses the profile's own `StopOrPause` rather than commanding zero speed, because the machine's
     * notion of workout state is authoritative and a belt driven to 0 kph is still, as far as the
     * machine is concerned, in a running workout.
     */
    override fun stop(): MachineAck = command("stop") { FtmsCodec.encodeStop() }

    // ---- state ----

    /**
     * The workout state, normalised to the GlassOS `WORKOUT_*` numbering.
     *
     * FTMS has no "what state are you in" read. The machine announces transitions on the Status
     * characteristic and otherwise says nothing, so what it last announced is preferred, and a belt
     * that is demonstrably moving is the fallback. Null when neither is available: nobody has told
     * us and nothing is moving, which is genuinely unknown rather than idle.
     */
    override fun workoutState(): Int? {
        transport.announcedWorkoutState()?.let { return it }
        val moving = FtmsValues.active(freshSample()) ?: return null
        return if (moving) GlassOsCommands.WORKOUT_RUNNING else GlassOsCommands.WORKOUT_IDLE
    }

    /** No fan, so nothing can match one to effort. A definite no, not an unanswered question. */
    override fun autoFanSupported(): Boolean = false

    override fun speedPresetsMph(): List<Double>? {
        val range = transport.speedRange ?: return null
        return MachinePresets.ladder(
            min = FtmsValues.kphToMph(range.minKph),
            max = FtmsValues.kphToMph(range.maxKph),
            // Whole mph buttons regardless of the machine's own resolution. A machine advertising a
            // 0.1 km/h step would otherwise produce a ladder of 200 near-identical buttons, capped
            // to 40 arbitrary ones — quick picks are for reaching a speed quickly, and the fine
            // resolution is still available through the ordinary controls.
            step = 1.0,
        )
    }

    override fun inclinePresets(): List<Double>? {
        val range = transport.inclinationRange ?: return null
        return MachinePresets.ladder(range.minPercent, range.maxPercent, step = 1.0)
    }

    /**
     * What the machine says it will accept.
     *
     * Reported, not enforced: [MachineCoordinator.applyMachineLimits] intersects this with Stride's
     * own fixed ceiling, so a machine advertising an implausible top speed does not get one.
     *
     * Null unless **both** ranges were read. A half-known limit is worse than none: it would look
     * like a machine that declared itself, and the missing half would silently fall back to a
     * default the machine never agreed to.
     */
    override fun limits(): MachineLimits? {
        val speed = transport.speedRange ?: return null
        val incline = transport.inclinationRange ?: return null
        return MachineLimits(
            minSpeedKph = speed.minKph,
            maxSpeedKph = speed.maxKph,
            minInclinePercent = incline.minPercent,
            maxInclinePercent = incline.maxPercent,
        )
    }

    // ---- internals ----

    /**
     * Send one command, taking control first if we do not hold it.
     *
     * Encoding happens inside the lambda so that a value the codec refuses to represent becomes a
     * refusal rather than a thrown exception crossing the coordinator's queue.
     */
    private fun command(label: String, build: () -> ByteArray): MachineAck {
        if (!transport.connected) return MachineAck.NoAnswer("$label: not connected")
        val frame = try {
            build()
        } catch (t: IllegalArgumentException) {
            return MachineAck.Refused("$label: ${t.message}")
        }

        val first = attempt(label, frame)
        // One retry, and only for the one refusal that a retry can actually fix.
        //
        // A machine revokes control when something else claims it or when it times the grant out,
        // and it announces that on the Status characteristic -- but the announcement can be missed,
        // arrive late, or never be sent at all. Without this retry the first command after a lapse
        // is always refused and only the *second* re-requests control. That is survivable for a
        // speed nudge and not survivable for a stop: the rider presses stop, the machine says "not
        // permitted", and the belt keeps running until they press it again.
        if (first is MachineAck.Refused && !hasControl) {
            Log.i(TAG, "$label refused for lost control; re-requesting and retrying once")
            return attempt(label, frame)
        }
        return first
    }

    /** One pass: take control if we do not hold it, write, and read the machine's answer. */
    private fun attempt(label: String, frame: ByteArray): MachineAck {
        // Consumed before deciding, so a revocation the machine announced is acted on rather than
        // discovered by having a command refused.
        if (transport.takeControlLost()) hasControl = false
        if (!requireControl()) {
            return MachineAck.Refused("$label: the machine did not grant control")
        }
        val reply = transport.command(frame)
            ?: return MachineAck.NoAnswer("$label: no reply from the machine")
        return if (reply.success) {
            MachineAck.Ok
        } else {
            // A refusal of `control not permitted` means the grant lapsed underneath us. Dropping
            // the belief here is what lets the caller above retry with control re-requested.
            if (reply.result == FtmsCodec.Result.CONTROL_NOT_PERMITTED) hasControl = false
            MachineAck.Refused("$label: ${FtmsCodec.Result.describe(reply.result)}")
        }
    }

    private fun requireControl(): Boolean = if (hasControl) true else requestControl()

    private fun requestControl(): Boolean {
        val reply = transport.command(FtmsCodec.encodeRequestControl())
        if (reply == null) {
            Log.w(TAG, "no reply to RequestControl")
            hasControl = false
            return false
        }
        hasControl = reply.success
        return hasControl
    }

    /** A [GlassOsClient.ConsoleState] code, derived from what the machine is doing. */
    private fun consoleState(): Int = when (workoutState()) {
        GlassOsCommands.WORKOUT_RUNNING -> GlassOsClient.ConsoleState.code("WORKOUT")
            ?: GlassOsClient.ConsoleState.DISCONNECTED
        GlassOsCommands.WORKOUT_PAUSED -> GlassOsClient.ConsoleState.code("PAUSED")
            ?: GlassOsClient.ConsoleState.DISCONNECTED
        // Reachable and not running. "Idle" rather than "unknown": the machine answered
        // RequestControl, so there is definitely something on the other end of this link.
        else -> GlassOsClient.ConsoleState.code("IDLE") ?: GlassOsClient.ConsoleState.DISCONNECTED
    }

    private fun freshSample(): FtmsCodec.MachineData? = FtmsValues.fresh(transport.latest())

    private companion object {
        const val TAG = "FtmsMachine"
    }
}

/**
 * Unit conversion and freshness for the FTMS path.
 *
 * Separate from the codec on purpose: the codec speaks the spec's units so its tests can be checked
 * against the spec, and everything the app displays is imperial. One place does the conversion, the
 * same way [FitProValues] does it for the register path.
 */
object FtmsValues {

    /** The exact factor, not a rounded one. */
    private const val MPH_PER_KPH = 0.621371192

    /**
     * How old a pushed sample may be before it stops counting as a reading.
     *
     * FTMS machines notify at roughly 1 Hz, so four seconds is several missed notifications rather
     * than one late one. Past that the honest answer is `Not measured`: a number that has quietly
     * stopped updating is more dangerous than no number, because it looks exactly like a number that
     * is still true — and next to a belt, a stale `0.0` reads as "stopped".
     */
    const val SAMPLE_TTL_MS = 4_000L

    fun kphToMph(kph: Double): Double = kph * MPH_PER_KPH

    fun metresToMiles(metres: Int): Double = metres * MPH_PER_KPH / 1000.0

    /**
     * Pace in minutes per mile, derived from a **measured** speed only.
     *
     * Takes speed rather than distance and time. Deriving pace from elapsed time against an assumed
     * speed is the specific dishonesty this project forbids, and taking speed as the input makes
     * that mistake impossible to write here.
     */
    fun paceMinPerMile(speedMph: Double?): Double? {
        if (speedMph == null || speedMph.isNaN() || speedMph < 0.1) return null
        return 60.0 / speedMph
    }

    /**
     * Whether the machine is being worked, or null when nothing it sent can say.
     *
     * Speed is the obvious signal and the wrong one to rely on alone: a rower reports no speed at
     * all, and a bike on a trainer may report cadence and power without one. Asking "is any measure
     * of effort non-zero" is the question that has the same meaning on all four machine types.
     *
     * Null rather than false when the machine reported none of them, because "nothing is moving" and
     * "nothing was measured" are different claims, and only one of them is safe to make next to a
     * machine somebody is standing on.
     */
    fun active(sample: FtmsCodec.MachineData?): Boolean? {
        if (sample == null) return null
        val signals = listOfNotNull(
            sample.speedKph,
            sample.cadenceRpm,
            sample.strokeRatePerMin,
            sample.stepsPerMinute?.toDouble(),
            sample.powerWatts?.toDouble(),
        )
        if (signals.isEmpty()) return null
        return signals.any { it > 0.0 }
    }

    /**
     * The sample if it is still fresh, else null. See [SAMPLE_TTL_MS].
     *
     * [now] is a parameter rather than a call inside, so the staleness rule can be tested at all.
     * `SystemClock` is an Android stub in unit tests, and a freshness check that can only be
     * exercised on a device is a freshness check nobody exercises.
     */
    fun fresh(
        latest: Pair<FtmsCodec.MachineData, Long>?,
        now: Long = SystemClock.elapsedRealtime(),
    ): FtmsCodec.MachineData? {
        val (sample, at) = latest ?: return null
        if (now - at > SAMPLE_TTL_MS) return null
        return sample
    }

    /**
     * Turn one FTMS sample into the snapshot the rest of the app reads.
     *
     * Pure and static so the field mapping is testable without a radio. The writability flags are
     * the interesting part: they come from what the machine said about *targets*, not from whether
     * it reports the value, because those are different feature bits and conflating them puts a
     * control on screen that silently refuses.
     */
    fun toSnapshot(
        sample: FtmsCodec.MachineData,
        features: FtmsCodec.Features?,
        workoutState: Int?,
    ): GlassOsClient.Snapshot {
        val speedMph = sample.speedKph?.let(::kphToMph)
        return GlassOsClient.Snapshot(
            consoleState = when (workoutState) {
                GlassOsCommands.WORKOUT_RUNNING -> "WORKOUT"
                GlassOsCommands.WORKOUT_PAUSED -> "PAUSED"
                GlassOsCommands.WORKOUT_IDLE -> "IDLE"
                else -> null
            },
            // The workout identity discriminates "measured zero" from "nothing is being measured",
            // exactly as it does on the GlassOS path. FTMS has no workout id of its own, so a
            // running workout is stamped with a constant: what the downstream rule actually tests is
            // presence, not the value.
            workoutId = if (workoutState == GlassOsCommands.WORKOUT_RUNNING) FTMS_WORKOUT else null,
            speedMph = speedMph,
            inclinePercent = sample.inclinePercent,
            distanceMiles = sample.totalDistanceMetres?.let(::metresToMiles),
            paceMinPerMile = paceMinPerMile(speedMph),
            elapsedSeconds = sample.elapsedSeconds?.toLong(),
            calories = sample.totalEnergyKcal?.toDouble(),
            speedWritable = features?.supportsSpeedTarget,
            inclineWritable = features?.supportsInclineTarget,
            // Not "unknown": the profile has no fan, so this control can never work here.
            fanWritable = false,
            fanLevel = null,
            // The machine's own sensor, when it has one. MachineLink prefers a strap over this.
            heartRateBpm = sample.heartRateBpm,
        )
    }

    private const val FTMS_WORKOUT = "ftms"
}

/**
 * The read side of the FTMS path.
 *
 * Thin because the transport already holds the latest pushed sample; this exists so `MachineLink`
 * can ask the same `read()` question of every transport without knowing which one answered.
 */
class FtmsClient(private val transport: FtmsLink) {

    /**
     * The current snapshot, or null when there is no fresh reading.
     *
     * Null rather than a snapshot full of nulls, because `MachineLink` already treats a missing
     * snapshot as `Not measured` and a half-empty one would additionally claim a console state we
     * cannot support.
     */
    fun read(): GlassOsClient.Snapshot? {
        if (!transport.connected) return null
        val sample = FtmsValues.fresh(transport.latest()) ?: return null
        val state = transport.announcedWorkoutState()
            ?: FtmsValues.active(sample)?.let {
                if (it) GlassOsCommands.WORKOUT_RUNNING else GlassOsCommands.WORKOUT_IDLE
            }
        return FtmsValues.toSnapshot(sample, transport.features, state)
    }
}
