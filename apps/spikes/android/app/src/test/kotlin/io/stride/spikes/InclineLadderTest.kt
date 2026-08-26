package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rider-chosen incline spacing (issue #27), and the guarantees it must not break.
 *
 * Two things are being pinned here and they pull in opposite directions.
 *
 * The first is that **the default is untouched**. This is an opt-in setting on a treadmill's only
 * launcher, and a rider who never opened it must find exactly the column they had before the update.
 * That is not "1.0 is passed somewhere" — it is that the whole list is identical, which is why the
 * fine cases below compare against `ladder(min, max, 1.0)` itself rather than against a literal.
 *
 * The second is that the coarse column is still built from the machine's own numbers. Every failure
 * mode here is silent: a button outside the reported range looks like a control that does nothing, a
 * missing zero takes flat away from a rider mid-climb, an uncapped merge hands the UI eighty buttons
 * to lay out, and an empty column is a rail that opens onto nothing. None of them throw.
 */
class InclineLadderTest {

    private val fine = InclineSpacing.FINE
    private val coarse = InclineSpacing.COARSE

    private fun coarse(min: Double, max: Double) = MachinePresets.inclineLadder(min, max, coarse)

    // ---- the default may not move ------------------------------------------------------------

    /**
     * The guarantee the whole setting rests on. Compared against the shared helper rather than a
     * hand-written list, so this keeps holding if the helper is ever legitimately changed.
     */
    @Test
    fun `fine is the ladder that shipped, value for value`() {
        listOf(
            -6.0 to 40.0,
            -3.0 to 12.0,
            0.0 to 12.0,
            0.5 to 4.0,
            2.5 to 2.7,
            -10.0 to -2.0,
        ).forEach { (min, max) ->
            assertEquals(
                "fine must equal the 1% ladder for $min..$max",
                MachinePresets.ladder(min, max, 1.0),
                MachinePresets.inclineLadder(min, max, fine),
            )
        }
    }

    /** An unreadable stored value resolves to the column nobody chose, never to the coarse one. */
    @Test
    fun `an unknown or missing spacing parses as fine`() {
        assertEquals(fine, InclineSpacing.parse(null))
        assertEquals(fine, InclineSpacing.parse(""))
        assertEquals(fine, InclineSpacing.parse("every-5-percent"))
        // Case-insensitive, because the value crosses a platform channel as lower case and comes
        // back from SharedPreferences as the enum name.
        assertEquals(coarse, InclineSpacing.parse("coarse"))
        assertEquals(coarse, InclineSpacing.parse("COARSE"))
    }

    // ---- the shape the issue asked for --------------------------------------------------------

    /** The worked example from issue #27: an X22i reporting -6% to 40%. */
    @Test
    fun `a wide incline trainer range gives 5 percent up and 3 percent down`() {
        assertEquals(
            listOf(40.0, 35.0, 30.0, 25.0, 20.0, 15.0, 10.0, 5.0, 0.0, -3.0, -6.0),
            coarse(-6.0, 40.0),
        )
    }

    /** The same range at the default, for contrast: 40 buttons with the middle eaten by the cap. */
    @Test
    fun `the fine column on that machine is the one the issue is about`() {
        val out = MachinePresets.inclineLadder(-6.0, 40.0, fine)
        assertEquals(MachinePresets.MAX_PRESETS, out.size)
        assertTrue("coarse must be dramatically shorter", coarse(-6.0, 40.0).size < out.size / 3)
    }

    // ---- boundaries ---------------------------------------------------------------------------

    /** A machine with no decline must not be handed decline buttons. */
    @Test
    fun `a range that is entirely at or above flat has no negative rungs`() {
        assertEquals(listOf(15.0, 10.0, 5.0, 0.0), coarse(0.0, 15.0))
        assertEquals(listOf(12.0, 10.0, 5.0, 2.0), coarse(2.0, 12.0))
        coarse(0.0, 15.0).forEach { assertTrue("$it is below the machine's floor", it >= 0.0) }
    }

    /**
     * A range entirely below flat must not be handed a zero.
     *
     * Zero is pinned everywhere else in this file, which is exactly why it needs pinning *out* here:
     * offering flat on a machine whose maximum is -2% is a button the machine would refuse.
     */
    @Test
    fun `a range that is entirely below flat has no non-negative rungs`() {
        assertEquals(listOf(-2.0, -3.0, -6.0, -9.0, -10.0), coarse(-10.0, -2.0))
        coarse(-10.0, -2.0).forEach { assertTrue("$it is above the machine's ceiling", it <= -2.0) }
    }

    /** A machine that reports one incline and nothing else still gets a button, not a blank rail. */
    @Test
    fun `a range whose ends are equal offers exactly one button`() {
        assertEquals(listOf(5.0), coarse(5.0, 5.0))
        assertEquals(listOf(0.0), coarse(0.0, 0.0))
        assertEquals(listOf(-4.0), coarse(-4.0, -4.0))
    }

    /**
     * Never an empty column where the default would have offered one.
     *
     * A rail with no pills in it is a toggle that opens onto nothing — the failure `railPresetEntries`
     * was written to prevent, arriving from the generator instead.
     */
    @Test
    fun `coarse is never empty where fine is not`() {
        listOf(
            -6.0 to 40.0,
            0.0 to 0.0,
            2.5 to 2.7,
            2.55 to 2.57,
            -0.05 to 0.04,
            -10.0 to -2.0,
            0.0 to 0.5,
        ).forEach { (min, max) ->
            if (MachinePresets.inclineLadder(min, max, fine).isNotEmpty()) {
                assertTrue(
                    "coarse gave an empty column for $min..$max",
                    coarse(min, max).isNotEmpty(),
                )
            }
        }
    }

    /**
     * A range too narrow for either step still offers the one value its ends agree on.
     *
     * 2.55 rounds to 2.6, above the machine's own maximum — a button that could only be refused.
     */
    @Test
    fun `a sub-tenth range stays inside itself`() {
        val out = coarse(2.55, 2.57)
        assertEquals(1, out.size)
        assertTrue("offered ${out[0]} for a 2.55-2.57 machine", out[0] in 2.55..2.57)
    }

    // ---- what it may never do -----------------------------------------------------------------

    /**
     * The safety-facing one: quick picks may only ever be *inside* what the machine said it takes.
     *
     * `MachineCoordinator` clamps again before anything reaches the belt, and that is deliberately
     * not what this test relies on. A column that disagrees with the machine's own limits leaves the
     * clamp as the only thing between a rider and a value the machine refused, and the house rule is
     * that clamps may be tightened and never widened.
     */
    @Test
    fun `no rung ever falls outside the machine's reported range`() {
        listOf(
            -6.0 to 40.0,
            -3.0 to 12.0,
            0.0 to 12.0,
            -0.05 to 0.04,
            0.4 to 0.6,
            -10.0 to -2.0,
            2.55 to 2.57,
            -60.0 to 100.0,
        ).forEach { (min, max) ->
            coarse(min, max).forEach { rung ->
                assertTrue("$rung is outside $min..$max", rung in min..max)
            }
        }
    }

    /**
     * Both halves are capped at 40 on their own, so the merge can arrive with up to twice the
     * budget. A range this wide is not a treadmill — it is a misdecoded register, which is the case
     * [MachinePresets.MAX_PRESETS] exists for.
     */
    @Test
    fun `a wildly decoded range is capped, keeping both ends and flat`() {
        val out = coarse(-3000.0, 3000.0)

        assertTrue("${out.size} buttons", out.size <= MachinePresets.MAX_PRESETS)
        assertEquals(3000.0, out.first(), 1e-9)
        assertEquals(-3000.0, out.last(), 1e-9)
        // Flat survives a thinning that has no idea it is special. On a symmetric range a uniform
        // sample lands either side of it.
        assertTrue("flat was thinned away", out.contains(0.0))
        assertEquals("no duplicates survived the cap", out.distinct().size, out.size)
        assertEquals("still descending", out.sortedDescending(), out)
    }

    /** Nonsense in, nothing out — and not a confident half-column built from the half that parsed. */
    @Test
    fun `a nonsense range produces nothing rather than the half of it that decoded`() {
        assertTrue(coarse(10.0, 2.0).isEmpty())
        assertTrue(coarse(Double.NaN, 5.0).isEmpty())
        assertTrue(coarse(-6.0, Double.NaN).isEmpty())
        // The one the split would otherwise get wrong: the decline half of -6..+Infinity is
        // perfectly valid on its own, so splitting before validating turns "this did not decode"
        // into a short but confident column.
        assertTrue(coarse(-6.0, Double.POSITIVE_INFINITY).isEmpty())
        assertTrue(coarse(Double.NEGATIVE_INFINITY, 40.0).isEmpty())
    }

    /** Descending, matching the GlassOS preset order the column is laid out in. */
    @Test
    fun `rungs come back highest first`() {
        val out = coarse(-6.0, 40.0)
        assertEquals(out.sortedDescending(), out)
    }

    /**
     * The overlay's coarse fallback column, which is this function over the fine fallback's ends.
     *
     * Pinned here because the value is otherwise invisible: `OverlayService` derives it rather than
     * spelling it out, precisely so the two cannot drift, and that leaves nothing to read. A rider
     * sees this list whenever the machine has published nothing yet — every idle GlassOS console,
     * and every moment on the direct path before the probe lands.
     */
    @Test
    fun `the overlay's coarse fallback is five buttons over the same ends as the fine one`() {
        assertEquals(listOf(12.0, 10.0, 5.0, 0.0, -3.0), coarse(-3.0, 12.0))
    }

    // ---- negative zero ------------------------------------------------------------------------

    /**
     * Flat appears once, not twice.
     *
     * [MachinePresets.ceil1] subtracts 1e-9 before rounding up, so `ceil1(0.0)` is `-0.0` while the
     * step walk reaches plain `0.0`. `-0.0 == 0.0`, but a sorted set of boxed Doubles orders by
     * `Double.compareTo`, which reports `-0.0 < 0.0` — so both survived, and `formatRailPreset`
     * renders both as "0".
     *
     * That was live on the default column of every machine reporting a 0% incline floor, which is
     * most treadmills without decline: `ladder(0.0, 12.0, 1.0)` returned fourteen rungs, not
     * thirteen, the last two both labelled "0". Two buttons with the same label sending different
     * values is the failure `formatRailPreset`'s half-step handling exists to prevent, arriving by a
     * different route — and the coarse column, which splits at zero on purpose, makes it likelier
     * rather than rarer.
     */
    @Test
    fun `flat is a single button, however the range reaches it`() {
        listOf(
            MachinePresets.ladder(0.0, 12.0, 1.0),
            MachinePresets.ladder(0.0, 40.0, 5.0),
            MachinePresets.inclineLadder(0.0, 12.0, fine),
            coarse(0.0, 12.0),
            coarse(-6.0, 40.0),
            coarse(-0.05, 0.04),
        ).forEach { out ->
            assertEquals("two zeroes in $out", 1, out.count { it == 0.0 })
            // Not merely de-duplicated: the surviving value must be positive zero, or the next
            // sorted set it passes through re-splits it.
            out.filter { it == 0.0 }.forEach {
                assertTrue("negative zero survived in $out", 1.0 / it > 0.0)
            }
        }
    }

    /** The regression the fix above is measured by, stated as a count. */
    @Test
    fun `a zero-floor machine gets thirteen rungs, not fourteen`() {
        assertEquals(
            listOf(12.0, 11.0, 10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0, 0.0),
            MachinePresets.ladder(0.0, 12.0, 1.0),
        )
    }
}
