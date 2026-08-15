package io.stride.spikes

import android.os.SystemClock

/**
 * The workout state Stride genuinely owns, as opposed to [MachineLink], which owns what the machine
 * knows.
 *
 * The distinction matters. Stride cannot see the belt, so it cannot report speed or distance. It
 * *can* honestly report how long the user has been working out, because it is the thing being asked
 * to start and stop. Elapsed time is therefore real in Phase 0 and is drawn as a number; everything
 * physical stays [MachineLink.NO_READING] until the machine link exists.
 *
 * A session here is a *user intent*, not a machine state. Starting a workout in Stride does not
 * start the belt and never will from this class — it starts our timer and, through the media
 * coupling, tells whatever is playing what to do. Pausing means "I stepped off"; it does not and
 * must not imply the belt has stopped. Wording in the UI has to keep that distinction visible,
 * because on a treadmill the gap between "the app paused" and "the belt stopped" is the whole
 * safety story.
 *
 * Deliberately a singleton rather than Flutter state: the overlay is a Kotlin foreground service
 * that outlives the Flutter engine and must keep counting while the user is inside Netflix, and
 * while Flutter is backgrounded or dead.
 *
 * Time comes from [SystemClock.elapsedRealtime] so a wall-clock change, timezone shift, or NTP
 * correction mid-run cannot make a workout appear to run backwards or jump.
 */
object WorkoutSession {

    /**
     * IDLE → STARTING → RUNNING ⇄ PAUSED → IDLE.
     *
     * [STARTING] is the rider's intent, recorded before the machine has agreed to it. It exists
     * because Stride used to go straight to RUNNING and start counting the moment the button was
     * pressed, which on a console that took ten seconds to answer produced a screen that said
     * "Pause workout" over a stationary belt and then banked ten seconds of standing still as
     * exercise. Neither half of that is something the app is entitled to claim.
     *
     * The clock does not run in STARTING. That is the whole point of the state: it is honest about
     * having asked and not yet been answered.
     */
    enum class State { IDLE, STARTING, RUNNING, PAUSED }

    private val listeners = mutableListOf<(State) -> Unit>()

    @Volatile
    var state: State = State.IDLE
        private set

    /** Accumulated time from previously completed RUNNING stretches. */
    private var accumulatedMs: Long = 0L

    /** When the current RUNNING stretch began, or 0 when not running. */
    private var runningSinceMs: Long = 0L

    /** Total time spent RUNNING this session, excluding paused stretches. */
    @Synchronized
    fun elapsedMs(): Long =
        if (state == State.RUNNING) accumulatedMs + (SystemClock.elapsedRealtime() - runningSinceMs)
        else accumulatedMs

    /**
     * Ask for a fresh session. No-op if one is already in progress.
     *
     * Lands in [STARTING], not RUNNING: the rider has asked, and nothing has agreed yet. The clock
     * begins at [confirmStart], so the first second counted is a second the belt was actually
     * turning.
     */
    @Synchronized
    fun start() {
        if (state != State.IDLE) return
        accumulatedMs = 0L
        runningSinceMs = 0L
        transition(State.STARTING)
    }

    /**
     * The machine agreed. Begin counting, from this instant.
     *
     * Separate from [start] so the gap between asking and being answered belongs to nobody: it is
     * not exercise, and it is not paused time either. A console that takes a moment to spin up
     * costs the rider nothing.
     */
    @Synchronized
    fun confirmStart() {
        if (state != State.STARTING) return
        runningSinceMs = SystemClock.elapsedRealtime()
        transition(State.RUNNING)
    }

    /** Banks the current stretch and holds. No-op unless RUNNING. */
    @Synchronized
    fun pause() {
        if (state != State.RUNNING) return
        accumulatedMs += SystemClock.elapsedRealtime() - runningSinceMs
        runningSinceMs = 0L
        transition(State.PAUSED)
    }

    /** Continues a paused session, keeping banked time. No-op unless PAUSED. */
    @Synchronized
    fun resume() {
        if (state != State.PAUSED) return
        runningSinceMs = SystemClock.elapsedRealtime()
        transition(State.RUNNING)
    }

    /**
     * Ends the session and returns its total duration, so a caller can record a summary before the
     * counters reset. Returns 0 and does nothing when already IDLE.
     */
    @Synchronized
    fun stop(): Long {
        if (state == State.IDLE) return 0L
        val total = elapsedMs()
        accumulatedMs = 0L
        runningSinceMs = 0L
        // The goal belongs to the workout, not to the app. Carrying it into the next session
        // silently would hand the rider a target they never chose this time.
        WorkoutGoal.clear()
        transition(State.IDLE)
        return total
    }

    /**
     * Give up a session that never really began, and return to IDLE.
     *
     * Distinct from [stop] in the one way that matters to the rider: the goal survives. A start the
     * machine refused is not a workout they completed, and wiping the target they set moments
     * earlier would punish them for the treadmill's failure. The banked time is discarded, because
     * there was nothing to bank.
     *
     * Listeners still fire, and the machine coupling still sends its stop. That is deliberate. The
     * refusal we are reacting to may be a reply that was lost rather than a command that never
     * landed, and a belt that might be moving must be told to stop either way.
     */
    @Synchronized
    fun abandon() {
        if (state == State.IDLE) return
        accumulatedMs = 0L
        runningSinceMs = 0L
        transition(State.IDLE)
    }

    @Synchronized
    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Formats elapsed time for display. `H:MM:SS` past an hour, `MM:SS` below it, so the common case
     * stays readable at arm's length instead of padding every run to look like an ultramarathon.
     */
    fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }

    private fun transition(next: State) {
        state = next
        // Copy before notifying: a listener that removes itself would otherwise mutate the list
        // mid-iteration, and the media coupling does exactly that on teardown.
        listeners.toList().forEach { it(next) }
    }
}
