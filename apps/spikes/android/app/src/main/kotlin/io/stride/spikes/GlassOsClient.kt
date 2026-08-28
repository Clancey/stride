package io.stride.spikes

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Transport and readers for the console's GlassOS daemon.
 *
 * ## This class no longer forbids control, but it does not offer it either
 *
 * Earlier revisions had no way at all to command the machine, and said so here. That is no longer
 * true: [postRaw] can carry a request body, and the command surface in [GlassOsCommands] is built on
 * it. The guarantee moved rather than disappeared — **no command reaches this transport except
 * through [MachineCoordinator]**, which owns the clamps, the ramp limit, stop preemption and the
 * generation check. Nothing in this file should grow a `setSpeed`-shaped method; put it in
 * [GlassOsCommands] where it is obviously dangerous to touch.
 *
 * The distinction is not academic. `StartNewWorkout` takes no arguments and reads like a harmless
 * session start, but measured on the real machine it drove the console `IDLE → WARM_UP → WORKOUT`
 * and **started the belt at 1.0 mph** with no speed command sent. Anything that can call it is a
 * motor control path.
 *
 * ## Reading, by contrast, is safe
 *
 * The readers here cannot change the machine's state, which is why telemetry landed well before
 * control did, and why the metrics in the overlay stopped saying "Not measured" without unlocking a
 * single button.
 *
 * Every call is blocking; callers must be off the main thread. Timeouts are deliberately short —
 * a stalled console must degrade to "no reading", never freeze an overlay on a device with no
 * physical Back button.
 */
class GlassOsClient(private val context: Context) {

    private companion object {
        const val ENDPOINT = "https://127.0.0.1:54321"
        val GRPC = "application/grpc".toMediaType()

        /**
         * GlassOS requires a client identity header. This must match the CN of the client
         * certificate in use, so it is derived from the certificate rather than hardcoded to a
         * value that silently disagrees with it.
         */
        const val FALLBACK_CLIENT_ID = "com.ifit.eriador"
    }

    private var http: OkHttpClient? = null
    private var clientId: String = FALLBACK_CLIENT_ID

    /** True once credentials are present and TLS material could be built. */
    fun isLinked(): Boolean = ensureClient() != null

    private fun ensureClient(): OkHttpClient? {
        http?.let { return it }
        val material = GlassOsCredentials.load(context)
        if (material == null) {
            note("no usable credentials, bundled or provisioned — staying disconnected")
            return null
        }
        // Worth a line: a tester reporting "it won't connect" is answered largely by whether their
        // console fell back to the bundle or picked up an override they forgot they had pushed.
        note("credentials loaded from ${material.source}")
        val built = OkHttpClient.Builder()
            .sslSocketFactory(material.socketFactory, material.trustManager)
            // The server certificate is CN-only with no SAN; the chain is still verified against
            // the pinned CA above. See GlassOsCredentials for why this is narrow and deliberate.
            .hostnameVerifier { _, _ -> true }
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        http = built
        return built
    }

    /**
     * Perform a unary call with an empty request, returning the raw (unframed) response message,
     * or null on any failure. Everything reachable from here is read-only: the request body is
     * always [GlassOsWire.EMPTY_FRAME], so no value can be carried to the machine.
     */
    private fun callRaw(service: String, method: String): ByteArray? =
        postRaw(service, method, GlassOsWire.EMPTY_FRAME)

    /**
     * Perform a unary call carrying an already-framed request body.
     *
     * Internal rather than private because the command surface needs it, and internal rather than
     * public so the only callers are in this module. [GlassOsCommands] is the intended one.
     */
    internal fun postRaw(
        service: String,
        method: String,
        framed: ByteArray,
        readTimeoutSeconds: Long = 0,
    ): ByteArray? {
        // Checked on every single call, not merely at construction, and this is the mechanism that
        // makes "direct sends nothing to GlassOS" true rather than merely likely.
        //
        // Dropping the reference in MachineLink is not enough on its own. A handshake or command
        // already in flight has its own reference on its own thread, captured before the rider
        // switched, and it will happily finish the call it started — putting a Connect on the
        // GlassOS socket moments after the rider asked for the machine to be driven directly.
        // Narrowing that window with a re-check would leave a smaller window. Closing the client
        // removes it: every captured reference becomes inert at the instant of the switch.
        if (closed) return null
        val base = ensureClient() ?: return null
        // Reads keep the short default so a stalled console degrades to "no reading" instead of
        // freezing the overlay. Commands need longer: StartNewWorkout spins the machine up and
        // measured well past two seconds, which surfaced as a bogus "no reply" on a command the
        // treadmill had actually accepted. newBuilder shares the connection pool, so this is a
        // timeout override rather than a second client.
        val client = if (readTimeoutSeconds <= 0) base else {
            base.newBuilder()
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(readTimeoutSeconds + 2, TimeUnit.SECONDS)
                .build()
        }
        return try {
            val request = Request.Builder()
                .url("$ENDPOINT/com.ifit.glassos.$service/$method")
                .addHeader("te", "trailers")
                .addHeader("client_id", clientId)
                .post(framed.toRequestBody(GRPC))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    note("$service/$method http ${response.code}")
                    return null
                }
                // The body must be read before the trailers exist. Asking for trailers first
                // throws, and aborting the response early resets the stream, which the server
                // logs as a protocol error — the request never gets a chance to succeed.
                val body = response.body?.bytes() ?: return null
                val status = response.trailers()["grpc-status"]
                if (status != null && status != "0") {
                    note("$service/$method grpc-status $status ${response.trailers()["grpc-message"]}")
                    return null
                }
                GlassOsWire.unframe(body)
            }
        } catch (t: Throwable) {
            note("$service/$method ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Perform a unary call with an empty request. Returns null on any failure. */
    /**
     * Make this client refuse all further traffic, permanently.
     *
     * One-way on purpose: a closed client is never reopened, it is replaced. Reopening would mean a
     * reference captured before a switch could come back to life after a second switch, which is
     * precisely the class of bug this exists to remove.
     */
    fun close() {
        closed = true
    }

    @Volatile private var closed = false

    private fun call(service: String, method: String): GlassOsWire.Fields? =
        callRaw(service, method)?.let { GlassOsWire.parse(it) }

    /**
     * Read a service's quick-pick presets read-only via `GetControls`, which returns a
     * `ControlList` (`repeated Control controls = 1`). `GetControls` is a pure reader — it is what
     * the stock console calls to *draw* the speed/incline buttons, not to press them — so nothing
     * here can actuate the machine.
     *
     * Returns null on transport failure, and an empty list when the machine decoded a `ControlList`
     * that carried no controls. The two are kept distinct so a caller can tell "we never got an
     * answer" from "the machine says there are none".
     *
     * [GlassOsWire.parse] deliberately collapses repeated fields to their last occurrence, so the
     * repeated `Control` entries are pulled out of the raw message here before each is parsed as a
     * single message.
     */
    fun controls(service: String): List<MachineControl>? {
        val raw = callRaw(service, "GetControls") ?: return null
        return repeatedLengthDelimited(field = 1, message = raw).map { entry ->
            val f = GlassOsWire.parse(entry)
            MachineControl(
                // Control.type = 1 (ControlType), .at = 2, .value = 3; proto3 omits any that are
                // zero, so an absent value is a genuine 0.0, not a missing preset.
                type = f.enum(1) ?: ControlType.UNKNOWN,
                at = f.double(2) ?: 0.0,
                value = f.double(3) ?: 0.0,
            )
        }
    }

    /**
     * Collect every length-delimited (wire type 2) entry for [field] from a protobuf [message].
     *
     * [GlassOsWire.parse] keeps only the last occurrence of a field, which is right for the scalar
     * metric messages it was written for but loses the elements of a `repeated` message. This walks
     * the message just enough to recover them, skipping other fields and other wire types rather
     * than treating them as errors.
     */
    private fun repeatedLengthDelimited(field: Int, message: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < message.size) {
            val (tag, afterTag) = readVarint(message, i) ?: break
            i = afterTag
            val f = (tag ushr 3).toInt()
            when ((tag and 0x7L).toInt()) {
                0 -> { val (_, next) = readVarint(message, i) ?: return out; i = next } // varint
                1 -> { if (i + 8 > message.size) return out; i += 8 }                   // fixed64
                2 -> {                                                                  // length
                    val (len, afterLen) = readVarint(message, i) ?: return out
                    val end = afterLen + len.toInt()
                    if (len < 0 || end > message.size) return out
                    if (f == field) out.add(message.copyOfRange(afterLen, end))
                    i = end
                }
                5 -> { if (i + 4 > message.size) return out; i += 4 }                   // fixed32
                else -> return out // group or corrupt; stop rather than guess
            }
        }
        return out
    }

    private fun readVarint(input: ByteArray, from: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = from
        while (i < input.size && shift <= 63) {
            val b = input[i].toInt()
            result = result or ((b.toLong() and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    /**
     * Diagnostics for the link. Deliberately logs only the call name and failure reason — never
     * headers, certificates or key material, which is the one thing that must not end up in a
     * logcat buffer that any app on this console could once read.
     */
    private fun note(message: String) {
        if (lastNote == message) return
        lastNote = message
        Log.i("StrideGlassOs", message)
    }

    @Volatile private var lastNote: String? = null

    /**
     * Ask a service whether a reading is available at all.
     *
     * Note the `?: false`: `AvailabilityResponse` is proto3, so an unavailable service replies with
     * an empty message and the `false` never reaches the wire. Defaulting this to true would make
     * the client believe the exact opposite of what the machine said. Measured on the 1750,
     * StepCount and Cadence answer this way.
     */
    private fun canRead(service: String): Boolean =
        GlassOsTelemetry.availability(call(service, "CanRead")?.bool(1))

    /**
     * Ask a service whether it will accept a write *right now*.
     *
     * A read, despite the name: `CanWrite` reports the machine's own opinion and actuates nothing.
     * It is asked because the answer changes with console state — an idle console, one showing
     * results, or one under another client's control refuses setpoints — and the alternative to
     * asking is finding out from a failed `SetIncline`, i.e. by having already tried to move the
     * machine.
     *
     * Null and false are deliberately different. Null is "the machine never answered", which is a
     * transport problem and says nothing about permission; false is the machine explicitly
     * declining. Callers must not collapse them: disabling controls on a dropped reply would lock
     * the rider out of the belt over one missed poll.
     */
    private fun canWrite(service: String): Boolean? =
        call(service, "CanWrite")?.let { GlassOsTelemetry.availability(it.bool(1)) }

    /**
     * One consistent view of the machine. Null values mean "no reading" and must render as
     * [MachineLink.NO_READING] — never as a number.
     */
    data class Snapshot(
        val consoleState: String?,
        val workoutId: String?,
        /**
         * Speed reported as a machine observation.
         *
         * Safety code reads this field. A commanded target must never be substituted here: an ack
         * says what the console accepted, not what the belt did.
         */
        val speedMph: Double?,
        /**
         * Speed suitable for the rider-facing metric, which normally equals [speedMph].
         *
         * FitPro belt consoles are the exception. Some, including the X22i in issue #34, return a
         * well-formed zero for `ACTUAL_KPH` while moving. The direct client follows iFit's own
         * belt-machine display rule until that register proves itself, while leaving [speedMph]
         * untouched for observations and stop confirmation.
         */
        val displaySpeedMph: Double? = speedMph,
        val inclinePercent: Double?,
        val distanceMiles: Double?,
        val paceMinPerMile: Double?,
        val elapsedSeconds: Long?,
        val calories: Double?,
        /**
         * What the machine says it will accept a write for at this moment. Null means it did not
         * answer; see [canWrite] for why that is not the same as "no".
         */
        val speedWritable: Boolean?,
        val inclineWritable: Boolean?,
        val fanWritable: Boolean?,
        /**
         * The fan state the *machine* reports, as a [GlassOsCommands] `FAN_*` value, or null when
         * we do not know.
         *
         * A reading, not an acknowledgement. [MachineCoordinator.lastFanState] is the other half of
         * the picture and only says what the last accepted Stride write asked for; the console has
         * its own fan button under the rider's hand, and this field is the only thing that ever sees
         * them press it.
         *
         * Null means unknown and must never be drawn as OFF — `FAN_OFF` is 0, and the whole point
         * of keeping this nullable is that "the fan is off" and "nobody could tell us" are
         * different sentences.
         */
        val fanState: Int? = null,
        /**
         * Heart rate as reported by the *machine*, or null when it does not report one.
         *
         * Only FTMS fills this in; GlassOS and the register path leave it null. Null is not zero and
         * is not "the rider has no pulse" — on a treadmill it usually means nobody is holding the
         * grip sensors, which is the normal case for anyone actually running.
         */
        val heartRateBpm: Int? = null,
        /**
         * Motor power draw in watts, as the *machine* reports it, or null when it does not report
         * one.
         *
         * Direct-path only — GlassOS and FTMS leave it null. Not a speed proxy: power depends on
         * rider weight and incline as well as belt speed, so this must never be calibrated into a
         * displayed mph figure. Its one job is [MachineLink.everReportedLoad]: a second, independent
         * signal that the belt is genuinely under load, for a console like the X22i (issue #34)
         * whose speed register never proves itself. It does **not** decay while merely paused, only
         * when the console actually leaves `RUNNING` — see [MachineLink.everReportedLoad].
         */
        val wattsW: Int? = null,
    ) {
        /**
         * Fan speed, 0..[MachineLink.FAN_MAX], or null when we do not know.
         *
         * Derived from [fanState] rather than stored beside it, so the two can never disagree.
         * `AUTO` deliberately falls out as null: auto is not a level — the machine is choosing —
         * and drawing it as a number would tell the rider the fan is at a speed it is not.
         * Anything that wants to *name* the state, including "Auto", must read [fanState].
         */
        val fanLevel: Int? get() = fanState?.takeIf { it in 0..MachineLink.FAN_MAX }
    }

    /**
     * Read everything at once. Blocking; call from a background thread.
     *
     * Returns null when not linked, which is distinct from a linked machine reporting nothing.
     *
     * "Linked" used to mean only that credentials parsed, so a console whose daemon never answered
     * still produced a Snapshot full of nulls and left [MachineLink] reporting LINKED. That is the
     * failure this returns null for now: `GetConsoleState` not answering at all means we are not
     * talking to GlassOS, and saying otherwise puts "Stride is linked to this machine" on screen
     * beside a machine nothing can reach.
     */
    fun read(): Snapshot? {
        if (!isLinked()) return null

        // Kept as the whole message rather than the decoded enum, because proto3 omits a zero and
        // DISCONNECTED *is* zero: `enum(1)` returns null both for "the console is disconnected"
        // and for "the daemon never replied", and those two must not collapse into one.
        val consoleReply = call("ConsoleService", "GetConsoleState") ?: return null
        val console = consoleReply.enum(1) ?: ConsoleState.DISCONNECTED
        val distance = call("DistanceService", "GetDistance")
        val speed = call("SpeedService", "GetSpeed")
        val incline = call("InclineService", "GetIncline")
        val elapsed = call("ElapsedTimeService", "GetElapsedTime")
        val calories = call("CaloriesBurnedService", "GetCaloriesBurned")

        // The workout id is the discriminator that tells a measured zero from an unknown. Any
        // metric message carrying one proves a workout context exists.
        val workoutId = listOf(distance, speed, incline, elapsed)
            .firstNotNullOfOrNull { it?.string(1) }

        val kph = GlassOsTelemetry.reading(speed?.double(3), workoutId, canRead("SpeedService"))
        val mph = GlassOsTelemetry.kphToMph(kph)
        val km = GlassOsTelemetry.reading(distance?.double(3), workoutId, canRead("DistanceService"))

        // `timeSeconds` is subject to the same proto3 trap as every value field: at second 0 of a
        // workout it is a genuine zero and is omitted from the wire. Reading it as a bare
        // `long(2)` would render "Not measured" for the first second of every run. It goes through
        // the same rule as everything else.
        val elapsedSeconds = GlassOsTelemetry
            .reading(elapsed?.long(2)?.toDouble(), workoutId, canRead("ElapsedTimeService"))
            ?.toLong()

        // `CanWrite` was already going to be asked for [Snapshot.fanWritable], so the fan read is
        // handed the answer rather than paying for the round trip a second time.
        val fanWritable = canWrite("FanStateService")
        val fanState = readFanState(fanWritable)

        return Snapshot(
            consoleState = ConsoleState.name(console),
            workoutId = workoutId,
            speedMph = mph,
            inclinePercent = GlassOsTelemetry.reading(
                incline?.double(3), workoutId, canRead("InclineService")
            ),
            distanceMiles = GlassOsTelemetry.kmToMiles(km),
            // Derived from measured speed only. Never from elapsed time against an assumed pace.
            paceMinPerMile = GlassOsTelemetry.paceMinPerMile(mph),
            elapsedSeconds = elapsedSeconds,
            calories = GlassOsTelemetry.reading(
                calories?.double(3), workoutId, canRead("CaloriesBurnedService")
            ),
            // Read every poll rather than once per link: this is precisely the thing that changes
            // when a workout starts or ends, which is the case the UI needs it for.
            speedWritable = canWrite("SpeedService"),
            inclineWritable = canWrite("InclineService"),
            fanWritable = fanWritable,
            fanState = fanState,
        )
    }

    /**
     * The console's own fan state, or null when we genuinely do not know.
     *
     * **`GetFanState` answering is not proof that this machine has a fan.** `FanState.OFF` is 0 and
     * proto3 omits a zero, so a console with no fan and a console whose fan is off both reply with
     * an empty message — the same trap `GetConsoleState` above has to sidestep, except that there
     * the console's existence was never in doubt. Defaulting an empty reply to OFF without
     * independent proof would print a confident "Off" over a treadmill that has no fan at all,
     * which is the one thing a fan readout must never do.
     *
     * So the machine has to say it has one first, and only then is an omitted zero read as OFF.
     * Two ways to say it, because the two questions fail differently: `CanWrite` is the answer this
     * poll already has in hand, and `CanRead` is the same question every other metric here asks, for
     * a console that will talk about reading a fan it will not talk about writing. `CanWrite` is
     * required to be an explicit `true` — null is "never answered", and unknown is not evidence.
     *
     * Tested in that order deliberately: `CanRead` is a round trip, and on a console that has
     * already said it will take a fan write there is nothing left to establish. Skipping the call
     * entirely when neither says yes also keeps a fanless machine off the extra round trip.
     *
     * **This decodes an absent zero; it does not vouch for the register.** Once the console has
     * confirmed it has a fan, an omitted field is a correct decode of OFF — that is what proto3
     * means. It is not a claim that the answer is true. `ACTUAL_KPH` reads a confident, well-formed,
     * entirely plausible `0x0000` on the X22i while the belt is running (issue #34), and nothing in
     * a reply frame distinguishes that from a real zero. A fan register can lie the same way, and
     * no null check here would catch it. What this promises is "the machine said this", which is
     * the same promise every other reading in this snapshot makes — and is precisely why the
     * overlay marks a value Stride merely *requested* as something weaker.
     */
    private fun readFanState(fanWritable: Boolean?): Int? {
        if (fanWritable != true && !canRead("FanStateService")) return null
        val reply = call("FanStateService", "GetFanState") ?: return null
        return reply.enum(1) ?: GlassOsCommands.FAN_OFF
    }

    /**
     * The console's own state machine, as observed on the real machine:
     * `IDLE → WARM_UP → WORKOUT → WORKOUT_RESULTS → IDLE`.
     *
     * `SAFETY_KEY_REMOVED` is the one that matters most. It is a real safety input, and it means
     * Stride can reconcile what the user asked for against what the machine actually is, instead
     * of assuming they agree.
     *
     * These numbers are transcribed from `protocol/glassos/console/ConsoleState.proto`, not from
     * memory. A first draft of this mapping put `SAFETY_KEY_REMOVED` at 1 — it is 6, and 1 is
     * `CONSOLE_STATE_UNKNOWN`. That single-digit slip would have made the app report a removed
     * safety key as an unknown state and vice versa, which is exactly the class of error the
     * extracted protos exist to prevent. Re-extract and diff rather than editing by hand.
     */
    object ConsoleState {
        /**
         * No machine behind the daemon.
         *
         * Zero, and therefore omitted from the wire, which is why [GlassOsClient.read] keeps the
         * raw reply. Observed for real: after a console reboot GlassOS answered every read while
         * `GetConsole` came back empty and `Connect` never returned — the head unit had lost its
         * link to the lower board. Every motion RPC blocked until it timed out.
         */
        const val DISCONNECTED = 0

        /** The name for [DISCONNECTED], compared by callers that only hold the display string. */
        const val DISCONNECTED_NAME = "DISCONNECTED"

        fun name(raw: Int?): String? = when (raw) {
            null -> null
            0 -> DISCONNECTED_NAME
            1 -> "CONSOLE_STATE_UNKNOWN"
            2 -> "IDLE"
            3 -> "WORKOUT"
            4 -> "PAUSED"
            5 -> "WORKOUT_RESULTS"
            6 -> "SAFETY_KEY_REMOVED"
            7 -> "WARM_UP"
            8 -> "COOL_DOWN"
            9 -> "RESUME"
            10 -> "LOCKED"
            11 -> "DEMO"
            12 -> "SLEEP"
            13 -> "ERROR"
            else -> null
        }

        /**
         * The number for a name produced by [name], or null if it is not one of ours.
         *
         * The inverse of [name], derived from it rather than written out a second time, so the two
         * cannot drift apart. The direct path needs it: FitPro reports a workout mode, Stride
         * translates that to a console-state *name*, and callers that compare numbers — including
         * [MachineLink.connectNow]'s DISCONNECTED test — need the number that name stands for.
         */
        fun code(name: String): Int? = (0..13).firstOrNull { name(it) == name }

        /**
         * True when the machine is in a state where the belt may be under power.
         *
         * Null when the console state is unknown — either it never reported one or it reported a
         * value this build does not recognise. That is deliberately *not* folded into `false`: this
         * predicate exists to decide whether motion is possible, and answering "no" for a state we
         * cannot read is the one wrong answer that matters. Callers already treat null as "assume
         * it might be", and `== true` is the idiom for "definitely moving".
         */
        fun beltMayBeMoving(name: String?): Boolean? = when (name) {
            "WORKOUT", "WARM_UP", "COOL_DOWN", "RESUME" -> true
            "IDLE", "PAUSED", "WORKOUT_RESULTS", "SAFETY_KEY_REMOVED", "LOCKED", "SLEEP" -> false
            // DISCONNECTED is false, and it is the one entry on that line worth arguing about. It is
            // not "I do not know what I am doing" — it is "there is no treadmill attached to me",
            // which is a definite statement, and a console with nothing attached is not running a
            // workout for us to worry about. It is grouped here rather than with the nulls below
            // because the nulls mean "cannot say", and this one says.
            DISCONNECTED_NAME -> false
            // CONSOLE_STATE_UNKNOWN, DEMO, ERROR and anything unrecognised fall through to null on
            // purpose. A demo routine can drive the belt, an errored console has not told us what it
            // is doing, and a state this build does not know is by definition not one it can rule
            // motion out from.
            else -> null
        }
    }

    /**
     * The `ControlType` enum from `protocol/glassos/workout/data/ControlType.proto`, limited to the
     * two values Stride reads. A quick-pick button is one `Control` carrying a preset `value`;
     * INCLINE values are a percent and MPS values are a speed in **metres per second**. The other
     * eleven types (resistance, gear, watts, …) belong to bikes and rowers and are ignored rather
     * than coerced — a resistance level is not a treadmill speed, and forcing it into one would
     * invent buttons the machine never offered.
     */
    /**
     * `Control.type`, from the gRPC enum `pb.e` in GlassOS. VERIFIED.
     *
     * **There is a second, differently numbered ControlType in the same APK** — the Kotlin SDK's
     * `IFitControlType` (`vf/a`), whose order is `unknown, gear, incline, mps, …`, making incline 2
     * and mps 3. That one is an internal SDK type and is *not* what crosses the wire. The wire enum
     * is the protobuf one below, reached from `Control.type_` via `pb.e.b(int)`.
     *
     * Reconciling these two the wrong way would not fail: it would silently filter the speed presets
     * out of the incline rail and vice versa.
     */
    object ControlType {
        const val UNKNOWN = 0
        const val INCLINE = 1
        const val MPS = 2
    }
}

/** One decoded `Control`: its [type] (a [GlassOsClient.ControlType]), position [at], and [value]. */
data class MachineControl(val type: Int, val at: Double, val value: Double)

/**
 * Shape a raw `ControlList` into the display presets for one control column.
 *
 * Pure and side-effect free so it can be tested without a live machine. Keeps only [keepType]
 * (ignoring every other [GlassOsClient.ControlType] rather than coercing it), maps each value
 * through [toDisplay] into the unit shown on screen, sorts **descending** so the highest preset
 * sits at the top of the column as it does on the stock console, and removes values that collide
 * once rounded for display. Empty input yields an empty list; the caller is responsible for
 * mapping that to a null property rather than a fabricated list.
 */
internal fun shapePresets(
    controls: List<MachineControl>,
    keepType: Int,
    toDisplay: (Double) -> Double,
): List<Double> =
    controls.asSequence()
        .filter { it.type == keepType }
        .map { roundForDisplay(toDisplay(it.value)) }
        .distinct()
        .sortedDescending()
        .toList()

/** Presets are shown to one decimal place; rounding here is what de-duplication collapses on. */
private fun roundForDisplay(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0
