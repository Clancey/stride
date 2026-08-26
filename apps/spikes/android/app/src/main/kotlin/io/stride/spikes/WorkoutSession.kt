package io.stride.spikes

import android.os.SystemClock
import android.util.Log

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

    private const val TAG = "WorkoutSession"

    /**
     * IDLE → STARTING → RUNNING ⇄ PAUSED → STOPPING → IDLE.
     *
     * [STARTING] is the rider's intent, recorded before the machine has agreed to it. It exists
     * because Stride used to go straight to RUNNING and start counting the moment the button was
     * pressed, which on a console that took ten seconds to answer produced a screen that said
     * "Pause workout" over a stationary belt and then banked ten seconds of standing still as
     * exercise. Neither half of that is something the app is entitled to claim.
     *
     * The clock does not run in STARTING. That is the whole point of the state: it is honest about
     * having asked and not yet been answered.
     *
     * [STOPPING] is the same honesty at the other end, and it is issue #39. [stop] used to publish
     * IDLE — and notify every listener — *before* the coupling had so much as called
     * `MachineCoordinator.stop()`, so the app showed "Start workout" over a belt nothing had asked
     * to stop yet, let alone confirmed stopped. A stop is done on ack **plus** observed
     * deceleration (`docs/PLAN.md` §5.4), and STOPPING is the state that exists while neither has
     * arrived.
     *
     * **STOPPING cannot delay a stop.** It is published on the way *into* the same listener
     * notification that issues the stop command, on the same thread, in the same call. It is a
     * label applied as the stop goes past, never a gate the stop waits behind.
     *
     * [abandon] deliberately does **not** pass through it. An abandon is the retry path — a start
     * that was refused, cancelled, or never answered — and holding it in STOPPING would lock the
     * rider out of pressing Start again for as long as a confirmation takes, on the one screen
     * where they are standing on a treadmill waiting to hear whether their start worked.
     */
    enum class State { IDLE, STARTING, RUNNING, PAUSED, STOPPING }

    /**
     * Why a session returned to [State.IDLE].
     *
     * The state machine used to collapse these two, and that was the gap behind issue #29: a
     * treadmill needs to tell "the rider is done" from "the machine never started", and both land
     * in IDLE. [State] alone cannot express the difference, so anything that wants to act on a
     * *definitive* end — shutting the fan off, re-asserting zero — had no way to ask.
     *
     * Note what is deliberately absent: a pause. A pause is [State.PAUSED], it is resumable, and it
     * must never be mistaken for either of these. Keeping the resumable case out of this enum
     * entirely is what makes that mistake unrepresentable rather than merely unlikely.
     */
    enum class Ending {
        /** [stop]: the rider ended a workout. Not resumable, and the goal is cleared. */
        ENDED,

        /** [abandon]: a start that never began. The rider is expected to try again. */
        ABANDONED,
    }

    /**
     * Notified on every transition, synchronously, on the thread that made it.
     *
     * [ending] is non-null only on a transition that ends a session — into [State.STOPPING] for an
     * end, and into [State.IDLE] for an abandon or for the settle that follows a stop — and it is
     * passed as an argument rather than read back off this object on purpose. A listener is free to
     * make its own transition while it is being notified, and a shared field would then be
     * overwritten under the listeners that had not run yet — which on this path means the machine
     * coupling reading "abandoned" for a workout the rider ended, and silently skipping the
     * end-of-workout writes. An argument cannot be clobbered by anybody.
     */
    fun interface Listener {
        fun onTransition(next: State, ending: Ending?)
    }

    private val listeners = mutableListOf<Listener>()

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
     *
     * **Returns whether it actually started the clock**, which callers need rather than merely
     * find convenient. A start is answered asynchronously, so the rider may have cancelled — or the
     * watchdog given up — between the answer being judged and this being called. Acting on the
     * answer regardless is how a fan came on for a workout that had already been abandoned, behind
     * the stop that was sent on the way out.
     */
    @Synchronized
    fun confirmStart(): Boolean {
        if (state != State.STARTING) return false
        runningSinceMs = SystemClock.elapsedRealtime()
        transition(State.RUNNING)
        return true
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
     *
     * Lands in [State.STOPPING], not IDLE. The belt has been asked to stop and nothing has yet
     * confirmed it did; publishing IDLE here is what issue #39 is about, because IDLE is the state
     * the whole app reads as "no workout, offer Start". [settle] is the only way out.
     *
     * Pressing End again from STOPPING deliberately re-enters STOPPING rather than no-oping, which
     * re-notifies the listeners and so sends the belt another stop. A rider pressing a stop control
     * twice means it harder, not less.
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
        transition(State.STOPPING, Ending.ENDED)
        return total
    }

    /**
     * The stop has settled, one way or the other. Return to IDLE.
     *
     * Called with the verdict rather than deciding it: whether the belt is confirmed at rest is a
     * question about a machine, and this class is a clock. **The session goes IDLE either way** —
     * there is nothing left for it to do, and holding it in STOPPING would be a state a rider could
     * not get out of. What keeps the app from cheerfully offering to start a belt it could not
     * confirm stopped is [StopEscalation]'s latch, not this.
     *
     * No-op unless STOPPING, so a late verdict from a stop the rider has already followed with
     * another action cannot drag a live session to idle.
     */
    @Synchronized
    fun settle() {
        if (state != State.STOPPING) return
        transition(State.IDLE, Ending.ENDED)
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
        transition(State.IDLE, Ending.ABANDONED)
    }

    @Synchronized
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Listener) {
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

    private fun transition(next: State, ending: Ending? = null) {
        state = next
        // Copy before notifying: a listener that removes itself would otherwise mutate the list
        // mid-iteration, and the media coupling does exactly that on teardown.
        //
        // Guarded individually, which is load-bearing rather than tidy. A single listener throwing
        // used to abort the whole loop, and the machine coupling is registered *last* — so a media
        // controller that went away, or an overlay view that had been torn down, could take out the
        // one listener whose job is to tell the belt to stop. A failure to update a screen must
        // never cost a treadmill its stop command.
        listeners.toList().forEach {
            try {
                it.onTransition(next, ending)
            } catch (t: Throwable) {
                Log.w(TAG, "A workout listener failed on the way to $next.", t)
            }
        }
    }
}
