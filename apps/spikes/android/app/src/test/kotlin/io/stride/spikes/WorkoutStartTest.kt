package io.stride.spikes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what tapping Start does to a console that is not idle.
 *
 * The bug these exist for: after ending a workout the console kept a paused session, and Start
 * resumed it instead of beginning a new one, so the rider landed on the console's "resume or quit"
 * prompt. Start must start.
 */
class WorkoutStartTest {

    @Test
    fun `a paused console starts fresh rather than resuming`() {
        assertFalse(shouldAdoptWorkout(GlassOsCommands.WORKOUT_PAUSED, 0.0))
        assertFalse(shouldAdoptWorkout(GlassOsCommands.WORKOUT_PAUSED, null))
        // Even a paused console still reporting speed is a stale session, not a workout to join.
        assertFalse(shouldAdoptWorkout(GlassOsCommands.WORKOUT_PAUSED, 4.0))
    }

    @Test
    fun `results and idle consoles start fresh`() {
        assertFalse(shouldAdoptWorkout(GlassOsCommands.WORKOUT_RESULTS, 0.0))
        assertFalse(shouldAdoptWorkout(GlassOsCommands.WORKOUT_IDLE, 0.0))
        assertFalse(shouldAdoptWorkout(null, null))
    }

    @Test
    fun `a running console with a moving belt is adopted`() {
        assertTrue(shouldAdoptWorkout(GlassOsCommands.WORKOUT_RUNNING, 3.5))
    }

    @Test
    fun `a running console with a stopped belt is a stale session`() {
        assertFalse(shouldAdoptWorkout(GlassOsCommands.WORKOUT_RUNNING, 0.0))
        assertFalse(shouldAdoptWorkout(GlassOsCommands.WORKOUT_RUNNING, null))
        // Rounding noise around a stop is not motion.
        assertFalse(shouldAdoptWorkout(GlassOsCommands.WORKOUT_RUNNING, 0.05))
    }
}
