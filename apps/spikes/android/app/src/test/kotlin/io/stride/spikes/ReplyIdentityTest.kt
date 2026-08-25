package io.stride.spikes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a buffer is actually an answer to the command that was just sent.
 *
 * Stride read the status straight off byte 3 and believed it. On a packetised pipe shared with a
 * console that also talks unprompted, that is how one command's answer gets attributed to another —
 * and the symptom is not a crash, it is a *plausible* refusal. "The console says CMD_NOT_SUPPORTED"
 * and "we read three bytes that were never an answer" are the same log line.
 *
 * GlassOS validates every frame before decoding it (`ai/b.a`): the sentinel, the address, the
 * declared length, the echoed command id, and the checksum. This is that check.
 */
class ReplyIdentityTest {

    private val command = FitProCodec.Command.DEVICE_INFO
    private val address = FitProCodec.ADDRESS_MAIN

    /** Builds a well-formed reply: address, length, command echo, status, payload, checksum. */
    private fun reply(
        address: Int = this.address,
        command: FitProCodec.Command = this.command,
        status: Int = FitProCodec.Status.DONE.value,
        payload: ByteArray = byteArrayOf(1, 2, 3),
        declaredLength: Int? = null,
    ): ByteArray {
        val total = FitProCodec.FRAME_OVERHEAD + 1 + payload.size
        val out = ByteArray(total)
        out[0] = address.toByte()
        out[1] = (declaredLength ?: total).toByte()
        out[2] = command.value.toByte()
        out[3] = status.toByte()
        payload.copyInto(out, 4)
        out[total - 1] = FitProCodec.checksum(out, total - 1)
        return out
    }

    @Test
    fun `a well-formed answer to the command we sent is accepted`() {
        assertTrue(FitProCodec.replyMatches(reply(), command, address))
    }

    /** The case that matters: a real frame, for a different command. */
    @Test
    fun `an answer to a different command is not ours`() {
        val other = reply(command = FitProCodec.Command.READ_WRITE_DATA)
        assertFalse(FitProCodec.replyMatches(other, command, address))
        // And it would otherwise have decoded as a perfectly ordinary status.
        assertTrue(FitProCodec.statusOf(other) != null)
    }

    /** A frame from another device on the bus is not ours either. */
    @Test
    fun `an answer from a different address is not ours`() {
        assertFalse(
            FitProCodec.replyMatches(
                reply(address = FitProCodec.ADDRESS_TREADMILL),
                command,
                address,
            ),
        )
    }

    /**
     * An untouched USB buffer reads as all-`0xFF`, and must never be mistaken for a refusal.
     *
     * Byte 3 is deliberately not part of the sentinel — GlassOS leaves it a wildcard, because the
     * board may drop a status into a buffer it has otherwise not written.
     */
    @Test
    fun `an unwritten buffer is not an answer`() {
        val blank = ByteArray(64) { 0xFF.toByte() }
        assertFalse(FitProCodec.replyMatches(blank, command, address))
        blank[3] = FitProCodec.Status.CMD_NOT_SUPPORTED.value.toByte()
        assertFalse("a status in an empty buffer is still not an answer",
            FitProCodec.replyMatches(blank, command, address))
    }

    @Test
    fun `a corrupted checksum is not an answer`() {
        val corrupted = reply()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()
        assertFalse(FitProCodec.replyMatches(corrupted, command, address))
    }

    /** A length byte that cannot be honest, in both directions. */
    @Test
    fun `an impossible declared length is not an answer`() {
        assertFalse(
            "shorter than the header",
            FitProCodec.replyMatches(reply(declaredLength = 2), command, address),
        )
        assertFalse(
            "longer than the protocol allows",
            FitProCodec.replyMatches(
                reply(declaredLength = FitProCodec.MAX_FRAME_LENGTH + 1),
                command,
                address,
            ),
        )
        assertFalse(
            "longer than the bytes we actually read",
            FitProCodec.replyMatches(reply(declaredLength = 60), command, address),
        )
    }

    @Test
    fun `a truncated read is not an answer`() {
        assertFalse(FitProCodec.replyMatches(byteArrayOf(2, 5), command, address))
    }
}
