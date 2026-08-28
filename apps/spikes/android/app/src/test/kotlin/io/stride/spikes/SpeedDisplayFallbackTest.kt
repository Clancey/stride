package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The display-only FitPro speed fallback from issue #34.
 *
 * These tests drive the real client through a fake FitPro frame. That pins both sides of the
 * boundary: the rider sees the KPH setpoint while ACTUAL_KPH is demonstrably dead, but every safety
 * observation still sees the raw zero.
 */
class SpeedDisplayFallbackTest {

    private class FakeWire(
        var setpointKph: Double,
        var actualKph: Double,
        var mode: FitProCodec.WorkoutMode = FitProCodec.WorkoutMode.RUNNING,
    ) : FitProTransport {
        override val name = "fake"
        override val connected = true
        override val variant = FitProCodec.Variant.FITPRO1

        override fun exchange(frame: ByteArray, command: FitProCodec.Command): ByteArray {
            // KPH(2), WATTS(2), CURRENT_DISTANCE(4), RUNNING_TIME(4), WORKOUT_MODE(1),
            // ACTUAL_KPH(2), ACTUAL_INCLINE(2), CURRENT_CALORIES(4), in ascending field order.
            val values = ByteArray(21)
            FitProCodec.encodeSpeed(setpointKph).copyInto(values, 0)
            values[12] = mode.value.toByte()
            FitProCodec.encodeSpeed(actualKph).copyInto(values, 13)

            val total = FitProCodec.FRAME_OVERHEAD + 1 + values.size
            return ByteArray(total).also { reply ->
                reply[0] = FitProCodec.ADDRESS_MAIN.toByte()
                reply[1] = total.toByte()
                reply[2] = FitProCodec.Command.READ_WRITE_DATA.value.toByte()
                reply[3] = FitProCodec.Status.DONE.value.toByte()
                values.copyInto(reply, 4)
                reply[total - 1] = FitProCodec.checksum(reply, total - 1)
            }
        }

        override fun close() = Unit
    }

    private fun client(wire: FakeWire) = DirectMachineClient(DirectMachineSession(wire))

    @Test
    fun `a dead actual register displays the setpoint without becoming an observation`() {
        val snapshot = client(FakeWire(setpointKph = 6.4, actualKph = 0.0)).read()!!

        assertEquals(0.0, snapshot.speedMph!!, 0.001)
        assertEquals(FitProValues.kphToMph(6.4), snapshot.displaySpeedMph!!, 0.001)
    }

    @Test
    fun `a display fallback cannot satisfy a pending rail request`() {
        val snapshot = client(
            FakeWire(setpointKph = FitProValues.KPH_PER_MPH * 5.0, actualKph = 0.0),
        ).read()!!
        val requested = snapshot.displaySpeedMph!!
        val pending = PendingSetpoint(tolerance = 0.3, graceMs = 5_000L)

        // The rail is wired to raw speedMph. Feeding it the display fallback here would clear the
        // request immediately and falsely turn the commanded rung into a measured one.
        pending.request(value = requested, label = "5", nowMs = 0L, measured = snapshot.speedMph)
        assertEquals(requested, pending.target!!, 0.001)
        assertEquals(requested, pending.observe(snapshot.speedMph, nowMs = 1_000L)!!, 0.001)

        val incorrectlyDisplayDriven = PendingSetpoint(tolerance = 0.3, graceMs = 5_000L)
        incorrectlyDisplayDriven.request(
            value = requested,
            label = "5",
            nowMs = 0L,
            measured = snapshot.displaySpeedMph,
        )
        assertNull(incorrectlyDisplayDriven.target)
    }

    @Test
    fun `the fallback follows console readback rather than local command state`() {
        val wire = FakeWire(setpointKph = 3.2, actualKph = 0.0)
        val client = client(wire)
        assertEquals(FitProValues.kphToMph(3.2), client.read()!!.displaySpeedMph!!, 0.001)

        wire.setpointKph = 8.0
        assertEquals(FitProValues.kphToMph(8.0), client.read()!!.displaySpeedMph!!, 0.001)
    }

    @Test
    fun `pace is derived from the speed the rider sees`() {
        val snapshot = client(FakeWire(setpointKph = FitProValues.KPH_PER_MPH * 2.0, actualKph = 0.0))
            .read()!!

        // displaySpeedMph itself carries the register's own small quantization residual (see
        // FitProValues.paceMinPerMile) -- paceMinPerMile rounds past it, so the two are compared
        // against the rider's clean 2.0 mph ask rather than against each other.
        assertEquals(2.0, snapshot.displaySpeedMph!!, 0.01)
        assertEquals(30.0, snapshot.paceMinPerMile!!, 0.001)
    }

    @Test
    fun `actual speed wins as soon as it reports motion`() {
        val snapshot = client(FakeWire(setpointKph = 8.0, actualKph = 6.0)).read()!!

        assertEquals(FitProValues.kphToMph(6.0), snapshot.speedMph!!, 0.001)
        assertEquals(snapshot.speedMph, snapshot.displaySpeedMph)
    }

    @Test
    fun `a proven actual register keeps its later zero instead of falling back`() {
        val wire = FakeWire(setpointKph = 6.0, actualKph = 6.0)
        val client = client(wire)
        client.read()

        wire.actualKph = 0.0
        val stopped = client.read()!!
        assertEquals(0.0, stopped.speedMph!!, 0.001)
        assertEquals(0.0, stopped.displaySpeedMph!!, 0.001)
        assertNull(stopped.paceMinPerMile)
    }

    @Test
    fun `non-moving workout states do not display a stale setpoint`() {
        for (mode in listOf(
            FitProCodec.WorkoutMode.PAUSE,
            FitProCodec.WorkoutMode.PAUSE_OVERRIDE,
            FitProCodec.WorkoutMode.RESULTS,
            FitProCodec.WorkoutMode.IDLE,
        )) {
            val snapshot = client(FakeWire(setpointKph = 6.0, actualKph = 0.0, mode = mode)).read()!!
            assertEquals("$mode speed", 0.0, snapshot.displaySpeedMph!!, 0.001)
            assertNull("$mode pace", snapshot.paceMinPerMile)
        }
    }

    @Test
    fun `a different client must prove its own actual register`() {
        val provenWire = FakeWire(setpointKph = 8.0, actualKph = 6.0)
        val proven = client(provenWire)
        proven.read()
        provenWire.actualKph = 0.0
        assertEquals(0.0, proven.read()!!.displaySpeedMph!!, 0.001)

        val unproven = client(FakeWire(setpointKph = 8.0, actualKph = 0.0))
        assertEquals(FitProValues.kphToMph(8.0), unproven.read()!!.displaySpeedMph!!, 0.001)
    }

    // --------------------------------------- pace rounds to the speed's own displayed precision

    /**
     * A rider commanding a clean mph value always gets a clean pace back, however the register's
     * 0.01 kph resolution happened to round that request.
     *
     * Measured live on an X22i: a commanded 4 mph paced 15:01 instead of 15:00, because `KPH` could
     * only hold 6.43 or 6.44 kph, never exactly 6.437376. [FitProValues.paceMinPerMile] rounds its
     * input to the tenth already shown on screen before dividing, which is comfortably wider than
     * the register's worst-case ~0.006 mph residual.
     */
    @Test
    fun `pace matches the speed shown, not the register's exact quantization`() {
        for (mph in listOf(1.0, 2.0, 4.0, 6.0, 11.9)) {
            val kph = FitProValues.mphToKph(mph)
            val snapshot = client(FakeWire(setpointKph = kph, actualKph = 0.0)).read()!!
            assertEquals("$mph mph", 60.0 / mph, snapshot.paceMinPerMile!!, 1e-9)
        }
    }

    @Test
    fun `paceMinPerMile rounds to one decimal, not two`() {
        // 3.9956 rounds to 4.0 at one decimal (the fix), but to 4.00 at two -- which would still
        // leave the residual visible. One decimal is what the speed readout itself shows.
        assertEquals(15.0, FitProValues.paceMinPerMile(3.9956)!!, 1e-9)
        assertEquals(15.0, FitProValues.paceMinPerMile(4.0044)!!, 1e-9)
    }

    @Test
    fun `paceMinPerMile is still null for a belt rounding down to a stop`() {
        assertNull(FitProValues.paceMinPerMile(0.04))
    }
}
