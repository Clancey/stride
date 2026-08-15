package io.stride.spikes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level tests for the FitPro wire format.
 *
 * Every expected frame below is computed **by hand from the decompiled GlassOS routines**, not
 * captured from this codec's own output. That distinction is the whole value of the file: a test
 * written by running the encoder and pasting the result proves only that the code does what it does.
 *
 * The reason this matters more here than in most codecs is that FitPro's failure mode is silent. A
 * frame with the wrong endianness, the wrong mask width or the wrong block order is still a
 * *well-formed* frame; the machine accepts it and does something other than what the rider asked.
 * There is no error to observe. So the bytes are pinned.
 */
class FitProCodecTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { (values[it] and 0xFF).toByte() }

    // ---- framing ----------------------------------------------------------------------------------

    /**
     * A pure read of `ACTUAL_KPH`, computed by hand:
     *
     *  - write block: empty, so a single `00` (`vh/f.f16666p`).
     *  - read block: field 16 → mask bytes `(16 / 8) + 1 = 3`; bit `1 shl (16 % 8)` = `01` in byte 2
     *    → `03 00 00 01`.
     *  - body = `00 03 00 00 01`, so total length = 5 + 4 = 9.
     *  - frame = `02 09 02` + body, checksum = 2+9+2+0+3+0+0+1 = 17 = `11`.
     */
    @Test
    fun readFrameMatchesHandComputedLayout() {
        val body = FitProCodec.readWriteBody(
            writes = emptyList(),
            reads = listOf(FitProCodec.Register.ACTUAL_KPH),
        )
        val frame = FitProCodec.frame(body, address = FitProCodec.ADDRESS_MAIN)
        assertEquals("02 09 02 00 03 00 00 01 11", hex(frame))
    }

    /**
     * A speed write of 6.0 kph, computed by hand:
     *
     *  - value: 6.0 × 100 = 600 = `0x0258`, little-endian → `58 02`.
     *  - write block: field 0 → 1 mask byte, bit 0 → `01 01` then the value → `01 01 58 02`.
     *  - read block: empty → `00`.
     *  - body = `01 01 58 02 00`, total = 9, checksum = 2+9+2+1+1+0x58+2+0 = 105 = `69`.
     */
    @Test
    fun writeFrameMatchesHandComputedLayout() {
        val body = FitProCodec.readWriteBody(
            writes = listOf(
                FitProCodec.writeOf(FitProCodec.Register.KPH, FitProCodec.encodeSpeed(6.0)),
            ),
            reads = emptyList(),
        )
        val frame = FitProCodec.frame(body, address = FitProCodec.ADDRESS_MAIN)
        assertEquals("02 09 02 01 01 58 02 00 69", hex(frame))
    }

    /** The write block comes first and carries values; the read block follows and does not. */
    @Test
    fun writesPrecedeReadsInTheBody() {
        val body = FitProCodec.readWriteBody(
            writes = listOf(
                FitProCodec.writeOf(FitProCodec.Register.KPH, FitProCodec.encodeSpeed(1.0)),
            ),
            reads = listOf(FitProCodec.Register.ACTUAL_KPH),
        )
        // write block `01 01 64 00`, then read block `03 00 00 01`.
        assertEquals("01 01 64 00 03 00 00 01", hex(body))
    }

    /**
     * An empty block is one zero byte, not nothing.
     *
     * This is what makes a read frame structurally unable to be mistaken for a write: the receiver
     * splits the body positionally, and a read always carries an explicit empty write block.
     */
    @Test
    fun emptyBlockIsASingleZeroByte() {
        assertArrayEquals(byteArrayOf(0), FitProCodec.registerBlock(emptyList(), null))
    }

    /** The mask spans field zero through the highest requested id, so a high field carries 13 bytes. */
    @Test
    fun maskSpansFromZeroToHighestFieldId() {
        val block = FitProCodec.registerBlock(listOf(FitProCodec.Register.FAN_STATE), null)
        // Field 98: (98 / 8) + 1 = 13 mask bytes; bit 1 shl (98 % 8) = 1 shl 2 = 0x04 in byte 12.
        assertEquals(14, block.size)
        assertEquals(13, block[0].toInt())
        assertEquals(0x04, block[13].toInt())
        for (i in 1..12) assertEquals("mask byte $i", 0, block[i].toInt())
    }

    /** Two registers in the same mask byte OR together rather than emitting two blocks. */
    @Test
    fun adjacentFieldsShareAMaskByte() {
        val block = FitProCodec.registerBlock(
            listOf(FitProCodec.Register.ACTUAL_INCLINE, FitProCodec.Register.ACTUAL_KPH),
            null,
        )
        assertEquals("03 00 00 03", hex(block))
    }

    /** Registers are emitted in ascending field-id order regardless of how the caller listed them. */
    @Test
    fun valuesFollowAscendingFieldIdOrderNotCallerOrder() {
        val body = FitProCodec.readWriteBody(
            writes = listOf(
                // Deliberately reversed: GRADE is field 1, KPH is field 0.
                FitProCodec.writeOf(FitProCodec.Register.GRADE, FitProCodec.encodeIncline(2.0)),
                FitProCodec.writeOf(FitProCodec.Register.KPH, FitProCodec.encodeSpeed(1.0)),
            ),
            reads = emptyList(),
        )
        // 1 mask byte, bits 0 and 1 set, then KPH's 100 (`64 00`) before GRADE's 200 (`C8 00`).
        assertEquals("01 03 64 00 C8 00 00", hex(body))
    }

    /** The checksum is a plain 8-bit sum that wraps, not a CRC and not an XOR. */
    @Test
    fun checksumIsSum8AndWraps() {
        assertEquals(0x11.toByte(), FitProCodec.checksum(bytes(0x02, 0x09, 0x02, 0x00, 0x03, 0x00, 0x00, 0x01), 8))
        // 0xFF + 0x02 = 0x101, truncated to 0x01.
        assertEquals(0x01.toByte(), FitProCodec.checksum(bytes(0xFF, 0x02), 2))
        // The length argument bounds the sum: the trailing byte is excluded.
        assertEquals(0xFF.toByte(), FitProCodec.checksum(bytes(0xFF, 0x02), 1))
    }

    // ---- serializers ------------------------------------------------------------------------------

    /**
     * Speed is little-endian, and this test states the consequence of getting it wrong.
     *
     * 1.0 kph encodes to 100 = `64 00`. Read big-endian, those same bytes are 0x6400 = 25600, i.e.
     * 256 kph. The machine would clamp rather than obey, but the point stands: the frame is valid
     * either way, so nothing fails loudly.
     */
    @Test
    fun speedIsLittleEndian() {
        assertEquals("64 00", hex(FitProCodec.encodeSpeed(1.0)))
        assertEquals(1.0, FitProCodec.decodeSpeed(bytes(0x64, 0x00)), 1e-9)
        assertEquals(256.0, FitProCodec.decodeSpeed(bytes(0x00, 0x64)), 1e-9)
    }

    @Test
    fun speedRoundTrips() {
        for (kph in listOf(0.0, 1.0, 6.4, 12.8, 19.31)) {
            assertEquals(kph, FitProCodec.decodeSpeed(FitProCodec.encodeSpeed(kph)), 0.011)
        }
    }

    /** Incline is two bytes and signed: a decline must not read as a steep climb. */
    @Test
    fun inclineIsSignedAndTwoBytes() {
        val encoded = FitProCodec.encodeIncline(-3.0)
        assertEquals(2, encoded.size)
        // -300 as unsigned 16-bit is 0xFED4, little-endian `D4 FE`.
        assertEquals("D4 FE", hex(encoded))
        assertEquals(-3.0, FitProCodec.decodeIncline(encoded), 1e-9)
        // Unsigned would have read this as +652.68%.
        assertEquals(12.0, FitProCodec.decodeIncline(FitProCodec.encodeIncline(12.0)), 1e-9)
    }

    @Test
    fun writeOfRefusesReadOnlyRegisters() {
        val failure = runCatching {
            FitProCodec.writeOf(FitProCodec.Register.ACTUAL_KPH, bytes(0, 0))
        }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IllegalArgumentException)
    }

    @Test
    fun writeOfRefusesWrongWidthValues() {
        val failure = runCatching {
            FitProCodec.writeOf(FitProCodec.Register.KPH, bytes(0, 0, 0, 0))
        }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IllegalArgumentException)
    }

    @Test
    fun readWriteBodyRefusesDuplicateWrites() {
        val failure = runCatching {
            FitProCodec.readWriteBody(
                writes = listOf(
                    FitProCodec.writeOf(FitProCodec.Register.KPH, FitProCodec.encodeSpeed(1.0)),
                    FitProCodec.writeOf(FitProCodec.Register.KPH, FitProCodec.encodeSpeed(9.0)),
                ),
                reads = emptyList(),
            )
        }.exceptionOrNull()
        assertTrue("two speeds in one frame must not encode", failure is IllegalArgumentException)
    }

    // ---- responses --------------------------------------------------------------------------------

    /** Values are packed contiguously from offset 4, in ascending field-id order. */
    @Test
    fun parseResponseUnpacksContiguousValues() {
        // addr, len=9, cmd, status=DONE(2), kph=100 (`64 00`), incline=250 (`FA 00`), checksum.
        // Sum: 2+9+2+2+0x64+0+0xFA+0 = 365 = 0x16D, truncated to 0x6D.
        val reply = bytes(0x02, 0x09, 0x02, 0x02, 0x64, 0x00, 0xFA, 0x00, 0x6D)
        val response = FitProCodec.parseResponse(
            reply,
            listOf(FitProCodec.Register.ACTUAL_INCLINE, FitProCodec.Register.ACTUAL_KPH),
        )
        assertNotNull(response)
        assertTrue(response!!.accepted)
        assertTrue("hand-computed checksum should validate", response.checksumValid)
        assertEquals(1.0, FitProCodec.decodeSpeed(response.value(FitProCodec.Register.ACTUAL_KPH)!!), 1e-9)
        assertEquals(2.5, FitProCodec.decodeIncline(response.value(FitProCodec.Register.ACTUAL_INCLINE)!!), 1e-9)
    }

    /**
     * A reply too short for everything asked is rejected outright rather than partially decoded.
     *
     * Partial decoding is the tempting option and the wrong one: the values are positional, so a
     * missing early value silently shifts every later one. A wrong speed is worse than no speed.
     */
    @Test
    fun parseResponseRejectsATruncatedReply() {
        // Room for one 2-byte value, but two were asked for.
        val reply = bytes(0x02, 0x07, 0x02, 0x02, 0x64, 0x00, 0x71)
        val response = FitProCodec.parseResponse(
            reply,
            listOf(FitProCodec.Register.ACTUAL_KPH, FitProCodec.Register.ACTUAL_INCLINE),
        )
        assertNull(response)
    }

    /** Values must come from inside the declared frame, never from the checksum or trailing padding. */
    @Test
    fun parseResponseIgnoresBytesBeyondTheDeclaredLength() {
        // Declares 7 bytes (one 2-byte value) but the buffer carries slack the transport left behind.
        val reply = bytes(0x02, 0x07, 0x02, 0x02, 0x64, 0x00, 0x71, 0xAA, 0xBB, 0xCC)
        val response = FitProCodec.parseResponse(
            reply,
            listOf(FitProCodec.Register.ACTUAL_KPH, FitProCodec.Register.ACTUAL_INCLINE),
        )
        assertNull("padding must not be parsed as an incline reading", response)
    }

    /** A refusal carries no values; the status is the answer. */
    @Test
    fun parseResponseReportsRefusalWithoutValues() {
        val reply = bytes(0x02, 0x05, 0x02, 0x01, 0x0A)
        val response = FitProCodec.parseResponse(reply, listOf(FitProCodec.Register.ACTUAL_KPH))
        assertNotNull(response)
        assertFalse(response!!.accepted)
        assertEquals(FitProCodec.Status.CMD_NOT_SUPPORTED, response.status)
        assertNull(response.value(FitProCodec.Register.ACTUAL_KPH))
    }

    /** An unrecognised status is treated as failure, never as success. */
    @Test
    fun unknownStatusIsNotSuccess() {
        assertEquals(FitProCodec.Status.CMD_NOT_SUPPORTED, FitProCodec.Status.fromValue(0x5A))
    }

    // ---- handshake --------------------------------------------------------------------------------

    /**
     * `DEVICE_INFO` is the machine answering "which controls do I have", so its mask decode is the
     * thing standing between a rider and an incline button that does nothing.
     */
    @Test
    fun parseDeviceInfoDecodesTheSupportedRegisterMask() {
        val reply = bytes(
            0x02, 0x11, 0x81, 0x02, // addr, len 17, DEVICE_INFO, DONE
            0x05, 0x07, // hardware 5, firmware 7
            0xE5, 0x42, 0x00, 0x00, // model 17125, little-endian
            0x04, 0x00, // brand 4 = NORDIC_TRACK
            0x03, // three mask bytes
            0x03, 0x00, 0x03, // fields 0,1 and 16,17
            0x4B, // checksum
        )
        val info = FitProCodec.parseDeviceInfo(reply)
        assertNotNull(info)
        assertEquals(5, info!!.hardwareVersion)
        assertEquals(7, info.firmwareVersion)
        assertEquals(17125, info.modelNumber)
        assertEquals(FitProCodec.Brand.NORDIC_TRACK, info.brand)
        assertTrue(info.supports(FitProCodec.Register.KPH))
        assertTrue(info.supports(FitProCodec.Register.GRADE))
        assertTrue(info.supports(FitProCodec.Register.ACTUAL_KPH))
        // Not in the mask, so the honest answer is "this machine has no controllable fan".
        assertFalse(info.supports(FitProCodec.Register.FAN_STATE))
        assertFalse(info.supports(FitProCodec.Register.FAN_SPEED))
    }

    @Test
    fun parseDeviceInfoRejectsAShortReply() {
        assertNull(FitProCodec.parseDeviceInfo(bytes(0x02, 0x06, 0x81, 0x02, 0x05, 0x07)))
    }

    /** A command with no body is still a full frame: header, no payload, checksum. */
    @Test
    fun commandFrameCarriesNoBody() {
        val frame = FitProCodec.commandFrame(FitProCodec.Command.DEVICE_INFO, FitProCodec.ADDRESS_MAIN)
        // 02 04 81, checksum = 2 + 4 + 0x81 = 0x87.
        assertEquals("02 04 81 87", hex(frame))
    }

    @Test
    fun deviceInfoCommandByteIsUnsigned() {
        assertEquals(0x81, FitProCodec.Command.DEVICE_INFO.value and 0xFF)
        assertEquals(FitProCodec.Command.DEVICE_INFO, FitProCodec.Command.fromValue(0x81))
    }

    // ---- transport shaping ------------------------------------------------------------------------

    @Test
    fun fitPro2EnvelopeWrapsWithLength() {
        val frame = bytes(0x02, 0x04, 0x81, 0x87)
        assertEquals("02 04 02 04 02 04 81 87", hex(FitProCodec.fitPro2Envelope(frame)))
    }

    @Test
    fun bleChunkingEmitsALeadThenTwentyBytePackets() {
        val payload = ByteArray(9) { (it + 1).toByte() }
        val packets = FitProCodec.chunkForBle(payload)
        assertEquals(2, packets.size)
        assertEquals("FE 02 09 02", hex(packets[0]))
        assertEquals(20, packets[1].size)
        assertEquals(0xFF.toByte(), packets[1][0])
        assertEquals(9, packets[1][1].toInt())
    }

    @Test
    fun bleChunkingSplitsPastEighteenBytes() {
        val payload = ByteArray(20) { it.toByte() }
        val packets = FitProCodec.chunkForBle(payload)
        assertEquals(3, packets.size)
        assertEquals("FE 02 14 03", hex(packets[0]))
        assertEquals(0x00.toByte(), packets[1][0])
        assertEquals(18, packets[1][1].toInt())
        assertEquals(0xFF.toByte(), packets[2][0])
        assertEquals(2, packets[2][1].toInt())
    }

    // ---- value translation ------------------------------------------------------------------------

    /**
     * FitPro's workout numbering is not GlassOS's, and the console-state strings are matched by name
     * elsewhere. Both are silent-failure traps: a wrong mapping produces a plausible state, and
     * `beltMayBeMoving` is built on it.
     */
    @Test
    fun workoutModeTranslatesToGlassOsNamesNotFitProNumbers() {
        assertEquals("WORKOUT", FitProValues.consoleStateName(FitProCodec.WorkoutMode.RUNNING))
        assertEquals("PAUSED", FitProValues.consoleStateName(FitProCodec.WorkoutMode.PAUSE))
        assertEquals("IDLE", FitProValues.consoleStateName(FitProCodec.WorkoutMode.IDLE))
        assertEquals(
            "WORKOUT_RESULTS",
            FitProValues.consoleStateName(FitProCodec.WorkoutMode.RESULTS),
        )
    }

    /** The names above must be ones GlassOS's own belt-motion rule recognises. */
    @Test
    fun translatedNamesFeedTheBeltMotionRule() {
        assertEquals(
            true,
            GlassOsClient.ConsoleState.beltMayBeMoving(
                FitProValues.consoleStateName(FitProCodec.WorkoutMode.RUNNING),
            ),
        )
        assertEquals(
            false,
            GlassOsClient.ConsoleState.beltMayBeMoving(
                FitProValues.consoleStateName(FitProCodec.WorkoutMode.IDLE),
            ),
        )
    }

    /** FitPro RUNNING is 2; GlassOS RUNNING is 3. Conflating them is the trap this pins shut. */
    @Test
    fun fitProAndGlassOsWorkoutNumbersDiffer()  {
        assertEquals(2, FitProCodec.WorkoutMode.RUNNING.value)
        assertEquals(
            GlassOsCommands.WORKOUT_RUNNING,
            FitProValues.glassOsWorkoutState(FitProCodec.WorkoutMode.RUNNING),
        )
        assertTrue(FitProCodec.WorkoutMode.RUNNING.value != GlassOsCommands.WORKOUT_RUNNING)
    }

    /** Auto is not a level, so it must not be drawn as one. */
    @Test
    fun autoFanReportsNoLevelRatherThanZero() {
        assertNull(FitProValues.fanLevel(FitProCodec.encodeFanState(FitProCodec.FanState.AUTO)))
        assertEquals(0, FitProValues.fanLevel(FitProCodec.encodeFanState(FitProCodec.FanState.OFF)))
        assertEquals(3, FitProValues.fanLevel(FitProCodec.encodeFanState(FitProCodec.FanState.HIGH)))
    }

    @Test
    fun speedUnitsRoundTripThroughMph() {
        assertEquals(6.0, FitProValues.kphToMph(FitProValues.mphToKph(6.0)), 1e-9)
        // A mile is 1.609344 km, so 1 mph must not silently become 1 kph.
        assertEquals(1.609344, FitProValues.mphToKph(1.0), 1e-9)
    }

    // ---- BLE reassembly -------------------------------------------------------------------------

    /**
     * The exact inverse of the verified chunker, checked against a frame that needs two packets.
     *
     * Round-tripping through [FitProCodec.chunkForBle] is the point: if either side drifts, the two
     * stop agreeing, and this catches it without needing a console.
     */
    @Test
    fun `reassembles a chunked reply`() {
        val frame = ByteArray(22) { (it + 1).toByte() }
        val payload = FitProCodec.fitPro2Envelope(frame)
        val packets = FitProCodec.chunkForBle(payload)
        assertEquals("22-byte frame + 4-byte envelope needs a lead and two packets", 3, packets.size)

        var got: ByteArray? = null
        val assembler = FitProCodec.BleReassembler { got = it }
        packets.forEach { assembler.accept(it) }

        assertArrayEquals("envelope should be stripped and the frame restored", frame, got)
    }

    /** A single-packet reply is the common case: telemetry frames are short. */
    @Test
    fun `reassembles a single packet reply`() {
        val frame = byteArrayOf(0x02, 0x07, 0x02, 0x00, 0x10, 0x27, 0x42)
        val packets = FitProCodec.chunkForBle(FitProCodec.fitPro2Envelope(frame))
        var got: ByteArray? = null
        val assembler = FitProCodec.BleReassembler { got = it }
        packets.forEach { assembler.accept(it) }
        assertArrayEquals(frame, got)
    }

    /**
     * Padding must not become data.
     *
     * Packets are zero-padded to 20 bytes and only `[1]` says how much is real. Appending the
     * padding would extend the frame past its declared length and fail the parser's exact-fit check
     * — or worse, shift the checksum.
     */
    @Test
    fun `ignores packet padding`() {
        val frame = byteArrayOf(0x02, 0x05, 0x02, 0x00, 0x09)
        val packets = FitProCodec.chunkForBle(FitProCodec.fitPro2Envelope(frame))
        val data = packets[1]
        assertEquals("data packets are padded out to a full ATT payload", 20, data.size)
        assertEquals("only the envelope plus frame is real", 9, data[1].toInt())

        var got: ByteArray? = null
        FitProCodec.BleReassembler { got = it }.also { packets.forEach(it::accept) }
        assertArrayEquals(frame, got)
    }

    /** Fragments before a lead packet are noise from an abandoned exchange, not the start of one. */
    @Test
    fun `drops fragments arriving without a lead`() {
        var calls = 0
        val assembler = FitProCodec.BleReassembler { calls++ }
        assembler.accept(byteArrayOf(0xFF.toByte(), 0x03, 1, 2, 3))
        assertEquals("a tail with no head is not a frame", 0, calls)
    }

    /** A retry must replace the abandoned attempt rather than being appended to it. */
    @Test
    fun `a new lead discards a partial frame`() {
        val frame = byteArrayOf(0x02, 0x05, 0x02, 0x00, 0x09)
        val packets = FitProCodec.chunkForBle(FitProCodec.fitPro2Envelope(frame))

        var got: ByteArray? = null
        var calls = 0
        val assembler = FitProCodec.BleReassembler { got = it; calls++ }
        assembler.accept(byteArrayOf(0xFE.toByte(), 0x02, 40, 3))
        assembler.accept(ByteArray(20).also { it[0] = 0; it[1] = 18 })
        packets.forEach { assembler.accept(it) }

        assertEquals("only the completed retry should surface", 1, calls)
        assertArrayEquals("the abandoned fragment must not be spliced on", frame, got)
    }

    /** Truncation is silence, not a short frame: a lost fragment must never surface as an answer. */
    @Test
    fun `withholds an incomplete frame`() {
        val frame = ByteArray(22) { (it + 1).toByte() }
        val packets = FitProCodec.chunkForBle(FitProCodec.fitPro2Envelope(frame))
        var calls = 0
        val assembler = FitProCodec.BleReassembler { calls++ }
        assembler.accept(packets[0])
        assembler.accept(packets[1])
        assertEquals("one of two data packets is not a frame", 0, calls)
    }

    /**
     * A reply that is not enveloped passes through whole.
     *
     * Blindly removing four bytes would eat address, length and command, and the parser would then
     * read the body as a header — a wrong answer rather than no answer.
     */
    @Test
    fun `leaves an unenveloped reply intact`() {
        val bare = byteArrayOf(0x02, 0x06, 0x02, 0x00, 0x01, 0x0B)
        assertArrayEquals(bare, FitProCodec.stripFitPro2Envelope(bare))
    }

    /** A declared envelope length that overruns the buffer is corruption; do not trust it. */
    @Test
    fun `leaves a mis-declared envelope intact`() {
        val lying = byteArrayOf(0x02, 0x04, 0x02, 0x40, 0x01, 0x02)
        assertArrayEquals(lying, FitProCodec.stripFitPro2Envelope(lying))
    }

    /** The reassembled frame must be something the parser actually accepts, end to end. */
    @Test
    fun `a reassembled reply parses`() {
        val reply = byteArrayOf(0x02, 0x07, 0x02, 0x02, 0x10, 0x27, 0x00)
        val checksum = reply.take(6).fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
        reply[6] = checksum.toByte()

        var got: ByteArray? = null
        val packets = FitProCodec.chunkForBle(FitProCodec.fitPro2Envelope(reply))
        FitProCodec.BleReassembler { got = it }.also { packets.forEach(it::accept) }

        val parsed = FitProCodec.parseResponse(got!!, listOf(FitProCodec.Register.ACTUAL_KPH))!!
        assertTrue("the round trip must not disturb the checksum", parsed.checksumValid)
        val raw = parsed.values[FitProCodec.Register.ACTUAL_KPH]!!
        assertEquals("100.00 kph should survive the chunk round trip", 100.0, FitProCodec.decodeSpeed(raw), 0.001)
    }
}
