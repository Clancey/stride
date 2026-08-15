package io.stride.spikes

/**
 * What the machine said in reply to a command.
 *
 * Distinct from "the belt is now doing what you asked" — that is only knowable from telemetry,
 * which is why [MachineCoordinator] confirms a stop by watching speed rather than by trusting [Ok].
 *
 * Lifted out of [GlassOsCommands] when the direct register path arrived, because both transports
 * have exactly these three outcomes and [MachineCoordinator]'s safety rules are written against the
 * outcome, not against the wire that produced it.
 */
sealed interface MachineAck {
    /** The machine accepted the command. */
    data object Ok : MachineAck

    /** The machine answered and refused. [detail] is its error field, when it gave one. */
    data class Refused(val detail: String) : MachineAck

    /** No usable answer: transport failure, timeout, or an unparseable reply. */
    data class NoAnswer(val reason: String) : MachineAck
}

/**
 * Every call that can move the belt, independent of how it reaches the machine.
 *
 * Two implementations: [GlassOsCommands] goes through iFit's gRPC server, [DirectMachineCommands]
 * writes registers to the motor controller underneath it. They are interchangeable *only* at this
 * interface — everything that protects the rider lives above it, in [MachineCoordinator], which is
 * why swapping transports does not swap out the clamps, the ramp limit, stop preemption or the
 * generation check.
 *
 * ## Read this before implementing it again
 *
 * Implementations perform **no validation of their own**. That is not laziness, it is the same rule
 * [GlassOsCommands] was written under: clamping in two places splits the safety rules across files
 * and makes it ambiguous which one is authoritative. An implementation's job is to put the value it
 * was handed onto the wire, or to say honestly that it could not.
 *
 * The one thing an implementation *may* refuse on its own is a transport it cannot trust — see
 * [DirectMachineCommands], which will not encode a write until [FitProProbe] has confirmed the
 * framing. That is not a safety clamp on the rider's value; it is a statement that the bytes would
 * be meaningless.
 *
 * Every call blocks. Callers must be off the main thread.
 */
interface MachineCommands {

    /** A short name for logs and diagnostics, e.g. "iFit (GlassOS)". Never parsed. */
    val transportName: String

    // ---- setpoints ----

    /** Set the belt speed in km/h. Sent as given; clamping belongs to the coordinator. */
    fun setSpeedKph(kph: Double): MachineAck

    /** Set the incline in percent. */
    fun setInclinePercent(percent: Double): MachineAck

    /** Set the console fan to a [GlassOsCommands] `FAN_*` value. */
    fun setFanState(state: Int): MachineAck

    // ---- lifecycle ----

    /**
     * Attach to the machine, and report the console state it answered with.
     *
     * Every transport has a handshake and none of them can be skipped. GlassOS hands machine
     * control only to a client that has called `ConsoleService/Connect`; the direct register path
     * has to find the device address, read `DEVICE_INFO` for the supported-register list, and
     * confirm the framing before a single write means anything. Callers need one question answered
     * — "is there a machine on the other end of this, right now" — and it is the same question
     * either way, so it is asked through one method.
     *
     * Returns a [GlassOsClient.ConsoleState] value, normalised by every implementation for the same
     * reason [workoutState] is: [MachineCoordinator] compares against those constants and must not
     * learn which transport it is talking to. Null means nothing usable came back.
     *
     * Must be idempotent and cheap to repeat. It is called on a schedule, not once.
     */
    fun connect(): Int?

    /** Begin a workout. On real hardware this starts the belt. */
    fun startWorkout(): MachineAck

    fun pause(): MachineAck

    fun resume(): MachineAck

    fun stop(): MachineAck

    // ---- state ----

    /**
     * The console's workout state as a [GlassOsCommands] `WORKOUT_*` value, or null if unknown.
     *
     * Normalised to the GlassOS numbering by every implementation, because [MachineCoordinator]
     * compares against those constants. The direct register path uses a *different* enum — FitPro's
     * `WorkoutMode` puts RUNNING at 2 where GlassOS's `WorkoutState` puts it at 3 — so an
     * implementation that returned its own raw value would silently break the stale-session clear.
     */
    fun workoutState(): Int?

    /** Whether the machine can match fan speed to effort itself. Null when it cannot be asked. */
    fun autoFanSupported(): Boolean?

    /**
     * The speeds this machine offers as quick picks, in **mph**, or null when it was not asked.
     *
     * Null and empty mean different things and callers rely on it: null is "we do not know yet, ask
     * again", empty is "this machine offers none". Collapsing them makes a transport failure look
     * like a machine with no presets, and the UI would stop retrying.
     *
     * mph because that is the unit the app displays and the unit [MachineLink.speedPresets] already
     * publishes. Neither wire speaks it — GlassOS sends metres per second, the register path sends
     * hundredths of a km/h — so the conversion happens once, in the implementation that knows which
     * wire it read from, rather than in every caller.
     */
    fun speedPresetsMph(): List<Double>?

    /** The inclines this machine offers as quick picks, in percent, or null when not asked. */
    fun inclinePresets(): List<Double>?

    /**
     * The range the machine will accept, or null where it does not say.
     *
     * Reported rather than enforced here: [MachineCoordinator.applyMachineLimits] intersects this
     * with Stride's own fixed ceiling, so a machine claiming a 25 mph top speed does not get one.
     */
    fun limits(): MachineLimits?
}
