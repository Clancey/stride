package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the console handshake Stride was missing entirely.
 *
 * The bug these exist for: after a console reboot, Start flipped the UI to "Pause workout" and the
 * belt never moved. GlassOS answered reads the whole time, so the link looked healthy, but it had
 * never been told to attach the machine — `ConsoleService/Connect` was a call Stride simply did not
 * make. It worked before only because the console's own iFit app connects when it launches, and a
 * console that boots straight into Stride never gets that.
 *
 * The bytes below are real replies captured from the machine over ADB during the diagnosis.
 */
class ConsoleConnectTest {

    /** Strip the 5-byte gRPC length prefix the way the transport does before parsing. */
    private fun fields(vararg bytes: Int): GlassOsWire.Fields =
        GlassOsWire.parse(bytes.map { it.toByte() }.toByteArray())

    @Test
    fun `a connected console answers with its state`() {
        // Captured: 10 04 = field 2 (consoleState), value 4 = PAUSED.
        assertEquals(4, interpretConnectionResult(fields(0x10, 0x04)))
    }

    @Test
    fun `an empty reply means disconnected rather than no answer`() {
        // DISCONNECTED is 0 and proto3 omits zero, so a console with no machine attached answers
        // Connect with an empty message. Reading that as "no answer" would hide the one state the
        // retry exists for.
        assertEquals(
            GlassOsClient.ConsoleState.DISCONNECTED,
            interpretConnectionResult(fields()),
        )
    }

    @Test
    fun `an error reply is not a state`() {
        // Field 1 present is the IFitError arm of the oneof. Null, so a caller retries rather than
        // believing the console told it something.
        assertNull(interpretConnectionResult(fields(0x0a, 0x02, 0x08, 0x01)))
    }

    @Test
    fun `zero is the disconnected state, and it has a name`() {
        assertEquals(0, GlassOsClient.ConsoleState.DISCONNECTED)
        assertEquals(
            GlassOsClient.ConsoleState.DISCONNECTED_NAME,
            GlassOsClient.ConsoleState.name(0),
        )
        // A state we cannot name is not silently treated as disconnected.
        assertNull(GlassOsClient.ConsoleState.name(99))
    }

    @Test
    fun `a disconnected console is not a belt that may be moving`() {
        assertEquals(
            false,
            GlassOsClient.ConsoleState.beltMayBeMoving(
                GlassOsClient.ConsoleState.DISCONNECTED_NAME,
            ),
        )
    }
}
