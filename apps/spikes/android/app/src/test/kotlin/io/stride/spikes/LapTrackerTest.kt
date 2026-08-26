package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the marker on the track floor is allowed to be.
 *
 * Written as tests because every failure here is silent and visual: the marker either sits on the
 * start line during a workout, or claims a position for a machine that stopped answering minutes
 * ago. Neither throws, and neither is obvious from a screenshot.
 */
class LapTrackerTest {

    private fun tracker() = LapTracker(lapMiles = 0.25, holdMs = 12_000L)

    @Test
    fun `distance maps onto the lap it falls in`() {
        val lap = tracker()

        assertEquals(0f, lap.sample(0.0, 0L)!!.progress, 0.0001f)
        assertEquals(1, lap.sample(0.0, 0L)!!.lap)

        val half = lap.sample(0.125, 1_000L)!!
        assertEquals(0.5f, half.progress, 0.0001f)
        assertEquals(1, half.lap)

        val third = lap.sample(0.5625, 2_000L)!!
        assertEquals(0.25f, third.progress, 0.0001f)
        assertEquals(3, third.lap)
    }

    @Test
    fun `a completed lap starts the next one rather than ending the last`() {
        val lap = tracker()
        val boundary = lap.sample(0.25, 0L)!!

        assertEquals(0f, boundary.progress, 0.0001f)
        assertEquals(2, boundary.lap)
    }

    @Test
    fun `a dropped reading holds the last position instead of snapping to the start line`() {
        val lap = tracker()
        lap.sample(0.2, 0L)

        val gap = lap.sample(null, 3_000L)

        assertNotNull(gap)
        assertEquals(0.8f, gap!!.progress, 0.0001f)
    }

    @Test
    fun `a machine that stops answering clears the position`() {
        val lap = tracker()
        lap.sample(0.2, 0L)

        assertNotNull(lap.sample(null, 12_000L))
        assertNull(lap.sample(null, 12_001L))
    }

    @Test
    fun `nothing is claimed before the first reading arrives`() {
        assertNull(tracker().sample(null, 0L))
    }

    @Test
    fun `a reset drops the held position`() {
        val lap = tracker()
        lap.sample(0.2, 0L)
        lap.reset()

        assertNull(lap.sample(null, 1_000L))
    }

    @Test
    fun `nonsense readings are refused rather than drawn`() {
        val lap = tracker()

        assertNull(lap.sample(-1.0, 0L))
        assertNull(lap.sample(Double.NaN, 0L))
        assertNull(lap.sample(Double.POSITIVE_INFINITY, 0L))
    }

    @Test
    fun `a lap length of zero cannot divide the track`() {
        assertNull(LapTracker(lapMiles = 0.0).sample(1.0, 0L))
    }
}

/**
 * Which colour the track floor is painted on which lap.
 *
 * A lap used to end by erasing itself — the progress band collapsed at the boundary and the plain
 * lane came back — so a rider watched four hundred metres of work disappear once a lap. The fix is
 * that a finished lap's colour *becomes* the track, and the next lap paints the next colour over
 * it, cycling forever.
 *
 * Tested here for the reason [LapTracker]'s own doc gives: this is pure arithmetic over a lap
 * counter, and the alternative to a JVM test is a rider on a treadmill counting to five and
 * squinting. The off-by-one is the whole risk. [LapTracker] hands out 1 for the first lap, not 0,
 * so a palette indexed straight off the lap number is one entry ahead of where it should be — and
 * the symptom is that the track never once looks the way it did before this existed.
 */
class LapPaletteTest {

    /** Lap 1 is what the track looked like before any of this: nothing has painted anything yet. */
    @Test
    fun `the first lap is the original track`() {
        assertEquals(LapPalette.UNPAINTED, LapPalette.lane(1))
        assertEquals(0, LapPalette.bandIndex(1))
    }

    /**
     * The property the whole feature is: what lap N painted is what lap N+1 runs over.
     *
     * Checked across two full turns of the cycle rather than a couple of laps, because an
     * off-by-one that only bites on the wrap looks perfect for the first four laps.
     */
    @Test
    fun `each lap runs on the colour the lap before it painted`() {
        for (lap in 1..(LapPalette.size * 2 + 1)) {
            assertEquals(
                "lap ${lap + 1} should be running on lap $lap's colour",
                LapPalette.band(lap),
                LapPalette.lane(lap + 1),
            )
        }
    }

    /** And it is genuinely a cycle, so an eleventh lap is as well defined as a second. */
    @Test
    fun `the palette wraps rather than running out`() {
        val size = LapPalette.size
        assertEquals(LapPalette.band(1), LapPalette.band(1 + size))
        assertEquals(LapPalette.band(1), LapPalette.band(1 + size * 2))
        assertEquals(LapPalette.lane(2), LapPalette.lane(2 + size))
        // The step across the wrap is still a step: the colour has to change there too, or the
        // rider sees the reset this exists to remove, just once every `size` laps.
        assertNotEquals(LapPalette.band(size), LapPalette.band(size + 1))
    }

    /**
     * A lap number that should be impossible must still land inside the palette.
     *
     * [LapTracker] cannot emit zero or a negative lap today, but it derives one from a distance
     * register on a machine Stride does not control, and an index off the end of the list here
     * throws inside `onDraw` — which takes the overlay down, and with it the only Back and Home
     * button this console has. `Int.MIN_VALUE` is in because reducing `lap - 1` before the modulo
     * rather than after is the only thing standing between it and a negative index.
     */
    @Test
    fun `no lap number can index off the end of the palette`() {
        for (lap in intArrayOf(Int.MIN_VALUE, -7, -1, 0, 1, Int.MAX_VALUE)) {
            assertTrue("band index for lap $lap", LapPalette.bandIndex(lap) in 0 until LapPalette.size)
            assertTrue("lane index for lap $lap", LapPalette.laneIndex(lap) in 0 until LapPalette.size)
        }
    }

    /** Anything at or before the first lap is unpainted ground, not a wrapped-around colour. */
    @Test
    fun `nothing before the first lap has painted anything`() {
        assertEquals(LapPalette.UNPAINTED, LapPalette.lane(0))
        assertEquals(LapPalette.UNPAINTED, LapPalette.lane(-3))
    }

    /**
     * Every entry keeps the gradient shape the original two had.
     *
     * `TrackFloorView.buildShaders` records why: a flat opaque band reads as a plastic ring lying
     * on the glass, and letting the far side sink into whatever is playing underneath is what makes
     * it read as ground receding away from the rider. That is a property of the alphas, so a new
     * palette entry pasted in with a flat alpha would quietly cost the surface its depth.
     */
    @Test
    fun `every colour is translucent and brightest at the near edge`() {
        val fills = (1..LapPalette.size).map { LapPalette.band(it) } + LapPalette.UNPAINTED
        for (fill in fills) {
            val far = alphaOf(fill.far)
            val mid = alphaOf(fill.mid)
            val near = alphaOf(fill.near)
            assertTrue("far edge should be see-through, was $far", far < 160)
            assertTrue("alpha should rise toward the rider", far < mid && mid < near)
            assertTrue("nothing here is opaque, was $near", near < 255)
        }
    }

    private fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF
}
