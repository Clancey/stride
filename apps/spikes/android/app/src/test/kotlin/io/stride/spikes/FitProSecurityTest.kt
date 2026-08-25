package io.stride.spikes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `VERIFY_SECURITY` handshake and the request bodies the handshake commands carry.
 *
 * The expected hashes are not transcriptions of [FitProCodec.calculateSecurityHash]. They were
 * produced by a separate implementation of `EquipmentUtil.CalculateSecurityHash` written directly
 * from the vendor source, emulating C#'s 32-bit signed arithmetic. Checking Kotlin against Kotlin
 * would only prove the code agrees with itself; these vectors can disagree with it, which is the
 * point — the mistakes this algorithm invites (logical instead of arithmetic shifts, a running
 * value instead of the seed, an overflow that does not wrap the same way) all still produce a
 * plausible 32 bytes.
 */
class FitProSecurityTest {

    private fun hash(serial: Int, part: Int, model: Int) =
        FitProCodec.calculateSecurityHash(serial, part, model)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `hash with no serial bits set folds in the model number`() {
        assertArrayEquals(
            bytes(
                0x01, 0x00, 0x05, 0x08, 0x11, 0x18, 0x2D, 0x30,
                0x41, 0x50, 0x65, 0x88, 0x91, 0xB8, 0xDD, 0xE0,
                0x01, 0x20, 0x45, 0x68, 0xB1, 0xD8, 0xED, 0x30,
                0x41, 0x90, 0xA5, 0xE8, 0x31, 0x78, 0xBD, 0xC0,
            ),
            hash(serial = 0, part = 0, model = 0),
        )
    }

    @Test
    fun `hash with every serial bit set folds in the part number`() {
        assertArrayEquals(
            bytes(
                0x35, 0x18, 0x8E, 0x42, 0x26, 0x97, 0x4F, 0x2C,
                0x1B, 0x83, 0x4F, 0xAE, 0xDC, 0x66, 0x3B, 0x8A,
                0x25, 0x08, 0x9E, 0x52, 0x36, 0x87, 0x5F, 0x3C,
                0x0B, 0x13, 0x1F, 0x1E, 0x1C, 0x1E, 0x1F, 0x20,
            ),
            hash(serial = -1, part = 0x1234ABCD, model = 7),
        )
    }

    @Test
    fun `hash alternates between both branches`() {
        assertArrayEquals(
            bytes(
                0xAA, 0x57, 0x29, 0x11, 0x2B, 0x0C, 0xEF, 0xC0,
                0x09, 0x8A, 0xCB, 0xEC, 0x4B, 0x3C, 0x2F, 0x00,
                0xBA, 0x47, 0x39, 0x01, 0xCB, 0xCC, 0xCF, 0xC0,
                0x19, 0x1A, 0x1B, 0x1C, 0xEB, 0x1C, 0x0F, 0x00,
            ),
            hash(serial = 0x0F0F0F0F, part = 0x00ABCDEF, model = 1234),
        )
    }

    /** The case that catches `ushr` used where C# sign-extends. */
    @Test
    fun `hash sign-extends a negative part number`() {
        assertArrayEquals(
            bytes(
                0x62, 0xE7, 0x2C, 0xBD, 0xD9, 0x76, 0xB0, 0x58,
                0xCA, 0xFC, 0xA4, 0xF1, 0xF3, 0x2E, 0xF0, 0x30,
                0xB2, 0xF7, 0xBC, 0xAD, 0xC9, 0x46, 0xA0, 0x68,
                0x1A, 0xEC, 0x34, 0xE1, 0xE3, 0x1E, 0xE0, 0x60,
            ),
            hash(serial = 0x5A5A5A5A, part = -0x12345678, model = 99),
        )
    }

    @Test
    fun `hash matches plausible console numbers`() {
        assertArrayEquals(
            bytes(
                0x02, 0xAC, 0x03, 0x60, 0x05, 0x24, 0x03, 0xE0,
                0x09, 0xBC, 0x8B, 0x4C, 0x77, 0x64, 0x47, 0xB4,
                0x12, 0x13, 0x2B, 0x14, 0x15, 0x24, 0x17, 0x20,
                0x19, 0x1A, 0x1B, 0x40, 0x77, 0x64, 0x93, 0x80,
            ),
            hash(serial = 123456789, part = 241234, model = 1750),
        )
    }

    @Test
    fun `hash is always the declared length`() {
        assertEquals(FitProCodec.SECURITY_HASH_LENGTH, hash(1, 2, 3).size)
    }

    @Test
    fun `verify security frame carries the hash then the key`() {
        val securityHash = hash(serial = 123456789, part = 241234, model = 1750)
        val frame = FitProCodec.verifySecurityFrame(
            address = 2,
            securityHash = securityHash,
            masterLibraryVersion = 83,
        )

        assertEquals(FitProCodec.SECURITY_CONTENT_LENGTH + FitProCodec.FRAME_OVERHEAD, frame.size)
        assertEquals(2, frame[0].toInt() and 0xFF)
        assertEquals(frame.size, frame[1].toInt() and 0xFF)
        assertEquals(
            FitProCodec.Command.VERIFY_SECURITY,
            FitProCodec.Command.fromValue(frame[2].toInt() and 0xFF),
        )
        assertArrayEquals(securityHash, frame.copyOfRange(3, 3 + FitProCodec.SECURITY_HASH_LENGTH))

        // 8 * 83 = 664 = 0x0298, little-endian.
        val keyAt = 3 + FitProCodec.SECURITY_HASH_LENGTH
        assertArrayEquals(bytes(0x98, 0x02, 0x00, 0x00), frame.copyOfRange(keyAt, keyAt + 4))
        assertEquals(FitProCodec.checksum(frame, frame.size - 1), frame[frame.size - 1])
    }

    /** The frame has to survive a 64-byte endpoint in one piece. */
    @Test
    fun `verify security frame fits a single packet`() {
        val frame = FitProCodec.verifySecurityFrame(1, hash(1, 2, 3), 255)
        assertTrue("frame was ${frame.size} bytes", frame.size <= 64)
    }

    @Test
    fun `verify security frame rejects a wrong-sized hash`() {
        val tooShort = ByteArray(FitProCodec.SECURITY_HASH_LENGTH - 1)
        val threw = runCatching { FitProCodec.verifySecurityFrame(2, tooShort, 83) }.isFailure
        assertTrue("a short hash must not encode", threw)
    }

    @Test
    fun `command frame refuses to fabricate a security body`() {
        val threw = runCatching {
            FitProCodec.commandFrame(FitProCodec.Command.VERIFY_SECURITY, 2)
        }.isFailure
        assertTrue("VERIFY_SECURITY needs a computed hash", threw)
    }

    // ---- request bodies -------------------------------------------------------------------------

    /**
     * The regression this whole change turns on: these two declare a two-byte body, and sending
     * them empty is what made the X22i fall silent after `DEVICE_INFO`.
     */
    @Test
    fun `system and version info carry a two-byte body`() {
        for (command in listOf(FitProCodec.Command.SYSTEM_INFO, FitProCodec.Command.VERSION_INFO)) {
            val frame = FitProCodec.commandFrame(command, address = 2)
            assertEquals("$command length", 2, command.requestContentLength)
            assertEquals("$command frame", FitProCodec.FRAME_OVERHEAD + 2, frame.size)
            assertEquals("$command declared length", frame.size, frame[1].toInt() and 0xFF)
            // Both flags mean "don't also send me the name".
            assertEquals("$command flag 0", 0, frame[3].toInt())
            assertEquals("$command flag 1", 0, frame[4].toInt())
            assertEquals(
                "$command checksum",
                FitProCodec.checksum(frame, frame.size - 1),
                frame[frame.size - 1],
            )
        }
    }

    @Test
    fun `the other handshake commands stay bodiless`() {
        for (command in listOf(
            FitProCodec.Command.DEVICE_INFO,
            FitProCodec.Command.SERIAL_NUMBER,
            FitProCodec.Command.SUPPORTED_COMMANDS,
            FitProCodec.Command.SUPPORTED_DEVICES,
        )) {
            assertEquals("$command length", 0, command.requestContentLength)
            assertEquals(
                "$command frame",
                FitProCodec.FRAME_OVERHEAD,
                FitProCodec.commandFrame(command, address = 2).size,
            )
        }
    }

    // ---- replies --------------------------------------------------------------------------------

    @Test
    fun `system info reads the model and part numbers`() {
        // header(4), configSize(2), configuration(1), model(4 LE), partNumber(4 LE)
        val reply = bytes(
            0x02, 0x0F, 0x82, 0x02,
            0x10, 0x00,
            0x01,
            0xD6, 0x06, 0x00, 0x00,
            0x52, 0xAE, 0x03, 0x00,
        )
        val info = FitProCodec.parseSystemInfo(reply)
        assertNotNull(info)
        assertEquals(1750, info!!.model)
        assertEquals(241234, info.partNumber)
    }

    /**
     * iFit rewrites one console's part number while parsing, so its `Unlock()` hashes the corrected
     * value. Hashing the raw one would fail the handshake on exactly that model.
     */
    @Test
    fun `system info applies the vendor part-number correction`() {
        fun reply(model: Int, part: Int) = bytes(0x02, 0x0F, 0x82, 0x02, 0x10, 0x00, 0x01) +
            FitProCodec.intToLe(model, 4) + FitProCodec.intToLe(part, 4)

        assertEquals(374677, FitProCodec.parseSystemInfo(reply(39915, 370357))!!.partNumber)
        // Only when both match: either number alone is left alone.
        assertEquals(370357, FitProCodec.parseSystemInfo(reply(39916, 370357))!!.partNumber)
        assertEquals(370358, FitProCodec.parseSystemInfo(reply(39915, 370358))!!.partNumber)
    }

    @Test
    fun `system info rejects a truncated reply`() {        assertNull(FitProCodec.parseSystemInfo(bytes(0x02, 0x0A, 0x82, 0x02, 0x10, 0x00)))
    }

    @Test
    fun `version info reads a one-byte master library version`() {
        // The build number that follows is two bytes; reading it as part of the version is the
        // obvious way to get this wrong and would silently produce the wrong secret key.
        val reply = bytes(0x02, 0x0A, 0x84, 0x02, 0x53, 0x11, 0x22, 0x00, 0x00, 0x00)
        assertEquals(83, FitProCodec.parseMasterLibraryVersion(reply))
    }

    @Test
    fun `version info rejects a truncated reply`() {
        assertNull(FitProCodec.parseMasterLibraryVersion(bytes(0x02, 0x04, 0x84, 0x02)))
    }

    @Test
    fun `security reply is unlocked only when the status says done`() {
        val done = FitProCodec.parseSecurityInfo(bytes(0x02, 0x06, 0x90, 0x02, 0x2A, 0x00))
        assertNotNull(done)
        assertTrue(done!!.unlocked)
        assertEquals(0x2A, done.unlockedKey)
        assertEquals(FitProCodec.Status.DONE, done.status)

        val blocked = FitProCodec.parseSecurityInfo(bytes(0x02, 0x06, 0x90, 0x08, 0x00, 0x00))
        assertNotNull(blocked)
        assertEquals(false, blocked!!.unlocked)
        assertEquals(FitProCodec.Status.SECURITY_BLOCK, blocked.status)
    }

    @Test
    fun `security reply rejects a truncated reply`() {
        assertNull(FitProCodec.parseSecurityInfo(bytes(0x02, 0x04, 0x90, 0x02)))
    }

    // ---- the threshold --------------------------------------------------------------------------

    @Test
    fun `security is demanded strictly above the threshold`() {
        fun info(softwareVersion: Int) = FitProCodec.DeviceInfo(
            address = 2,
            softwareVersion = softwareVersion,
            hardwareVersion = 1,
            serialNumber = 1,
            brand = FitProCodec.Brand.NORDIC_TRACK,
            supportedFieldIds = emptySet(),
        )
        assertEquals(false, info(FitProCodec.SECURITY_REQUIRED_ABOVE).requiresSecurity)
        assertTrue(info(FitProCodec.SECURITY_REQUIRED_ABOVE + 1).requiresSecurity)
        // The version the X22i in the field reports.
        assertTrue(info(83).requiresSecurity)
    }
}
