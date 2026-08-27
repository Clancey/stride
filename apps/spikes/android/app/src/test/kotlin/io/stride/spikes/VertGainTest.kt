package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VertGainTest {

    private fun snapshot(
        workoutId: String? = "workout-1",
        distanceMiles: Double? = null,
        inclinePercent: Double? = null,
    ) = GlassOsClient.Snapshot(
        consoleState = null,
        workoutId = workoutId,
        speedMph = null,
        inclinePercent = inclinePercent,
        distanceMiles = distanceMiles,
        paceMinPerMile = null,
        elapsedSeconds = null,
        calories = null,
        speedWritable = null,
        inclineWritable = null,
        fanWritable = null,
    )

    private fun fold(
        previous: MachineLink.VertGainState?,
        workoutId: String? = "workout-1",
        distanceMiles: Double? = null,
        inclinePercent: Double? = null,
    ) = MachineLink.foldVertGain(
        previous,
        snapshot(workoutId, distanceMiles, inclinePercent),
    )

    @Test
    fun `first usable poll establishes zero and a distance baseline`() {
        assertEquals(
            MachineLink.VertGainState("workout-1", 1.25, 0.0),
            fold(null, distanceMiles = 1.25, inclinePercent = 8.0),
        )
    }

    @Test
    fun `incline is integrated over each distance interval in feet`() {
        var state = fold(null, distanceMiles = 0.0, inclinePercent = 0.0)
        state = fold(state, distanceMiles = 0.1, inclinePercent = 5.0)
        state = fold(state, distanceMiles = 0.2, inclinePercent = 10.0)

        assertEquals(79.2, state.feet!!, 1e-9)
    }

    @Test
    fun `duplicate distance polls add nothing`() {
        var state = fold(null, distanceMiles = 0.5, inclinePercent = 12.0)
        state = fold(state, distanceMiles = 0.5, inclinePercent = 12.0)
        state = fold(state, distanceMiles = 0.5, inclinePercent = 20.0)

        assertEquals(0.0, state.feet!!, 0.0)
        assertEquals(0.5, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `decline advances the baseline without reducing accrued climb`() {
        var state = fold(null, distanceMiles = 0.0, inclinePercent = 10.0)
        state = fold(state, distanceMiles = 0.1, inclinePercent = 10.0)
        state = fold(state, distanceMiles = 0.2, inclinePercent = -6.0)

        assertEquals(52.8, state.feet!!, 1e-9)
        assertEquals(0.2, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `missing distance preserves the baseline until a complete poll`() {
        var state = fold(null, distanceMiles = 0.0, inclinePercent = 10.0)
        state = fold(state, distanceMiles = null, inclinePercent = 10.0)
        state = fold(state, distanceMiles = 0.2, inclinePercent = 10.0)

        assertEquals(105.6, state.feet!!, 1e-9)
    }

    @Test
    fun `missing incline preserves the baseline and full distance gap`() {
        var state = fold(null, distanceMiles = 0.0, inclinePercent = 10.0)
        state = fold(state, distanceMiles = 0.1, inclinePercent = null)
        state = fold(state, distanceMiles = 0.2, inclinePercent = 10.0)

        assertEquals(105.6, state.feet!!, 1e-9)
    }

    @Test
    fun `non-finite readings are unusable and preserve the baseline`() {
        var state = fold(null, distanceMiles = 0.0, inclinePercent = 10.0)
        state = fold(state, distanceMiles = Double.NaN, inclinePercent = 10.0)
        state = fold(state, distanceMiles = 0.1, inclinePercent = Double.POSITIVE_INFINITY)
        state = fold(state, distanceMiles = 0.2, inclinePercent = 10.0)

        assertEquals(105.6, state.feet!!, 1e-9)
    }

    @Test
    fun `backwards distance noise neither subtracts nor creates double counting`() {
        var state = fold(null, distanceMiles = 1.0, inclinePercent = 10.0)
        state = fold(state, distanceMiles = 1.1, inclinePercent = 10.0)
        state = fold(state, distanceMiles = 1.05, inclinePercent = 10.0)
        state = fold(state, distanceMiles = 1.2, inclinePercent = 10.0)

        assertEquals(105.6, state.feet!!, 1e-9)
        assertEquals(1.2, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `coarse and large forward distance jumps use the same integral`() {
        var state = fold(null, distanceMiles = 2.0, inclinePercent = 0.0)
        state = fold(state, distanceMiles = 3.5, inclinePercent = 12.0)

        assertEquals(950.4, state.feet!!, 1e-9)
    }

    @Test
    fun `new workout identity resets gain and baseline`() {
        var state = fold(null, workoutId = "first", distanceMiles = 0.0, inclinePercent = 10.0)
        state = fold(state, workoutId = "first", distanceMiles = 0.1, inclinePercent = 10.0)
        state = fold(state, workoutId = "second", distanceMiles = 0.0, inclinePercent = 5.0)

        assertEquals(MachineLink.VertGainState("second", 0.0, 0.0), state)
    }

    @Test
    fun `transition to no workout resets even when metrics are unavailable`() {
        var state = fold(null, workoutId = "first", distanceMiles = 0.0, inclinePercent = 10.0)
        state = fold(state, workoutId = "first", distanceMiles = 0.1, inclinePercent = 10.0)
        state = fold(state, workoutId = null, distanceMiles = null, inclinePercent = null)

        assertEquals(MachineLink.VertGainState(null, null, 0.0), state)
    }

    @Test
    fun `idle metric leftovers never accrue and a new workout starts clean`() {
        var state = fold(null, workoutId = null, distanceMiles = 4.0, inclinePercent = 10.0)
        state = fold(state, workoutId = null, distanceMiles = 4.1, inclinePercent = 10.0)
        assertEquals(0.0, state.feet!!, 0.0)

        state = fold(state, workoutId = "new", distanceMiles = 0.0, inclinePercent = 10.0)

        assertEquals(MachineLink.VertGainState("new", 0.0, 0.0), state)
    }

    @Test
    fun `unusable first workout poll has no invented baseline or zero`() {
        val state = fold(null, distanceMiles = 0.25, inclinePercent = null)

        assertNull(state.feet)
        assertNull(state.distanceMiles)
    }

    @Test
    fun `first complete poll after loading establishes a truthful zero`() {
        var state = fold(null, distanceMiles = 0.25, inclinePercent = null)
        state = fold(state, distanceMiles = 0.3, inclinePercent = 8.0)

        assertEquals(0.0, state.feet!!, 0.0)
        assertEquals(0.3, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `idle transition publishes a real zero without metric readings`() {
        val state = fold(null, workoutId = null, distanceMiles = null, inclinePercent = null)

        assertEquals(MachineLink.VertGainState(null, null, 0.0), state)
    }
}
