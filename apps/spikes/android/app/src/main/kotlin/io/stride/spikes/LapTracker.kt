package io.stride.spikes

import kotlin.math.floor

/**
 * Turns the machine's cumulative distance reading into a position around one lap of the track.
 *
 * This exists as its own object, away from the overlay, for two reasons. It is the piece the track
 * floor is actually *about* — where the rider is on the loop — and it is pure arithmetic over a
 * reading that can go missing, which is exactly the shape of thing that should be tested on the JVM
 * rather than by squinting at a treadmill.
 *
 * ### Why a reading that goes missing is the interesting case
 *
 * [MachineLink] hands out `null` for anything older than its freshness window, so a single dropped
 * poll turns a perfectly good distance into "unknown" for a second. Reading that as *zero* — which
 * is what the first version of the track floor did — teleports the marker back to the start line
 * and then back out again, several times a workout, and makes the whole surface look broken.
 *
 * Holding the last position through a short gap is not a fabricated reading: the claim is only that
 * the rider was last seen there, which is true, and the marker is not moving while we make it. Past
 * [holdMs] the gap stops being a dropped poll and starts being a machine we are no longer talking
 * to, and the answer becomes null so the caller can draw an empty track rather than a stale one.
 * Distance is never integrated from speed here — see docs/DIRECT_MACHINE_PROTOCOL.md; the machine
 * owns that number.
 */
class LapTracker(
    private val lapMiles: Double,
    private val holdMs: Long = DEFAULT_HOLD_MS,
) {

    companion object {
        /**
         * How long a known position survives without a fresh distance reading.
         *
         * Comfortably longer than [MachineLink]'s freshness window plus a poll, so an ordinary
         * hiccup is invisible, and short enough that a machine that has actually gone away clears
         * the marker while the rider is still looking at the screen.
         */
        const val DEFAULT_HOLD_MS = 12_000L
    }

    /** Where the rider is: [progress] around the current lap, and which [lap] that is. */
    data class Position(val progress: Float, val lap: Int)

    private var held: Position? = null
    private var heldAtMs: Long = 0L

    /**
     * The position implied by [distanceMiles], or the last one if the reading is briefly missing,
     * or null when there is nothing honest to draw.
     */
    fun sample(distanceMiles: Double?, nowMs: Long): Position? {
        if (lapMiles > 0.0 && distanceMiles != null && distanceMiles >= 0.0 && distanceMiles.isFinite()) {
            val laps = distanceMiles / lapMiles
            val completed = floor(laps)
            // A rider standing exactly on a lap boundary is at the *start* of the next lap, not the
            // end of the one behind them, which is why the lap number is the count plus one.
            val position = Position(
                progress = (laps - completed).toFloat().coerceIn(0f, 1f),
                lap = (completed.toLong() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
            held = position
            heldAtMs = nowMs
            return position
        }
        val last = held ?: return null
        if (nowMs - heldAtMs in 0..holdMs) return last
        held = null
        return null
    }

    /** Forget the held position. Called when the session changes and the old one means nothing. */
    fun reset() {
        held = null
        heldAtMs = 0L
    }
}
