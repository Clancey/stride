package io.stride.spikes

import android.util.Log
import java.io.Closeable

/**
 * The direct path: Stride talking to the treadmill's motor controller itself, with iFit's software
 * out of the loop.
 *
 * Three pieces live here.
 *
 * - [DirectMachineSession] owns the wire. It is the only thing that touches [FitProTransport], and
 *   it serialises access, because the protocol is request/response with no request identifiers —
 *   two callers exchanging at once would read each other's replies and neither would know.
 * - [DirectMachineClient] reads telemetry, shaped as a [GlassOsClient.Snapshot] so that everything
 *   above it is transport-blind.
 * - [DirectMachineCommands] implements [MachineCommands] by writing registers.
 *
 * ## What this path can do that GlassOS cannot
 *
 * The console exposes its own limits (`MAX_KPH`, `MIN_KPH`, `MAX_GRADE`, `MIN_GRADE`), which the
 * gRPC surface never publishes — see [MachineLimits]. It also accepts several writes in a single
 * frame, which is why [DirectMachineCommands.stop] can zero the speed and idle the workout
 * atomically instead of hoping both of two round-trips land.
 *
 * ## What it deliberately does not do
 *
 * No clamping, no ramping, no rate limiting. Those are [MachineCoordinator]'s, and duplicating them
 * here would split the safety rules across two files and make it ambiguous which is authoritative.
 * The single exception is the [FitProProbe] gate, which is not a limit on the rider's value but a
 * statement that the bytes would be meaningless.
 */
class DirectMachineSession(
    private val transport: FitProTransport,
) : Closeable {

    val probe = FitProProbe()

    /**
     * Serialises wire access. See the class note: FitPro replies carry no correlation id, so an
     * interleaved exchange would hand a telemetry poll the answer to a speed write.
     */
    private val wire = Any()

    /**
     * What the machine told us about itself during [connect], including which registers it
     * implements. Null until the handshake succeeds.
     */
    @Volatile
    var deviceInfo: FitProCodec.DeviceInfo? = null
        private set

    /** The command types the machine advertised. Empty until [connect] succeeds. */
    @Volatile
    var supportedCommands: Set<FitProCodec.Command> = emptySet()
        private set

    /**
     * The address every frame is sent to, learned from the handshake.
     *
     * Starts at [FitProCodec.ADDRESS_MAIN] because that is the only address knowable before the
     * machine has spoken, and it is where GlassOS begins (`xh/n0.F()` sends `DEVICE_INFO` to
     * `yh.a.MAIN`).
     *
     * It is worth being precise about why this is not [FitProCodec.ADDRESS_TREADMILL]. GlassOS's
     * `DEVICE_INFO` parser builds its device record from `this.f16654a` — the address it *asked* —
     * not from anything in the reply (`vh/e.a`, case 0), and the register command then takes its
     * address from that same record (`vh/f`'s `super(bVar.f18899a)`). So iFit reads and writes
     * registers on MAIN, and a hardcoded treadmill address would have been talking to a device that
     * may not answer at all.
     */
    @Volatile
    var address: Int = FitProCodec.ADDRESS_MAIN
        private set

    /**
     * Which register this console's fan actually answers on, once we've found out.
     *
     * `ai/c.java` binds GlassOS's `FAN_STATE` metric to field **98**, while field 8 (`FAN_SPEED`)
     * carries no metric binding at all — strong evidence that 98 is the modern register and 8 is
     * legacy. Where the handshake tells us which one the machine implements we use that; where it
     * does not, the first fan write tries 98, falls back to 8, and remembers the answer.
     */
    @Volatile
    var fanRegister: FitProCodec.Register? = null
        private set

    /**
     * Whether this console accepted `AUTO` as a fan state, once we have actually tried it.
     *
     * Null until the question has been settled by a write. There is deliberately no inference behind
     * this: GlassOS answers the same question from `IsAutoFanStateSupported`, which it resolves from
     * a per-console configuration blob (`FanFeature`, with its own `AutoFanSupported` field, in
     * `ak/k0`) rather than from anything the machine says over the wire. No FitPro register carries
     * it. So a console can implement a fan register and still have no automatic mode, and the
     * presence of that register — which is what this used to be inferred from — proves nothing.
     *
     * Asking the machine is the only honest way to find out, and the cost of asking is a fan command
     * that gets refused.
     */
    @Volatile
    var autoFanAccepted: Boolean? = null
        private set

    val transportName: String get() = transport.name

    /** Whether the wire is still up. False once the cable is unplugged or the radio drops. */
    val connected: Boolean get() = transport.connected

    /** Whether the machine said it implements [register]. Null when the handshake hasn't run. */
    fun supports(register: FitProCodec.Register): Boolean? = deviceInfo?.supports(register)

    /**
     * Whether the machine said it implements a rider-facing control. Null when unknown.
     *
     * The fan is asked differently from the others: which register carries it varies by machine, so
     * [fanRegister] holds whichever one the handshake found and "no fan" means neither candidate was
     * listed. Speed and incline have one register each and are asked directly.
     */
    fun supports(control: MachineLink.Control): Boolean? = when (control) {
        MachineLink.Control.SPEED -> supports(FitProCodec.Register.KPH)
        MachineLink.Control.INCLINE -> supports(FitProCodec.Register.GRADE)
        MachineLink.Control.FAN -> if (deviceInfo == null) null else fanRegister != null
    }

    /**
     * Run one read/write exchange. Returns null when the machine said nothing usable.
     *
     * Blocking; call off the main thread.
     */
    fun exchange(
        writes: List<FitProCodec.Write> = emptyList(),
        reads: List<FitProCodec.Register> = emptyList(),
    ): FitProCodec.Response? = synchronized(wire) {
        val body = FitProCodec.readWriteBody(writes, reads)
        val reply = transport.exchange(FitProCodec.frame(body, address = address)) ?: return null
        FitProCodec.parseResponse(reply, reads, expectAddress = address)
    }

    /**
     * Authorise and send one write without letting go of the wire in between.
     *
     * This exists instead of "check, then call [exchange]" because those are two operations and the
     * gap between them is real: [connect] can reset the probe and re-point [address] at a different
     * device from another thread, so a command authorised against the old session could be
     * transmitted to the new one. `@Volatile` publishes the change, it does not order it against a
     * decision already taken. Holding [wire] across the whole decision closes that window.
     *
     * [gate] names the registers whose absence blocks the command; see [DirectMachineCommands.write]
     * for why that is not simply every register in the frame.
     */
    fun authorisedWrite(
        label: String,
        gate: List<FitProCodec.Register>,
        build: (DirectMachineSession) -> List<FitProCodec.Write>,
    ): MachineAck = synchronized(wire) {
        probe.refusalReason()?.let { return MachineAck.Refused(it) }

        val info = deviceInfo
        val missing = gate.filter { info != null && !info.supports(it) }
        if (missing.isNotEmpty()) {
            val names = missing.joinToString(" or ") { it.name.lowercase().replace('_', ' ') }
            return MachineAck.Refused("This machine doesn't support $names over the direct link.")
        }

        val writes = try {
            build(this)
        } catch (e: IllegalArgumentException) {
            // A programming error, not a machine refusal. Surfacing it as a transport failure would
            // invite a retry of something that cannot ever encode.
            Log.e(TAG, "refusing to encode $label", e)
            return MachineAck.Refused(e.message ?: "value could not be encoded")
        }
        if (writes.isEmpty()) return MachineAck.Refused("nothing to write for $label")

        val reply = try {
            val body = FitProCodec.readWriteBody(writes, emptyList())
            transport.exchange(FitProCodec.frame(body, address = address))
        } catch (t: Throwable) {
            return MachineAck.NoAnswer("$label failed: ${t.message}")
        } ?: return MachineAck.NoAnswer("the machine didn't answer the $label command")

        val response = FitProCodec.parseResponse(reply, emptyList(), expectAddress = address)
            ?: return MachineAck.NoAnswer("the $label reply was malformed")

        // Telemetry tolerates a bad checksum because GlassOS does and a wrong number is visibly
        // wrong. An acknowledgement is different: it is the evidence that a command that moves a
        // belt landed, and a corrupted frame is not evidence of anything.
        if (!response.checksumValid) {
            return MachineAck.NoAnswer("the $label reply failed its checksum")
        }
        return if (response.accepted) {
            MachineAck.Ok
        } else {
            MachineAck.Refused(response.status.name.lowercase().replace('_', ' '))
        }
    }

    /**
     * Perform the startup handshake, in the order GlassOS performs it.
     *
     * `xh/n0.F()` is the model: start the link, ask `DEVICE_INFO`, keep the resulting device record
     * as the address for everything afterwards, then batch `SYSTEM_INFO`, `VERSION_INFO` and
     * `SERIAL_NUMBER`, and separately (`n0.n0`) `SUPPORTED_DEVICES` and `SUPPORTED_COMMANDS`.
     *
     * Only `DEVICE_INFO` is load-bearing for us — it carries the address and the supported-register
     * mask. The rest are sent anyway, and their failures tolerated, because matching iFit's opening
     * exchange is cheap and a console that expects to be greeted a particular way is a real class of
     * embedded-firmware behaviour that costs nothing to accommodate.
     *
     * Blocking; call off the main thread.
     */
    fun connect(reference: FitProProbe.Reference? = null): ConnectResult = synchronized(wire) {
        lastConnect = null
        deviceInfo = null
        supportedCommands = emptySet()
        fanRegister = null
        probe.reset()

        val info = handshake() ?: return ConnectResult(
            deviceInfo = null,
            supportedCommands = emptySet(),
            probe = FitProProbe.Result(FitProProbe.Stage.UNCONFIRMED, "no device answered"),
            detail = "No FitPro device answered on ${transport.name}.",
        ).also { lastConnect = it }

        deviceInfo = info
        address = info.address
        fanRegister = FAN_REGISTERS.firstOrNull { info.supports(it) }

        // Informational, and deliberately unchecked: a console that declines to introduce itself is
        // still a console we can read registers from.
        for (command in listOf(
            FitProCodec.Command.SYSTEM_INFO,
            FitProCodec.Command.VERSION_INFO,
            FitProCodec.Command.SERIAL_NUMBER,
        )) {
            runCatching { transport.exchange(FitProCodec.commandFrame(command, address)) }
                .onFailure { Log.w(TAG, "${command.name} failed", it) }
        }

        supportedCommands = runCatching {
            transport.exchange(FitProCodec.commandFrame(FitProCodec.Command.SUPPORTED_COMMANDS, address))
                ?.let(FitProCodec::parseSupportedCommands)
                .orEmpty()
        }.getOrDefault(emptySet())

        // The probe reads registers too, so it needs the same supported-register filter — see
        // FitProProbe.attempt. deviceInfo is set above, so `supports` can answer by now.
        val probeResult = probe.confirm(transport, reference, address) { info.supports(it) }
        return ConnectResult(
            deviceInfo = info,
            supportedCommands = supportedCommands,
            probe = probeResult,
            detail = describe(info, probeResult),
        ).also { lastConnect = it }
    }

    /**
     * The outcome of the most recent [connect], so callers do not have to keep their own copy.
     *
     * They used to: [MachineLink] cached the detail string at the one place it ran the handshake.
     * That was correct exactly once. The handshake is now also re-run from
     * [DirectMachineCommands.connect] whenever the link has dropped and come back, and a cached
     * copy would still be describing the session that failed — telling the rider the treadmill
     * never answered while it is answering.
     */
    @Volatile
    var lastConnect: ConnectResult? = null
        private set

    /**
     * Ask `DEVICE_INFO` at each candidate address until one answers.
     *
     * [FitProCodec.ADDRESS_MAIN] first, matching GlassOS. [FitProCodec.ADDRESS_TREADMILL] is tried
     * second for the case this codebase cannot rule out: a link wired straight to the motor
     * controller rather than through the console, where MAIN may be nobody.
     */
    private fun handshake(): FitProCodec.DeviceInfo? {
        for (candidate in listOf(FitProCodec.ADDRESS_MAIN, FitProCodec.ADDRESS_TREADMILL)) {
            val reply = runCatching {
                transport.exchange(FitProCodec.commandFrame(FitProCodec.Command.DEVICE_INFO, candidate))
            }.getOrNull() ?: continue

            if (FitProCodec.statusOf(reply) != FitProCodec.Status.DONE) {
                Log.i(TAG, "address $candidate answered ${FitProCodec.statusOf(reply)} to DEVICE_INFO")
                continue
            }
            // The address we asked, not the one in the reply: see [address].
            val info = FitProCodec.parseDeviceInfo(reply)?.copy(address = candidate) ?: continue
            Log.i(
                TAG,
                "device at $candidate: ${info.brand} serial ${info.serialNumber} " +
                    "sw ${info.softwareVersion} hw ${info.hardwareVersion}, " +
                    "${info.supportedFieldIds.size} registers" +
                    if (info.requiresSecurity) " (software > ${FitProCodec.SECURITY_REQUIRED_ABOVE}: may demand VERIFY_SECURITY)" else "",
            )
            return info
        }
        return null
    }

    private fun describe(info: FitProCodec.DeviceInfo, probeResult: FitProProbe.Result): String {
        val brand = info.brand.name.lowercase().replace('_', ' ')
        val missing = REQUIRED_FOR_CONTROL.filterNot { info.supports(it) }
        val caveat = when {
            missing.isEmpty() -> ""
            else -> " It doesn't offer ${missing.joinToString(" or ") { it.name.lowercase() }}."
        }
        // Only worth saying when the probe could not get a write accepted: on a console that took
        // our writes, the security version is trivia, but on one that refused them it is the answer.
        val security = when {
            info.requiresSecurity && probeResult.stage == FitProProbe.Stage.UNCONFIRMED ->
                " Its software version (${info.softwareVersion}) is one that asks for a security" +
                    " handshake Stride can't perform, which may be why."
            else -> ""
        }
        return "Found a $brand machine on ${transport.name}. ${probeResult.detail}.$caveat$security"
    }

    /**
     * Write [state] to whichever fan register this console honours, discovering it if needed.
     *
     * Returns the response for the attempt that was accepted, or the last failure.
     */
    fun writeFan(state: FitProCodec.FanState): MachineAck = synchronized(wire) {
        probe.refusalReason()?.let { return MachineAck.Refused(it) }

        val known = fanRegister
        val info = deviceInfo
        val candidates = when {
            // Already established which register this console answers on.
            known != null -> listOf(known)
            // The handshake told us; only try what the machine claims to implement.
            info != null -> FAN_REGISTERS.filter(info::supports)
            // No handshake: try both, newest first.
            else -> FAN_REGISTERS
        }
        if (candidates.isEmpty()) {
            return MachineAck.Refused("This machine doesn't have a controllable fan.")
        }

        var last: MachineAck = MachineAck.NoAnswer("the machine didn't answer the fan command")
        for (register in candidates) {
            // The gate list is empty because the candidate list above has already done the
            // supported-register filtering, and more precisely than a generic gate could.
            val ack = authorisedWrite("fan", emptyList()) {
                listOf(FitProCodec.writeOf(register, FitProCodec.encodeFanState(state)))
            }
            if (ack is MachineAck.Ok) {
                if (register != known) {
                    Log.i(TAG, "fan answers on ${register.name} (field ${register.fieldId})")
                    fanRegister = register
                }
                if (state == FitProCodec.FanState.AUTO) autoFanAccepted = true
                return ack
            }
            last = ack
        }
        // Only an explicit refusal answers the question. A silent link says nothing about whether
        // this console has an automatic mode, and recording false for it would permanently disable
        // a feature the machine may well have.
        if (state == FitProCodec.FanState.AUTO && last is MachineAck.Refused) autoFanAccepted = false
        return last
    }

    /**
     * Forget the handshake so the next [connect] performs it again, without closing the transport.
     *
     * Distinct from [close]: the device is still there and still open, we have simply stopped
     * believing what it told us. Used when the link is enumerated but has gone silent — a console
     * that slept behind a live USB connection, say — where the only route back is a fresh
     * handshake, and where leaving [deviceInfo] set would mean never attempting one.
     */
    fun invalidateHandshake() {
        synchronized(wire) {
            lastConnect = null
            probe.reset()
            deviceInfo = null
            supportedCommands = emptySet()
            fanRegister = null
            address = FitProCodec.ADDRESS_MAIN
        }
    }

    override fun close() {
        synchronized(wire) {
            lastConnect = null
            probe.reset()
            deviceInfo = null
            supportedCommands = emptySet()
            fanRegister = null
            address = FitProCodec.ADDRESS_MAIN
            runCatching { transport.close() }
                .onFailure { Log.w(TAG, "closing direct transport failed", it) }
        }
    }

    /** What [connect] found, for diagnostics and for the settings screen's copy. */
    data class ConnectResult(
        val deviceInfo: FitProCodec.DeviceInfo?,
        val supportedCommands: Set<FitProCodec.Command>,
        val probe: FitProProbe.Result,
        val detail: String,
    ) {
        val connected: Boolean get() = deviceInfo != null
    }

    companion object {
        const val TAG = "DirectMachine"

        /** Preferred first: see [fanRegister]. */
        val FAN_REGISTERS = listOf(FitProCodec.Register.FAN_STATE, FitProCodec.Register.FAN_SPEED)

        /** Registers a treadmill must implement for Stride to drive it. */
        val REQUIRED_FOR_CONTROL = listOf(FitProCodec.Register.KPH, FitProCodec.Register.GRADE)
    }
}

/**
 * Unit conversion and enum translation between the FitPro wire and the rest of Stride.
 *
 * Kept in one object because every one of these is a place where a silent mistake produces a
 * plausible-looking wrong number rather than an error.
 */
object FitProValues {

    const val KPH_PER_MPH = FitProProbe.KPH_PER_MPH
    const val METRES_PER_MILE = 1609.344

    fun kphToMph(kph: Double): Double = kph / KPH_PER_MPH

    fun mphToKph(mph: Double): Double = mph * KPH_PER_MPH

    /**
     * Distance registers are 4-byte little-endian integers of **metres**.
     *
     * Inferred, not stated: every distance register (`CURRENT_DISTANCE`, `DISTANCE`,
     * `ACTUAL_DISTANCE`, `MOTOR_TOTAL_DISTANCE`) shares the serializer `uh/c` with
     * `BELT_TOTAL_METERS`, which names its unit, and nothing between the register read and the
     * published metric applies a scale factor. If this is ever wrong it will be wrong by a factor of
     * ten or a thousand, which is exactly what [FitProProbe]'s cross-check against GlassOS surfaces.
     */
    fun metresToMiles(metres: Int): Double = metres / METRES_PER_MILE

    /** Minutes per mile at [mph], or null when stopped — dividing by zero would render as "∞". */
    fun paceMinPerMile(mph: Double): Double? = if (mph > 0.1) 60.0 / mph else null

    /**
     * Translates FitPro's [FitProCodec.WorkoutMode] to the console-state *names* that
     * [GlassOsClient.ConsoleState] produces.
     *
     * The names matter: [GlassOsClient.ConsoleState.beltMayBeMoving] matches on them, and it is what
     * [MachineLink] uses to decide whether the belt might be under power. Returning a FitPro-flavoured
     * name here would make that check quietly answer "no" on the direct path.
     */
    fun consoleStateName(mode: FitProCodec.WorkoutMode): String = when (mode) {
        FitProCodec.WorkoutMode.IDLE -> "IDLE"
        FitProCodec.WorkoutMode.RUNNING -> "WORKOUT"
        FitProCodec.WorkoutMode.PAUSE, FitProCodec.WorkoutMode.PAUSE_OVERRIDE -> "PAUSED"
        FitProCodec.WorkoutMode.RESULTS -> "WORKOUT_RESULTS"
        FitProCodec.WorkoutMode.WARM_UP -> "WARM_UP"
        FitProCodec.WorkoutMode.COOL_DOWN -> "COOL_DOWN"
        FitProCodec.WorkoutMode.RESUME -> "RESUME"
        FitProCodec.WorkoutMode.SLEEP -> "SLEEP"
        FitProCodec.WorkoutMode.LOCKED -> "LOCKED"
        FitProCodec.WorkoutMode.DEMO -> "DEMO"
        FitProCodec.WorkoutMode.UNKNOWN -> "CONSOLE_STATE_UNKNOWN"
        else -> "CONSOLE_STATE_UNKNOWN"
    }

    /**
     * The [GlassOsClient.ConsoleState] *number* for a FitPro workout mode.
     *
     * Routed through [consoleStateName] and [GlassOsClient.ConsoleState.code] rather than written as
     * a second `when`, so the name-based callers and the number-based callers can never be given two
     * different answers for the same machine.
     */
    fun consoleState(mode: FitProCodec.WorkoutMode): Int? =
        GlassOsClient.ConsoleState.code(consoleStateName(mode))

    /**
     * Translates to the `GlassOsCommands.WORKOUT_*` numbering that [MachineCoordinator] compares
     * against.
     *
     * The two enums disagree exactly where it is most dangerous: FitPro puts RUNNING at 2, GlassOS
     * puts it at 3 — which is FitPro's PAUSE. An implementation that returned its raw value would
     * make the coordinator believe a running machine was paused.
     */
    fun glassOsWorkoutState(mode: FitProCodec.WorkoutMode): Int? = when (mode) {
        FitProCodec.WorkoutMode.IDLE -> GlassOsCommands.WORKOUT_IDLE
        FitProCodec.WorkoutMode.RUNNING,
        FitProCodec.WorkoutMode.WARM_UP,
        FitProCodec.WorkoutMode.COOL_DOWN,
        FitProCodec.WorkoutMode.RESUME,
        -> GlassOsCommands.WORKOUT_RUNNING
        FitProCodec.WorkoutMode.PAUSE, FitProCodec.WorkoutMode.PAUSE_OVERRIDE -> GlassOsCommands.WORKOUT_PAUSED
        FitProCodec.WorkoutMode.RESULTS -> GlassOsCommands.WORKOUT_RESULTS
        else -> null
    }

    /** Converts a `GlassOsCommands.FAN_*` value to the wire enum. Unknown values become OFF. */
    fun fanStateFromGlassOs(state: Int): FitProCodec.FanState = when (state) {
        GlassOsCommands.FAN_OFF -> FitProCodec.FanState.OFF
        GlassOsCommands.FAN_LOW -> FitProCodec.FanState.LOW
        GlassOsCommands.FAN_MEDIUM -> FitProCodec.FanState.MEDIUM
        GlassOsCommands.FAN_HIGH -> FitProCodec.FanState.HIGH
        GlassOsCommands.FAN_AUTO -> FitProCodec.FanState.AUTO
        else -> FitProCodec.FanState.OFF
    }

    /**
     * The rider-facing fan level, 0..[MachineLink.FAN_MAX], from a raw `FAN_STATE` value.
     *
     * `AUTO` and `UNKNOWN` deliberately return null rather than a number. Auto is not a level — the
     * machine is choosing — and drawing it as "0" would tell the rider the fan is off while it is
     * about to spin up.
     */
    fun fanLevel(raw: ByteArray): Int? = when (val state = FitProCodec.decodeFanState(raw)) {
        FitProCodec.FanState.OFF,
        FitProCodec.FanState.LOW,
        FitProCodec.FanState.MEDIUM,
        FitProCodec.FanState.HIGH,
        -> state.value
        FitProCodec.FanState.AUTO, FitProCodec.FanState.UNKNOWN -> null
    }
}

/**
 * Telemetry over the direct path, shaped as a [GlassOsClient.Snapshot].
 *
 * Reusing the GlassOS snapshot type is deliberate: [MachineLink]'s freshness rules, staleness
 * handling and rider-facing sentences are written against it, and they are not transport-specific.
 * A parallel type would have meant a parallel set of safety rules.
 */
class DirectMachineClient(private val session: DirectMachineSession) {

    /**
     * Read everything in one exchange. Returns null when the machine did not answer usefully.
     *
     * One frame rather than several because the protocol allows it and because values read in a
     * single reply are consistent with each other — speed and workout state from two round-trips can
     * straddle a state change and describe a machine that never existed.
     */
    fun read(): GlassOsClient.Snapshot? {
        // Ask only for what this console said it implements.
        //
        // This is not an optimisation. A reply carries values packed contiguously with nothing to
        // say which register each came from, so `parseResponse` requires them to fill the frame
        // exactly — if the machine omits one register it does not have, every remaining value
        // decodes at the wrong offset and the whole response is rejected. One absent register
        // therefore costs the entire poll, not one field. On a treadmill without incline that is
        // all telemetry, permanently.
        //
        // `!= false` because before the handshake `supports` answers null for everything, and
        // dropping the whole list then would mean never reading anything. Unknown is not refusal;
        // only a console that positively said it lacks a register is excluded.
        val fan = session.fanRegister
        val reads = (if (fan == null) TELEMETRY else TELEMETRY + fan)
            .filter { session.supports(it) != false }
        if (reads.isEmpty()) return null
        val response = session.exchange(reads = reads) ?: return null
        if (!response.accepted) return null

        val mode = response.value(FitProCodec.Register.WORKOUT_MODE)
            ?.let(FitProCodec::decodeWorkoutMode)
            ?: FitProCodec.WorkoutMode.UNKNOWN
        val speedMph = response.value(FitProCodec.Register.ACTUAL_KPH)
            ?.let { FitProValues.kphToMph(FitProCodec.decodeSpeed(it)) }
        val distanceMiles = response.value(FitProCodec.Register.CURRENT_DISTANCE)
            ?.let { FitProValues.metresToMiles(FitProCodec.decodeInt(it)) }

        // The probe is what licenses writing, so it is also what licenses claiming a value is
        // writable. Reporting true before it has passed would put a control on screen that refuses.
        //
        // Past that, each control answers for itself from the machine's own supported-register mask,
        // which is the whole point of the handshake: the console — not our guesswork — says whether
        // incline and the fan exist. `!= false` keeps an unanswered handshake from disabling a
        // control, matching the GlassOS rule that unknown is not refusal; `writable` is already
        // false in that case, so this cannot fail open.
        val writable = session.probe.confirmed

        // Speed and incline are only accepted while a workout is live — that is why an incline pill
        // does nothing from an idle console, and reporting them writable there would put a control
        // on screen that silently refuses. The fan is not workout-gated.
        val duringWorkout = writable && mode == FitProCodec.WorkoutMode.RUNNING

        return GlassOsClient.Snapshot(
            consoleState = FitProValues.consoleStateName(mode),
            workoutId = workoutInstanceId(mode),
            speedMph = speedMph,
            inclinePercent = response.value(FitProCodec.Register.ACTUAL_INCLINE)
                ?.let(FitProCodec::decodeIncline),
            distanceMiles = distanceMiles,
            paceMinPerMile = speedMph?.let(FitProValues::paceMinPerMile),
            elapsedSeconds = response.value(FitProCodec.Register.RUNNING_TIME)
                ?.let { FitProCodec.decodeInt(it).toLong() },
            calories = response.value(FitProCodec.Register.CURRENT_CALORIES)
                ?.let { FitProCodec.decodeInt(it).toDouble() },
            speedWritable = duringWorkout && session.supports(FitProCodec.Register.KPH) != false,
            inclineWritable = duringWorkout && session.supports(FitProCodec.Register.GRADE) != false,
            fanWritable = writable && session.fanRegister != null,
            fanLevel = fan?.let { response.value(it) }?.let { FitProValues.fanLevel(it) },
        )
    }

    /**
     * A stand-in for GlassOS's `workoutID`, which the hardware does not have.
     *
     * ## What GlassOS's version actually is
     *
     * Every metric response carries a `workoutID` in field 1 — decompiled as the constructor
     * parameter `workoutInstanceID` on `xl.b`, the data point behind `GetSpeed`, `GetDistance`,
     * `GetElapsedTime` and the rest. `am.j` builds those two ways and the difference is the whole
     * meaning of the field: `p(workoutInstanceID, timeSeconds, value)` during a workout, and `q()`
     * — which passes `CoreConstants.EMPTY_STRING` and a zero time — when there is not one.
     *
     * So the field is not a value Stride displays. It is a discriminator: **non-empty exactly when a
     * workout instance exists.** That is what [GlassOsTelemetry.reading] uses it for, and it matters
     * because proto3 omits zeros — a missing speed inside a workout is a measured 0.0, and the same
     * missing speed outside one means nothing is measuring. Without this field those are identical
     * on the wire.
     *
     * ## Why the direct path needs an equivalent at all
     *
     * The register protocol has no such ambiguity — a register either answered with bytes or did
     * not — and it has no workout-instance register either; `sh/a` defines `WORKOUT_MODE` and
     * `START_REQUESTED` and nothing resembling an instance id. So this could have been left null,
     * and it was.
     *
     * Leaving it null is a parity gap even though nothing breaks today. `workoutId` is on the shared
     * [GlassOsClient.Snapshot], and the obvious reading of a null there is "no workout is running" —
     * which on the direct path would be a lie during every run. Any future caller that tests it, as
     * [GlassOsTelemetry] already does, would silently get the wrong answer on one transport only.
     * The transports must be answerable to the same questions.
     *
     * ## What this returns
     *
     * The same *meaning*, honestly synthesised rather than pretending to be iFit's identifier: a
     * non-empty token while the console has a live workout instance, null when it does not, and a
     * *new* token each time a workout begins — so a caller comparing two readings can tell a second
     * run from a continuation of the first, which is the other thing an instance id is for.
     *
     * RESULTS counts as still belonging to the instance: the totals on screen are that workout's,
     * and GlassOS keeps reporting them there too. IDLE, SLEEP, LOCKED and DEMO do not.
     *
     * ## Why UNKNOWN holds rather than clears
     *
     * [read] resolves a `WORKOUT_MODE` register that did not answer to `UNKNOWN`, so `UNKNOWN` is
     * not a console state — it is a failed read. Clearing on it would end the instance every time
     * one register dropped off a noisy link and mint a fresh token on the next good poll, turning a
     * single run into two as far as any caller comparing tokens is concerned. So the three cases are
     * distinct: a live mode mints or keeps, a mode that positively says no workout clears, and
     * `UNKNOWN` holds whatever we last knew. This is the same rule as `!= false` elsewhere here —
     * unknown is not refusal. It cannot fail open either: holding can only preserve an id we already
     * had evidence for, never invent one.
     */
    private fun workoutInstanceId(mode: FitProCodec.WorkoutMode): String? {
        synchronized(instanceLock) {
            when {
                mode in WORKOUT_MODES ->
                    // Counted rather than random so it is reproducible in a log and in a test. It
                    // only has to be distinct from the previous one, not globally unique.
                    if (workoutInstance == null) {
                        workoutInstance = "direct-${instanceCounter.incrementAndGet()}"
                    }
                mode == FitProCodec.WorkoutMode.UNKNOWN -> Unit
                else -> workoutInstance = null
            }
            return workoutInstance
        }
    }

    private val instanceLock = Any()
    private var workoutInstance: String? = null

    /** This machine's own limits, or null until [FitProProbe] has read them. */
    fun limits(): MachineLimits? = session.probe.limits

    private companion object {
        /**
         * One poll's worth of registers, all read-only.
         *
         * `WORKOUT_MODE` is writable but perfectly readable — the read block is a plain bitmask with
         * no read-only restriction (`vh/f.j`); only writes are checked (`th/a`).
         */
        val TELEMETRY: List<FitProCodec.Register> = listOf(
            FitProCodec.Register.CURRENT_DISTANCE,
            FitProCodec.Register.RUNNING_TIME,
            FitProCodec.Register.WORKOUT_MODE,
            FitProCodec.Register.ACTUAL_KPH,
            FitProCodec.Register.ACTUAL_INCLINE,
            FitProCodec.Register.CURRENT_CALORIES,
        )

        /**
         * The console states that mean a workout instance exists.
         *
         * RESULTS is included: the numbers on screen still belong to the workout that just ended,
         * and GlassOS keeps serving them there too, so a rider reading a final distance of zero
         * because we had already declared the instance over would be a regression against iFit.
         */
        val WORKOUT_MODES: Set<FitProCodec.WorkoutMode> = setOf(
            FitProCodec.WorkoutMode.RUNNING,
            FitProCodec.WorkoutMode.PAUSE,
            FitProCodec.WorkoutMode.PAUSE_OVERRIDE,
            FitProCodec.WorkoutMode.WARM_UP,
            FitProCodec.WorkoutMode.COOL_DOWN,
            FitProCodec.WorkoutMode.RESUME,
            FitProCodec.WorkoutMode.RESULTS,
        )

        /** Shared so two sessions in one process cannot mint the same instance id. */
        val instanceCounter = java.util.concurrent.atomic.AtomicLong(0)
    }
}

/**
 * Register writes that move the belt.
 *
 * Performs no validation of the rider's value — see [MachineCommands]. The one thing it refuses on
 * its own is a link [FitProProbe] has not confirmed, which is not a safety clamp but a statement
 * that we do not yet know the bytes would mean anything to this console.
 */
class DirectMachineCommands(private val session: DirectMachineSession) : MachineCommands {

    override val transportName: String get() = "Direct (${session.transportName})"

    override fun setSpeedKph(kph: Double): MachineAck =
        write("speed", FitProCodec.Register.KPH) {
            listOf(FitProCodec.writeOf(FitProCodec.Register.KPH, FitProCodec.encodeSpeed(kph)))
        }

    override fun setInclinePercent(percent: Double): MachineAck =
        write("incline", FitProCodec.Register.GRADE) {
            listOf(FitProCodec.writeOf(FitProCodec.Register.GRADE, FitProCodec.encodeIncline(percent)))
        }

    /**
     * Fan control, which is the one command whose register is genuinely uncertain across firmware —
     * hence [DirectMachineSession.writeFan]'s two-register discovery.
     */
    override fun setFanState(state: Int): MachineAck =
        runCatching { session.writeFan(FitProValues.fanStateFromGlassOs(state)) }
            .getOrElse { MachineAck.NoAnswer("fan write failed: ${it.message}") }

    /**
     * Start a workout — but only on a machine we could also stop.
     *
     * Gating start on `KPH` as well as `WORKOUT_MODE` looks redundant and is not. [stop] drives the
     * belt to zero via `KPH`; a console that implements the workout register but not the speed
     * register would therefore accept a start it has no way to undo. Refusing to arm a control whose
     * counterpart is missing is the difference between a treadmill and a trap.
     */
    override fun startWorkout(): MachineAck = setMode(FitProCodec.WorkoutMode.RUNNING, "start")

    override fun pause(): MachineAck = setMode(FitProCodec.WorkoutMode.PAUSE, "pause")

    /**
     * Resume from pause.
     *
     * Writes `RESUME` (13), not `RUNNING` (2), because that is what GlassOS writes. Its console-state
     * translation (`xh/n0.p0`) is a straight 1:1 map from its own `ConsoleState` to FitPro's
     * `WorkoutMode`, and `ConsoleState.RESUME` maps to `WorkoutMode.RESUME` like every other pair.
     *
     * The value is transient: GlassOS deliberately refuses to *publish* RESUME as a console state
     * (`vh/f.m` returns early for exactly this register/value pair), so a machine that has resumed
     * reports itself as RUNNING immediately afterwards. That is why the read side maps RESUME onto
     * running rather than treating it as a state of its own.
     *
     * Falls back to RUNNING if the console refuses RESUME. A refusal here is not fatal — it means
     * this machine has no distinct resume transition — and failing to restart a belt the rider has
     * asked to restart is a much worse outcome than sending the blunter value.
     */
    override fun resume(): MachineAck {
        val ack = setMode(FitProCodec.WorkoutMode.RESUME, "resume")
        if (ack !is MachineAck.Refused) return ack
        Log.i(TAG, "console refused RESUME; falling back to RUNNING")
        return setMode(FitProCodec.WorkoutMode.RUNNING, "resume")
    }

    /**
     * Stop the belt and end the session in a single frame.
     *
     * Both writes ride in one request because the protocol supports multiple writes per frame and
     * ordering them as two round-trips introduces a window where the workout has ended but the belt
     * is still commanded to a speed. The write block is emitted in ascending field order —
     * `KPH` (0) then `WORKOUT_MODE` (12) — by [FitProCodec.readWriteBody], not by this list.
     *
     * `WORKOUT_MODE` is included only when the machine implements it. Sending an unsupported field
     * risks the console rejecting the *whole* frame, which would take the `KPH = 0` down with it —
     * failing to stop because we also asked for something optional is not an acceptable trade.
     */
    override fun stop(): MachineAck =
        write("stop", FitProCodec.Register.KPH) { session ->
            buildList {
                add(FitProCodec.writeOf(FitProCodec.Register.KPH, FitProCodec.encodeSpeed(0.0)))
                if (session.supports(FitProCodec.Register.WORKOUT_MODE) != false) {
                    add(
                        FitProCodec.writeOf(
                            FitProCodec.Register.WORKOUT_MODE,
                            FitProCodec.encodeWorkoutMode(FitProCodec.WorkoutMode.IDLE),
                        ),
                    )
                }
            }
        }

    /**
     * Attach to the machine, and report the console state — the direct path's whole handshake.
     *
     * This is the method that makes DIRECT a real swap for GlassOS rather than a partial one. The
     * app asks one question through one interface, and nothing above this line has to know which
     * wire answered it.
     *
     * Three cases, in order:
     *
     * 1. **The transport is gone.** A cable pulled or a radio dropped is reported as `DISCONNECTED`,
     *    not as null. Null means "we could not ask"; this is a definite answer that there is nothing
     *    on the other end, and [MachineLink.connectNow] must treat it as a failed handshake rather
     *    than as a call it should retry immediately.
     * 2. **Never handshaken, or handshaken and lost.** Run the full [DirectMachineSession.connect]:
     *    address discovery, `DEVICE_INFO` for the supported-register mask, the informational reads,
     *    and the probe. Idempotent by construction — it resets its own state first — which is what
     *    lets this be called on a schedule.
     * 3. **Already attached.** Skip straight to reading the state. The handshake is not repeated
     *    just because someone asked again; a console that is up stays up, and re-running address
     *    discovery on every poll would put a burst of frames on the wire for no new information.
     *
     * A machine that answered the handshake but will not report a workout mode is reported as
     * `CONSOLE_STATE_UNKNOWN`, not as `DISCONNECTED`. It is attached — we are talking to it — we
     * simply do not know what it is doing, and saying "disconnected" would send the rider to
     * power-cycle a treadmill that is working.
     */
    override fun connect(): Int? {
        if (!session.connected) return GlassOsClient.ConsoleState.DISCONNECTED

        if (session.deviceInfo == null) {
            val result = runCatching { session.connect() }
                .onFailure { Log.w(TAG, "direct handshake failed", it) }
                .getOrNull()
                ?: return null
            if (!result.connected) return GlassOsClient.ConsoleState.DISCONNECTED
        }

        val mode = runCatching { readWorkoutMode() }.getOrNull() ?: ModeRead.Silent
        return when (mode) {
            // The console answered, just not with a mode we could use — it is present and talking,
            // so "unknown state" is the honest report.
            is ModeRead.AnsweredWithoutMode -> GlassOsClient.ConsoleState.code("CONSOLE_STATE_UNKNOWN")
            is ModeRead.Mode -> FitProValues.consoleState(mode.value)
            // Nothing came back at all. Reporting CONSOLE_STATE_UNKNOWN here would be read as
            // attached by MachineLink.connectNow, which treats anything that is not DISCONNECTED as
            // a live console — so a device that is still enumerated but has stopped answering (a
            // console asleep behind a live USB link, a BLE peer that dropped without a disconnect)
            // would read as attached forever. Worse, deviceInfo would stay non-null and the
            // handshake above would never run again, so it could never recover on its own.
            //
            // So: discard the handshake and report no answer. The next connect redoes the full
            // sequence, which is what actually re-establishes the link when the console wakes.
            ModeRead.Silent -> {
                Log.w(TAG, "direct link enumerated but not answering; dropping the handshake")
                session.invalidateHandshake()
                null
            }
        }
    }

    /**
     * The speeds this machine will accept, as a ladder built from its own reported range.
     *
     * GlassOS publishes a list the console's designers chose; FitPro publishes no such list, only
     * `MIN_KPH` and `MAX_KPH`. Rather than leave the direct path with no quick picks — which would
     * make switching transports visibly degrade the app, exactly what "100% swap" rules out — the
     * range is turned into the same kind of ladder the console would have shown.
     *
     * Whole mph steps, because that is what a rider reaches for, and because presets that read
     * 3.7 / 5.6 / 7.5 are the tell-tale of a unit conversion nobody meant to expose. The minimum is
     * included as-is when it is not itself a whole number, since a machine with a 0.5 mph floor
     * should still offer its floor.
     *
     * Null only when the machine never told us its range, so the caller retries rather than
     * concluding the machine has no presets.
     */
    override fun speedPresetsMph(): List<Double>? {
        val limits = session.probe.limits ?: return null
        return ladder(limits.minSpeedMph, limits.maxSpeedMph, step = 1.0)
    }

    /** As [speedPresetsMph], from `MIN_GRADE`/`MAX_GRADE`, in whole percent. */
    override fun inclinePresets(): List<Double>? {
        val limits = session.probe.limits ?: return null
        return ladder(limits.minInclinePercent, limits.maxInclinePercent, step = 1.0)
    }

    override fun limits(): MachineLimits? = session.probe.limits

    override fun workoutState(): Int? {
        val response = session.exchange(reads = listOf(FitProCodec.Register.WORKOUT_MODE)) ?: return null
        if (!response.accepted) return null
        val mode = response.value(FitProCodec.Register.WORKOUT_MODE)
            ?.let(FitProCodec::decodeWorkoutMode)
            ?: return null
        return FitProValues.glassOsWorkoutState(mode)
    }

    /**
     * Whether the console can match fan speed to effort itself.
     *
     * Null until a fan write has actually settled it — see [DirectMachineSession.autoFanAccepted]
     * for why this cannot be inferred from the handshake. Returning null rather than a guess is what
     * makes [MachineCoordinator.restoreFan] willing to try Auto once and believe the answer.
     */
    override fun autoFanSupported(): Boolean? = session.autoFanAccepted

    /**
     * The three outcomes of a mode read, which [connect] must tell apart.
     *
     * Collapsing [Silent] into [AnsweredWithoutMode] is the bug this exists to prevent: one means
     * the link is dead, the other means the console is fine and did not send that register, and
     * they lead to opposite conclusions about whether we are attached.
     */
    private sealed interface ModeRead {
        /** No reply, or one that did not parse. The link is not answering. */
        data object Silent : ModeRead

        /** A reply arrived, but carried no usable mode. The console is there. */
        data object AnsweredWithoutMode : ModeRead

        data class Mode(val value: FitProCodec.WorkoutMode) : ModeRead
    }

    private fun readWorkoutMode(): ModeRead {
        // A reply of any status means something is on the other end and talking, even if it is
        // refusing; only a null is silence. `accepted` is about the answer, not about the link.
        val response = session.exchange(reads = listOf(FitProCodec.Register.WORKOUT_MODE))
            ?: return ModeRead.Silent
        if (!response.accepted) return ModeRead.AnsweredWithoutMode
        val mode = response.value(FitProCodec.Register.WORKOUT_MODE)?.let(FitProCodec::decodeWorkoutMode)
            ?: return ModeRead.AnsweredWithoutMode
        return ModeRead.Mode(mode)
    }

    private fun setMode(mode: FitProCodec.WorkoutMode, label: String): MachineAck =
        write(label, FitProCodec.Register.WORKOUT_MODE, FitProCodec.Register.KPH) {
            listOf(
                FitProCodec.writeOf(
                    FitProCodec.Register.WORKOUT_MODE,
                    FitProCodec.encodeWorkoutMode(mode),
                ),
            )
        }

    /**
     * The shared shape of every write: refuse if unconfirmed or unsupported, encode, exchange,
     * translate.
     *
     * [gate] names the registers whose absence should block the command. It is not simply "every
     * register in the frame": [stop] writes both `KPH` and `WORKOUT_MODE` but gates only on `KPH`,
     * because a machine that cannot be moved to idle must still be able to be commanded to zero.
     *
     * [build] is a lambda so encoding happens *after* the gates — there is no point constructing
     * bytes we have already decided not to send, and [FitProCodec.writeOf] throws on programming
     * errors, which should not surface as a transport failure.
     */
    private inline fun write(
        label: String,
        vararg gate: FitProCodec.Register,
        noinline build: (DirectMachineSession) -> List<FitProCodec.Write>,
    ): MachineAck = session.authorisedWrite(label, gate.toList(), build)

    private companion object {
        const val TAG = "DirectMachine"

        /**
         * Whole-[step] values within a range, highest first, with both ends always present.
         *
         * Descending to match the GlassOS preset order, which the UI lays out top-down.
         *
         * The two ends are included explicitly rather than left to the step arithmetic, because a
         * range rarely lands on step boundaries and both ends matter more than the middle: the
         * fastest speed a machine offers is the one riders reach for, and the slowest is the one
         * they need to walk. A pure step walk from the floor drops the maximum whenever the range
         * is not a whole number of steps, and produces *nothing at all* for a range too narrow to
         * contain a step — a 2.5 to 2.7 incline would leave a rider with no buttons.
         *
         * Ends are rounded inward to one decimal, never outward: a button that asks for slightly
         * less than the machine's minimum is a button that gets clamped or refused, which looks
         * like a broken control rather than a rounded one.
         *
         * The cap exists because these bounds come off a wire: a machine that reports a 0-3000
         * range through a decoding error would otherwise hand the UI three thousand buttons.
         */
        fun ladder(min: Double, max: Double, step: Double): List<Double> {
            if (!min.isFinite() || !max.isFinite() || max < min) return emptyList()
            if (!step.isFinite() || step <= 0.0) return emptyList()

            val floor = ceil1(min)
            val ceiling = floor1(max)
            // Rounding inward can cross the bounds over on a range narrower than 0.1; there is no
            // honest button to offer in that case, so offer the one value both ends agree on.
            // Coerced because rounding a sub-0.1 range can land outside it (min 2.55, max 2.57
            // rounds to 2.6) and a preset the machine would refuse is worse than an ugly label.
            if (ceiling < floor) return listOf(round1(min).coerceIn(min, max))

            val out = sortedSetOf<Double>(reverseOrder())
            out += floor
            out += ceiling
            var v = kotlin.math.ceil(min / step) * step
            // Both ends are already in the set, so they survive the cap regardless of where it
            // bites — a truncated ladder that has lost its extremes is worse than one that has
            // lost part of its middle.
            while (v <= max + 1e-9 && out.size < MAX_PRESETS) {
                val rounded = round1(v)
                if (rounded in floor..ceiling) out += rounded
                v += step
            }
            return out.toList()
        }

        const val MAX_PRESETS = 40

        fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

        /** One decimal place, never rounding below [v] — used for a range's lower bound. */
        fun ceil1(v: Double): Double = kotlin.math.ceil(v * 10.0 - 1e-9) / 10.0

        /** One decimal place, never rounding above [v] — used for a range's upper bound. */
        fun floor1(v: Double): Double = kotlin.math.floor(v * 10.0 + 1e-9) / 10.0
    }
}
