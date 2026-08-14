package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule for how long the value a rider asked for stays on screen.
 *
 * Written as tests rather than trusted to review because the failure modes are opposite and both
 * are bad: drop it too early and a tap looks ignored while the belt is visibly still ramping, hold
 * it too long and the column keeps advertising a speed the machine has already declined.
 */
class PendingSetpointTest {

    private fun setpoint() = PendingSetpoint(tolerance = 0.3, graceMs = 5_000L)

    @Test
    fun `a request is marked while the belt is still on its way`() {
        val pending = setpoint()
        pending.request(value = 5.0, label = "5", nowMs = 0L, measured = 2.0)

        assertEquals(5.0, pending.observe(measured = 2.4, nowMs = 1_000L))
        assertEquals("5", pending.label)
    }

    @Test
    fun `the request clears once the machine arrives`() {
        val pending = setpoint()
        pending.request(value = 5.0, label = "5", nowMs = 0L, measured = 2.0)
        pending.observe(measured = 3.5, nowMs = 1_000L)

        assertNull(pending.observe(measured = 4.9, nowMs = 4_000L))
        assertNull(pending.target)
    }

    @Test
    fun `asking for the speed the machine already reports promises nothing`() {
        val pending = setpoint()
        pending.request(value = 5.0, label = "5", nowMs = 0L, measured = 5.05)

        assertNull(pending.target)
    }

    @Test
    fun `a belt moving away from the request drops the mark immediately`() {
        val pending = setpoint()
        pending.request(value = 8.0, label = "8", nowMs = 0L, measured = 6.0)
        pending.observe(measured = 6.5, nowMs = 1_000L)

        // Someone hit slow-down on the console, or the command was refused.
        assertNull(pending.observe(measured = 5.5, nowMs = 2_000L))
    }

    @Test
    fun `a request nothing happens to expires`() {
        val pending = setpoint()
        pending.request(value = 8.0, label = "8", nowMs = 0L, measured = 3.0)
        assertEquals(8.0, pending.observe(measured = 3.0, nowMs = 2_000L))

        assertNull(pending.observe(measured = 3.0, nowMs = 5_100L))
    }

    @Test
    fun `a long climb outlasts the grace period because it keeps making progress`() {
        val pending = setpoint()
        pending.request(value = 12.0, label = "12", nowMs = 0L, measured = 2.0)

        // A ramp far longer than the grace window, one reading every two seconds.
        var now = 0L
        var speed = 2.0
        while (speed < 11.5) {
            now += 2_000L
            speed += 0.5
            assertEquals(12.0, pending.observe(measured = speed, nowMs = now))
        }
        assertNull(pending.observe(measured = 11.9, nowMs = now + 2_000L))
    }

    @Test
    fun `telemetry lapsing neither confirms nor refutes, it only runs the clock`() {
        val pending = setpoint()
        pending.request(value = 5.0, label = "5", nowMs = 0L, measured = 2.0)

        assertEquals(5.0, pending.observe(measured = null, nowMs = 3_000L))
        assertNull(pending.observe(measured = null, nowMs = 5_500L))
    }

    @Test
    fun `the newest tap replaces the previous one`() {
        val pending = setpoint()
        pending.request(value = 5.0, label = "5", nowMs = 0L, measured = 2.0)
        pending.request(value = 7.0, label = "7", nowMs = 500L, measured = 2.0)

        assertEquals(7.0, pending.target)
        assertEquals("7", pending.label)
    }
}
