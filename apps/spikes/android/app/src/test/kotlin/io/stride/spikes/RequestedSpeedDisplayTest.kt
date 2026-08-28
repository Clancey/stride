package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The clean-requested-speed refinement to issue #34's display fallback.
 *
 * `KPH` only holds hundredths of a km/h, so a requested 1 mph is rounded to the nearest
 * representable value (1.61 kph) before it is ever sent — a real rider saw this live as a 59:59
 * mile instead of 60:00. [DirectMachineSession.lastRequestedSpeedMph] lets
 * [DirectMachineClient.read] show the value that was actually asked for instead of the register's
 * own round-tripped echo, but only when an accepted write proves the two describe the same target.
 *
 * These drive the real write path (unlike [SpeedDisplayFallbackTest]'s fixture, which only ever
 * answers reads and cannot exercise [DirectMachineSession.lastRequestedSpeedMph] at all) through a
 * fake console that actually applies accepted writes to what it then reads back, so a bug that
 * skipped the tolerance check or fired on a refusal would show up as a wrong number, not just a
 * wrong code path.
 */
class RequestedSpeedDisplayTest {

    /** A console that applies accepted writes to its own state and refuses to be told a stale one. */
    private class FakeConsole(
        var mode: FitProCodec.WorkoutMode = FitProCodec.WorkoutMode.RUNNING,
    ) : FitProTransport {
        override val name = "fake"
        override val connected = true
        override val variant = FitProCodec.Variant.FITPRO1

        /** Status returned for the *next* write only; resets to DONE after each attempt. */
        var writeStatus: FitProCodec.Status = FitProCodec.Status.DONE
        val readValues = mutableMapOf<FitProCodec.Register, Int>()

        override fun exchange(frame: ByteArray, command: FitProCodec.Command): ByteArray {
            val body = frame.copyOfRange(3, frame.size - 1)
            var i = 0
            val writeMaskLen = body[i].toInt() and 0xFF
            i++
            val written = fieldsIn(body, i, writeMaskLen)
            i += writeMaskLen
            val accepted = writeStatus == FitProCodec.Status.DONE
            for (register in written) {
                if (accepted) readValues[register] = FitProCodec.leToInt(body.copyOfRange(i, i + register.width), register.width)
                i += register.width
            }
            val readMaskLen = body[i].toInt() and 0xFF
            i++
            val read = fieldsIn(body, i, readMaskLen)
            val values = ArrayList<Byte>()
            for (register in read) {
                val v = if (register == FitProCodec.Register.WORKOUT_MODE) mode.value else (readValues[register] ?: 0)
                for (b in 0 until register.width) values += ((v shr (8 * b)) and 0xFF).toByte()
            }
            val status = if (written.isEmpty()) FitProCodec.Status.DONE else writeStatus
            writeStatus = FitProCodec.Status.DONE
            return reply(values.toByteArray(), status)
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

    /** A session whose probe has been satisfied, so writes are permitted. 0.5-12 mph, 0-15% grade. */
    private fun ready(): Triple<DirectMachineCommands, DirectMachineSession, FakeConsole> {
        val wire = FakeConsole()
        wire.readValues[FitProCodec.Register.MIN_KPH] = 80
        wire.readValues[FitProCodec.Register.MAX_KPH] = 1930
        wire.readValues[FitProCodec.Register.MIN_GRADE] = 0
        wire.readValues[FitProCodec.Register.MAX_GRADE] = 1500
        val session = DirectMachineSession(wire)
        session.probe.confirm(wire)
        return Triple(DirectMachineCommands(session), session, wire)
    }

    @Test
    fun `an accepted request displays exactly, not the register's own quantization`() {
        val (commands, session, wire) = ready()
        wire.readValues[FitProCodec.Register.ACTUAL_KPH] = 0

        assertEquals(MachineAck.Ok, commands.setSpeedKph(FitProValues.mphToKph(1.0)))

        val snapshot = DirectMachineClient(session).read()!!
        // Without the fix this reads 1.0004 mph (1.61 kph, the nearest the register can hold) and
        // paces 59:59. With it, the rider's own clean ask is shown instead.
        assertEquals(1.0, snapshot.displaySpeedMph!!, 1e-9)
        assertEquals(60.0, snapshot.paceMinPerMile!!, 1e-9)
    }

    @Test
    fun `a refused write never overwrites what the console is still actually holding`() {
        val (commands, session, wire) = ready()
        wire.readValues[FitProCodec.Register.ACTUAL_KPH] = 0
        assertEquals(MachineAck.Ok, commands.setSpeedKph(FitProValues.mphToKph(1.0)))

        wire.writeStatus = FitProCodec.Status.FAILED
        commands.setSpeedKph(FitProValues.mphToKph(5.0))

        // The refusal must not have replaced the last *accepted* target with the declined one.
        assertEquals(1.0, session.lastRequestedSpeedMph!!, 1e-9)
        assertEquals(1.0, DirectMachineClient(session).read()!!.displaySpeedMph!!, 1e-9)
    }

    @Test
    fun `a console-side change beyond one quantization step is trusted over a stale request`() {
        val (commands, session, wire) = ready()
        wire.readValues[FitProCodec.Register.ACTUAL_KPH] = 0
        assertEquals(MachineAck.Ok, commands.setSpeedKph(FitProValues.mphToKph(1.0)))

        // Something other than this write changed what the register holds -- an iFit UI on the same
        // console, or a firmware clamp. 8.0 kph is nowhere near 1.61 kph, so the stale local target
        // must not win.
        wire.readValues[FitProCodec.Register.KPH] = 800

        val snapshot = DirectMachineClient(session).read()!!
        assertEquals(FitProValues.kphToMph(8.0), snapshot.displaySpeedMph!!, 0.001)
    }

    @Test
    fun `a fresh link has no requested speed to fall back on`() {
        val (_, session, wire) = ready()
        wire.readValues[FitProCodec.Register.ACTUAL_KPH] = 0
        wire.readValues[FitProCodec.Register.KPH] = 161

        assertNull(session.lastRequestedSpeedMph)
        val snapshot = DirectMachineClient(session).read()!!
        assertEquals(FitProValues.kphToMph(1.61), snapshot.displaySpeedMph!!, 1e-9)
    }
}
