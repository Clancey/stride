package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a workout that *ended* from one that was merely paused.
 *
 * The bug these exist for is issue #29: Stride turned the console fan on at the start of every
 * workout and never turned it off, so a machine left alone after a run kept blowing indefinitely.
 * Adding the missing half is easy; adding it to the *wrong* transition is the part that needed
 * pinning, because a pause and an end used to be one shrug away from each other in this code.
 *
 * A pause is resumable. The rider stepped off to answer the door, the belt is expected to move
 * again, and an app that shut their fan down and dropped their deck to flat every time they paused
 * would be deciding on their behalf that the session is over. It is also not only a button: the
 * console's own Stop button is adopted as a pause by [consoleFollowUp], so getting this wrong would
 * end a workout every time somebody used the machine's own controls.
 *
 * Pure, so every branch is checkable without a treadmill — the same reason [startSettlement] and
 * [consoleFollowUp] are pure.
 */
class WorkoutEndTest {

    /** The feature, stated directly: End is the one transition that settles the machine. */
    @Test
    fun `ending a workout stops the belt and settles the machine`() {
        assertEquals(
            EndFollowUp.STOP_AND_SETTLE,
            endFollowUp(
                previous = WorkoutSession.State.PAUSED,
                next = WorkoutSession.State.STOPPING,
                ending = WorkoutSession.Ending.ENDED,
            ),
        )
    }

    /**
     * The end is not conditional on where it was ended from.
     *
     * The overlay only offers "End workout" from PAUSED today, but `SpikeBridge.workoutStop` and
     * `WorkoutSession.stop` will both end a RUNNING session, and an end is an end.
     */
    @Test
    fun `a workout ended while running settles the machine too`() {
        assertEquals(
            EndFollowUp.STOP_AND_SETTLE,
            endFollowUp(
                previous = WorkoutSession.State.RUNNING,
                next = WorkoutSession.State.STOPPING,
                ending = WorkoutSession.Ending.ENDED,
            ),
        )
    }

    /**
     * **Issue #39.** The settle that follows a stop commands nothing.
     *
     * `STOPPING → IDLE` is the confirmation arriving, not a new ending. The stop it is settling
     * went out when the session *entered* STOPPING; asking for another here would re-send a stop
     * and re-run the tidy-up for a workout that finished seconds ago — and worse, it would do so
     * from the callback of the very watcher that was judging the first one.
     */
    @Test
    fun `the settle after a stop asks the machine for nothing`() {
        for (ending in listOf(null, WorkoutSession.Ending.ENDED, WorkoutSession.Ending.ABANDONED)) {
            assertEquals(
                "stopping -> idle ($ending) must ask for nothing",
                EndFollowUp.NOTHING,
                endFollowUp(WorkoutSession.State.STOPPING, WorkoutSession.State.IDLE, ending),
            )
        }
    }

    /**
     * Pressing End again while a stop is still unconfirmed sends another one.
     *
     * The one case that must *not* be swallowed by the rule above. A rider pressing a stop control
     * twice is asking harder, and the honest answer to "I am not sure it stopped" is another stop —
     * not a no-op because the app is already in the state it entered when the first one went out.
     */
    @Test
    fun `pressing End again while stopping re-sends the stop`() {
        assertEquals(
            EndFollowUp.STOP_AND_SETTLE,
            endFollowUp(
                previous = WorkoutSession.State.STOPPING,
                next = WorkoutSession.State.STOPPING,
                ending = WorkoutSession.Ending.ENDED,
            ),
        )
    }

    /**
     * A pause gets none of it — not the fan, not the re-assert, not the deck.
     *
     * This is the test the whole change is balanced on. A pause never reaches IDLE, so it must ask
     * the end path for nothing at all; the pause command itself is issued elsewhere.
     */
    @Test
    fun `a pause is not an end`() {
        assertEquals(
            EndFollowUp.NOTHING,
            endFollowUp(
                previous = WorkoutSession.State.RUNNING,
                next = WorkoutSession.State.PAUSED,
                ending = null,
            ),
        )
    }

    /** Nor is resuming out of one, or starting, or confirming a start. */
    @Test
    fun `no live transition is an end`() {
        val live = listOf(
            WorkoutSession.State.PAUSED to WorkoutSession.State.RUNNING,
            WorkoutSession.State.IDLE to WorkoutSession.State.STARTING,
            WorkoutSession.State.STARTING to WorkoutSession.State.RUNNING,
        )
        for ((previous, next) in live) {
            assertEquals(
                "$previous -> $next must not settle the machine",
                EndFollowUp.NOTHING,
                endFollowUp(previous, next, ending = null),
            )
        }
    }

    /**
     * An abandoned start still stops the belt, and still gets nothing else.
     *
     * `WorkoutSession.abandon` is a start the machine refused, the rider cancelled, or the watchdog
     * gave up on. The stop is unchanged and unconditional — a refusal can be a reply that was lost
     * rather than a command that never landed, so a belt that might be moving is always told to
     * stop. The settling is deliberately withheld: this is the retry path, nothing established the
     * belt ever moved, and the round trips would land on the one screen where a rider is standing
     * on a treadmill waiting to hear whether their start worked.
     *
     * **It also goes straight to IDLE rather than through STOPPING (#39).** Holding the retry path
     * behind a stop confirmation would lock the rider out of pressing Start again for as long as a
     * confirmation takes, on that same screen. Byte for byte what it did before.
     */
    @Test
    fun `an abandoned start stops the belt without settling the machine`() {
        assertEquals(
            EndFollowUp.STOP,
            endFollowUp(
                previous = WorkoutSession.State.STARTING,
                next = WorkoutSession.State.IDLE,
                ending = WorkoutSession.Ending.ABANDONED,
            ),
        )
    }

    /**
     * An IDLE transition with no ending recorded stops, and does no more.
     *
     * Unreachable today — every path to IDLE names its ending — which is exactly why it is pinned.
     * The safe reading of "we do not know what this was" is to stop the belt and touch nothing
     * else; an unrecognised ending must never be a licence to start moving a deck.
     */
    @Test
    fun `an ending this build does not recognise still stops the belt`() {
        assertEquals(
            EndFollowUp.STOP,
            endFollowUp(
                previous = WorkoutSession.State.RUNNING,
                next = WorkoutSession.State.IDLE,
                ending = null,
            ),
        )
    }

    /** Already idle asks for nothing: there is no session to end and no belt we started. */
    @Test
    fun `an idle session that goes idle again asks for nothing`() {
        for (ending in listOf(null, WorkoutSession.Ending.ENDED, WorkoutSession.Ending.ABANDONED)) {
            assertEquals(
                "idle -> idle ($ending) must ask for nothing",
                EndFollowUp.NOTHING,
                endFollowUp(WorkoutSession.State.IDLE, WorkoutSession.State.IDLE, ending),
            )
        }
    }

    // ------------------------------------------------------------------ moving the deck

    /**
     * The deck goes flat only for a belt Stride can *see* has stopped, on a console whose speed
     * register has proved it says anything at all.
     *
     * The first version gated on the re-asserted `KPH = 0` coming back accepted, which is a
     * statement about a console taking a register write and not about a belt. It was worse than
     * useless: on a console that took the stop, the re-assert is refused and no deck moves — so the
     * ack gate fired only on the branch where the console was still in a workout with the belt
     * running, which is the single state a deck must not move in.
     */
    @Test
    fun `a stopped belt on a console that reports motion may have its deck flattened`() {
        assertTrue(mayFlattenDeck(0.0, everReportedMotion = true))
        // Rounding noise around a stop is still a stop.
        assertTrue(mayFlattenDeck(0.05, everReportedMotion = true))
    }

    @Test
    fun `a moving belt keeps its deck`() {
        assertFalse(mayFlattenDeck(0.5, everReportedMotion = true))
        assertFalse(mayFlattenDeck(6.0, everReportedMotion = true))
    }

    /**
     * And a belt Stride cannot see keeps its deck too.
     *
     * Null is "the telemetry snapshot is stale, or the machine could not be asked" — never "it is
     * stopped". The start path already refuses to move a treadmill on probably; the deck is held to
     * the same rule, and the cost of being wrong this way is a deck left on a hill, which is where
     * it sat before any of this existed.
     */
    @Test
    fun `an unreadable belt keeps its deck`() {
        assertFalse("null speed is not permission to move anything", mayFlattenDeck(null, true))
        assertFalse(mayFlattenDeck(null, false))
    }

    /**
     * **Issue #34.** A console whose speed register is stuck at zero never gets its deck moved.
     *
     * This is the case a null check does not catch and the whole reason `everReportedMotion`
     * exists. On the X22i, `ACTUAL_KPH` reads exactly `0x0000` on every poll while a rider walks at
     * 4 mph — not null, not absent, not a decode error, with `CURRENT_DISTANCE` accumulating the
     * real pace beside it. A gate that only refuses on null accepts that confident zero and drops
     * the deck under a moving belt.
     *
     * Until a console has shown its speed register saying something other than zero, a zero from it
     * is indistinguishable from a register that always reads zero, and is worth nothing.
     */
    @Test
    fun `a console that has never reported motion is never believed when it reports zero`() {
        assertFalse(
            "a stuck-at-zero speed register must not license moving the deck (#34)",
            mayFlattenDeck(0.0, everReportedMotion = false),
        )
        assertFalse(mayFlattenDeck(0.05, everReportedMotion = false))
    }

    /**
     * The two conditions are an AND, and neither one alone is enough.
     *
     * Pinned as a table because the failure that matters is a future refactor keeping one half.
     */
    @Test
    fun `the deck moves only when both conditions hold`() {
        val cases = listOf(
            Triple(0.0, true, true),
            Triple(0.0, false, false),
            Triple(4.0, true, false),
            Triple(4.0, false, false),
            Triple(null, true, false),
            Triple(null, false, false),
        )
        for ((speed, seenMotion, expected) in cases) {
            assertEquals(
                "speed=$speed everReportedMotion=$seenMotion",
                expected,
                mayFlattenDeck(speed, seenMotion),
            )
        }
    }
}
