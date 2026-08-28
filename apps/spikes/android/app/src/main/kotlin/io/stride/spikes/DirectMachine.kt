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
     * The address this console's own replies are actually stamped with, learned from `DEVICE_INFO`'s
     * reply rather than assumed to equal [address].
     *
     * [handshake] deliberately keeps [address] pinned to the address asked, matching GlassOS
     * (see [address]'s note) — outgoing frames are unaffected by this field. What it buys is
     * **peer validation** on the way back in: [FitProCodec.parseResponse] rejects a reply whose
     * address byte is not this one, so a frame from a device that is not the console we handshook
     * with cannot be read as that console's answer. It is worth being exact about what that is not.
     * It is not request/reply correlation: two answers from the *same* console to two successive
     * `READ_WRITE_DATA` frames carry an identical address byte and an identical command byte, so
     * this check cannot tell them apart — and on a console that stamps every reply the same way,
     * that is every frame it sends. Real correlation would need the transport to drain or quarantine
     * what it has not matched, which it does not do today.
     *
     * GlassOS-era hardware happens to echo the address it was asked, so validating against the
     * outgoing address was never seen to fail. This X22i (FitPro1, not GlassOS) does not: every
     * reply observed on real hardware — DEVICE_INFO, SYSTEM_INFO, VERSION_INFO, and register reads
     * alike — comes back stamped with the console's own bus address instead (5, when asked at
     * [FitProCodec.ADDRESS_MAIN]/2), which made every read and write after the handshake look like a
     * silent, unanswering machine. Null until `DEVICE_INFO` has answered once.
     */
    @Volatile
    var replyAddress: Int? = null
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
     * The speed, in mph, most recently *accepted* over this link — before [FitProCodec.encodeSpeed]
     * rounds it to the register's 0.01 kph resolution.
     *
     * Exists for [DirectMachineClient]'s issue #34 display fallback: a rider who asks for 1 mph gets
     * 1.609344 kph rounded to the nearest representable 1.61 kph, which reads back as 1.0004 mph and
     * paces a clean mile at 59:59 rather than 60:00 — not a bug, just the register's own quantization
     * made visible. [DirectMachineClient.read] uses this in place of that round-tripped value, but
     * only when the two agree to within one quantization step, which is what proves this accepted
     * write is what the register is actually holding rather than a stale memory of a different one.
     *
     * Set only from an accepted write ([authorisedWrite]), never a refused one — a refusal must not
     * overwrite what the console is still actually holding with the target it just declined.
     */
    @Volatile
    var lastRequestedSpeedMph: Double? = null
        private set

    /**
     * How far [initializeStartGate] got on this console, and therefore whether control is trustworthy.
     *
     * Read by [authorisedWrite] the same way [FitProProbe.refusalReason] is: a console whose start
     * gate was attempted and did not complete is one whose gate state we do not know, and issuing
     * speed or mode commands to it would be commanding a machine whose interlocks are in an
     * unknown configuration. [StartGate.NotApplicable] is the ordinary case and refuses nothing.
     */
    @Volatile
    var startGate: StartGate = StartGate.NotApplicable
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
     * Whether this session has cleared the console's security gate.
     *
     * [Unavailable] is deliberately distinct from [Refused]: the first means we never got the
     * inputs to compute the hash, the second means the console looked at our answer and said no.
     * They call for different responses from a human, and collapsing them into "locked" would
     * throw away the only clue about which one happened.
     */
    sealed interface SecurityState {
        /** Software version at or below [FitProCodec.SECURITY_REQUIRED_ABOVE]; no gate to clear. */
        data object NotRequired : SecurityState

        data object Unlocked : SecurityState

        /** The console demanded security and refused the answer we computed. */
        data class Refused(val reason: String) : SecurityState

        /** The console demanded security and we could not assemble the request. */
        data class Unavailable(val reason: String) : SecurityState
    }

    /**
     * How far [initializeStartGate] got with a FitPro1 console's start gate.
     *
     * Three states rather than a boolean because "we never needed to" and "we tried and cannot say"
     * are opposite answers for control: the first is every console Stride has ever driven, the
     * second is a machine whose interlocks are in a configuration nobody knows.
     */
    sealed interface StartGate {
        /**
         * Never attempted: not a FitPro1 console, or one that does not implement field 108.
         * The ordinary case, including every GlassOS-era console. Refuses nothing.
         */
        data object NotApplicable : StartGate

        /**
         * Every write this console needed was acknowledged.
         *
         * [idleModeLockoutCleared] is false on a console that implements field 108 but not field 95,
         * which is a complete initialization for that console rather than a partial one.
         */
        data class Ready(val idleModeLockoutCleared: Boolean) : StartGate

        /**
         * Attempted and did not complete, so the gate's state is unknown. [reason] is rider-visible
         * and is what [authorisedWrite] refuses control with.
         */
        data class Incomplete(val reason: String) : StartGate
    }

    /** The security outcome of the most recent [connect]. */
    @Volatile
    var security: SecurityState = SecurityState.NotRequired
        private set

    @Volatile
    private var systemInfo: FitProCodec.SystemInfo? = null

    @Volatile
    private var masterLibraryVersion: Int? = null

    /**
     * Clear the console's security gate.
     *
     * Consoles above [FitProCodec.SECURITY_REQUIRED_ABOVE] will not accept writes until they have
     * been shown a hash derived from three numbers they already reported — the serial number from
     * `DEVICE_INFO`, and the part and model numbers from `SYSTEM_INFO` — together with a key
     * derived from the master library version from `VERSION_INFO`. This mirrors FitPro1's own
     * `Unlock()`, which computes exactly this and enqueues exactly this command.
     *
     * All the inputs come from replies already in hand, so this cannot be attempted when any of
     * those replies is missing; that is [SecurityState.Unavailable] rather than a failure, because
     * the console is not at fault and a retry of the *handshake* is what would help.
     *
     * Caller must hold [wire].
     */
    private fun unlock(): SecurityState {
        val info = deviceInfo ?: return SecurityState.Unavailable("no device info")
        val system = systemInfo
            ?: return SecurityState.Unavailable("the machine didn't answer SYSTEM_INFO")
        val library = masterLibraryVersion
            ?: return SecurityState.Unavailable("the machine didn't answer VERSION_INFO")

        val hash = FitProCodec.calculateSecurityHash(
            serialNumber = info.serialNumber,
            partNumber = system.partNumber,
            modelNumber = system.model,
        )
        val reply = try {
            transport.exchange(
                FitProCodec.verifySecurityFrame(address, hash, library),
                FitProCodec.Command.VERIFY_SECURITY,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VERIFY_SECURITY failed", t)
            return SecurityState.Unavailable("the security exchange failed: ${t.message}")
        } ?: return SecurityState.Unavailable("the machine didn't answer VERIFY_SECURITY")

        val parsed = FitProCodec.parseSecurityInfo(reply)
            ?: return SecurityState.Unavailable("the VERIFY_SECURITY reply was malformed")
        return if (parsed.unlocked) {
            Log.i(TAG, "console unlocked (key ${parsed.unlockedKey})")
            SecurityState.Unlocked
        } else {
            val reason = parsed.status?.name?.lowercase()?.replace('_', ' ') ?: "no status"
            Log.w(TAG, "console refused VERIFY_SECURITY: $reason")
            SecurityState.Refused(reason)
        }
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
        FitProCodec.parseResponse(reply, reads, expectAddress = replyAddress ?: address)
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
        /**
         * Whether this command may go out while [startGate] is [StartGate.Incomplete].
         *
         * False for everything that asks the belt to move. True only for a stop: `PLAN.md` §3.1 has
         * stop preemption "*never* rate-limited or ramp-delayed" and §5.2 ramp-limits acceleration
         * "but **never on deceleration or stop**", and a start gate we could not finish initialising
         * is exactly the kind of thing a stop must not be queued behind. The asymmetry is safe in
         * the direction that matters: refusing a start on a console in an unknown state costs a
         * rider a workout, while refusing a stop on a belt that is already moving — a rider can
         * start one from the console's own panel — is the failure this project exists to avoid.
         *
         * Note this does not bypass [FitProProbe]'s refusal, which is a different question: that one
         * says we have not established the peer is the motor controller at all, and writing a stop
         * to a device that may not be a treadmill is not a stop.
         */
        startGateExempt: Boolean = false,
        build: (DirectMachineSession) -> List<FitProCodec.Write>,
    ): MachineAck = synchronized(wire) {
        probe.refusalReason()?.let { return MachineAck.Refused(it) }
        // Same shape of gate as the probe's, and for the same reason. The probe answers "is this
        // peer the machine we think it is"; this answers "are its interlocks in the state we think
        // they are". Both have to hold before a frame that asks a belt to move goes out.
        if (!startGateExempt) {
            (startGate as? StartGate.Incomplete)?.let { return MachineAck.Refused(it.reason) }
        }

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

        val body = FitProCodec.readWriteBody(writes, emptyList())
        val frame = FitProCodec.frame(body, address = address)

        fun send(): MachineAck {
            val reply = try {
                transport.exchange(frame)
            } catch (t: Throwable) {
                return MachineAck.NoAnswer("$label failed: ${t.message}")
            } ?: return MachineAck.NoAnswer("the machine didn't answer the $label command")

            val response = FitProCodec.parseResponse(reply, emptyList(), expectAddress = replyAddress ?: address)
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

        val first = send()
        // A console can drop its unlock mid-session — a power blip on the controller, or simply
        // more time than it keeps the grant for. iFit handles that by unlocking again and reissuing
        // the command rather than surfacing the refusal, and so does this. Retried exactly once:
        // if the second attempt is blocked too, the answer is not going to change by asking harder,
        // and this runs on the thread that moves the belt.
        if (first is MachineAck.Refused && first.detail == SECURITY_BLOCK_REASON) {
            Log.i(TAG, "$label was security-blocked; unlocking again")
            security = unlock()
            if (security is SecurityState.Unlocked) return send()
        }
        return first
    }

    /**
     * Records [lastRequestedSpeedMph]. Called by a KPH writer with its own pre-encoding mph value,
     * only once the write is confirmed accepted — never with the register's post-rounding echo,
     * which would just reintroduce the quantization this field exists to see past.
     */
    internal fun noteAcceptedSpeedMph(mph: Double) {
        lastRequestedSpeedMph = mph
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
        replyAddress = null
        supportedCommands = emptySet()
        fanRegister = null
        lastRequestedSpeedMph = null
        startGate = StartGate.NotApplicable
        probe.reset()
        forgetSecurity()

        val info = handshake() ?: return ConnectResult(
            deviceInfo = null,
            supportedCommands = emptySet(),
            probe = FitProProbe.Result(FitProProbe.Stage.UNCONFIRMED, "no device answered"),
            detail = handshakeFailureDetail(),
        ).also { lastConnect = it }

        deviceInfo = info
        address = info.address
        fanRegister = FAN_REGISTERS.firstOrNull { info.supports(it) }

        // SUPPORTED_COMMANDS comes first because iFit fetches the device tree immediately after
        // DEVICE_INFO and then sends each interrogation command only if the console advertised it
        // (`foreach … if (PrimaryDevice.SupportedCommands.Contains(item.Key))`). Asking a console
        // for something it just said it does not implement is exactly the kind of frame that gets
        // no reply.
        supportedCommands = runCatching {
            transport.exchange(
                FitProCodec.commandFrame(FitProCodec.Command.SUPPORTED_COMMANDS, address),
                FitProCodec.Command.SUPPORTED_COMMANDS,
            )
                ?.let(FitProCodec::parseSupportedCommands)
                .orEmpty()
        }.getOrDefault(emptySet())

        // Informational for the most part, but two of these carry the inputs the security handshake
        // is computed from, so their replies are kept rather than discarded. A console that
        // declines to introduce itself is still a console we can read registers from — unless it
        // also demands security, which is handled below.
        val replies = mutableMapOf<FitProCodec.Command, ByteArray>()
        for (command in listOf(
            FitProCodec.Command.SYSTEM_INFO,
            FitProCodec.Command.VERSION_INFO,
            FitProCodec.Command.SERIAL_NUMBER,
        )) {
            // An empty set means the console never told us, not that it supports nothing — gating
            // strictly on that would skip SYSTEM_INFO and so make the unlock below impossible.
            if (supportedCommands.isNotEmpty() && command !in supportedCommands) {
                Log.i(TAG, "${command.name} not advertised; skipping")
                continue
            }
            runCatching { transport.exchange(FitProCodec.commandFrame(command, address), command) }
                .onFailure { Log.w(TAG, "${command.name} failed", it) }
                .getOrNull()
                ?.let { replies[command] = it }
        }
        systemInfo = replies[FitProCodec.Command.SYSTEM_INFO]?.let(FitProCodec::parseSystemInfo)
        masterLibraryVersion = replies[FitProCodec.Command.VERSION_INFO]
            ?.let(FitProCodec::parseMasterLibraryVersion)

        // Order matters: unlock before the probe. The probe writes, and on a console that demands
        // security an unauthorised write is refused rather than ignored — so probing first would
        // diagnose a locked console as an unsupported one.
        security = if (info.requiresSecurity) unlock() else SecurityState.NotRequired

        // The probe reads registers too, so it needs the same supported-register filter — see
        // FitProProbe.attempt. deviceInfo is set above, so `supports` can answer by now.
        //
        // It also gets the address replies must come from, which until now it was the one
        // post-handshake exchange not to check — and it is the exchange that licenses writing at
        // all. `replyAddress` cannot be null here, because `handshake()` sets it on the path that
        // returns non-null; the fallback is written the same way as `exchange` and `authorisedWrite`
        // above so that there is one rule for reply identity in this class rather than two.
        val probeResult = probe.confirm(
            transport,
            reference,
            address,
            expectAddress = replyAddress ?: address,
        ) { info.supports(it) }

        // Only after the probe, because what follows are register *writes*. FitProProbe exists to
        // establish that this peer is the motor controller and "implements the same register table
        // as the console this protocol was recovered from" before anything writes to it — which is
        // precisely the assumption fields 108 and 95 rest on. This ran before probe.confirm when it
        // was first written, justified as being like the SYSTEM_INFO/VERSION_INFO exchanges above;
        // those are commandFrame interrogations that write no register, so they were never a
        // precedent for it. The probe is reads-only, so deferring past it costs one read round-trip
        // and leaves the post-unlock ordering these writes depend on otherwise intact.
        startGate = initializeStartGate(info)

        return ConnectResult(
            deviceInfo = info,
            supportedCommands = supportedCommands,
            probe = probeResult,
            startGate = startGate,
            detail = describe(info, probeResult),
        ).also { lastConnect = it }
    }

    /**
     * Reproduce `FitPro1Console`'s post-unlock initialization writes, which the X22i needs before it
     * will accept `WORKOUT_MODE = RUNNING` at all — see `DIRECT_MACHINE_PROTOCOL.md`'s "What is
     * still open" for the four start strategies that were ruled out before this one.
     *
     * `FitPro1Console.InitializeConsole` writes two fields, in this order:
     *
     * ```
     * SetRequireStartRequested(IsBitFieldSupported(RequireStartRequested));   // field 108
     * SetIdleModeLockout(!flag || !PrimaryDevice.Device.IsBeltBasedMachine()); // field 95
     * ```
     *
     * ## Why two frames rather than one
     *
     * Because one frame cannot express the order, and because iFit does not use one. Each setter
     * goes through `FitnessConsoleBase.SetValue`, which starts its own `SetValueAsync`, and
     * `FitPro1Console.SetValidatedValuesAsync` wraps that single value in its own `ReadWriteDataCmd`
     * — two commands, two frames, 108 then 95.
     *
     * The clinching detail is that `ReadWriteDataCmd` *does* sort ascending when several fields
     * share one command (`OrderBy((int)x.BitField)`), which is precisely what
     * [FitProCodec.registerBlock] does here. So batching these two would put field 95 on the wire
     * *ahead* of field 108 in either implementation — and iFit declines to batch them. This repo
     * already records the same trap for `KPH` (0) overtaking `WORKOUT_MODE` (12), which is why
     * [DirectMachineCommands.startWorkout] splits its frames too.
     *
     * The order is the safety-relevant part, not a fidelity nicety. Field 108 *arms* a gate ("this
     * console requires an explicit start request"); field 95 *removes* one. A single frame carries a
     * single status byte and no per-register acknowledgement, so a `FAILED`, a timeout or a lost
     * reply cannot distinguish "neither applied" from "only the lockout was cleared" — and that
     * second state is the least gated of the three.
     *
     * Stated precisely, because the loose version of this claim is wrong: the guarantee is **not**
     * that every failure leaves the console more gated than it started. If the lockout write's own
     * reply is lost after it landed, the result is the ordinary `(108=1, 95=0)` state. The guarantee
     * is the one that matters — *the lockout is never cleared unless the gate was armed first* —
     * because "lockout cleared, gate not armed" is the only combination that is worse than doing
     * nothing at all, and sending 95 only after 108 is acknowledged is what excludes it.
     *
     * ## Where this is deliberately stricter than iFit
     *
     * Three places, all chosen rather than copied:
     *
     * - **The gate write is awaited.** `InitializeConsole` does not await either setter and does not
     *   abort if one fails; both are fired and initialization continues regardless. Waiting for the
     *   arming write excludes the unsafe ordering where the lockout clears before the gate is armed,
     *   which is worth one round-trip on a machine with a belt.
     * - **The outcome is propagated.** iFit discards it. Here it becomes [StartGate], reaches
     *   [ConnectResult], and makes [authorisedWrite] refuse control — because a console whose gate
     *   state is unknown is not one to send speed or mode commands to. That is the same discipline
     *   [authorisedWrite] already applies to a reply with a bad checksum.
     * - **The console is asked whether a start is already pending** before the lockout is cleared,
     *   and the lockout is left alone if it cannot answer. iFit clears it unconditionally. See
     *   [startIsPending].
     */
    private fun initializeStartGate(info: FitProCodec.DeviceInfo): StartGate {
        // Field semantics come from `Sindarin.FitPro1`, and by this codebase's own account
        // "GlassOS/FitPro2 has no binding" for either one. A FitPro2 board that happens to set bit
        // 108 would mean something we have never verified, so restrict this by generation as well as
        // by capability. Note the generation half only bites over USB, where `variantOf` reads the
        // product id: `BleTransport.variant` is hardcoded FITPRO1, so a BLE-attached console is held
        // by the capability check alone.
        if (transport.variant != FitProCodec.Variant.FITPRO1) return StartGate.NotApplicable
        if (!info.supports(FitProCodec.Register.REQUIRE_START_REQUESTED)) return StartGate.NotApplicable
        // Nothing was attempted, so nothing is unknown — and the probe already refuses control on
        // its own account, which is the more accurate reason to show the rider.
        if (!probe.confirmed) return StartGate.NotApplicable

        // Step one: arm the gate. Its own frame — see [sendStartGateWrite].
        val armed = sendStartGateWrite(FitProCodec.Register.REQUIRE_START_REQUESTED, 1)
        if (armed != null) return StartGate.Incomplete(armed)

        if (!info.supports(FitProCodec.Register.IDLE_MODE_LOCKOUT)) {
            Log.i(TAG, "start-gate init: armed; this console has no IDLE_MODE_LOCKOUT to clear")
            return StartGate.Ready(idleModeLockoutCleared = false)
        }

        // Step two, before clearing anything: establish that the console is not already holding a
        // start request. See [startIsPending] for why an acknowledgement alone is not enough.
        startIsPending(info)?.let { return StartGate.Incomplete(it) }

        val lockout = if (BELT_BASED_MACHINE) 0 else 1
        val cleared = sendStartGateWrite(FitProCodec.Register.IDLE_MODE_LOCKOUT, lockout)
        if (cleared != null) return StartGate.Incomplete(cleared)

        Log.i(TAG, "start-gate init: REQUIRE_START_REQUESTED=1 then IDLE_MODE_LOCKOUT=$lockout, both accepted")
        return StartGate.Ready(idleModeLockoutCleared = true)
    }

    /**
     * Whether anything stops [IDLE_MODE_LOCKOUT] being cleared right now. Null means go ahead.
     *
     * Two questions, answered by one read frame.
     *
     * **Is a start already pending?** This is the auto-start question, and it is the reason this
     * read exists. Clearing the idle lockout does not command motion — nothing here writes `KPH` or
     * `WORKOUT_MODE`, and [Register.START_REQUESTED] is read-only so Stride cannot ask for a start
     * even if it wanted to. But `connect()` runs unattended, from launch and from every reconnect,
     * and "we command no motion" is not the same claim as "no motion can result". A console already
     * holding a start request — a rider pressed Start on its own panel — is the case where removing
     * an interlock could let motion follow, and `PLAN.md` §5 admits no auto-start from launch or
     * boot. So the lockout is cleared only against a console that says no start is pending, and a
     * console that cannot be asked is treated as one that might be.
     *
     * **Did the arming write actually take?** FitPro carries no request id, so [parseResponse] can
     * only correlate a reply by address and command — which this file already notes is how "a late
     * reply to a previous frame" gets mistaken for an acknowledgement. Reading field 108 back turns
     * an acknowledgement into an observation. It is advisory rather than required: a console that
     * declines to read the field back has not contradicted its own ack, and failing initialization
     * on that would be inventing a precondition iFit never had. A field that reads back as *zero* is
     * a contradiction, and is treated as one.
     */
    private fun startIsPending(info: FitProCodec.DeviceInfo): String? {
        val pendingRegister = FitProCodec.Register.START_REQUESTED
        if (!info.supports(pendingRegister)) {
            Log.w(TAG, "start-gate init: console does not report START_REQUESTED; not clearing the lockout")
            return "Stride couldn't confirm this console isn't already waiting to start, so it left" +
                " its idle lockout alone."
        }

        val reads = listOf(pendingRegister, FitProCodec.Register.REQUIRE_START_REQUESTED)
        val response = runCatching { exchange(reads = reads) }.getOrNull()
        if (response == null || !response.accepted || !response.checksumValid) {
            Log.w(TAG, "start-gate init: could not read START_REQUESTED back ($response)")
            return "Stride couldn't confirm this console isn't already waiting to start, so it left" +
                " its idle lockout alone."
        }

        // Advisory, per the note above: only a definite zero is evidence against the ack.
        val armedValue = response.value(FitProCodec.Register.REQUIRE_START_REQUESTED)
            ?.firstOrNull()?.toInt()?.and(0xFF)
        if (armedValue == 0) {
            Log.w(TAG, "start-gate init: REQUIRE_START_REQUESTED acknowledged but reads back 0")
            return "This console accepted Stride's start-gate setting and then reported it unset."
        }

        val pending = response.value(pendingRegister)?.firstOrNull()?.toInt()?.and(0xFF)
        if (pending == null) {
            Log.w(TAG, "start-gate init: START_REQUESTED missing from the reply")
            return "Stride couldn't confirm this console isn't already waiting to start, so it left" +
                " its idle lockout alone."
        }
        if (pending != 0) {
            Log.w(TAG, "start-gate init: START_REQUESTED=$pending; not clearing the idle lockout")
            return "This console is already holding a start request, so Stride left its idle lockout" +
                " in place rather than releasing it on your behalf."
        }
        return null
    }

    /**
     * Send one start-gate field in a frame of its own. Returns null on acknowledgement, or a
     * rider-visible reason.
     *
     * **This is where the start gate's frame layout is decided.** One field per frame, because a
     * single frame cannot express an order: [FitProCodec.registerBlock] sorts by field id, so
     * batching `REQUIRE_START_REQUESTED` (108) with `IDLE_MODE_LOCKOUT` (95) would put the lockout
     * *first*, reversing the sequence. iFit does not batch them either — each setter gets its own
     * `ReadWriteDataCmd` — and its `ReadWriteDataCmd` carries the same ascending sort, so batching
     * would reverse them in either implementation.
     *
     * `MachineAck.NoAnswer` counts as a failure: per [FitProTransport.exchange], a command whose
     * reply was lost "may still have landed", so silence makes a field's state unknown rather than
     * unchanged.
     */
    private fun sendStartGateWrite(register: FitProCodec.Register, value: Int): String? {
        val label = "start-gate ${register.name.lowercase().replace('_', ' ')}"
        val ack = authorisedWrite(label, gate = listOf(register)) {
            listOf(FitProCodec.writeOf(register, byteArrayOf(value.toByte())))
        }
        if (ack !is MachineAck.Ok) {
            Log.w(TAG, "start-gate init: ${register.name}=$value not acknowledged ($ack)")
            return startGateRefusal(ack)
        }
        return null
    }

    /** Phrase a failed start-gate write for a rider, keeping the machine's own words where it gave any. */
    private fun startGateRefusal(ack: MachineAck): String {
        val because = when (ack) {
            is MachineAck.Refused -> "the machine refused it (${ack.detail})"
            is MachineAck.NoAnswer -> "the machine didn't answer (${ack.reason})"
            MachineAck.Ok -> "it succeeded"
        }
        return "Stride couldn't put this console into a state it will accept a start from: $because."
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
    /** Bytes as lowercase hex, for a log line that can be read back against the protocol notes. */
    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it) }

    private fun handshake(): FitProCodec.DeviceInfo? {
        val refusals = linkedMapOf<Int, FitProCodec.Status?>()
        for (candidate in listOf(FitProCodec.ADDRESS_MAIN, FitProCodec.ADDRESS_TREADMILL)) {
            val request = FitProCodec.commandFrame(FitProCodec.Command.DEVICE_INFO, candidate)
            val reply = runCatching {
                transport.exchange(request, FitProCodec.Command.DEVICE_INFO)
            }.getOrNull()
            // Logged as bytes, both ways, because every conclusion drawn from this exchange so far
            // has been drawn from a decoded status with nothing to check it against. A refusal and a
            // misread buffer are the same three words in a log line and completely different bugs.
            Log.i(
                TAG,
                "DEVICE_INFO @$candidate -> ${hex(request)} <- ${reply?.let(::hex) ?: "(nothing)"}",
            )
            if (reply == null) {
                refusals[candidate] = null
                continue
            }
            // Checked before the status is believed. See FitProCodec.replyMatches: without this, a
            // stale frame or an unwritten buffer reads as a plausible refusal from the console.
            if (!FitProCodec.replyMatches(reply, FitProCodec.Command.DEVICE_INFO)) {
                Log.w(TAG, "address $candidate answered something that was not a DEVICE_INFO reply")
                refusals[candidate] = null
                continue
            }

            val status = FitProCodec.statusOf(reply)
            if (status != FitProCodec.Status.DONE) {
                Log.i(TAG, "address $candidate answered $status to DEVICE_INFO")
                refusals[candidate] = status
                continue
            }
            val parsed = FitProCodec.parseDeviceInfo(reply)
            if (parsed == null) {
                // Answered DONE and then said something unparseable. Recorded as its own outcome so
                // the failure report can tell it from silence.
                refusals[candidate] = FitProCodec.Status.DONE
                continue
            }
            // The address we asked, not the one in the reply, for outgoing frames: see [address].
            // The reply's own address is kept separately, in [replyAddress], for validating the
            // replies that come back to those frames: see its note for why the two can differ.
            replyAddress = parsed.address
            val info = parsed.copy(address = candidate)
            Log.i(
                TAG,
                "device at $candidate: ${info.brand} serial ${info.serialNumber} " +
                    "sw ${info.softwareVersion} hw ${info.hardwareVersion}, " +
                    "${info.supportedFieldIds.size} registers" +
                    if (info.requiresSecurity) " (software > ${FitProCodec.SECURITY_REQUIRED_ABOVE}: may demand VERIFY_SECURITY)" else "",
            )
            lastRefusals = emptyMap()
            return info
        }
        lastRefusals = refusals
        return null
    }

    /**
     * Why the last [handshake] gave up, in a sentence a rider can read back to us.
     *
     * "No FitPro device answered" used to cover both of these, and they are opposite problems. A
     * console that says nothing is a wiring or ownership question — wrong pipe, wrong cable, another
     * app holding the interface. A console that answers `CMD_NOT_SUPPORTED` is talking to us
     * perfectly and telling us we are speaking the wrong protocol at it, which no amount of
     * re-cabling will fix and which the generic sentence actively pointed away from.
     *
     * The second case is the live one on a product-3 board. This codec is the register protocol a
     * console speaks *down to its motor board*; a FitPro2 console presenting itself over USB is the
     * app-facing link, which is a different wire entirely — see [FitProCodec.Variant.FITPRO2], where
     * this was recorded as unconfirmed rather than known. Naming the refusal is what turns that
     * caveat into something a rider's report can confirm.
     */
    private fun handshakeFailureDetail(): String {
        val refusals = lastRefusals
        val answered = refusals.filterValues { it != null }
        if (answered.isEmpty()) {
            return "Nothing answered on ${transport.name}. Stride sent the treadmill's own " +
                "identify command to every address it knows and got no reply at all."
        }
        val said = answered.entries.joinToString(", ") { (address, status) ->
            "address ${address} said ${status?.name?.lowercase()?.replace('_', ' ')}"
        }
        return "The console on ${transport.name} is answering, but it refuses the command Stride " +
            "uses to identify a treadmill ($said). That means it speaks a different protocol to " +
            "the one direct access implements, so there is nothing to switch on here. iFit " +
            "(GlassOS) is the connection that works on this machine."
    }

    /**
     * What each address said to the last unsuccessful [handshake]: a status, or null for silence.
     *
     * Held rather than passed back so [handshake] keeps its single, obvious return value, and
     * cleared on success so a later failure report can never be built from an older attempt's
     * answers.
     */
    @Volatile
    private var lastRefusals: Map<Int, FitProCodec.Status?> = emptyMap()

    private fun describe(info: FitProCodec.DeviceInfo, probeResult: FitProProbe.Result): String {
        val brand = info.brand.name.lowercase().replace('_', ' ')
        val missing = REQUIRED_FOR_CONTROL.filterNot { info.supports(it) }
        val caveat = when {
            missing.isEmpty() -> ""
            else -> " It doesn't offer ${missing.joinToString(" or ") { it.name.lowercase() }}."
        }
        // Only worth saying when something went wrong: on a console that took our writes, the
        // security state is trivia; on one that refused them it is very often the answer.
        val securityNote = when (val state = security) {
            is SecurityState.Refused ->
                " Its software version (${info.softwareVersion}) demands a security handshake," +
                    " and the machine rejected Stride's answer (${state.reason})."
            is SecurityState.Unavailable ->
                " Its software version (${info.softwareVersion}) demands a security handshake" +
                    " Stride couldn't complete: ${state.reason}."
            SecurityState.Unlocked ->
                if (probeResult.stage == FitProProbe.Stage.UNCONFIRMED) {
                    " It did accept Stride's security handshake, so the refusal is something else."
                } else {
                    ""
                }
            SecurityState.NotRequired -> ""
        }
        // Worth saying whenever it applies, because it is the difference between "this machine will
        // not take a start" and "Stride will not send one". Without it the refusal from
        // authorisedWrite has no explanation anywhere the rider can see.
        val startGateNote = when (val gate = startGate) {
            is StartGate.Incomplete -> " ${gate.reason}"
            is StartGate.Ready, StartGate.NotApplicable -> ""
        }
        return "Found a $brand machine on ${transport.name}. ${probeResult.detail}.$caveat$securityNote$startGateNote"
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
            startGate = StartGate.NotApplicable
            address = FitProCodec.ADDRESS_MAIN
            replyAddress = null
            forgetSecurity()
        }
    }

    override fun close() {
        synchronized(wire) {
            lastConnect = null
            probe.reset()
            deviceInfo = null
            supportedCommands = emptySet()
            fanRegister = null
            startGate = StartGate.NotApplicable
            address = FitProCodec.ADDRESS_MAIN
            replyAddress = null
            forgetSecurity()
            runCatching { transport.close() }
                .onFailure { Log.w(TAG, "closing direct transport failed", it) }
        }
    }

    /**
     * Drop everything learned about this console's security gate.
     *
     * Kept as one call rather than three assignments because these three fields are only ever
     * meaningful together, and because every reset path in this class has at some point been the
     * place a field was forgotten. `security` in particular is public and drives what the settings
     * screen tells the rider — leaving it reading "unlocked" after the link dropped would make the
     * one diagnostic this change exists to provide say the opposite of the truth.
     *
     * Caller must hold [wire].
     */
    private fun forgetSecurity() {
        security = SecurityState.NotRequired
        systemInfo = null
        masterLibraryVersion = null
    }

    /** What [connect] found, for diagnostics and for the settings screen's copy. */
    data class ConnectResult(
        val deviceInfo: FitProCodec.DeviceInfo?,
        val supportedCommands: Set<FitProCodec.Command>,
        val probe: FitProProbe.Result,
        val detail: String,
        /**
         * How far the FitPro1 start-gate initialization got. Defaults to [StartGate.NotApplicable]
         * because the handshake-failure result never reaches the point of attempting it.
         */
        val startGate: StartGate = StartGate.NotApplicable,
    ) {
        val connected: Boolean get() = deviceInfo != null

        /**
         * Whether the console answered but its start gate is in an unknown state.
         *
         * Deliberately not folded into [connected]: the console *is* answering, and reporting it as
         * disconnected would send a rider to power-cycle a treadmill that is working. Control is
         * refused in [authorisedWrite] instead, which is the same shape the probe's refusal takes.
         */
        val startGateIncomplete: Boolean get() = startGate is StartGate.Incomplete
    }

    companion object {
        const val TAG = "DirectMachine"

        /**
         * Whether the machine underneath this console drives a belt.
         *
         * iFit computes the idle-mode lockout as `!requireStartRequested || !IsBeltBasedMachine()`,
         * so on a console that supports field 108 the value reduces to "is this a belt machine".
         * `DeviceExtensions.IsBeltBasedMachine` answers that from the primary device reported by
         * `DeviceInfoCmd`, and accepts **both** `Treadmill` and `InclineTrainer`. The X22i is an
         * incline trainer, so `true` is correct for it — but correct for a narrower reason than
         * "Stride only drives treadmills", which is why the distinction is recorded here.
         *
         * It is an assumption rather than a reading because Stride has no primary device to consult.
         * [FitProCodec.DeviceInfo.address] is the bus address a reply was stamped with — 5 on the
         * X22i — not a device-type enum, and the device list that `SUPPORTED_DEVICES` would carry is
         * never requested: `parseSupportedDevices` has no callers, which
         * `DIRECT_MACHINE_PROTOCOL.md`'s "What is still open" already tracks as its own item.
         * Wiring that command up is a larger change than this one, so the assumption is named here
         * rather than written as a bare literal at its one call site. FitPro1 equipment that is not
         * belt-based — a bike, a rower, an elliptical — needs the real conditional, and would send
         * `IDLE_MODE_LOCKOUT = 1` instead.
         */
        const val BELT_BASED_MACHINE = true

        /** Preferred first: see [fanRegister]. */
        val FAN_REGISTERS = listOf(FitProCodec.Register.FAN_STATE, FitProCodec.Register.FAN_SPEED)

        /** Registers a treadmill must implement for Stride to drive it. */
        val REQUIRED_FOR_CONTROL = listOf(FitProCodec.Register.KPH, FitProCodec.Register.GRADE)

        /**
         * How [MachineAck.Refused] spells `SECURITY_BLOCK`.
         *
         * Derived from the enum rather than written out, so that renaming the status cannot leave
         * the retry silently matching nothing.
         */
        val SECURITY_BLOCK_REASON: String =
            FitProCodec.Status.SECURITY_BLOCK.name.lowercase().replace('_', ' ')
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
     * The rider-facing fan state, as a [GlassOsCommands] `FAN_*` value, from a raw `FAN_STATE`
     * value. The inverse of [fanStateFromGlassOs].
     *
     * `UNKNOWN` returns null rather than a number. It is the wire's word for a value this firmware
     * revision does not use, and passing it on as a state would let a garbage byte become something
     * the overlay draws a name for.
     */
    fun fanStateToGlassOs(state: FitProCodec.FanState): Int? = when (state) {
        FitProCodec.FanState.OFF -> GlassOsCommands.FAN_OFF
        FitProCodec.FanState.LOW -> GlassOsCommands.FAN_LOW
        FitProCodec.FanState.MEDIUM -> GlassOsCommands.FAN_MEDIUM
        FitProCodec.FanState.HIGH -> GlassOsCommands.FAN_HIGH
        FitProCodec.FanState.AUTO -> GlassOsCommands.FAN_AUTO
        FitProCodec.FanState.UNKNOWN -> null
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
     * Whether `ACTUAL_KPH` has demonstrated that it reports this belt's motion.
     *
     * Latched for this client/link. Once earned, a later actual zero is a real stopped reading and
     * must not be replaced by a stale target. A replacement client starts unproven, just as
     * [MachineLink.everReportedMotion] does after a transport change.
     */
    private var actualSpeedReportedMotion = false

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
        val actualSpeedMph = response.value(FitProCodec.Register.ACTUAL_KPH)
            ?.let { FitProValues.kphToMph(FitProCodec.decodeSpeed(it)) }
        if ((actualSpeedMph ?: 0.0) > BELT_MOVING_MPH) actualSpeedReportedMotion = true
        val confirmedSetpointMph = response.value(FitProCodec.Register.KPH)
            ?.let { FitProValues.kphToMph(FitProCodec.decodeSpeed(it)) }
        // Prefer the clean value Stride actually asked for over the register's own round-tripped
        // echo, but only when they agree to within one quantization step: that is what proves the
        // accepted write really is what the register is holding, rather than a stale target from
        // before a console-side change. This is what turns a requested 1 mph into a 60:00 pace
        // instead of the register's nearest representable 1.61 kph (59:59) — see
        // DirectMachineSession.lastRequestedSpeedMph. It never contradicts the confirmed register;
        // it only replaces that register's own quantization noise with the value that produced it.
        val requested = session.lastRequestedSpeedMph
        val setpointSpeedMph = if (
            requested != null && confirmedSetpointMph != null &&
            Math.abs(requested - confirmedSetpointMph) <= SPEED_QUANTUM_MPH
        ) {
            requested
        } else {
            confirmedSetpointMph
        }
        // iFit makes this same source choice for belt machines. It is display-only: speedMph below
        // remains ACTUAL_KPH, so MachineLink's observation and everReportedMotion latch never see a
        // commanded target. Outside a moving workout state, do not resurrect a stale setpoint from
        // a paused or stopped run.
        val displaySpeedMph =
            if (!actualSpeedReportedMotion && mode in SPEED_DISPLAY_MODES) setpointSpeedMph else actualSpeedMph
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
            speedMph = actualSpeedMph,
            displaySpeedMph = displaySpeedMph,
            inclinePercent = response.value(FitProCodec.Register.ACTUAL_INCLINE)
                ?.let(FitProCodec::decodeIncline),
            distanceMiles = distanceMiles,
            paceMinPerMile = displaySpeedMph?.let(FitProValues::paceMinPerMile),
            elapsedSeconds = response.value(FitProCodec.Register.RUNNING_TIME)
                ?.let { FitProCodec.decodeInt(it).toLong() },
            calories = response.value(FitProCodec.Register.CURRENT_CALORIES)
                ?.let(FitProCodec::decodeCalories),
            speedWritable = duringWorkout && session.supports(FitProCodec.Register.KPH) != false,
            inclineWritable = duringWorkout && session.supports(FitProCodec.Register.GRADE) != false,
            wattsW = response.value(FitProCodec.Register.WATTS)?.let(FitProCodec::decodeInt),
            fanWritable = writable && session.fanRegister != null,
            // Whichever register this console said it implements, decoded to the shared FAN_*
            // numbering. Auto survives here where it used to be flattened away, because the
            // overlay names the state rather than plotting it on a scale.
            fanState = fan?.let { response.value(it) }
                ?.let { FitProValues.fanStateToGlassOs(FitProCodec.decodeFanState(it)) },
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
         * One quantization step of the `KPH` register (0.01 kph), in mph.
         *
         * The largest possible gap between a requested speed and the nearest value the register can
         * actually hold, after [FitProCodec.encodeSpeed]'s rounding. Used as the tolerance for
         * trusting [DirectMachineSession.lastRequestedSpeedMph] in place of the register's own
         * round-tripped echo — see where it is read, above.
         */
        const val SPEED_QUANTUM_MPH = 0.01 / FitProValues.KPH_PER_MPH

        /**
         * One poll's worth of registers, all read-only.
         *
         * `WORKOUT_MODE` is writable but perfectly readable — the read block is a plain bitmask with
         * no read-only restriction (`vh/f.j`); only writes are checked (`th/a`).
         */
        val TELEMETRY: List<FitProCodec.Register> = listOf(
            // Writable, but also readable: this is iFit's rider-facing speed source on belt
            // machines and the display-only fallback for a dead ACTUAL_KPH register.
            FitProCodec.Register.KPH,
            FitProCodec.Register.CURRENT_DISTANCE,
            FitProCodec.Register.RUNNING_TIME,
            FitProCodec.Register.WORKOUT_MODE,
            FitProCodec.Register.ACTUAL_KPH,
            FitProCodec.Register.ACTUAL_INCLINE,
            FitProCodec.Register.CURRENT_CALORIES,
            // Motor power draw. Not shown anywhere on its own — it exists for
            // MachineLink.everReportedLoad, issue #34's fallback for a console whose ACTUAL_KPH
            // never proves itself.
            FitProCodec.Register.WATTS,
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

        /**
         * States where a commanded target is meaningful as rider-facing live speed.
         *
         * Deliberately narrower than [WORKOUT_MODES]: pause and results still belong to the workout
         * instance, but their belt is stopped while KPH may retain the last non-zero target.
         */
        val SPEED_DISPLAY_MODES: Set<FitProCodec.WorkoutMode> = setOf(
            FitProCodec.WorkoutMode.RUNNING,
            FitProCodec.WorkoutMode.WARM_UP,
            FitProCodec.WorkoutMode.COOL_DOWN,
            FitProCodec.WorkoutMode.RESUME,
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
        }.also { if (it is MachineAck.Ok) session.noteAcceptedSpeedMph(FitProValues.kphToMph(kph)) }

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
     *
     * ## Why this also writes a speed and an incline
     *
     * GlassOS's `StartNewWorkout` takes no arguments, and measured on the real machine it drove the
     * console `IDLE -> WARM_UP -> WORKOUT` and started the belt at **1.0 mph** with no speed command
     * sent by us — the belt moves and the incline sits at 0. DIRECT is supposed to be a swap for
     * that, so pressing Start has to produce the same machine, not merely the same register.
     *
     * Whether GlassOS writes those values itself or the firmware picks them on entering a workout is
     * not visible from outside, and it does not matter: writing them explicitly is idempotent if the
     * machine was going to choose them anyway, and it is the difference between a defined starting
     * state and a firmware default we have never seen on hardware we do not own.
     *
     * The mode goes first, in its own frame. A speed written while the console is still `IDLE` may
     * be rejected as out of context, and the register block orders values by field id — `KPH` (0)
     * would land *before* `WORKOUT_MODE` (12) inside a single frame, which is precisely the order
     * that risks being dropped. Two frames cost one exchange and remove the question.
     *
     * A refused opening speed is deliberately **not** fatal. The workout has started by then, and
     * the failure it leaves behind is a stationary belt under a started workout — which is what
     * DIRECT did before this existed, and is the safe direction to fail in.
     */
    override fun startWorkout(): MachineAck {
        val started = setMode(FitProCodec.WorkoutMode.RUNNING, "start")
        if (started !is MachineAck.Ok) return started
        val opening = openingSpeedKph()
        val ack = writeOpeningState(opening)
        if (ack !is MachineAck.Ok) {
            Log.w(
                TAG,
                "workout started but the opening state was not accepted " +
                    "(${FitProValues.kphToMph(opening)} mph): $ack",
            )
        }
        return started
    }

    /**
     * The speed a new workout opens at, in kph.
     *
     * [START_SPEED_MPH] is what the machine GlassOS was measured on does, and it is also that
     * machine's own minimum — one observation fits both "always 1 mph" and "always the slowest it
     * will run", and there is no second machine to separate them. Coercing 1 mph into the reported
     * range satisfies both readings wherever they agree, and on a machine whose floor is above
     * 1 mph it sends a speed that can actually be accepted rather than one that is refused.
     *
     * Bounded by Stride's own policy ceiling as well, so a machine reporting a nonsense minimum
     * cannot talk us into opening a workout above the fastest speed this app will ever command.
     */
    private fun openingSpeedKph(): Double {
        val limits = session.probe.limits ?: return FitProValues.mphToKph(START_SPEED_MPH)
        val ceiling = minOf(limits.maxSpeedKph, FitProValues.mphToKph(MachineCoordinator.MAX_SPEED_MPH))
        val target = FitProValues.mphToKph(START_SPEED_MPH)
        // A machine reporting an inverted range must not invert the coercion.
        if (limits.minSpeedKph > ceiling) return target
        return target.coerceIn(limits.minSpeedKph, ceiling)
    }

    /**
     * The opening speed and a flat belt, in one frame.
     *
     * Incline is included only when the machine has a grade register — a treadmill without one is a
     * machine for which "incline 0" is not a state that exists, and naming an unsupported register
     * in a write is how a frame gets refused wholesale, taking the speed down with it.
     */
    private fun writeOpeningState(kph: Double): MachineAck =
        write("opening state", FitProCodec.Register.KPH) { session ->
            buildList {
                add(FitProCodec.writeOf(FitProCodec.Register.KPH, FitProCodec.encodeSpeed(kph)))
                if (session.supports(FitProCodec.Register.GRADE) != false) {
                    add(
                        FitProCodec.writeOf(
                            FitProCodec.Register.GRADE,
                            FitProCodec.encodeIncline(openingInclinePercent()),
                        ),
                    )
                }
            }
        }.also { if (it is MachineAck.Ok) session.noteAcceptedSpeedMph(FitProValues.kphToMph(kph)) }

    /** Flat, unless the machine cannot be flat, in which case as close to it as it goes. */
    private fun openingInclinePercent(): Double {
        val limits = session.probe.limits ?: return START_INCLINE_PERCENT
        val floor = maxOf(limits.minInclinePercent, MachineCoordinator.MIN_INCLINE)
        val ceiling = minOf(limits.maxInclinePercent, MachineCoordinator.MAX_INCLINE)
        if (floor > ceiling) return START_INCLINE_PERCENT
        return START_INCLINE_PERCENT.coerceIn(floor, ceiling)
    }

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
     * Stop the belt, then end the session, as two separate writes.
     *
     * They used to ride in one frame — that was true of the original `KPH` + `WORKOUT_MODE = IDLE`
     * write, and stayed true of the first fix that changed only the mode value to `RESULTS` (see
     * below). Combined, it is refused: live on the X22i, `KPH = 0` bundled with
     * `WORKOUT_MODE = RESULTS` in one frame came back `Refused`, from a console sitting in `PAUSE`.
     * iFit's own `WorkoutFacade.EndWorkoutAsync` (decompiled from `ifit-standalone.apk`) explains
     * why: it calls `SetValueAsync(WorkoutMode, WorkoutResults)` — one value, alone, never bundled
     * with a speed write the way `StartWorkoutAsync` bundles `WorkoutMode` with `Kph`/`Grade`. Ending
     * a workout is a mode-only transition on this protocol; only starting one carries a setpoint
     * alongside it. Sent as two round-trips instead, in the order that matters most for safety: the
     * belt first, the session state second, since a caller that only gets one of the two should get
     * the one that stops the belt.
     *
     * The mode written is `RESULTS`, not `IDLE`. It used to be `IDLE`, which is wrong: ending a
     * workout means transitioning to `RESULTS`, full stop — `EndWorkoutAsync` never asks for `IDLE`
     * at all. Asking this console for `IDLE` directly from `RUNNING`/`PAUSE` was refused outright on
     * the X22i, and retrying the same wrong write while `clearWorkout` looped drove the console
     * through a second unintended state before this was understood.
     *
     * `RESULTS` is not the end of it, though — see the third write below.
     *
     * Every write in this path is [DirectMachineSession.authorisedWrite]'s `startGateExempt`. None
     * of them can increase belt motion — one commands zero speed and the other two end the session —
     * and a start gate Stride could not finish initialising is exactly what a stop must not be
     * queued behind. Leaving a console parked in `RESULTS` for that reason would be its own bug.
     */
    override fun stop(): MachineAck {
        val speedAck = write("stop speed", FitProCodec.Register.KPH, startGateExempt = true) {
            listOf(FitProCodec.writeOf(FitProCodec.Register.KPH, FitProCodec.encodeSpeed(0.0)))
        }
        if (speedAck is MachineAck.Ok) session.noteAcceptedSpeedMph(0.0)
        if (speedAck !is MachineAck.Ok) {
            Log.w(TAG, "stop: speed-to-zero was not accepted: $speedAck")
        }
        if (session.supports(FitProCodec.Register.WORKOUT_MODE) == false) return speedAck
        val resultsAck = write("end workout", FitProCodec.Register.WORKOUT_MODE, startGateExempt = true) {
            listOf(
                FitProCodec.writeOf(
                    FitProCodec.Register.WORKOUT_MODE,
                    FitProCodec.encodeWorkoutMode(FitProCodec.WorkoutMode.RESULTS),
                ),
            )
        }
        if (resultsAck !is MachineAck.Ok) return resultsAck
        return cleanUpWorkout()
    }

    /**
     * The write that actually gets a console from `RESULTS` back to `IDLE`.
     *
     * Not a firmware timeout, and not guessed: `Sindarin.Core.Console.FitnessConsoleBase` (shared
     * across every equipment type, decompiled from `ifit-standalone.apk`) subscribes to its own
     * console-state stream, and on every transition *into* `RESULTS` — regardless of what caused
     * it — waits 200ms and then calls `CleanUpWorkout()`, which writes `WorkoutMode = Idle` and
     * `Grade = 0.0` together, in one frame, unconditionally. That 200ms figure is iFit's own
     * constant, not a value tuned here. Confirmed: `clearWorkout`'s previous approach — waiting up
     * to 18s for the console to leave `RESULTS` on its own with no further write — never once
     * succeeded live: nothing was ever going to leave RESULTS without this.
     */
    private fun cleanUpWorkout(): MachineAck {
        Thread.sleep(RESULTS_CLEANUP_DELAY_MS)
        return write("clean up workout", FitProCodec.Register.WORKOUT_MODE, startGateExempt = true) { session ->
            buildList {
                add(
                    FitProCodec.writeOf(
                        FitProCodec.Register.WORKOUT_MODE,
                        FitProCodec.encodeWorkoutMode(FitProCodec.WorkoutMode.IDLE),
                    ),
                )
                if (session.supports(FitProCodec.Register.GRADE) != false) {
                    add(FitProCodec.writeOf(FitProCodec.Register.GRADE, FitProCodec.encodeIncline(0.0)))
                }
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
     * The speeds this machine will accept, as a ladder built from its reported range intersected
     * with Stride's installation clamp.
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
        return MachinePresets.speedLadder(limits.minSpeedMph, limits.maxSpeedMph)
    }

    /** As [speedPresetsMph], from `MIN_GRADE`/`MAX_GRADE`, in percent at the rider's [spacing]. */
    override fun inclinePresets(spacing: InclineSpacing): List<Double>? {
        val limits = session.probe.limits ?: return null
        return MachinePresets.inclineLadder(limits.minInclinePercent, limits.maxInclinePercent, spacing)
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
        startGateExempt: Boolean = false,
        noinline build: (DirectMachineSession) -> List<FitProCodec.Write>,
    ): MachineAck = session.authorisedWrite(label, gate.toList(), startGateExempt, build)

    private companion object {
        const val TAG = "DirectMachine"

        /**
         * How long to wait after `RESULTS` before [cleanUpWorkout]'s follow-up write. iFit's own
         * constant (`FitnessConsoleBase`'s `Delay(TimeSpan.FromMilliseconds(200))`), not a value
         * tuned here.
         */
        const val RESULTS_CLEANUP_DELAY_MS = 200L

        /**
         * What a new workout opens at, matching GlassOS measured on the real machine: the belt runs
         * at 1.0 mph and the deck sits flat. See [startWorkout] for why DIRECT writes these rather
         * than trusting the firmware to pick them.
         */
        const val START_SPEED_MPH = 1.0
        const val START_INCLINE_PERCENT = 0.0

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
         *
         * Delegated to [MachinePresets] because FTMS derives its quick picks from the same three
         * numbers. Kept as a named alias here so the two call sites above read the same as they did
         * when the arithmetic lived in this file.
         */
        fun ladder(min: Double, max: Double, step: Double): List<Double> =
            MachinePresets.ladder(min, max, step)
    }
}
