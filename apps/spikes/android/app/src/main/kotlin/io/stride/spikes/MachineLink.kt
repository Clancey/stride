package io.stride.spikes

import android.content.Context
import android.os.Handler
import android.os.HandlerThread

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
     * The safety sentence to print beside a metric readout, chosen by what is actually true.
     *
     * Printing [CANNOT_READ_NOTICE] next to live numbers would be a visible contradiction, and the
     * cost is not cosmetic: safety copy that is obviously wrong in the easy case is not believed in
     * the hard case. When we can read, the honest warning is the one about *control*.
     */
    val metricsNotice: String
        get() = when (status) {
            Status.LINKED -> SAFETY_KEY_NOTICE
            Status.DISCONNECTED -> CANNOT_READ_NOTICE
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

    /**
     * Metres per second to miles per hour. GlassOS reports speed presets as MPS
     * ([GlassOsClient.ControlType.MPS]); this is the exact factor (1 / 0.44704), not a rounded
     * 2.24, so a 12.0 mph preset reads back as 12.0 and not 12.01.
     */
    const val MPS_TO_MPH = 2.2369362920544

    @Volatile private var snapshot: GlassOsClient.Snapshot? = null
    @Volatile private var snapshotAt: Long = 0L
    @Volatile private var client: GlassOsClient? = null

    @Volatile private var inclinePresetsCache: List<Double>? = null
    @Volatile private var speedPresetsCache: List<Double>? = null
    // Distinct from the caches being null: null there means "no presets", this means "not asked".
    @Volatile private var presetsFetched: Boolean = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Null unless we hold a snapshot that is still fresh. Every reading below goes through this. */
    private fun fresh(): GlassOsClient.Snapshot? {
        val s = snapshot ?: return null
        if (System.currentTimeMillis() - snapshotAt > FRESHNESS_MS) return null
        return s
    }

    val status: Status
        get() = if (fresh() != null) Status.LINKED else Status.DISCONNECTED

    /**
     * Why we are in this state, in words a person on the machine can act on — not an error code.
     * Even when linked, the sentence still leads with what Stride will *not* do, because that is
     * the part that matters to someone standing on a belt.
     */
    val reason: String
        get() = when (status) {
            Status.LINKED ->
                "Stride is reading this machine, but doesn't control it. " +
                    "Speed, incline and fan stay on the console."
            Status.DISCONNECTED -> DISCONNECTED_REASON
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
     * Fan speed. Still null: GlassOS does expose a fan service, but this build does not read it,
     * and a fan number nobody has checked against the physical fan is worth less than an honest
     * blank.
     */
    val fanLevel: Int? = null

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
        client = GlassOsClient(context.applicationContext).also { MachineCoordinator.attach(it) }
        val t = HandlerThread("machine-link").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post(poll)
    }

    fun detach() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        client = null
        snapshot = null
        snapshotAt = 0L
        inclinePresetsCache = null
        speedPresetsCache = null
        presetsFetched = false
    }

    private val poll = object : Runnable {
        override fun run() {
            val read = try {
                client?.read()
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
            }
            // Poll faster while the machine says it may be moving. There is no reason to hammer a
            // console sitting idle, and no excuse for a laggy readout while someone is running.
            val moving = read?.let { GlassOsClient.ConsoleState.beltMayBeMoving(it.consoleState) }
            handler?.postDelayed(this, if (moving == true) 500L else 2_000L)
        }
    }

    /**
     * Fetch the quick-pick presets exactly once per link.
     *
     * On transport failure [GlassOsClient.controls] returns null and this leaves [presetsFetched]
     * false so a later poll retries; only a decoded `ControlList` (even an empty one) counts as
     * fetched. An empty shaped list is stored as null rather than as `emptyList()`, so callers see
     * the same "no presets" signal whether the machine listed none or matched none of the type.
     */
    private fun fetchPresetsOnce() {
        if (presetsFetched) return
        val c = client ?: return
        val incline = c.controls("InclineService") ?: return
        val speed = c.controls("SpeedService") ?: return
        inclinePresetsCache =
            shapePresets(incline, GlassOsClient.ControlType.INCLINE) { it }.takeIf { it.isNotEmpty() }
        speedPresetsCache =
            shapePresets(speed, GlassOsClient.ControlType.MPS) { it * MPS_TO_MPH }
                .takeIf { it.isNotEmpty() }
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
}
