package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a stop counts as done, and when it must escalate to "USE THE SAFETY KEY".
 *
 * These are the tests for issue #39, and they are ported from the failure modes already written
 * down in `packages/stride_control`'s coordinator tests rather than re-derived —
 * `positive_stop_confirmation_test.dart` covers the three headline cases and they appear here by
 * name.
 *
 * **What is deliberately not ported is the Dart model's rule itself.** `coordinator.dart:447` sets
 * `decelObserved = true` for any observed speed at or below its threshold, with no requirement that
 * the console has ever demonstrated it reports motion and no requirement that the reading was taken
 * *after* the stop. Both of those are the naive rule issue #34 rules out: on the X22i `ACTUAL_KPH`
 * reads a confident, well-formed `0x0000` on every poll while a rider walks at 4 mph, so the Dart
 * rule would confirm every stop instantly on that console — including one that never landed. The
 * model is treated here as a specification of behaviour and failure modes, not of the predicate.
 */
class StopConfirmationTest {

    /** A poll, as [MachineLink] publishes them. */
    private fun reading(seq: Long, speed: Double?, distance: Double? = 1.0) =
        MachineLink.Observation(seq = seq, atMs = seq * 500L, speedMph = speed, distanceMiles = distance)

    /** Two agreeing readings of a belt at rest that covered no ground between them. */
    private fun stoppedBelt() = listOf(
        reading(11, 0.0, distance = 1.25),
        reading(12, 0.0, distance = 1.25),
    )

    private fun verdict(
        acked: Boolean = true,
        everReportedMotion: Boolean = true,
        beforeStop: MachineLink.Observation? = null,
        postStop: List<MachineLink.Observation> = stoppedBelt(),
    ) = stopVerdict(acked, everReportedMotion, beforeStop, postStop)

    // --------------------------------------------------------------- the three Dart failure modes

    /** `positive_stop_confirmation_test.dart:7` — ack plus observed deceleration confirms. */
    @Test
    fun `a stop with an ack and observed deceleration confirms`() {
        assertEquals(
            StopVerdict.Confirmed,
            verdict(
                postStop = listOf(
                    // The deceleration itself. Seeing a belt still moving on the first reading
                    // after a stop is not a problem — it is the evidence.
                    reading(10, 4.0, distance = 1.20),
                    reading(11, 0.0, distance = 1.25),
                    reading(12, 0.0, distance = 1.25),
                ),
            ),
        )
    }

    /** `positive_stop_confirmation_test.dart:19` — an ack with no deceleration escalates. */
    @Test
    fun `an ack without observed deceleration does not confirm`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.STILL_MOVING),
            verdict(
                postStop = listOf(
                    reading(11, 4.0, distance = 1.20),
                    reading(12, 4.0, distance = 1.26),
                ),
            ),
        )
    }

    /** `positive_stop_confirmation_test.dart:35` — a stop over a dead link is never "stopped". */
    @Test
    fun `a stop the console never accepted is never confirmed`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.NOT_ACKED),
            verdict(acked = false),
        )
        // And not even with perfect telemetry behind it. An unacked stop is a command we have no
        // evidence reached the machine; a belt that happens to be still is not evidence that it did.
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.NOT_ACKED),
            verdict(acked = false, everReportedMotion = true, postStop = stoppedBelt()),
        )
    }

    // ------------------------------------------------------------------------ issue #34

    /**
     * **Issue #34.** A console whose speed register has never said anything but zero is not
     * believed when it says zero.
     *
     * This is the case a null check does not catch, and the reason this is not a straight port of
     * the Dart rule. `ACTUAL_KPH` reads exactly `0x0000` on every poll on the X22i while a rider
     * walks at 4 mph — not null, not absent, not a decode error — and there is no per-field
     * validity marker in the protocol that could tell that apart from a genuine zero. A
     * confirmation built on the reading alone would confirm every stop on that console instantly,
     * including one that never landed, which is worse than the honest ack-only behaviour it
     * replaced.
     */
    @Test
    fun `a console that has never reported motion cannot confirm a stop`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.NEVER_REPORTED_MOTION),
            verdict(everReportedMotion = false),
        )
    }

    /**
     * **The other half of #34, and the failure the speed register cannot catch about itself.**
     *
     * A register that reports motion once — latching [MachineLink.everReportedMotion] — and then
     * goes dead while the belt is still running produces a fresh, plausible zero on a console that
     * has "proved" itself. Nothing in the speed signal distinguishes that from a belt that really
     * stopped. `CURRENT_DISTANCE` does: #34 observed it accumulating the real pace beside a speed
     * stuck at zero.
     */
    @Test
    fun `distance advancing refuses a confirmation the speed reading would have granted`() {
        val movingBeltWithALyingRegister = listOf(
            reading(11, 0.0, distance = 1.20),
            // 0.01 mi in one poll: about 16 metres, which is not a belt at rest.
            reading(12, 0.0, distance = 1.21),
        )
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.DISTANCE_ADVANCED),
            verdict(postStop = movingBeltWithALyingRegister),
        )
    }

    /**
     * Distance is a veto and never a proof.
     *
     * Stated as its own test because the asymmetry is the whole reason this is safe on a console
     * nobody has characterised. An *increase* is monotone positive evidence of travel and can be
     * trusted without knowing the register's quantum. Standing still cannot: a 10 m quantum at
     * 4 mph is five and a half seconds of real motion reading as "unchanged", and the quantum of
     * GlassOS's `GetDistance` has never been measured. So an unchanging distance grants nothing on
     * its own, and a machine that publishes no distance at all cannot have a stop confirmed here.
     */
    @Test
    fun `an unchanging distance is not on its own evidence of rest`() {
        // Speed says the belt is moving; distance has not ticked. The veto has nothing to add and
        // must not be read as a second opinion in favour.
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.STILL_MOVING),
            verdict(
                postStop = listOf(
                    reading(11, 4.0, distance = 1.20),
                    reading(12, 4.0, distance = 1.20),
                ),
            ),
        )
        // And a machine with no distance to report cannot confirm, however still it claims to be.
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED),
            verdict(
                postStop = listOf(
                    reading(11, 0.0, distance = null),
                    reading(12, 0.0, distance = null),
                ),
            ),
        )
    }

    // ------------------------------------------------------- readings have to belong to this stop

    /**
     * One reading is never enough on its own, however good it looks.
     *
     * A single sample cannot tell a belt at rest from a belt passing through a reading, and on the
     * transports here a reading is up to four seconds old before it goes stale. Two, so the second
     * has to agree with the first.
     */
    @Test
    fun `a single reading at rest does not confirm`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED),
            verdict(postStop = listOf(reading(11, 0.0))),
        )
    }

    // ------------------------------------------------- the pre-stop reading, and what it may do

    /**
     * **Measured on hardware.** The reading taken as the stop went out may complete the pair.
     *
     * This is the End-from-PAUSED case, which is the only one the overlay produces: the belt was
     * already at rest, the reading before the stop says so, and the first reading after it agrees,
     * with no distance in between.
     *
     * It exists because requiring *both* readings to come from after the stop sat on an unmeasured
     * knife-edge. A belt run showed GlassOS taking its telemetry away when the workout ends —
     * `speedMph` goes null, not zero — somewhere between 0.5 s and 3 s after the stop. If it is the
     * near end of that range, only one post-stop reading ever arrives, and requiring two would
     * escalate **every ordinary end**. An alarm that always fires is a worse defect than the gap
     * this issue closes.
     */
    @Test
    fun `the reading taken as the stop went out may complete the pair`() {
        assertEquals(
            StopVerdict.Confirmed,
            verdict(
                beforeStop = reading(10, 0.0, distance = 1.25),
                postStop = listOf(reading(11, 0.0, distance = 1.25)),
            ),
        )
    }

    /**
     * But it may never be the whole of it. **This is the B2 guard.**
     *
     * `MachineLink.speedMph` is believed for four seconds, so without this a reading taken *before*
     * a stop was transmitted could satisfy "the belt is at rest" *after* it — confirming a stop on
     * evidence the stop could not possibly have influenced. A confirmation always needs something
     * from after.
     */
    @Test
    fun `a pre-stop reading alone can never confirm a stop`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED),
            verdict(
                beforeStop = reading(10, 0.0, distance = 1.25),
                postStop = emptyList(),
            ),
        )
    }

    /**
     * Ending from a *moving* belt still needs two readings from after the stop.
     *
     * The pre-stop reading says the belt was moving, so it cannot be half of a pair that says it
     * was not. If telemetry goes before two post-stop readings arrive, we genuinely never saw the
     * belt stop, and the escalation is the honest answer rather than a failure of the rule.
     */
    @Test
    fun `ending from a moving belt is not confirmed by one post-stop reading`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED),
            verdict(
                beforeStop = reading(10, 4.0, distance = 1.20),
                postStop = listOf(reading(11, 0.0, distance = 1.25)),
            ),
        )
    }

    /** And a moving belt straddling the stop is refused by distance, not merely by speed. */
    @Test
    fun `a pre-stop reading cannot complete a pair across travelled ground`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.DISTANCE_ADVANCED),
            verdict(
                beforeStop = reading(10, 0.0, distance = 1.20),
                postStop = listOf(reading(11, 0.0, distance = 1.21)),
            ),
        )
    }

    /** No readings at all after the stop is "we could not see the belt", never "it stopped". */
    @Test
    fun `no telemetry after the stop does not confirm`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED),
            verdict(postStop = emptyList()),
        )
    }

    /** A reading that arrived but carried no speed is not a reading of zero. */
    @Test
    fun `a null speed is not a stopped belt`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED),
            verdict(postStop = listOf(reading(11, null), reading(12, null))),
        )
    }

    /**
     * A belt that read at rest and then moved again cannot be confirmed by its earlier readings.
     *
     * The rule takes the *trailing* run of at-rest readings, so the two agreeing samples have to be
     * the most recent ones rather than any two in the history.
     */
    @Test
    fun `a belt that moves again after reading at rest does not confirm`() {
        assertEquals(
            StopVerdict.Unconfirmed(StopUnconfirmed.STILL_MOVING),
            verdict(
                postStop = listOf(
                    reading(10, 0.0, distance = 1.20),
                    reading(11, 0.0, distance = 1.20),
                    reading(12, 3.0, distance = 1.22),
                ),
            ),
        )
    }

    /** Rounding noise around a stop is still a stop; the threshold is shared with #36's deck gate. */
    @Test
    fun `rounding noise around zero still counts as at rest`() {
        assertEquals(
            StopVerdict.Confirmed,
            verdict(
                postStop = listOf(
                    reading(11, BELT_MOVING_MPH, distance = 1.25),
                    reading(12, 0.05, distance = 1.25),
                ),
            ),
        )
    }

    /**
     * A superseded watcher says so rather than inventing an answer.
     *
     * Its own reason because it is the one "unconfirmed" that must not raise an alarm: a newer stop
     * owns the machine, and its watcher is the one entitled to speak for it.
     */
    @Test
    fun `superseded is a distinct answer and not an alarm`() {
        assertEquals(
            false,
            shouldEscalate(
                StopVerdict.Unconfirmed(StopUnconfirmed.SUPERSEDED),
                StopCause.ENDED,
                consoleDetached = false,
                beltSeenMoving = false,
            ),
        )
    }

    /**
     * All four conditions are an AND, pinned as a table.
     *
     * The failure that matters is a future refactor keeping three of them, so every combination is
     * enumerated rather than sampled.
     */
    @Test
    fun `a stop is confirmed only when every condition holds`() {
        data class Case(
            val acked: Boolean,
            val everReportedMotion: Boolean,
            val postStop: List<MachineLink.Observation>,
            val expected: StopVerdict,
        )

        val moving = listOf(reading(11, 4.0, 1.20), reading(12, 4.0, 1.26))
        val travelling = listOf(reading(11, 0.0, 1.20), reading(12, 0.0, 1.21))
        val cases = listOf(
            Case(true, true, stoppedBelt(), StopVerdict.Confirmed),
            Case(false, true, stoppedBelt(), StopVerdict.Unconfirmed(StopUnconfirmed.NOT_ACKED)),
            Case(
                true,
                false,
                stoppedBelt(),
                StopVerdict.Unconfirmed(StopUnconfirmed.NEVER_REPORTED_MOTION),
            ),
            Case(true, true, moving, StopVerdict.Unconfirmed(StopUnconfirmed.STILL_MOVING)),
            Case(true, true, travelling, StopVerdict.Unconfirmed(StopUnconfirmed.DISTANCE_ADVANCED)),
            Case(true, true, emptyList(), StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED)),
            // Not acked wins over everything, because it is checked first and because a command we
            // have no evidence reached the machine cannot be confirmed by anything the machine says.
            Case(false, false, moving, StopVerdict.Unconfirmed(StopUnconfirmed.NOT_ACKED)),
        )
        for (case in cases) {
            assertEquals(
                "acked=${case.acked} everReportedMotion=${case.everReportedMotion} " +
                    "postStop=${case.postStop}",
                case.expected,
                stopVerdict(case.acked, case.everReportedMotion, null, case.postStop),
            )
        }
    }
}
