package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rung must land on the round tenth its own register value means, not fall a hair short of it.
 *
 * FitPro's `KPH`/`GRADE` family holds hundredths of a km/h or a percent, so a machine-reported limit
 * that means some whole or one-decimal value can decode a little short of it — measured live, an
 * X22i's 19.31 kph maximum decodes to 11.9987 mph, not 12.0. `ceil1`/`floor1` used to treat that
 * shortfall the same as a genuinely lower limit and floor the top rung to 11.9, one whole tenth below
 * what the console's own physical buttons offer. Their quantization margin exists to see past exactly
 * this, without letting a rung ask for anything the wire quantizes differently than the register's own
 * reported limit already does — see the comment on `MachinePresets.QUANTIZATION_EPSILON` for why that
 * is provably safe rather than merely convenient.
 */
class RailQuantizationTest {

    /** The exact live measurement: 19.31 kph is 12 mph's own nearest representable value. */
    @Test
    fun `an X22i's reported 19_31 kph maximum offers a 12 mph rung`() {
        val maxMph = 19.31 / FitProValues.KPH_PER_MPH
        val ladder = MachinePresets.speedLadder(min = 0.5, max = maxMph)
        assertEquals("expected 12.0 as the top rung, got $ladder", 12.0, ladder.first(), 1e-9)
    }

    /**
     * The minimum side of the same bug: a register value quantized just *above* a tenth used to be
     * ceilinged to the *next* tenth up, skipping the one it actually meant.
     *
     * 0.6 mph encodes to 97 centikph (round(0.6 × 1.609344 × 100) = 97), which decodes back to
     * 0.60273 mph — 0.00273 mph over 0.6, not under. `ceil1` on the raw value used to jump straight
     * to 0.7.
     */
    @Test
    fun `a minimum quantized just above a tenth still offers that tenth, not the next one up`() {
        val quantizedMinMph = 97.0 / 100.0 / FitProValues.KPH_PER_MPH
        val ladder = MachinePresets.speedLadder(min = quantizedMinMph, max = 6.0)
        assertEquals("expected 0.6 as the bottom rung, got $ladder", 0.6, ladder.last(), 1e-9)
    }

    /** A limit that is genuinely, meaningfully short of a tenth must still floor to the one below. */
    @Test
    fun `a limit meaningfully below a tenth is not pulled up to it`() {
        // 11.9 mph is 0.0987 mph below 12.0 -- more than an order of magnitude past the quantization
        // margin, so this must floor exactly the way it always has.
        val ladder = MachinePresets.speedLadder(min = 0.5, max = 11.9)
        assertEquals(11.9, ladder.first(), 1e-9)
    }

    /** The same fix, for incline -- exercised even though GRADE has no unit-conversion residual. */
    @Test
    fun `incline ends still land on the machine's own reported limits`() {
        val ladder = MachinePresets.inclineLadder(-2.0, 10.0, InclineSpacing.FINE)
        assertEquals(10.0, ladder.first(), 1e-9)
        assertEquals(-2.0, ladder.last(), 1e-9)
    }

    /** [InclineLadderTest]'s sub-tenth-range case must still come back empty with the wider margin. */
    @Test
    fun `a genuinely sub-tenth range is still empty`() {
        assertTrue(MachinePresets.inclineLadder(2.55, 2.57, InclineSpacing.FINE).isEmpty())
    }
}
