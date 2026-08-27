package io.stride.spikes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the overlay is allowed to say about the fan.
 *
 * Stride holds two fan values and they are not the same kind of thing. `MachineLink.fanState` is
 * what the machine reported; `MachineCoordinator.lastFanRequest` is Stride's pending or last
 * accepted request, and the console has its own fan button that Stride never hears — so even an
 * accepted request can be confidently wrong the moment the rider presses it.
 *
 * Item 9 of the safety checklist on `MachineLink.canCommand` is the rule these pin: the UI
 * distinguishes requested from confirmed from unknown, and never shows a requested value styled as
 * a measured one. Three ways to get that wrong, all of them tested here — claiming OFF because
 * nobody answered, drawing a fan on a treadmill that has none, and presenting a stale reading as
 * current while a newer request is in flight.
 */
class FanReadoutTest {

    private fun readout(
        reported: Int? = null,
        reportedAt: Long = 0L,
        requested: Int? = null,
        requestedAt: Long = 0L,
        requestPending: Boolean = true,
        knownPresent: Boolean = true,
    ) = MachineLink.fanReadout(
        reported,
        reportedAt,
        requested,
        requestedAt,
        requestPending,
        knownPresent,
    )

    /** The plain case: the machine answered, so the answer is drawn as the reading it is. */
    @Test
    fun `a reading is measured`() {
        assertEquals(
            MachineLink.FanReadout.Measured(GlassOsCommands.FAN_HIGH),
            readout(reported = GlassOsCommands.FAN_HIGH, reportedAt = 100L),
        )
    }

    /**
     * A fan that is genuinely off is a reading like any other.
     *
     * `FAN_OFF` is zero, which is exactly the value everything else in this codebase has to be
     * careful not to invent — but a machine that says zero has still said something.
     */
    @Test
    fun `a machine reporting off is measured, not unknown`() {
        assertEquals(
            MachineLink.FanReadout.Measured(GlassOsCommands.FAN_OFF),
            readout(reported = GlassOsCommands.FAN_OFF, reportedAt = 100L),
        )
    }

    /**
     * Nothing read and nothing asked is *unknown*, and unknown is not off.
     *
     * The whole failure this readout exists to avoid: a rider glancing up, seeing "Off", and
     * concluding the fan is off when in fact nobody could tell them anything.
     */
    @Test
    fun `no reading and no request is unknown, never off`() {
        assertEquals(MachineLink.FanReadout.Unknown, readout())
    }

    /** A request with no reading behind it is drawn as a request, never as a measurement. */
    @Test
    fun `a request with no reading is requested`() {
        assertEquals(
            MachineLink.FanReadout.Requested(GlassOsCommands.FAN_LOW),
            readout(requested = GlassOsCommands.FAN_LOW, requestedAt = 100L),
        )
    }

    /**
     * A reading taken after the request supersedes it — including when it disagrees.
     *
     * This is the console's own fan button arriving. Stride asked for High, the rider pressed the
     * physical control down to Low, and the next poll is the only thing in the app that will ever
     * see that. The request must not win here or the strip would insist on High indefinitely.
     */
    @Test
    fun `a reading taken after a request wins, even when it disagrees`() {
        assertEquals(
            MachineLink.FanReadout.Measured(GlassOsCommands.FAN_LOW),
            readout(
                reported = GlassOsCommands.FAN_LOW,
                reportedAt = 200L,
                requested = GlassOsCommands.FAN_HIGH,
                requestedAt = 100L,
            ),
        )
    }

    /**
     * A reading taken *before* the request is stale by construction, and must not be drawn
     * confidently.
     *
     * The rider taps High and the snapshot in hand is up to a poll old, so it physically cannot
     * contain the answer. Showing it would put a confident "Low" on screen for a second over a fan
     * that is spinning up. Shown as the request instead until a later reading settles it — no grace
     * timer, because the next poll resolves it either way.
     */
    @Test
    fun `a reading older than the request is shown as the request`() {
        assertEquals(
            MachineLink.FanReadout.Requested(GlassOsCommands.FAN_HIGH),
            readout(
                reported = GlassOsCommands.FAN_LOW,
                reportedAt = 100L,
                requested = GlassOsCommands.FAN_HIGH,
                requestedAt = 200L,
            ),
        )
    }

    @Test
    fun `accepted fan state never overrides available telemetry`() {
        assertEquals(
            MachineLink.FanReadout.Measured(GlassOsCommands.FAN_LOW),
            readout(
                reported = GlassOsCommands.FAN_LOW,
                reportedAt = 100L,
                requested = GlassOsCommands.FAN_HIGH,
                requestedAt = 200L,
                requestPending = false,
            ),
        )
    }

    @Test
    fun `accepted fan state remains useful when telemetry is unavailable`() {
        assertEquals(
            MachineLink.FanReadout.Requested(GlassOsCommands.FAN_HIGH),
            readout(
                requested = GlassOsCommands.FAN_HIGH,
                requestedAt = 200L,
                requestPending = false,
            ),
        )
    }

    @Test
    fun `fan picker highlights a pending request over older telemetry`() {
        val readout = readout(
            reported = GlassOsCommands.FAN_LOW,
            reportedAt = 100L,
            requested = GlassOsCommands.FAN_HIGH,
            requestedAt = 200L,
            requestPending = true,
        )

        assertEquals(GlassOsCommands.FAN_HIGH, MachineLink.fanSelection(readout))
    }

    @Test
    fun `fan picker highlights telemetry over an accepted write`() {
        val readout = readout(
            reported = GlassOsCommands.FAN_LOW,
            reportedAt = 100L,
            requested = GlassOsCommands.FAN_HIGH,
            requestedAt = 200L,
            requestPending = false,
        )

        assertEquals(GlassOsCommands.FAN_LOW, MachineLink.fanSelection(readout))
    }

    @Test
    fun `timed fan telemetry stays paired across pending request ordering`() {
        val pending = MachineCoordinator.FanRequestSnapshot(
            state = GlassOsCommands.FAN_HIGH,
            at = 200L,
            pending = true,
        )

        assertEquals(
            MachineLink.FanReadout.Requested(GlassOsCommands.FAN_HIGH),
            MachineLink.fanReadout(
                MachineLink.FanTelemetry(GlassOsCommands.FAN_LOW, 100L),
                pending,
                knownPresent = true,
            ),
        )
        assertEquals(
            MachineLink.FanReadout.Measured(GlassOsCommands.FAN_LOW),
            MachineLink.fanReadout(
                MachineLink.FanTelemetry(GlassOsCommands.FAN_LOW, 300L),
                pending,
                knownPresent = true,
            ),
        )
    }

    /**
     * A treadmill with no fan gets no fan cell at all — not one reading "Off", and not one reading
     * "Not measured" for the life of the machine.
     */
    @Test
    fun `a machine with no fan shows nothing`() {
        assertEquals(MachineLink.FanReadout.Absent, readout(knownPresent = false))
    }

    /**
     * The trap that makes the presence gate load-bearing rather than decorative.
     *
     * A restore request becomes visible while its write is queued or in flight, before the machine
     * has accepted it. Without the gate that useful in-flight visibility would briefly draw a fan
     * speed on a machine that may have no fan.
     */
    @Test
    fun `a stale request on a fanless machine shows nothing`() {
        assertEquals(
            MachineLink.FanReadout.Absent,
            readout(requested = GlassOsCommands.FAN_HIGH, requestedAt = 100L, knownPresent = false),
        )
    }

    /**
     * A *reading* is shown even when nothing has established the fan is commandable.
     *
     * The machine answering about its fan is itself the proof, and it is a stronger one than
     * `CanWrite`, which also goes false when another client owns the console. A rider whose fan is
     * running should be told so even while Stride cannot change it.
     */
    @Test
    fun `a reading is shown even when the fan is not commandable`() {
        assertEquals(
            MachineLink.FanReadout.Measured(GlassOsCommands.FAN_MEDIUM),
            readout(reported = GlassOsCommands.FAN_MEDIUM, reportedAt = 100L, knownPresent = false),
        )
    }

    /**
     * Auto survives to the readout.
     *
     * It is not a level and has no number on a 0..3 scale, which is why `fanLevel` drops it. The
     * readout names states rather than plotting them, so it can say the true thing: the machine is
     * choosing.
     */
    @Test
    fun `auto is a state the readout can name`() {
        assertEquals(
            MachineLink.FanReadout.Measured(GlassOsCommands.FAN_AUTO),
            readout(reported = GlassOsCommands.FAN_AUTO, reportedAt = 100L),
        )
        assertEquals("Auto", GlassOsCommands.fanStateName(GlassOsCommands.FAN_AUTO))
    }

    /**
     * A fan change the rider never made still reaches the readout.
     *
     * `MachineCoordinator.stopFan` shuts the fan off at the end of a workout (#29), so the state can
     * now diverge from what is on screen with no user action at all — which is exactly when a stale
     * readout is least likely to be questioned. The stamp on the request carries it immediately, and
     * the next reading confirms it.
     */
    @Test
    fun `an automatic fan off is picked up without waiting for a poll`() {
        assertEquals(
            MachineLink.FanReadout.Requested(GlassOsCommands.FAN_OFF),
            readout(
                reported = GlassOsCommands.FAN_HIGH,
                reportedAt = 100L,
                requested = GlassOsCommands.FAN_OFF,
                requestedAt = 200L,
            ),
        )
        assertEquals(
            MachineLink.FanReadout.Measured(GlassOsCommands.FAN_OFF),
            readout(
                reported = GlassOsCommands.FAN_OFF,
                reportedAt = 300L,
                requested = GlassOsCommands.FAN_OFF,
                requestedAt = 200L,
            ),
        )
    }

    /**
     * A refused automatic fan off does not become "Off" on screen.
     *
     * `stopFan` records its state only on an `Ok`, so a refusal leaves the request where it was —
     * and the reading, which is the thing that actually knows, keeps the strip honest about a fan
     * that is still running.
     */
    @Test
    fun `a fan that kept running is still reported as running`() {
        assertEquals(
            MachineLink.FanReadout.Measured(GlassOsCommands.FAN_HIGH),
            readout(
                reported = GlassOsCommands.FAN_HIGH,
                reportedAt = 300L,
                requested = GlassOsCommands.FAN_HIGH,
                requestedAt = 100L,
            ),
        )
    }
}

/**
 * The two fan numbers a snapshot carries, and why only one of them is stored.
 *
 * `fanLevel` is a 0..`FAN_MAX` scale position and `fanState` is a named state, so `AUTO` has a
 * value in one and none in the other. They used to be capable of disagreeing; `fanLevel` is now
 * derived, which is what makes that impossible rather than merely unlikely.
 */
class SnapshotFanLevelTest {

    private fun snapshot(fanState: Int?) = GlassOsClient.Snapshot(
        consoleState = null,
        workoutId = null,
        speedMph = null,
        inclinePercent = null,
        distanceMiles = null,
        paceMinPerMile = null,
        elapsedSeconds = null,
        calories = null,
        speedWritable = null,
        inclineWritable = null,
        fanWritable = null,
        fanState = fanState,
    )

    @Test
    fun `the four speeds carry their scale position`() {
        assertEquals(0, snapshot(GlassOsCommands.FAN_OFF).fanLevel)
        assertEquals(1, snapshot(GlassOsCommands.FAN_LOW).fanLevel)
        assertEquals(2, snapshot(GlassOsCommands.FAN_MEDIUM).fanLevel)
        assertEquals(MachineLink.FAN_MAX, snapshot(GlassOsCommands.FAN_HIGH).fanLevel)
    }

    /**
     * Auto has no level, and drawing it as one would be a lie in the dangerous direction: `0` on a
     * scale reads as "off" over a fan that is about to spin up on its own.
     */
    @Test
    fun `auto has no level`() {
        assertEquals(null, snapshot(GlassOsCommands.FAN_AUTO).fanLevel)
    }

    @Test
    fun `an unknown fan state has no level`() {
        assertEquals(null, snapshot(null).fanLevel)
    }

    /** Every named state either has a level in range or has none. Nothing lands off the scale. */
    @Test
    fun `no state produces a level outside the scale`() {
        listOf(
            GlassOsCommands.FAN_OFF,
            GlassOsCommands.FAN_LOW,
            GlassOsCommands.FAN_MEDIUM,
            GlassOsCommands.FAN_HIGH,
            GlassOsCommands.FAN_AUTO,
        ).forEach { state ->
            val level = snapshot(state).fanLevel
            assertTrue("$state produced $level", level == null || level in 0..MachineLink.FAN_MAX)
        }
    }
}
