package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Wire and telemetry tests built from messages **actually captured from the treadmill** during the
 * live workout on 2026-08-13 (`.telemetry/20260813-222255/`), not from invented examples.
 *
 * The capture is the point. The single most dangerous behaviour in this whole subsystem is that a
 * genuinely-zero field and a never-sent field are byte-identical in proto3, and the only reason we
 * know which is which is that we watched a real machine send both. These tests pin that reading
 * down so a later refactor cannot quietly reintroduce a confident `0.0` next to a moving belt.
 */
class GlassOsWireTest {

    // ---- helpers that build real protobuf bytes -----------------------------------------------

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun tag(field: Int, wireType: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, ((field shl 3) or wireType).toLong())
        return out.toByteArray()
    }

    private fun stringField(field: Int, value: String): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(tag(field, 2))
        writeVarint(out, raw.size.toLong())
        out.write(raw)
        return out.toByteArray()
    }

    private fun varintField(field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(field, 0))
        writeVarint(out, value)
        return out.toByteArray()
    }

    private fun doubleField(field: Int, value: Double): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(field, 1))
        val bits = java.lang.Double.doubleToLongBits(value)
        for (b in 0..7) out.write(((bits ushr (8 * b)) and 0xFF).toInt())
        return out.toByteArray()
    }

    private fun message(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** The workout id the machine actually issued during the recorded session. */
    private val workoutId = "d3a3a6d8-b50b-406c-8e46-98b1485cf276"

    // ---- the captured messages -----------------------------------------------------------------

    @Test
    fun `decodes the speed message the machine actually sent`() {
        // {"workoutID":"d3a3a6d8-…","timeSeconds":36,"lastKph":1.6093}
        val fields = GlassOsWire.parse(
            message(
                stringField(1, workoutId),
                varintField(2, 36),
                doubleField(3, 1.6093),
            )
        )
        assertEquals(workoutId, fields.string(1))
        assertEquals(36L, fields.long(2))
        assertEquals(1.6093, fields.double(3)!!, 1e-9)
        // Fields the message did not carry stay absent rather than becoming zero.
        assertNull(fields.double(4))
        assertNull(fields.double(5))
    }

    @Test
    fun `decodes the distance message the machine actually sent`() {
        // {"workoutID":"…","lastDistanceKm":0.01654,"remainingDistanceKm":19.3045}
        val fields = GlassOsWire.parse(
            message(
                stringField(1, workoutId),
                doubleField(3, 0.01654),
                doubleField(4, 19.3045),
            )
        )
        assertEquals(0.01654, fields.double(3)!!, 1e-9)
        assertEquals(19.3045, fields.double(4)!!, 1e-9)
    }

    /**
     * The most important test in the file.
     *
     * During the recorded workout the incline was genuinely 0%, and all 39 `InclineSubscription`
     * messages carried **only** the workout id — no incline field at all. This is byte-for-byte
     * what a machine that never reported incline would send.
     */
    @Test
    fun `incline at a true zero arrives with no incline field at all`() {
        val fields = GlassOsWire.parse(message(stringField(1, workoutId)))

        assertEquals(workoutId, fields.string(1))
        assertNull("the machine sent no incline field", fields.double(3))

        // With a workout in progress and the service readable, that absence means a measured zero.
        val resolved = GlassOsTelemetry.reading(fields.double(3), fields.string(1), canRead = true)
        assertEquals(0.0, resolved!!, 1e-9)
    }

    @Test
    fun `the same bytes without a workout mean unknown, not zero`() {
        // Before the workout started, the recorder captured `{}` — no workout id, no value.
        val fields = GlassOsWire.parse(ByteArray(0))

        assertNull(fields.string(1))
        assertNull(
            "no workout context means we do not know the incline",
            GlassOsTelemetry.reading(fields.double(3), fields.string(1), canRead = true)
        )
    }

    @Test
    fun `an unreadable service is never treated as zero`() {
        assertNull(GlassOsTelemetry.reading(null, workoutId, canRead = false))
    }

    /**
     * StepCount and Cadence on the 1750 answer `CanRead` with an empty message, because proto3
     * omits `false`. Defaulting that to true would make the app believe the exact opposite of what
     * the machine said.
     */
    @Test
    fun `availability denies by default`() {
        val empty = GlassOsWire.parse(ByteArray(0))
        assertEquals(false, GlassOsTelemetry.availability(empty.bool(1)))

        val available = GlassOsWire.parse(message(varintField(1, 1)))
        assertEquals(true, GlassOsTelemetry.availability(available.bool(1)))
    }

    /** GlassOS uses NaN as "no figure". It fails every comparison, so it must be caught by name. */
    @Test
    fun `NaN is not a reading`() {
        val fields = GlassOsWire.parse(
            message(stringField(1, workoutId), doubleField(4, Double.NaN))
        )
        assertTrue(fields.double(4)!!.isNaN())
        assertNull(GlassOsTelemetry.reading(fields.double(4), workoutId, canRead = true))
    }

    // ---- console state -------------------------------------------------------------------------

    /**
     * Transcribed from `protocol/glassos/console/ConsoleState.proto`. An early draft of this
     * mapping placed SAFETY_KEY_REMOVED at 1; it is 6. Pinning the safety-relevant values here
     * means the next such slip fails a test instead of mislabelling a safety state on screen.
     */
    @Test
    fun `console states match the extracted proto`() {
        assertEquals("IDLE", GlassOsClient.ConsoleState.name(2))
        assertEquals("WORKOUT", GlassOsClient.ConsoleState.name(3))
        assertEquals("WORKOUT_RESULTS", GlassOsClient.ConsoleState.name(5))
        assertEquals("SAFETY_KEY_REMOVED", GlassOsClient.ConsoleState.name(6))
        assertEquals("WARM_UP", GlassOsClient.ConsoleState.name(7))
        assertNull(GlassOsClient.ConsoleState.name(null))
    }

    @Test
    fun `the states observed during the recorded workout are the moving ones`() {
        // Captured transition: IDLE -> WARM_UP -> WORKOUT -> WORKOUT_RESULTS -> IDLE
        assertTrue(GlassOsClient.ConsoleState.beltMayBeMoving("WARM_UP"))
        assertTrue(GlassOsClient.ConsoleState.beltMayBeMoving("WORKOUT"))
        assertEquals(false, GlassOsClient.ConsoleState.beltMayBeMoving("IDLE"))
        assertEquals(false, GlassOsClient.ConsoleState.beltMayBeMoving("WORKOUT_RESULTS"))
        assertEquals(false, GlassOsClient.ConsoleState.beltMayBeMoving(null))
    }

    // ---- gRPC framing --------------------------------------------------------------------------

    @Test
    fun `an empty request is five bytes, not zero bytes`() {
        assertEquals(5, GlassOsWire.EMPTY_FRAME.size)
        assertTrue(GlassOsWire.EMPTY_FRAME.all { it.toInt() == 0 })
    }

    @Test
    fun `framing round-trips`() {
        val payload = message(stringField(1, workoutId), doubleField(3, 1.6093))
        val framed = GlassOsWire.frame(payload)
        assertEquals(payload.size + 5, framed.size)
        val recovered = GlassOsWire.unframe(framed)!!
        assertEquals(1.6093, GlassOsWire.parse(recovered).double(3)!!, 1e-9)
    }

    @Test
    fun `a truncated response is no reading rather than a crash`() {
        assertNull(GlassOsWire.unframe(ByteArray(3)))
        // A frame claiming more bytes than arrived must not be parsed as if it were complete.
        assertNull(GlassOsWire.unframe(byteArrayOf(0, 0, 0, 0, 64, 1, 2)))
    }

    /** A subscription delivers many frames per read, and a read can land mid-frame. */
    @Test
    fun `streaming keeps the incomplete tail for the next read`() {
        val one = GlassOsWire.frame(message(doubleField(3, 1.0)))
        val two = GlassOsWire.frame(message(doubleField(3, 2.0)))
        val buffer = one + two.copyOfRange(0, 4) // second frame arrives half-read

        val (messages, consumed) = GlassOsWire.unframeAll(buffer, buffer.size)
        assertEquals(1, messages.size)
        assertEquals(1.0, GlassOsWire.parse(messages[0]).double(3)!!, 1e-9)
        assertEquals("only the complete frame was consumed", one.size, consumed)
    }

    // ---- derived values ------------------------------------------------------------------------

    @Test
    fun `speed converts to the units on the console`() {
        // The machine reported 1.6093 kph, which is the 1.0 mph minimum from ConsoleInfo.
        assertEquals(1.0, GlassOsTelemetry.kphToMph(1.6093)!!, 0.001)
        assertNull(GlassOsTelemetry.kphToMph(null))
    }

    @Test
    fun `pace is only derived from a speed we measured`() {
        assertEquals(10.0, GlassOsTelemetry.paceMinPerMile(6.0)!!, 0.001)
        assertNull("no speed reading means no pace", GlassOsTelemetry.paceMinPerMile(null))
        assertNull("a stationary belt has no meaningful pace", GlassOsTelemetry.paceMinPerMile(0.0))
    }
}
