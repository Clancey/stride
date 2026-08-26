package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Typing a value on a column of whole-number pills.
 *
 * Tapping 5 and then 5 again asks for 5.5; 6 then 4 asks for 6.4. The rails only carry whole
 * numbers, so without this the half steps a treadmill actually runs at are unreachable from Stride.
 *
 * The risk is the opposite mistake — reading "I changed my mind, 4" as "6.4" — so the window is the
 * whole safety argument, and every branch below is about refusing to compose when the evidence for
 * it is weak.
 */
class ComposeSetpointTest {

    private val window = COMPOSE_WINDOW_MS

    private fun compose(previous: Double?, tapped: Double, gapMs: Long): Double? =
        composeSetpoint(
            previous = previous,
            previousAtMs = 10_000L,
            tapped = tapped,
            nowMs = 10_000L + gapMs,
            windowMs = window,
        )

    /** The two examples, exactly as asked for. */
    @Test
    fun `a quick second tap types a tenth`() {
        assertEquals(5.5, compose(5.0, 5.0, 200))
        assertEquals(6.4, compose(6.0, 4.0, 200))
    }

    /** Zero is a digit: 7 then 0 is a deliberate way to say plain 7.0. */
    @Test
    fun `zero composes`() {
        assertEquals(7.0, compose(7.0, 0.0, 200))
    }

    /** Nothing tapped before is an ordinary pick. */
    @Test
    fun `the first tap of all is a plain pick`() {
        assertNull(compose(null, 5.0, 0))
    }

    /** Past the window it is a change of mind, and must be taken literally. */
    @Test
    fun `a slow second tap is a new choice`() {
        assertNull(compose(6.0, 4.0, window))
        assertNull(compose(6.0, 4.0, window + 500))
    }

    /** Right up to the boundary still composes; the edge is exclusive. */
    @Test
    fun `the window edge is exclusive`() {
        assertEquals(6.4, compose(6.0, 4.0, window - 1))
    }

    /**
     * A machine that publishes its own fractional presets is choosing its steps.
     *
     * 7.5 has no digit to extend, and re-typing it would be inventing a value the machine never
     * offered.
     */
    @Test
    fun `a fractional first tap does not compose`() {
        assertNull(compose(7.5, 5.0, 200))
    }

    /** Two digits cannot be a tenths place. */
    @Test
    fun `a two-digit second tap is a new choice`() {
        assertNull(compose(6.0, 10.0, 200))
        assertNull(compose(6.0, 12.0, 200))
    }

    /**
     * On the incline column the sign is kept and the magnitude grows.
     *
     * -2 then 5 is how -2.5 would be typed, and how it reads back.
     */
    @Test
    fun `a negative first tap keeps its sign`() {
        assertEquals(-2.5, compose(-2.0, 5.0, 200))
        assertEquals(-1.0, compose(-1.0, 0.0, 200))
    }

    /** A negative second tap is not a digit. */
    @Test
    fun `a negative second tap is a new choice`() {
        assertNull(compose(3.0, -1.0, 200))
    }

    /** Binary arithmetic must not leak into a label: 6 + 4/10 is not exactly 6.4. */
    @Test
    fun `composed values are clean to one decimal`() {
        assertEquals("6.4", formatRailPreset(compose(6.0, 4.0, 200)!!))
        assertEquals("5.5", formatRailPreset(compose(5.0, 5.0, 200)!!))
        assertEquals("12.9", formatRailPreset(compose(12.0, 9.0, 200)!!))
    }

    /** A clock that goes backwards is not evidence of anything. */
    @Test
    fun `a tap from the past does not compose`() {
        assertNull(compose(6.0, 4.0, -50))
    }
}
