package io.stride.spikes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level tests for the FTMS wire format.
 *
 * Every frame below is laid out **by hand from the Bluetooth SIG Fitness Machine Service spec**,
 * not captured from this codec's own output. A test written by running the parser and pasting the
 * result proves only that the code does what it does.
 *
 * That matters more here than in most codecs because Treadmill Data has no fixed layout. A parser
 * that mis-reads one flag does not throw — it reads every subsequent field from the wrong offset and
 * returns *plausible numbers*. Next to a treadmill, a plausible wrong speed is the dangerous
 * failure, so the offsets are pinned rather than trusted.
 */
class FtmsCodecTest {

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { (values[it] and 0xFF).toByte() }

    // ---- the inverted flag ------------------------------------------------------------------

    /**
     * Flags `0x0000`: "More Data" is **clear**, so Instantaneous Speed *is* present.
     *
     * 8.05 km/h → 805 → `0x0325` → `25 03` little-endian.
     */
    @Test
    fun `speed is present when the More Data bit is clear`() {
        val data = FtmsCodec.parseTreadmillData(bytes(0x00, 0x00, 0x25, 0x03))
        assertEquals(8.05, data!!.speedKph!!, 1e-9)
    }

    /**
     * Flags `0x0001`: "More Data" is **set**, so speed is absent.
     *
     * This is the inversion that breaks most FTMS parsers. A parser that reads bit 0 like every
     * other bit would decode the two bytes after the flags as a speed that was never sent.
     */
    @Test
    fun `speed is absent when the More Data bit is set`() {
        val data = FtmsCodec.parseTreadmillData(bytes(0x01, 0x00))
        assertNull(data!!.speedKph)
    }

    // ---- multi-field flags ------------------------------------------------------------------

    /**
     * Bit 3 carries **two** fields, not one: Inclination then Ramp Angle Setting, four bytes.
     *
     * Flags `0x0008`. Speed 4.00 km/h → 400 → `90 01`. Incline 3.0% → 30 → `1E 00`.
     * Ramp 1.7° → 17 → `11 00`.
     */
    @Test
    fun `inclination flag carries both incline and ramp angle`() {
        val data = FtmsCodec.parseTreadmillData(
            bytes(0x08, 0x00, 0x90, 0x01, 0x1E, 0x00, 0x11, 0x00),
        )!!
        assertEquals(4.0, data.speedKph!!, 1e-9)
        assertEquals(3.0, data.inclinePercent!!, 1e-9)
        assertEquals(1.7, data.rampAngleDegrees!!, 1e-9)
    }

    /**
     * A decline is signed. -3.0% → -30 → `0xFFE2` → `E2 FF`.
     *
     * Read unsigned this becomes +6553.4%, which is not an obviously wrong number to a clamp — it
     * is simply a very large one, and it would pass straight through an intersection with our
     * ceiling as "no lower bound".
     */
    @Test
    fun `a decline decodes as a negative grade`() {
        val data = FtmsCodec.parseTreadmillData(
            bytes(0x08, 0x00, 0x90, 0x01, 0xE2, 0xFF, 0x00, 0x00),
        )!!
        assertEquals(-3.0, data.inclinePercent!!, 1e-9)
    }

    /**
     * Total Distance is a **uint24** — the only three-byte field, and the usual reason an otherwise
     * correct parser drifts by one byte from there on.
     *
     * Flags `0x000C` = distance (bit 2) + inclination (bit 3). Speed `90 01`, distance 5000 m →
     * `0x001388` → `88 13 00`, incline 2.0% → `14 00`, ramp 0 → `00 00`.
     *
     * The incline assertion is what actually pins the width: read as a uint16, distance would leave
     * the incline one byte out and this value would be wrong.
     */
    @Test
    fun `total distance is three bytes and does not shift the fields after it`() {
        val data = FtmsCodec.parseTreadmillData(
            bytes(0x0C, 0x00, 0x90, 0x01, 0x88, 0x13, 0x00, 0x14, 0x00, 0x00, 0x00),
        )!!
        assertEquals(5000, data.totalDistanceMetres)
        assertEquals(2.0, data.inclinePercent!!, 1e-9)
    }

    /**
     * Bit 7 carries **three** fields across five bytes: total, per hour, per minute.
     *
     * Flags `0x0080`. Speed `90 01`, total 120 kcal → `78 00`, per hour 500 → `F4 01`,
     * per minute 8 → `08`.
     */
    @Test
    fun `expended energy flag carries three fields`() {
        val data = FtmsCodec.parseTreadmillData(
            bytes(0x80, 0x00, 0x90, 0x01, 0x78, 0x00, 0xF4, 0x01, 0x08),
        )!!
        assertEquals(120, data.totalEnergyKcal)
        assertEquals(500, data.energyPerHourKcal)
        assertEquals(8, data.energyPerMinuteKcal)
    }

    /**
     * `0xFFFF` is the spec's "Data Not Available", and must decode to unknown rather than to 65535.
     *
     * Same rule the GlassOS parser applies to `NaN`: a machine saying "I do not know" must not reach
     * the UI as a number.
     */
    @Test
    fun `energy reported as not available decodes to unknown`() {
        val data = FtmsCodec.parseTreadmillData(
            bytes(0x80, 0x00, 0x90, 0x01, 0xFF, 0xFF, 0xF4, 0x01, 0x08),
        )!!
        assertNull(data.totalEnergyKcal)
        assertEquals(500, data.energyPerHourKcal)
    }

    /**
     * The whole offset chain, end to end: energy (5 bytes) then elapsed time.
     *
     * Flags `0x0480` = expended energy (bit 7) + elapsed time (bit 10). Elapsed 3600 s → `0x0E10`
     * → `10 0E`. If the energy triple were read as one field, elapsed would land on the wrong bytes.
     */
    @Test
    fun `elapsed time survives the energy triple`() {
        val data = FtmsCodec.parseTreadmillData(
            bytes(0x80, 0x04, 0x90, 0x01, 0x78, 0x00, 0xF4, 0x01, 0x08, 0x10, 0x0E),
        )!!
        assertEquals(3600, data.elapsedSeconds)
    }

    @Test
    fun `heart rate decodes after the fields that precede it`() {
        // Flags 0x0100 = heart rate only (plus speed, since More Data is clear).
        val data = FtmsCodec.parseTreadmillData(bytes(0x00, 0x01, 0x90, 0x01, 0x8A))!!
        assertEquals(138, data.heartRateBpm)
    }

    // ---- truncation -------------------------------------------------------------------------

    /**
     * A packet that declares more than it carries is discarded **whole**.
     *
     * Not parsed up to the break: the speed here is valid, but publishing it alongside a missing
     * incline would let a fresh speed sit next to a stale grade, and the pair would describe a
     * machine that never existed. One consistent sample or none.
     */
    @Test
    fun `a truncated packet yields no sample at all`() {
        assertNull(FtmsCodec.parseTreadmillData(bytes(0x08, 0x00, 0x90, 0x01, 0x1E)))
    }

    @Test
    fun `a payload too short for flags is rejected`() {
        assertNull(FtmsCodec.parseTreadmillData(bytes(0x00)))
        assertNull(FtmsCodec.parseTreadmillData(null))
    }

    // ---- control point ----------------------------------------------------------------------

    @Test
    fun `control point op codes encode as the spec defines them`() {
        assertArrayEquals(bytes(0x00), FtmsCodec.encodeRequestControl())
        assertArrayEquals(bytes(0x07), FtmsCodec.encodeStartOrResume())
        // The stop parameter is mandatory, and stop and pause differ only by it.
        assertArrayEquals(bytes(0x08, 0x01), FtmsCodec.encodeStop())
        assertArrayEquals(bytes(0x08, 0x02), FtmsCodec.encodePause())
    }

    /** 8.05 km/h → 805 → `0x0325`, little-endian, behind op code `0x02`. */
    @Test
    fun `set target speed encodes hundredths of a kph`() {
        assertArrayEquals(bytes(0x02, 0x25, 0x03), FtmsCodec.encodeSetTargetSpeed(8.05))
    }

    /** 3.0% → 30 → `0x001E`; -3.0% → -30 → `0xFFE2`, two's complement. */
    @Test
    fun `set target inclination encodes a signed tenth of a percent`() {
        assertArrayEquals(bytes(0x03, 0x1E, 0x00), FtmsCodec.encodeSetTargetInclination(3.0))
        assertArrayEquals(bytes(0x03, 0xE2, 0xFF), FtmsCodec.encodeSetTargetInclination(-3.0))
    }

    /**
     * The codec does **not** clamp.
     *
     * 30 km/h is far above anything Stride would permit, and it still encodes faithfully — because
     * clamping belongs to `MachineCoordinator` and duplicating it here would split the safety rules
     * across two files and make it ambiguous which is authoritative. This test exists so that a
     * later "defensive" clamp added here fails loudly instead of quietly becoming a second, weaker
     * source of truth.
     */
    @Test
    fun `the codec transmits what it was given rather than clamping`() {
        // 30 km/h -> 3000 -> 0x0BB8
        assertArrayEquals(bytes(0x02, 0xB8, 0x0B), FtmsCodec.encodeSetTargetSpeed(30.0))
    }

    /** Unrepresentable is a programming error, not a value to silently truncate. */
    @Test(expected = IllegalArgumentException::class)
    fun `a speed that cannot be represented is refused rather than truncated`() {
        FtmsCodec.encodeSetTargetSpeed(1000.0)
    }

    @Test
    fun `control point responses decode op code and result`() {
        val ok = FtmsCodec.parseControlResponse(bytes(0x80, 0x02, 0x01))!!
        assertEquals(FtmsCodec.OpCode.SET_TARGET_SPEED, ok.requestOpCode)
        assertTrue(ok.success)

        val denied = FtmsCodec.parseControlResponse(bytes(0x80, 0x02, 0x05))!!
        assertFalse(denied.success)
        assertEquals(FtmsCodec.Result.CONTROL_NOT_PERMITTED, denied.result)
    }

    /**
     * Anything that is not a response frame is "we do not know", not "refused".
     *
     * The difference is load-bearing: a command whose reply was lost may still have landed, so the
     * caller must report [MachineAck.NoAnswer] rather than telling the rider it was rejected.
     */
    @Test
    fun `a non-response frame decodes to unknown`() {
        assertNull(FtmsCodec.parseControlResponse(bytes(0x02, 0x01)))
        assertNull(FtmsCodec.parseControlResponse(bytes(0x80, 0x02)))
        assertNull(FtmsCodec.parseControlResponse(null))
    }

    // ---- ranges -----------------------------------------------------------------------------

    /** min 0.5 km/h → 50 → `32 00`; max 20 km/h → 2000 → `D0 07`; step 0.1 → 10 → `0A 00`. */
    @Test
    fun `supported speed range decodes hundredths of a kph`() {
        val range = FtmsCodec.parseSpeedRange(bytes(0x32, 0x00, 0xD0, 0x07, 0x0A, 0x00))!!
        assertEquals(0.5, range.minKph, 1e-9)
        assertEquals(20.0, range.maxKph, 1e-9)
        assertEquals(0.1, range.stepKph, 1e-9)
    }

    /**
     * The inclination minimum is **signed** — a machine that declines advertises a negative one.
     *
     * min -3.0% → `E2 FF`; max 15.0% → 150 → `96 00`; step 0.5% → 5 → `05 00`.
     */
    @Test
    fun `supported inclination range decodes a negative minimum`() {
        val range = FtmsCodec.parseInclinationRange(bytes(0xE2, 0xFF, 0x96, 0x00, 0x05, 0x00))!!
        assertEquals(-3.0, range.minPercent, 1e-9)
        assertEquals(15.0, range.maxPercent, 1e-9)
        assertEquals(0.5, range.stepPercent, 1e-9)
    }

    @Test
    fun `a short range payload decodes to unknown`() {
        assertNull(FtmsCodec.parseSpeedRange(bytes(0x32, 0x00)))
        assertNull(FtmsCodec.parseInclinationRange(null))
    }

    // ---- features ---------------------------------------------------------------------------

    /**
     * Reporting a value and accepting a target for it are **different bits in different words**.
     *
     * A machine can stream speed while refusing to be told one. Conflating the two puts a control on
     * screen that always refuses.
     */
    @Test
    fun `features separate what is reported from what can be set`() {
        // machine word bit 1 = incline reporting; target word bits 0 and 1 = speed and incline targets.
        val both = FtmsCodec.parseFeatures(
            bytes(0x02, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00),
        )!!
        assertTrue(both.supportsInclineReporting)
        assertTrue(both.supportsSpeedTarget)
        assertTrue(both.supportsInclineTarget)

        // Reports incline, accepts no targets at all.
        val readOnly = FtmsCodec.parseFeatures(
            bytes(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        )!!
        assertTrue(readOnly.supportsInclineReporting)
        assertFalse(readOnly.supportsSpeedTarget)
        assertFalse(readOnly.supportsInclineTarget)
    }

    // ---- status -----------------------------------------------------------------------------

    @Test
    fun `machine status maps onto the GlassOS workout numbering`() {
        assertEquals(
            GlassOsCommands.WORKOUT_RUNNING,
            FtmsCodec.workoutStateFromStatus(bytes(0x04)),
        )
        assertEquals(
            GlassOsCommands.WORKOUT_PAUSED,
            FtmsCodec.workoutStateFromStatus(bytes(0x02, 0x02)),
        )
        assertEquals(
            GlassOsCommands.WORKOUT_IDLE,
            FtmsCodec.workoutStateFromStatus(bytes(0x02, 0x01)),
        )
    }

    /**
     * An unparameterised stop reads as stopped, not paused.
     *
     * Machines disagree about whether they send the parameter. Believing "paused" on a belt that has
     * actually stopped would leave Stride showing a live workout for a machine at rest; the narrower
     * reading is the safe one.
     */
    @Test
    fun `a stop with no parameter is not treated as a pause`() {
        assertEquals(
            GlassOsCommands.WORKOUT_IDLE,
            FtmsCodec.workoutStateFromStatus(bytes(0x02)),
        )
    }

    /** The safety key is the one true emergency stop. Nothing may report a live workout after it. */
    @Test
    fun `the safety key reports a stopped workout`() {
        assertEquals(
            GlassOsCommands.WORKOUT_IDLE,
            FtmsCodec.workoutStateFromStatus(bytes(0x03)),
        )
    }

    @Test
    fun `an unrecognised status is unknown rather than a guess`() {
        assertNull(FtmsCodec.workoutStateFromStatus(bytes(0x7F)))
        assertNull(FtmsCodec.workoutStateFromStatus(bytes()))
    }
}
