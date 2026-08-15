package io.stride.spikes

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Everything Stride knows about the physical machine.
 *
 * This exists to make "we cannot read the machine" and "we cannot move the machine" *structural*
 * facts rather than conventions someone has to remember. Those two are now separate: this build
 * can read the treadmill over GlassOS, and still cannot command it. [canCommand] stays false.
 *
 * The rule this type enforces: **a null reading must never be drawn as a number.** Rendering `0.0`
 * for an unknown speed is not a placeholder, it is a false statement, and next to a treadmill it is
 * a false statement that reads as "the belt is stopped". Draw [NO_READING] instead.
 *
 * [NO_READING] is deliberately words and not a dash. A safety review pointed out that "—" is only
 * half a fix: it stops claiming zero, but a glance still reads it as nothing/empty/none, which next
 * to a belt is the same wrong answer. The person reading this may already be running. Say the thing.
 *
 * ## Why readings can go back to null
 *
 * A reading is only served while it is *fresh*. If polling stalls — GlassOS restarts, the link
 * drops, the thread is starved — the last good numbers are discarded and the UI returns to
 * [NO_READING]. A number that has quietly stopped updating is more dangerous than no number,
 * because it looks exactly like a number that is still true. This is the seed of the telemetry
 * watchdog in plan section 3.1; here it only governs what is displayed, since nothing can be
 * commanded yet.
 */
object MachineLink {

    /** What to draw when a reading is unknown. Never substitute a zero, and never a bare dash. */
    const val NO_READING: String = "Not measured"

    /**
     * The sentence that must appear on any surface showing machine metrics *while unlinked*.
     * "Unknown" is not the same claim as "unknown, and the thing you are standing on may still be
     * moving".
     */
    const val CANNOT_READ_NOTICE: String =
        "Stride can't read the treadmill. The belt may be moving."

    /**
     * The sentence for a linked machine Stride can command.
     *
     * It used to read "Stride doesn't control the treadmill", which was true when nothing here
     * could move the belt and became a lie the moment [MachineCoordinator] shipped. The warning
     * that survives is the one that is still true and still matters: software stop is best-effort,
     * and the key is not.
     */
    const val SAFETY_KEY_NOTICE: String =
        "The safety key is the only emergency stop. Stride's stop is best-effort."

    /** What a disabled machine control says when someone taps it. It must never just swallow it. */
    const val CONTROL_LOCKED_NOTICE: String =
        "Stride can't reach the console right now. Use the console's own controls."

    /**
     * What a control says when the link is fine but the machine is declining setpoints.
     *
     * Almost always because there is no workout: the console accepts speed and incline while a
     * workout is live and refuses them from idle or from the results screen. Saying so is the
     * difference between a broken-looking app and one telling the rider the single thing that
     * would make the button work.
     */
    const val CONTROL_NEEDS_WORKOUT_NOTICE: String =
        "The treadmill won't change speed or incline until a workout is running. Start one first."

    /** What the direct path says when no cable or radio was found at all. */
    const val DIRECT_NO_TRANSPORT: String =
        "No direct connection to the treadmill. Stride checked the USB port and Bluetooth and found " +
            "nothing to talk to."

    /** What the direct path says when a transport exists but nothing on it answered. */
    const val DIRECT_NO_ANSWER: String =
        "Stride found a connection but the treadmill didn't answer. Switch back to GlassOS to keep " +
            "using the console."

    /**
     * What a control says when GlassOS is answering but has no machine attached to it.
     *
     * Its own state, not a guess: the console reports [GlassOsClient.ConsoleState.DISCONNECTED]
     * and every RPC that would move something blocks until it times out. Distinct from
     * [CONTROL_LOCKED_NOTICE] because the fix is different — nothing about Stride or the app will
     * recover this, only the machine coming back will.
     */
    const val CONSOLE_DETACHED_NOTICE: String =
        "The console has lost its connection to the treadmill. Nothing can reach the belt until " +
            "the machine is power-cycled at the wall."

    /**
     * The sentence to show for a console with no machine behind it.
     *
     * Deliberately not the "not linked yet" wording: Stride *is* linked, to a daemon that has
     * nothing to command, and sending a rider to check the app is sending them to the wrong place.
     */
    const val CONSOLE_DETACHED_REASON: String =
        "GlassOS is answering, but the console reports no treadmill attached. Speed, incline and " +
            "the belt itself are unreachable until the machine is power-cycled at the wall."

    /**
     * The safety sentence to print beside a metric readout, chosen by what is actually true.
     *
     * Printing [CANNOT_READ_NOTICE] next to live numbers would be a visible contradiction, and the
     * cost is not cosmetic: safety copy that is obviously wrong in the easy case is not believed in
     * the hard case. When we can read, the honest warning is the one about *control*.
     */
    val metricsNotice: String
        get() = when {
            consoleDetached -> CONSOLE_DETACHED_NOTICE
            status == Status.LINKED -> SAFETY_KEY_NOTICE
            else -> CANNOT_READ_NOTICE
        }

    enum class Status {
        /** No transport, no credentials, or no fresh telemetry. */
        DISCONNECTED,

        /**
         * Fresh telemetry is arriving over a live transport.
         *
         * This says nothing about whether a particular command will be accepted — the machine can
         * still refuse a write depending on its own state. It means only that the link is good
         * enough to try, which is why every command still returns an outcome.
         */
        LINKED,
    }

    /** How long a reading stays believable after the last successful poll. */
    private const val FRESHNESS_MS = 4_000L

    /** Log tag. */
    private const val TAG = "MachineLink"

    /**
     * How long a successful handshake is taken at its word.
     *
     * Short, because it is not a cache of the console's state — it is a guard against two callers
     * racing to shake hands with the same daemon in the same breath. The poll learns a connect
     * succeeded on its next pass at most two seconds later; until then a start arriving would
     * otherwise issue a second, redundant handshake and queue behind the first.
     */
    private const val CONNECT_SUCCESS_TTL_MS = 2_000L

    /**
     * What a console handshake produced.
     *
     * Typed rather than a nullable state code because three of these were previously conflated as
     * "not null, so we are attached", and the difference between them is the difference between a
     * treadmill that starts and one that hangs for a minute before refusing.
     */
    sealed class ConnectResult {
        /** GlassOS handed over a console, in this state. */
        data class Attached(val state: Int) : ConnectResult()

        /** GlassOS answered, and has no machine to give us. */
        object Disconnected : ConnectResult()

        /** GlassOS did not answer at all: not running, not listening, or timed out. */
        object NoAnswer : ConnectResult()

        /** Skipped, because a handshake attached moments ago and still stands. */
        object AttachedRecently : ConnectResult()

        /** True when the console is ours to command. */
        val attached: Boolean
            get() = this is Attached || this is AttachedRecently
    }

    @Volatile private var connectFailures: Int = 0
    @Volatile private var nextConnectAt: Long = 0L
    @Volatile private var lastAttachedAt: Long = 0L

    /**
     * Whether a handshake is already queued or running.
     *
     * Without this the poll posts a fresh attempt every two seconds while one is blocked, and a
     * handshake against a console that is not answering blocks for twelve. The queue grows faster
     * than it drains, and the rider's Start — which shares this lock — ends up waiting behind a
     * backlog of attempts that were all asking the same question.
     */
    @Volatile private var connectInFlight: Boolean = false

    private val connectLock = Any()

    /**
     * Metres per second to miles per hour. GlassOS reports speed presets as MPS
     * ([GlassOsClient.ControlType.MPS]); this is the exact factor (1 / 0.44704), not a rounded
     * 2.24, so a 12.0 mph preset reads back as 12.0 and not 12.01.
     */
    const val MPS_TO_MPH = 2.2369362920544

    @Volatile private var snapshot: GlassOsClient.Snapshot? = null
    @Volatile private var snapshotAt: Long = 0L
    @Volatile private var client: GlassOsClient? = null

    /**
     * The direct path, when [StrideSettings.transport] selects it. Null on the GlassOS path.
     *
     * Held separately from [client] rather than behind a shared interface because the two are not
     * interchangeable: only GlassOS has quick-pick presets, and only the direct session has a
     * handshake whose result the rider needs to see.
     */
    @Volatile private var directSession: DirectMachineSession? = null
    @Volatile private var direct: DirectMachineClient? = null

    /**
     * What the direct handshake concluded, in a sentence fit to show a rider. Null on the GlassOS
     * path or before the attempt has finished.
     */
    @Volatile
    var directDetail: String? = null
        private set

    @Volatile private var inclinePresetsCache: List<Double>? = null
    @Volatile private var speedPresetsCache: List<Double>? = null
    // Distinct from the caches being null: null there means "no presets", this means "not asked".
    @Volatile private var presetsFetched: Boolean = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var appContext: Context? = null

    private var connectThread: HandlerThread? = null
    private var connectHandler: Handler? = null

    /** Null unless we hold a snapshot that is still fresh. Every reading below goes through this. */
    private fun fresh(): GlassOsClient.Snapshot? {
        val s = snapshot ?: return null
        if (System.currentTimeMillis() - snapshotAt > FRESHNESS_MS) return null
        return s
    }

    val status: Status
        get() = if (fresh() != null && !consoleDetached) Status.LINKED else Status.DISCONNECTED

    /**
     * True when a *fresh* read says the daemon has no machine attached.
     *
     * Positive knowledge only. This is the console explicitly reporting DISCONNECTED, never an
     * absent reply or a stale snapshot, because [MachineCoordinator] refuses commands on it and a
     * missed poll must not be allowed to lock a rider out of their own belt.
     */
    val consoleDetached: Boolean
        get() = fresh()?.consoleState == GlassOsClient.ConsoleState.DISCONNECTED_NAME

    /**
     * Why we are in this state, in words a person on the machine can act on — not an error code.
     * This used to lead with what Stride would *not* do, which was the honest thing to say when
     * nothing here could move a belt. Stride drives the machine now, so saying otherwise would
     * send a rider to the console for something they can do under their thumb.
     */
    val reason: String
        get() = when {
            consoleDetached -> CONSOLE_DETACHED_REASON
            status == Status.LINKED ->
                "Stride is linked to this machine. " +
                    "Speed, incline and fan respond here or on the console."
            else -> DISCONNECTED_REASON
        }

    /**
     * Why we are disconnected, in words a person on the machine can act on — not an error code.
     */
    const val DISCONNECTED_REASON: String =
        "Stride is not linked to this machine yet. Speed, incline and fan stay on the console."

    /** Current belt speed. Null means unknown — see the class note before drawing it. */
    val speedMph: Double? get() = fresh()?.speedMph

    /** Current incline percent. Null means unknown. */
    val inclinePercent: Double? get() = fresh()?.inclinePercent

    /** Distance covered this session, as measured by the machine. Null means unknown. */
    val distanceMiles: Double? get() = fresh()?.distanceMiles

    /** Instantaneous pace, derived from measured speed only. Null means unknown. */
    val paceMinPerMile: Double? get() = fresh()?.paceMinPerMile

    /** Calories as the machine estimates them. Null means unknown. */
    val calories: Double? get() = fresh()?.calories

    /** Workout seconds as the machine counts them. Null means unknown. */
    val elapsedSeconds: Long? get() = fresh()?.elapsedSeconds

    /** The console's own state, e.g. IDLE, WARM_UP, WORKOUT, SAFETY_KEY_REMOVED. */
    val consoleState: String? get() = fresh()?.consoleState

    /**
     * Whether the machine says it will accept a speed / incline / fan write right now.
     *
     * Null means it has not answered, which is *not* "no" — see [GlassOsClient] — and is why the
     * `canCommand*` helpers below only refuse on an explicit false.
     */
    val speedWritable: Boolean? get() = fresh()?.speedWritable
    val inclineWritable: Boolean? get() = fresh()?.inclineWritable
    val fanWritable: Boolean? get() = fresh()?.fanWritable

    /**
     * The console's incline quick-pick presets, in **percent**, highest first. Null until they have
     * been fetched, or when the machine reports none — never a fabricated fallback list, because a
     * button offering an incline the machine did not is worse than no button at all.
     */
    val inclinePresets: List<Double>? get() = inclinePresetsCache

    /**
     * The console's speed quick-pick presets, in **miles per hour**, highest first. Null until
     * fetched, or when the machine reports none. GlassOS reports these as MPS; see [MPS_TO_MPH].
     */
    val speedPresets: List<Double>? get() = speedPresetsCache

    /**
     * Whether the machine says the belt may be under power. Null means we do not know, which is
     * *not* the same as "no", and callers must not collapse it to one.
     */
    val beltMayBeMoving: Boolean?
        get() = fresh()?.let { GlassOsClient.ConsoleState.beltMayBeMoving(it.consoleState) }

    /**
     * Fan speed, 0..[FAN_MAX], or null when we do not know.
     *
     * Null on the GlassOS path: it exposes a fan service, but this build does not read it, and a fan
     * number nobody has checked against the physical fan is worth less than an honest blank. The
     * direct path does read it, from whichever fan register the machine said it implements.
     */
    val fanLevel: Int? get() = fresh()?.fanLevel

    const val FAN_MAX: Int = 3

    // ---------------------------------------------------------------- polling

    /**
     * Begin reading the machine. Safe to call repeatedly, and safe on a device where GlassOS does
     * not exist — there it simply never produces a snapshot and everything stays [NO_READING].
     *
     * Deliberately process-scoped and *not* torn down when the overlay service or the launcher
     * activity goes away. Both attach, either can be the surface on screen, and one 2-second poll
     * on a background thread is far cheaper than a metric readout that blanks out because the
     * component that happened to own the link was destroyed. [detach] exists for tests and for a
     * future explicit unlink.
     */
    fun attach(context: Context) {
        if (thread != null) return
        val app = context.applicationContext
        appContext = app
        val t = HandlerThread("machine-link").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        // A second thread purely for the console handshake. Connect against a console with nothing
        // attached blocks for the full command timeout, and doing that on the poll thread would
        // stop telemetry for twelve seconds at a stretch — long enough for every reading to go
        // stale and the whole top strip to fall back to "Not measured" on a machine that is
        // running fine. The poll must never wait for the recovery of something it only reports on.
        val c = HandlerThread("machine-connect").also { it.start() }
        connectThread = c
        connectHandler = Handler(c.looper)
        // Opening the transport is blocking I/O — USB enumeration, a BLE connect, and a multi-frame
        // handshake — so it happens on the link thread rather than on whoever called attach.
        h.post { openTransport(app) }
        h.post(poll)
        // Shake hands immediately, in parallel with the first poll rather than after it.
        //
        // This is the difference between a treadmill that starts when the rider presses Start and
        // one that starts ten seconds later. GlassOS hands over machine control only to a client
        // that has called Connect, and there is nothing to learn from a poll first: we have a
        // client here, so this is the earliest moment the handshake can possibly go out. Waiting
        // for a reading to come back and say "disconnected" only spent the rider's time confirming
        // something we were going to do anyway.
        reconnect()
    }

    fun detach() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        connectHandler?.removeCallbacksAndMessages(null)
        connectThread?.quitSafely()
        connectThread = null
        connectHandler = null
        closeTransport()
        appContext = null
    }

    /**
     * Re-open the link against whatever [StrideSettings.transport] now says.
     *
     * Called when the rider changes the transport. Everything measured through the old path is
     * dropped rather than carried across, because a speed read from GlassOS is not evidence about a
     * direct link, and a stale reading is the one thing this object exists to prevent.
     */
    fun retarget() {
        val app = appContext ?: return
        handler?.post {
            closeTransport()
            openTransport(app)
            // Connect immediately rather than waiting for a poll to notice. A rider who flips the
            // switch is watching the screen right then, and the difference between "controls live
            // now" and "controls live in up to two seconds" is the difference between the setting
            // looking like it worked and looking broken. closeTransport() has already cleared the
            // backoff and the success TTL, so this attempt is never skipped as too-soon or
            // short-circuited by the previous transport's handshake.
            reconnect()
        }
    }

    /**
     * Point the link at whichever transport the rider has chosen.
     *
     * Exactly one of [client] and [direct] is ever non-null, and that is the mechanism — not a
     * convention — by which DIRECT sends nothing to GlassOS. In DIRECT there is no [GlassOsClient]
     * in existence to send anything with: no poll, no handshake, no preset fetch, no stray read.
     * The rest of this object asks its questions through [MachineCoordinator], which holds one
     * [MachineCommands] and neither knows nor exposes which wire is behind it.
     */
    private fun openTransport(app: Context) {
        StrideSettings.attach(app)
        when (StrideSettings.transport) {
            StrideSettings.Transport.GLASSOS -> {
                val c = GlassOsClient(app)
                client = c
                // GlassOS cannot be asked for the machine's limits, so the fixed ceiling stands.
                MachineCoordinator.applyMachineLimits(null)
                MachineCoordinator.rebind(GlassOsCommands(c))
            }
            StrideSettings.Transport.DIRECT -> openDirect(app)
        }
    }

    /**
     * Bring up the direct path: find a transport, greet the machine, and only then hand the
     * coordinator something that can move a belt.
     *
     * Every failure leaves the coordinator unbound rather than bound to a half-open session. An
     * unbound coordinator refuses commands, which is the correct answer when we could not establish
     * that the console understands us.
     */
    private fun openDirect(app: Context) {
        val transport = try {
            FitProTransport.open(app)
        } catch (t: Throwable) {
            Log.w(TAG, "direct transport failed to open", t)
            null
        }
        if (transport == null) {
            directDetail = DIRECT_NO_TRANSPORT
            MachineCoordinator.rebind(null)
            return
        }

        val session = DirectMachineSession(transport)
        directSession = session
        val result = try {
            // No reference reading is available at startup: nobody has told us what the console
            // shows. The probe can still confirm the link answers and read the machine's limits; it
            // simply cannot reach VALUES_CONFIRMED until someone checks a number against the panel.
            session.connect(reference = null)
        } catch (t: Throwable) {
            Log.w(TAG, "direct handshake failed", t)
            null
        }

        if (result == null) {
            directDetail = DIRECT_NO_ANSWER
            session.close()
            directSession = null
            MachineCoordinator.rebind(null)
            return
        }

        directDetail = result.detail
        direct = DirectMachineClient(session)
        // The machine's own ceiling, so the clamp becomes the intersection of ours and theirs.
        MachineCoordinator.applyMachineLimits(session.probe.limits)
        MachineCoordinator.rebind(DirectMachineCommands(session))
    }

    private fun closeTransport() {
        MachineCoordinator.rebind(null)
        direct = null
        directSession?.let { runCatching { it.close() } }
        directSession = null
        directDetail = null
        client = null
        snapshot = null
        snapshotAt = 0L
        inclinePresetsCache = null
        speedPresetsCache = null
        presetsFetched = false
        synchronized(connectLock) {
            connectFailures = 0
            nextConnectAt = 0L
            lastAttachedAt = 0L
            // Any handshake still in flight belongs to a link that no longer exists. Clearing
            // the flag here rather than waiting for it to finish means a re-attach is never
            // blocked by the tail of the previous one.
            connectInFlight = false
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val read = try {
                client?.read() ?: direct?.read()
            } catch (t: Throwable) {
                // A failed read means "we do not know". Never a crash, and never a stale number.
                null
            }
            if (read != null) {
                snapshot = read
                snapshotAt = System.currentTimeMillis()
                // Presets are static for the machine, so fetch them once on the same background
                // thread as the poll rather than inventing a second worker. A read having just
                // succeeded means the link is up, so this is the cheapest moment to try.
                fetchPresetsOnce()
            } else {
                reopenDirectIfDropped()
            }
            if (read?.consoleState == GlassOsClient.ConsoleState.DISCONNECTED_NAME || read == null) {
                // Also on a read that failed outright, not only on one that came back saying
                // "disconnected". During boot GlassOS is not listening yet, so the read does not
                // return an answer — it returns nothing at all, and treating that as "no news"
                // meant waiting for the daemon to come up *and* for a poll to complete before the
                // handshake was even attempted. A refused socket fails in about a millisecond, so
                // retrying on it is nearly free and it is the case that catches GlassOS starting.
                reconnect()
            }
            // Poll faster while the machine says it may be moving. There is no reason to hammer a
            // console sitting idle, and no excuse for a laggy readout while someone is running.
            val moving = read?.let { GlassOsClient.ConsoleState.beltMayBeMoving(it.consoleState) }
            handler?.postDelayed(this, if (moving == true) 500L else 2_000L)
        }
    }

    /**
     * Ask the machine to attach.
     *
     * Fired at [attach] and again from the poll whenever a reading says nothing is attached, or no
     * reading comes back at all. Done repeatedly rather than once at startup because the console
     * can lose the machine at any time — a reboot, a sleep, the iFit app disconnecting when it
     * exits — and a rider should not have to know that the cure is to open a different app and come
     * back.
     *
     * Runs on its own thread and never blocks the caller. Backed off by [RECONNECT_BACKOFF_MS], so
     * a console with nothing behind it is not asked twelve seconds' worth of questions every two
     * seconds. Failures are left to the next attempt: there is nothing to tell the rider that the
     * machine cell is not already saying.
     */
    private fun reconnect() {
        // Both guards matter, and they are different questions: "is one already running" and "is it
        // too soon to ask again". Checking only the clock let every poll pile another attempt onto
        // the connect thread while the first was still blocked.
        synchronized(connectLock) {
            if (connectInFlight) return
            if (SystemClock.elapsedRealtime() < nextConnectAt) return
            connectInFlight = true
        }
        val posted = connectHandler?.post {
            try {
                connectNow()
            } finally {
                synchronized(connectLock) { connectInFlight = false }
            }
        } ?: false
        // A handler that has gone away (detached, or quitting) would otherwise leave the flag set
        // and stop every future attempt.
        if (!posted) synchronized(connectLock) { connectInFlight = false }
    }

    /**
     * Perform the console handshake, and report the state it returned.
     *
     * Blocking, serialised, and shared by both callers that need a console attached: the poll's
     * [reconnect] and the start path in [MachineCoordinator]. One entry point rather than two
     * because they can otherwise fire within the same breath — the poll notices a disconnected
     * console at the same moment the rider presses Start — and two handshakes racing on one daemon
     * is strictly worse than one, since the second waits for the first and then repeats its work.
     *
     * A handshake that has just attached is taken at its word for [CONNECT_SUCCESS_TTL_MS]. The
     * snapshot cannot answer this question: it is up to a poll interval old, so immediately after a
     * successful connect it still reads DISCONNECTED and would send the rider's start into a
     * pointless second handshake.
     */
    fun connectNow(): ConnectResult = synchronized(connectLock) {
        val now = SystemClock.elapsedRealtime()
        if (lastAttachedAt != 0L && now - lastAttachedAt < CONNECT_SUCCESS_TTL_MS) {
            return ConnectResult.AttachedRecently
        }
        val state = try {
            MachineCoordinator.connectConsole()
        } catch (t: Throwable) {
            Log.w(TAG, "console connect attempt failed", t)
            null
        }
        // Timed from here, after the call returned, so a handshake that blocked for the full
        // command timeout is not immediately followed by another.
        val done = SystemClock.elapsedRealtime()
        val result = when {
            state == null -> ConnectResult.NoAnswer
            // A reply of DISCONNECTED is GlassOS answering politely that it has nothing to give us.
            // Counting it as success was the subtler half of the original bug: it reset the backoff
            // and let a start march on into RPCs that can only block for the full timeout and fail.
            // The call worked. The handshake did not.
            state == GlassOsClient.ConsoleState.DISCONNECTED -> ConnectResult.Disconnected
            else -> ConnectResult.Attached(state)
        }
        if (result is ConnectResult.Attached) {
            lastAttachedAt = done
            connectFailures = 0
            nextConnectAt = 0L
        } else {
            connectFailures++
            nextConnectAt = done + connectBackoffMs(connectFailures)
        }
        Log.i(TAG, "console Connect -> $result after ${done - now}ms")
        result
    }

    /**
     * Re-run the handshake when the cable or radio has gone away and come back.
     *
     * Only fires once the transport itself reports it is down, so a console that merely declined one
     * poll does not get torn off mid-run. Reconnecting deliberately goes through the full [connect]
     * again rather than resuming: the probe is reset, the coordinator's generation is bumped, and no
     * previously requested speed can survive the gap. That is checklist item 7 — reconnection must
     * not replay a target — enforced by construction rather than by remembering to.
     */
    private fun reopenDirectIfDropped() {
        val session = directSession ?: return
        if (session.connected) return
        val app = appContext ?: return
        Log.i(TAG, "direct transport dropped; re-running handshake")
        closeTransport()
        openTransport(app)
    }

    /**
     * Fetch the quick-pick presets exactly once per link.
     *
     * Asked through [MachineCoordinator.ask] rather than through a GlassOS client, which is what
     * makes this work identically on both transports: GlassOS answers from the console's published
     * control list, the direct path answers from the machine's own `MIN_KPH`/`MAX_KPH` and
     * `MIN_GRADE`/`MAX_GRADE` registers. The rider gets quick picks either way, and this function
     * does not know or care which happened.
     *
     * A null answer means the question could not be asked, and leaves [presetsFetched] false so a
     * later poll retries. Only a real list — even an empty one — counts as fetched. An empty list is
     * stored as null so callers see one "no presets" signal rather than two.
     */
    private fun fetchPresetsOnce() {
        if (presetsFetched) return
        val incline = MachineCoordinator.ask { it.inclinePresets() } ?: return
        val speed = MachineCoordinator.ask { it.speedPresetsMph() } ?: return
        inclinePresetsCache = incline.takeIf { it.isNotEmpty() }
        speedPresetsCache = speed.takeIf { it.isNotEmpty() }
        presetsFetched = true
    }

    /**
     * Whether Stride may send *any* command to the machine.
     *
     * Hardcoded false, and it must stay a `fun` returning a literal so that no code path — no
     * setter, no test double, no debug flag — can flip it at runtime.
     *
     * **Reading the machine does not move this any closer to true, and must not be mistaken for
     * progress toward it.** The link above is built on a client with no command methods at all, so
     * the fact that live speed now appears on screen says nothing about our readiness to change it.
     *
     * **This single Boolean is not the real safety boundary and must not become one.** A safety
     * review made the point sharply: one edit here would enable every command at once, regardless of
     * telemetry freshness, machine identity, exclusive-client ownership, or watchdog health. When
     * Phase 1 lands, this does not become `true`; it is *replaced* by per-capability authority
     * granted by the Control & Safety Coordinator (plan section 3.1), where each of speed, incline
     * and fan is separately armed, short-lived, and revoked the moment its preconditions lapse.
     *
     * The checklist below is written here, next to the line someone will be tempted to change,
     * because it is cheap to state now and expensive to retrofit. Every item must be true on real
     * hardware, under a safety harness with a person at the physical stop key, before any command
     * path ships:
     *
     *  1. The machine model and firmware are positively identified — not assumed from the plan.
     *     (Partly answered: ConsoleService reports model 17125, 1.0–12.0 mph, -3–12% incline.)
     *  2. Exclusive-client behaviour is known: what happens when iFit and Stride both hold a session.
     *  3. Belt behaviour is documented for every way we can die — Flutter engine death, overlay
     *     service death, process kill, GlassOS death, reboot, link timeout, concurrent clients.
     *     Specifically: **does the belt keep moving when the controlling client disappears?**
     *  4. Commands are bounded in both absolute range and rate of change, clamped below the machine's
     *     own limits, not at them.
     *  5. Acknowledgements are correlated to the specific request that caused them, and telemetry
     *     confirms the machine actually reached the state — requested is not confirmed.
     *  6. Stale, duplicated, reordered and late messages provably cannot cause motion.
     *  7. Reconnection cannot replay a previous speed or incline target. Nothing is ever queued
     *     across a disconnect.
     *  8. The physical safety key and the console's native controls still work with Stride running,
     *     verified by use, not by reasoning.
     *  9. The UI distinguishes requested / confirmed / unknown, and never shows a requested value
     *     styled as a measured one.
     *
     * Most of that checklist is now satisfied and control has shipped, so this no longer returns a
     * hardcoded false. It is still **not** the safety boundary: it answers only "is there a live,
     * fresh link a command could travel over". Every clamp, ramp, generation check and stop
     * preemption lives in [MachineCoordinator], and a command that does not go through the
     * coordinator has none of them.
     *
     * Items 3 and 8 of the checklist above remain unverified by use. Until they are, the UI must
     * keep describing the physical safety key as the only true stop.
     */
    fun canCommand(): Boolean = MachineCoordinator.available

    /**
     * Whether one particular control is usable at this moment.
     *
     * Two conditions, and both are needed. [canCommand] answers "could a command travel", which is
     * about Stride's link; the writability flag answers "would the machine accept it", which is
     * about the console's state and is the reason an incline pill does nothing from an idle
     * console. A control that fails either must be drawn as unavailable rather than left live to
     * fail on tap — a button that looks pressable and moves nothing teaches the rider that Stride's
     * controls are unreliable, on a machine where that doubt matters.
     *
     * Only an *explicit* refusal disables. An unanswered `CanWrite` leaves the control live, so a
     * single dropped poll cannot lock a rider out of their own belt mid-run.
     */
    fun canCommandSpeed(): Boolean = canCommand() && speedWritable != false

    fun canCommandIncline(): Boolean = canCommand() && inclineWritable != false

    fun canCommandFan(): Boolean = canCommand() && fanWritable != false

    /**
     * Why a control is unavailable, in the words to show the rider who just tapped it.
     *
     * Ordered most specific first. The direct path knows a great deal about *why* it is not working
     * — no cable, nothing answering, a machine that listed the registers it implements and did not
     * include this one — and a rider who taps a dead incline pill deserves that sentence rather
     * than a generic "can't reach the console". [directDetail] is set by the handshake and is
     * already written for a rider to read.
     */
    fun unavailableReason(): String {
        // The direct path's own conclusion outranks the generic sentences, but only on the direct
        // path: CONSOLE_DETACHED_NOTICE talks about GlassOS having no treadmill attached, which is
        // not a thing that can be true when we are not talking to GlassOS at all.
        val detail = directDetail
        if (!canCommand() && detail != null) return detail
        return when {
            consoleDetached -> CONSOLE_DETACHED_NOTICE
            canCommand() -> CONTROL_NEEDS_WORKOUT_NOTICE
            else -> CONTROL_LOCKED_NOTICE
        }
    }

    /**
     * Why one specific control is unavailable.
     *
     * Split from [unavailableReason] because the answers genuinely differ: on the direct path the
     * machine itself reports which registers it implements, so "this treadmill has no fan control"
     * is a fact we hold rather than a guess. Saying that is the difference between a rider thinking
     * Stride is broken and a rider knowing their machine has no fan.
     */
    fun unavailableReason(control: Control): String {
        if (!canCommand()) return unavailableReason()
        val writable = when (control) {
            Control.SPEED -> speedWritable
            Control.INCLINE -> inclineWritable
            Control.FAN -> fanWritable
        }
        if (writable != false) return CONTROL_NEEDS_WORKOUT_NOTICE
        // Only the direct path can tell "the machine does not have this" from "not right now",
        // because only it has the supported-register list the machine sent during the handshake.
        val unsupported = directSession?.supports(control) == false
        return if (unsupported) {
            when (control) {
                Control.SPEED -> "This treadmill didn't list speed control as something it accepts."
                Control.INCLINE -> "This treadmill didn't list an incline motor, so Stride can't move it."
                Control.FAN -> "This treadmill didn't list a fan, so there's nothing for Stride to set."
            }
        } else {
            CONTROL_NEEDS_WORKOUT_NOTICE
        }
    }

    /** The three things a rider can ask the machine to change. */
    enum class Control { SPEED, INCLINE, FAN }

    /** Whether the direct handshake has completed and a session is bound. */
    val directLinked: Boolean get() = directSession != null

    /**
     * What the machine itself said it supports, for the settings screen to display.
     *
     * Null when the direct path has not been opened — which the screen must show as "not tried"
     * rather than "unsupported". The values are the machine's own answer, decoded from the
     * supported-register bitmask in its `DEVICE_INFO` reply, so this is reporting rather than
     * predicting. That distinction is the whole point: the screen used to state flatly that incline
     * and fan would not work, which was a guess, and on any machine that implements them it was
     * simply false.
     */
    fun directCapabilities(): Map<String, Any?>? {
        val session = directSession ?: return null
        return mapOf(
            "speed" to session.supports(Control.SPEED),
            "incline" to session.supports(Control.INCLINE),
            "fan" to session.supports(Control.FAN),
            "transport" to session.transportName,
        )
    }

}

/**
 * Backoff between console connect attempts, in milliseconds, given how many have failed in a row.
 *
 * Measured from the *end* of the previous attempt, not the start, because a failed `Connect`
 * against a console with nothing attached blocks for the full command timeout — timing from the
 * start would mean the gap had already elapsed by the time we learned the answer, and we would
 * retry in a tight loop.
 *
 * The front of this schedule is what makes a start feel instant. GlassOS finishes coming up at an
 * unpredictable moment during boot, so what matters is not how often we ask but how soon after it
 * becomes ready we ask again. A flat ten-second retry made the rider wait an average of five
 * seconds and up to ten for a console that was ready the whole time; the first few hundred
 * milliseconds here cost one cheap refused socket each and remove that wait entirely.
 *
 * The tail is what keeps it polite: a console that genuinely has nothing attached settles at eight
 * seconds, so it is not asked twelve seconds' worth of questions every two seconds.
 */
internal fun connectBackoffMs(failures: Int): Long {
    val schedule = longArrayOf(0L, 250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L)
    if (failures <= 0) return schedule[0]
    return schedule[failures.coerceAtMost(schedule.size - 1)]
}
