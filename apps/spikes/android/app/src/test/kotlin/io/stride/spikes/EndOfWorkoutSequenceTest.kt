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

        /** Held by every call while set, so a test can prove the *caller* is not the one blocking. */
        var blockUntil: CountDownLatch? = null

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
            record("fan $state")
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
        override fun inclinePresets(): List<Double>? = null
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
        // A known fan state that is not Off, because [MachineCoordinator.lastFanState] is a
        // singleton that outlives a test. Without this baseline, "the fan was turned off" would
        // pass by inheriting whatever the previous test left behind.
        MachineCoordinator.setFan(GlassOsCommands.FAN_HIGH)
        awaitCalls(1)
        console.calls.clear()
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

    /** Exactly what [WorkoutMachineCoupling] issues for a definitive end, in its order. */
    private fun endWorkout() {
        MachineCoordinator.stop()
        MachineCoordinator.reassertZero()
        MachineCoordinator.stopFan()
    }

    /**
     * The stop is first on the wire, and the settling writes queue behind it.
     *
     * The ordering is not a nicety. `stop` empties the queue and takes the front of it; the two
     * settling calls only ever append. If that ever inverts, a treadmill waits for a fan.
     */
    @Test
    fun `the stop goes out before anything that settles the machine`() {
        endWorkout()
        awaitCalls(4)
        assertEquals(listOf("stop", "speed 0.00", "incline 0.0", "fan 0"), console.calls)
    }

    /** The fan is genuinely turned off, not merely commanded to something. */
    @Test
    fun `ending a workout turns the fan off`() {
        endWorkout()
        awaitCalls(4)
        assertTrue("the fan must be told OFF", console.calls.contains("fan ${GlassOsCommands.FAN_OFF}"))
        assertEquals(GlassOsCommands.FAN_OFF, MachineCoordinator.lastFanState)
    }

    /**
     * The fan goes off even when Stride already believes it is off.
     *
     * Suppressing the write on [MachineCoordinator.lastFanState] looks like a free saving and is a
     * race twice over. `restoreFan` records the state from inside its own queued job, so a restore
     * still in flight when the workout ends is invisible to such a check and would turn the fan on
     * right behind the stop; and `lastFanState` is only ever Stride's own last *request*, while the
     * console has fan controls of its own. The write is cheap and a refusal is harmless.
     */
    @Test
    fun `the fan is turned off even when Stride thinks it already is`() {
        MachineCoordinator.setFan(GlassOsCommands.FAN_OFF)
        awaitCalls(1)
        console.calls.clear()

        endWorkout()
        awaitCalls(4)
        assertTrue(
            "an already-off fan is still told off; the console has its own controls",
            console.calls.contains("fan ${GlassOsCommands.FAN_OFF}"),
        )
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
        console.duringSpeedWrite = {
            console.duringSpeedWrite = null
            MachineCoordinator.stop()
        }
        endWorkout()
        awaitCalls(3)
        settle()
        assertEquals(listOf("stop", "speed 0.00", "stop"), console.calls)
    }

    /**
     * A machine that will not take a zero speed does not get its deck moved.
     *
     * On the happy path this is the *expected* answer: the stop has already put the console in
     * IDLE, and an idle console refuses setpoints. The re-assert earns its round trips in the
     * unhappy case, where the stop frame was lost — and there the belt is still moving, which is
     * the last state to be dropping a deck in.
     */
    @Test
    fun `a refused zero speed leaves the deck where it is`() {
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
        awaitCalls(4)
        settle()
        assertEquals(listOf("stop", "speed 0.00", "incline 0.0", "fan 0"), console.calls)
        // And the failure did not poison what Stride believes about the fan: the state is only ever
        // recorded for a write the machine actually took.
        assertEquals(GlassOsCommands.FAN_HIGH, MachineCoordinator.lastFanState)
    }

    /**
     * None of the three calls blocks the thread that ended the workout.
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
        awaitCalls(4)
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
        awaitCalls(4)
        settle()
        assertEquals("Stop", MachineCoordinator.lastLabel)
        assertTrue(
            "a failed stop must still read as failed, saw ${MachineCoordinator.lastOutcome}",
            MachineCoordinator.lastOutcome is MachineCoordinator.Outcome.Failed,
        )
    }
}
