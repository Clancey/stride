package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The receiver's answer is parsed by `tools/certify.sh`, so its shape is a contract, not a detail.
 */
class CertificationDumpReceiverTest {
    @Test
    fun `passes a real id through untouched`() {
        // Grouping and formatting belong to whoever displays it. What goes back to the shell is
        // what gets registered, so anything added here would have to be stripped there.
        assertEquals(
            "3849284900098157545",
            CertificationDumpReceiver.resultFor("3849284900098157545"),
        )
    }

    @Test
    fun `says none rather than nothing when there is no id`() {
        // An empty result is indistinguishable from a receiver that never ran, and the script has
        // to tell those apart to say anything useful.
        assertEquals("none", CertificationDumpReceiver.resultFor(null))
    }
}
