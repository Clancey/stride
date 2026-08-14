package io.stride.spikes

/**
 * A goal for the current session, plus the progress and ETA derived from it.
 *
 * This type exists because "ETA at current pace" is two very different questions depending on
 * what you are aiming at, and only one of them has an honest answer in Phase 0:
 *
 *  - A **time** goal ("run 30 minutes") is measured entirely against [WorkoutSession], which is
 *    ours and is truthful. Progress and ETA are exact, not estimated.
 *  - A **distance** goal ("run 5 km") requires distance and pace. Stride cannot read either — see
 *    [MachineLink]. There is no honest ETA, so every derived value here returns null and the UI
 *    renders [MachineLink.NO_READING].
 *
 * The temptation this type is built to defeat: distance ≈ elapsed × assumed_speed. That produces a
 * confident, wrong number, and on a treadmill a confident wrong number about how far you have run
 * is worse than no number at all. Distance goals stay unknowable until the machine link lands.
 */
object WorkoutGoal {

    enum class Kind {
        /** No goal set. The session just counts up. */
        NONE,

        /** Aim at a duration. Fully computable today. */
        TIME,

        /** Aim at a distance. Settable, but unknowable until [MachineLink] can read distance. */
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
     * Null for [Kind.DISTANCE] because we have no distance reading. Draw [MachineLink.NO_READING],
     * never a zero-length progress ring — an empty ring reads as "you have run nothing", which is
     * a claim we are not entitled to make.
     */
    fun progressFraction(): Double? = when (kind) {
        Kind.NONE -> null
        Kind.DISTANCE -> null
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

    /**
     * Wall-clock time the goal is expected to complete, as an epoch millisecond value, or null.
     *
     * Two deliberate nulls:
     *  - **Distance goals**: no pace, no ETA. Never extrapolate one.
     *  - **A paused or idle session**: the finish time is not moving with the clock, so any value we
     *    showed would silently age into a lie while the user is standing still. Show "Paused",
     *    not a stale time.
     *
     * Note the mixed clocks are intentional: elapsed is measured with the monotonic
     * `SystemClock.elapsedRealtime()` inside [WorkoutSession] so a time correction cannot make a
     * workout run backwards, while the ETA is *displayed* against the wall clock because that is
     * the thing a person reads off it.
     */
    fun etaEpochMs(): Long? {
        if (WorkoutSession.state != WorkoutSession.State.RUNNING) return null
        val remaining = remainingMs() ?: return null
        return System.currentTimeMillis() + remaining
    }
}
