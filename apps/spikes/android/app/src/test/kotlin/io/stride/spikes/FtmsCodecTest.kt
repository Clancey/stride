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

    // ---- interoperability with qdomyos-zwift ------------------------------------------------

    /**
     * The exact frame `qdomyos-zwift` emits to Zwift, decoded.
     *
     * Taken from its `CharacteristicNotifier2ACD::notify`, which is the FTMS treadmill payload it
     * publishes as a virtual machine and which is known to work against production Zwift. That makes
     * it the closest thing to a reference vector available without hardware: if this decodes wrong,
     * Stride disagrees with the most widely deployed FTMS implementation in this space.
     *
     * Layout it writes, flags `0x0E 0x05` (= `0x050E`): speed, average speed, total distance,
     * inclination + ramp, heart rate, elapsed time.
     *
     * ```
     * 0E 05                      flags
     * 25 03                      speed        805 -> 8.05 km/h
     * BC 02                      avg speed    700 -> 7.00 km/h
     * 49 06 00                   distance     1609 m
     * 1E 00                      incline      30 -> 3.0 %
     * 11 00                      ramp         17 -> 1.7 deg
     * 8A                         heart rate   138 bpm
     * 58 02                      elapsed      600 s
     * ```
     */
    @Test
    fun `the frame qdomyos-zwift publishes to Zwift decodes field for field`() {
        val data = FtmsCodec.parseTreadmillData(
            bytes(
                0x0E, 0x05,
                0x25, 0x03,
                0xBC, 0x02,
                0x49, 0x06, 0x00,
                0x1E, 0x00,
                0x11, 0x00,
                0x8A,
                0x58, 0x02,
            ),
        )!!

        assertEquals(8.05, data.speedKph!!, 1e-9)
        assertEquals(7.00, data.averageSpeedKph!!, 1e-9)
        assertEquals(1609, data.totalDistanceMetres)
        assertEquals(3.0, data.inclinePercent!!, 1e-9)
        assertEquals(1.7, data.rampAngleDegrees!!, 1e-9)
        assertEquals(138, data.heartRateBpm)
        assertEquals(600, data.elapsedSeconds)
    }

    /**
     * The same source's other layout, flags `0x0C 0x05`, which omits average speed.
     *
     * Worth pinning separately because dropping a two-byte field shifts everything after it. A
     * parser that ignored the average-speed bit would still decode speed correctly here and get
     * distance, incline, heart rate and elapsed time all wrong.
     */
    @Test
    fun `the same publisher's no-average-speed layout also decodes`() {
        val data = FtmsCodec.parseTreadmillData(
            bytes(
                0x0C, 0x05,
                0x25, 0x03,
                0x49, 0x06, 0x00,
                0x1E, 0x00,
                0x11, 0x00,
                0x8A,
                0x58, 0x02,
            ),
        )!!

        assertEquals(8.05, data.speedKph!!, 1e-9)
        assertNull(data.averageSpeedKph)
        assertEquals(1609, data.totalDistanceMetres)
        assertEquals(3.0, data.inclinePercent!!, 1e-9)
        assertEquals(138, data.heartRateBpm)
        assertEquals(600, data.elapsedSeconds)
    }

    // ---- indoor bike (0x2AD2) ---------------------------------------------------------------

    /**
     * The flag *positions* differ from the treadmill's even where field names match.
     *
     * Bits 2 and 3 are cadence here and inclination/elevation on a treadmill. Sharing one flag table
     * between the two would decode a cadence as an incline — a wrong number, not a failure.
     *
     * Flags `0x0044` = instantaneous cadence (bit 2) + instantaneous power (bit 6), plus speed since
     * More Data is clear. Cadence 90 rpm is carried as 180 half-rpm → `B4 00`.
     */
    @Test
    fun `indoor bike data decodes cadence and power`() {
        val data = FtmsCodec.parseIndoorBikeData(
            bytes(
                0x44, 0x00,
                0x25, 0x03,
                0xB4, 0x00,
                0xFA, 0x00,
            ),
        )!!

        assertEquals(8.05, data.speedKph!!, 1e-9)
        assertEquals(90.0, data.cadenceRpm!!, 1e-9)
        assertEquals(250, data.powerWatts)
        // A bike has no incline, and must not invent one.
        assertNull(data.inclinePercent)
    }

    /** Distance sits at bit 4 on a bike and is still a uint24, so the fields after it must line up. */
    @Test
    fun `indoor bike distance is three bytes and keeps later fields aligned`() {
        // Flags 0x0210 = total distance (bit 4) + heart rate (bit 9). More Data clear, so speed too.
        val data = FtmsCodec.parseIndoorBikeData(
            bytes(
                0x10, 0x02,
                0x25, 0x03,
                0x49, 0x06, 0x00,
                0x8A,
            ),
        )!!

        assertEquals(1609, data.totalDistanceMetres)
        assertEquals(138, data.heartRateBpm)
    }

    // ---- cross trainer (0x2ACE) -------------------------------------------------------------

    /**
     * **The cross trainer's flags field is 24 bits, not 16.**
     *
     * This is the single most dangerous difference in the family. Reading a two-byte header here
     * leaves the cursor one byte early and decodes every field from the wrong offset, silently. The
     * assertion that matters is the speed: with a two-byte header it would decode as `0x2503`
     * hundredths — 95.71 km/h — rather than 8.05.
     *
     * Flags `0x000040` = inclination (bit 6), plus speed since More Data is clear.
     */
    @Test
    fun `cross trainer data reads a three-byte flags header`() {
        val data = FtmsCodec.parseCrossTrainerData(
            bytes(
                0x40, 0x00, 0x00,
                0x25, 0x03,
                0x1E, 0x00,
                0x11, 0x00,
            ),
        )!!

        assertEquals(8.05, data.speedKph!!, 1e-9)
        assertEquals(3.0, data.inclinePercent!!, 1e-9)
        assertEquals(1.7, data.rampAngleDegrees!!, 1e-9)
    }

    /** Bit 3 carries step count *and* average step rate — two fields, four bytes. */
    @Test
    fun `cross trainer step count carries two fields`() {
        // Flags 0x000808 = step count (bit 3) + heart rate (bit 11).
        val data = FtmsCodec.parseCrossTrainerData(
            bytes(
                0x08, 0x08, 0x00,
                0x25, 0x03,
                0x78, 0x00,
                0x64, 0x00,
                0x8A,
            ),
        )!!

        assertEquals(120, data.stepsPerMinute)
        assertEquals(100, data.averageStepRate)
        assertEquals(138, data.heartRateBpm)
    }

    /** A treadmill packet fed to the cross trainer parser must not be silently believed. */
    @Test
    fun `a payload too short for a 24-bit header is rejected`() {
        assertNull(FtmsCodec.parseCrossTrainerData(bytes(0x00, 0x00)))
    }

    // ---- rower (0x2AD1) ---------------------------------------------------------------------

    /**
     * The rower's inverted bit 0 gates a **pair**: stroke rate (1 byte, half-strokes) and stroke
     * count (2 bytes). Three bytes, where the treadmill's equivalent consumes two.
     *
     * Flags `0x0004` = total distance (bit 2), plus the stroke pair since More Data is clear.
     * Stroke rate 24 /min is carried as 48 half-strokes → `30`.
     */
    @Test
    fun `rower data decodes the stroke pair and distance`() {
        val data = FtmsCodec.parseRowerData(
            bytes(
                0x04, 0x00,
                0x30,
                0x2C, 0x01,
                0xE8, 0x03, 0x00,
            ),
        )!!

        assertEquals(24.0, data.strokeRatePerMin!!, 1e-9)
        assertEquals(300, data.strokeCount)
        assertEquals(1000, data.totalDistanceMetres)
        // A rower reports no speed. It must stay unknown rather than becoming zero.
        assertNull(data.speedKph)
    }

    @Test
    fun `rower pace decodes as seconds per 500 metres`() {
        // Flags 0x0008 = instantaneous pace (bit 3). Bit 0 set, so no stroke pair.
        val data = FtmsCodec.parseRowerData(bytes(0x09, 0x00, 0x78, 0x00))!!

        assertEquals(120, data.paceSecondsPer500m)
        assertNull(data.strokeCount)
    }

    // ---- one parser per characteristic -------------------------------------------------------

    /**
     * The parser is chosen by characteristic, never guessed from the payload.
     *
     * The same eight bytes decode to entirely different numbers under each parser, because bit 2 is
     * cadence on a bike and total distance on a treadmill. Nothing throws — the treadmill parser
     * happily reports **16,384 km** of distance from a bike's cadence and power bytes. That silent
     * plausibility is why the transport binds a parser to the UUID it subscribed to rather than
     * sniffing the payload.
     */
    @Test
    fun `parseMachineData dispatches on the machine type`() {
        val frame = bytes(0x44, 0x00, 0x25, 0x03, 0xB4, 0x00, 0xFA, 0x00)

        val bike = FtmsCodec.parseMachineData(FtmsCodec.MachineType.INDOOR_BIKE, frame)!!
        assertEquals(90.0, bike.cadenceRpm!!, 1e-9)
        assertEquals(250, bike.powerWatts)

        // Same bytes, treadmill parser: bit 2 is total distance there, and the uint24 swallows the
        // power bytes as well. A wrong answer, not a failure.
        val treadmill = FtmsCodec.parseMachineData(FtmsCodec.MachineType.TREADMILL, frame)!!
        assertNull(treadmill.cadenceRpm)
        assertEquals(16_384_180, treadmill.totalDistanceMetres)
    }
}
