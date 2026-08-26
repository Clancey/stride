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
        val run: () -> Outcome,
    )

    private val queue = LinkedBlockingDeque<Job>()
    private val generation = AtomicInteger(0)

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
        queue.clear()
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
    fun startWorkout(onDone: ((Outcome) -> Unit)? = null) = submit("Start workout", onDone = onDone) {
        val gen = generation.get()
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

        val moving = (MachineLink.speedMph ?: 0.0) > BELT_MOVING_MPH
        if (shouldAdoptWorkout(state, MachineLink.speedMph)) {
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
     */
    fun stop() {
        val gen = generation.incrementAndGet()
        queue.clear()
        queue.addFirst(
            Job(gen, STOP_LABEL) {
                val c = commands ?: return@Job Outcome.Failed("No link to the console")
                c.stop().toOutcome()
            },
        )
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
        val current = MachineLink.speedMph
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
     * overlap a speed write on the wire. The last state is remembered here rather than read back,
     * because the rider's choice should survive a console that reports the fan lazily.
     */
    fun setFan(state: Int) {
        lastFanState = state
        submit("Fan ${GlassOsCommands.fanStateName(state)}") { it.setFanState(state).toOutcome() }
    }

    /**
     * The fan state Stride last asked for, or null if it has not asked this run.
     *
     * Kept in memory only; [StrideSettings] owns the value that outlives the process.
     */
    @Volatile
    var lastFanState: Int? = null
        private set

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
    fun restoreFan(remembered: Int?) {
        submit("Restore fan") { commands ->
            val speculative = remembered == null
            val target = remembered
                ?: if (commands.autoFanSupported() != false) GlassOsCommands.FAN_AUTO else null
                ?: return@submit Outcome.Ok
            val ack = commands.setFanState(target)
            // Only remember a state the machine actually took. Recording a speculative Auto that was
            // refused would make every later restore replay a command this console has rejected.
            if (ack is MachineAck.Ok || !speculative) lastFanState = target
            if (speculative && ack is MachineAck.Refused) Outcome.Ok else ack.toOutcome()
        }
    }

    // -------------------------------------------------------------- plumbing

    private fun submit(
        label: String,
        delayMs: Long = 0L,
        onDone: ((Outcome) -> Unit)? = null,
        speedGen: Int? = null,
        // MachineCommands, not GlassOsCommands: the coordinator owns the clamps and the ramp and
        // must not know which wire is underneath it. That split is what lets the direct register
        // path substitute for GlassOS without a second copy of any of the safety rules.
        run: (MachineCommands) -> Outcome,
    ) {
        val gen = generation.get()
        queue.addLast(
            Job(gen, label, onDone, speedGen) {
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
            lastLabel = job.label
            lastOutcome = outcome
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
private const val BELT_MOVING_MPH = 0.1
