package io.stride.spikes

/**
 * Quick-pick ladders derived from a machine's own reported range.
 *
 * ## Why this is shared rather than owned by one transport
 *
 * GlassOS publishes a list of quick picks outright. Every other transport publishes only a minimum,
 * a maximum and a step, and the buttons have to be derived. That derivation was written once for the
 * FitPro register path and is identical for FTMS, which reports exactly the same three numbers in
 * `Supported Speed Range` and `Supported Inclination Range`.
 *
 * It lives here rather than in either driver because two copies of this arithmetic would drift, and
 * every way it can drift is silent: a wrong step gives a rider buttons at 3.7 mph, an uncapped loop
 * gives them three thousand buttons, and a dropped floor puts the slowest button above the machine's
 * slowest walk. None of those throw. All of them are wrong on a treadmill.
 */
internal object MachinePresets {

    /**
     * The most buttons any ladder may produce.
     *
     * These bounds come off a wire. A machine that reports a 0-3000 range through a decoding error
     * would otherwise hand the UI three thousand buttons to lay out.
     */
    const val MAX_PRESETS = 40

    /**
     * Whole-[step] values within a range, highest first, with both ends always present.
     *
     * Descending to match the GlassOS preset order, which the UI lays out top-down.
     *
     * The two ends are included explicitly rather than left to the step arithmetic, because a range
     * rarely lands on step boundaries and both ends matter more than the middle: the fastest speed a
     * machine offers is the one riders reach for, and the slowest is the one they need to walk. A
     * pure step walk from the floor drops the maximum whenever the range is not a whole number of
     * steps, and produces *nothing at all* for a range too narrow to contain a step — a 2.5 to 2.7
     * incline would leave a rider with no buttons.
     *
     * Ends are rounded inward to one decimal, never outward: a button that asks for slightly less
     * than the machine's minimum is a button that gets clamped or refused, which looks like a broken
     * control rather than a rounded one.
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

    fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

    /** One decimal place, never rounding below [v] — used for a range's lower bound. */
    fun ceil1(v: Double): Double = kotlin.math.ceil(v * 10.0 - 1e-9) / 10.0

    /** One decimal place, never rounding above [v] — used for a range's upper bound. */
    fun floor1(v: Double): Double = kotlin.math.floor(v * 10.0 + 1e-9) / 10.0
}
