package io.stride.spikes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [FitProCodec], the pure Sindarin / FitPro register codec.
 *
 * These pin the two things this protocol is easiest to get wrong: the deliberate endianness
 * asymmetry (speed is big-endian, incline and distance little-endian) and the sign handling on a
 * negative incline. They also prove the read-only guard fires, because the whole file exists behind
 * a standing rule that nothing may command the belt — a codec that would silently encode a write to
 * a machine-reported register is a step toward exactly that.
 *
 * Nothing here transmits a byte; every assertion is over a `ByteArray` held in memory.
 */
class FitProCodecTest {

    // ---- speed: big-endian ----------------------------------------------------------------------

    @Test
    fun `speed encodes big-endian hundredths of a kph`() {
        // 5.5 kph -> (short)550 -> 0x0226, high byte first.
        assertArrayEquals(byteArrayOf(0x02, 0x26), FitProCodec.encodeSpeed(5.5))
        // A stationary belt is a true zero, not an absent value.
        assertArrayEquals(byteArrayOf(0x00, 0x00), FitProCodec.encodeSpeed(0.0))
        // 12.0 kph -> 1200 -> 0x04B0.
        assertArrayEquals(byteArrayOf(0x04, 0xB0.toByte()), FitProCodec.encodeSpeed(12.0))
    }

    @Test
    fun `speed round-trips big-endian`() {
        val bytes = FitProCodec.encodeSpeed(8.25)
        assertEquals(825L, FitProCodec.beBytesToLong(bytes, length = 2))
    }

    // ---- incline: little-endian, signed ---------------------------------------------------------

    @Test
    fun `incline encodes little-endian hundredths of a percent`() {
        // 3.0% -> 300 -> 0x0000012C, low byte first.
        assertArrayEquals(byteArrayOf(0x2C, 0x01, 0x00, 0x00), FitProCodec.encodeIncline(3.0))
    }

    @Test
    fun `a negative incline keeps its sign in two's complement little-endian`() {
        // -3.0% -> -300 -> 0xFFFFFED4 -> little-endian [D4, FE, FF, FF].
        assertArrayEquals(
            byteArrayOf(0xD4.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            FitProCodec.encodeIncline(-3.0),
        )
    }

    // ---- distance: little-endian round trip -----------------------------------------------------

    @Test
    fun `distance round-trips through encode and decode`() {
        for (value in intArrayOf(0, 1, 255, 256, 12345, 1_000_000)) {
            assertEquals(value, FitProCodec.decodeDistance(FitProCodec.encodeDistance(value)))
        }
    }

    @Test
    fun `distance decodes the exact little-endian bytes`() {
        // 12345 = 0x00003039 -> little-endian [39, 30, 00, 00].
        assertArrayEquals(byteArrayOf(0x39, 0x30, 0x00, 0x00), FitProCodec.encodeDistance(12345))
        assertEquals(12345, FitProCodec.decodeDistance(byteArrayOf(0x39, 0x30, 0x00, 0x00)))
    }

    // ---- FitPro2 envelope -----------------------------------------------------------------------

    @Test
    fun `the FitPro2 envelope carries the fixed prefix and a correct length byte`() {
        val payload = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E)
        val frame = FitProCodec.encodeFitPro2Frame(payload)

        assertArrayEquals(byteArrayOf(0x02, 0x04, 0x02), frame.copyOfRange(0, 3))
        assertEquals(payload.size, frame[3].toInt() and 0xFF)
        assertArrayEquals(payload, frame.copyOfRange(4, frame.size))
        assertEquals(4 + payload.size, frame.size)
    }

    // ---- BLE chunking ---------------------------------------------------------------------------

    @Test
    fun `a short payload becomes one lead packet and one terminating chunk`() {
        val payload = ByteArray(10) { it.toByte() }
        val packets = FitProCodec.chunkForBle(payload)

        assertEquals("lead packet plus a single data chunk", 2, packets.size)
        // Lead: [0xFE, 0x02, len, chunkCount].
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0x02, 10, 1), packets[0])
        // The single data chunk is also the last, so its index is 0xFF.
        assertEquals(0xFF, packets[1][0].toInt() and 0xFF)
        assertEquals(payload.size, packets[1][1].toInt() and 0xFF)
    }

    @Test
    fun `a long payload splits into several chunks with the last marked 0xFF`() {
        val payload = ByteArray(40) { it.toByte() } // ceil(40 / 18) = 3 data chunks
        val packets = FitProCodec.chunkForBle(payload)

        val lead = packets[0]
        val dataChunks = packets.drop(1)
        assertEquals(3, dataChunks.size)
        assertEquals("chunkCount in the lead packet", 3, lead[3].toInt() and 0xFF)
        assertEquals("payload length in the lead packet", 40, lead[2].toInt() and 0xFF)

        // Only the final data chunk is terminated with 0xFF; earlier ones carry sequential indexes.
        assertEquals(0, dataChunks[0][0].toInt() and 0xFF)
        assertEquals(1, dataChunks[1][0].toInt() and 0xFF)
        assertEquals(0xFF, dataChunks.last()[0].toInt() and 0xFF)
    }

    @Test
    fun `no BLE packet exceeds twenty bytes`() {
        val payload = ByteArray(255) { it.toByte() }
        for (packet in FitProCodec.chunkForBle(payload)) {
            assertTrue("packet of ${packet.size} bytes exceeds the 20-byte MTU", packet.size <= 20)
        }
    }

    @Test
    fun `reassembling the BLE data chunks reproduces the payload exactly`() {
        for (size in intArrayOf(0, 1, 18, 19, 36, 100, 255)) {
            val payload = ByteArray(size) { (it * 7).toByte() }
            val packets = FitProCodec.chunkForBle(payload)

            val reassembled = packets.drop(1).fold(ByteArray(0)) { acc, packet ->
                val dataLen = packet[1].toInt() and 0xFF
                acc + packet.copyOfRange(2, 2 + dataLen)
            }
            assertArrayEquals("payload of size $size did not round-trip", payload, reassembled)
        }
    }

    // ---- read-only guard ------------------------------------------------------------------------

    @Test
    fun `encoding a write to a read-only register throws`() {
        try {
            FitProCodec.encodeRegisterWrite(FitProCodec.Register.DISTANCE, byteArrayOf(0, 0, 0, 0))
            fail("writing a read-only register must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "the message should name the read-only-field condition",
                e.message!!.contains("read only", ignoreCase = true),
            )
        }
    }

    @Test
    fun `encoding a write to a writable register succeeds`() {
        val value = FitProCodec.encodeSpeed(6.0)
        val body = FitProCodec.encodeRegisterWrite(FitProCodec.Register.KPH, value)
        assertEquals(FitProCodec.Register.KPH.fieldId, body[0].toInt() and 0xFF)
        assertArrayEquals(value, body.copyOfRange(1, body.size))
    }

    // ---- WorkoutMode enum -----------------------------------------------------------------------

    @Test
    fun `workout mode maps to and from its wire value`() {
        assertEquals(2, FitProCodec.WorkoutMode.RUNNING.value)
        assertEquals(FitProCodec.WorkoutMode.RUNNING, FitProCodec.WorkoutMode.fromValue(2))
        // PAUSE_OVERRIDE jumps to 20, so its value is not its ordinal.
        assertEquals(20, FitProCodec.WorkoutMode.PAUSE_OVERRIDE.value)
        assertEquals(FitProCodec.WorkoutMode.PAUSE_OVERRIDE, FitProCodec.WorkoutMode.fromValue(20))
    }

    @Test
    fun `an unknown workout mode value resolves to UNKNOWN rather than throwing`() {
        assertEquals(FitProCodec.WorkoutMode.UNKNOWN, FitProCodec.WorkoutMode.fromValue(99))
        assertEquals(FitProCodec.WorkoutMode.UNKNOWN, FitProCodec.WorkoutMode.fromValue(15))
    }

    @Test
    fun `workout mode encodes as a single byte`() {
        assertArrayEquals(byteArrayOf(2), FitProCodec.encodeWorkoutMode(FitProCodec.WorkoutMode.RUNNING))
        assertArrayEquals(byteArrayOf(20), FitProCodec.encodeWorkoutMode(FitProCodec.WorkoutMode.PAUSE_OVERRIDE))
    }
}
