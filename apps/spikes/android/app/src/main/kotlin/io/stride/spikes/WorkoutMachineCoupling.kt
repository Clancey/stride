package io.stride.spikes

import android.util.Log

/**
 * Makes Stride's workout controls drive the actual belt.
 *
 * [WorkoutSession] is a pure timer and stays that way: it knows about elapsed time and nothing about
 * treadmills. This listens to its transitions and issues the matching machine command, the same
 * shape as [WorkoutMediaCoupling], so pressing pause stops the belt *and* the music *and* the clock
 * without any of those three knowing about the others.
 *
 * Every command goes through [MachineCoordinator]. Nothing here talks to the machine directly.
 *
 * One asymmetry is deliberate. Starting the timer starts the belt, but the belt reaching a stop is
 * confirmed by telemetry rather than by this class, because a stop that was merely *sent* is not a
 * stop. See the safety-key notice in [MachineLink].
 */
object WorkoutMachineCoupling {

    private const val TAG = "WorkoutMachineCoupling"

    @Volatile
    private var attached = false

    private var lastState = WorkoutSession.state

    @Synchronized
    fun attach() {
        if (attached) return
        lastState = WorkoutSession.state
        WorkoutSession.addListener(::onTransition)
        attached = true
    }

    /**
     * Put the fan back the way the rider left it, at the moment a workout begins.
     *
     * The rider should not have to re-dial the fan every session. Restoring on start rather than on
     * app launch is deliberate: a fan that switches itself on when the launcher happens to boot is
     * a machine acting on its own, while one that comes up as the belt does is the workout starting.
     *
     * A first run has no remembered setting. Auto is preferred there when the machine supports it,
     * because matching the fan to effort is a better default than any fixed speed we could guess;
     * otherwise it stays off and the rider chooses.
     */
    private fun restoreFan() {
        MachineCoordinator.restoreFan(StrideSettings.fanState)
    }

    private fun onTransition(next: WorkoutSession.State) {
        val previous = synchronized(this) {
            val state = lastState
            lastState = next
            state
        }
        try {
            when {
                // Stop first and unconditionally. If the rider ends a workout we do not care what
                // state we thought we were in; the belt must be told to stop.
                next == WorkoutSession.State.IDLE && previous != WorkoutSession.State.IDLE ->
                    MachineCoordinator.stop()

                previous == WorkoutSession.State.IDLE && next == WorkoutSession.State.RUNNING -> {
                    MachineCoordinator.startWorkout()
                    restoreFan()
                }

                previous == WorkoutSession.State.RUNNING && next == WorkoutSession.State.PAUSED ->
                    MachineCoordinator.pause()

                previous == WorkoutSession.State.PAUSED && next == WorkoutSession.State.RUNNING ->
                    MachineCoordinator.resume()
            }
        } catch (t: Throwable) {
            // A failure to command must never take down the timer or the overlay. The rider still
            // needs the clock and, more importantly, still needs the UI responsive.
            Log.w(TAG, "Machine coupling skipped after a workout state change.", t)
        }
    }
}
