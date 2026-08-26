package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the direct path actually puts on the wire for the control verbs.
 *
 * These matter because a wrong value here does not fail — it succeeds at doing something else. The
 * register block is a bitmask with the values packed behind it, so a command carrying the wrong
 * workout mode is a perfectly well-formed frame that the console will happily obey.
 *
 * The fake console below answers reads by decoding the request's own read mask, so the frames under
 * test are the real ones: if [FitProCodec] built a body the console could not parse, these would
 * fail rather than quietly agreeing with a hand-written fixture.
 */
class DirectWriteSequenceTest {

    /**
     * A console that parses requests properly and answers with whatever [status] is set to.
     *
     * Records every write it is sent so a test can assert on the value, not just the outcome.
     */
    private class FakeConsole : FitProTransport {
        override val name = "fake"
        override val connected = true
        // The generation a fake console claims. FitPro1 because that is the framing verified
        // against iFit's own code; nothing in these tests reaches the transport layer where
        // the two differ.
        override val variant = FitProCodec.Variant.FITPRO1

        /** Status returned for frames that carry writes. Reads always succeed. */
        var writeStatus: FitProCodec.Status = FitProCodec.Status.DONE

        /** Registers this console rejects. A frame naming any of them is refused whole. */
        val refuses = mutableSetOf<FitProCodec.Register>()

        /** When set, the console stops answering — a live link that has gone quiet. */
        var silent = false

        /** Every (register, value bytes) pair this console has been asked to write, in order. */
        val writes = mutableListOf<Pair<FitProCodec.Register, List<Int>>>()

        /** Values handed back for reads, by register. Anything absent reads as zero. */
        val readValues = mutableMapOf<FitProCodec.Register, Int>()

        override fun exchange(frame: ByteArray, command: FitProCodec.Command): ByteArray? {
            if (silent) return null
            // A real console validates before it acts, and so must this one. Without these checks
            // the fake answers a frame addressed to the wrong device, carrying the wrong command,
            // or with a corrupt checksum exactly as happily as a correct one — so a codec that
            // builds frames correctly (FitProCodecTest) plus a caller that builds them wrongly
            // would pass both suites. These are the only assertions covering that seam.
            assertEquals("frames must be addressed to MAIN", FitProCodec.ADDRESS_MAIN, frame[0].toInt() and 0xFF)
            assertEquals("the length byte must match the frame", frame.size, frame[1].toInt() and 0xFF)
            assertEquals(
                "register traffic must use READ_WRITE_DATA",
                FitProCodec.Command.READ_WRITE_DATA.value,
                frame[2].toInt() and 0xFF,
            )
            assertEquals(
                "the checksum must cover every byte before it",
                FitProCodec.checksum(frame, frame.size - 1),
                frame[frame.size - 1],
            )
            val body = frame.copyOfRange(3, frame.size - 1)
            var i = 0
            val writeMaskLen = body[i].toInt() and 0xFF
            i++
            val written = fieldsIn(body, i, writeMaskLen)
            i += writeMaskLen
            // Values follow the write mask in ascending field order, each as wide as its register.
            for (register in written) {
                writes += register to (0 until register.width).map { body[i + it].toInt() and 0xFF }
                i += register.width
            }
            val readMaskLen = body[i].toInt() and 0xFF
            i++
            val read = fieldsIn(body, i, readMaskLen)

            val values = ArrayList<Byte>()
            for (register in read) {
                val v = readValues[register] ?: 0
                for (b in 0 until register.width) values += ((v shr (8 * b)) and 0xFF).toByte()
            }
            val status = if (written.isEmpty()) {
                FitProCodec.Status.DONE
            } else if (written.any { it in refuses }) {
                FitProCodec.Status.FAILED
            } else {
                writeStatus
            }
            return reply(values.toByteArray(), status)
        }

        /** Registers whose bit is set in the [len]-byte mask starting at [from]. */
        private fun fieldsIn(body: ByteArray, from: Int, len: Int): List<FitProCodec.Register> {
            val ids = mutableListOf<Int>()
            for (b in 0 until len) {
                val mask = body[from + b].toInt() and 0xFF
                for (bit in 0 until 8) if (mask and (1 shl bit) != 0) ids += b * 8 + bit
            }
            return ids.sorted().mapNotNull { id -> FitProCodec.Register.entries.find { it.fieldId == id } }
        }

        private fun reply(values: ByteArray, status: FitProCodec.Status): ByteArray {
            val total = FitProCodec.FRAME_OVERHEAD + 1 + values.size
            val out = ByteArray(total)
            out[0] = FitProCodec.ADDRESS_MAIN.toByte()
            out[1] = total.toByte()
            out[2] = FitProCodec.Command.READ_WRITE_DATA.value.toByte()
            out[3] = status.value.toByte()
            values.copyInto(out, 4)
            out[total - 1] = FitProCodec.checksum(out, total - 1)
            return out
        }

        override fun close() = Unit
    }

    /** A session whose probe has been satisfied, so writes are permitted. */
    private fun ready(): Triple<DirectMachineCommands, DirectMachineSession, FakeConsole> {
        val wire = FakeConsole()
        // A plausible treadmill: 0.5-12 mph, 0-15% grade, currently stopped and flat.
        wire.readValues[FitProCodec.Register.MIN_KPH] = 80
        wire.readValues[FitProCodec.Register.MAX_KPH] = 1930
        wire.readValues[FitProCodec.Register.MIN_GRADE] = 0
        wire.readValues[FitProCodec.Register.MAX_GRADE] = 1500
        val session = DirectMachineSession(wire)
        session.probe.confirm(wire)
        return Triple(DirectMachineCommands(session), session, wire)
    }

    private fun modesWritten(wire: FakeConsole): List<Int> =
        wire.writes.filter { it.first == FitProCodec.Register.WORKOUT_MODE }.map { it.second.first() }

    /**
     * Resume writes `RESUME`, matching GlassOS.
     *
     * `xh/n0.p0` translates GlassOS's own `ConsoleState` to FitPro's `WorkoutMode` one for one, and
     * `ConsoleState.RESUME` maps to `WorkoutMode.RESUME`. Sending `RUNNING` instead — which is what
     * this used to do — is a different command that happens to look similar in a log.
     */
    @Test
    fun `resume writes RESUME rather than RUNNING`() {
        val (commands, _, wire) = ready()
        assertTrue("the probe must accept this console", wire.writes.isEmpty())
        commands.resume()
        assertEquals(
            "resume must send the mode GlassOS sends",
            listOf(FitProCodec.WorkoutMode.RESUME.value),
            modesWritten(wire),
        )
    }

    /**
     * A console that refuses `RESUME` still gets resumed.
     *
     * `RESUME` is a transient value that GlassOS deliberately never publishes as a state, so a
     * console without a distinct resume transition is a plausible machine rather than a broken one.
     * Refusing to restart a belt the rider asked to restart would be the worse failure.
     */
    @Test
    fun `resume falls back to RUNNING when the console refuses`() {
        val (commands, _, wire) = ready()
        wire.writeStatus = FitProCodec.Status.CMD_NOT_SUPPORTED
        commands.resume()
        assertEquals(
            "the fallback must follow the refusal, in that order",
            listOf(FitProCodec.WorkoutMode.RESUME.value, FitProCodec.WorkoutMode.RUNNING.value),
            modesWritten(wire),
        )
    }

    /** Start and pause are unchanged, and must not have been dragged into the resume rework. */
    @Test
    fun `start and pause send their own modes`() {
        val (commands, _, wire) = ready()
        commands.startWorkout()
        commands.pause()
        assertEquals(
            listOf(FitProCodec.WorkoutMode.RUNNING.value, FitProCodec.WorkoutMode.PAUSE.value),
            modesWritten(wire),
        )
    }

    /**
     * Starting a workout opens the belt at 1 mph on a flat deck, because that is what GlassOS does.
     *
     * `StartNewWorkout` takes no arguments, and measured on the real machine it started the belt at
     * 1.0 mph with no speed command sent by us. A rider pressing Start must get the same treadmill
     * whichever transport is selected, so DIRECT writes the state rather than hoping the firmware
     * picks it.
     */
    @Test
    fun `starting a workout opens the belt at 1 mph and flat`() {
        val (commands, _, wire) = ready()
        commands.startWorkout()
        assertEquals(
            "the opening speed must be 1 mph, expressed in kph",
            1.0,
            FitProValues.kphToMph(speedWritten(wire).last()),
            0.01,
        )
        assertEquals("a new workout starts flat", 0.0, inclineWritten(wire).last(), 0.001)
    }

    /**
     * The mode is written before the speed, in a separate frame.
     *
     * Values inside one register block are ordered by field id, so `KPH` (0) would reach the console
     * ahead of `WORKOUT_MODE` (12) — a speed arriving while the machine is still idle, which is the
     * case most likely to be discarded. Ordering the frames removes the question.
     */
    @Test
    fun `the opening speed is sent after the mode, not before it`() {
        val (commands, _, wire) = ready()
        commands.startWorkout()
        val mode = wire.writes.indexOfFirst { it.first == FitProCodec.Register.WORKOUT_MODE }
        val speed = wire.writes.indexOfFirst { it.first == FitProCodec.Register.KPH }
        assertTrue("both must be written", mode >= 0 && speed >= 0)
        assertTrue("mode at $mode must precede speed at $speed", mode < speed)
    }

    /**
     * A machine that will not run as slowly as 1 mph opens at the slowest speed it will run.
     *
     * The single hardware observation fits both "always 1 mph" and "always the machine's floor",
     * because the machine it was taken on has a 1.0 mph floor. Coercing satisfies both readings
     * where they agree and sends an acceptable speed where they do not.
     */
    @Test
    fun `the opening speed is raised to a floor the machine can actually run`() {
        val wire = FakeConsole()
        // A machine that will not run below 3 kph (about 1.9 mph).
        wire.readValues[FitProCodec.Register.MIN_KPH] = 300
        wire.readValues[FitProCodec.Register.MAX_KPH] = 1930
        wire.readValues[FitProCodec.Register.MIN_GRADE] = 0
        wire.readValues[FitProCodec.Register.MAX_GRADE] = 1500
        val session = DirectMachineSession(wire)
        session.probe.confirm(wire)
        DirectMachineCommands(session).startWorkout()
        assertEquals(
            "1 mph is below this machine's floor, so it must open at the floor",
            3.0,
            speedWritten(wire).last(),
            0.01,
        )
    }

    /**
     * A refused opening speed leaves the workout started.
     *
     * By the time the speed is written the console has already accepted the start, and the failure
     * this leaves is a stationary belt under a started workout — which is what DIRECT did before the
     * opening state existed. Reporting the start as failed would be worse: the coordinator would
     * show the rider an error for a workout the machine is actually running.
     */
    @Test
    fun `a refused opening speed does not fail the start`() {
        val (commands, _, wire) = ready()
        wire.refuses += FitProCodec.Register.KPH
        assertEquals(
            "the start itself was accepted",
            MachineAck.Ok,
            commands.startWorkout(),
        )
        assertEquals(
            "the console was still asked to start",
            listOf(FitProCodec.WorkoutMode.RUNNING.value),
            modesWritten(wire),
        )
    }

    /** Every speed the console was asked to run at, in kph, in order. */
    private fun speedWritten(wire: FakeConsole): List<Double> = wire.writes
        .filter { it.first == FitProCodec.Register.KPH }
        .map { FitProCodec.decodeSpeed(it.second.map(Int::toByte).toByteArray()) }

    /** Every incline the console was asked for, in percent, in order. */
    private fun inclineWritten(wire: FakeConsole): List<Double> = wire.writes
        .filter { it.first == FitProCodec.Register.GRADE }
        .map { FitProCodec.decodeIncline(it.second.map(Int::toByte).toByteArray()) }


    /**
     * Auto fan is unknown until asked, because nothing on the wire answers it.
     *
     * GlassOS resolves `IsAutoFanStateSupported` from a per-console configuration blob (`FanFeature`
     * in `ak/k0`, which carries its own `AutoFanSupported` field alongside the fan PWM range). No
     * FitPro register carries it, so the presence of a fan register — which this used to be inferred
     * from — says nothing about whether the console has an automatic mode.
     */
    @Test
    fun `auto fan support is unknown until a write settles it`() {
        val (commands, _, _) = ready()
        assertNull("nothing on the wire answers this, so it must not be guessed", commands.autoFanSupported())
    }

    @Test
    fun `an accepted auto fan write proves support`() {
        val (commands, _, _) = ready()
        commands.setFanState(GlassOsCommands.FAN_AUTO)
        assertEquals(true, commands.autoFanSupported())
    }

    @Test
    fun `a refused auto fan write proves the absence of support`() {
        val (commands, _, wire) = ready()
        wire.writeStatus = FitProCodec.Status.CMD_NOT_SUPPORTED
        commands.setFanState(GlassOsCommands.FAN_AUTO)
        assertEquals(false, commands.autoFanSupported())
    }

    /**
     * A link that has gone quiet must not be mistaken for a console without an automatic fan.
     *
     * Recording false here would disable the feature permanently on a machine that supports it,
     * because nothing would ever ask again. Only an explicit refusal is an answer.
     */
    @Test
    fun `a silent link leaves auto fan support unknown`() {
        val (commands, _, wire) = ready()
        wire.silent = true
        commands.setFanState(GlassOsCommands.FAN_AUTO)
        assertNull("silence is not an answer", commands.autoFanSupported())
    }

    /**
     * A refusal to set some other fan level says nothing about the automatic mode.
     *
     * The two are separate questions, and a console that rejects HIGH for its own reasons has not
     * told us anything about AUTO.
     */
    @Test
    fun `a refused non-auto fan write leaves auto support unknown`() {
        val (commands, _, wire) = ready()
        wire.writeStatus = FitProCodec.Status.CMD_NOT_SUPPORTED
        commands.setFanState(GlassOsCommands.FAN_HIGH)
        assertNull(commands.autoFanSupported())
    }

    // ---- the FitPro1 start gate ------------------------------------------------------------------

    /**
     * A console that completes a handshake, so [DirectMachineSession.connect] runs end to end.
     *
     * [FakeConsole] above cannot serve here: it answers no `DEVICE_INFO`, so its sessions never run
     * `connect()` and never reach the start-gate init at all. This one answers the handshake, and —
     * unlike either existing fake — records **frame boundaries**, which is the whole point. Whether
     * the two init writes share a frame or not is invisible to a fake that only records registers.
     */
    private class StartGateConsole(
        override val variant: FitProCodec.Variant = FitProCodec.Variant.FITPRO1,
        /** Field ids this console claims in its `DEVICE_INFO` mask. */
        private val fields: Set<Int> = FitProCodec.Register.entries.map { it.fieldId }.toSet(),
    ) : FitProTransport {
        override val name = "fake fitpro1"
        override val connected = true

        /** Status to answer a write of this register with. Absent means [FitProCodec.Status.DONE]. */
        val refuse = mutableMapOf<FitProCodec.Register, FitProCodec.Status>()

        /** Registers whose write frame gets no reply at all — a lost answer, not a refusal. */
        val silentFor = mutableSetOf<FitProCodec.Register>()

        /** One entry per `READ_WRITE_DATA` frame: the writes it carried, in wire order. */
        val frames = mutableListOf<List<Pair<FitProCodec.Register, Int>>>()

        /** Only the frames that actually wrote something. */
        val writeFrames: List<List<Pair<FitProCodec.Register, Int>>>
            get() = frames.filter { it.isNotEmpty() }

        /** Registers written, flattened across every frame, in the order they went out. */
        val written: List<Pair<FitProCodec.Register, Int>> get() = frames.flatten()

        private val readValues = mapOf(
            FitProCodec.Register.ACTUAL_KPH to 0,
            FitProCodec.Register.ACTUAL_INCLINE to 0,
            FitProCodec.Register.MIN_KPH to 80,
            FitProCodec.Register.MAX_KPH to 1930,
            FitProCodec.Register.MIN_GRADE to 0,
            FitProCodec.Register.MAX_GRADE to 1500,
        )

        /**
         * Register values this console will report, updated by every write it accepts.
         *
         * A real console reflects what it was told, and the start-gate init reads field 108 back to
         * check its own acknowledgement — a fake that always answered zero would fail that check for
         * the wrong reason. Pre-seed an entry to model a console that reports something Stride did
         * not write, which is exactly what `START_REQUESTED` is for.
         */
        val state = mutableMapOf<FitProCodec.Register, Int>()

        private fun valueOf(register: FitProCodec.Register): Int =
            state[register] ?: readValues[register] ?: 0

        override fun exchange(frame: ByteArray, command: FitProCodec.Command): ByteArray? = when (command) {
            FitProCodec.Command.DEVICE_INFO -> deviceInfo()
            FitProCodec.Command.READ_WRITE_DATA -> readWrite(frame)
            else -> null
        }

        private fun deviceInfo(): ByteArray {
            val maskCount = (fields.maxOrNull() ?: 0) / 8 + 1
            val total = 13 + maskCount + 1
            val out = ByteArray(total)
            out[0] = FitProCodec.ADDRESS_MAIN.toByte()
            out[1] = total.toByte()
            out[2] = FitProCodec.Command.DEVICE_INFO.value.toByte()
            out[3] = FitProCodec.Status.DONE.value.toByte()
            // At the threshold, not above it, so connect() skips the security handshake.
            out[4] = FitProCodec.SECURITY_REQUIRED_ABOVE.toByte()
            out[5] = 1
            out[12] = maskCount.toByte()
            for (id in fields) {
                val at = 13 + id / 8
                out[at] = (out[at].toInt() or (1 shl (id % 8))).toByte()
            }
            out[total - 1] = FitProCodec.checksum(out, total - 1)
            return out
        }

        private fun readWrite(frame: ByteArray): ByteArray? {
            val body = frame.copyOfRange(3, frame.size - 1)
            var i = 0
            val writeMaskLen = body[i].toInt() and 0xFF
            i++
            val writtenHere = fieldsIn(body, i, writeMaskLen)
            i += writeMaskLen
            val values = mutableListOf<Pair<FitProCodec.Register, Int>>()
            for (register in writtenHere) {
                values += register to (body[i].toInt() and 0xFF)
                i += register.width
            }
            frames += values

            if (writtenHere.any { it in silentFor }) return null
            val status = writtenHere.firstNotNullOfOrNull { refuse[it] } ?: FitProCodec.Status.DONE
            if (status == FitProCodec.Status.DONE) {
                for ((register, value) in values) state[register] = value
            }

            val readMaskLen = body[i].toInt() and 0xFF
            i++
            val out = ArrayList<Byte>()
            if (status == FitProCodec.Status.DONE) {
                for (register in fieldsIn(body, i, readMaskLen)) {
                    val v = valueOf(register)
                    for (b in 0 until register.width) out += ((v shr (8 * b)) and 0xFF).toByte()
                }
            }
            return reply(out.toByteArray(), status)
        }

        private fun fieldsIn(body: ByteArray, from: Int, len: Int): List<FitProCodec.Register> {
            val ids = mutableListOf<Int>()
            for (b in 0 until len) {
                val mask = body[from + b].toInt() and 0xFF
                for (bit in 0 until 8) if (mask and (1 shl bit) != 0) ids += b * 8 + bit
            }
            return ids.sorted().mapNotNull { id -> FitProCodec.Register.entries.find { it.fieldId == id } }
        }

        private fun reply(values: ByteArray, status: FitProCodec.Status): ByteArray {
            val total = FitProCodec.FRAME_OVERHEAD + 1 + values.size
            val out = ByteArray(total)
            out[0] = FitProCodec.ADDRESS_MAIN.toByte()
            out[1] = total.toByte()
            out[2] = FitProCodec.Command.READ_WRITE_DATA.value.toByte()
            out[3] = status.value.toByte()
            values.copyInto(out, 4)
            out[total - 1] = FitProCodec.checksum(out, total - 1)
            return out
        }

        override fun close() = Unit
    }

    private val requireStart = FitProCodec.Register.REQUIRE_START_REQUESTED
    private val idleLockout = FitProCodec.Register.IDLE_MODE_LOCKOUT

    /**
     * The gate-arming write goes out first, and in a frame of its own.
     *
     * The frame boundary is the assertion that matters. `registerBlock` sorts by field id, so
     * batching these two would put `IDLE_MODE_LOCKOUT` (95) ahead of `REQUIRE_START_REQUESTED`
     * (108) — the reverse of iFit's sequence, and the reverse of the safe one, since 108 arms a gate
     * and 95 removes one. `DIRECT_MACHINE_PROTOCOL.md` records the same trap for `KPH` overtaking
     * `WORKOUT_MODE`, which is why `startWorkout` splits its frames too.
     */
    @Test
    fun `the start gate arms before it clears the idle lockout, in separate frames`() {
        val wire = StartGateConsole()
        val result = DirectMachineSession(wire).connect()

        assertEquals(
            "each init write needs its own frame, or the order is whatever field id sorting says",
            listOf(listOf(requireStart to 1), listOf(idleLockout to 0)),
            wire.writeFrames,
        )
        assertEquals(
            DirectMachineSession.StartGate.Ready(idleModeLockoutCleared = true),
            result.startGate,
        )
    }

    /**
     * Nothing is written until the probe has confirmed the peer.
     *
     * `FitProProbe` exists to establish that this device is the motor controller and implements this
     * register table "before anything is allowed to write to it" — which is exactly what fields 108
     * and 95 assume. The first revision of this ran the init writes before `probe.confirm`.
     */
    @Test
    fun `the start gate writes nothing before the probe has confirmed the link`() {
        val wire = StartGateConsole()
        DirectMachineSession(wire).connect()

        val firstWrite = wire.frames.indexOfFirst { it.isNotEmpty() }
        val firstRead = wire.frames.indexOfFirst { it.isEmpty() }
        assertTrue("the probe's read must come first", firstRead in 0 until firstWrite)
    }

    /**
     * A refused arming write stops the sequence — the idle lockout is never cleared.
     *
     * This is the property that makes every failure mode leave the console *more* gated than it
     * started rather than less. Clearing the lockout after failing to arm the gate is the one
     * combination that must never reach the wire.
     */
    @Test
    fun `a refused arming write prevents the idle lockout from being cleared`() {
        val wire = StartGateConsole()
        wire.refuse[requireStart] = FitProCodec.Status.FAILED
        val result = DirectMachineSession(wire).connect()

        assertEquals("only the arming attempt may have gone out", listOf(listOf(requireStart to 1)), wire.writeFrames)
        assertTrue("the gate's state is unknown, not ready", result.startGateIncomplete)
    }

    /**
     * And so does a silent one. A lost reply is not a refusal.
     *
     * `FitProTransport.exchange` documents that a command whose reply was lost "may still have
     * landed", so silence leaves the gate unknown — it must not be read as "the write did nothing"
     * and it must not license clearing the lockout.
     */
    @Test
    fun `a silent arming write also prevents the idle lockout from being cleared`() {
        val wire = StartGateConsole()
        wire.silentFor += requireStart
        val result = DirectMachineSession(wire).connect()

        assertEquals(listOf(listOf(requireStart to 1)), wire.writeFrames)
        assertTrue("silence is not an answer", result.startGateIncomplete)
    }

    /**
     * An unfinished start gate refuses control, the same way an unconfirmed probe does.
     *
     * Without this the session reports itself connected and accepts speed and mode commands against
     * a console whose interlocks are in a state nobody established.
     */
    @Test
    fun `an incomplete start gate refuses commands that move the belt`() {
        val wire = StartGateConsole()
        wire.refuse[requireStart] = FitProCodec.Status.FAILED
        val session = DirectMachineSession(wire)
        session.connect()

        val ack = DirectMachineCommands(session).startWorkout()
        assertTrue("control must be refused, not attempted", ack is MachineAck.Refused)
        assertTrue(
            "no workout mode may reach a console in this state",
            wire.written.none { it.first == FitProCodec.Register.WORKOUT_MODE },
        )
    }

    /**
     * A console already holding a start request keeps its idle lockout.
     *
     * This is the auto-start guard. `connect()` runs unattended, from launch and from every
     * reconnect, so "Stride commands no motion" is a weaker claim than "no motion can result" on a
     * console where somebody already pressed Start on the panel. `PLAN.md` §5 admits no auto-start
     * from launch or boot, so the interlock stays where it is and control is refused instead.
     */
    @Test
    fun `a console already holding a start request keeps its idle lockout`() {
        val wire = StartGateConsole()
        wire.state[FitProCodec.Register.START_REQUESTED] = 1
        val result = DirectMachineSession(wire).connect()

        assertEquals(
            "the gate may be armed, but the lockout must not be released",
            listOf(listOf(requireStart to 1)),
            wire.writeFrames,
        )
        assertTrue(result.startGateIncomplete)
    }

    /**
     * And so does one that cannot be asked.
     *
     * A console that does not report `START_REQUESTED` cannot tell us whether a start is pending,
     * and absence of evidence is not evidence of absence when the question is whether a belt may
     * move on its own.
     */
    @Test
    fun `a console that cannot report a pending start keeps its idle lockout`() {
        val wire = StartGateConsole(
            fields = FitProCodec.Register.entries.map { it.fieldId }.toSet() -
                FitProCodec.Register.START_REQUESTED.fieldId,
        )
        val result = DirectMachineSession(wire).connect()

        assertEquals(listOf(listOf(requireStart to 1)), wire.writeFrames)
        assertTrue(result.startGateIncomplete)
    }

    /**
     * A stop still goes out even when the start gate never finished.
     *
     * `PLAN.md` §5: a stop "is never ramp-limited, delayed, or queued behind another command — it
     * preempts". An initialization we could not complete is precisely the sort of thing it must not
     * be queued behind, and the belt can be moving for reasons Stride did not cause: a rider can
     * start one from the console's own panel. Refusing a start in this state costs a workout;
     * refusing a stop is the failure this project exists to avoid.
     */
    @Test
    fun `a stop is not blocked by an incomplete start gate`() {
        val wire = StartGateConsole()
        wire.refuse[requireStart] = FitProCodec.Status.FAILED
        val session = DirectMachineSession(wire)
        session.connect()
        wire.frames.clear()

        val ack = DirectMachineCommands(session).stop()
        assertEquals("a stop must reach a console whose gate state is unknown", MachineAck.Ok, ack)
        assertTrue(
            "the stop must actually command zero speed",
            wire.written.any { it.first == FitProCodec.Register.KPH && it.second == 0 },
        )
    }

    /**
     * A console that never claims field 108 is untouched — the Commercial 1750 guard.
     *
     * This is the only thing standing between the one machine this project is actually tested on and
     * a pair of writes whose meaning was recovered from a different console generation.
     */
    @Test
    fun `a console that does not report the start gate field is left alone`() {
        val wire = StartGateConsole(
            fields = FitProCodec.Register.entries.map { it.fieldId }.toSet() - requireStart.fieldId,
        )
        val result = DirectMachineSession(wire).connect()

        assertTrue("no init writes belong on a console that never claimed the field", wire.writeFrames.isEmpty())
        assertEquals(DirectMachineSession.StartGate.NotApplicable, result.startGate)
    }

    /**
     * Neither is a FitPro2 board that happens to set the bit.
     *
     * These field semantics come from `Sindarin.FitPro1` and have no GlassOS/FitPro2 binding, so
     * capability alone is not enough to license the write — the generation has to match too.
     */
    @Test
    fun `a FitPro2 console is left alone even when it claims the field`() {
        val wire = StartGateConsole(variant = FitProCodec.Variant.FITPRO2)
        val result = DirectMachineSession(wire).connect()

        assertTrue("FitPro1-only semantics must not be written to a FitPro2 board", wire.writeFrames.isEmpty())
        assertEquals(DirectMachineSession.StartGate.NotApplicable, result.startGate)
    }
}
