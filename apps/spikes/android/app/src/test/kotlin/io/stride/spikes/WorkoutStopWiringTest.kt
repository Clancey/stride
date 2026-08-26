package io.stride.spikes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * That pressing End still stops the belt.
 *
 * This is the regression test for the one way issue #39's fix could have done far more harm than
 * the bug it closes. `WorkoutSession.stop()` no longer publishes `IDLE`; it publishes `STOPPING`,
 * and `endFollowUp` had to be moved to fire on that transition instead. Get that wrong in either
 * direction and the app looks fine, every pure test still passes, and **no stop command reaches the
 * treadmill**.
 *
 * So this drives the real [WorkoutSession], through the real [WorkoutMachineCoupling], into the real
 * [MachineCoordinator], and asserts on what a console would have received. Nothing here is a pure
 * function, and that is the point — [WorkoutEndTest] already pins the decision, and a decision that
 * is right in a function nobody calls is worth nothing.
 */
class WorkoutStopWiringTest {

    /** Records what reached the wire. Only the verbs this path uses are answered with any care. */
    private class RecordingConsole : MachineCommands {
        override val transportName = "recording"

        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        override fun setSpeedKph(kph: Double): MachineAck {
            calls += "speed ${"%.2f".format(kph)}"
            return MachineAck.Ok
        }

        override fun setInclinePercent(percent: Double): MachineAck {
            calls += "incline ${"%.1f".format(percent)}"
            return MachineAck.Ok
        }

        override fun setFanState(state: Int): MachineAck {
            calls += "fan $state"
            return MachineAck.Ok
        }

        override fun stop(): MachineAck {
            calls += "stop"
            return MachineAck.Ok
        }

        override fun connect(): Int? = null
        override fun startWorkout(): MachineAck = MachineAck.Ok
        override fun pause(): MachineAck = MachineAck.Ok
        override fun resume(): MachineAck = MachineAck.Ok
        override fun workoutState(): Int? = null
        override fun autoFanSupported(): Boolean? = null
        override fun speedPresetsMph(): List<Double>? = null
        override fun inclinePresets(spacing: InclineSpacing): List<Double>? = null
        override fun limits(): MachineLimits? = null
    }

    private lateinit var console: RecordingConsole

    /** Every transition the session published, in order, as `state/ending`. */
    private val transitions = Collections.synchronizedList(mutableListOf<String>())

    private val recorder = WorkoutSession.Listener { state, ending ->
        transitions += "$state/${ending ?: "-"}"
    }

    @Before
    fun bind() {
        console = RecordingConsole()
        MachineCoordinator.rebind(console)
        MachineCoordinator.applyMachineLimits(null)
        // The singletons outlive a test, so put the session back to idle before each one rather
        // than inheriting whatever the previous test left.
        WorkoutSession.settle()
        WorkoutSession.abandon()
        WorkoutMachineCoupling.attach()
        WorkoutSession.addListener(recorder)
        transitions.clear()
        console.calls.clear()
    }

    @After
    fun unbind() {
        WorkoutSession.removeListener(recorder)
        WorkoutSession.settle()
        WorkoutSession.abandon()
        StopEscalation.acknowledge()
    }

    private fun awaitCall(what: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (console.calls.contains(what)) return
            Thread.sleep(5)
        }
        throw AssertionError("'$what' never reached the console; saw ${console.calls}")
    }

    /** Take a session all the way to RUNNING, the way the coupling does. */
    private fun runWorkout() {
        WorkoutSession.start()
        WorkoutSession.confirmStart()
        assertEquals(WorkoutSession.State.RUNNING, WorkoutSession.state)
        // Cleared here rather than in @Before: getting to RUNNING is setup, and its own transitions
        // and writes would otherwise be counted as the end's.
        console.calls.clear()
        transitions.clear()
    }

    /**
     * **The test this file exists for.** Ending a workout still puts a stop on the wire.
     *
     * If `endFollowUp` and `WorkoutSession.stop` ever disagree about which transition an end
     * arrives on, this is the only test in the suite that notices.
     */
    @Test
    fun `ending a workout still sends the stop`() {
        runWorkout()
        WorkoutSession.stop()
        awaitCall("stop")
        assertEquals("the stop must be first on the wire", "stop", console.calls.first())
    }

    /** And the session says so honestly while it waits: STOPPING, not IDLE. */
    @Test
    fun `ending a workout publishes stopping before idle`() {
        runWorkout()
        WorkoutSession.stop()
        assertEquals(WorkoutSession.State.STOPPING, WorkoutSession.state)
        assertEquals(
            "an end must not publish IDLE before the stop has settled",
            listOf("STOPPING/ENDED"),
            transitions.toList(),
        )
    }

    /**
     * The session leaves STOPPING once the stop settles, and does not wait for a rider.
     *
     * A session that could get stuck in STOPPING would be a worse bug than the one being fixed: the
     * app would be honest and unusable. What withholds Start after an unconfirmed stop is
     * [StopEscalation]'s latch, which the rider clears deliberately — not this state.
     */
    @Test
    fun `the session leaves stopping on its own once the stop settles`() {
        runWorkout()
        WorkoutSession.stop()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline && WorkoutSession.state != WorkoutSession.State.IDLE) {
            Thread.sleep(20)
        }
        assertEquals(
            "the session must settle to IDLE without a rider doing anything",
            WorkoutSession.State.IDLE,
            WorkoutSession.state,
        )
        assertEquals(listOf("STOPPING/ENDED", "IDLE/ENDED"), transitions.toList())
    }

    /**
     * **Issue #39, the sharp end.** A stop nothing could confirm raises the safety-key warning.
     *
     * Nothing here reports any telemetry, which is exactly the case §5.4 says must escalate rather
     * than report "stopped". The console acks every write, so if this ever fails it is because an
     * ack has been wired back into the confirmation.
     */
    @Test
    fun `an unconfirmable stop raises the safety key warning`() {
        runWorkout()
        WorkoutSession.stop()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline && !StopEscalation.active) Thread.sleep(20)
        assertTrue(
            "a stop with no telemetry behind it must escalate, not report stopped",
            StopEscalation.active,
        )
    }

    /**
     * An abandoned start still stops the belt, and still goes straight to IDLE.
     *
     * Both halves matter. The stop is what covers a start whose reply was lost, and it is unchanged
     * from before this issue. Going straight to IDLE is what keeps the retry path — the whole reason
     * `retryStart` exists — from being held behind a confirmation while a rider stands on a belt.
     */
    @Test
    fun `an abandoned start stops the belt and does not enter stopping`() {
        WorkoutSession.start()
        console.calls.clear()
        transitions.clear()
        WorkoutSession.abandon()
        assertEquals(WorkoutSession.State.IDLE, WorkoutSession.state)
        assertEquals(listOf("IDLE/ABANDONED"), transitions.toList())
        awaitCall("stop")
    }

    /** A pause is still not an end: no stop, no settling writes, and no STOPPING. */
    @Test
    fun `a pause still sends only a pause`() {
        runWorkout()
        WorkoutSession.pause()
        Thread.sleep(250)
        assertEquals(WorkoutSession.State.PAUSED, WorkoutSession.state)
        assertTrue("a pause must not stop the belt, saw ${console.calls}", "stop" !in console.calls)
        assertTrue(
            "a pause must not settle the machine, saw ${console.calls}",
            console.calls.none { it.startsWith("fan") || it.startsWith("incline") },
        )
    }
}
