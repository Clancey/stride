package io.stride.spikes

import android.util.Log

/**
 * Every GlassOS call that can move the belt.
 *
 * ## Read this before you call anything here
 *
 * These are motor commands. `startWorkout()` in particular takes no arguments and reads like a
 * harmless session start, but measured on the real machine it drove the console
 * `IDLE → WARM_UP → WORKOUT` and **started the belt at 1.0 mph** with no speed command sent.
 *
 * Nothing in the app should call this class directly. [MachineCoordinator] is the only intended
 * caller: it owns the clamps, the ramp limit, stop preemption and the generation check, and a
 * command that bypasses it has none of those. This class deliberately performs **no validation of
 * its own** — clamping here as well would split the safety rules across two files and make it
 * ambiguous which one is authoritative.
 *
 * Every call blocks. Callers must be off the main thread.
 */
class GlassOsCommands(private val client: GlassOsClient) {

    /**
     * What the machine said. Distinct from "the belt is now doing what you asked" — that is only
     * knowable from telemetry, which is why [MachineCoordinator] confirms a stop by watching speed
     * rather than by trusting [Ack.Ok].
     */
    sealed interface Ack {
        /** The machine accepted the command. */
        data object Ok : Ack

        /** The machine answered and refused. [detail] is its error field, when it gave one. */
        data class Refused(val detail: String) : Ack

        /** No usable answer: transport failure, timeout, or an unparseable reply. */
        data class NoAnswer(val reason: String) : Ack
    }

    // ------------------------------------------------------------- setpoints

    /** Set the belt speed in km/h. [kph] is sent as given; clamping belongs to the coordinator. */
    fun setSpeedKph(kph: Double): Ack =
        command("SpeedService", "SetSpeed", GlassOsWire.encodeDouble(1, kph))

    /** Set the incline in percent. */
    fun setInclinePercent(percent: Double): Ack =
        command("InclineService", "SetIncline", GlassOsWire.encodeDouble(1, percent))

    // ------------------------------------------------------------- lifecycle

    /** Starts a workout **and the belt**. See the class note. */
    /**
     * Begin a workout.
     *
     * Parsed separately from the others because it is the one command with a different reply type.
     * `StartNewWorkout` returns `StartWorkoutResponse { WorkoutResult result = 1; string workoutID
     * = 2 }`, so field 1 is the *result*, not an error, and field 2 is an ID, not a success flag.
     * Reading it as a bare `WorkoutResult` inverted the meaning: a successful start came back as
     * `0a 02 10 01` — field 1 holding `WorkoutResult{success:true}` — and was reported to the rider
     * as a refusal while the belt was in fact starting.
     */
    fun startWorkout(): Ack {
        val raw = client.postRaw("WorkoutService", "StartNewWorkout", GlassOsWire.EMPTY_FRAME, COMMAND_TIMEOUT_S)
            ?: return Ack.NoAnswer("WorkoutService/StartNewWorkout: no reply")
        val result = GlassOsWire.parse(raw).message(1)
            ?: return Ack.Ok
        return interpretWorkoutResult("WorkoutService", "StartNewWorkout", result, raw)
    }

    fun pause(): Ack = command("WorkoutService", "Pause", ByteArray(0))

    fun resume(): Ack = command("WorkoutService", "Resume", ByteArray(0))

    fun stop(): Ack = command("WorkoutService", "Stop", ByteArray(0))

    // ---------------------------------------------------------- capabilities

    /**
     * Whether the machine says it will accept a speed write right now.
     *
     * Asked rather than assumed. A console that is idle, in results, or under someone else's
     * control can refuse writes, and finding that out from a failed `SetSpeed` means having already
     * tried to move the belt.
     */
    fun canWriteSpeed(): Boolean? = availability("SpeedService")

    fun canWriteIncline(): Boolean? = availability("InclineService")

    fun canWriteFan(): Boolean? = availability("FanStateService")

    /** Whether this machine can match fan speed to effort by itself. */
    fun autoFanSupported(): Boolean? =
        client.postRaw("FanStateService", "IsAutoFanStateSupported", GlassOsWire.EMPTY_FRAME)
            ?.let { GlassOsWire.parse(it).bool(1) }

    fun fanState(): Int? =
        client.postRaw("FanStateService", "GetFanState", GlassOsWire.EMPTY_FRAME)
            ?.let { GlassOsWire.parse(it).enum(1) }

    /** Set the console fan. Comfort, not motion — but it still goes through the coordinator's queue. */
    fun setFanState(state: Int): Ack =
        acknowledged("FanStateService", "SetFanState", GlassOsWire.encodeVarintField(1, state))

    /**
     * Send a command whose reply is `Empty`.
     *
     * Separate from [command] on purpose: `Empty` carries no success flag, so *any* reply is the
     * acknowledgement. Running these through the `WorkoutResult` reader would work by accident
     * today and break the moment that reader gets stricter about empty messages.
     */
    private fun acknowledged(service: String, method: String, message: ByteArray): Ack {
        client.postRaw(service, method, GlassOsWire.frame(message), COMMAND_TIMEOUT_S)
            ?: return Ack.NoAnswer("$service/$method: no reply")
        return Ack.Ok
    }

    private fun availability(service: String): Boolean? =
        client.postRaw(service, "CanWrite", GlassOsWire.EMPTY_FRAME)
            ?.let { GlassOsWire.parse(it).bool(1) }

    /**
     * Send one command and interpret the `WorkoutResult` reply.
     *
     * `WorkoutResult` is a oneof: field 1 is an `IFitError`, field 2 is `bool success`. Proto3 omits
     * a false bool, so "field 2 absent and field 1 absent" is an empty message — which several of
     * these RPCs legitimately return on success. Treating an empty reply as failure would make
     * every successful stop look like a refusal, so absence is only an error when field 1 is there.
     */
    private fun command(service: String, method: String, message: ByteArray): Ack {
        val raw = client.postRaw(service, method, GlassOsWire.frame(message), COMMAND_TIMEOUT_S)
            ?: return Ack.NoAnswer("$service/$method: no reply")
        return interpretWorkoutResult(service, method, GlassOsWire.parse(raw), raw)
    }

    /** Read a `WorkoutResult`, whether it arrived on its own or nested inside another message. */
    private fun interpretWorkoutResult(
        service: String,
        method: String,
        result: GlassOsWire.Fields,
        raw: ByteArray,
    ): Ack {
        result.bool(2)?.let { return if (it) Ack.Ok else Ack.Refused("$service/$method: success=false") }
        if (result.hasField(1)) {
            // The decoded detail is best-effort, so the raw reply is logged alongside it. A
            // refusal we cannot explain is a refusal we cannot fix, and this is the only place the
            // bytes still exist.
            Log.w(TAG, "$service/$method refused, raw=${raw.joinToString(" ") { b -> "%02x".format(b) }}")
            return Ack.Refused("$service/$method: ${result.errorDetail()}")
        }
        return Ack.Ok
    }

    /**
     * The console's own workout state, as a raw `WorkoutState` enum number.
     *
     * A read, not a command, but it lives here because it is only ever needed to explain why a
     * command was refused: `StartNewWorkout` is only valid from some states, and knowing which one
     * the machine is actually in turns "refused" into something a rider can act on.
     */
    fun workoutState(): Int? =
        client.postRaw("WorkoutService", "GetWorkoutState", GlassOsWire.EMPTY_FRAME)
            ?.let { GlassOsWire.parse(it).enum(1) }

    companion object {
        /**
         * WorkoutState values, from protocol/glassos WorkoutState.proto. Named because a bare 3 in
         * a state check is exactly the sort of constant that gets misread during a hardware bring-up.
         */
        const val WORKOUT_IDLE = 1
        const val WORKOUT_RUNNING = 3
        const val WORKOUT_PAUSED = 4
        const val WORKOUT_RESULTS = 5

        /** FanState values, from protocol/glassos settings/FanStateService.proto. */
        const val FAN_OFF = 0
        const val FAN_LOW = 1
        const val FAN_MEDIUM = 2
        const val FAN_HIGH = 3
        const val FAN_AUTO = 4

        /** Rider-facing name for a FanState. Short because it labels a segmented control. */
        fun fanStateName(state: Int): String = when (state) {
            FAN_OFF -> "Off"
            FAN_LOW -> "Low"
            FAN_MEDIUM -> "Med"
            FAN_HIGH -> "High"
            FAN_AUTO -> "Auto"
            else -> "—"
        }

        const val TAG = "GlassOsCommands"

        /**
         * How long a command may take to be acknowledged.
         *
         * Generous compared to the two seconds a metric read gets. A command that is reported as
         * failed but actually landed is the worst outcome available here: the rider is told the
         * treadmill ignored them while the belt does what they asked.
         */
        const val COMMAND_TIMEOUT_S = 12L
    }
}
