package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level tests for the BLE Heart Rate Measurement format.
 *
 * Laid out by hand from the SIG Heart Rate Service specification. The failure mode is the same one
 * FTMS has — a misread flag produces a plausible number rather than an error — with one addition
 * that makes it worse: **bit 0 changes the width of the very next field**, so getting it wrong does
 * not shift a later field, it changes the heart rate itself.
 *
 * A wrong number here is a claim about the rider's body, which is why zero and staleness are treated
 * as carefully as they are.
 */
class HeartRateCodecTest {

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { (values[it] and 0xFF).toByte() }

    // ---- the value width --------------------------------------------------------------------

    /** Flags `0x00`: bit 0 clear, so the value is a single byte. 138 → `0x8A`. */
    @Test
    fun `an eight-bit value decodes as one byte`() {
        assertEquals(138, HeartRateCodec.parseMeasurement(bytes(0x00, 0x8A))!!.bpm)
    }

    /**
     * Flags `0x01`: bit 0 set, so the value is **two** bytes, little-endian. 138 → `8A 00`.
     *
     * Straps only need 16 bits above 255 bpm, which no human reaches, so some report a plain 8-bit
     * value and others always report 16. The width must follow the flag rather than the
     * plausibility of the number — reading `8A 00` as one byte happens to give the right answer
     * here, but leaves the cursor wrong for everything after it.
     */
    @Test
    fun `a sixteen-bit value decodes as two little-endian bytes`() {
        assertEquals(138, HeartRateCodec.parseMeasurement(bytes(0x01, 0x8A, 0x00))!!.bpm)
    }

    /**
     * The width flag shifts every optional field after it.
     *
     * Flags `0x09` = 16-bit value (bit 0) + energy expended (bit 3). Reading the value as one byte
     * would take the energy from the wrong offset.
     */
    @Test
    fun `a sixteen-bit value does not displace the fields after it`() {
        val m = HeartRateCodec.parseMeasurement(
            bytes(0x09, 0x8A, 0x00, 0x78, 0x00),
        )!!
        assertEquals(138, m.bpm)
        assertEquals(120, m.energyExpendedKj)
    }

    // ---- zero is not a heart rate -----------------------------------------------------------

    /**
     * Straps emit 0 bpm while searching for a signal, at startup, and when taken off.
     *
     * Drawing that would put a confident `0` next to a running rider — both alarming and false. It
     * must read as "no reading", which the overlay already knows how to draw.
     */
    @Test
    fun `a zero reading is no reading at all`() {
        assertNull(HeartRateCodec.parseMeasurement(bytes(0x00, 0x00)))
        assertNull(HeartRateCodec.parseMeasurement(bytes(0x01, 0x00, 0x00)))
    }

    @Test
    fun `a payload too short to hold a value is rejected`() {
        assertNull(HeartRateCodec.parseMeasurement(bytes(0x00)))
        assertNull(HeartRateCodec.parseMeasurement(bytes(0x01, 0x8A)))
        assertNull(HeartRateCodec.parseMeasurement(null))
    }

    // ---- sensor contact ---------------------------------------------------------------------

    /**
     * Contact is three-state, and the three states mean different things.
     *
     * A strap that does not support contact reporting leaves both bits clear, which must read as
     * "does not say" — not as "not touching skin". Collapsing the two would mark every reading from
     * the many straps that omit it as untrustworthy, and Stride discards untrusted readings.
     */
    @Test
    fun `contact is unknown unless the strap says it supports reporting it`() {
        // Neither bit: not supported.
        assertNull(HeartRateCodec.parseMeasurement(bytes(0x00, 0x8A))!!.sensorContact)

        // Supported (bit 2) and detected (bit 1).
        assertEquals(
            true,
            HeartRateCodec.parseMeasurement(bytes(0x06, 0x8A))!!.sensorContact,
        )

        // Supported but not detected — the strap is telling us the number is not about the rider.
        assertEquals(
            false,
            HeartRateCodec.parseMeasurement(bytes(0x04, 0x8A))!!.sensorContact,
        )
    }

    /**
     * "Detected" without "supported" is still unknown.
     *
     * A strap that sets bit 1 while leaving bit 2 clear is contradicting itself, and believing the
     * optimistic half would report solid contact on a strap that never claimed to measure it.
     */
    @Test
    fun `a detected bit without a supported bit is not believed`() {
        assertNull(HeartRateCodec.parseMeasurement(bytes(0x02, 0x8A))!!.sensorContact)
    }

    // ---- RR intervals -----------------------------------------------------------------------

    /**
     * RR intervals repeat to the end of the packet — the count is never transmitted, so the length
     * is the count. They are carried in 1/1024 second units, not milliseconds.
     *
     * Flags `0x10` = RR present. `0x0400` = 1024 → exactly 1000 ms; `0x0200` = 512 → 500 ms.
     */
    @Test
    fun `rr intervals decode from 1024ths of a second and repeat to the end`() {
        val m = HeartRateCodec.parseMeasurement(
            bytes(0x10, 0x8A, 0x00, 0x04, 0x00, 0x02),
        )!!
        assertEquals(2, m.rrIntervalsMs.size)
        assertEquals(1000.0, m.rrIntervalsMs[0], 1e-9)
        assertEquals(500.0, m.rrIntervalsMs[1], 1e-9)
    }

    /** Energy comes before the RR list, so the list must start after it. */
    @Test
    fun `rr intervals start after the energy field`() {
        // Flags 0x18 = energy expended (bit 3) + RR (bit 4).
        val m = HeartRateCodec.parseMeasurement(
            bytes(0x18, 0x8A, 0x78, 0x00, 0x00, 0x04),
        )!!
        assertEquals(120, m.energyExpendedKj)
        assertEquals(listOf(1000.0), m.rrIntervalsMs.map { it })
    }

    /**
     * A packet whose RR block is not a whole number of intervals is discarded entirely.
     *
     * It previously kept the intervals that happened to parse and ignored the odd trailing byte.
     * But a packet that disagrees with its own flags is not a packet we understand, and the part
     * that parsed is not more trustworthy than the part that did not — the heart rate itself is
     * read from the same bytes.
     */
    @Test
    fun `a malformed rr block rejects the whole packet`() {
        assertNull(HeartRateCodec.parseMeasurement(bytes(0x10, 0x8A, 0x00, 0x04, 0x99)))
        // Bit 4 set with no interval at all is equally malformed.
        assertNull(HeartRateCodec.parseMeasurement(bytes(0x10, 0x8A)))
    }

    @Test
    fun `a plain measurement reports no optional fields`() {
        val m = HeartRateCodec.parseMeasurement(bytes(0x00, 0x8A))!!
        assertNull(m.energyExpendedKj)
        assertTrue(m.rrIntervalsMs.isEmpty())
    }

    // ---- the informational reads ------------------------------------------------------------

    @Test
    fun `body sensor location decodes the codes the spec defines`() {
        assertEquals("chest", HeartRateCodec.bodySensorLocation(bytes(0x01)))
        assertEquals("wrist", HeartRateCodec.bodySensorLocation(bytes(0x02)))
        // An unrecognised code is null rather than a number: "location 9" tells a rider nothing.
        assertNull(HeartRateCodec.bodySensorLocation(bytes(0x09)))
        assertNull(HeartRateCodec.bodySensorLocation(null))
    }

    @Test
    fun `battery level is a percentage or nothing`() {
        assertEquals(87, HeartRateCodec.batteryPercent(bytes(0x57)))
        assertEquals(100, HeartRateCodec.batteryPercent(bytes(0x64)))
        // Out of range means the characteristic is not what we think it is.
        assertNull(HeartRateCodec.batteryPercent(bytes(0xFF)))
        assertNull(HeartRateCodec.batteryPercent(bytes()))
    }
}
