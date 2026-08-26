package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an unconfirmed stop is worth telling somebody to pull the safety key.
 *
 * `docs/PLAN.md` §5.4 says a stop that is neither acked nor observed to decelerate escalates. This
 * is the narrow set of cases where it deliberately does not, and each of those exceptions is a
 * decision *not* to warn a person about a treadmill — so every one of them is pinned here.
 *
 * The argument for having exceptions at all: an alarm a rider sees every time they cancel a start,
 * or every time they use a console with no treadmill plugged into it, is an alarm they learn to
 * dismiss without reading. Alarm fatigue is a safety defect and not a UX complaint. The argument
 * for keeping them this narrow is everything below.
 */
class StopEscalationTest {

    private fun escalates(
        reason: StopUnconfirmed = StopUnconfirmed.NOT_OBSERVED,
        cause: StopCause = StopCause.ENDED,
        consoleDetached: Boolean = false,
        beltSeenMoving: Boolean = false,
    ) = shouldEscalate(
        StopVerdict.Unconfirmed(reason),
        cause,
        consoleDetached,
        beltSeenMoving,
    )

    /** A confirmed stop is the only thing that never warns. */
    @Test
    fun `a confirmed stop never escalates`() {
        for (cause in StopCause.entries) {
            assertFalse(
                "confirmed must never escalate ($cause)",
                shouldEscalate(
                    StopVerdict.Confirmed,
                    cause,
                    consoleDetached = false,
                    beltSeenMoving = true,
                ),
            )
        }
    }

    /** The headline case: a workout the rider ended, whose stop nothing could confirm. */
    @Test
    fun `an ended workout with an unconfirmed stop escalates`() {
        for (reason in StopUnconfirmed.entries - StopUnconfirmed.SUPERSEDED) {
            assertTrue("$reason must escalate for an ended workout", escalates(reason = reason))
        }
    }

    /**
     * A start the console **explicitly refused** does not escalate.
     *
     * The console answered, and the answer was no. Nothing was set moving by us, and this is the
     * common path on a console that is busy or in the wrong state — `MachineCoordinator.startWorkout`
     * refuses rather than starting blind, and `retryStart` exists precisely because a rider is
     * expected to try again. Alarming here would put "USE THE SAFETY KEY" in front of somebody
     * whose belt has never moved.
     */
    @Test
    fun `a start the console refused does not escalate`() {
        assertFalse(escalates(cause = StopCause.START_REFUSED))
    }

    /**
     * A start that merely went **unanswered** does escalate, and this is the important one.
     *
     * An earlier draft suppressed the alarm for every start that never reached RUNNING. That
     * quietly covered the case where the console accepted the start and the *reply* was lost: a
     * moving belt with the alarm turned off. `WorkoutSession.abandon` already names this failure —
     * "the refusal we are reacting to may be a reply that was lost rather than a command that never
     * landed" — which is exactly why it still sends its stop, and exactly why an unconfirmable one
     * has to be heard about.
     */
    @Test
    fun `a start that was never answered escalates`() {
        assertTrue(escalates(cause = StopCause.START_UNANSWERED))
    }

    /**
     * A console that says it has no treadmill does not escalate.
     *
     * Positive knowledge, not an absence of it: [MachineLink.consoleDetached] is only ever a *fresh*
     * reading of the console explicitly reporting DISCONNECTED. A stale snapshot or a missed poll
     * does not qualify, so this cannot become "we did not hear anything, so never mind".
     */
    @Test
    fun `a console with no treadmill attached does not escalate`() {
        assertFalse(escalates(consoleDetached = true))
    }

    /**
     * Nothing may talk us out of an alarm for a belt telemetry says is moving.
     *
     * Checked before every exception, so a detached-console reading or a refused start cannot
     * suppress a warning about a belt we have actually seen going round.
     */
    @Test
    fun `evidence of motion beats every exception`() {
        for (cause in StopCause.entries) {
            assertTrue(
                "a belt seen moving must escalate ($cause)",
                escalates(cause = cause, consoleDetached = true, beltSeenMoving = true),
            )
        }
        // And the two verdicts that are themselves statements that the belt is moving.
        assertTrue(
            escalates(
                reason = StopUnconfirmed.STILL_MOVING,
                cause = StopCause.START_REFUSED,
                consoleDetached = true,
            ),
        )
        assertTrue(
            escalates(
                reason = StopUnconfirmed.DISTANCE_ADVANCED,
                cause = StopCause.START_REFUSED,
                consoleDetached = true,
            ),
        )
    }

    /**
     * A superseded stop never escalates, whatever else is true.
     *
     * A newer stop has taken the machine, and its own watcher is the thing entitled to speak for
     * it. Alarming from a retired watcher would raise a warning about a state that has already been
     * replaced — and would do it *behind* a stop that may well have been confirmed.
     */
    @Test
    fun `a superseded stop never escalates`() {
        for (cause in StopCause.entries) {
            assertFalse(
                "superseded must never escalate ($cause)",
                escalates(
                    reason = StopUnconfirmed.SUPERSEDED,
                    cause = cause,
                    beltSeenMoving = true,
                    consoleDetached = false,
                ),
            )
        }
    }

    /**
     * The default for an unrecognised cause is to warn.
     *
     * Pinned as a table over every combination, because the failure mode that matters is somebody
     * adding a fourth [StopCause] for a new path and inheriting silence. The exceptions are an
     * explicit list; everything else escalates.
     */
    @Test
    fun `only the two named exceptions are silent`() {
        for (cause in StopCause.entries) {
            for (detached in listOf(true, false)) {
                val expected = !(detached || cause == StopCause.START_REFUSED)
                assertEquals(
                    "cause=$cause consoleDetached=$detached",
                    expected,
                    escalates(cause = cause, consoleDetached = detached),
                )
            }
        }
    }
}
