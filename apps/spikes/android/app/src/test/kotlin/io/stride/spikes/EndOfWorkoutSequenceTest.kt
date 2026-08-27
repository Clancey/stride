package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What actually reaches the wire when a workout ends, and in what order.
 *
 * These are the tests that matter for issue #29, because the risk in fixing it was never the fan.
 * It was that shutting the fan down and re-asserting zero adds writes to the one path in this app
 * that must not gain any: a stop is never ramp-limited, delayed, or queued behind another command
 * (`docs/PLAN.md` §5.2, CONTRIBUTING.md). A change that turns the fan off perfectly and puts a fan
 * write in front of a stop is a worse app than the one with the bug.
 *
 * So they drive the real [MachineCoordinator] against a recording console rather than asserting on
 * a pure function: queue order, generation checks and the worker thread are the things under test,
 * and none of them exist in a pure function.
 */
class EndOfWorkoutSequenceTest {

    /**
     * A console that records every call in order and answers however a test tells it to.
     *
     * Only the verbs the end path uses are implemented with any care; the rest answer the way an
     * unbound transport does, because nothing here calls them.
     */
    private class RecordingConsole : MachineCommands {
        override val transportName = "recording"

        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        var speedAck: MachineAck = MachineAck.Ok
        var inclineAck: MachineAck = MachineAck.Ok
        var fanAck: MachineAck = MachineAck.Ok
        var stopAck: MachineAck = MachineAck.Ok

        /** Thrown from [setFanState] when set, standing in for a transport that blew up. */
        var fanThrows: Throwable? = null

        /** Run inside [setSpeedKph], to simulate something landing while the write is on the wire. */
        var duringSpeedWrite: (() -> Unit)? = null

        /** Run after a fan write is recorded, before the worker advances to its next job. */
        var duringFanWrite: (() -> Unit)? = null

        /** Held by every call while set, so a test can prove the *caller* is not the one blocking. */
        var blockUntil: CountDownLatch? = null

        /** Signals and holds a fan write after it reaches the transport. */
        var fanEntered: CountDownLatch? = null
        var releaseFan: CountDownLatch? = null

        private fun record(what: String) {
            blockUntil?.await(5, TimeUnit.SECONDS)
            calls += what
        }

        override fun setSpeedKph(kph: Double): MachineAck {
            record("speed ${"%.2f".format(kph)}")
            duringSpeedWrite?.invoke()
            return speedAck
        }

        override fun setInclinePercent(percent: Double): MachineAck {
            record("incline ${"%.1f".format(percent)}")
            return inclineAck
        }

        override fun setFanState(state: Int): MachineAck {
            fanEntered?.countDown()
            releaseFan?.await(5, TimeUnit.SECONDS)
            record("fan $state")
            duringFanWrite?.invoke()
            fanThrows?.let { throw it }
            return fanAck
        }

        override fun connect(): Int? = null
        override fun startWorkout(): MachineAck = MachineAck.Ok
        override fun pause(): MachineAck = MachineAck.Ok
        override fun resume(): MachineAck = MachineAck.Ok

        override fun stop(): MachineAck {
            record("stop")
            return stopAck
        }

        override fun workoutState(): Int? = null
        override fun autoFanSupported(): Boolean? = null
        override fun speedPresetsMph(): List<Double>? = null

        /**
         * Ignores [spacing], because it has no range to space: this console answers "no presets" the
         * way an unbound transport does, and the end-of-workout path never asks. Named rather than
         * `_` so the next person reading this does not have to check whether it was forgotten.
         */
        override fun inclinePresets(spacing: InclineSpacing): List<Double>? = null
        override fun limits(): MachineLimits? = null
    }

    private lateinit var console: RecordingConsole

    @Before
    fun bind() {
        console = RecordingConsole()
        // rebind rather than attach: it clears anything a previous test left queued and swaps the
        // console, where attach keeps the first one it was ever given.
        MachineCoordinator.rebind(console)
        MachineCoordinator.applyMachineLimits(null)
        // A known accepted fan state that is not Off. Without this baseline, "the fan was turned
        // off" could pass while comparing null with state cleared by the rebind above.
        MachineCoordinator.setFan(GlassOsCommands.FAN_HIGH)
        awaitCalls(1)
        console.calls.clear()
        publishObservation(null)
    }

    /** Wait for the worker to have made [count] calls, or fail the test rather than hang. */
    private fun awaitCalls(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (console.calls.size < count && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals("expected $count calls, saw ${console.calls}", count, console.calls.size)
    }

    /** Let the worker run to a standstill, so "nothing else was sent" means something. */
    private fun settle() {
        Thread.sleep(250)
    }

    /** Obtain the immutable machine/start identity that authorizes a restore. */
    private fun fanRestoreToken(): MachineCoordinator.FanRestoreToken {
        val tokens = java.util.concurrent.ArrayBlockingQueue<MachineCoordinator.FanRestoreToken>(1)
        MachineCoordinator.startWorkout { _, token -> tokens.add(token) }
        return tokens.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("start did not return a fan restore token")
    }

    /** Exactly what [WorkoutMachineCoupling] issues for a definitive end, in its order. */
    private fun endWorkout() {
        MachineCoordinator.stop()
        MachineCoordinator.settleAfterEnd()
    }

    /**
     * The same, with a confirmation watcher attached, as the coupling really does it.
     *
     * Returns the verdict, waited for rather than polled so a test that is wrong hangs for its
     * timeout and then fails rather than passing on a race.
     */
    private fun endWorkoutAwaitingVerdict(): StopVerdict {
        val settled = java.util.concurrent.ArrayBlockingQueue<StopVerdict>(4)
        MachineCoordinator.stop { settled.add(it) }
        MachineCoordinator.settleAfterEnd()
        return settled.poll(15, TimeUnit.SECONDS)
            ?: throw AssertionError("the stop confirmation never settled")
    }

    private fun publishObservation(observation: MachineLink.Observation?) {
        MachineLink::class.java.getDeclaredField("latestObservation").also {
            it.isAccessible = true
            it.set(MachineLink, observation)
        }
    }

    private fun stoppedObservation(seq: Long) = MachineLink.Observation(
        seq = seq,
        atMs = System.currentTimeMillis(),
        speedMph = 0.0,
        distanceMiles = 1.0,
        workoutEpoch = 1L,
        reportedMotionInWorkout = true,
    )

    /**
     * The stop is first on the wire, and the settling writes queue behind it.
     *
     * The ordering is not a nicety. `stop` empties the queue and takes the front of it; the
     * coordinator's settling sequence only appends. If that ever inverts, a treadmill waits for a
     * fan.
     *
     * No incline here, and that is the assertion rather than an omission: nothing has told this
     * coordinator what the belt is doing, and [mayFlattenDeck] refuses to move a deck it cannot see
     * a stopped belt behind.
     */
    @Test
    fun `the stop goes out before anything that settles the machine`() {
        endWorkout()
        awaitCalls(3)
        settle()
        assertEquals(listOf("stop", "speed 0.00", "fan 0"), console.calls)
    }

    @Test
    fun `fan off is on the wire before a positively authorized flatten`() {
        console.duringSpeedWrite = { publishObservation(stoppedObservation(10)) }
        console.duringFanWrite = { publishObservation(stoppedObservation(11)) }

        endWorkout()

        awaitCalls(4)
        assertEquals(listOf("stop", "speed 0.00", "fan 0", "incline 0.0"), console.calls)
    }

    /**
     * With no fresh telemetry, the deck is never moved.
     *
     * Stated on its own because it is a safety property and not a side effect of the test harness.
     * `MachineLink.speedMph` is null when the snapshot is stale or the machine could not be asked,
     * and an app that cannot see the belt must not move a physical part under a rider stepping off
     * it. The zero speed still goes out — that half is what covers a lost stop.
     */
    @Test
    fun `a belt Stride cannot see is not a belt whose deck it moves`() {
        endWorkout()
        awaitCalls(3)
        settle()
        assertTrue("the zero must still be re-asserted", console.calls.contains("speed 0.00"))
        assertTrue(
            "no incline may be written without an observed stop, saw ${console.calls}",
            console.calls.none { it.startsWith("incline") },
        )
    }

    /** The fan is genuinely turned off, not merely commanded to something. */
    @Test
    fun `ending a workout turns the fan off`() {
        endWorkout()
        awaitCalls(3)
        assertTrue("the fan must be told OFF", console.calls.contains("fan ${GlassOsCommands.FAN_OFF}"))
        assertEquals(GlassOsCommands.FAN_OFF, MachineCoordinator.lastFanState)
    }

    /**
     * The fan goes off even when Stride already believes it is off.
     *
     * Suppressing the write on [MachineCoordinator.lastFanState] looks like a free saving and is a
     * race twice over. A restore already in flight can turn the fan on right behind the stop, and an
     * accepted state can be stale because the console has fan controls of its own. The write is
     * cheap and a refusal is harmless.
     */
    @Test
    fun `the fan is turned off even when Stride thinks it already is`() {
        MachineCoordinator.setFan(GlassOsCommands.FAN_OFF)
        awaitCalls(1)
        console.calls.clear()

        endWorkout()
        awaitCalls(3)
        assertTrue(
            "an already-off fan is still told off; the console has its own controls",
            console.calls.contains("fan ${GlassOsCommands.FAN_OFF}"),
        )
    }

    @Test
    fun `a refused remembered restore does not become fan state`() {
        console.fanAck = MachineAck.Refused("fan unavailable")

        MachineCoordinator.restoreFan(GlassOsCommands.FAN_MEDIUM, fanRestoreToken())
        awaitCalls(1)
        settle()

        assertEquals(GlassOsCommands.FAN_HIGH, MachineCoordinator.lastFanState)
        assertEquals(GlassOsCommands.FAN_HIGH, MachineCoordinator.lastFanRequest)
    }

    @Test
    fun `a manual fan request stays visible until the write settles`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        console.fanEntered = entered
        console.releaseFan = release

        MachineCoordinator.setFan(GlassOsCommands.FAN_LOW)
        assertTrue("fan write never reached the transport", entered.await(5, TimeUnit.SECONDS))
        assertEquals(GlassOsCommands.FAN_HIGH, MachineCoordinator.lastFanState)
        assertEquals(GlassOsCommands.FAN_LOW, MachineCoordinator.lastFanRequest)

        release.countDown()
        awaitCalls(1)
        settle()
        assertEquals(GlassOsCommands.FAN_LOW, MachineCoordinator.lastFanState)
        assertEquals(GlassOsCommands.FAN_LOW, MachineCoordinator.lastFanRequest)
    }

    @Test
    fun `a fan write accepted after a stop is not recorded as state`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        console.fanEntered = entered
        console.releaseFan = release

        MachineCoordinator.setFan(GlassOsCommands.FAN_LOW)
        assertTrue("fan write never reached the transport", entered.await(5, TimeUnit.SECONDS))
        MachineCoordinator.stop()
        release.countDown()

        awaitCalls(2)
        settle()
        assertEquals(listOf("fan 1", "stop"), console.calls)
        assertEquals(GlassOsCommands.FAN_HIGH, MachineCoordinator.lastFanState)
        assertEquals(GlassOsCommands.FAN_HIGH, MachineCoordinator.lastFanRequest)
    }

    @Test
    fun `rebind clears fan evidence accepted by the previous machine`() {
        assertEquals(GlassOsCommands.FAN_HIGH, MachineCoordinator.lastFanState)

        console = RecordingConsole()
        MachineCoordinator.rebind(console)

        assertEquals(null, MachineCoordinator.lastFanState)
        assertEquals(null, MachineCoordinator.lastFanRequest)
    }

    @Test
    fun `an old transport acknowledgement cannot become new transport evidence`() {
        val oldConsole = console
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        oldConsole.fanEntered = entered
        oldConsole.releaseFan = release

        MachineCoordinator.setFan(GlassOsCommands.FAN_LOW)
        assertTrue("fan write never reached the old transport", entered.await(5, TimeUnit.SECONDS))
        console = RecordingConsole()
        MachineCoordinator.rebind(console)
        release.countDown()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (oldConsole.calls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        settle()
        assertEquals(listOf("fan 1"), oldConsole.calls)
        assertTrue("the new transport must not receive the old write", console.calls.isEmpty())
        assertEquals(null, MachineCoordinator.lastFanState)
        assertEquals(null, MachineCoordinator.lastFanRequest)
    }

    @Test
    fun `fan off follows a restore already in flight when end wins the race`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        console.fanEntered = entered
        console.releaseFan = release

        MachineCoordinator.restoreFan(GlassOsCommands.FAN_MEDIUM, fanRestoreToken())
        assertTrue("restore never reached the transport", entered.await(5, TimeUnit.SECONDS))
        endWorkout()
        assertEquals(GlassOsCommands.FAN_OFF, MachineCoordinator.lastFanRequest)

        console.releaseFan = null
        release.countDown()
        awaitCalls(4)
        settle()
        assertEquals(listOf("fan 2", "stop", "speed 0.00", "fan 0"), console.calls)
        assertEquals(GlassOsCommands.FAN_OFF, MachineCoordinator.lastFanState)
    }

    @Test
    fun `a restore submitted after end cannot turn the fan back on`() {
        val token = fanRestoreToken()

        endWorkout()
        MachineCoordinator.restoreFan(GlassOsCommands.FAN_MEDIUM, token)

        awaitCalls(3)
        settle()
        assertEquals(listOf("stop", "speed 0.00", "fan 0"), console.calls)
        assertEquals(GlassOsCommands.FAN_OFF, MachineCoordinator.lastFanState)
    }

    @Test
    fun `a newer start retires the previous starts fan restore token`() {
        val staleToken = fanRestoreToken()
        fanRestoreToken()

        MachineCoordinator.restoreFan(GlassOsCommands.FAN_MEDIUM, staleToken)

        settle()
        assertTrue("a stale start must not restore the fan", console.calls.isEmpty())
        assertEquals(GlassOsCommands.FAN_HIGH, MachineCoordinator.lastFanState)
    }

    @Test
    fun `a rebind retires the previous machines fan restore token`() {
        val staleToken = fanRestoreToken()
        console = RecordingConsole()
        MachineCoordinator.rebind(console)

        MachineCoordinator.restoreFan(GlassOsCommands.FAN_MEDIUM, staleToken)

        settle()
        assertTrue("the new transport must not receive the old restore", console.calls.isEmpty())
        assertEquals(null, MachineCoordinator.lastFanState)
        assertEquals(null, MachineCoordinator.lastFanRequest)
    }

    /**
     * A stop that lands mid-sequence retires everything the previous end had queued.
     *
     * The deck is the reason this is checked between the two writes of the re-assert and not only
     * before them: the speed write blocks for a round trip, and a deck movement authorised by a
     * session that ended before it started is a moving part outliving its generation.
     */
    @Test
    fun `a stop during the re-assert cancels the rest of it`() {
        // Every job is queued before the worker is allowed to touch any of them. The gate makes the
        // nested stop deterministic: it clears the already-queued fan and flatten jobs.
        val gate = CountDownLatch(1)
        console.blockUntil = gate
        console.duringSpeedWrite = {
            console.duringSpeedWrite = null
            MachineCoordinator.stop()
        }
        endWorkout()
        gate.countDown()
        awaitCalls(3)
        settle()
        assertEquals(listOf("stop", "speed 0.00", "stop"), console.calls)
    }

    /**
     * A machine that will not take a zero speed still gets its fan turned off.
     *
     * The two are independent jobs on purpose. A console that has already gone idle refuses
     * setpoints — that is the *expected* answer on the happy path — and letting a refusal there
     * suppress the fan would reintroduce issue #29 for every workout that ended normally.
     */
    @Test
    fun `a refused zero speed does not suppress the fan`() {
        console.speedAck = MachineAck.Refused("not in a workout")
        endWorkout()
        awaitCalls(3)
        settle()
        assertEquals(listOf("stop", "speed 0.00", "fan 0"), console.calls)
    }

    /**
     * A fan write that blows up does not take the end down with it.
     *
     * The rider has already left; the session is already IDLE. Anything that could make a failed
     * comfort control hold up an end, or surface as a crash, is a worse bug than the one being
     * fixed.
     */
    @Test
    fun `a fan write that throws still lets the workout end`() {
        console.fanThrows = IllegalStateException("transport went away")
        endWorkout()
        awaitCalls(3)
        settle()
        assertEquals(listOf("stop", "speed 0.00", "fan 0"), console.calls)
        // And the failure did not poison what Stride believes about the fan: the state is only ever
        // recorded for a write the machine actually took.
        assertEquals(GlassOsCommands.FAN_HIGH, MachineCoordinator.lastFanState)
    }

    /**
     * Neither call blocks the thread that ended the workout.
     *
     * That thread is the one notifying every workout listener, and on the overlay it is the main
     * thread. A console that has stopped answering must cost a rider a slow fan, not a frozen
     * screen over a treadmill.
     */
    @Test
    fun `ending a workout never blocks the caller`() {
        val gate = CountDownLatch(1)
        console.blockUntil = gate
        val startedAt = System.nanoTime()
        endWorkout()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("ending took ${elapsedMs}ms with the console wedged", elapsedMs < 500)
        gate.countDown()
        awaitCalls(3)
    }

    /**
     * A successful tidy-up never becomes the last word on an end whose stop failed.
     *
     * The settling writes run *after* the stop, so whatever they report would otherwise be the
     * outcome a caller reads back — and "the fan went off" is not an answer to "did the belt stop".
     * A stop is done on ack plus observed deceleration, and neither of those is anything this
     * sequence produces.
     */
    @Test
    fun `the settling writes do not overwrite the stop's outcome`() {
        console.stopAck = MachineAck.NoAnswer("the console did not answer")
        endWorkout()
        awaitCalls(3)
        settle()
        assertEquals("Stop", MachineCoordinator.lastLabel)
        assertTrue(
            "a failed stop must still read as failed, saw ${MachineCoordinator.lastOutcome}",
            MachineCoordinator.lastOutcome is MachineCoordinator.Outcome.Failed,
        )
    }

    // -------------------------------------------------------------- positive stop confirmation

    /**
     * **Issue #39.** No amount of writing to a console can produce a confirmed stop.
     *
     * The property the whole change turns on, tested against a console that says yes to everything
     * and reports no telemetry at all. Every command here acks; the belt is invisible; the verdict
     * must be unconfirmed. If this ever passes as [StopVerdict.Confirmed], somebody has wired an
     * ack back into the confirmation and the app is once again reporting "stopped" on the strength
     * of having asked.
     */
    @Test
    fun `a console that acks everything and reports nothing cannot confirm a stop`() {
        val verdict = endWorkoutAwaitingVerdict()
        assertTrue(
            "a write must never confirm a stop, saw $verdict",
            verdict is StopVerdict.Unconfirmed,
        )
    }

    /** A stop the console refused is answered immediately, not after the confirmation timeout. */
    @Test
    fun `a refused stop is reported without waiting out the confirmation`() {
        console.stopAck = MachineAck.Refused("not in a workout")
        val startedAt = System.nanoTime()
        val verdict = endWorkoutAwaitingVerdict()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertEquals(StopVerdict.Unconfirmed(StopUnconfirmed.NOT_ACKED), verdict)
        assertTrue(
            "a stop the console refused must not wait for telemetry that cannot help it; took ${elapsedMs}ms",
            elapsedMs < 4_000,
        )
    }

    /**
     * The stop still goes out first with a watcher attached, and the watcher adds nothing to the
     * wire.
     *
     * This is the regression test for the one way this change could have broken the invariant it
     * was written to serve: confirmation happens *after and alongside* a stop, so the sequence a
     * treadmill sees must be exactly what it was before.
     */
    @Test
    fun `attaching a confirmation does not change what reaches the wire`() {
        endWorkoutAwaitingVerdict()
        settle()
        assertEquals(listOf("stop", "speed 0.00", "fan 0"), console.calls)
    }

    /**
     * A watcher retired by a newer stop settles as superseded rather than vanishing.
     *
     * Two properties in one, and both are wedges if they break. It must **settle**, because a
     * session waiting on this verdict to leave [WorkoutSession.State.STOPPING] has nothing else to
     * release it. And it must settle as [StopUnconfirmed.SUPERSEDED] rather than as a failure,
     * because that is the one unconfirmed verdict that must not raise a safety alarm — the newer
     * stop's own watcher is the thing entitled to speak for the machine now.
     */
    @Test
    fun `a stop superseded by a newer one still settles, and does not alarm`() {
        val settled = java.util.concurrent.ArrayBlockingQueue<StopVerdict>(4)
        MachineCoordinator.stop { settled.add(it) }
        MachineCoordinator.stop()
        val verdict = settled.poll(15, TimeUnit.SECONDS)
            ?: throw AssertionError("a superseded confirmation never settled")
        assertEquals(StopVerdict.Unconfirmed(StopUnconfirmed.SUPERSEDED), verdict)
        assertTrue(
            "a superseded stop must not send anyone to the safety key",
            !shouldEscalate(verdict, StopCause.ENDED, consoleDetached = false, beltSeenMoving = true),
        )
    }

    /**
     * Ending still never blocks the caller, now with a watcher pending as well as a wedged console.
     *
     * That thread is the one notifying every workout listener, and on the overlay it is the main
     * thread. The watcher runs on its own scheduler precisely so that waiting seconds for a
     * treadmill cannot become waiting seconds for a screen.
     */
    @Test
    fun `ending never blocks the caller even with a confirmation pending`() {
        val gate = CountDownLatch(1)
        console.blockUntil = gate
        val startedAt = System.nanoTime()
        MachineCoordinator.stop { }
        MachineCoordinator.settleAfterEnd()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("ending took ${elapsedMs}ms with the console wedged", elapsedMs < 500)
        gate.countDown()
        awaitCalls(3)
    }
}
