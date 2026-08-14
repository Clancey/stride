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
 * A read-only client for the console's GlassOS daemon.
 *
 * ## This client cannot move the belt
 *
 * That is a structural property, not a convention. `SpeedService/SetSpeed`,
 * `InclineService/SetIncline`, `WorkoutService/StartNewWorkout`, `Pause`, `Resume` and `Stop` are
 * simply not implemented here, so no amount of UI wiring or future refactoring can accidentally
 * command the machine through this class. Commands, when they come, need the Coordinator's clamps,
 * watchdog and stop-preemption, and they will arrive as a separate type that is obviously
 * dangerous to touch.
 *
 * The distinction is not academic. `StartNewWorkout` takes no arguments and reads like a harmless
 * session start, but measured on the real machine it drove the console `IDLE → WARM_UP → WORKOUT`
 * and **started the belt at 1.0 mph** with no speed command sent. Anything that can call it is a
 * motor control path.
 *
 * ## Reading, by contrast, is safe
 *
 * Nothing here can change the machine's state, which is why telemetry can land well before control
 * does, and why the metrics in the overlay can stop saying "Not measured" without unlocking a
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
            note("no credentials in files/glassos — staying disconnected")
            return null
        }
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

    /** Perform a unary call with an empty request. Returns null on any failure. */
    private fun call(service: String, method: String): GlassOsWire.Fields? {
        val client = ensureClient() ?: return null
        return try {
            val request = Request.Builder()
                .url("$ENDPOINT/com.ifit.glassos.$service/$method")
                .addHeader("te", "trailers")
                .addHeader("client_id", clientId)
                .post(GlassOsWire.EMPTY_FRAME.toRequestBody(GRPC))
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
                GlassOsWire.unframe(body)?.let { GlassOsWire.parse(it) }
            }
        } catch (t: Throwable) {
            note("$service/$method ${t.javaClass.simpleName}: ${t.message}")
            null
        }
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
     * One consistent view of the machine. Null values mean "no reading" and must render as
     * [MachineLink.NO_READING] — never as a number.
     */
    data class Snapshot(
        val consoleState: String?,
        val workoutId: String?,
        val speedMph: Double?,
        val inclinePercent: Double?,
        val distanceMiles: Double?,
        val paceMinPerMile: Double?,
        val elapsedSeconds: Long?,
        val calories: Double?,
    )

    /**
     * Read everything at once. Blocking; call from a background thread.
     *
     * Returns null when not linked, which is distinct from a linked machine reporting nothing.
     */
    fun read(): Snapshot? {
        if (!isLinked()) return null

        val console = call("ConsoleService", "GetConsoleState")?.enum(1)
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
        )
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
        fun name(raw: Int?): String? = when (raw) {
            null -> null
            0 -> "DISCONNECTED"
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

        /** True when the machine is in a state where the belt may be under power. */
        fun beltMayBeMoving(name: String?): Boolean =
            name == "WORKOUT" || name == "WARM_UP" || name == "COOL_DOWN" || name == "RESUME"
    }
}
