package io.stride.spikes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a preset axis is worth asking about again.
 *
 * Two failures pull against each other here, and this is the line between them.
 *
 * Ask once and believe it, and the quick-pick columns stay blank forever: GlassOS answers
 * `GetControls` out of the live workout, so an idle console returns a successful *empty* list and
 * only publishes its real buttons once something is running. That is the bug the retry exists for.
 *
 * Ask forever and the poll thread pays. Every metric on screen is refreshed from that one thread,
 * and on a console that has lost its own treadmill each RPC can block for the whole read timeout —
 * so re-asking a detached console on a timer trades blank rails for a frozen readout.
 */
class PresetRetryTest {

    private val fresh: List<Double>? = null
    private val answeredNone = emptyList<Double>()

    /** Never asked is always asked, whatever else is true. */
    @Test
    fun `an axis that has never been asked is always asked`() {
        assertTrue(MachineLink.mayAskPresets(fresh, nextAskAt = 0L, nowMs = 0L, detached = false))
        // Including on a detached console: "asked, and the answer was none" is what the rails need
        // to tell apart from "not asked yet", and only an ask produces it.
        assertTrue(MachineLink.mayAskPresets(fresh, nextAskAt = 0L, nowMs = 0L, detached = true))
        // And regardless of a clock that has not come round.
        assertTrue(
            MachineLink.mayAskPresets(fresh, nextAskAt = 9_000L, nowMs = 0L, detached = false),
        )
    }

    /** An empty answer stands until its interval is up, then is asked again. */
    @Test
    fun `an empty answer is re-asked on the interval`() {
        assertFalse(
            MachineLink.mayAskPresets(
                answeredNone, nextAskAt = 10_000L, nowMs = 4_000L, detached = false,
            ),
        )
        assertTrue(
            MachineLink.mayAskPresets(
                answeredNone, nextAskAt = 10_000L, nowMs = 10_000L, detached = false,
            ),
        )
    }

    /**
     * A console that has lost its treadmill is never re-asked.
     *
     * It has nothing to publish — a head unit with no link to the lower board cannot start offering
     * quick picks for it — and asking is the expensive half of this on exactly the machine that can
     * least afford it.
     */
    @Test
    fun `a detached console is not re-asked, however long it has been`() {
        assertFalse(
            MachineLink.mayAskPresets(
                answeredNone, nextAskAt = 10_000L, nowMs = 999_000L, detached = true,
            ),
        )
    }
}
