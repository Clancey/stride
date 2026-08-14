package io.stride.spikes.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug these pin down was found on hardware: install two apps quickly, miss the second
 * confirmation dialog, and the first app's row stays "waiting for you to confirm" with a disabled
 * button forever. The platform never reports that it destroyed the buried dialog.
 */
class ConfirmQueueTest {
    @Test
    fun `first offer shows immediately`() {
        val queue = ConfirmQueue()
        assertTrue(queue.offer("com.a"))
        assertEquals("com.a", queue.holder())
    }

    @Test
    fun `second offer waits instead of burying the first`() {
        val queue = ConfirmQueue()
        queue.offer("com.a")
        assertFalse(queue.offer("com.b"))
        assertEquals("com.a", queue.holder())
    }

    @Test
    fun `settling the holder promotes the one behind it`() {
        val queue = ConfirmQueue()
        queue.offer("com.a")
        queue.offer("com.b")
        assertEquals("com.b", queue.settled("com.a"))
        assertEquals("com.b", queue.holder())
    }

    @Test
    fun `queued installs are promoted in the order they were asked for`() {
        val queue = ConfirmQueue()
        queue.offer("com.a")
        queue.offer("com.b")
        queue.offer("com.c")
        assertEquals("com.b", queue.settled("com.a"))
        assertEquals("com.c", queue.settled("com.b"))
    }

    @Test
    fun `settling a queued package does not disturb the holder`() {
        val queue = ConfirmQueue()
        queue.offer("com.a")
        queue.offer("com.b")
        assertNull(queue.settled("com.b"))
        assertEquals("com.a", queue.holder())
    }

    @Test
    fun `a missed prompt can be raised again`() {
        val queue = ConfirmQueue()
        queue.offer("com.a")
        // The user dismissed it. Nothing settled, so it is still ours to re-raise.
        assertTrue(queue.reshow("com.a"))
        assertEquals("com.a", queue.holder())
    }

    @Test
    fun `re-raising a queued package takes the screen from the holder`() {
        // The rider pressed Confirm on the row that is waiting. That is an explicit request for
        // this package, so it wins the screen rather than being told to wait for itself.
        val queue = ConfirmQueue()
        queue.offer("com.a")
        queue.offer("com.b")
        assertTrue(queue.reshow("com.b"))
        assertEquals("com.b", queue.holder())
    }

    @Test
    fun `nothing to re-raise for a package that was never offered`() {
        assertFalse(ConfirmQueue().reshow("com.a"))
    }

    @Test
    fun `nothing to re-raise once it settled`() {
        val queue = ConfirmQueue()
        queue.offer("com.a")
        queue.settled("com.a")
        assertFalse(queue.reshow("com.a"))
        assertNull(queue.holder())
    }

    @Test
    fun `offering the same package twice keeps one entry`() {
        val queue = ConfirmQueue()
        queue.offer("com.a")
        assertTrue(queue.offer("com.a"))
        queue.offer("com.b")
        assertEquals("com.b", queue.settled("com.a"))
        // com.a was queued once, so nothing is left behind com.b.
        assertNull(queue.settled("com.b"))
    }

    @Test
    fun `a prompt that could not be shown does not block the queue`() {
        val queue = ConfirmQueue()
        queue.offer("com.a")
        queue.offer("com.b")
        assertEquals("com.b", queue.abandon("com.a"))
        assertFalse(queue.isWaiting("com.a"))
    }
}
