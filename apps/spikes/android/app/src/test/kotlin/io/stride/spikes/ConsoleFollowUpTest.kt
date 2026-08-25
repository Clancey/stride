package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Following the console when the rider drives it from the machine's own buttons.
 *
 * Stride is not the only thing that can stop this belt: there is a Stop button under the rider's
 * hand, and pressing it used to leave the overlay counting a workout that had visibly ended —
 * "Pause workout" over a stationary belt, which is the same wrong screen the start handshake was
 * reworked to remove, arrived at from the other direction.
 *
 * The hazard in fixing it is the opposite mistake, and these tests are mostly about that: a rule
 * that pauses whenever the console says it is not moving would pause every workout a second after
 * it began, because Stride confirms a start as soon as the command is accepted and the console keeps
 * reporting IDLE while the belt spins up.
 */
class ConsoleFollowUpTest {

    /** The feature, stated directly. */
    @Test
    fun `the console stopping a running belt pauses the session`() {
        assertEquals(
            ConsoleFollowUp.PAUSE,
            consoleFollowUp(previous = true, moving = false, state = WorkoutSession.State.RUNNING),
        )
    }

    /** And its counterpart, so Start on the console picks the session back up. */
    @Test
    fun `the console restarting the belt resumes a paused session`() {
        assertEquals(
            ConsoleFollowUp.RESUME,
            consoleFollowUp(previous = false, moving = true, state = WorkoutSession.State.PAUSED),
        )
    }

    /**
     * The spin-up race, which is the whole reason this works on edges.
     *
     * Stride has confirmed a start and is RUNNING; the console has not yet said it is moving. A
     * level test pauses here. There is no edge, so nothing happens.
     */
    @Test
    fun `a console that has not yet reported motion does not pause a fresh workout`() {
        assertEquals(
            ConsoleFollowUp.NOTHING,
            consoleFollowUp(previous = null, moving = false, state = WorkoutSession.State.RUNNING),
        )
    }

    /** A steady state is not an event, however many times it is polled. */
    @Test
    fun `repeating the same reading changes nothing`() {
        assertEquals(
            ConsoleFollowUp.NOTHING,
            consoleFollowUp(previous = false, moving = false, state = WorkoutSession.State.RUNNING),
        )
        assertEquals(
            ConsoleFollowUp.NOTHING,
            consoleFollowUp(previous = true, moving = true, state = WorkoutSession.State.PAUSED),
        )
    }

    /**
     * A workout started on the console is not silently adopted.
     *
     * Deliberate, and the reason this is a separate case rather than an oversight: beginning a
     * session from a poll would start a clock, a goal and the media coupling on the rider's behalf.
     */
    @Test
    fun `a belt starting while Stride is idle does not begin a session`() {
        assertEquals(
            ConsoleFollowUp.NOTHING,
            consoleFollowUp(previous = false, moving = true, state = WorkoutSession.State.IDLE),
        )
    }

    /** Nor is a session Stride is still waiting on an answer for touched by either edge. */
    @Test
    fun `a start that has not been answered is left alone`() {
        assertEquals(
            ConsoleFollowUp.NOTHING,
            consoleFollowUp(previous = true, moving = false, state = WorkoutSession.State.STARTING),
        )
        assertEquals(
            ConsoleFollowUp.NOTHING,
            consoleFollowUp(previous = false, moving = true, state = WorkoutSession.State.STARTING),
        )
    }

    /** An already-paused session is not paused again, and an idle one is not stopped. */
    @Test
    fun `stopping a belt Stride was not counting changes nothing`() {
        assertEquals(
            ConsoleFollowUp.NOTHING,
            consoleFollowUp(previous = true, moving = false, state = WorkoutSession.State.PAUSED),
        )
        assertEquals(
            ConsoleFollowUp.NOTHING,
            consoleFollowUp(previous = true, moving = false, state = WorkoutSession.State.IDLE),
        )
    }

    /**
     * The states the console reports map onto motion the way the overlay already reads them.
     *
     * Included because this is the join between the two halves: an unknown state answers null and
     * must reach [consoleFollowUp] as "no reading" rather than as either answer.
     */
    @Test
    fun `console states map to motion as the overlay reads them`() {
        assertEquals(true, GlassOsClient.ConsoleState.beltMayBeMoving("WORKOUT"))
        assertEquals(false, GlassOsClient.ConsoleState.beltMayBeMoving("PAUSED"))
        assertEquals(false, GlassOsClient.ConsoleState.beltMayBeMoving("SAFETY_KEY_REMOVED"))
        assertEquals(null, GlassOsClient.ConsoleState.beltMayBeMoving("CONSOLE_STATE_UNKNOWN"))
        assertEquals(null, GlassOsClient.ConsoleState.beltMayBeMoving(null))
    }
}
