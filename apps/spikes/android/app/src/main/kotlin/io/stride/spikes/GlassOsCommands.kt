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
class GlassOsCommands(private val client: GlassOsClient) : MachineCommands {

    override val transportName: String get() = "iFit (GlassOS)"

    // ------------------------------------------------------------- setpoints

    /** Set the belt speed in km/h. [kph] is sent as given; clamping belongs to the coordinator. */
    override fun setSpeedKph(kph: Double): MachineAck =
        command("SpeedService", "SetSpeed", GlassOsWire.encodeDouble(1, kph))

    /** Set the incline in percent. */
    override fun setInclinePercent(percent: Double): MachineAck =
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
    override fun startWorkout(): MachineAck {
        val raw = client.postRaw("WorkoutService", "StartNewWorkout", GlassOsWire.EMPTY_FRAME, COMMAND_TIMEOUT_S)
            ?: return MachineAck.NoAnswer("WorkoutService/StartNewWorkout: no reply")
        val result = GlassOsWire.parse(raw).message(1)
            ?: return MachineAck.Ok
        return interpretWorkoutResult("WorkoutService", "StartNewWorkout", result, raw)
    }

    override fun pause(): MachineAck = command("WorkoutService", "Pause", ByteArray(0))

    override fun resume(): MachineAck = command("WorkoutService", "Resume", ByteArray(0))

    override fun stop(): MachineAck = command("WorkoutService", "Stop", ByteArray(0))

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
    override fun autoFanSupported(): Boolean? =
        client.postRaw("FanStateService", "IsAutoFanStateSupported", GlassOsWire.EMPTY_FRAME)
            ?.let { GlassOsWire.parse(it).bool(1) }

    fun fanState(): Int? =
        client.postRaw("FanStateService", "GetFanState", GlassOsWire.EMPTY_FRAME)
            ?.let { GlassOsWire.parse(it).enum(1) }

    /** Set the console fan. Comfort, not motion — but it still goes through the coordinator's queue. */
    override fun setFanState(state: Int): MachineAck =
        acknowledged("FanStateService", "SetFanState", GlassOsWire.encodeVarintField(1, state))

    /**
     * Send a command whose reply is `Empty`.
     *
     * Separate from [command] on purpose: `Empty` carries no success flag, so *any* reply is the
     * acknowledgement. Running these through the `WorkoutResult` reader would work by accident
     * today and break the moment that reader gets stricter about empty messages.
     */
    private fun acknowledged(service: String, method: String, message: ByteArray): MachineAck {
        client.postRaw(service, method, GlassOsWire.frame(message), COMMAND_TIMEOUT_S)
            ?: return MachineAck.NoAnswer("$service/$method: no reply")
        return MachineAck.Ok
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
    private fun command(service: String, method: String, message: ByteArray): MachineAck {
        val raw = client.postRaw(service, method, GlassOsWire.frame(message), COMMAND_TIMEOUT_S)
            ?: return MachineAck.NoAnswer("$service/$method: no reply")
        return interpretWorkoutResult(service, method, GlassOsWire.parse(raw), raw)
    }

    /** Read a `WorkoutResult`, whether it arrived on its own or nested inside another message. */
    private fun interpretWorkoutResult(
        service: String,
        method: String,
        result: GlassOsWire.Fields,
        raw: ByteArray,
    ): MachineAck {
        result.bool(2)?.let {
            return if (it) MachineAck.Ok else MachineAck.Refused("$service/$method: success=false")
        }
        if (result.hasField(1)) {
            // The decoded detail is best-effort, so the raw reply is logged alongside it. A
            // refusal we cannot explain is a refusal we cannot fix, and this is the only place the
            // bytes still exist.
            Log.w(TAG, "$service/$method refused, raw=${raw.joinToString(" ") { b -> "%02x".format(b) }}")
            return MachineAck.Refused("$service/$method: ${result.errorDetail()}")
        }
        return MachineAck.Ok
    }

    /**
     * Attach this client to the console, and report the state it comes back with.
     *
     * The step Stride was missing, and the reason a rebooted console ignored everything it was
     * told. GlassOS does not hand out control of the machine just because a client can open a
     * socket and present a certificate: until someone calls `Connect`, `GetConsoleState` reports
     * DISCONNECTED, `GetConsole` returns nothing, `CanWrite` is false, and every RPC that would
     * move the belt blocks until it times out. Reads that do not need the machine — cached console
     * info, fan state — answer instantly throughout, which is what made the fault look like a
     * broken treadmill rather than a missing handshake.
     *
     * It looked like it worked before only because the console's own iFit app connects when it
     * starts, and Stride inherited that. A console that boots straight into Stride never gets it,
     * which is exactly the case the rider hit after a reboot.
     *
     * Returns the `ConsoleState` from the reply, or null if the call itself failed. Safe to call
     * repeatedly: an already-connected console just answers with its current state.
     */
    override fun connect(): Int? =
        client.postRaw("ConsoleService", "Connect", GlassOsWire.EMPTY_FRAME, COMMAND_TIMEOUT_S)
            ?.let { raw ->
                val fields = GlassOsWire.parse(raw)
                interpretConnectionResult(fields)
                    ?: null.also { Log.w(TAG, "ConsoleService/Connect: ${fields.errorDetail()}") }
            }

    /**
     * The console's own workout state, as a raw `WorkoutState` enum number.
     *
     * A read, not a command, but it lives here because it is only ever needed to explain why a
     * command was refused: `StartNewWorkout` is only valid from some states, and knowing which one
     * the machine is actually in turns "refused" into something a rider can act on.
     */
    /**
     * The speed quick-picks the console publishes, converted to mph.
     *
     * Null on transport failure so the caller retries; an empty list is a real answer meaning this
     * machine publishes none. `ControlType.MPS` filters out entries of other kinds that share the
     * list — the console mixes them.
     */
    override fun speedPresetsMph(): List<Double>? =
        client.controls("SpeedService")
            ?.let { shapePresets(it, GlassOsClient.ControlType.MPS) { v -> v * MachineLink.MPS_TO_MPH } }

    /**
     * The incline quick picks the console publishes.
     *
     * [spacing] is ignored, and that is the answer rather than an omission. These are the buttons
     * the console's own designers chose and published; there is nothing here for Stride to re-space,
     * and no range to re-space it over — [limits] is null on this transport by design. Re-deriving
     * them at 5% would be Stride overruling the machine about its own controls using numbers the
     * machine never gave it.
     */
    override fun inclinePresets(spacing: InclineSpacing): List<Double>? =
        client.controls("InclineService")
            ?.let { shapePresets(it, GlassOsClient.ControlType.INCLINE) { v -> v } }

    /**
     * Always null: GlassOS has no call that reports the machine's own range.
     *
     * Not an omission to be fixed later by guessing from the preset list. The presets are the
     * buttons the console chose to show, not the limits it will accept, and treating the largest
     * button as a ceiling would silently narrow what a rider can ask for. Null here means Stride's
     * own fixed ceiling stands alone, which is the honest answer.
     */
    override fun limits(): MachineLimits? = null

    override fun workoutState(): Int? =
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

/**
 * Read the `ConsoleState` out of a `ConnectionResult`.
 *
 * `ConnectionResult` is a oneof: field 1 an `IFitError`, field 2 the state. Null means the console
 * refused to attach, and is deliberately distinct from returning DISCONNECTED — one is "the
 * handshake failed", the other is "the handshake worked and the answer is that nothing is
 * attached", and a retry loop that confused the two would either give up or spin.
 *
 * The zero case is the trap this encodes: DISCONNECTED is 0, proto3 omits zero, so a successful
 * Connect against a machineless console is an *empty* message. Absent therefore means DISCONNECTED
 * here, not "no answer".
 *
 * Pure, and separate from [GlassOsCommands], so the decode can be tested against bytes captured
 * from a real console without one attached.
 */
internal fun interpretConnectionResult(fields: GlassOsWire.Fields): Int? {
    // Field 1 present is the error arm. Nothing is logged here: this stays pure so it can be
    // tested against real captured bytes, and the caller has the context worth logging anyway.
    if (fields.hasField(1)) return null
    return fields.enum(2) ?: GlassOsClient.ConsoleState.DISCONNECTED
}
