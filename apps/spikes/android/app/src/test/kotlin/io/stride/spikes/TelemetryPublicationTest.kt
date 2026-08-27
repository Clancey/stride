package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TelemetryPublicationTest {

    @Test
    fun `detach while an old poll is blocked rejects its eventual publication`() {
        val gate = PublicationGate()
        val pollGeneration = gate.capture()
        var value: String? = "old"
        val pollMayPublish = CountDownLatch(1)
        val pollDone = CountDownLatch(1)

        val poll = thread {
            pollMayPublish.await()
            gate.publish(pollGeneration) { value = "resurrected" }
            pollDone.countDown()
        }
        gate.invalidate { value = null }
        pollMayPublish.countDown()

        assertTrue(pollDone.await(1, TimeUnit.SECONDS))
        poll.join()
        assertEquals(null, value)
    }

    @Test
    fun `detach waits for an active publication then clears it`() {
        val gate = PublicationGate()
        val pollGeneration = gate.capture()
        var value: String? = null
        val publishing = CountDownLatch(1)
        val finishPublication = CountDownLatch(1)

        val poll = thread {
            gate.publish(pollGeneration) {
                publishing.countDown()
                finishPublication.await()
                // Written last so a broken gate that lets detach interleave would leave "reading"
                // resurrected after detach's clear and fail deterministically.
                value = "reading"
            }
        }
        assertTrue(publishing.await(1, TimeUnit.SECONDS))

        val attemptingDetach = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val detach = thread {
            attemptingDetach.countDown()
            gate.invalidate { value = null }
            detached.countDown()
        }
        assertTrue(attemptingDetach.await(1, TimeUnit.SECONDS))
        finishPublication.countDown()

        assertTrue(detached.await(1, TimeUnit.SECONDS))
        poll.join()
        detach.join()
        assertEquals(null, value)
    }

    @Test
    fun `an open that outlives detach cannot reinstall its old client`() {
        val gate = PublicationGate()
        val openingGeneration = gate.capture()
        var client: String? = null

        gate.invalidate { client = null }
        val installed = gate.publish(openingGeneration) { client = "old client" }

        assertFalse(installed)
        assertEquals(null, client)
    }

    @Test
    fun `failed read latch is consumed only by the first successful publication`() {
        val latch = ReconnectResetLatch()
        latch.noteLost()

        assertTrue(latch.takeLost())
        assertFalse(latch.takeLost())
    }

    @Test
    fun `successful reconnect starts gain from a new distance baseline`() {
        val latch = ReconnectResetLatch()
        var gain = MachineLink.foldVertGain(
            null,
            snapshot(distanceMiles = 1.0, inclinePercent = 10.0),
        )
        gain = MachineLink.foldVertGain(
            gain,
            snapshot(distanceMiles = 1.1, inclinePercent = 10.0),
        )
        assertEquals(52.8, gain.feet!!, 1e-9)

        latch.noteLost()
        if (latch.takeLost()) gain = MachineLink.foldVertGain(
            null,
            snapshot(distanceMiles = 1.3, inclinePercent = 15.0),
        )

        assertEquals(0.0, gain.feet!!, 0.0)
        assertEquals(1.3, gain.distanceMiles!!, 0.0)
    }

    private fun snapshot(
        distanceMiles: Double,
        inclinePercent: Double,
    ) = GlassOsClient.Snapshot(
        consoleState = null,
        workoutId = "same-workout",
        speedMph = null,
        inclinePercent = inclinePercent,
        distanceMiles = distanceMiles,
        paceMinPerMile = null,
        elapsedSeconds = null,
        calories = null,
        speedWritable = null,
        inclineWritable = null,
        fanWritable = null,
    )
}
