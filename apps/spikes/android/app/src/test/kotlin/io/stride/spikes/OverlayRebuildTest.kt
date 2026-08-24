package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When closing a transport is worth telling the overlay about.
 *
 * The overlay rebuilds its entire chrome whenever `MachineLink.presetsGeneration` moves. That is
 * correct when a machine's real quick-picks arrive and replace the fallback ladder — the pills
 * themselves change, which means adding and removing views.
 *
 * It is not correct on a console where no transport can be opened at all. The poll retries every
 * five seconds, each retry closes the transport before reopening it, and closing used to bump the
 * generation unconditionally. So a rider who selected direct access on a machine that could not
 * answer got the whole overlay torn down and rebuilt on a five-second cycle, forever. It was
 * reported, accurately, as flashing.
 *
 * Nothing had changed on any of those passes, because there had never been any presets to lose.
 */
class OverlayRebuildTest {

    /**
     * The case that caused the flashing: a transport that never connected, closed again.
     *
     * No caches, nothing fetched — so nothing to announce, and the overlay is left alone.
     */
    @Test
    fun `closing a transport that never had presets announces nothing`() {
        assertFalse(
            MachineLink.presetsWorthAnnouncing(
                incline = null,
                speed = null,
                inclineFetched = false,
                speedFetched = false,
            ),
        )
    }

    /** Losing real quick-picks is a genuine change; the rails must be rebuilt without them. */
    @Test
    fun `losing cached presets is worth announcing`() {
        assertTrue(
            MachineLink.presetsWorthAnnouncing(
                incline = listOf(3.0, 2.0, 1.0),
                speed = null,
                inclineFetched = true,
                speedFetched = false,
            ),
        )
    }

    /**
     * A machine that was asked and offered none still counts.
     *
     * "Fetched, and the answer was none" is a different state from "never asked", and the overlay
     * showing the fallback ladder needs to know the difference — so dropping it is a change.
     */
    @Test
    fun `having asked and been told none is still worth announcing`() {
        assertTrue(
            MachineLink.presetsWorthAnnouncing(
                incline = null,
                speed = null,
                inclineFetched = true,
                speedFetched = false,
            ),
        )
        assertTrue(
            MachineLink.presetsWorthAnnouncing(
                incline = null,
                speed = null,
                inclineFetched = false,
                speedFetched = true,
            ),
        )
    }
}

/**
 * How hard to keep retrying a transport that will not open.
 *
 * A flat five-second retry is only harmless when opening is cheap, and it is not: discovery runs on
 * the poll thread and a BLE pass is a six-second scan plus up to ten seconds per bonded device. On a
 * console where the transport never opens — which is precisely the machine whose owner files a bug —
 * the attempt can outlast its own interval, so the console spends its life in a reconnect loop that
 * also churns the Bluetooth stack.
 */
class ReopenBackoffTest {

    @Test
    fun `the first retry is prompt and later ones back off`() {
        assertEquals(5_000L, MachineLink.reopenBackoffMs(0))
        assertEquals(10_000L, MachineLink.reopenBackoffMs(1))
        assertEquals(20_000L, MachineLink.reopenBackoffMs(2))
        assertEquals(40_000L, MachineLink.reopenBackoffMs(3))
    }

    /** It stops growing, so a treadmill switched on an hour later is still picked up. */
    @Test
    fun `the backoff is capped`() {
        val capped = MachineLink.reopenBackoffMs(4)
        assertEquals(80_000L, capped)
        assertEquals(capped, MachineLink.reopenBackoffMs(9))
        assertEquals(capped, MachineLink.reopenBackoffMs(100))
    }

    /** Nonsense input cannot produce a zero interval, which would be a tight retry loop. */
    @Test
    fun `a negative count still yields the base interval`() {
        assertEquals(5_000L, MachineLink.reopenBackoffMs(-1))
    }
}
