package io.stride.spikes

import android.os.Handler
import android.os.Looper
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

    /**
     * How long a start may sit unanswered before we stop showing "Starting…" and give up.
     *
     * Sized to be longer than any answer we expect, not as a guess at how long a start takes: the
     * console's own command timeout is 12s, and a start can spend one of those on the handshake
     * before it spends another on the command itself. Anything past that is not slow, it is stuck.
     *
     * The point is not to be clever about the deadline. It is that there has to be one. Every path
     * out of [WorkoutSession.State.STARTING] runs on an answer arriving, and an answer that never
     * arrives would otherwise leave a rider looking at a disabled button with no way back.
     */
    private const val START_TIMEOUT_MS = 30_000L

    private val watchdog = Handler(Looper.getMainLooper())

    /**
     * The pending expiry for the current attempt, if any.
     *
     * Held so it can be cancelled individually. Clearing the handler wholesale looked equivalent
     * and was not: a late answer to an abandoned attempt would remove the *newer* attempt's
     * watchdog on its way out, leaving the one state in the app that has no other way out of
     * "Starting…" with nothing watching it.
     */
    private var pendingWatchdog: Runnable? = null

    @Volatile
    private var attached = false

    private var lastState = WorkoutSession.state

    /**
     * The last definite answer the console gave about its own belt, or null when it has not given
     * one this session.
     *
     * Kept so [observeConsole] can work on *edges* rather than levels. That distinction is the whole
     * safety argument for this feature: a level test ("the console says it is not moving, so pause")
     * fires during the gap between Stride confirming a start and the console actually reaching
     * WORKOUT, and would pause every workout a second after it began. An edge only fires when the
     * console has told us it was moving and has since told us it is not, which is exactly what
     * pressing Stop on the machine looks like from here.
     */
    private var beltMoving: Boolean? = null

    /**
     * Set while *this thread* is making a transition that follows the machine rather than driving
     * it.
     *
     * Without it, adopting the console's own pause would send a pause command straight back to the
     * console that just paused itself. Safe on this machine and pointless on any — but it also
     * inverts the meaning of the coupling, which exists to make Stride's controls move the belt, not
     * to re-issue the belt's own decisions at it.
     *
     * Thread-local, and that is load-bearing rather than tidy. A plain flag was wrong the moment
     * this class started making transitions from the machine poll thread: [adopt] must set the flag
     * before `WorkoutSession.pause()` can take WorkoutSession's monitor, so a rider tapping Pause on
     * the main thread at the same instant would run *their* listeners while the poll thread sat on
     * that monitor with the flag raised — and [onTransition] would read it and quietly drop the
     * command that stops the belt. The same race against `IDLE → STARTING` would skip both the start
     * and its watchdog, wedging the session in "Starting…" with nothing left to release it.
     *
     * `WorkoutSession.transition` notifies listeners synchronously on the calling thread, so a
     * thread-confined flag suppresses exactly the adopting thread's own transition and no other.
     */
    private val adopting = ThreadLocal.withInitial { false }

    /**
     * Which start attempt is current.
     *
     * A start is answered asynchronously, and by the time the answer lands the rider may have
     * paused, ended, or started again. Reverting on a stale answer would tear down a session they
     * are already using, so each attempt carries a token and only the newest one may roll back.
     */
    private var startToken = 0

    /**
     * Try to start again, after a start was refused.
     *
     * Nothing clever: the same path as the Start button. It exists because the alternative to
     * offering a retry is deciding on the rider's behalf that their machine is unusable, and this
     * app is in no position to make that call — a console can drop its link to the lower board and
     * pick it back up, and the only way to find out is to ask it again.
     */
    fun retryStart() {
        WorkoutSession.start()
    }

    @Synchronized
    fun attach() {
        if (attached) return
        lastState = WorkoutSession.state
        beltMoving = null
        WorkoutSession.addListener(::onTransition)
        attached = true
    }

    /**
     * Follow the console when the rider drives it from the machine's own buttons.
     *
     * Stride is not the only thing that can stop this belt. The console has a Stop button under the
     * rider's hand, and until now pressing it left the overlay counting a workout that had visibly
     * ended — "Pause workout" over a stationary belt, which is the same wrong screen the start
     * handshake was reworked to get rid of, arrived at from the other direction.
     *
     * Called from the machine poll with whatever the console last reported, so it sees every state
     * change within a poll interval. [GlassOsClient.ConsoleState.beltMayBeMoving] is the authority
     * on what a state name means, and its **null** — a state this build does not recognise, or none
     * reported at all — is ignored rather than folded into either answer. Guessing there would
     * either pause a running workout or claim a stopped one is still going.
     *
     * Deliberately does not start a session that Stride never began. Adopting a workout the rider
     * started on the console is a larger question — whose goal, whose clock, whose media — and
     * quietly beginning one from a poll is not the way to answer it.
     */
    fun observeConsole(consoleState: String?) {
        if (!attached) return
        // DISCONNECTED is not "the belt is stopped" — it is "the head unit cannot see the lower
        // board", which is a statement about the link and not about the machine. It arrives on a
        // *successful* read (proto3 omits the zero, so GlassOsClient.read substitutes it), and it
        // comes and goes under a live session: MachineLink's own poll treats it as transient and
        // fires a reconnect on it.
        //
        // Read as an edge it would be the worst possible false positive. A console that blinked
        // would pause the rider's workout and their media, stop the clock, and — because an adopted
        // transition is deliberately not sent back to the machine — do all of that over a belt
        // nothing has told to stop, under a button now reading "Resume workout". That is the exact
        // screen this whole feature exists to prevent, reached from a third direction.
        //
        // So it is grouped with the states we cannot read: forget what was known, and let the next
        // reading start a fresh edge rather than manufacture one across a gap we could not see.
        if (consoleState == GlassOsClient.ConsoleState.DISCONNECTED_NAME) {
            forgetConsole()
            return
        }
        val moving = GlassOsClient.ConsoleState.beltMayBeMoving(consoleState) ?: return
        val previous = synchronized(this) {
            val was = beltMoving
            beltMoving = moving
            was
        }
        try {
            when (consoleFollowUp(previous, moving, WorkoutSession.state)) {
                ConsoleFollowUp.NOTHING -> return

                ConsoleFollowUp.PAUSE -> {
                    Log.i(TAG, "console stopped the belt; pausing the session to match")
                    adopt { WorkoutSession.pause() }
                }

                ConsoleFollowUp.RESUME -> {
                    Log.i(TAG, "console restarted the belt; resuming the session to match")
                    adopt { WorkoutSession.resume() }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not follow the console's own workout state.", t)
        }
    }

    /** Forget what the console was doing, so the next reading starts a fresh edge. */
    fun forgetConsole() {
        synchronized(this) { beltMoving = null }
    }

    /**
     * Make a transition that follows the machine, without sending it back at the machine.
     *
     * The flag is cleared in a `finally` because a listener throwing on the way through would
     * otherwise leave every later pause and stop silently uncommanded — a coupling that has stopped
     * commanding the belt is the one failure here that matters.
     */
    private fun adopt(change: () -> Unit) {
        adopting.set(true)
        try {
            change()
        } finally {
            adopting.set(false)
        }
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

    /**
     * What to do once the machine has answered a start.
     *
     * Stride's clock used to start regardless of the answer. On a console that had lost its link to
     * the treadmill that produced the worst screen in the app: "Pause workout" over a stationary
     * belt, the side rails dim because the machine was refusing setpoints, and the only trace of
     * the refusal in a log line nobody standing on a treadmill can read. The clock now starts on
     * the answer, so it can only ever run while the belt was actually told to move.
     *
     * A refusal is reported, not just swallowed, because the rider's next move depends on which one
     * it was — a console with no machine attached is not something tapping Start again will fix.
     */
    private fun onStartSettled(token: Int, outcome: MachineCoordinator.Outcome) {
        // Sampled together, under the lock that guards the token, so the answer cannot be judged
        // against one attempt's identity and then applied to another's state.
        val (stale, state) = synchronized(this) { (token != startToken) to WorkoutSession.state }
        when (startSettlement(outcome, stale, state)) {
            StartSettlement.IGNORE -> return

            StartSettlement.STAND_DOWN -> clearWatchdog(token)

            StartSettlement.CONFIRM -> {
                clearWatchdog(token)
                // The belt is moving, so this is the instant the workout began. Everything
                // downstream — the clock, the goal, the media coupling — hangs off this transition.
                WorkoutSession.confirmStart()
                restoreFan()
            }

            StartSettlement.ABANDON -> {
                clearWatchdog(token)
                val detail = when (outcome) {
                    is MachineCoordinator.Outcome.Rejected -> outcome.reason
                    is MachineCoordinator.Outcome.Failed -> outcome.reason
                    else -> ""
                }
                Log.w(TAG, "start refused by the machine; returning to idle: $detail")
                WorkoutSession.abandon()
                OverlayService.reportStartRefused(detail)
            }
        }
    }

    /**
     * Arm the escape hatch for a start that may never be answered.
     *
     * On expiry this abandons rather than waits, which sends the coupling's stop on the way out —
     * so if the start does land afterwards, the belt is told to stop right behind it. That ordering
     * is the reason giving up is safe: the worst case is a machine that briefly starts and stops,
     * not one that runs under a screen that has forgotten about it.
     */
    private fun armWatchdog(token: Int) {
        val expiry = Runnable { onStartTimedOut(token) }
        synchronized(this) {
            pendingWatchdog?.let { watchdog.removeCallbacks(it) }
            pendingWatchdog = expiry
        }
        watchdog.postDelayed(expiry, START_TIMEOUT_MS)
    }

    /**
     * Disarm the watchdog, but only if it still belongs to [token].
     *
     * The token check is the whole point: a late answer must not take down the guard on an attempt
     * that came after it.
     */
    private fun clearWatchdog(token: Int) {
        val expiry = synchronized(this) {
            if (token != startToken) return
            pendingWatchdog.also { pendingWatchdog = null }
        }
        expiry?.let { watchdog.removeCallbacks(it) }
    }

    private fun onStartTimedOut(token: Int) {
        val stale = synchronized(this) { token != startToken }
        if (stale || WorkoutSession.state != WorkoutSession.State.STARTING) return
        Log.w(TAG, "start was never answered after ${START_TIMEOUT_MS}ms; returning to idle")
        WorkoutSession.abandon()
        OverlayService.reportStartRefused("The treadmill did not answer.")
    }

    private fun onTransition(next: WorkoutSession.State) {
        val previous = synchronized(this) {
            val state = lastState
            lastState = next
            state
        }
        // A session ending is the end of what the console was doing too, whoever ended it. Leaving
        // the old reading in place would let the *next* workout inherit this one's edge and pause
        // itself the first time the console reported anything.
        if (next == WorkoutSession.State.IDLE) forgetConsole()
        // Following the machine, not driving it. The pause below would otherwise be sent to the
        // console that has just paused itself. The stop on the way to IDLE is deliberately *not*
        // suppressed — nothing here adopts an end, so any IDLE transition is the rider's, and a
        // belt that might be moving is always told to stop.
        if (adopting.get() && next != WorkoutSession.State.IDLE) return
        try {
            when {
                // Stop first and unconditionally. If the rider ends a workout we do not care what
                // state we thought we were in; the belt must be told to stop. This covers an
                // abandoned start too: a refusal can be a reply that was lost rather than a command
                // that never landed, so a belt that might be moving still gets told to stop.
                next == WorkoutSession.State.IDLE && previous != WorkoutSession.State.IDLE -> {
                    // Retire the attempt before anything else. Any answer still in flight belongs
                    // to a start the rider has left behind, and the token is what tells the two
                    // apart — without this bump a slow reply could land after a retry had begun and
                    // be mistaken for that newer attempt's answer.
                    val retired = synchronized(this) {
                        ++startToken
                        pendingWatchdog.also { pendingWatchdog = null }
                    }
                    retired?.let { watchdog.removeCallbacks(it) }
                    MachineCoordinator.stop()
                }

                previous == WorkoutSession.State.IDLE &&
                    next == WorkoutSession.State.STARTING -> {
                    val token = synchronized(this) { ++startToken }
                    armWatchdog(token)
                    MachineCoordinator.startWorkout { outcome -> onStartSettled(token, outcome) }
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

/** What to do with a start the machine has answered. */
internal enum class StartSettlement {
    /** Not ours to act on. Leave every bit of state alone. */
    IGNORE,

    /** Ours, but there is nothing left to do beyond disarming the watchdog. */
    STAND_DOWN,

    /** The belt is moving. Start the clock. */
    CONFIRM,

    /** The machine did not start. Return to idle and tell the rider. */
    ABANDON,
}

/**
 * Decide what a settled start means, given the answer, whether it is stale, and where the session
 * is now.
 *
 * Pulled out as a pure function because this is the lockout-critical decision in the app: it is the
 * only thing standing between a rider and a screen stuck on "Starting…", and every branch of it
 * has to be checkable without a treadmill. The two easy mistakes it exists to pin are acting on an
 * answer that belongs to a previous attempt, and returning [IGNORE] for something that is ours —
 * the latter would leave the watchdog armed as the only way out.
 */
internal fun startSettlement(
    outcome: MachineCoordinator.Outcome,
    stale: Boolean,
    state: WorkoutSession.State,
): StartSettlement {
    // A stale answer belongs to an attempt the rider has already moved past. Acting on it would
    // tear down a session they are using, and even standing down would disarm the newer attempt's
    // watchdog — so this one really does touch nothing.
    if (stale) return StartSettlement.IGNORE
    // Answered after the rider cancelled, or after the watchdog gave up. Nothing to confirm and
    // nothing to abandon — they are already where an abandon would have put them, and the stop that
    // covers a belt which may have started anyway was sent on the way out of STARTING.
    if (state != WorkoutSession.State.STARTING) return StartSettlement.STAND_DOWN
    return when (outcome) {
        is MachineCoordinator.Outcome.Ok -> StartSettlement.CONFIRM
        // Superseded normally arrives *after* the transition that caused it, so the check above has
        // already sent it home. Reaching here means the start was cancelled by something that did
        // not move the session — and STARTING is the one state with no way out of its own accord,
        // so it must be resolved rather than merely stopped watching.
        else -> StartSettlement.ABANDON
    }
}

/** What Stride should do about a change in what the console says its own belt is doing. */
internal enum class ConsoleFollowUp {
    /** Leave the session exactly as it is. */
    NOTHING,

    /** The console stopped a belt Stride was counting a workout against. */
    PAUSE,

    /** The console restarted a belt Stride was holding paused. */
    RESUME,
}

/**
 * Decide whether the console's own reading should move Stride's session, from an edge rather than a
 * level.
 *
 * Pulled out as a pure function for the same reason [startSettlement] is: this decides whether the
 * app pauses a workout underneath a rider, and every branch of it has to be checkable without a
 * treadmill.
 *
 * ## Why an edge and not a level
 *
 * The obvious rule — "the console says it is not moving, so pause" — pauses every workout about a
 * second after it starts. Stride confirms a start the moment the machine accepts the command, and
 * the console spends the next poll or two still reporting IDLE while the belt spins up. A level test
 * fires in that gap, every single time.
 *
 * An edge only fires when the console has told us it was moving and has since told us it is not,
 * which is what pressing Stop on the machine looks like from here and is not what a start looks
 * like. [previous] being null — nothing known yet, or the link dropped and cleared it — is therefore
 * never an edge.
 *
 * ## What is deliberately not here
 *
 * A console that starts moving while Stride is IDLE does not begin a session. Adopting a workout the
 * rider started on the machine raises questions this cannot answer on its own — whose goal, whose
 * clock, whether the media should start — and quietly starting one from a poll is not the way to
 * settle them. Only a session Stride is already holding is followed.
 */
internal fun consoleFollowUp(
    previous: Boolean?,
    moving: Boolean,
    state: WorkoutSession.State,
): ConsoleFollowUp {
    if (previous == null || previous == moving) return ConsoleFollowUp.NOTHING
    return when {
        !moving && state == WorkoutSession.State.RUNNING -> ConsoleFollowUp.PAUSE
        moving && state == WorkoutSession.State.PAUSED -> ConsoleFollowUp.RESUME
        else -> ConsoleFollowUp.NOTHING
    }
}
