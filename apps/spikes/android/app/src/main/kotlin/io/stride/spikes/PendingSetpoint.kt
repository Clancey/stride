package io.stride.spikes

import kotlin.math.abs

/**
 * The value the rider just asked for, held only for as long as it is still plausibly on its way.
 *
 * A treadmill does not jump to 5 mph because someone tapped "5". The coordinator ramps the command,
 * and the belt then takes seconds more to physically get there, so telemetry keeps reporting 2.5
 * long after the rider chose. Marking nothing but the measured value in that window makes the tap
 * look ignored; marking the request *as if measured* is the dishonesty [MachineLink] exists to
 * prevent. So the request is remembered here as a separate, clearly-labelled fact, and it is dropped
 * the moment it stops being true.
 *
 * It is dropped when:
 *
 * - **The machine arrives.** Measured is within [tolerance] of the target; the derived value now
 *   says the same thing and there is nothing left to promise.
 * - **The machine goes the other way.** Measured moved *away* from the target — the rider slowed it
 *   from the console, a workout ended, or the command was refused. A request the machine is visibly
 *   not honouring must not stay on screen.
 * - **Nothing happens for a while.** [graceMs] passes with no progress toward the target.
 *
 * Progress *toward* the target extends the grace rather than consuming it, which is what makes a
 * long climb work: a 2 → 8 mph ramp takes far longer than any fixed timeout worth using for a
 * command that was simply ignored, but it produces a steady stream of closing readings.
 *
 * Pure and clock-injected so the whole policy is testable without a machine or a UI.
 */
class PendingSetpoint(
    private val tolerance: Double,
    private val graceMs: Long,
    private val epsilon: Double = 0.05,
) {

    /** The value the rider asked for, or null when there is nothing outstanding. */
    var target: Double? = null
        private set

    /** The label the rider tapped, carried through so the UI marks the exact rung they touched. */
    var label: String? = null
        private set

    private var deadlineMs: Long = 0L
    private var lastMeasured: Double? = null

    /** Record a request. Replaces any previous one; the newest tap is the one the rider means. */
    fun request(value: Double, label: String, nowMs: Long, measured: Double?) {
        target = value
        this.label = label
        deadlineMs = nowMs + graceMs
        lastMeasured = measured
        // Asking for what the machine already reports is not a promise about the future.
        if (measured != null && abs(measured - value) <= tolerance) clear()
    }

    fun clear() {
        target = null
        label = null
        deadlineMs = 0L
        lastMeasured = null
    }

    /**
     * Fold in a fresh reading and return the request that is still outstanding, if any.
     *
     * [measured] may be null — telemetry can lapse — and that neither confirms nor refutes the
     * request, so it only runs the clock down.
     */
    fun observe(measured: Double?, nowMs: Long): Double? {
        val goal = target ?: return null
        if (measured == null) {
            if (nowMs >= deadlineMs) clear()
            return target
        }
        val distance = abs(measured - goal)
        if (distance <= tolerance) {
            clear()
            return null
        }
        val previous = lastMeasured
        lastMeasured = measured
        if (previous != null) {
            val was = abs(previous - goal)
            when {
                distance < was - epsilon -> deadlineMs = nowMs + graceMs
                distance > was + epsilon -> {
                    clear()
                    return null
                }
            }
        }
        if (nowMs >= deadlineMs) {
            clear()
            return null
        }
        return target
    }
}
