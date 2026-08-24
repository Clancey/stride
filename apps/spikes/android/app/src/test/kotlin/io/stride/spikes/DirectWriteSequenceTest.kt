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

        override fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
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
}
