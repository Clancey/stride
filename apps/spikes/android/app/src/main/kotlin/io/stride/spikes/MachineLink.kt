package io.stride.spikes

/**
 * Everything Stride knows about the physical machine — which, in this build, is nothing.
 *
 * This exists to make "we are not connected" a *structural* fact rather than a convention someone
 * has to remember. There is no GlassOS client and no motor path in Phase 0, so every reading below
 * is null and [canCommand] is false. UI reads these values and renders the absence honestly.
 *
 * The rule this type enforces: **a null reading must never be drawn as a number.** Rendering `0.0`
 * for an unknown speed is not a placeholder, it is a false statement, and next to a treadmill it is
 * a false statement that reads as "the belt is stopped". Draw [NO_READING] instead.
 *
 * [NO_READING] is deliberately words and not a dash. A safety review pointed out that "—" is only
 * half a fix: it stops claiming zero, but a glance still reads it as nothing/empty/none, which next
 * to a belt is the same wrong answer. The person reading this may already be running. Say the thing.
 *
 * When Phase 1 lands the real GlassOS link (plan section 3.1 / spike S2-B), this becomes an
 * interface with a connected implementation and the UI above it does not have to change shape:
 * readings turn non-null, [canCommand] turns true once the Coordinator's clamps and watchdog are in
 * place, and controls unlock. Until then, nothing here can be made to lie by a UI bug.
 */
object MachineLink {

    /** What to draw when a reading is unknown. Never substitute a zero, and never a bare dash. */
    const val NO_READING: String = "Not measured"

    /**
     * The sentence that must appear on any surface showing machine metrics. "Unknown" is not the
     * same claim as "unknown, and the thing you are standing on may still be moving".
     */
    const val CANNOT_READ_NOTICE: String =
        "Stride can't read the treadmill. The belt may be moving."

    /** The sentence that must appear on any surface offering workout actions. */
    const val NO_CONTROL_NOTICE: String =
        "Stride doesn't control the treadmill. Use the console's own controls or the safety key."

    /** What a disabled machine control says when someone taps it. It must never just swallow it. */
    const val CONTROL_LOCKED_NOTICE: String =
        "Stride can't control the belt yet. Use the console's own controls."

    enum class Status {
        /** No transport, no credentials, no telemetry. The only state Phase 0 can be in. */
        DISCONNECTED,
    }

    val status: Status = Status.DISCONNECTED

    /**
     * Why we are disconnected, in words a person on the machine can act on — not an error code.
     */
    const val DISCONNECTED_REASON: String =
        "Stride is not linked to this machine yet. Speed, incline and fan stay on the console."

    /** Current belt speed. Null means unknown — see the class note before drawing it. */
    val speedMph: Double? = null

    /** Current incline percent. Null means unknown. */
    val inclinePercent: Double? = null

    /** Distance covered this session. Null means unknown; we cannot derive it without telemetry. */
    val distanceMiles: Double? = null

    /** Instantaneous pace. Null means unknown. */
    val paceMinPerMile: Double? = null

    /** Fan speed, 0..[FAN_MAX]. Null means unknown — the fan is a machine peripheral, not ours. */
    val fanLevel: Int? = null

    const val FAN_MAX: Int = 3

    /**
     * Whether Stride may send *any* command to the machine.
     *
     * Hardcoded false, and it must stay a `fun` returning a literal so that no code path — no
     * setter, no test double, no debug flag — can flip it at runtime.
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
     * Until every one of those is true, this returns false and the controls above it stay inert.
     */
    fun canCommand(): Boolean = false
}
