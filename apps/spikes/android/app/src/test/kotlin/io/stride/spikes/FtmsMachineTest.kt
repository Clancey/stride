package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FTMS driver above the wire: how a machine's replies become [MachineAck]s, and how a pushed
 * sample becomes the snapshot the rest of the app reads.
 *
 * This is where the FTMS path either upholds the project's rules or quietly breaks them, because
 * everything above it trusts what these functions return. A refusal reported as a failure makes a
 * working control look broken; a failure reported as a refusal tells a rider their machine said no
 * when in fact nobody knows whether the command landed. `MachineCoordinator` treats those
 * differently and cannot tell them apart itself.
 */
class FtmsMachineTest {

    /**
     * A machine that answers however the test needs it to.
     *
     * Records the frames it was given, which is how the "does not clamp" tests can assert on what
     * actually reached the wire rather than on what the driver claims it sent.
     */
    private class FakeLink(
        override val connected: Boolean = true,
        override val machineType: FtmsCodec.MachineType = FtmsCodec.MachineType.TREADMILL,
        override val features: FtmsCodec.Features? = ALL_FEATURES,
        override val speedRange: FtmsCodec.SpeedRange? = null,
        override val inclinationRange: FtmsCodec.InclinationRange? = null,
        private val sample: Pair<FtmsCodec.MachineData, Long>? = null,
        private val announced: Int? = null,
        /** Result code per op code; anything unlisted succeeds. */
        private val results: Map<Int, Int> = emptyMap(),
        /** Op codes the machine simply does not answer. */
        private val silent: Set<Int> = emptySet(),
        /**
         * Op codes to refuse with `control not permitted` exactly **once**, then accept.
         *
         * Models the real thing: a grant lapses, the machine refuses, and once control has been
         * taken again the same command works. A permanently refusing machine is a different case
         * and is covered by [results].
         */
        private val refuseOnceForControl: Set<Int> = emptySet(),
    ) : FtmsLink {
        override val name: String get() = "fake"
        val sent = mutableListOf<ByteArray>()

        /** Set by a test to model the machine announcing it took its control grant back. */
        var announceControlLost: Boolean = false

        private val refused = mutableSetOf<Int>()

        override fun latest() = sample
        override fun announcedWorkoutState() = announced

        override fun takeControlLost(): Boolean {
            val was = announceControlLost
            announceControlLost = false
            return was
        }

        override fun command(frame: ByteArray, timeoutMs: Long): FtmsCodec.ControlResponse? {
            sent += frame
            val op = frame[0].toInt() and 0xFF
            if (op in silent) return null
            if (op == FtmsCodec.OpCode.REQUEST_CONTROL) {
                return FtmsCodec.ControlResponse(op, FtmsCodec.Result.SUCCESS)
            }
            if (op in refuseOnceForControl && refused.add(op)) {
                return FtmsCodec.ControlResponse(op, FtmsCodec.Result.CONTROL_NOT_PERMITTED)
            }
            return FtmsCodec.ControlResponse(op, results[op] ?: FtmsCodec.Result.SUCCESS)
        }

        companion object {
            val ALL_FEATURES = FtmsCodec.Features(
                supportsInclineReporting = true,
                supportsSpeedTarget = true,
                supportsInclineTarget = true,
            )
        }
    }

    private fun opsSent(link: FakeLink): List<Int> = link.sent.map { it[0].toInt() and 0xFF }

    // ---- taking control ---------------------------------------------------------------------

    /**
     * FTMS machines reject every setpoint until a client has taken control, so the first setpoint
     * must be preceded by `RequestControl` without anybody having to remember to call it.
     */
    @Test
    fun `the first setpoint requests control first`() {
        val link = FakeLink()
        val commands = FtmsMachineCommands(link)

        assertEquals(MachineAck.Ok, commands.setSpeedKph(8.0))

        assertEquals(
            listOf(FtmsCodec.OpCode.REQUEST_CONTROL, FtmsCodec.OpCode.SET_TARGET_SPEED),
            opsSent(link),
        )
    }

    /** Control is granted once and held, so the second setpoint is a single frame. */
    @Test
    fun `control is not re-requested once it has been granted`() {
        val link = FakeLink()
        val commands = FtmsMachineCommands(link)

        commands.setSpeedKph(8.0)
        link.sent.clear()
        commands.setSpeedKph(9.0)

        assertEquals(listOf(FtmsCodec.OpCode.SET_TARGET_SPEED), opsSent(link))
    }

    /**
     * A grant can lapse underneath us — a rider touching the machine's own console is enough. When
     * the machine says so, the belief must be dropped, or every subsequent command repeats one the
     * machine will keep rejecting.
     */
    @Test
    fun `a control-not-permitted refusal makes the next command re-request control`() {
        val link = FakeLink(
            results = mapOf(
                FtmsCodec.OpCode.SET_TARGET_SPEED to FtmsCodec.Result.CONTROL_NOT_PERMITTED,
            ),
        )
        val commands = FtmsMachineCommands(link)

        commands.setSpeedKph(8.0)
        link.sent.clear()
        commands.setSpeedKph(9.0)

        // Two passes: the refusal drops the belief, and the retry re-requests before trying again.
        // A machine that refuses permanently gets exactly one retry, never a loop.
        assertEquals(
            listOf(
                FtmsCodec.OpCode.REQUEST_CONTROL,
                FtmsCodec.OpCode.SET_TARGET_SPEED,
                FtmsCodec.OpCode.REQUEST_CONTROL,
                FtmsCodec.OpCode.SET_TARGET_SPEED,
            ),
            opsSent(link),
        )
    }

    /**
     * **A stop refused for a lapsed grant is retried, not reported as a refusal.**
     *
     * This is the sharpest edge in the driver. A machine revokes control when something else claims
     * it or when it times the grant out. Without a retry the *first* command after that is always
     * refused and only the second re-requests control — survivable for a speed nudge, and not
     * survivable for a stop: the rider presses stop, the machine says "not permitted", and the belt
     * keeps running until they press it again.
     */
    @Test
    fun `a stop refused for lost control is retried after re-requesting it`() {
        val link = FakeLink(refuseOnceForControl = setOf(FtmsCodec.OpCode.STOP_OR_PAUSE))

        val ack = FtmsMachineCommands(link).stop()

        assertEquals("the stop must succeed, not surface a refusal", MachineAck.Ok, ack)
        assertEquals(
            listOf(
                FtmsCodec.OpCode.REQUEST_CONTROL,
                FtmsCodec.OpCode.STOP_OR_PAUSE,
                FtmsCodec.OpCode.REQUEST_CONTROL,
                FtmsCodec.OpCode.STOP_OR_PAUSE,
            ),
            opsSent(link),
        )
    }

    /**
     * An announced revocation is acted on *before* the command, not discovered by being refused.
     *
     * The machine publishes `Control Permission Lost` on its Status characteristic. Consuming it
     * means the command carries a fresh grant rather than spending a round trip finding out.
     */
    @Test
    fun `an announced control loss re-requests control before the next command`() {
        val link = FakeLink()
        val commands = FtmsMachineCommands(link)

        // Take control the ordinary way first, so the belief is established.
        commands.setSpeedKph(8.0)
        link.sent.clear()
        // The machine now announces it has taken control back.
        link.announceControlLost = true
        commands.setSpeedKph(9.0)

        assertEquals(
            listOf(FtmsCodec.OpCode.REQUEST_CONTROL, FtmsCodec.OpCode.SET_TARGET_SPEED),
            opsSent(link),
        )
    }

    /** A refusal the machine meant is still a refusal — the retry must not mask a real "no". */
    @Test
    fun `a refusal that is not about control is not retried`() {
        val link = FakeLink(
            results = mapOf(
                FtmsCodec.OpCode.SET_TARGET_SPEED to FtmsCodec.Result.INVALID_PARAMETER,
            ),
        )
        val ack = FtmsMachineCommands(link).setSpeedKph(8.0)

        assertTrue("expected Refused, got $ack", ack is MachineAck.Refused)
        assertEquals(
            listOf(FtmsCodec.OpCode.REQUEST_CONTROL, FtmsCodec.OpCode.SET_TARGET_SPEED),
            opsSent(link),
        )
    }

    // ---- refusal vs. no answer --------------------------------------------------------------

    /**
     * A machine that answered and said no is a **refusal**.
     *
     * The rider can be told their machine declined, and the coordinator can stop retrying.
     */
    @Test
    fun `a machine that declines produces a refusal, not a failure`() {
        val link = FakeLink(
            results = mapOf(
                FtmsCodec.OpCode.SET_TARGET_INCLINATION to FtmsCodec.Result.OPERATION_FAILED,
            ),
        )
        val ack = FtmsMachineCommands(link).setInclinePercent(3.0)

        assertTrue("expected Refused, got $ack", ack is MachineAck.Refused)
    }

    /**
     * A machine that said nothing is **not** a refusal.
     *
     * The command may still have landed. Reporting "the machine refused" would be a claim nobody is
     * in a position to make, and it is the difference the coordinator needs to decide whether the
     * belt might now be moving.
     */
    @Test
    fun `silence produces no-answer, not a refusal`() {
        val link = FakeLink(silent = setOf(FtmsCodec.OpCode.SET_TARGET_SPEED))
        val ack = FtmsMachineCommands(link).setSpeedKph(8.0)

        assertTrue("expected NoAnswer, got $ack", ack is MachineAck.NoAnswer)
    }

    @Test
    fun `a disconnected link answers no-answer without touching the wire`() {
        val link = FakeLink(connected = false)
        val ack = FtmsMachineCommands(link).setSpeedKph(8.0)

        assertTrue("expected NoAnswer, got $ack", ack is MachineAck.NoAnswer)
        assertTrue("nothing should have been sent", link.sent.isEmpty())
    }

    // ---- capability gating ------------------------------------------------------------------

    /**
     * Reporting a value and accepting a target for it are different feature bits.
     *
     * A machine that streams its speed but will not be told one gets a refusal rather than a frame
     * it would reject anyway — and, importantly, the driver does not send it.
     */
    @Test
    fun `a machine that accepts no speed target is refused locally`() {
        val link = FakeLink(
            features = FtmsCodec.Features(
                supportsInclineReporting = true,
                supportsSpeedTarget = false,
                supportsInclineTarget = true,
            ),
        )
        val ack = FtmsMachineCommands(link).setSpeedKph(8.0)

        assertTrue("expected Refused, got $ack", ack is MachineAck.Refused)
        assertTrue("nothing should have been sent", link.sent.isEmpty())
    }

    /**
     * A machine that never answered the feature read is **not** assumed incapable.
     *
     * Unknown is not refusal — the same rule the GlassOS path applies to `CanWrite`. Refusing here
     * would disable every control on a machine that simply did not publish its features.
     */
    @Test
    fun `unknown features do not disable the controls`() {
        val link = FakeLink(features = null)
        assertEquals(MachineAck.Ok, FtmsMachineCommands(link).setSpeedKph(8.0))
    }

    /** The profile has no fan. A definite no, so the UI stops offering it rather than retrying. */
    @Test
    fun `the fan is refused because the profile has none`() {
        val link = FakeLink()
        val commands = FtmsMachineCommands(link)

        assertTrue(commands.setFanState(GlassOsCommands.FAN_HIGH) is MachineAck.Refused)
        assertEquals(false, commands.autoFanSupported())
        assertTrue("nothing should have been sent", link.sent.isEmpty())
    }

    // ---- no clamping ------------------------------------------------------------------------

    /**
     * The driver transmits what it was handed.
     *
     * 30 km/h is far above anything Stride would permit, and it must still reach the wire unaltered,
     * because clamping belongs to `MachineCoordinator`. A second, weaker clamp here would split the
     * safety rules across two files and make it ambiguous which one is authoritative.
     */
    @Test
    fun `a setpoint is transmitted unclamped`() {
        val link = FakeLink()
        FtmsMachineCommands(link).setSpeedKph(30.0)

        val frame = link.sent.last()
        // 30 km/h -> 3000 -> 0x0BB8, little-endian behind op code 0x02.
        assertEquals(FtmsCodec.OpCode.SET_TARGET_SPEED, frame[0].toInt())
        assertEquals(0xB8, frame[1].toInt() and 0xFF)
        assertEquals(0x0B, frame[2].toInt() and 0xFF)
    }

    /** A value the codec cannot represent becomes a refusal, never an exception across the queue. */
    @Test
    fun `an unrepresentable setpoint is refused rather than thrown`() {
        val link = FakeLink()
        val ack = FtmsMachineCommands(link).setSpeedKph(10_000.0)

        assertTrue("expected Refused, got $ack", ack is MachineAck.Refused)
    }

    // ---- stop -------------------------------------------------------------------------------

    /**
     * Stop uses the profile's own stop, not a speed of zero.
     *
     * A belt driven to 0 kph is still, as far as the machine is concerned, inside a running workout.
     * The machine's notion of workout state is the authoritative one.
     */
    @Test
    fun `stop sends the profile stop rather than a zero speed`() {
        val link = FakeLink()
        FtmsMachineCommands(link).stop()

        val frame = link.sent.last()
        assertEquals(FtmsCodec.OpCode.STOP_OR_PAUSE, frame[0].toInt())
        assertEquals(FtmsCodec.StopParam.STOP, frame[1].toInt())
    }

    @Test
    fun `pause and stop are distinguished by their parameter`() {
        val link = FakeLink()
        FtmsMachineCommands(link).pause()

        assertEquals(FtmsCodec.StopParam.PAUSE, link.sent.last()[1].toInt())
    }

    // ---- limits and presets -----------------------------------------------------------------

    @Test
    fun `limits come from the machine's own advertised ranges`() {
        val link = FakeLink(
            speedRange = FtmsCodec.SpeedRange(minKph = 0.8, maxKph = 20.0, stepKph = 0.1),
            inclinationRange = FtmsCodec.InclinationRange(-3.0, 15.0, 0.5),
        )
        val limits = FtmsMachineCommands(link).limits()!!

        assertEquals(0.8, limits.minSpeedKph, 1e-9)
        assertEquals(20.0, limits.maxSpeedKph, 1e-9)
        assertEquals(-3.0, limits.minInclinePercent, 1e-9)
        assertEquals(15.0, limits.maxInclinePercent, 1e-9)
    }

    /**
     * A half-known limit is worse than none.
     *
     * It would look like a machine that declared itself, and the missing half would silently fall
     * back to a default the machine never agreed to.
     */
    @Test
    fun `limits are withheld unless both ranges were read`() {
        val link = FakeLink(
            speedRange = FtmsCodec.SpeedRange(0.8, 20.0, 0.1),
            inclinationRange = null,
        )
        assertNull(FtmsMachineCommands(link).limits())
    }

    /**
     * Quick picks are whole mph, not the machine's own resolution.
     *
     * A machine advertising a 0.1 km/h step would otherwise produce hundreds of near-identical
     * buttons, capped to an arbitrary forty.
     */
    @Test
    fun `speed presets are whole mph buttons within the machine's range`() {
        val link = FakeLink(
            speedRange = FtmsCodec.SpeedRange(minKph = 1.6, maxKph = 16.0, stepKph = 0.1),
        )
        val presets = FtmsMachineCommands(link).speedPresetsMph()!!

        assertTrue("expected a usable ladder, got $presets", presets.size in 2..40)
        // Descending, matching the GlassOS preset order the UI lays out top-down.
        assertEquals(presets.sortedDescending(), presets)
        // 16 km/h is 9.94 mph, so the top button must not claim 10.
        assertTrue("top preset ${presets.first()} exceeds the machine's max", presets.first() <= 9.95)
    }

    @Test
    fun `reported FTMS ranges are intersected with installation clamps`() {
        val commands = FtmsMachineCommands(
            FakeLink(
                speedRange = FtmsCodec.SpeedRange(minKph = 1.6, maxKph = 32.0, stepKph = 0.1),
                inclinationRange = FtmsCodec.InclinationRange(-6.0, 40.0, 0.5),
            ),
        )

        val speed = commands.speedPresetsMph()!!
        assertEquals(MachineCoordinator.MAX_SPEED_MPH, speed.first(), 1e-9)
        assertEquals(speed.size, speed.distinct().size)

        val incline = commands.inclinePresets(InclineSpacing.COARSE)!!
        assertEquals(
            listOf(40.0, 35.0, 30.0, 25.0, 20.0, 15.0, 10.0, 5.0, 0.0, -3.0, -6.0),
            incline,
        )
        assertEquals(incline.size, incline.distinct().size)
    }

    @Test
    fun `presets are unknown rather than empty when the machine did not publish a range`() {
        val link = FakeLink(speedRange = null, inclinationRange = null)
        val commands = FtmsMachineCommands(link)

        // Null and empty mean different things: null is "ask again", empty is "this machine offers
        // none". Collapsing them would make a transport failure look like a machine with no presets
        // and the UI would stop retrying.
        assertNull(commands.speedPresetsMph())
        assertNull(commands.inclinePresets(InclineSpacing.FINE))
    }

    // ---- workout state ----------------------------------------------------------------------

    /** What the machine announced wins: it is the only direct statement available. */
    @Test
    fun `an announced state is preferred over inference`() {
        val link = FakeLink(
            sample = FtmsCodec.MachineData(speedKph = 0.0) to 0L,
            announced = GlassOsCommands.WORKOUT_PAUSED,
        )
        assertEquals(GlassOsCommands.WORKOUT_PAUSED, FtmsMachineCommands(link).workoutState())
    }

    /**
     * With nothing announced and nothing being reported, the state is **unknown**.
     *
     * Not idle. Reporting idle would be a claim about a machine that has told us nothing, next to a
     * belt that may well be moving.
     */
    @Test
    fun `no announcement and no sample is unknown rather than idle`() {
        assertNull(FtmsMachineCommands(FakeLink()).workoutState())
    }

    /**
     * A rower reports **no speed at all**, so speed cannot be the only signal of activity.
     *
     * Inferring from speed alone would report every rower and most trainers as idle while somebody
     * was working on them, which on a machine with a session running is the wrong answer.
     */
    @Test
    fun `a machine that reports no speed can still be seen to be working`() {
        val rowing = FakeLink(
            machineType = FtmsCodec.MachineType.ROWER,
            sample = FtmsCodec.MachineData(strokeRatePerMin = 24.0) to 0L,
        )
        assertEquals(GlassOsCommands.WORKOUT_RUNNING, FtmsMachineCommands(rowing).workoutState())

        val resting = FakeLink(
            machineType = FtmsCodec.MachineType.ROWER,
            sample = FtmsCodec.MachineData(strokeRatePerMin = 0.0) to 0L,
        )
        assertEquals(GlassOsCommands.WORKOUT_IDLE, FtmsMachineCommands(resting).workoutState())
    }

    /** A sample carrying no measure of effort at all says nothing, rather than saying "idle". */
    @Test
    fun `a sample with no effort signal leaves the state unknown`() {
        val link = FakeLink(sample = FtmsCodec.MachineData(elapsedSeconds = 60) to 0L)
        assertNull(FtmsMachineCommands(link).workoutState())
    }

    // ---- snapshot mapping -------------------------------------------------------------------

    /**
     * Units are converted exactly once, at this boundary.
     *
     * 8.05 km/h is 5.0 mph; 1609 m is 1.0 mile. Pace is derived from the *measured* speed, never
     * from distance over elapsed time.
     */
    @Test
    fun `a sample maps onto the snapshot in the units the app displays`() {
        val snapshot = FtmsValues.toSnapshot(
            FtmsCodec.MachineData(
                speedKph = 8.05,
                inclinePercent = 3.0,
                totalDistanceMetres = 1609,
                totalEnergyKcal = 120,
                elapsedSeconds = 600,
            ),
            features = FakeLink.ALL_FEATURES,
            workoutState = GlassOsCommands.WORKOUT_RUNNING,
        )

        assertEquals(5.0, snapshot.speedMph!!, 0.01)
        assertEquals(3.0, snapshot.inclinePercent!!, 1e-9)
        assertEquals(1.0, snapshot.distanceMiles!!, 0.01)
        assertEquals(12.0, snapshot.paceMinPerMile!!, 0.05)
        assertEquals(600L, snapshot.elapsedSeconds)
        assertEquals(120.0, snapshot.calories!!, 1e-9)
        assertEquals("WORKOUT", snapshot.consoleState)
    }

    /**
     * The workout identity is what lets the GlassOS-shaped rule downstream tell a **measured zero**
     * from "nothing is being measured". A running workout must carry one.
     */
    @Test
    fun `a running workout stamps a workout id and an idle one does not`() {
        val running = FtmsValues.toSnapshot(
            FtmsCodec.MachineData(speedKph = 0.0),
            features = null,
            workoutState = GlassOsCommands.WORKOUT_RUNNING,
        )
        assertNotNull(running.workoutId)

        val idle = FtmsValues.toSnapshot(
            FtmsCodec.MachineData(speedKph = 0.0),
            features = null,
            workoutState = GlassOsCommands.WORKOUT_IDLE,
        )
        assertNull(idle.workoutId)
    }

    /** No fan in the profile, so the control is definitely not writable rather than unknown. */
    @Test
    fun `the snapshot reports the fan as unwritable rather than unknown`() {
        val snapshot = FtmsValues.toSnapshot(
            FtmsCodec.MachineData(speedKph = 4.0),
            features = FakeLink.ALL_FEATURES,
            workoutState = null,
        )
        assertEquals(false, snapshot.fanWritable)
        assertEquals(true, snapshot.speedWritable)
    }

    /**
     * A machine that reports heart rate carries it into the snapshot.
     *
     * This is the only transport that can: GlassOS and the register path leave it null. It is the
     * fallback behind a chest strap, not a replacement for one — on a treadmill it comes from grips
     * a running rider is not holding.
     */
    @Test
    fun `machine-reported heart rate reaches the snapshot`() {
        val snapshot = FtmsValues.toSnapshot(
            FtmsCodec.MachineData(speedKph = 8.05, heartRateBpm = 142),
            features = null,
            workoutState = GlassOsCommands.WORKOUT_RUNNING,
        )
        assertEquals(142, snapshot.heartRateBpm)
    }

    /** A machine that reports no heart rate leaves it unknown, never zero. */
    @Test
    fun `a machine with no heart rate sensor reports none`() {
        val snapshot = FtmsValues.toSnapshot(
            FtmsCodec.MachineData(speedKph = 8.05),
            features = null,
            workoutState = GlassOsCommands.WORKOUT_RUNNING,
        )
        assertNull(snapshot.heartRateBpm)
    }

    /** Below a walking pace, pace is unknown rather than a huge number dressed up as information. */
    @Test
    fun `pace is withheld on a stopped belt`() {
        val snapshot = FtmsValues.toSnapshot(
            FtmsCodec.MachineData(speedKph = 0.0),
            features = null,
            workoutState = GlassOsCommands.WORKOUT_RUNNING,
        )
        assertNull(snapshot.paceMinPerMile)
    }

    // ---- freshness --------------------------------------------------------------------------

    /**
     * A sample that has stopped arriving stops being a reading.
     *
     * This is the whole reason the transport timestamps its cache. A number that has quietly
     * stopped updating looks exactly like a number that is still true, and next to a belt a stale
     * `0.0` reads as "stopped".
     */
    @Test
    fun `a sample older than the TTL is not a reading`() {
        val sample = FtmsCodec.MachineData(speedKph = 8.0) to 1_000L

        assertNotNull(FtmsValues.fresh(sample, now = 1_000L + FtmsValues.SAMPLE_TTL_MS))
        assertNull(FtmsValues.fresh(sample, now = 1_000L + FtmsValues.SAMPLE_TTL_MS + 1))
        assertNull(FtmsValues.fresh(null, now = 1_000L))
    }
}
