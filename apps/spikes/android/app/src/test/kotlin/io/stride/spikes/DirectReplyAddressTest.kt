package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A console whose replies do not carry the address they were asked at.
 *
 * The bug these exist for: `parseResponse` rejects a reply whose address byte differs from the one
 * the request was sent to. Every GlassOS-era console echoes the address it was asked, so that check
 * never fired — but a NordicTrack X22i (FitPro1) stamps its replies with its own bus address (5)
 * whatever they answer. Requests still go to MAIN (2), so every read and write after the handshake
 * was thrown away as malformed and the link looped in reconnect forever, looking exactly like a
 * console that was not there.
 *
 * The existing session-level fake ([DirectWriteSequenceTest]'s `FakeConsole`) cannot catch this: it
 * hardcodes `ADDRESS_MAIN` into its replies, and its sessions never run `connect()` at all, so the
 * `replyAddress ?: address` fallback is the only branch it ever takes. Hence a fake of its own.
 */
class DirectReplyAddressTest {

    /** What the X22i stamps its replies with when asked at [FitProCodec.ADDRESS_MAIN]. */
    private val consoleAddress = 5

    /**
     * A console that answers frames addressed to MAIN but signs its replies with [replyFrom].
     *
     * Deliberately answers only `DEVICE_INFO` and `READ_WRITE_DATA`: the rest of the opening
     * exchange is optional and `connect` tolerates its silence, so leaving it unanswered keeps the
     * fake to the two commands these tests are about.
     */
    private class NonEchoingConsole(var replyFrom: Int) : FitProTransport {
        override val name = "fake x22i"
        override val connected = true
        override val variant = FitProCodec.Variant.FITPRO1

        /** The address byte of every frame this console has been sent, in order. */
        val addressesAsked = mutableListOf<Int>()

        /** A plausible treadmill: 0.5–12 mph, 0–15% grade, currently stopped and flat. */
        val readValues = mutableMapOf(
            FitProCodec.Register.ACTUAL_KPH to 0,
            FitProCodec.Register.ACTUAL_INCLINE to 0,
            FitProCodec.Register.MIN_KPH to 80,
            FitProCodec.Register.MAX_KPH to 1930,
            FitProCodec.Register.MIN_GRADE to 0,
            FitProCodec.Register.MAX_GRADE to 1500,
        )

        override fun exchange(frame: ByteArray, command: FitProCodec.Command): ByteArray? {
            addressesAsked += frame[0].toInt() and 0xFF
            return when (command) {
                FitProCodec.Command.DEVICE_INFO -> deviceInfo()
                FitProCodec.Command.READ_WRITE_DATA -> readWrite(frame)
                else -> null
            }
        }

        /** A `DEVICE_INFO` reply claiming every register this codec models. */
        private fun deviceInfo(): ByteArray {
            val fields = FitProCodec.Register.entries.map { it.fieldId }
            val maskCount = fields.max() / 8 + 1
            // Layout after the header: software, hardware, serial (4), manufacturer (2), mask
            // count, mask — then the checksum.
            val total = 13 + maskCount + 1
            val out = ByteArray(total)
            out[0] = replyFrom.toByte()
            out[1] = total.toByte()
            out[2] = FitProCodec.Command.DEVICE_INFO.value.toByte()
            out[3] = FitProCodec.Status.DONE.value.toByte()
            // At the threshold, not above it, so `connect` skips the security handshake: these
            // tests are about addressing and an unlock exchange would only add a second fake.
            out[4] = FitProCodec.SECURITY_REQUIRED_ABOVE.toByte()
            out[5] = 1
            out[12] = maskCount.toByte()
            for (id in fields) {
                val at = 13 + id / 8
                out[at] = (out[at].toInt() or (1 shl (id % 8))).toByte()
            }
            out[total - 1] = FitProCodec.checksum(out, total - 1)
            return out
        }

        /** Answers a register frame by decoding its own read mask, as a real console would. */
        private fun readWrite(frame: ByteArray): ByteArray {
            val body = frame.copyOfRange(3, frame.size - 1)
            var i = 0
            val writeMaskLen = body[i].toInt() and 0xFF
            i++
            for (register in fieldsIn(body, i, writeMaskLen)) i += register.width
            i += writeMaskLen
            val readMaskLen = body[i].toInt() and 0xFF
            i++

            val values = ArrayList<Byte>()
            for (register in fieldsIn(body, i, readMaskLen)) {
                val v = readValues[register] ?: 0
                for (b in 0 until register.width) values += ((v shr (8 * b)) and 0xFF).toByte()
            }

            val total = FitProCodec.FRAME_OVERHEAD + 1 + values.size
            val out = ByteArray(total)
            out[0] = replyFrom.toByte()
            out[1] = total.toByte()
            out[2] = FitProCodec.Command.READ_WRITE_DATA.value.toByte()
            out[3] = FitProCodec.Status.DONE.value.toByte()
            values.toByteArray().copyInto(out, 4)
            out[total - 1] = FitProCodec.checksum(out, total - 1)
            return out
        }

        private fun fieldsIn(body: ByteArray, from: Int, len: Int): List<FitProCodec.Register> {
            val ids = mutableListOf<Int>()
            for (b in 0 until len) {
                val mask = body[from + b].toInt() and 0xFF
                for (bit in 0 until 8) if (mask and (1 shl bit) != 0) ids += b * 8 + bit
            }
            return ids.sorted().mapNotNull { id -> FitProCodec.Register.entries.find { it.fieldId == id } }
        }

        override fun close() = Unit
    }

    private fun connected(): Pair<DirectMachineSession, NonEchoingConsole> {
        val wire = NonEchoingConsole(consoleAddress)
        val session = DirectMachineSession(wire)
        assertNotNull("the handshake must survive a console that doesn't echo", session.connect().deviceInfo)
        return session to wire
    }

    /**
     * The handshake learns where replies come from without moving where frames go.
     *
     * Both halves matter. Sending to 5 would be talking to a device iFit never talks to; validating
     * against 2 is the bug.
     */
    @Test
    fun `the handshake learns the reply address without redirecting outgoing frames`() {
        val (session, wire) = connected()
        assertEquals("the reply's own address must be remembered", consoleAddress, session.replyAddress)
        assertEquals("frames still go to MAIN", FitProCodec.ADDRESS_MAIN, session.address)
        assertTrue(
            "every frame must be addressed to MAIN, whatever the replies say",
            wire.addressesAsked.isNotEmpty() && wire.addressesAsked.all { it == FitProCodec.ADDRESS_MAIN },
        )
    }

    /**
     * The regression itself: a read whose reply is signed by the console rather than echoed back.
     *
     * Before `replyAddress`, `parseResponse` returned null here and the caller reported a machine
     * that was not answering.
     */
    @Test
    fun `a read is accepted from the address the console replies from`() {
        val (session, _) = connected()
        val response = session.exchange(reads = listOf(FitProCodec.Register.ACTUAL_KPH))
        assertNotNull("a reply from the console's own address is that console answering", response)
        assertEquals(FitProCodec.Status.DONE, response?.status)
    }

    /** Learning one address is not the same as accepting any address. */
    @Test
    fun `a reply from a third address is still rejected`() {
        val (session, wire) = connected()
        wire.replyFrom = consoleAddress + 1
        assertNull(
            "only the console we handshook with may answer for it",
            session.exchange(reads = listOf(FitProCodec.Register.ACTUAL_KPH)),
        )
    }

    /**
     * A discarded handshake takes the learned address with it.
     *
     * `invalidateHandshake` resets the outgoing address to MAIN, and the poll thread keeps calling
     * `exchange` while it does — so leaving `replyAddress` behind would validate a new console's
     * replies against the old one's address.
     */
    @Test
    fun `invalidating the handshake forgets the reply address`() {
        val (session, _) = connected()
        session.invalidateHandshake()
        assertNull("a discarded handshake must not leave its address behind", session.replyAddress)
    }

    /** And so does closing the session. */
    @Test
    fun `closing the session forgets the reply address`() {
        val (session, _) = connected()
        session.close()
        assertNull(session.replyAddress)
    }
}
