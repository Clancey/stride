package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The direct path's stand-in for GlassOS's `workoutID`.
 *
 * GlassOS puts a `workoutInstanceID` in field 1 of every metric response and builds it two ways
 * (`am/j`): with a real id during a workout, and with `CoreConstants.EMPTY_STRING` when there is
 * not one. So the field is a discriminator — non-empty exactly while a workout instance exists —
 * and that is what [GlassOsTelemetry] reads it as. FitPro has no such register, so the direct path
 * synthesises the same meaning from `WORKOUT_MODE`.
 *
 * These drive the real [DirectMachineClient.read] through a fake wire rather than poking at the
 * private counter, so the framing, the register decode and the instance rule are all under test.
 */
class WorkoutInstanceIdTest {

    /** Replies to any read with a full telemetry frame reporting [mode], as the console would. */
    private class FakeWire(var mode: FitProCodec.WorkoutMode) : FitProTransport {
        override val name = "fake"
        override val connected = true
        var replies = 0

        /** Set to drop `WORKOUT_MODE` from the reply, which is how a lossy link looks to [read]. */
        var silent = false

        override fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
            replies++
            if (silent) return null
            // Values are packed in ascending field id: CURRENT_DISTANCE(4), RUNNING_TIME(4),
            // WORKOUT_MODE(1), ACTUAL_KPH(2), ACTUAL_INCLINE(2), CURRENT_CALORIES(4) = 17 bytes.
            val values = ByteArray(17)
            values[8] = mode.value.toByte()
            // Header is address, length, command, status; then values; then the checksum — one
            // byte more than a request's FRAME_OVERHEAD, which has no status.
            val total = FitProCodec.FRAME_OVERHEAD + 1 + values.size
            val reply = ByteArray(total)
            reply[0] = FitProCodec.ADDRESS_MAIN.toByte()
            reply[1] = total.toByte()
            reply[2] = FitProCodec.Command.READ_WRITE_DATA.value.toByte()
            reply[3] = FitProCodec.Status.DONE.value.toByte()
            values.copyInto(reply, 4)
            reply[total - 1] = FitProCodec.checksum(reply, total - 1)
            return reply
        }

        override fun close() = Unit
    }

    private fun client(mode: FitProCodec.WorkoutMode): Pair<DirectMachineClient, FakeWire> {
        val wire = FakeWire(mode)
        return DirectMachineClient(DirectMachineSession(wire)) to wire
    }

    /** An idle console has no workout instance, which is the empty string on the GlassOS side. */
    @Test
    fun idleConsoleHasNoInstance() {
        val (client, _) = client(FitProCodec.WorkoutMode.IDLE)
        assertNull("an idle console is not in a workout", client.read()?.workoutId)
    }

    /** A running console has one, and it is the same one on every poll of that run. */
    @Test
    fun runningConsoleHasAStableInstance() {
        val (client, _) = client(FitProCodec.WorkoutMode.RUNNING)
        val first = client.read()?.workoutId
        assertNotNull("a running console is in a workout", first)
        assertEquals("the id must not change between polls of one run", first, client.read()?.workoutId)
        assertEquals("or between any two polls", first, client.read()?.workoutId)
    }

    /**
     * Pausing does not end the workout, and neither does the results screen — the totals there are
     * still that workout's, and GlassOS keeps reporting them.
     */
    @Test
    fun pauseAndResultsBelongToTheSameInstance() {
        val (client, wire) = client(FitProCodec.WorkoutMode.RUNNING)
        val id = client.read()?.workoutId
        assertNotNull(id)
        for (mode in listOf(
            FitProCodec.WorkoutMode.PAUSE,
            FitProCodec.WorkoutMode.RESUME,
            FitProCodec.WorkoutMode.RUNNING,
            FitProCodec.WorkoutMode.COOL_DOWN,
            FitProCodec.WorkoutMode.RESULTS,
        )) {
            wire.mode = mode
            assertEquals("$mode is still the same workout", id, client.read()?.workoutId)
        }
    }

    /** Returning to idle ends it, and the next run is a *different* workout, not a continuation. */
    @Test
    fun aSecondRunGetsANewInstance() {
        val (client, wire) = client(FitProCodec.WorkoutMode.RUNNING)
        val first = client.read()?.workoutId
        assertNotNull(first)

        wire.mode = FitProCodec.WorkoutMode.IDLE
        assertNull("back to idle ends the instance", client.read()?.workoutId)

        wire.mode = FitProCodec.WorkoutMode.RUNNING
        val second = client.read()?.workoutId
        assertNotNull(second)
        assertNotEquals("a second run must be distinguishable from the first", first, second)
    }

    /**
     * A dropped `WORKOUT_MODE` decodes to UNKNOWN, which means "we could not tell", not "no
     * workout". Clearing on it would split one run into two ids the moment the link glitched.
     */
    @Test
    fun anUnreadableModeHoldsTheInstanceRatherThanEndingIt() {
        val (client, wire) = client(FitProCodec.WorkoutMode.RUNNING)
        val id = client.read()?.workoutId
        assertNotNull(id)

        wire.mode = FitProCodec.WorkoutMode.UNKNOWN
        assertEquals("an unreadable mode must not end the workout", id, client.read()?.workoutId)

        wire.mode = FitProCodec.WorkoutMode.RUNNING
        assertEquals("and the run continues under the same id", id, client.read()?.workoutId)
    }

    /** Holding on UNKNOWN must never *invent* an instance we have no evidence for. */
    @Test
    fun anUnreadableModeDoesNotInventAnInstance() {
        val (client, wire) = client(FitProCodec.WorkoutMode.IDLE)
        assertNull(client.read()?.workoutId)
        wire.mode = FitProCodec.WorkoutMode.UNKNOWN
        assertNull("unknown cannot start a workout", client.read()?.workoutId)
    }

    /** Service and showroom modes are not workouts. */
    @Test
    fun nonWorkoutModesClearTheInstance() {
        for (mode in listOf(
            FitProCodec.WorkoutMode.SLEEP,
            FitProCodec.WorkoutMode.LOCKED,
            FitProCodec.WorkoutMode.DEMO,
            FitProCodec.WorkoutMode.MAINTENANCE,
            FitProCodec.WorkoutMode.DEBUG,
            FitProCodec.WorkoutMode.DMK,
            FitProCodec.WorkoutMode.LOG,
        )) {
            val (client, wire) = client(FitProCodec.WorkoutMode.RUNNING)
            assertNotNull(client.read()?.workoutId)
            wire.mode = mode
            assertNull("$mode is not a workout", client.read()?.workoutId)
        }
    }

    /** A lost reply yields no snapshot at all, so it cannot disturb the instance either way. */
    @Test
    fun aLostReplyLeavesTheInstanceAlone() {
        val (client, wire) = client(FitProCodec.WorkoutMode.RUNNING)
        val id = client.read()?.workoutId
        assertNotNull(id)

        wire.silent = true
        assertNull("no reply is no snapshot", client.read())

        wire.silent = false
        assertEquals("the run survives a lost poll", id, client.read()?.workoutId)
    }

    /**
     * Two consoles in one process must not share an id. The counter is static for exactly this
     * reason — a per-instance counter would hand both sessions "direct-1".
     */
    @Test
    fun twoSessionsGetDistinctInstances() {
        val (a, _) = client(FitProCodec.WorkoutMode.RUNNING)
        val (b, _) = client(FitProCodec.WorkoutMode.RUNNING)
        assertNotEquals(a.read()?.workoutId, b.read()?.workoutId)
    }

    /**
     * The whole point of the field: [GlassOsTelemetry] must read a direct-path id the same way it
     * reads a GlassOS one, since it cannot tell which transport produced the snapshot.
     */
    @Test
    fun telemetryReadsADirectIdTheSameWayAsAGlassOsOne() {
        val (client, wire) = client(FitProCodec.WorkoutMode.RUNNING)
        val running = client.read()
        assertNotNull(running?.workoutId)

        wire.mode = FitProCodec.WorkoutMode.IDLE
        val idle = client.read()
        assertNull(idle?.workoutId)
    }
}
