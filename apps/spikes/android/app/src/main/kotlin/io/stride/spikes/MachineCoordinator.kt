package io.stride.spikes

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The only thing in Stride that may move the belt.
 *
 * Plan section 3.1 calls for this to exist *before* the first control command ships, and this is it.
 * [GlassOsCommands] can command the machine and validates nothing; everything that protects the
 * rider lives here, in one file, so there is exactly one place to read to know what Stride will and
 * will not do to a treadmill.
 *
 * What it enforces:
 *
 * - **Serialization.** One command in flight. The machine gets a single ordered stream of
 *   instructions, never two racing writes.
 * - **Absolute clamps.** Speed and incline are clipped to the machine's physical range before
 *   transmit. A bad UI value is rejected here, not by the motor controller.
 * - **Ramp limiting on the way up only.** Large speed increases are broken into steps. Decreases
 *   and stops are never rate-limited, never delayed, never batched.
 * - **Stop preemption.** [stop] empties the queue and runs immediately, ahead of anything pending.
 * - **Generation IDs.** Every queued command carries the generation it was issued in. A stop bumps
 *   the generation, so a "set 8 mph" queued a moment earlier is discarded rather than landing on a
 *   machine the rider just stopped.
 *
 * What it does **not** do, and what therefore must never be claimed in the UI: it is not a
 * fail-safe. A command sent over a dead link stops nothing, and a stop is only believed once
 * telemetry shows the belt slowing. The physical safety key remains the only true emergency stop.
 */
object MachineCoordinator {

    private const val TAG = "MachineCoordinator"

    /**
     * The device-level ceiling, in mph and percent.
     *
     * These are the values `ConsoleService` reports for this machine (model 17125: 1.0-12.0 mph,
     * -3 to 12% incline), transcribed rather than read at runtime. A clamp the machine supplies can
     * be widened by the machine; this one cannot, and plan section 3.6 requires a ceiling a profile
     * may only ever lower.
     *
     * Speed's floor is 0 rather than the machine's 1.0 mph on purpose: 0 is how the belt is told to
     * stop, and clamping a stop up to 1 mph would be catastrophic.
     */
    const val MIN_SPEED_MPH = 0.0
    const val MAX_SPEED_MPH = 12.0
    const val MIN_INCLINE = -3.0
    const val MAX_INCLINE = 12.0

    /**
     * What the machine says its own limits are, when a transport can tell us. Null on a link that
     * cannot be asked.
     *
     * Only ever used to make the clamp *tighter* — see [clampSpeed] and [clampIncline]. A machine
     * reporting a 20 mph ceiling does not raise ours.
     */
    @Volatile
    var machineLimits: MachineLimits? = null
        private set

    /**
     * Record the connected machine's own limits, or clear them.
     *
     * Called when a transport is bound. The direct path learns these from the console's `MAX_KPH` /
     * `MIN_KPH` / `MAX_GRADE` / `MIN_GRADE` registers; GlassOS passes null, which leaves the fixed
     * ceiling in force.
     */
    fun applyMachineLimits(limits: MachineLimits?) {
        machineLimits = limits
        if (limits != null) Log.i(TAG, "machine limits: $limits")
    }

    /**
     * The rider's speed request, clamped to the *intersection* of Stride's ceiling and the machine's.
     *
     * The floor stays at [MIN_SPEED_MPH] regardless of what the machine reports as its minimum.
     * Model 17125 reports a 1.0 mph floor, and clamping a stop request up to it would keep the belt
     * running — the machine's minimum describes the slowest it will *run*, not the slowest it will
     * accept, and zero is how it is told to stop.
     */
    private fun clampSpeed(mph: Double): Double {
        val ceiling = minOf(MAX_SPEED_MPH, machineLimits?.maxSpeedMph ?: MAX_SPEED_MPH)
        return mph.coerceIn(MIN_SPEED_MPH, maxOf(MIN_SPEED_MPH, ceiling))
    }

    private fun clampIncline(percent: Double): Double {
        val limits = machineLimits
        val ceiling = minOf(MAX_INCLINE, limits?.maxInclinePercent ?: MAX_INCLINE)
        val floor = maxOf(MIN_INCLINE, limits?.minInclinePercent ?: MIN_INCLINE)
        // A machine reporting a nonsensical pair must not invert the clamp.
        if (floor > ceiling) return percent.coerceIn(MIN_INCLINE, MAX_INCLINE)
        return percent.coerceIn(floor, ceiling)
    }

    /** Largest speed increase sent in a single command, in mph. Increases only. */
    private const val MAX_STEP_UP_MPH = 2.0

    /** Gap between ramp steps. Short enough to feel immediate, long enough to be a ramp. */
    private const val STEP_INTERVAL_MS = 700L

    /** Stops sent to clear a stale console session before a new workout. */
    private const val MAX_CLEAR_ATTEMPTS = 3

    /**
     * How long a stop may take to be positively confirmed before it is reported unconfirmed.
     *
     * Sized against the poll, not against a guess at how long a belt takes to stop: a confirmation
     * needs two agreeing readings, [MachineLink] polls every 500 ms while a stop is pending, and a
     * console that has stopped answering has to be given long enough that a single dropped reply is
     * not mistaken for a treadmill that ignored us.
     *
     * Its purpose is not to be exactly right. It is that there has to be one — a confirmation that
     * could wait forever would leave a rider looking at "Stopping…" with nothing to release it.
     */
    private const val STOP_CONFIRM_TIMEOUT_MS = 6_000L

    /**
     * How often the watcher looks. Faster than the poll on purpose, so a reading is picked up in
     * the tick after it arrives rather than up to a whole poll later.
     */
    private const val STOP_CONFIRM_SAMPLE_MS = 200L

    /** Pause after a clearing stop, so the console's state read reflects it. */
    private const val CLEAR_SETTLE_MS = 300L

    /**
     * Attempts to read the console's workout state before a start is refused.
     *
     * More than one because a single null is usually a dropped message rather than a machine that
     * cannot answer; bounded because a console that has not answered three times is not going to.
     */
    private const val MAX_STATE_READS = 3

    private const val MPH_TO_KPH = 1.609344

    /** The outcome of one command, as the UI should describe it. */
    sealed interface Outcome {
        data object Ok : Outcome
        data class Rejected(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
        /** Discarded because a stop (or a newer generation) overtook it. Never an error. */
        data object Superseded : Outcome
    }

    /** Compared by identity in [drain], so it must be the exact string the stop job carries. */
    private const val STOP_LABEL = "Stop"

    /**
     * The end-of-workout re-assert.
     *
     * Deliberately **not** called "confirm". A stop is confirmed by ack plus observed deceleration
     * in telemetry (plan §5.4), and nothing this job does is evidence of either — it is another
     * command going out, not a reading coming back. Naming it "confirm stopped" would be the first
     * step towards somebody treating its [Outcome.Ok] as proof the belt is at rest, which is the
     * exact mistake that ends with "stopped" on screen over a moving belt.
     */
    private const val REASSERT_LABEL = "Re-assert zero"

    private const val FAN_OFF_LABEL = "Fan off (workout ended)"

    private data class FanAdmission(
        val generation: Int,
        val transport: MachineCommands?,
    )

    private data class FanRequest(
        val state: Int,
        val requestedAt: Long,
        val admission: FanAdmission,
    )

    private data class AcceptedFanState(
        val state: Int,
        val acceptedAt: Long,
    )

    internal data class FanRequestSnapshot(
        val state: Int,
        val at: Long,
        val pending: Boolean,
    )

    class FanRestoreToken internal constructor(
        internal val generation: Int,
        internal val endGeneration: Int,
        internal val transport: MachineCommands?,
    )

    private data class Job(
        val generation: Int,
        val label: String,
        val onDone: ((Outcome) -> Unit)? = null,
        /**
         * The speed-request generation this job belongs to, or null for jobs that are not speed
         * commands. Lets a new speed request retire a pending ramp without also discarding a queued
         * incline or fan change, which have nothing to do with it.
         */
        val speedGen: Int? = null,
        /**
         * The end-of-workout generation this job belongs to, or null for jobs that are not tidying
         * up after a finished workout. See [endGeneration].
         */
        val endGen: Int? = null,
        /**
         * Whether this job may overwrite [lastLabel] / [lastOutcome].
         *
         * False for the end-of-workout writes. They run *behind* a stop, and a tidy-up that
         * succeeded must never be the last thing recorded about an end whose stop failed — the
         * outcome a rider needs to see after pressing End is what happened to the stop.
         */
        val reportsOutcome: Boolean = true,
        val run: () -> Outcome,
    )

    private val queue = LinkedBlockingDeque<Job>()
    private val generation = AtomicInteger(0)

    /**
     * Retires the writes that tidy up after a finished workout.
     *
     * Separate from [generation] for the same reason [speedGeneration] is: that one means
     * "everything queued is stale", and a stop must keep meaning exactly that. This one means "the
     * workout those writes belonged to is over and something newer wants the machine".
     *
     * It exists because the tidy-up costs round trips — a re-assert is two, the fan is a third —
     * and each one can block for the console's full command timeout. A rider who ends a workout and
     * immediately starts another would otherwise have their start queued behind the best part of a
     * minute of writes for a session that no longer exists, which is long enough for
     * [WorkoutMachineCoupling]'s start watchdog to give up on a start that was never sent.
     */
    private val endGeneration = AtomicInteger(0)

    /**
     * Retires pending speed jobs without touching anything else.
     *
     * Separate from [generation] because that one is the "everything queued is stale" signal used by
     * stop and rebind. A rider nudging the speed down should cancel the climb, not their pending
     * incline change.
     */
    private val speedGeneration = AtomicInteger(0)
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "machine-coordinator").apply { isDaemon = true }
    }

    /**
     * Where stop confirmations are watched.
     *
     * Deliberately **not** [worker]. A confirmation waits on a treadmill for seconds, and the
     * command queue is the one thing in this app that must never be waiting on anything: putting a
     * watcher on it would mean the next stop queues behind the previous stop's confirmation.
     */
    private val confirmations = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stop-confirmation").apply { isDaemon = true }
    }

    @Volatile private var commands: MachineCommands? = null
    @Volatile private var running = false

    /** Last outcome, for the UI to report. Never used to decide whether the belt is moving. */
    @Volatile var lastOutcome: Outcome? = null
        private set

    @Volatile var lastLabel: String? = null
        private set

    /** Listeners fire after every command settles, on the worker thread. */
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l); Unit }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l); Unit }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach {
            try {
                it()
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed", t)
            }
        }
    }

    /**
     * Bind to a command surface. Idempotent; safe to call from every entry point.
     *
     * Takes a [MachineCommands] rather than a client so the coordinator does not know or care which
     * transport is underneath it. That is the point of the split: swapping iFit's gRPC server for
     * the direct register path swaps the wire, not the clamps.
     */
    @Synchronized
    fun attach(commands: MachineCommands) {
        if (this.commands == null) this.commands = commands
        if (!running) {
            running = true
            worker.execute(::drain)
        }
    }

    /**
     * Replace the bound command surface, e.g. when the rider switches transports.
     *
     * Bumps the generation before swapping, so anything queued for the old transport is discarded
     * rather than delivered to the new one. A "set 8 mph" issued against GlassOS must not land on
     * the register path a moment later — it was authorised against a different machine view.
     */
    @Synchronized
    fun rebind(commands: MachineCommands?) {
        generation.incrementAndGet()
        // The previous transport's leftovers include any end-of-workout tidy-up, which was aimed at
        // a machine we are no longer talking to.
        endGeneration.incrementAndGet()
        queue.clear()
        pendingFanRequest = null
        acceptedFanState = null
        this.commands = commands
        if (commands != null && !running) {
            running = true
            worker.execute(::drain)
        }
    }

    /** The transport currently bound, for diagnostics. Null when nothing is attached. */
    val transportName: String? get() = commands?.transportName

    /**
     * Read something from whichever transport is bound, on the caller's thread.
     *
     * Deliberately not queued. These are questions, not commands: they move nothing, they are
     * already called from background threads, and putting them behind the command queue would let a
     * preset fetch delay a stop. [ask] returns null when nothing is bound, which callers must treat
     * as "not asked" rather than as "the machine said no".
     *
     * This exists so [MachineLink] never has to hold a GlassOS client to ask a question. Reaching
     * past the interface for reads was how the direct path ended up silently talking to GlassOS.
     */
    fun <T> ask(read: (MachineCommands) -> T?): T? = commands?.let(read)

    /**
     * True when a command could be attempted right now.
     *
     * Deliberately not "the belt will move" — it means credentials and a transport exist. The
     * machine can still refuse, which is why every command returns an [Outcome].
     */
    val available: Boolean
        get() = commands != null && MachineLink.status == MachineLink.Status.LINKED

    // ------------------------------------------------------------- commands

    /**
     * Attach the console to its machine, synchronously, on the caller's thread.
     *
     * Not queued behind the worker: this is what [MachineLink] calls from its poll when the console
     * says nothing is attached, and putting it in the command queue would mean a handshake could sit
     * behind commands that cannot possibly succeed until the handshake has happened.
     *
     * Returns the `ConsoleState` GlassOS answers with, or null if there was no usable answer.
     */
    fun connectConsole(): Int? {
        val c = commands ?: return null
        val state = c.connect()
        // Refreshed here rather than only when the link is opened, because a handshake can run again
        // long afterwards — the direct path re-runs it whenever the machine has dropped and come
        // back — and the limits that came with it are the ones now in force. Applying them only at
        // open meant a reconnected treadmill kept whatever ceiling the previous machine reported,
        // or none at all if the first handshake never got that far.
        //
        // Null is a real answer meaning "this transport cannot say", not a failure to be ignored:
        // GlassOS never reports a range, and passing its null through is what makes Stride's own
        // fixed ceiling stand alone. See applyMachineLimits.
        applyMachineLimits(c.limits())
        return state
    }

    /**
     * Begin a workout, reconciling with whatever the console is already doing.
     *
     * A bare StartNewWorkout is only valid from an idle console, and on real hardware it very often
     * is not idle: a previous session, an iFit workout, or a Stride command whose reply timed out
     * can all leave a workout live. That case was found the hard way — the console sat in RUNNING
     * with every metric reading "Not measured", and refused every start until it was stopped.
     *
     * Start means *start*. Stride only shows its Start control when it believes no workout is
     * running, so a leftover console session is stale by definition and is cleared rather than
     * carried forward. Resuming it instead — which is what this used to do from PAUSED — put the
     * rider on the console's "resume or quit" prompt after tapping Start, which is not a start.
     *
     * The one exception is a belt that is genuinely moving: adopting that is the honest option,
     * because stopping a live belt to start our own session is surprise motion, not safety. It also
     * encodes a related discovery — GlassOS only publishes telemetry *during* a workout, so
     * adopting a live one is how metrics start flowing at all.
     */
    fun startWorkout(onDone: ((Outcome, FanRestoreToken) -> Unit)? = null) {
        // A new workout retires the previous one's tidy-up. Those writes are queued ahead of this
        // one and each can block for the console's command timeout; letting them run first would
        // spend that time putting a finished session to bed while a rider stands on the belt
        // waiting for "Starting…" to become a workout.
        val token = synchronized(this) {
            FanRestoreToken(generation.get(), endGeneration.incrementAndGet(), commands)
        }
        submit(
            label = "Start workout",
            onDone = { outcome -> onDone?.invoke(outcome, token) },
            jobGeneration = token.generation,
        ) {
            val gen = token.generation
            // Connect first, always. It is cheap on a console that is already attached — it just
            // answers with the current state — and it is the difference between working and not on one
            // that is not. A rider pressing Start is the one moment we know they want the machine, so
            // this is the right place to make sure GlassOS has actually given it to us.
            //
            // Routed through MachineLink rather than straight to the wire so this shares the poll's
            // handshake: a start arriving just after one attached returns immediately instead of
            // repeating it, and a start arriving during one waits for that answer rather than racing it.
            // The snapshot cannot be used to skip this — it is up to a poll interval stale.
            val connected = MachineLink.connectNow()
            if (!connected.attached) {
                // Fail here rather than pressing on. Every command below would go on to block for the
                // full timeout and then fail anyway, turning a refusal we already know about into most
                // of a minute of the rider watching a spinner. This is not the app overruling the
                // console: we asked the hardware, just now, and it told us — either by saying it has no
                // machine, or by not answering at all.
                return@submit Outcome.Failed(
                    if (connected is MachineLink.ConnectResult.Disconnected) {
                        "The console has no treadmill attached."
                    } else {
                        "The console did not answer."
                    },
                )
            }
            // Re-read rather than trusting one miss: a null here is "we could not ask", which on the
            // GlassOS path is often a single dropped RPC and on the direct path is a dropped frame.
            var state = it.workoutState()
            var reads = 1
            while (state == null && reads < MAX_STATE_READS) {
                if (!settle()) return@submit Outcome.Failed("Interrupted")
                state = it.workoutState()
                reads++
            }

            val observedSpeed = MachineLink.observedSpeedMph
            val moving = (observedSpeed ?: 0.0) > BELT_MOVING_MPH
            if (shouldAdoptWorkout(state, observedSpeed)) {
                Log.i(TAG, "console already running with the belt moving; adopting the existing workout")
                return@submit Outcome.Ok
            }
            if (state == null) {
                // A moving belt we cannot account for is the one case where doing nothing is right.
                // Clearing would stop someone else's run; starting would send a start sequence to a
                // console that is already under way. Adopting is the only option that moves nothing.
                if (moving) {
                    Log.w(TAG, "console state unreadable but the belt is moving; adopting rather than starting")
                    return@submit Outcome.Ok
                }
                // Otherwise refuse. Starting blind means issuing a start against a console whose session
                // we could not clear and whose state we cannot confirm afterwards — and every speed we
                // then send would be aimed at a machine we never established was listening.
                Log.w(TAG, "refusing to start: console workout state could not be read")
                return@submit Outcome.Failed("The console did not report its workout state")
            }

            // Tried and disproven live on the X22i, kept as a note rather than silently forgotten: a
            // direct `WorkoutMode = Running` write from `RESULTS`, skipping the wait for `IDLE`
            // entirely — matching iFit's own `WorkoutFacade.StartWorkoutAsync`, which never checks
            // console state before writing. iFit's own code not gating on state turned out not to mean
            // the console accepts it from any state: asked directly from `RESULTS` here, it came back
            // `Rejected(reason=failed)`, cleanly. Whatever lets iFit's client get away with an
            // unconditional write is something upstream of the wire protocol — its own UI probably
            // never offers Start until the console has already gone idle in real usage timing — not a
            // console-side allowance this app can rely on. `IDLE` really is required.
            val cleared = clearWorkout(it, state)
            if (!cleared) return@submit Outcome.Failed("The console would not end its previous workout")
            // Re-checked immediately before the one command here that can set the belt in motion.
            // Everything above blocks — the handshake, the state reads, and the clearing loop each wait
            // on the console — so by now the rider may have cancelled, or the start watchdog may have
            // given up and put the UI back to idle. Starting anyway would move a treadmill under a
            // screen that shows no workout. The stop that follows a cancel would catch it a moment
            // later, but a moment is exactly what must not happen on a machine someone is standing on.
            if (generation.get() != gen) return@submit Outcome.Superseded
            it.startWorkout().toOutcome()
        }
    }

    /** Wait out a console state transition. Returns false if the wait was interrupted. */
    private fun settle(durationMs: Long = CLEAR_SETTLE_MS): Boolean = try {
        Thread.sleep(durationMs)
        true
    } catch (t: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /**
     * Stop whatever session the console is holding, so a new one may begin.
     *
     * Looped rather than sent once because the console walks through states on its way to idle: a
     * paused session stops into results, and results has to be cleared in turn. Bounded, and
     * abandoned the moment a stop stops changing anything, so a console that will not budge is left
     * alone instead of hammered.
     *
     * Returns whether it is safe to start. A stop the machine *refused* is the interesting case: it
     * used to be discarded, and the start went out regardless. A console that will not accept a stop
     * from us is a console we have no way of stopping once the belt is moving, and arming it is
     * exactly the situation the rest of this file exists to prevent.
     */
    /**
     * Ends whatever workout the console is already in, so a start begins a session rather than
     * colliding with one.
     *
     * **Returns true only for a console that positively said IDLE.** Every other ending — a stop
     * that went unanswered, a state we could not re-read, a console that did not move, or attempts
     * running out — is false.
     *
     * That asymmetry is deliberate and it is the opposite of the `!= false` rule used for
     * capabilities elsewhere. There, unknown must not disable a control the machine never denied.
     * Here, the next thing that happens is the one command that can set a belt in motion, and the
     * caller has already refused to start when it could not read the state at all, saying that
     * starting blind means issuing a start against a console whose session we could not clear.
     * Returning true on an unconfirmed clear would do precisely that, one call later — so the two
     * halves of the decision have to agree, and this is the half that was disagreeing.
     *
     * The concrete case: a paused console with the belt stopped and a rider standing on it. If the
     * stop went unacknowledged, a start resumes the belt underneath them. A rider who is told the
     * console would not end its previous workout can press Start again; one who is not told cannot
     * un-stand on a moving belt.
     */
    private fun clearWorkout(commands: MachineCommands, initial: Int?): Boolean {
        var state = initial
        var attempts = 0
        while (state != null && state != GlassOsCommands.WORKOUT_IDLE && attempts < MAX_CLEAR_ATTEMPTS) {
            Log.i(TAG, "console workout state $state before start; clearing it")
            val ack = commands.stop()
            attempts++
            if (ack is MachineAck.Refused) {
                Log.w(TAG, "console refused a stop while clearing; not starting")
                return false
            }
            if (!settle()) return false
            val next = commands.workoutState()
            if (next == null) {
                // The stop may well have landed — an unanswered command is not a rejected one — but
                // "probably cleared" is not a basis for moving a belt.
                Log.w(TAG, "console did not report its state after a stop; not starting")
                return false
            }
            if (next == state) {
                Log.w(TAG, "console stayed in state $state after a stop; not starting")
                return false
            }
            state = next
        }
        // Covers the loop running out of attempts as well as an initial state that was already
        // idle. Anything still not idle here was never cleared.
        if (state != GlassOsCommands.WORKOUT_IDLE) {
            Log.w(TAG, "console did not reach idle after $attempts stop attempts; not starting")
            return false
        }
        return true
    }

    fun pause() = submit("Pause") { it.pause().toOutcome() }

    fun resume() = submit("Resume") { it.resume().toOutcome() }

    /**
     * Stop the belt, ahead of everything else.
     *
     * Bumps the generation first so anything already queued is discarded rather than executed after
     * the stop, then jumps the queue. This is the one command that is never rate-limited and never
     * ramped.
     *
     * ## What [onSettled] is, and what it is not
     *
     * A stop is "done" only on ack **plus** observed deceleration in telemetry (plan §5.4). The
     * command's [Outcome] is the ack half and nothing more — it says a console took a register
     * write, not that a motor slowed. [onSettled] delivers the other half: a [StopVerdict] reached
     * by watching telemetry after the write, on a separate thread.
     *
     * **The watcher cannot delay, weaken or reorder the stop.** It is armed *after* the job is
     * queued, it runs on its own scheduler, and everything it does is a read of a volatile field.
     * The queue never waits for it. Confirmation happens after and alongside a stop; a stop never
     * waits on a confirmation.
     *
     * Sampling begins the moment the stop is queued rather than when the ack lands, and that is
     * load-bearing on GlassOS: the belt decelerates while the console is still inside the workout,
     * and GlassOS only publishes telemetry while a workout is live
     * ([GlassOsTelemetry.reading] returns null once no `workoutId` is stamped). Waiting for the ack
     * can mean waiting past the only window in which the deceleration is visible.
     */
    @Synchronized
    fun stop(onSettled: ((StopVerdict) -> Unit)? = null) {
        val gen = generation.incrementAndGet()
        queue.clear()
        pendingFanRequest = null
        // Sampled on the caller's thread, before the job can possibly run, so "readings newer than
        // the stop" is anchored to the instant the rider asked for it rather than to whenever the
        // worker got round to it.
        val watcher = onSettled?.let {
            StopConfirmation(gen, MachineLink.readingSeq, MachineLink.observation(), it)
        }
        queue.addFirst(
            Job(
                gen,
                STOP_LABEL,
                onDone = { outcome -> watcher?.onAck(outcome is Outcome.Ok) },
            ) {
                val c = commands ?: return@Job Outcome.Failed("No link to the console")
                c.stop().toOutcome()
            },
        )
        watcher?.start()
    }

    /**
     * True while a stop is waiting to be confirmed.
     *
     * Read by [MachineLink]'s poll, which speeds up for it. A console that has walked to
     * `WORKOUT_RESULTS` reports a belt that may not be moving and would otherwise drop the poll to
     * one reading every two seconds — so the two agreeing readings a confirmation needs would take
     * four, which is long enough to time out and escalate a stop that worked perfectly.
     */
    val stopConfirmationPending: Boolean get() = pendingConfirmations.get() > 0

    private val pendingConfirmations = AtomicInteger(0)

    /**
     * Watches telemetry after one stop and produces a verdict, exactly once.
     *
     * Everything it decides lives in [stopVerdict], which is pure. This class is only the plumbing:
     * when to look, what to keep, and when to give up.
     *
     * **It always settles.** Confirmed, timed out, or retired by a newer stop — [onSettled] runs
     * once on every path. A watcher that could finish without answering would leave the session
     * that is waiting on it stuck in [WorkoutSession.State.STOPPING] with nothing left to release
     * it, which is a wedge on the ending path.
     */
    private class StopConfirmation(
        private val generation: Int,
        /** The poll count when the stop was queued. Only readings past this are evidence. */
        private val seqAtStop: Long,
        /**
         * The belt as it read when the stop went out, or null if nothing was fresh.
         *
         * May complete the pair of agreeing at-rest readings a confirmation needs, and may never be
         * the whole of it — see [stopVerdict], which enforces that rather than trusting this to.
         */
        private val beforeStop: MachineLink.Observation?,
        private val onSettled: (StopVerdict) -> Unit,
    ) {
        private val settled = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Written and read only on the confirmation thread. */
        private val observations = mutableListOf<MachineLink.Observation>()

        @Volatile private var acked: Boolean? = null

        private var deadlineAt = 0L

        /**
         * Volatile because [start] publishes it *after* the first sample may already have run, and
         * [finish] reads it from the confirmation thread. Without it a watcher that was superseded
         * before its first tick could cancel a `null` and leave its 200 ms sampler running for the
         * life of the process. It never affected settling — [settled] guarantees that — but a leaked
         * repeating task on the one confirmation thread is still a leak.
         */
        @Volatile private var task: java.util.concurrent.ScheduledFuture<*>? = null

        fun onAck(ok: Boolean) {
            acked = ok
        }

        fun start() {
            pendingConfirmations.incrementAndGet()
            // System.nanoTime rather than SystemClock.elapsedRealtime. Both are monotonic and
            // immune to a wall-clock correction, which is the property that matters; this one is
            // also real under a JVM unit test, where the Android stub returns a constant zero and
            // a deadline built on it never arrives. A safety timeout that silently never fires off
            // a real device is not a timeout worth having.
            deadlineAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_CONFIRM_TIMEOUT_MS)
            val future = confirmations.scheduleWithFixedDelay(
                ::sample,
                0L,
                STOP_CONFIRM_SAMPLE_MS,
                TimeUnit.MILLISECONDS,
            )
            task = future
            // Re-checked here because the first sample can run — and settle — before the line
            // above publishes the handle it would have cancelled. A watcher superseded on its first
            // tick would otherwise leave its sampler running for the life of the process. Settling
            // was never at risk; the leak was.
            if (settled.get()) future.cancel(false)
        }

        private fun sample() {
            if (settled.get()) return
            try {
                // A newer stop, a rebind, or a transport swap has retired this. Settle rather than
                // vanish: something is waiting for an answer, and the newer stop's own watcher is
                // the one that will speak for the machine now.
                if (generation != MachineCoordinator.generation.get()) {
                    finish(StopVerdict.Unconfirmed(StopUnconfirmed.SUPERSEDED))
                    return
                }
                MachineLink.observation()
                    ?.takeIf { it.seq > seqAtStop && observations.lastOrNull()?.seq != it.seq }
                    ?.let { observations.add(it) }
                val ack = acked
                if (ack != null) {
                    val verdict = stopVerdict(ack, MachineLink.everReportedMotion, beforeStop, observations)
                    if (verdict is StopVerdict.Confirmed) {
                        finish(verdict)
                        return
                    }
                    // A stop the console refused or never answered is not going to become
                    // confirmed by waiting, and the rider needs to hear about it now.
                    if (!ack) {
                        finish(verdict)
                        return
                    }
                }
                if (System.nanoTime() >= deadlineAt) {
                    finish(stopVerdict(acked ?: false, MachineLink.everReportedMotion, beforeStop, observations))
                }
            } catch (t: Throwable) {
                // A watcher that throws must not leave the ending path waiting forever, and must
                // not be quietly cancelled by the scheduler either — which is what
                // scheduleWithFixedDelay does to a task that throws.
                Log.w(TAG, "stop confirmation failed; treating the stop as unconfirmed", t)
                finish(StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED))
            }
        }

        private fun finish(verdict: StopVerdict) {
            if (!settled.compareAndSet(false, true)) return
            pendingConfirmations.decrementAndGet()
            // Cancelled through the executor as well as through the handle, because the handle may
            // not be published yet: a watcher superseded before its first tick can reach here while
            // start() is still assigning it. Returning true from the task's own run is not an option
            // with scheduleWithFixedDelay, so the handle is the mechanism — it just has to be there.
            task?.cancel(false)
            Log.i(TAG, "stop verdict: $verdict")
            try {
                onSettled(verdict)
            } catch (t: Throwable) {
                Log.w(TAG, "stop verdict handler failed", t)
            }
        }
    }

    /**
     * Request a speed in mph. Clamped, and ramped when the increase is large.
     *
     * The ramp only ever applies upward. Asking for a lower speed is a single immediate command,
     * because slowing down is never the dangerous direction.
     */
    fun setSpeedMph(mph: Double, onDone: ((Outcome) -> Unit)? = null) {
        val target = clampSpeed(mph)
        // Every new speed request retires the previous one, including a ramp still climbing. Without
        // this, asking to slow down merely queues *behind* the remaining steps of the climb, so the
        // belt keeps accelerating after the rider has asked it not to. The generation is bumped
        // before anything is queued so the new jobs carry the new value.
        val gen = speedGeneration.incrementAndGet()
        val current = MachineLink.observedSpeedMph
        if (current == null || target <= current || target - current <= MAX_STEP_UP_MPH) {
            submit(label = "Speed ${format(target)} mph", speedGen = gen, onDone = onDone) {
                it.setSpeedKph(target * MPH_TO_KPH).toOutcome()
            }
            return
        }
        // Break the climb into steps. Each is queued as its own generation-checked job, so a stop
        // partway up discards the rest instead of continuing to accelerate.
        var next = current + MAX_STEP_UP_MPH
        while (next < target) {
            val step = next
            submit(label = "Speed ${format(step)} mph", delayMs = STEP_INTERVAL_MS, speedGen = gen) {
                it.setSpeedKph(step * MPH_TO_KPH).toOutcome()
            }
            next += MAX_STEP_UP_MPH
        }
        // Only the last step reports: the caller asked for a speed, not for a ramp, and the
        // outcome that matters is whether the machine ended up taking it.
        submit(
            label = "Speed ${format(target)} mph",
            delayMs = STEP_INTERVAL_MS,
            speedGen = gen,
            onDone = onDone,
        ) {
            it.setSpeedKph(target * MPH_TO_KPH).toOutcome()
        }
    }

    fun setInclinePercent(percent: Double, onDone: ((Outcome) -> Unit)? = null) {
        val target = clampIncline(percent)
        submit("Incline ${format(target)}%", onDone = onDone) {
            it.setInclinePercent(target).toOutcome()
        }
    }

    /**
     * Set the console fan.
     *
     * Queued with everything else even though it cannot move the belt, so a fan write can never
     * overlap a speed write on the wire. The request is visible while queued and in flight, but
     * [lastFanState] advances only after the machine accepts it.
     */
    fun setFan(state: Int) {
        val request = beginFanRequest(state, fanAdmission())
        submit(
            label = "Fan ${GlassOsCommands.fanStateName(state)}",
            onDone = { finishFanRequest(request) },
            jobGeneration = request.admission.generation,
        ) { commands ->
            if (commands !== request.admission.transport) return@submit Outcome.Superseded
            acceptFanWrite(request, commands.setFanState(state)).toOutcome()
        }
    }

    /**
     * The last fan state a machine accepted, or null when this transport has accepted none.
     *
     * **Accepted is still not measured.** The console has its own fan button and Stride never hears
     * it pressed, so this can be confidently wrong the moment the rider uses it. Anything drawing a
     * fan value must prefer [MachineLink.fanState], which is the machine's own answer.
     *
     * Cleared on [rebind], because an acknowledgement from one machine is not evidence about the
     * next. [StrideSettings] owns the rider preference that outlives a transport and the process.
     */
    val lastFanState: Int? get() = acceptedFanState?.state

    /**
     * When [lastFanState] was accepted, on `System.currentTimeMillis` — the same clock carried by
     * [MachineLink.FanTelemetry], because the two are only ever used by comparing them.
     */
    val lastFanStateAt: Long get() = acceptedFanState?.acceptedAt ?: 0L

    /**
     * The newest fan intent that the readout should show: a queued/in-flight request first, then the
     * last accepted state. A refused, failed, superseded, or transport-less write never remains here.
     */
    val lastFanRequest: Int? get() = fanRequestSnapshot()?.state

    /** When [lastFanRequest] was requested or accepted, on `System.currentTimeMillis`. */
    val lastFanRequestAt: Long get() = fanRequestSnapshot()?.at ?: 0L

    /**
     * One coherent state/time pair for consumers that compare this intent with a machine reading.
     * Reading the two public compatibility accessors separately can span a settling write.
     */
    internal fun fanRequestSnapshot(): FanRequestSnapshot? {
        val pending = pendingFanRequest
        if (pending != null) return FanRequestSnapshot(pending.state, pending.requestedAt, pending = true)
        val accepted = acceptedFanState ?: return null
        return FanRequestSnapshot(accepted.state, accepted.acceptedAt, pending = false)
    }

    @Volatile
    private var pendingFanRequest: FanRequest? = null

    @Volatile
    private var acceptedFanState: AcceptedFanState? = null

    @Synchronized
    private fun fanAdmission(): FanAdmission = FanAdmission(generation.get(), commands)

    @Synchronized
    private fun beginFanRequest(state: Int, admission: FanAdmission): FanRequest =
        FanRequest(state, System.currentTimeMillis(), admission).also {
            // A stop/rebind between deciding the request and publishing it has already retired it.
            if (admission.generation == generation.get() && admission.transport === commands) {
                pendingFanRequest = it
            }
        }

    private fun finishFanRequest(request: FanRequest) {
        if (pendingFanRequest === request) pendingFanRequest = null
    }

    @Synchronized
    private fun acceptFanWrite(request: FanRequest, ack: MachineAck): MachineAck {
        if (
            ack is MachineAck.Ok &&
            request.admission.generation == generation.get() &&
            request.admission.transport === commands
        ) {
            acceptedFanState = AcceptedFanState(request.state, System.currentTimeMillis())
        }
        return ack
    }

    /**
     * Restore the fan for a starting workout.
     *
     * The whole decision runs inside the queue, on the worker thread, because working out what to
     * send requires asking the console whether it supports Auto — a blocking round trip. Deciding
     * at the call site would put that round trip on whichever thread happened to start the workout,
     * which is the main one, and a treadmill overlay that freezes for two seconds as the belt
     * starts is worse than a fan that comes on a moment late.
     *
     * A remembered setting always wins; Auto is only the fallback for a rider who has never chosen.
     *
     * Auto is attempted whenever the console has not specifically said no. GlassOS can answer this
     * question outright because it reads a per-console configuration blob; the direct path has no
     * equivalent and can only find out by asking, so an unknown answer is treated as "worth trying
     * once". A machine that refuses is not an error — it is a machine without an automatic fan, and
     * it has just told us so, which is why the refusal is swallowed here and remembered there.
     */
    fun restoreFan(remembered: Int?, token: FanRestoreToken) {
        val admission = synchronized(this) {
            if (
                token.generation != generation.get() ||
                token.endGeneration != endGeneration.get() ||
                token.transport !== commands
            ) {
                return
            }
            FanAdmission(token.generation, token.transport)
        }
        var request = remembered?.let { beginFanRequest(it, admission) }
        submit(
            label = "Restore fan",
            onDone = { request?.let(::finishFanRequest) },
            endGen = token.endGeneration,
            jobGeneration = token.generation,
        ) { commands ->
            if (commands !== admission.transport) return@submit Outcome.Superseded
            val speculative = remembered == null
            val target = remembered
                ?: if (commands.autoFanSupported() != false) GlassOsCommands.FAN_AUTO else null
                ?: return@submit Outcome.Ok
            val activeRequest = request ?: beginFanRequest(target, admission).also { request = it }
            val ack = acceptFanWrite(activeRequest, commands.setFanState(target))
            if (speculative && ack is MachineAck.Refused) Outcome.Ok else ack.toOutcome()
        }
    }

    /**
     * Put the machine back into a known-safe state after a workout the rider *ended*.
     *
     * The counterpart to [restoreFan], and deliberately not reachable from a pause. A pause is
     * resumable — the rider stepped off, the belt is expected to move again, and shutting the fan
     * down or flattening the deck under them would be the app deciding their session is over. This
     * runs only for [WorkoutSession.Ending.ENDED]; see [WorkoutMachineCoupling.endFollowUp].
     *
     * ## Why anything at all, when [stop] already sent a zero
     *
     * Because the interesting case is the one where it did not land. A stop is a single frame —
     * `DirectMachineCommands.stop` puts `KPH = 0` and `WORKOUT_MODE = IDLE` in one — and a frame
     * that was lost leaves a belt running under an app that has gone back to showing "Start
     * workout". If the stop *did* land, the console is idle and will most likely refuse this, which
     * costs a log line and nothing else. If it did not, this is the write that stops the treadmill.
     * Paying a round trip on the ending path to cover that is the trade this exists to make.
     *
     * ## What it is not
     *
     * It is not confirmation. A stop is done on ack **plus** observed deceleration in telemetry,
     * and this produces neither — see [REASSERT_LABEL]. The [Outcome] it records is deliberately
     * not published (`reportsOutcome = false`), so nothing downstream can read a successful
     * re-assert as a successful stop.
     *
     * Queued, never preempting. [stop] has already bumped the generation and taken the front of the
     * queue by the time this is called, so this can only ever sit behind it. That ordering is not
     * an accident of timing: the caller issues the stop first, and `submit` only ever appends.
     */
    fun reassertZero() {
        // Sampled here, on the caller's thread, so the checks inside the job compare against the
        // state this end belonged to rather than against whatever is current when it runs.
        val gen = generation.get()
        val end = endGeneration.get()
        submit(REASSERT_LABEL, endGen = end, reportsOutcome = false) { commands ->
            // Through the same clamp as every other speed request, not as a raw zero. The floor
            // being 0 rather than the machine's reported minimum is a safety rule, and it is stated
            // in exactly one place (clampSpeed) so a second copy here cannot drift away from it.
            val speed = commands.setSpeedKph(clampSpeed(0.0) * MPH_TO_KPH)
            // Re-checked between the two writes, not just before the first. The speed write blocks
            // for a round trip, and a stop or a new workout arriving inside that window means this
            // deck movement belongs to a session that is over — moving it anyway would be this
            // job's writes outliving the generation that authorised them.
            if (generation.get() != gen || endGeneration.get() != end) return@submit Outcome.Superseded
            val observed = MachineLink.observedSpeedMph
            if (!mayFlattenDeck(observed, MachineLink.everReportedMotion)) {
                Log.i(
                    TAG,
                    "belt not observably at rest (speed=$observed, " +
                        "everReportedMotion=${MachineLink.everReportedMotion}); " +
                        "leaving the deck where it is",
                )
                return@submit speed.toOutcome()
            }
            // Flat is the state a rider steps off onto, and it is the same state a workout is
            // started in — a run should not end leaving the deck wherever the last hill put it.
            //
            // Clamped, so a machine whose reported grade range excludes zero gets as flat as it
            // goes rather than a value it would refuse.
            commands.setInclinePercent(clampIncline(0.0)).toOutcome()
        }
    }

    /**
     * Shut the fan off, because the workout is over.
     *
     * The missing half of [restoreFan]. Stride has turned the fan on at the start of every workout
     * since that landed and has never turned it off, so a console left alone after a run sat there
     * blowing until somebody noticed — issue #29.
     *
     * **Sent unconditionally, not gated on [lastFanState].** Skipping when Stride does not think it
     * has a fan running looks like an easy saving and is a race: a restore can already be on the wire
     * when the workout ends and can turn the fan on behind the stop. The accepted state cannot make
     * that in-flight write safe, and the console has controls of its own.
     *
     * A refusal is not an error. It means this machine has no fan register that will take a write,
     * which is a fact about the treadmill and not a failure of the end — swallowed for the same
     * reason [restoreFan] swallows a speculative Auto.
     *
     * [StrideSettings.fanState] is deliberately untouched. That is the rider's remembered
     * preference and the value the next [restoreFan] replays; recording an automatic shutdown into
     * it would quietly teach the app that they want the fan off from now on.
     */
    fun stopFan() {
        val end = endGeneration.get()
        val request = beginFanRequest(GlassOsCommands.FAN_OFF, fanAdmission())
        submit(
            label = FAN_OFF_LABEL,
            onDone = { finishFanRequest(request) },
            endGen = end,
            reportsOutcome = false,
            jobGeneration = request.admission.generation,
        ) { commands ->
            if (commands !== request.admission.transport) return@submit Outcome.Superseded
            val ack = acceptFanWrite(request, commands.setFanState(GlassOsCommands.FAN_OFF))
            if (ack is MachineAck.Refused) Outcome.Ok else ack.toOutcome()
        }
    }

    // -------------------------------------------------------------- plumbing

    private fun submit(
        label: String,
        delayMs: Long = 0L,
        onDone: ((Outcome) -> Unit)? = null,
        speedGen: Int? = null,
        endGen: Int? = null,
        reportsOutcome: Boolean = true,
        jobGeneration: Int = generation.get(),
        // MachineCommands, not GlassOsCommands: the coordinator owns the clamps and the ramp and
        // must not know which wire is underneath it. That split is what lets the direct register
        // path substitute for GlassOS without a second copy of any of the safety rules.
        run: (MachineCommands) -> Outcome,
    ) {
        val gen = jobGeneration
        queue.addLast(
            Job(gen, label, onDone, speedGen, endGen, reportsOutcome) {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (t: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Job Outcome.Superseded
                    }
                }
                // Re-checked after the sleep as well as before dequeue: a stop during the gap
                // between ramp steps must cancel the rest of the climb, and so must a newer speed
                // request that arrived while this step was waiting its turn.
                if (generation.get() != gen) return@Job Outcome.Superseded
                if (speedGen != null && speedGeneration.get() != speedGen) return@Job Outcome.Superseded
                // A workout's tidy-up is worth nothing to the workout that replaced it, and it
                // holds the worker for a round trip it could be spending on a start.
                if (endGen != null && endGeneration.get() != endGen) return@Job Outcome.Superseded
                // Deliberately not short-circuited on [MachineLink.consoleDetached]. Refusing here
                // without touching the wire would be faster, but it also makes the app's own
                // reading of the console the thing that decides whether a rider may use their
                // treadmill — and a single stale or wrong poll would then lock them out with no
                // way to overrule it. The console gets asked every time; a detached one answers by
                // timing out, and that failure is now shown with a retry rather than swallowed.
                val c = commands ?: return@Job Outcome.Failed("No link to the console")
                run(c)
            },
        )
    }

    private fun drain() {
        while (true) {
            val job = try {
                queue.poll(1, TimeUnit.SECONDS) ?: continue
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            // A stop bumps the generation, so anything issued before it is stale by definition.
            val outcome = if (job.generation != generation.get() && job.label != STOP_LABEL) {
                Outcome.Superseded
            } else {
                try {
                    job.run()
                } catch (t: Throwable) {
                    Outcome.Failed(t.message ?: t.javaClass.simpleName)
                }
            }
            // Skipped for the end-of-workout writes. They run behind a stop, and a tidy-up that
            // succeeded must never become the last recorded word on an end whose stop failed.
            if (job.reportsOutcome) {
                lastLabel = job.label
                lastOutcome = outcome
            }

            // A ramp step that failed must not be followed by the next, larger one. Each step is
            // only safe because the one below it landed; continuing past a failure would deliver an
            // increase bigger than MAX_STEP_UP_MPH in a single jump, which is the exact thing the
            // ramp exists to prevent.
            if (outcome is Outcome.Failed && job.speedGen != null) {
                speedGeneration.incrementAndGet()
                Log.w(TAG, "${job.label} failed; cancelling the rest of the ramp")
            }
            if (outcome !is Outcome.Superseded) Log.i(TAG, "${job.label} -> $outcome")
            // Before the general listeners, and individually guarded: this is how a caller learns
            // its own command failed, and a caller that throws must not cost every other listener
            // its notification.
            job.onDone?.let {
                try {
                    it(outcome)
                } catch (t: Throwable) {
                    Log.w(TAG, "${job.label} completion handler failed", t)
                }
            }
            notifyListeners()
        }
    }

    private fun MachineAck.toOutcome(): Outcome = when (this) {
        is MachineAck.Ok -> Outcome.Ok
        is MachineAck.Refused -> Outcome.Rejected(detail)
        is MachineAck.NoAnswer -> Outcome.Failed(reason)
    }

    private fun format(v: Double): String =
        if (v == v.toInt().toDouble()) v.toInt().toString() else String.format("%.1f", v)
}

/**
 * Whether a Start should adopt the console's existing workout instead of starting a new one.
 *
 * Only a belt that is actually moving is adopted. A console reporting RUNNING with the belt at rest
 * is the stale-session case — a stop whose reply was lost, or a finished session the console never
 * let go of — and adopting it hands the rider a workout that is already over, which is how tapping
 * Start ended up on the console's "resume or quit" prompt. Every other state (paused, results, idle,
 * or unknown) starts fresh.
 *
 * Pure, and separate from [MachineCoordinator], so the decision can be tested without a console.
 */
internal fun shouldAdoptWorkout(state: Int?, beltSpeedMph: Double?): Boolean =
    state == GlassOsCommands.WORKOUT_RUNNING && (beltSpeedMph ?: 0.0) > BELT_MOVING_MPH

/** Above this the belt is moving, rather than reporting rounding noise around a stop. */
internal const val BELT_MOVING_MPH = 0.1

/**
 * Whether a stop is "done".
 *
 * Two values, not three. Plan §5.4 gives a stop exactly one way to be finished — ack **plus**
 * observed deceleration — and everything else, including "we could not see the belt", is the same
 * answer: not confirmed, escalate. A third "probably fine" value is the shape this whole issue
 * exists to forbid, so it is unrepresentable rather than merely discouraged.
 */
sealed interface StopVerdict {
    /** Ack, plus telemetry that is worth believing showing a belt at rest. */
    data object Confirmed : StopVerdict

    data class Unconfirmed(val reason: StopUnconfirmed) : StopVerdict
}

/**
 * Why a stop could not be confirmed.
 *
 * Carried so the escalation can say which failure it saw. "The console never accepted the stop" and
 * "the console accepted it and the belt is still moving" are the same verdict and very different
 * sentences to read standing next to a treadmill.
 */
enum class StopUnconfirmed {
    /** The console did not accept the stop: refused, timed out, or no link at all. */
    NOT_ACKED,

    /**
     * This console's speed register has never reported motion on this link, so its zero is worth
     * nothing. Issue #34 — see [MachineLink.everReportedMotion].
     */
    NEVER_REPORTED_MOTION,

    /** Telemetry we have reason to believe says the belt is still moving. */
    STILL_MOVING,

    /** The belt covered ground *after* the console told us it had stopped. */
    DISTANCE_ADVANCED,

    /** Telemetry never produced two agreeing readings of a belt at rest. */
    NOT_OBSERVED,

    /** A newer stop took over. This one's answer belongs to a machine state that has moved on. */
    SUPERSEDED,
}

/**
 * Distance change small enough to be floating-point noise rather than travel, in miles.
 *
 * Three orders of magnitude below the smallest real quantum any transport reports — the direct path
 * counts whole metres (`FitProValues.metresToMiles`, 1 m ≈ 6.2e-4 mi) — and far above the ~1e-12
 * error of the conversions themselves. Erring small is the safe direction: a smaller epsilon vetoes
 * more confirmations, and a vetoed confirmation escalates.
 */
private const val DISTANCE_STILL_MILES = 1e-6

/**
 * Whether a stop is confirmed, from the ack and the readings taken after it.
 *
 * Pure, and separate from [MachineCoordinator], because this is the decision the whole issue turns
 * on and every branch of it has to be checkable without a treadmill.
 *
 * @param beforeStop the observation current when the stop was queued, or null if nothing was fresh.
 *   It may **complete** the pair of agreeing readings below; it may never be the whole of it.
 * @param postStop every observation taken *after* the stop was queued, oldest first. Readings from
 *   before it are not evidence about it and must never appear here — [MachineLink.observedSpeedMph] is
 *   believed for four seconds, so a reading taken before a stop is still "fresh" after it.
 *
 * ## Four conditions, all required
 *
 * 1. **The console acked.** Necessary and nowhere near sufficient: an ack says a console took a
 *    register write, which is the exact thing this issue exists because somebody once mistook for a
 *    stopped belt.
 * 2. **The speed register has proved it reports motion**, via [MachineLink.everReportedMotion]. On
 *    the X22i `ACTUAL_KPH` reads a confident, well-formed `0x0000` on every poll while a rider walks
 *    at 4 mph (issue #34), and there is no per-field validity marker in the protocol that could tell
 *    that apart from a real zero. A console that has only ever said zero is not saying anything.
 * 3. **Two agreeing readings say the belt is at rest, at least one of them from after the stop.**
 *    Two, not one, because a single sample cannot distinguish a belt at rest from a belt passing
 *    through a reading. At least one from after, by poll count, because otherwise a reading taken
 *    *before* the stop could confirm it — and readings are believed for four seconds.
 * 4. **Distance did not advance across those readings.**
 *
 * ## Why the pre-stop reading is allowed to be one of the two — measured, not assumed
 *
 * The first draft required *both* readings to be post-stop, and a belt run on a GlassOS console
 * showed that sitting on an unmeasured knife-edge. GlassOS publishes metrics only while a workout
 * is live: `GlassOsTelemetry.reading` returns **null, not zero**, once no `workoutId` is stamped.
 * Ending a workout therefore takes the telemetry away, and the trace showed it going:
 *
 * ```
 * 11:28:40.056  Stop -> Ok
 * 11:28:40.090  Re-assert zero -> Rejected(... WorkoutState IDLE ...)   <- WorkoutService: 34 ms
 * 11:28:40.542  console=PAUSED speedMph=0.0 distance=0.00555 workoutId=3bbc34e0…   <- still live
 * 11:28:43.134  console=IDLE   speedMph=null distance=null workoutId=null          <- gone
 * ```
 *
 * So the metric services outlive the workout state by at least half a second and are gone by three,
 * and nothing narrows that further — the poll was running at two seconds. Requiring two post-stop
 * readings would make every ordinary end depend on which end of that range is true, and if it is
 * the near end then **every** end escalates. An alarm that always fires is a worse safety defect
 * than the gap this issue closes, because it teaches a rider to ignore the one that matters.
 *
 * Allowing the pre-stop reading to complete the pair removes that dependence for the case the UI
 * actually produces — the overlay only offers End from PAUSED, where the belt is already at rest
 * and both readings agree it is. It does not weaken anything: a *moving* belt cannot produce two
 * at-rest readings straddling a stop on an honest register, and on a dishonest one the distance
 * veto below still applies. Ending from RUNNING still needs two readings from after the stop,
 * because there the pre-stop reading says the belt was moving — and if telemetry goes before those
 * arrive, we genuinely never saw it stop and the escalation is the honest answer.
 *
 * ## Why distance may veto a stop but never grant one
 *
 * This asymmetry is what makes the rule safe on a console nobody has characterised.
 *
 * Distance *advancing* is monotone positive evidence of travel. It cannot be manufactured by coarse
 * quantisation — a register that ticks in 10 m steps still only ticks when 10 m have actually
 * passed — so an increase can be trusted without knowing the quantum. It is therefore allowed to
 * **refuse** a confirmation.
 *
 * Distance *failing to advance* is not evidence of rest. A 10 m quantum at 4 mph is five and a half
 * seconds of real motion reading as "unchanged". The belt run above measured GlassOS's quantum at
 * roughly **0.45 m** — increments of 2.8e-4 mi once per second at 1 mph, which is fine enough to be
 * useful — but the FitPro register path counts whole metres and nobody has measured any other
 * machine. So it is never allowed to **grant** a confirmation, and a machine that publishes no
 * distance at all cannot have a stop confirmed here. That is the honest degradation: unconfirmed,
 * and therefore escalated, rather than reassuring.
 *
 * This is the leg that catches the failure a speed register cannot report about itself: one that
 * reports motion, earns [MachineLink.everReportedMotion], and *then* goes dead while the belt is
 * still running. On the X22i that is not hypothetical — it is what #34 observed, with
 * `CURRENT_DISTANCE` accumulating the real pace beside a speed stuck at zero.
 *
 * ## Why this is stricter than [mayFlattenDeck], which looks like the same question
 *
 * It is not the same question. [mayFlattenDeck] asks whether a deck may be driven flat, and gets
 * that wrong by leaving a deck on a hill. This asks whether a rider may be told their treadmill has
 * stopped, and gets that wrong by suppressing the one warning that would have sent them to the
 * safety key. So this requires everything that one does, and then a post-stop reading, a second
 * agreeing reading, and a distance veto on top. The shared piece is
 * [MachineLink.everReportedMotion], not the verdict.
 */
internal fun stopVerdict(
    acked: Boolean,
    everReportedMotion: Boolean,
    beforeStop: MachineLink.Observation?,
    postStop: List<MachineLink.Observation>,
): StopVerdict {
    if (!acked) return StopVerdict.Unconfirmed(StopUnconfirmed.NOT_ACKED)
    if (!everReportedMotion) return StopVerdict.Unconfirmed(StopUnconfirmed.NEVER_REPORTED_MOTION)
    val last = postStop.lastOrNull() ?: return StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED)
    val lastSpeed = last.speedMph
    if (lastSpeed != null && lastSpeed > BELT_MOVING_MPH) {
        return StopVerdict.Unconfirmed(StopUnconfirmed.STILL_MOVING)
    }
    // The *trailing* run, so a belt that read at rest and then moved again cannot be confirmed by
    // its earlier readings. A reading of motion partway through is not a problem on its own — that
    // is the deceleration, and seeing it is the point — it just has to be behind us.
    //
    // The reading taken as the stop went out is allowed to complete the run, but never to be the
    // whole of it: the check below requires the run to contain something from after the stop.
    val window = listOfNotNull(beforeStop) + postStop
    val rest = window.takeLastWhile { it.speedMph != null && it.speedMph <= BELT_MOVING_MPH }
    if (rest.size < 2) return StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED)
    if (rest.none { it.seq >= (postStop.firstOrNull()?.seq ?: Long.MAX_VALUE) }) {
        return StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED)
    }
    val from = rest.first().distanceMiles
    val to = rest.last().distanceMiles
    if (from == null || to == null) return StopVerdict.Unconfirmed(StopUnconfirmed.NOT_OBSERVED)
    if (to - from > DISTANCE_STILL_MILES) {
        return StopVerdict.Unconfirmed(StopUnconfirmed.DISTANCE_ADVANCED)
    }
    return StopVerdict.Confirmed
}

/**
 * Why a stop was sent, as far as it bears on whether an unconfirmed one is an alarm.
 *
 * Not the same as [WorkoutSession.Ending]. That one says what the *rider* did; this says what
 * Stride knows about whether a belt could be moving, which is a different question with a different
 * answer for two endings that look identical to a session.
 */
internal enum class StopCause {
    /** The rider ended a workout. Stride asked this belt to move and it agreed. */
    ENDED,

    /**
     * A start the console **explicitly refused**. It said no, in as many words, so nothing was set
     * moving by us. Distinct from a start that went unanswered, which is not a refusal.
     */
    START_REFUSED,

    /**
     * A start that was never answered — cancelled, timed out, or failed. The reply may have been
     * lost rather than the command not landing, so the belt may well be moving.
     */
    START_UNANSWERED,
}

/**
 * Whether an unconfirmed stop should escalate the UI to "USE THE SAFETY KEY".
 *
 * Pure, and a single greppable function rather than scattered conditions, because it is the one
 * place in this change that decides *not* to warn somebody.
 *
 * ## Why this is not simply "escalate whenever unconfirmed"
 *
 * §5.4's rule is about a stop for a belt that may be moving. Firing the same alarm for a stop that
 * could not have been stopping anything would be worse than not firing it: an alarm a rider sees on
 * every refused start, on a console with no treadmill plugged into it, is an alarm they learn to
 * dismiss without reading — and the one time it means what it says, they dismiss that too. Alarm
 * fatigue is a safety defect, not a UX complaint.
 *
 * So the exceptions are narrow, and both are *positive* statements from the machine rather than
 * absences of evidence:
 *
 * - **[MachineLink.consoleDetached]** — the console explicitly reports that no treadmill is
 *   attached to it. Positive knowledge only; a stale snapshot or a missed poll does not qualify.
 * - **[StopCause.START_REFUSED]** — the console explicitly refused the start. It answered, and the
 *   answer was no.
 *
 * A start that merely *went unanswered* escalates, and that is deliberate: an earlier draft of this
 * suppressed the alarm for every start that never reached RUNNING, which quietly covered the case
 * where the console accepted the start and the *reply* was lost. That is a moving belt with the
 * alarm turned off, and it is the exact failure [WorkoutSession.abandon] already warns about —
 * "the refusal we are reacting to may be a reply that was lost rather than a command that never
 * landed".
 *
 * Neither exception can suppress an alarm for a belt we have actually seen moving: [beltSeenMoving]
 * and the two verdicts that mean "telemetry says it is still going" are checked first.
 */
internal fun shouldEscalate(
    verdict: StopVerdict,
    cause: StopCause,
    consoleDetached: Boolean,
    beltSeenMoving: Boolean,
): Boolean {
    val reason = (verdict as? StopVerdict.Unconfirmed)?.reason ?: return false
    // A newer stop owns the machine now, and its own watcher will speak for it. Escalating here
    // would raise an alarm about a state that has already been superseded.
    if (reason == StopUnconfirmed.SUPERSEDED) return false
    // Evidence of motion overrides every exception below. Nothing may talk us out of an alarm for a
    // belt telemetry says is moving.
    if (beltSeenMoving ||
        reason == StopUnconfirmed.STILL_MOVING ||
        reason == StopUnconfirmed.DISTANCE_ADVANCED
    ) {
        return true
    }
    if (consoleDetached) return false
    if (cause == StopCause.START_REFUSED) return false
    return true
}

/**
 * Whether the deck may be driven to flat as part of ending a workout.

 *
 * Pure, and separate from [MachineCoordinator], because getting it wrong moves a physical part
 * under someone stepping off a treadmill and every branch has to be checkable without one.
 *
 * ## Why an ack is not enough, and was the first answer here
 *
 * The obvious gate is "the zero speed we just re-sent was accepted, so the belt is stopping". It is
 * wrong twice. [MachineAck.Ok] means a console took a register write; it says nothing about a belt.
 * And it selects for exactly the wrong case: if the stop landed, the console is idle and refuses
 * the write, so an ack-gated deck movement would happen *only* on the branch where the console is
 * still in a workout with the belt running. Measured on a GlassOS console, which refuses both
 * `SetSpeed` and `SetIncline` outside `RUNNING` in as many words.
 *
 * ## Why a zero speed is not enough either — issue #34
 *
 * `observedSpeedMph == 0.0` looks like the honest reading this should turn on, and on the X22i it
 * is a lie. `ACTUAL_KPH` reads exactly `0x0000` on every poll while a rider walks at 4 mph, with
 * `CURRENT_DISTANCE` accumulating the real pace beside it. **Not null, either.** A gate that only
 * refuses on null accepts that console's confident, well-formed zero and flattens the deck under a
 * moving belt, which is the same hazard the ack version had, reached through a different door and
 * with no branch to limit it.
 *
 * Two findings say this cannot be repaired by asking differently:
 *
 * - **iFit never reads that register on a treadmill.** `SpeedMetric` selects `LatestBasicInfo.Kph`
 *   — field 0, the commanded *setpoint* — for belt-based consoles, and `ActualKph` (16) only for
 *   non-belt ones. So iFit displaying a correct speed on this machine says nothing about whether
 *   field 16 works; it would look right either way. Stride is the first client to read it honestly,
 *   which is why Stride is the first to see the zeros.
 * - **There is no per-field validity marker.** `ReadWriteDataCmd.SetResponseBytes` checks command
 *   status and total length, then consumes raw bytes in field order. Nothing distinguishes "the
 *   value is zero" from "I do not have this value", so the two are identical on the wire by
 *   construction.
 *
 * So the reading alone is not the question. [everReportedMotion] asks whether this console's speed
 * register has *ever* said anything but zero on this link; until it has, a zero from it is
 * indistinguishable from a register stuck at zero and is worth nothing. See
 * [MachineLink.everReportedMotion].
 *
 * `Rpm` is field 5 and read-only on FitPro1, but the X22i reports it unsupported. A future real
 * motion signal on this console therefore has to come from another measured register rather than
 * from the commanded speed.
 *
 * Corroborating against `CURRENT_DISTANCE` failing to advance would work too, and issue #39's
 * [stopVerdict] now does exactly that for the stronger question of whether a *stop* is confirmed —
 * but only in the one direction that is safe under an unmeasured quantum: distance advancing may
 * refuse a confirmation, distance standing still may never grant one. The same veto would tighten
 * this gate and has deliberately been left off it, so that #39 could not change #36's behaviour on
 * the way past. Either would let this be strengthened. It must not be weakened.
 *
 * **Null is not permission.** [MachineLink.observedSpeedMph] is null when the snapshot is stale or the
 * machine could not be asked, and "we cannot see the belt" has to mean "do not move anything",
 * matching the rule the start path already holds itself to: probably-stopped is not a basis for
 * moving a treadmill. The cost of being wrong in this direction is a deck left on a hill, which is
 * exactly where it sat before any of this existed.
 */
internal fun mayFlattenDeck(observedSpeedMph: Double?, everReportedMotion: Boolean): Boolean =
    everReportedMotion && observedSpeedMph != null && observedSpeedMph <= BELT_MOVING_MPH
