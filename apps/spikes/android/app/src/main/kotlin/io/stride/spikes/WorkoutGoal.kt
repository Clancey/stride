package io.stride.spikes

/**
 * A goal for the current session, plus the progress and ETA derived from it.
 *
 * Both goal kinds are now computable, but from different clocks:
 *
 *  - A **time** goal is measured against [WorkoutSession], which is ours and is exact.
 *  - A **distance** goal is measured against the machine's own distance register, read over
 *    [MachineLink]. The console reports distance itself; it is not integrated here from speed.
 *
 * The temptation this type is built to defeat is still live: when the machine reading is stale or
 * absent it would be easy to fall back on elapsed x assumed speed. That produces a confident,
 * wrong number, and a confident wrong number about how far someone has run is worse than no
 * number. Every derived value below returns null rather than estimating, and the UI renders
 * [MachineLink.NO_READING].
 */
object WorkoutGoal {

    enum class Kind {
        /** No goal set. The session just counts up. */
        NONE,

        /** Aim at a duration. Fully computable today. */
        TIME,

        /** Aim at a distance, measured against the machine's own distance register. */
        DISTANCE,
    }

    @Volatile
    var kind: Kind = Kind.NONE
        private set

    /** Target duration in ms when [kind] is [Kind.TIME]. */
    @Volatile
    var targetMs: Long = 0L
        private set

    /** Target distance in miles when [kind] is [Kind.DISTANCE]. */
    @Volatile
    var targetMiles: Double = 0.0
        private set

    /**
     * True when a goal exists *and* a session exists to measure it against.
     *
     * A goal outliving its workout is what produced an orphaned progress ring reading "0%" over
     * an idle console: the number was not stale, it was about a workout that had already ended.
     */
    fun trackable(): Boolean =
        kind != Kind.NONE && WorkoutSession.state != WorkoutSession.State.IDLE

    fun clear() {
        kind = Kind.NONE
        targetMs = 0L
        targetMiles = 0.0
    }

    fun setTimeGoal(ms: Long) {
        if (ms <= 0L) return clear()
        kind = Kind.TIME
        targetMs = ms
        targetMiles = 0.0
    }

    fun setDistanceGoal(miles: Double) {
        if (miles <= 0.0) return clear()
        kind = Kind.DISTANCE
        targetMiles = miles
        targetMs = 0L
    }

    /**
     * Progress through the goal as 0.0..1.0, or null when it cannot be known.
     *
     * Null for a distance goal whenever the machine reading is stale or absent. Draw
     * [MachineLink.NO_READING] then, never a zero-length arc -- an empty ring reads as "you have
     * run nothing", which is a different claim from "we cannot see how far you have run".
     */
    fun progressFraction(): Double? = when (kind) {
        Kind.NONE -> null
        Kind.DISTANCE -> {
            val covered = MachineLink.distanceMiles
            if (targetMiles <= 0.0 || covered == null) null
            else (covered / targetMiles).coerceIn(0.0, 1.0)
        }
        Kind.TIME -> {
            if (targetMs <= 0L) null
            else (WorkoutSession.elapsedMs().toDouble() / targetMs.toDouble()).coerceIn(0.0, 1.0)
        }
    }

    /** Time left against a [Kind.TIME] goal, floored at zero. Null when unknowable. */
    fun remainingMs(): Long? = when (kind) {
        Kind.TIME -> (targetMs - WorkoutSession.elapsedMs()).coerceAtLeast(0L)
        else -> null
    }

    /** Distance left against a [Kind.DISTANCE] goal, floored at zero. Null when unknowable. */
    fun remainingMiles(): Double? = when (kind) {
        Kind.DISTANCE -> {
            val covered = MachineLink.distanceMiles
            if (targetMiles <= 0.0 || covered == null) null
            else (targetMiles - covered).coerceAtLeast(0.0)
        }
        else -> null
    }

    /**
     * Milliseconds until the goal completes at the pace achieved so far, or null.
     *
     * Distance uses the session's *average* pace rather than the instantaneous speed reading.
     * Instantaneous speed makes the estimate lurch every time the rider touches the belt controls,
     * and the number people actually want is "if the rest looks like what I have already done".
     *
     * Null whenever the answer would be fabricated rather than merely unknown:
     *  - a paused or idle session, where the finish time is not advancing with the clock;
     *  - no distance covered yet, where the average pace is a divide by zero;
     *  - a stationary belt, which projects to infinity.
     */
    fun etaMs(): Long? {
        if (WorkoutSession.state != WorkoutSession.State.RUNNING) return null
        return when (kind) {
            Kind.TIME -> remainingMs()
            Kind.DISTANCE -> {
                val remaining = remainingMiles() ?: return null
                if (remaining <= 0.0) return 0L
                val covered = MachineLink.distanceMiles ?: return null
                val elapsed = WorkoutSession.elapsedMs()
                if (covered <= 0.0 || elapsed <= 0L) return null
                val msPerMile = elapsed.toDouble() / covered
                (remaining * msPerMile).toLong()
            }
            Kind.NONE -> null
        }
    }

    /**
     * Wall-clock time the goal is expected to complete, as an epoch millisecond value, or null.
     *
     * The mixed clocks are intentional: elapsed is measured with the monotonic
     * `SystemClock.elapsedRealtime()` inside [WorkoutSession] so a time correction cannot make a
     * workout run backwards, while the ETA is *displayed* against the wall clock because that is
     * the thing a person reads off it.
     */
    fun etaEpochMs(): Long? {
        val remaining = etaMs() ?: return null
        return System.currentTimeMillis() + remaining
    }

    /** Short description of the target itself, e.g. "5.0 mi" or "30:00". */
    fun targetLabel(): String = when (kind) {
        Kind.NONE -> "No goal"
        Kind.TIME -> WorkoutSession.formatElapsed(targetMs)
        Kind.DISTANCE -> String.format(java.util.Locale.US, "%.2f mi", targetMiles)
    }
}
