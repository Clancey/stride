package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
