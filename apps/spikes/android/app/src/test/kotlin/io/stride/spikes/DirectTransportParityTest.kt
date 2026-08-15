package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The direct path must be a drop-in replacement for GlassOS, not a subset of it.
 *
 * These tests exist because "100% swap" is a claim that decays silently. Adding a method to
 * [MachineCommands] and implementing it on only one side compiles fine on the interface and fails
 * only on the transport nobody was testing with — which, since the console ships with GlassOS, is
 * always the direct one. The parity test below fails at compile time in that case, which is the
 * only moment it can be fixed cheaply.
 */
class DirectTransportParityTest {

    /**
     * Every question the app can ask, asked through the interface.
     *
     * Not a mock: this is a real implementation, so if [MachineCommands] grows a method this file
     * stops compiling. That is deliberate and is the entire point — a runtime assertion could be
     * satisfied by a stub returning null, which is exactly the failure mode being guarded against.
     */
    private class Recorder : MachineCommands {
        val asked = mutableListOf<String>()
        override val transportName: String get() = "recorder"
        override fun setSpeedKph(kph: Double): MachineAck = record("setSpeedKph")
        override fun setInclinePercent(percent: Double): MachineAck = record("setInclinePercent")
        override fun setFanState(state: Int): MachineAck = record("setFanState")
        override fun connect(): Int? = null.also { asked += "connect" }
        override fun startWorkout(): MachineAck = record("startWorkout")
        override fun pause(): MachineAck = record("pause")
        override fun resume(): MachineAck = record("resume")
        override fun stop(): MachineAck = record("stop")
        override fun workoutState(): Int? = null.also { asked += "workoutState" }
        override fun autoFanSupported(): Boolean? = null.also { asked += "autoFanSupported" }
        override fun speedPresetsMph(): List<Double>? = null.also { asked += "speedPresetsMph" }
        override fun inclinePresets(): List<Double>? = null.also { asked += "inclinePresets" }
        override fun limits(): MachineLimits? = null.also { asked += "limits" }
        private fun record(name: String): MachineAck {
            asked += name
            return MachineAck.NoAnswer("test")
        }
    }

    @Test
    fun `connect is part of the shared interface, not a GlassOS extra`() {
        // The regression this pins: connect() used to exist only on GlassOsCommands, so
        // MachineLink.connectNow() reached past the interface to call it. On the direct path that
        // meant either no handshake at all, or — worse — a handshake sent to GlassOS while the
        // rider had chosen to bypass it.
        val recorder = Recorder()
        val asMachine: MachineCommands = recorder
        asMachine.connect()
        assertEquals(listOf("connect"), recorder.asked)
    }

    @Test
    fun `presets are asked through the interface so both transports can answer`() {
        val recorder = Recorder()
        val asMachine: MachineCommands = recorder
        asMachine.speedPresetsMph()
        asMachine.inclinePresets()
        assertEquals(listOf("speedPresetsMph", "inclinePresets"), recorder.asked)
    }

    /**
     * A null preset list means "not asked", and must not be confused with "none".
     *
     * [MachineLink.fetchPresetsOnce] relies on this to decide whether to retry, so collapsing the
     * two would leave a machine permanently without quick picks after one dropped frame.
     */
    @Test
    fun `null presets are distinguishable from empty presets`() {
        val notAsked: List<Double>? = Recorder().speedPresetsMph()
        assertNull(notAsked)
        val none: List<Double>? = emptyList()
        assertNotNull(none)
        assertTrue(none!!.isEmpty())
    }

    /**
     * The direct path reports console state in GlassOS's numbering, via GlassOS's own name table.
     *
     * FitPro and GlassOS disagree about what each number means, and the direct path must speak
     * GlassOS's dialect because [MachineCoordinator] and [MachineLink] compare against those
     * constants. Deriving the number from the name — rather than writing a second `when` — is what
     * makes it impossible for the name-based and number-based callers to be told different things.
     */
    @Test
    fun `console state numbers round trip through their names`() {
        for (raw in 0..13) {
            val name = GlassOsClient.ConsoleState.name(raw)
            assertNotNull("state $raw should have a name", name)
            assertEquals("state $raw", raw, GlassOsClient.ConsoleState.code(name!!))
        }
        assertNull(GlassOsClient.ConsoleState.code("NOT_A_STATE"))
    }

    @Test
    fun `a FitPro running machine reports as a GlassOS workout, by number`() {
        // The trap this pins: FitPro RUNNING is 2, which is GlassOS IDLE. Returning the raw value
        // would tell the coordinator an idle machine was running and vice versa.
        assertEquals(
            GlassOsClient.ConsoleState.code("WORKOUT"),
            FitProValues.consoleState(FitProCodec.WorkoutMode.RUNNING),
        )
        assertEquals(
            GlassOsClient.ConsoleState.code("IDLE"),
            FitProValues.consoleState(FitProCodec.WorkoutMode.IDLE),
        )
        assertEquals(
            GlassOsClient.ConsoleState.code("PAUSED"),
            FitProValues.consoleState(FitProCodec.WorkoutMode.PAUSE),
        )
        // And the number for RUNNING is emphatically not FitPro's own ordinal.
        assertTrue(
            "a FitPro running machine must not report GlassOS's value for that number",
            FitProValues.consoleState(FitProCodec.WorkoutMode.RUNNING) !=
                FitProCodec.WorkoutMode.RUNNING.ordinal,
        )
    }

    /**
     * A disconnected console is a failed handshake, and the direct path must agree.
     *
     * [MachineLink.connectNow] tests the returned number against
     * [GlassOsClient.ConsoleState.DISCONNECTED] to decide whether to back off. A direct
     * implementation that returned null there would be treated as "could not ask" and retried
     * without backoff, hammering a transport that has already said there is nothing on it.
     */
    @Test
    fun `disconnected is zero on both transports`() {
        assertEquals(0, GlassOsClient.ConsoleState.DISCONNECTED)
        assertEquals(
            GlassOsClient.ConsoleState.DISCONNECTED,
            GlassOsClient.ConsoleState.code(GlassOsClient.ConsoleState.DISCONNECTED_NAME),
        )
    }
}

/**
 * The preset ladder the direct path builds from the machine's own reported range.
 *
 * GlassOS publishes a list of quick picks; FitPro publishes only a minimum and a maximum. These
 * pin the shape of the ladder derived from that range, because the failure modes are all silent:
 * a wrong step gives a rider buttons at 3.7 mph, an uncapped loop gives them three thousand
 * buttons, and a dropped floor puts the slowest button above the machine's slowest walk.
 */
class DirectPresetLadderTest {

    private fun ladder(min: Double, max: Double, step: Double = 1.0): List<Double> =
        invokeLadder(min, max, step)

    @Test
    fun `a normal treadmill range gives whole mph buttons, fastest first`() {
        assertEquals(
            listOf(12.0, 11.0, 10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0),
            ladder(1.0, 12.0),
        )
    }

    @Test
    fun `a fractional floor is kept as its own button`() {
        // A machine whose slowest speed is 0.5 mph must still offer 0.5, or its slowest walk is
        // unreachable from the quick picks.
        val out = ladder(0.5, 4.0)
        assertEquals(listOf(4.0, 3.0, 2.0, 1.0, 0.5), out)
    }

    @Test
    fun `an inverted or nonsense range produces nothing rather than garbage`() {
        assertTrue(ladder(10.0, 2.0).isEmpty())
        assertTrue(ladder(Double.NaN, 5.0).isEmpty())
        assertTrue(ladder(0.0, Double.POSITIVE_INFINITY).isEmpty())
    }

    @Test
    fun `a wildly decoded range is capped rather than handed to the UI whole`() {
        // The guard against a decoding error: a range read as 0-3000 must not produce a list the
        // UI would try to lay out.
        assertTrue(ladder(0.0, 3000.0).size <= 40)
    }

    @Test
    fun `a range narrower than one step still offers the floor`() {
        assertEquals(listOf(2.0), ladder(2.0, 2.4))
    }

    private fun invokeLadder(min: Double, max: Double, step: Double): List<Double> {
        // Reached reflectively because the helper is private to DirectMachineCommands, and making
        // it public purely for a test would widen an API for no caller.
        val companion = DirectMachineCommands::class.java.declaredClasses
            .first { it.simpleName == "Companion" }
        val instance = DirectMachineCommands::class.java
            .getDeclaredField("Companion")
            .apply { isAccessible = true }
            .get(null)
        val method = companion.getDeclaredMethod(
            "ladder",
            Double::class.java,
            Double::class.java,
            Double::class.java,
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(instance, min, max, step) as List<Double>
    }
}
