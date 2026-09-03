package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayDistanceTest {

    private fun snapshot(
        workoutId: String? = "workout-1",
        distanceMiles: Double? = null,
    ) = GlassOsClient.Snapshot(
        consoleState = null,
        workoutId = workoutId,
        speedMph = null,
        inclinePercent = null,
        distanceMiles = distanceMiles,
        paceMinPerMile = null,
        elapsedSeconds = null,
        calories = null,
        speedWritable = null,
        inclineWritable = null,
        fanWritable = null,
    )

    private fun fold(
        previous: MachineLink.DisplayDistanceState?,
        workoutId: String? = "workout-1",
        distanceMiles: Double? = null,
    ) = MachineLink.foldDisplayDistance(previous, snapshot(workoutId, distanceMiles))

    @Test
    fun `first poll establishes the baseline verbatim`() {
        assertEquals(
            MachineLink.DisplayDistanceState("workout-1", 1.25),
            fold(null, distanceMiles = 1.25),
        )
    }

    @Test
    fun `forward progress tracks every poll`() {
        var state = fold(null, distanceMiles = 0.0)
        state = fold(state, distanceMiles = 0.1)
        state = fold(state, distanceMiles = 0.2)

        assertEquals(0.2, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `a hard backward reset is held at the last good value`() {
        // What was seen live on an X22i: CURRENT_DISTANCE reading a genuine 0.177 mi, then
        // dropping to a hard 0.0 for several consecutive polls across a pause while the belt's
        // true distance had not moved.
        var state = fold(null, distanceMiles = 0.1)
        state = fold(state, distanceMiles = 0.177)
        state = fold(state, distanceMiles = 0.0)
        state = fold(state, distanceMiles = 0.0)

        assertEquals(0.177, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `recovery after a glitch resumes forward from the true distance`() {
        var state = fold(null, distanceMiles = 0.177)
        state = fold(state, distanceMiles = 0.0)
        state = fold(state, distanceMiles = 0.185)

        assertEquals(0.185, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `missing distance preserves the baseline`() {
        var state = fold(null, distanceMiles = 0.3)
        state = fold(state, distanceMiles = null)

        assertEquals(0.3, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `non-finite distance preserves the baseline`() {
        var state = fold(null, distanceMiles = 0.3)
        state = fold(state, distanceMiles = Double.NaN)
        state = fold(state, distanceMiles = Double.POSITIVE_INFINITY)

        assertEquals(0.3, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `negative distance is treated as unusable, not a real backward value`() {
        var state = fold(null, distanceMiles = 0.3)
        state = fold(state, distanceMiles = -1.0)

        assertEquals(0.3, state.distanceMiles!!, 0.0)
    }

    @Test
    fun `new workout identity resets the baseline even to a lower value`() {
        var state = fold(null, workoutId = "first", distanceMiles = 2.0)
        state = fold(state, workoutId = "second", distanceMiles = 0.0)

        assertEquals(MachineLink.DisplayDistanceState("second", 0.0), state)
    }

    @Test
    fun `transition to no workout resets even when distance is unavailable`() {
        var state = fold(null, workoutId = "first", distanceMiles = 1.5)
        state = fold(state, workoutId = null, distanceMiles = null)

        assertEquals(MachineLink.DisplayDistanceState(null, null), state)
    }

    @Test
    fun `unusable first poll has no invented baseline`() {
        val state = fold(null, distanceMiles = null)

        assertNull(state.distanceMiles)
    }

    @Test
    fun `a usable poll can still establish a baseline after an unusable first one`() {
        var state = fold(null, distanceMiles = null)
        state = fold(state, distanceMiles = 0.4)

        assertEquals(0.4, state.distanceMiles!!, 0.0)
    }
}
