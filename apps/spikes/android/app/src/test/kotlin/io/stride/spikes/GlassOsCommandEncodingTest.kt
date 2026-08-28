package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encoding tests for the command path.
 *
 * These matter more than most: [GlassOsWire.encodeDouble] is what turns "8 mph" into the bytes a
 * treadmill acts on. A byte-order mistake here does not throw, it does not fail to compile, and it
 * does not show up in a screenshot — it makes the belt run at the wrong speed. So the expected
 * bytes are written out literally rather than derived with the same helper being tested.
 */
class GlassOsCommandEncodingTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02x", it) }

    @Test
    fun `encodes a double as field 1 wire type 1, little endian`() {
        // 1.0 as IEEE-754 is 0x3FF0000000000000. Little-endian on the wire, so the bytes reverse.
        // Tag for field 1, wire type 1 is (1 << 3) | 1 = 0x09.
        assertEquals("09 00 00 00 00 00 00 f0 3f", hex(GlassOsWire.encodeDouble(1, 1.0)))
    }

    @Test
    fun `encodes a representative belt speed`() {
        // 8 mph is 12.874752 kph, which is what SetSpeed would actually carry.
        val bytes = GlassOsWire.encodeDouble(1, 12.874752)
        assertEquals(9, bytes.size)
        assertEquals(0x09.toByte(), bytes[0])
        // Round-trips back to the same number through the parser the responses use.
        val parsed = GlassOsWire.parse(bytes).double(1)
        assertEquals(12.874752, parsed!!, 1e-9)
    }

    @Test
    fun `writes zero rather than omitting it, because zero means stop`() {
        // Proto3 normally drops a field equal to its default. Dropping this one would turn
        // "set speed to 0" into "say nothing", which is the difference between stopping and not.
        val bytes = GlassOsWire.encodeDouble(1, 0.0)
        assertEquals(9, bytes.size)
        assertEquals(0.0, GlassOsWire.parse(bytes).double(1)!!, 0.0)
    }

    @Test
    fun `encodes a negative incline`() {
        val bytes = GlassOsWire.encodeDouble(1, -3.0)
        assertEquals(-3.0, GlassOsWire.parse(bytes).double(1)!!, 1e-9)
    }

    @Test
    fun `hasField distinguishes an absent oneof branch from a false value`() {
        // WorkoutResult is a oneof: field 1 is an error, field 2 is bool success. Proto3 omits a
        // false bool, so presence is the only way to tell the branches apart.
        val empty = GlassOsWire.parse(ByteArray(0))
        assertTrue(!empty.hasField(1))
        assertTrue(!empty.hasField(2))
        assertEquals(null, empty.bool(2))
    }

    @Test
    fun `coordinator clamps stay inside the machine's reported range`() {
        // Speed is still model 17125's own figure (1.0-12.0 mph). Incline was deliberately widened
        // to -6/40% to cover incline trainers past model 17125's 12% -- confirmed against an X22i's
        // own reported range, not a guess -- so it is checked against that range instead.
        assertTrue(MachineCoordinator.MAX_SPEED_MPH <= 12.0)
        assertTrue(MachineCoordinator.MAX_INCLINE <= 40.0)
        assertTrue(MachineCoordinator.MIN_INCLINE >= -6.0)
        // Zero must remain reachable even though the machine's minimum running speed is 1.0 mph,
        // because zero is how the belt is told to stop.
        assertEquals(0.0, MachineCoordinator.MIN_SPEED_MPH, 0.0)
    }

    @Test
    fun `StartWorkoutResponse nests the result rather than carrying an error in field 1`() {
        // The exact bytes a real 1750 console returned from StartNewWorkout. Read as a bare
        // WorkoutResult, field 1 looks like an error and the start was reported to the rider as a
        // refusal while the belt was actually starting. It is a StartWorkoutResponse, so field 1 is
        // the nested WorkoutResult and this payload means success.
        val raw = byteArrayOf(0x0a, 0x02, 0x10, 0x01)
        val response = GlassOsWire.parse(raw)

        val nested = response.message(1)
        assertTrue(nested != null)
        assertEquals(true, nested!!.bool(2))
        assertTrue(!nested.hasField(1))
    }

    @Test
    fun `a fan state encodes even when it is zero`() {
        // FAN_STATE_OFF is 0, and proto3 omits zero. Writing it anyway is what makes "off" sendable.
        val off = GlassOsWire.encodeVarintField(1, GlassOsCommands.FAN_OFF)
        assertEquals(2, off.size)
        assertEquals(0x08.toByte(), off[0])
        assertEquals(0x00.toByte(), off[1])
        assertEquals(0, GlassOsWire.parse(off).enum(1))

        val auto = GlassOsWire.encodeVarintField(1, GlassOsCommands.FAN_AUTO)
        assertEquals(GlassOsCommands.FAN_AUTO, GlassOsWire.parse(auto).enum(1))
    }
}
