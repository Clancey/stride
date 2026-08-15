package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two decisions behind a start that has been asked for but not yet answered.
 *
 * The bug these exist for: Stride went straight to RUNNING the moment Start was pressed, so on a
 * console that took ten seconds to answer the rider got "Pause workout" over a stationary belt and
 * ten seconds of standing still banked as exercise. The fix introduced a pending state, and a
 * pending state on a treadmill is only safe if there is no way to get stuck in it.
 */
class PendingStartTest {

    // ------------------------------------------------------------------ settling a start

    @Test
    fun `a machine that started the belt starts the clock`() {
        assertEquals(
            StartSettlement.CONFIRM,
            startSettlement(
                MachineCoordinator.Outcome.Ok,
                stale = false,
                state = WorkoutSession.State.STARTING,
            ),
        )
    }

    @Test
    fun `a refusal returns to idle rather than pretending to run`() {
        assertEquals(
            StartSettlement.ABANDON,
            startSettlement(
                MachineCoordinator.Outcome.Failed("The console did not answer."),
                stale = false,
                state = WorkoutSession.State.STARTING,
            ),
        )
        assertEquals(
            StartSettlement.ABANDON,
            startSettlement(
                MachineCoordinator.Outcome.Rejected("Safety key removed"),
                stale = false,
                state = WorkoutSession.State.STARTING,
            ),
        )
    }

    @Test
    fun `an answer to a previous attempt touches nothing`() {
        // Including a success: confirming here would start the clock on an attempt the rider has
        // already abandoned, and standing down would disarm the current attempt's watchdog.
        for (outcome in everyOutcome()) {
            assertEquals(
                "stale $outcome must be ignored",
                StartSettlement.IGNORE,
                startSettlement(outcome, stale = true, state = WorkoutSession.State.STARTING),
            )
        }
    }

    @Test
    fun `a stop that overtook the start leaves the rider where they are heading`() {
        // The transition that superseded it has already moved the session, so there is nothing
        // left to resolve.
        assertEquals(
            StartSettlement.STAND_DOWN,
            startSettlement(
                MachineCoordinator.Outcome.Superseded,
                stale = false,
                state = WorkoutSession.State.IDLE,
            ),
        )
    }

    @Test
    fun `a superseded start that is somehow still pending is resolved, not just unwatched`() {
        // STARTING is the one state that cannot leave under its own power. Standing down here
        // would take away the watchdog and leave the rider on "Starting…" for good.
        assertEquals(
            StartSettlement.ABANDON,
            startSettlement(
                MachineCoordinator.Outcome.Superseded,
                stale = false,
                state = WorkoutSession.State.STARTING,
            ),
        )
    }

    @Test
    fun `no current answer ever leaves the session pending`() {
        // The property that matters more than any single branch: whatever the machine says, if we
        // own the answer and the rider is still waiting, STARTING must be resolved.
        for (outcome in everyOutcome()) {
            val settlement =
                startSettlement(outcome, stale = false, state = WorkoutSession.State.STARTING)
            assertTrue(
                "$outcome left the session pending",
                settlement == StartSettlement.CONFIRM || settlement == StartSettlement.ABANDON,
            )
        }
    }

    @Test
    fun `an answer that arrives after a cancel changes nothing`() {
        // The rider cancelled, or the watchdog gave up. Either way they are already at IDLE, and
        // the stop covering a belt that may have started anyway went out on the way there.
        assertEquals(
            StartSettlement.STAND_DOWN,
            startSettlement(
                MachineCoordinator.Outcome.Ok,
                stale = false,
                state = WorkoutSession.State.IDLE,
            ),
        )
    }

    @Test
    fun `every answer we own takes the watchdog down with it`() {
        // The one shape of bug that strands a rider on "Starting…": an outcome that is ours, is
        // current, and quietly returns without disarming the watchdog or resolving the state.
        for (outcome in everyOutcome()) {
            for (state in WorkoutSession.State.values()) {
                val settlement = startSettlement(outcome, stale = false, state = state)
                assertTrue(
                    "a current $outcome in $state must not be ignored",
                    settlement != StartSettlement.IGNORE,
                )
            }
        }
    }

    // ------------------------------------------------------------------ connect backoff

    @Test
    fun `early retries stay inside a second`() {
        // The whole point of the fix. GlassOS becomes ready at an unpredictable moment during boot,
        // so what matters is not how often we ask but how soon after it is ready we ask again. The
        // old flat ten-second retry cost the rider up to ten seconds on a console that was ready.
        assertEquals(250L, connectBackoffMs(1))
        assertEquals(500L, connectBackoffMs(2))
        assertEquals(1_000L, connectBackoffMs(3))
    }

    @Test
    fun `a first attempt waits for nothing`() {
        // No failures yet: attach must be able to shake hands the instant there is a client.
        assertEquals(0L, connectBackoffMs(0))
    }

    @Test
    fun `a console with nothing attached settles rather than being hammered`() {
        assertEquals(8_000L, connectBackoffMs(6))
        assertEquals(8_000L, connectBackoffMs(50))
        // Guards the index arithmetic against a counter that ran away.
        assertEquals(8_000L, connectBackoffMs(Int.MAX_VALUE))
    }

    @Test
    fun `the schedule only ever grows`() {
        var previous = -1L
        for (failures in 0..12) {
            val delay = connectBackoffMs(failures)
            assertTrue("backoff went backwards at $failures", delay >= previous)
            previous = delay
        }
    }

    private fun everyOutcome(): List<MachineCoordinator.Outcome> = listOf(
        MachineCoordinator.Outcome.Ok,
        MachineCoordinator.Outcome.Superseded,
        MachineCoordinator.Outcome.Failed("no answer"),
        MachineCoordinator.Outcome.Rejected("refused"),
    )
}
