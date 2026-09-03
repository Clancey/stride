package io.stride.spikes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * The one state in Stride that means **"USE THE SAFETY KEY"**.
 *
 * `docs/PLAN.md` §5.4 requires it: a stop is done on ack *plus* observed deceleration, and when
 * neither arrives the UI escalates here — never to "stopped". Until this existed there was no path
 * that reached that state at all, so the one outcome §5 mandates for an unconfirmed stop could not
 * be produced (issue #39).
 *
 * ## Why it is a latch and not a message
 *
 * A toast, a log line, or a card that closes when the rider taps anywhere are all ways of saying
 * something that can be missed. This is the sentence the whole safety story ends with, and §3.1
 * already describes the shape for its sibling: a safety-key removal "latches a stopped state
 * requiring explicit local reset". So does this. It is set by the machine and cleared only by a
 * person, and nothing that happens to the app in between clears it for them — with one narrow
 * exception, see [restore] and [rebootVerdict].
 *
 * ## Why it is not part of [WorkoutSession]
 *
 * That class is a clock. Its own note is that a session is "a user intent, not a machine state",
 * and it is careful to know nothing about treadmills. Whether a belt could be confirmed at rest is
 * a fact about a machine, so it lives here — and being separate is also what lets the session return
 * to IDLE while the warning stays up. A session held in [WorkoutSession.State.STOPPING] until the
 * rider acknowledged would be a state the app could wedge in; a latch beside it cannot.
 *
 * **The latch, not the session state, is what withholds Start.** Offering to start a belt Stride
 * could not confirm had stopped is the same mistake as reporting it stopped, one screen later.
 */
object StopEscalation {

    private const val TAG = "StopEscalation"

    /** Notified whenever the latch changes, on the thread that changed it. */
    fun interface Listener {
        fun onEscalationChanged(active: Boolean)
    }

    private val listeners = mutableListOf<Listener>()

    @Volatile
    private var reason: StopUnconfirmed? = null

    @Volatile
    private var restored = false

    /**
     * True while [restore] is waiting on the console's own first fresh word before deciding whether
     * a reboot-persisted warning still needs a tap.
     *
     * [active] is already true throughout — Start stays withheld the whole time, which is the safe
     * default while the question is genuinely open — but nothing is drawn on screen yet. In the
     * common case the console reconnects and confirms rest before a rider could have reached Start
     * anyway, so this resolves invisibly; only a console that comes back mid-workout, or does not
     * come back at all, ever surfaces the card.
     */
    @Volatile
    private var pendingBootVerification = false

    private var appContext: Context? = null

    private val timeoutHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Persist the latch, or fail loudly but harmlessly.
     *
     * The in-memory latch is what withholds Start and draws the card, and it must work whether or
     * not a preferences store has been attached — this is called from the confirmation thread, and
     * a warning that could not be *saved* is still a warning that has to be *shown*.
     */
    private fun persist(value: String?) {
        runCatching { StrideSettings.stopEscalation = value }
            .onFailure { Log.w(TAG, "could not persist the stop escalation; it is still raised", it) }
    }

    /** As [persist], for the boot count alongside it — must work whether or not prefs are attached. */
    private fun persistBootCount(value: Int) {
        runCatching { StrideSettings.stopEscalationBootCount = value }
            .onFailure { Log.w(TAG, "could not persist the stop escalation's boot count", it) }
    }

    /**
     * `Settings.Global.BOOT_COUNT`, or null when it could not be read.
     *
     * A stable, permission-free system counter that increments once per boot and nothing else —
     * exactly the fact [restore] needs to tell "the process was killed, the console never rebooted"
     * apart from "the console actually rebooted since this was raised".
     */
    private fun bootCount(context: Context): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

    /**
     * True while a stop is outstanding and unexplained.
     *
     * Read by every surface that would otherwise imply the belt is stopped.
     */
    val active: Boolean get() = reason != null

    /** Which failure raised it, or null when nothing has. For the copy, not for a decision. */
    val lastReason: StopUnconfirmed? get() = reason

    /**
     * Restore a latch that outlived the process.
     *
     * A low-memory kill of the overlay service must not clear a safety warning and bring the app
     * back up offering "Start workout". §3.1 says the latch requires an explicit local reset, and
     * surviving a restart is what makes that true rather than merely intended. That is unconditional
     * here: if the console has not rebooted since the warning was raised (`Settings.Global.BOOT_COUNT`
     * unchanged), nothing has happened that could be new evidence, and the warning is raised exactly
     * as it always has been.
     *
     * A console reboot is different. The motor controller [DirectMachine] talks to is a **separate
     * board on USB**, not something an Android reboot necessarily resets, so in principle the belt
     * could still be moving independently of whatever the tablet just did — but in practice a rider
     * who rebooted on purpose almost always finds a machine sitting idle, and typical iFit does not
     * make them re-confirm that by hand. [rebootVerdict] asks the console rather than assuming either
     * way: see [observeBootTelemetry].
     *
     * Called once, from the same place [StrideSettings] is attached.
     */
    fun restore(context: Context) {
        if (restored) return
        restored = true
        appContext = context.applicationContext
        val saved = runCatching { StrideSettings.stopEscalation }.getOrNull() ?: return
        val recovered = runCatching { StopUnconfirmed.valueOf(saved) }.getOrNull()
        if (recovered == null) {
            // A value this build does not recognise is still a latch that was set. Losing it
            // because an enum was renamed would silently drop a safety warning across an update.
            Log.w(TAG, "unrecognised persisted escalation '$saved'; keeping the warning up")
        }
        reason = recovered ?: StopUnconfirmed.NOT_OBSERVED

        val savedBootCount = StrideSettings.stopEscalationBootCount
        val currentBootCount = bootCount(context)
        // Missing or unreadable boot counts are treated as "a reboot may have happened" rather than
        // "it didn't" — the same direction every other unresolved case in this file already leans.
        if (currentBootCount != null && savedBootCount >= 0 && savedBootCount == currentBootCount) {
            Log.w(TAG, "restored an unacknowledged stop escalation: $reason")
            notifyListeners(true)
            return
        }

        Log.w(TAG, "stop escalation ($reason) persisted across a reboot; waiting for the console")
        pendingBootVerification = true
        // false, not rebootVerdict(null): a poll that never arrives is not evidence, but a window
        // that closed with nothing to show for it still has to end somewhere, and the safe end is
        // the one this file has always defaulted to.
        timeoutHandler.postDelayed({ resolveBootVerification(confirmedAtRest = false) }, BOOT_VERIFICATION_TIMEOUT_MS)
    }

    /**
     * Called by [MachineLink]'s poll loop with each successful read's console state, so a
     * reboot-persisted warning can resolve the moment the console has something to say — never
     * before, and never on a poll that failed to reach it at all.
     *
     * A no-op once [restore] has nothing pending, which is the overwhelming majority of polls: this
     * only ever does anything in the narrow window right after a reboot that restored a warning.
     */
    fun observeBootTelemetry(consoleState: String?) {
        if (!pendingBootVerification) return
        rebootVerdict(consoleState)?.let { resolveBootVerification(it) }
    }

    private fun resolveBootVerification(confirmedAtRest: Boolean) {
        synchronized(this) {
            if (!pendingBootVerification) return
            pendingBootVerification = false
        }
        if (confirmedAtRest) {
            Log.i(TAG, "reboot verification: console confirmed at rest; clearing without a tap")
            acknowledge()
        } else {
            Log.w(TAG, "reboot verification did not confirm rest; escalating")
            notifyListeners(true)
        }
    }

    /**
     * Raise the warning.
     *
     * Idempotent in the direction that matters: a second unconfirmed stop while one is already
     * outstanding does not clear or re-announce it, because the rider is already being told to use
     * the key and telling them again adds nothing.
     */
    fun raise(reason: StopUnconfirmed) {
        val wasActive = synchronized(this) {
            val was = this.reason != null
            this.reason = reason
            // Nothing was restored before this, and nothing should be afterwards: a latch raised
            // now must not be replaced by a stale one saved before the process restarted.
            restored = true
            pendingBootVerification = false
            was
        }
        persist(reason.name)
        // The boot this was raised on, so a future restore() can tell a mere process kill from an
        // actual reboot. Best-effort: a context that never attached just means the next restore()
        // treats this the same conservative way it treats any other unreadable boot count.
        appContext?.let { context -> bootCount(context)?.let { persistBootCount(it) } }
        Log.w(TAG, "escalating to USE THE SAFETY KEY: $reason")
        if (!wasActive) notifyListeners(true)
    }

    /**
     * The rider says they have dealt with it — or [resolveBootVerification] says the console just
     * did, which needs the exact same bookkeeping (clear, persist, notify) without pretending to be
     * a rider action in the log.
     *
     * The **only** other way out is deliberately not something the app can do for itself — not on a
     * successful later stop, not on a reconnect, not on an ordinary fresh telemetry reading. None of
     * those is evidence about the belt this warning was raised for. [rebootVerdict] is narrower and
     * different in kind: it is evidence that no workout — and therefore no commanded belt — exists
     * at all, which a live reading during an ordinary run can never say.
     */
    fun acknowledge() {
        val wasActive = synchronized(this) {
            val was = reason != null
            reason = null
            restored = true
            pendingBootVerification = false
            was
        }
        persist(null)
        persistBootCount(-1)
        if (wasActive) {
            Log.i(TAG, "stop escalation cleared")
            notifyListeners(false)
        }
    }

    fun addListener(listener: Listener) = synchronized(listeners) { listeners.add(listener); Unit }

    fun removeListener(listener: Listener) =
        synchronized(listeners) { listeners.remove(listener); Unit }

    private fun notifyListeners(active: Boolean) {
        // Copied before notifying, and guarded individually, for the reason WorkoutSession.transition
        // documents: a listener that has been torn down must never cost the others their
        // notification. Here that matters more than usual — the listeners are the surfaces that
        // tell a rider to use the safety key, and a dead overlay view taking out the notification
        // would leave the warning raised and invisible.
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach {
            try {
                it.onEscalationChanged(active)
            } catch (t: Throwable) {
                Log.w(TAG, "an escalation listener failed", t)
            }
        }
    }

    /**
     * What to tell the rider, chosen by what actually failed.
     *
     * Every branch ends in the same instruction, because there is only one thing to do about any of
     * them. What differs is the sentence before it: "the console never accepted the stop" and "the
     * console accepted it and the belt is still moving" are the same verdict and very different
     * things to be standing next to.
     */
    fun explain(reason: StopUnconfirmed?): String = when (reason) {
        StopUnconfirmed.NOT_ACKED ->
            "Stride told the treadmill to stop and the console never accepted the command."

        StopUnconfirmed.STILL_MOVING ->
            "Stride told the treadmill to stop, and the machine is still reporting that the belt " +
                "is moving."

        StopUnconfirmed.DISTANCE_ADVANCED ->
            "Stride told the treadmill to stop, and the machine went on recording distance " +
                "afterwards — the belt covered ground after it should have been still."

        StopUnconfirmed.NEVER_REPORTED_MOTION ->
            "Stride told the treadmill to stop and the console accepted it, but this machine has " +
                "never reported a belt speed or motor load above zero, so its reading cannot " +
                "confirm anything."

        // Null included deliberately rather than left to an else that reads as a default. A latch
        // restored from a build that named its reasons differently lands here, and it must say
        // something true rather than nothing.
        StopUnconfirmed.NOT_OBSERVED, StopUnconfirmed.SUPERSEDED, null ->
            "Stride told the treadmill to stop and the console accepted it, but no telemetry came " +
                "back to show the belt actually slowing."
    }

    /** The instruction, identical on every branch, kept in one place so it cannot drift. */
    const val INSTRUCTION: String =
        "Do not assume the belt has stopped. Pull the safety key — it is the only true emergency " +
            "stop — and check the belt before stepping on."

    /** How long [restore] waits for the console before giving up and raising the card anyway. */
    private const val BOOT_VERIFICATION_TIMEOUT_MS = 45_000L
}

/**
 * Whether a stop-escalation warning persisted across a reboot should clear itself, given the
 * console's own first fresh word since Stride reconnected.
 *
 * Pure, and separate from [StopEscalation] for the same reason [stopVerdict] is: this is the
 * decision the whole reboot behaviour turns on, and it has to be checkable without a treadmill.
 *
 * Null means "no fresh word yet, keep waiting" — a poll that failed to reach the console is not
 * evidence of anything and must not be read as either answer. Once a [consoleState] does arrive,
 * [NO_WORKOUT_STATES] is real, positive evidence either way: this protocol never accepts a speed
 * write outside a workout, so a console reporting no live workout cannot have a commanded belt, and
 * one reporting a live workout is exactly the case the original warning was raised to catch.
 */
internal fun rebootVerdict(consoleState: String?): Boolean? =
    if (consoleState == null) null else consoleState in NO_WORKOUT_STATES
