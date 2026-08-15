package io.stride.spikes

/**
 * Confirms that the console on the other end of a [FitProTransport] really speaks the protocol
 * [FitProCodec] encodes, before anything is allowed to write to it.
 *
 * ## Why this still exists now that the framing is known
 *
 * An earlier version of this file searched a space of forty framing hypotheses, because the frame
 * layout and checksum had been guessed. That search is gone: the layout is now read directly out of
 * iFit's own encoder (`vh/d.e` and `vh/f.j`), so there is nothing left to guess.
 *
 * What remains uncertain is not the *protocol* but the *peer*. Opening a USB or BLE endpoint tells
 * us a device is there; it does not tell us the device is the treadmill's motor controller, that it
 * answers at [FitProCodec.ADDRESS_TREADMILL], or that its firmware implements the same register
 * table as the console this protocol was recovered from. A single read answers all three, and reads
 * cannot move the belt.
 *
 * ## Why a read frame cannot accidentally be a write
 *
 * This was the real hazard while the framing was unknown, and it is worth recording why it is gone.
 * A read/write frame carries two register blocks: writes first, then reads ([FitProCodec.readWriteBody]).
 * A pure read frame therefore contains an explicit **empty write block** — a single `0x00` mask-count
 * byte. There is no bit pattern in the read block that a conforming parser can mistake for a write,
 * because the write block's length is stated before it and is zero. That is a structural property of
 * the format, not a probabilistic one, which is why this probe needs no safety-key ceremony.
 *
 * ## The three stages
 *
 * - [Stage.UNCONFIRMED] — nothing has round-tripped. Writes are refused.
 * - [Stage.LINK_CONFIRMED] — a read round-tripped with [FitProCodec.Status.DONE], a valid checksum,
 *   and values inside physically possible ranges. Writes are allowed.
 * - [Stage.VALUES_CONFIRMED] — additionally, the decoded speed and incline agree with what GlassOS
 *   independently reports for the same machine. Nothing extra is unlocked; it is the strongest
 *   statement we can make, and it is what the diagnostics screen shows.
 *
 * Writes are allowed at [Stage.LINK_CONFIRMED] rather than only at [Stage.VALUES_CONFIRMED] on
 * purpose: the direct path exists precisely for the case where GlassOS is *not* answering, so
 * requiring a GlassOS cross-check to enable direct control would disable it exactly when it is
 * needed. The cross-check is an upgrade, not a gate.
 *
 * Not thread-safe for concurrent [confirm] calls; [MachineLink] runs it on its single machine thread.
 */
class FitProProbe {

    enum class Stage {
        UNCONFIRMED,
        LINK_CONFIRMED,
        VALUES_CONFIRMED,
    }

    /**
     * What GlassOS says about the same machine right now, for the optional cross-check.
     *
     * Null fields simply skip their comparison — a missing reference is not a failure, because the
     * direct path must work with GlassOS absent.
     */
    data class Reference(val speedMph: Double?, val inclinePercent: Double?)

    /** The outcome of one [confirm] attempt. [detail] is rider-visible in diagnostics. */
    data class Result(val stage: Stage, val detail: String)

    @Volatile
    var stage: Stage = Stage.UNCONFIRMED
        private set

    /** Human-readable account of the last attempt. Never parsed. */
    @Volatile
    var detail: String = "not checked yet"
        private set

    /** The machine's own limits, learned during [confirm]. Null until a check succeeds. */
    @Volatile
    var limits: MachineLimits? = null
        private set

    /** Whether [DirectMachineCommands] may encode a write. */
    val confirmed: Boolean get() = stage != Stage.UNCONFIRMED

    /**
     * Why a write is being refused, phrased for a rider rather than a developer.
     *
     * Returns null when writes are permitted, so callers can use it as the refusal itself.
     */
    fun refusalReason(): String? =
        if (confirmed) null else "Direct control hasn't been verified on this machine yet ($detail)."

    /** Forget everything. Called when the transport drops, so a new peer is re-checked. */
    fun reset() {
        stage = Stage.UNCONFIRMED
        detail = "not checked yet"
        limits = null
    }

    /**
     * Read [PROBE_READS] once and decide how far to trust the link.
     *
     * Blocking; call off the main thread. Never throws — a probe that threw would be a probe that
     * could take down the machine thread it is meant to protect.
     */
    fun confirm(
        transport: FitProTransport,
        reference: Reference? = null,
        address: Int = FitProCodec.ADDRESS_MAIN,
    ): Result {
        val outcome = runCatching { attempt(transport, reference, address) }
            .getOrElse { Result(Stage.UNCONFIRMED, "check failed: ${it.message ?: it::class.java.simpleName}") }
        stage = outcome.stage
        detail = outcome.detail
        if (outcome.stage == Stage.UNCONFIRMED) limits = null
        return outcome
    }

    private fun attempt(transport: FitProTransport, reference: Reference?, address: Int): Result {
        val body = FitProCodec.readWriteBody(writes = emptyList(), reads = PROBE_READS)
        val reply = transport.exchange(FitProCodec.frame(body, address = address))
            ?: return Result(Stage.UNCONFIRMED, "the machine didn't answer")

        val response = FitProCodec.parseResponse(reply, PROBE_READS)
            ?: return Result(Stage.UNCONFIRMED, "the reply wasn't a valid frame")

        if (response.status != FitProCodec.Status.DONE) {
            return Result(Stage.UNCONFIRMED, "the machine answered ${response.status.name.lowercase().replace('_', ' ')}")
        }
        if (!response.checksumValid) {
            return Result(Stage.UNCONFIRMED, "the reply's checksum didn't match")
        }

        val maxKph = response.value(FitProCodec.Register.MAX_KPH)?.let(FitProCodec::decodeSpeed)
        val minKph = response.value(FitProCodec.Register.MIN_KPH)?.let(FitProCodec::decodeSpeed)
        val maxGrade = response.value(FitProCodec.Register.MAX_GRADE)?.let(FitProCodec::decodeIncline)
        val minGrade = response.value(FitProCodec.Register.MIN_GRADE)?.let(FitProCodec::decodeIncline)
        val actualKph = response.value(FitProCodec.Register.ACTUAL_KPH)?.let(FitProCodec::decodeSpeed)
        val actualGrade = response.value(FitProCodec.Register.ACTUAL_INCLINE)?.let(FitProCodec::decodeIncline)

        if (maxKph == null || maxGrade == null || actualKph == null || actualGrade == null) {
            return Result(Stage.UNCONFIRMED, "the reply was too short to hold every value we asked for")
        }

        // Plausibility, not correctness. The point is that a width or byte-order regression does not
        // produce a slightly-wrong number, it produces one that is wrong by a factor of 256 — so a
        // treadmill claiming a 4000 km/h top speed is the signature we are looking for.
        if (maxKph !in PLAUSIBLE_MAX_KPH) {
            return Result(Stage.UNCONFIRMED, "it reported a top speed of ${"%.1f".format(maxKph)} km/h, which isn't a treadmill")
        }
        if (maxGrade !in PLAUSIBLE_MAX_GRADE) {
            return Result(Stage.UNCONFIRMED, "it reported a maximum incline of ${"%.1f".format(maxGrade)}%, which isn't a treadmill")
        }
        if (actualKph < -0.5 || actualKph > maxKph + SPEED_HEADROOM_KPH) {
            return Result(Stage.UNCONFIRMED, "it reported a current speed of ${"%.1f".format(actualKph)} km/h against its own ${"%.1f".format(maxKph)} km/h limit")
        }
        if (actualGrade > maxGrade + GRADE_HEADROOM || actualGrade < (minGrade ?: 0.0) - GRADE_HEADROOM) {
            return Result(Stage.UNCONFIRMED, "it reported a current incline of ${"%.1f".format(actualGrade)}% outside its own limits")
        }

        limits = MachineLimits(
            minSpeedKph = minKph ?: 0.0,
            maxSpeedKph = maxKph,
            minInclinePercent = minGrade ?: 0.0,
            maxInclinePercent = maxGrade,
        )

        val linkDetail = "reads ${"%.1f".format(actualKph)} km/h at ${"%.1f".format(actualGrade)}%, " +
            "limits ${"%.1f".format(maxKph)} km/h and ${"%.1f".format(maxGrade)}%"

        val mismatch = crossCheck(reference, actualKph, actualGrade)
        return when {
            mismatch != null -> Result(Stage.UNCONFIRMED, mismatch)
            reference == null || (reference.speedMph == null && reference.inclinePercent == null) ->
                Result(Stage.LINK_CONFIRMED, linkDetail)
            else -> Result(Stage.VALUES_CONFIRMED, "$linkDetail; agrees with iFit")
        }
    }

    /**
     * Compares our decode against GlassOS's, returning a description of any disagreement.
     *
     * The speed tolerance is deliberately loose. GlassOS's own speed decoder is `Double.valueOf(raw
     * / 100)` using **integer** division (`g7/z.h`), while its incline decoder correctly uses
     * `/ 100.0d`. So GlassOS truncates speed to whole km/h and can legitimately disagree with us by
     * just under 1 km/h. Tightening this tolerance would fail the check against a machine that is
     * working perfectly.
     */
    private fun crossCheck(reference: Reference?, actualKph: Double, actualGrade: Double): String? {
        if (reference == null) return null

        reference.speedMph?.let { referenceMph ->
            val ourMph = actualKph / KPH_PER_MPH
            if (kotlin.math.abs(ourMph - referenceMph) > SPEED_TOLERANCE_MPH) {
                return "we read ${"%.1f".format(ourMph)} mph where iFit reads ${"%.1f".format(referenceMph)} mph"
            }
        }
        reference.inclinePercent?.let { referencePercent ->
            if (kotlin.math.abs(actualGrade - referencePercent) > GRADE_TOLERANCE) {
                return "we read ${"%.1f".format(actualGrade)}% incline where iFit reads ${"%.1f".format(referencePercent)}%"
            }
        }
        return null
    }

    companion object {
        /**
         * What one probe exchange reads.
         *
         * The limits are included because they are the strongest plausibility signal available: a
         * treadmill's top speed is a tightly constrained number, so reading a sane one is good
         * evidence that widths and byte order are right. They are also worth having for their own
         * sake — see [MachineLimits].
         */
        val PROBE_READS: List<FitProCodec.Register> = listOf(
            FitProCodec.Register.ACTUAL_KPH,
            FitProCodec.Register.ACTUAL_INCLINE,
            FitProCodec.Register.MAX_GRADE,
            FitProCodec.Register.MIN_GRADE,
            FitProCodec.Register.MAX_KPH,
            FitProCodec.Register.MIN_KPH,
        )

        const val KPH_PER_MPH = 1.609344

        private val PLAUSIBLE_MAX_KPH = 4.0..40.0
        private val PLAUSIBLE_MAX_GRADE = 0.5..60.0
        private const val SPEED_HEADROOM_KPH = 2.0
        private const val GRADE_HEADROOM = 2.0

        /** Just over 1 km/h, to absorb GlassOS's truncating speed decoder. See [crossCheck]. */
        private const val SPEED_TOLERANCE_MPH = 0.75
        private const val GRADE_TOLERANCE = 0.75
    }
}

/**
 * What this particular machine says it can do.
 *
 * Read from `MAX_KPH`/`MIN_KPH`/`MAX_GRADE`/`MIN_GRADE`, which are read-only registers the console
 * populates from its own model configuration. This is strictly better than a constant compiled into
 * the app: it is this treadmill's answer, not some treadmill's.
 *
 * [MachineCoordinator] still applies its own clamp on top. Two clamps is the right number here, and
 * it is not a contradiction of the "one authoritative clamp" rule: the coordinator's clamp encodes
 * what *Stride* is willing to command, and this one encodes what the *hardware* will accept. The
 * effective limit is the intersection, and neither side is entitled to widen the other.
 */
data class MachineLimits(
    val minSpeedKph: Double,
    val maxSpeedKph: Double,
    val minInclinePercent: Double,
    val maxInclinePercent: Double,
) {
    val minSpeedMph: Double get() = minSpeedKph / FitProProbe.KPH_PER_MPH
    val maxSpeedMph: Double get() = maxSpeedKph / FitProProbe.KPH_PER_MPH
}
