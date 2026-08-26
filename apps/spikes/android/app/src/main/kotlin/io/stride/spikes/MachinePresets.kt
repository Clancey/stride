package io.stride.spikes

/**
 * How the incline quick-pick column is spaced.
 *
 * A rider-facing choice rather than a fixed rule, because the right answer depends on the machine.
 * On a console reporting -6% to 40% a flat 1% step is a forty-button column — long enough that
 * finding 15% means scrolling past fourteen buttons nobody wanted, and long enough that
 * [MachinePresets.MAX_PRESETS] silently eats the middle of it.
 *
 * [FINE] is the default and must stay byte-identical to what shipped before this choice existed.
 * Nobody who has not opted in gets a different column.
 *
 * Top-level rather than nested inside [MachinePresets], which is internal: this type appears in the
 * [MachineCommands] signature every transport implements, and a public interface cannot take an
 * internal parameter. The arithmetic stays internal; only the vocabulary is shared.
 */
enum class InclineSpacing {
    /** Every 1%, exactly as [MachinePresets.ladder] has always produced. */
    FINE,

    /** 5% climbing, 3% declining. See [MachinePresets.inclineLadder] for why the two differ. */
    COARSE,
    ;

    companion object {
        /** Unknown or absent values fall back to [FINE], the behaviour nobody chose. */
        fun parse(raw: String?): InclineSpacing =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FINE
    }
}

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

    /** The step [InclineSpacing.FINE] uses, and the one every ladder used before it was a choice. */
    const val INCLINE_STEP_FINE = 1.0

    /** [InclineSpacing.COARSE] climbing, at and above flat. */
    const val INCLINE_STEP_COARSE = 5.0

    /** [InclineSpacing.COARSE] descending, below flat. */
    const val DECLINE_STEP_COARSE = 3.0

    /**
     * The incline quick picks for a machine's reported range, at the rider's chosen [spacing].
     *
     * ## Why the two sides of zero are not spaced the same
     *
     * Decline and incline are not used the same way. A treadmill that declines at all rarely goes
     * past -6%, so a 5% step would offer a rider exactly one decline button and call it a range;
     * climbing runs to 40% on an incline trainer, where 5% is already eight buttons. Spacing them
     * identically means picking a step that is too coarse at one end or too fine at the other.
     *
     * So this is two [ladder] calls split at zero and merged, not one call with a single step. Each
     * half keeps [ladder]'s "both ends always present" guarantee, which is what puts the machine's
     * true minimum and true maximum on the column even when neither sits on a step boundary — the
     * -6 and the 40 in `-6, -3, 0, 5 … 40`.
     *
     * Zero is deliberately in both halves and de-duplicated. Flat is the value a rider reaches for
     * after a climb, and it is the one rung neither step is entitled to skip.
     *
     * ## What this may not do
     *
     * Widen anything. Every value comes out of a [ladder] call over a sub-range of `[min, max]`, so
     * no button can ask for more than the machine said it would take. The final [coerceIn] is not
     * redundant with that: [ceil1] and [floor1] carry a 1e-9 nudge to keep binary fractions from
     * rounding the wrong way, and that nudge can put a rounded end a hair outside the range it came
     * from. A hair is enough — `MachineCoordinator` clamps again on the way to the belt, but a
     * preset column that quietly disagrees with the machine's own limits is how a clamp ends up
     * being asked to be the only thing standing between a rider and a value the machine refused.
     */
    fun inclineLadder(min: Double, max: Double, spacing: InclineSpacing): List<Double> {
        // Verbatim, not "step = 1.0 happens to be the same". This is the guarantee that a rider who
        // never opens the setting sees exactly the column they saw before it existed.
        if (spacing == InclineSpacing.FINE) return ladder(min, max, INCLINE_STEP_FINE)

        // Repeated from [ladder] rather than left to it. Splitting first would ask each half about a
        // sub-range of a nonsensical one, and a half that happens to be valid — the decline side of
        // `-6 .. +Infinity` — would answer, turning "this machine's range did not decode" into a
        // short but confident column.
        if (!min.isFinite() || !max.isFinite() || max < min) return emptyList()

        val decline = if (min < 0.0) ladder(min, kotlin.math.min(max, 0.0), DECLINE_STEP_COARSE) else emptyList()
        val incline = if (max >= 0.0) ladder(kotlin.math.max(min, 0.0), max, INCLINE_STEP_COARSE) else emptyList()

        val merged = sortedSetOf<Double>(reverseOrder())
        (decline + incline).forEach { merged += it.coerceIn(min, max) }
        return capMerged(merged.toList())
    }

    /**
     * Trim a merged ladder back to [MAX_PRESETS], keeping the rungs that carry meaning.
     *
     * Each half of [inclineLadder] is capped on its own, so the merge can arrive with up to twice
     * the budget. Reaching that needs a range like -60% to 100%, which no treadmill has and a
     * misdecoded register absolutely can — the same reason [MAX_PRESETS] exists at all.
     *
     * Three rungs are pinned before anything is thinned: both ends, for the reason [ladder] pins
     * them, and flat. Flat is pinned because it is the one value on this column a rider looks for by
     * name, and a uniform thinning has no idea it is special — on a symmetric range it lands exactly
     * where the sampling skips.
     */
    private fun capMerged(values: List<Double>): List<Double> {
        if (values.size <= MAX_PRESETS) return values

        val kept = sortedSetOf<Double>(reverseOrder())
        kept += values.first()
        kept += values.last()
        if (values.any { it == 0.0 }) kept += 0.0

        val rest = values.filterNot { it in kept }
        val budget = MAX_PRESETS - kept.size
        if (budget > 0 && rest.isNotEmpty()) {
            // rest is always larger than the budget here (the list is over 40 and at most three
            // rungs are pinned), so this samples every nth rung rather than repeating one. Indexed
            // defensively anyway: a repeat would collapse in the set, but an overrun would throw on
            // a treadmill's only launcher.
            val stride = rest.size.toDouble() / budget
            for (i in 0 until budget) kept += rest[minOf((i * stride).toInt(), rest.lastIndex)]
        }
        return kept.toList()
    }

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
        if (ceiling < floor) return listOf(flatten(round1(min).coerceIn(min, max)))

        val out = sortedSetOf<Double>(reverseOrder())
        out += flatten(floor)
        out += flatten(ceiling)
        var v = kotlin.math.ceil(min / step) * step
        // Both ends are already in the set, so they survive the cap regardless of where it
        // bites — a truncated ladder that has lost its extremes is worse than one that has
        // lost part of its middle.
        while (v <= max + 1e-9 && out.size < MAX_PRESETS) {
            val rounded = round1(v)
            if (rounded in floor..ceiling) out += flatten(rounded)
            v += step
        }
        return out.toList()
    }

    /**
     * Collapse negative zero onto zero.
     *
     * Not cosmetic, and not hypothetical. [ceil1] subtracts 1e-9 before rounding up, so `ceil1(0.0)`
     * is **-0.0**, while the step walk reaches plain `0.0`. `-0.0 == 0.0` is true, but a sorted set
     * of boxed Doubles orders by `Double.compareTo`, which reports `-0.0 < 0.0` — so both survive,
     * and both render as "0".
     *
     * That put two identical-looking buttons on the bottom of the column of every machine reporting
     * a 0% incline floor, which is most treadmills without decline: `ladder(0.0, 12.0, 1.0)` was
     * fourteen rungs, not thirteen. Two buttons with the same label sending different values is the
     * exact failure the half-step formatting in `formatRailPreset` was written to prevent, arriving
     * by a different route.
     */
    private fun flatten(v: Double): Double = if (v == 0.0) 0.0 else v

    fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

    /** One decimal place, never rounding below [v] — used for a range's lower bound. */
    fun ceil1(v: Double): Double = kotlin.math.ceil(v * 10.0 - 1e-9) / 10.0

    /** One decimal place, never rounding above [v] — used for a range's upper bound. */
    fun floor1(v: Double): Double = kotlin.math.floor(v * 10.0 + 1e-9) / 10.0
}
