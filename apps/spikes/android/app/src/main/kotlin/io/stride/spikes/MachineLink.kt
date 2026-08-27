package io.stride.spikes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
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

    /**
     * The same failure, but saying which of its several causes actually happened.
     *
     * [DIRECT_NO_TRANSPORT] is true of a console with nothing plugged in, one whose device ids we do
     * not recognise, and one we simply have not been granted access to — three situations with three
     * different fixes and one sentence between them. A rider reporting "it says it found nothing"
     * told us almost nothing; this is what makes the next report conclusive.
     */
    fun directNoTransportDetail(context: Context): String =
        "No direct connection to the treadmill. " + UsbSerialTransport.describeBus(context) +
            // Both were tried, so both are reported. Naming only USB left a rider attempting the
            // Bluetooth console with "nothing is attached to the USB port", which reads as though
            // the half they were actually using had never been looked at.
            " Bluetooth was checked too, and no paired console answered."

    /** What the direct path says when a transport exists but nothing on it answered. */
    const val DIRECT_NO_ANSWER: String =
        "Stride found a connection but the treadmill didn't answer. Switch back to GlassOS to keep " +
            "using the console."

    /**
     * What the FTMS path says when no standards-compliant machine could be found.
     *
     * Names pairing specifically because that is the fix on the consoles this targets. Stride does
     * not scan below Android 12 — scanning there needs a location permission a launcher has no
     * business holding — so a machine that has never been paired is invisible however long the rider
     * waits. See [FtmsTransport].
     */
    const val FTMS_NO_MACHINE: String =
        "No Bluetooth fitness machine found. Pair the machine in Android's Bluetooth settings first, " +
            "then try again."

    /**
     * How often the poll retries discovery when nothing is attached.
     *
     * Long enough that a console with no treadmill wired to it is not enumerating USB forever, short
     * enough that powering the treadmill on, finishing a pairing, or granting the USB dialog is
     * picked up while the rider is still looking at the screen.
     */
    private const val REOPEN_INTERVAL_MS = 5_000L

    /** Where the backoff stops growing. A minute is long enough to stop mattering. */
    private const val MAX_REOPEN_FAILURES = 4

    /**
     * How long to wait before retrying a transport that would not open, given how many attempts in
     * a row have already failed.
     *
     * A flat five seconds was wrong in a way that only shows on hardware where the transport never
     * opens — which is exactly the case a rider reports. Opening runs on the poll thread and is not
     * cheap: a BLE discovery is a six-second scan plus up to ten seconds per bonded device, so the
     * attempt can outlast its own retry interval and the console spends its life in a reconnect
     * loop that also churns the Bluetooth stack. Doubling backs off to a minute, which still picks
     * up a treadmill being switched on without hammering one that is not there.
     */
    internal fun reopenBackoffMs(failures: Int): Long =
        REOPEN_INTERVAL_MS shl failures.coerceIn(0, MAX_REOPEN_FAILURES)

    /**
     * How long an axis that published no presets stands before it is asked again.
     *
     * Two extra RPCs on a ten-second cadence, against a poll that already makes eight every two
     * seconds, so the cost is noise. It is deliberately not "every poll": the answer only changes
     * when the console starts or ends a workout, and there is nothing to be gained by asking
     * between those.
     */
    private const val EMPTY_PRESET_RETRY_MS = 10_000L

    /**
     * How long a freshly opened link may report "no treadmill" before it counts as a fault.
     *
     * Measured on a Commercial 1750 from cold: `Connect` sat out the full 12-second command timeout
     * three times before the console attached, about 40 seconds after Stride started. Two minutes is
     * comfortably past that without being so long that a genuinely detached console goes unreported
     * — and the window closes early anyway, the instant the console attaches for the first time.
     */
    private const val CONSOLE_BOOT_GRACE_MS = 120_000L

    /**
     * How often the poll retries a heart rate strap that is enabled but not connected.
     *
     * Longer than [REOPEN_INTERVAL_MS] because the failure is far more likely to be permanent — most
     * riders never pair a strap at all — and because each attempt walks every bonded device and
     * opens a GATT connection to each. Retrying that every five seconds forever would keep the
     * radio busy on behalf of a device that does not exist.
     */
    private const val HEART_RATE_REOPEN_INTERVAL_MS = 30_000L

    /**
     * How often to re-ask the hardware what it is while detection is unresolved.
     *
     * Short, because the two cases it exists for — a daemon still binding at boot, a USB device not
     * yet enumerated — resolve within seconds and every one of those seconds is a rider looking at
     * "Not measured".
     */
    private const val REDETECT_INTERVAL_MS = 5_000L

    /**
     * What a control says while the console is still on its way up.
     *
     * A booting console reports [GlassOsClient.ConsoleState.DISCONNECTED] for the same reason a
     * broken one does — the head unit has not attached the lower board yet — and on this hardware
     * that lasts the best part of a minute, with each `Connect` sitting out the full 12-second
     * command timeout before the machine finally answers. Showing
     * [CONSOLE_DETACHED_NOTICE] through that window tells a rider their treadmill is broken and
     * sends them to the wall socket, every single time the console is switched on.
     *
     * Deliberately still a warning rather than a reassurance. Stride genuinely cannot read the belt
     * here, so the safety line has to keep saying so; what changes is that it names a cause that
     * resolves on its own instead of one that needs the mains pulled.
     */
    const val CONSOLE_STARTING_NOTICE: String =
        "The treadmill is still starting up. Stride can't read it yet, and the belt may be moving."

    /** The same, in the longer form the settings screen shows. */
    const val CONSOLE_STARTING_REASON: String =
        "The console is still starting up and hasn't attached its treadmill yet. This takes about a " +
            "minute from cold, and speed and incline come back on their own — there is nothing to fix."

    /**
     * What a control says when GlassOS is answering but has no machine attached to it.
     *
     * Its own state, not a guess: the console reports [GlassOsClient.ConsoleState.DISCONNECTED]
     * and every RPC that would move something blocks until it times out. Distinct from
     * [CONTROL_LOCKED_NOTICE] because the fix is different — nothing about Stride or the app will
     * recover this, only the machine coming back will.
     *
     * Only said once the console has had time to start, and only when it has never attached — see
     * [consoleStarting]. Said too early it is simply wrong, and safety copy that is wrong in the
     * ordinary case is not believed in the case that matters.
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
            consoleStarting -> CONSOLE_STARTING_NOTICE
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
     * When this link was opened, and whether the console has ever attached across it.
     *
     * The pair is what [consoleStarting] is built from: a console that has never attached and was
     * only asked a moment ago is starting up, and one that has attached even once is not. Both are
     * reset by [closeTransport], because a new transport is a new question.
     */
    @Volatile private var linkOpenedAt: Long = 0L

    @Volatile private var everAttached: Boolean = false

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

    private data class TimedSnapshot(
        val value: GlassOsClient.Snapshot,
        val at: Long,
    )

    // Keep the poll value and its time in one volatile publication; fan ordering depends on the pair.
    @Volatile private var timedSnapshot: TimedSnapshot? = null
    @Volatile private var client: GlassOsClient? = null

    /**
     * One poll's worth of the readings a stop confirmation needs, taken together.
     *
     * Read as a single object rather than through [speedMph] and [distanceMiles] on purpose. Those
     * are two separate reads of a field the poll thread rewrites, so a caller sampling them one
     * after the other can straddle a poll and pair a distance from *before* a stop with a speed
     * from after it — which is exactly the comparison a confirmation is making, so getting a
     * mismatched pair there is not a cosmetic race.
     *
     * [seq] is what ties a reading to a moment. It is a count of polls, deliberately not a
     * timestamp: [TimedSnapshot.at] is wall-clock, and an NTP correction or a timezone change mid-run
     * must never be able to make a reading taken *before* a stop look like one taken after it.
     */
    data class Observation(
        /** Which poll produced this. Strictly increasing; comparable across a stop. */
        val seq: Long,
        /** When it was taken, for the freshness test only. */
        val atMs: Long,
        val speedMph: Double?,
        val distanceMiles: Double?,
    )

    @Volatile private var latestObservation: Observation? = null

    /**
     * How many polls have succeeded on this link.
     *
     * Ungated by freshness, because its one job is to be sampled at the instant a stop goes out so
     * that later readings can be told from earlier ones. A stale link still has a definite count,
     * and "no reading was fresh when we stopped" must not collapse into "every reading is newer
     * than the stop".
     *
     * Only ever written by the poll thread, so the read-modify-write is single-writer.
     */
    @Volatile
    var readingSeq: Long = 0L
        private set

    /**
     * The freshest complete observation, or null when nothing is fresh.
     *
     * Same freshness rule as every other reading ([FRESHNESS_MS]); null means "we cannot see the
     * belt" and must never be read as "the belt is stopped".
     */
    fun observation(): Observation? =
        latestObservation?.takeIf { System.currentTimeMillis() - it.atMs <= FRESHNESS_MS }

    /**
     * Whether this machine has ever, on this link, reported a speed that means the belt is moving.
     *
     * Not a reading — a statement about whether this console's speed register can be believed when
     * it says zero.
     *
     * It exists because of issue #34: on the X22i, `ACTUAL_KPH` reads exactly `0x0000` on every
     * poll while a rider is genuinely walking at 4 mph. Not null, not absent, not a decode error —
     * a confident, well-formed, entirely plausible zero, while `CURRENT_DISTANCE` accumulates the
     * real pace beside it. "We could not ask" and "we asked, and the answer is a lie" are different
     * failures, and a null check only catches the first. There is no per-field validity marker in
     * the reply framing either, so on the wire that zero is indistinguishable from an absent value
     * by construction.
     *
     * So anything that would treat a zero speed as evidence of a stopped belt has to know whether
     * this console has ever demonstrated that it reports motion at all. On a machine whose register
     * is stuck at zero this never becomes true, and the zero is correctly worth nothing. Once a
     * console has shown real motion, its zero has earned the same credit the rest of this file
     * already extends it.
     *
     * Reset with the snapshot when a transport is torn down: a different machine has to prove
     * itself again.
     */
    @Volatile
    var everReportedMotion: Boolean = false
        private set

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
     * The FTMS path, when [StrideSettings.transport] selects it. Null on every other transport.
     *
     * Held separately for the same reason [direct] is: what the three transports can be asked
     * differs. Only GlassOS publishes quick picks outright, only the direct session has a handshake
     * whose result a rider needs to read, and only this one is a link to a machine Stride is *not*
     * running on — which is why its failure copy talks about pairing rather than about the console.
     */
    @Volatile private var ftmsTransport: FtmsTransport? = null
    @Volatile private var ftms: FtmsClient? = null

    /**
     * A heart rate strap, when the rider has one paired and enabled.
     *
     * Held outside [openTransport] on purpose: a strap is not a machine transport and is not chosen
     * by [StrideSettings.Transport]. It stays connected across a transport switch, because nothing
     * about changing how Stride reaches the treadmill should drop the rider's heart rate.
     */
    @Volatile private var heartRate: HeartRateSensor? = null

    private var heartRateThread: HandlerThread? = null
    private var heartRateHandler: Handler? = null

    /**
     * What the last handshake concluded, in a sentence fit to show a rider. Null on the GlassOS path
     * or before the attempt has finished.
     *
     * Read through from the session rather than cached here. The direct handshake runs from two
     * places — [openDirect] when the link is first opened, and [DirectMachineCommands.connect] when
     * it has dropped and come back — and a copy taken at the first would keep describing a failure
     * the second had already fixed.
     *
     * [openFailure] covers the case no session can describe: there is no session, because no
     * transport could be opened at all. That is also the whole of the FTMS story, which either finds
     * a machine or reports [FTMS_NO_MACHINE].
     */
    val machineDetail: String?
        get() = directSession?.lastConnect?.detail ?: openFailure

    @Volatile private var openFailure: String? = null
        private set

    @Volatile private var inclinePresetsCache: List<Double>? = null
    @Volatile private var speedPresetsCache: List<Double>? = null
    // Distinct from the caches being null: null there means "no presets", this means "not asked".
    @Volatile private var inclinePresetsFetched: Boolean = false
    @Volatile private var speedPresetsFetched: Boolean = false

    /**
     * When an axis that answered "I have no presets" may be asked again.
     *
     * An empty answer is real, but on GlassOS it is not *final*: `GetControls` is answered from the
     * live workout, so an idle console returns an empty `ControlList` and starts publishing its
     * real buttons only once a workout is running. Latching that first empty answer for the life of
     * the link is what left both quick-pick columns blank on a perfectly healthy console.
     *
     * So an empty answer is cached — callers still get to tell "none" from "not asked" — but it is
     * re-asked on this interval instead of being believed forever. A non-empty answer still latches:
     * presets do not change under a running link once the machine has actually published them.
     */
    @Volatile private var nextInclinePresetAskAt: Long = 0L
    @Volatile private var nextSpeedPresetAskAt: Long = 0L

    /**
     * Which [InclineSpacing] the cached incline answer was built from, or null when
     * nothing is cached.
     *
     * A tag rather than a re-read, because the rider can change the spacing while a preset ask is
     * already in flight. [refreshInclinePresets] alone cannot cover that: it clears the cache, then
     * the older request's answer lands and re-latches `inclinePresetsFetched`, and
     * [fetchPresetsOnce] returns early forever after — a stale column that survives until the
     * transport is switched. Recording what each cached answer was actually built from turns that
     * race into a mismatch the next poll pass notices and corrects on its own.
     *
     * Written *before* the fetched flag for the same reason: a pass that sees `fetched` must already
     * be able to see what it was fetched for.
     */
    @Volatile private var inclinePresetsSpacing: InclineSpacing? = null

    /**
     * Bumped whenever a preset answer lands. The overlay builds its rails once, before any machine
     * has been asked anything, so without a signal that the answers have arrived it would show the
     * fallback ladder for the life of the window.
     */
    val presetsGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var appContext: Context? = null

    private var connectThread: HandlerThread? = null
    private var connectHandler: Handler? = null

    /** Null unless we hold a snapshot that is still fresh. Every reading below goes through this. */
    private fun freshTimed(): TimedSnapshot? {
        val timed = timedSnapshot ?: return null
        if (System.currentTimeMillis() - timed.at > FRESHNESS_MS) return null
        return timed
    }

    private fun fresh(): GlassOsClient.Snapshot? = freshTimed()?.value

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
     * True when the console says it has no treadmill, but has not yet had time to find one.
     *
     * A booting console and a broken one report the same thing, and telling them apart is the whole
     * point: one resolves itself in about a minute, the other needs the mains pulled. The evidence
     * used is deliberately narrow — a link that has **never** seen the console attached, within
     * [CONSOLE_BOOT_GRACE_MS] of that link opening.
     *
     * "Never attached" is what keeps this honest. The moment the console attaches once, the grace is
     * spent for good ([everAttached] is never cleared while the link lives), so a treadmill that
     * genuinely drops out mid-session gets the real warning immediately rather than a minute of
     * reassurance. And after the window, an unattached console gets the real warning too.
     */
    val consoleStarting: Boolean
        get() {
            if (!consoleDetached || everAttached) return false
            val opened = linkOpenedAt
            return opened != 0L && SystemClock.elapsedRealtime() - opened < CONSOLE_BOOT_GRACE_MS
        }

    /**
     * Why we are in this state, in words a person on the machine can act on — not an error code.
     * This used to lead with what Stride would *not* do, which was the honest thing to say when
     * nothing here could move a belt. Stride drives the machine now, so saying otherwise would
     * send a rider to the console for something they can do under their thumb.
     */
    val reason: String
        get() = when {
            consoleStarting -> CONSOLE_STARTING_REASON
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

    /**
     * Current rider-facing speed. Null means unknown — see the class note before drawing it.
     *
     * This can be a clearly isolated display fallback on a direct FitPro belt console whose actual
     * speed register has never reported motion. Safety decisions use [observation], which is always
     * built from the raw [GlassOsClient.Snapshot.speedMph] instead.
     */
    val speedMph: Double? get() = fresh()?.displaySpeedMph

    /**
     * Current machine-observed speed, never a display fallback.
     *
     * Coordinator code uses this for adoption, ramping, and deck movement so a commanded setpoint
     * cannot become evidence about what the belt did.
     */
    internal val observedSpeedMph: Double? get() = fresh()?.speedMph

    /** Current incline percent. Null means unknown. */
    val inclinePercent: Double? get() = fresh()?.inclinePercent

    /** Distance covered this session, as measured by the machine. Null means unknown. */
    val distanceMiles: Double? get() = fresh()?.distanceMiles

    /** Instantaneous pace, derived from the same speed shown to the rider. Null means unknown. */
    val paceMinPerMile: Double? get() = fresh()?.paceMinPerMile

    /** Calories as the machine estimates them. Null means unknown. */
    val calories: Double? get() = fresh()?.calories

    /**
     * Heart rate in bpm, from the best source that has something fresh to say.
     *
     * A **strap wins over the machine** whenever it has a current reading. That is not a tie-break,
     * it is an accuracy judgement: a chest strap measures continuously, and a treadmill measures
     * through grips a running rider is not holding. Preferring the machine would replace a good
     * number with a worse one, or with nothing.
     *
     * Null when neither has anything fresh, which must be drawn as [NO_READING] and never as zero.
     */
    val heartRateBpm: Int?
        get() = strapHeartRate() ?: fresh()?.heartRateBpm

    /** Which source [heartRateBpm] came from, or null when there is no reading. */
    val heartRateSource: HeartRateSource?
        get() = when {
            strapHeartRate() != null -> HeartRateSource.STRAP
            fresh()?.heartRateBpm != null -> HeartRateSource.MACHINE
            else -> null
        }

    /** True when a strap is connected, whether or not it is currently reporting. */
    val heartRateStrapLinked: Boolean get() = heartRate?.connected == true

    /** The paired strap's name, for the settings screen. Null when none is connected. */
    val heartRateStrapName: String? get() = heartRate?.takeIf { it.connected }?.deviceName

    /** Strap battery percentage, when it publishes one. */
    val heartRateStrapBattery: Int? get() = heartRate?.takeIf { it.connected }?.batteryPercent

    /**
     * The strap's reading, if it is still fresh.
     *
     * A reading whose strap reports it has lost skin contact is discarded rather than shown. The
     * strap is telling us the number is not about the rider any more, and a stale 150 bpm on a belt
     * that has slipped is worse than an honest blank.
     */
    private fun strapHeartRate(): Int? {
        val (reading, at) = heartRate?.takeIf { it.connected }?.latest() ?: return null
        if (SystemClock.elapsedRealtime() - at > HeartRateSensor.READING_TTL_MS) return null
        if (reading.sensorContact == false) return null
        return reading.bpm
    }

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
     * Read on both paths now: the direct path from whichever fan register the machine said it
     * implements, GlassOS from `FanStateService/GetFanState`. Auto reads as null here because it is
     * not a level; [fanState] is the accessor that can name it.
     */
    val fanLevel: Int? get() = fresh()?.fanLevel

    /**
     * The fan state the *machine* reports, as a [GlassOsCommands] `FAN_*` value, or null when we do
     * not know.
     *
     * This is the only fan value in the app that is a reading. [MachineCoordinator.lastFanState] is
     * only the last state a write acknowledgement supports and can be wrong the instant the rider
     * touches the console's own fan button, which nothing in Stride ever hears about.
     */
    val fanState: Int? get() = fanTelemetry()?.state

    /**
     * One poll's fan state and timestamp, published and consumed as one immutable value.
     *
     * Keeping these together is load-bearing: pairing an old state with a new timestamp can make
     * stale telemetry incorrectly outrank a pending request, while the opposite pairing can hide a
     * current machine reading.
     */
    internal data class FanTelemetry(val state: Int?, val at: Long)

    internal fun fanTelemetry(): FanTelemetry? =
        freshTimed()?.let { FanTelemetry(it.value.fanState, it.at) }

    const val FAN_MAX: Int = 3

    /**
     * Whether this machine has a fan at all, as far as anything has been able to establish.
     *
     * A latch, not a live reading, and that is the point. [canCommandFan] goes false the moment a
     * snapshot ages past [FRESHNESS_MS], so a readout keyed straight to it would appear and vanish
     * on every missed poll. Latching also means the answer survives the gap between the overlay
     * being built and the machine getting round to describing itself.
     *
     * Only ever set from positive evidence — the machine said it would take a fan write, or it
     * actually reported a fan state. A console that has never said either keeps this false, which
     * is what keeps a fanless treadmill from growing a fan readout. Same shape as
     * [everReportedMotion], and for the same reason: a claim about a machine is only ever earned,
     * never assumed and never withdrawn by a poll that failed to reach it.
     *
     * Cleared in [closeTransport], because a different transport is a different machine.
     */
    @Volatile private var fanSeen: Boolean = false

    fun fanKnownPresent(): Boolean =
        fanSeen || canCommandFan() || MachineCoordinator.lastFanState != null

    /**
     * What the overlay should say about the fan.
     *
     * Pure, and takes its inputs rather than reading them, so every branch below is checkable
     * without a treadmill. [fanReadout] with no arguments is the live wrapper.
     *
     * The distinction this exists to keep is item 9 of the checklist on [canCommand]: the UI
     * distinguishes requested from confirmed from unknown, and never draws a request as a
     * measurement. There are three genuinely different things to say here and collapsing any two of
     * them puts a confident wrong number in front of someone on a moving belt.
     *
     * @param reported the machine's own answer, or null if it has not given one
     * @param reportedAt when the snapshot carrying [reported] was taken
     * @param requested what Stride last asked for, or null if it has not asked
     * @param requestedAt when Stride asked
     * @param knownPresent [fanKnownPresent]
     */
    fun fanReadout(
        reported: Int?,
        reportedAt: Long,
        requested: Int?,
        requestedAt: Long,
        requestPending: Boolean = true,
        knownPresent: Boolean,
    ): FanReadout {
        // A request Stride made *after* the last snapshot was taken cannot possibly be in it, so the
        // reading is known to be describing the fan as it was before the rider asked. Showing it
        // would be a confident readout that is stale by construction — the rider taps High and
        // watches the strip insist on Low for a poll. Shown as a request until a reading taken
        // afterwards either confirms it or contradicts it; no grace timer is needed, because the
        // very next poll settles it either way.
        if (
            requestPending &&
            requested != null &&
            knownPresent &&
            (reported == null || requestedAt > reportedAt)
        ) {
            return FanReadout.Requested(requested)
        }
        // Otherwise the reading wins, even against a disagreeing request. The console's fan button
        // is under the rider's hand and Stride never hears it; the reading is the only thing that
        // ever does.
        if (reported != null) return FanReadout.Measured(reported)
        // An accepted write is useful evidence when the machine cannot report a state, but unlike a
        // pending write it never suppresses telemetry: acknowledgement says the command landed, not
        // that the fan remained there after the rider used the console's own controls.
        if (requested != null && knownPresent) return FanReadout.Requested(requested)
        // `knownPresent` gates the request, not just the blank. A request can be queued before the
        // machine answers, and intent alone is not evidence that this treadmill has a fan.
        return if (knownPresent) FanReadout.Unknown else FanReadout.Absent
    }

    internal fun fanReadout(
        telemetry: FanTelemetry?,
        requested: MachineCoordinator.FanRequestSnapshot?,
        knownPresent: Boolean,
    ): FanReadout =
        fanReadout(
            reported = telemetry?.state,
            reportedAt = telemetry?.at ?: 0L,
            requested = requested?.state,
            requestedAt = requested?.at ?: 0L,
            requestPending = requested?.pending == true,
            knownPresent = knownPresent,
        )

    fun fanReadout(): FanReadout =
        fanReadout(fanTelemetry(), MachineCoordinator.fanRequestSnapshot(), fanKnownPresent())

    /**
     * Which state a fan picker should highlight, using the exact same evidence ordering as
     * [fanReadout]. A pending request may outrank an older poll; an accepted write never does.
     */
    internal fun fanSelection(readout: FanReadout): Int? = when (readout) {
        is FanReadout.Measured -> readout.state
        is FanReadout.Requested -> readout.state
        FanReadout.Absent, FanReadout.Unknown -> null
    }

    fun fanSelection(): Int? = fanSelection(fanReadout())

    /** What is known about the fan, and how well. See [fanReadout]. */
    sealed class FanReadout {
        /** The machine's own answer. May be drawn like any other measured metric. */
        data class Measured(val state: Int) : FanReadout()

        /** Stride's request, not yet confirmed by a reading. Must never be drawn as measured. */
        data class Requested(val state: Int) : FanReadout()

        /** There is a fan and nobody can say what it is doing. [NO_READING], never "Off". */
        object Unknown : FanReadout()

        /** No fan on this machine. Show nothing at all — an empty readout is not a readout. */
        object Absent : FanReadout()
    }

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
        // A third thread, purely for the strap. Sharing the connect thread meant an optional
        // accessory could sit in front of the machine handshake: a paired strap that is switched off
        // blocks its GATT connect for the full timeout, and every motor RPC queued behind it waited.
        // Nothing that can move a belt may queue behind something that cannot.
        val hrt = HandlerThread("heart-rate").also { it.start() }
        heartRateThread = hrt
        heartRateHandler = Handler(hrt.looper)
        // Opening the transport is blocking I/O — USB enumeration, a BLE connect, and a multi-frame
        // handshake — so it happens on the link thread rather than on whoever called attach.
        h.post {
            // Stamped before the open, not after: the point of reference is when Stride started
            // asking, and on a cold console the open itself is part of the wait.
            linkOpenedAt = SystemClock.elapsedRealtime()
            everAttached = false
            openTransport(app)
            armReopen()
        }
        // On its own thread: a strap that is paired but switched off blocks the GATT connect for its
        // full timeout, and spending that on the poll thread would stall every other metric for ten
        // seconds while heart rate -- the one metric nothing else depends on -- decided it was absent.
        heartRateHandler?.post { openHeartRate(app) }
        registerUsbPermissionReceiver(app)
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
        heartRateHandler?.removeCallbacksAndMessages(null)
        heartRateThread?.quitSafely()
        heartRateThread = null
        heartRateHandler = null
        closeTransport()
        // Closed here rather than in closeTransport, because a transport switch must not drop the
        // rider's heart rate: the strap has nothing to do with how Stride reaches the treadmill.
        heartRate?.let { runCatching { it.close() } }
        heartRate = null
        if (usbReceiverRegistered) {
            runCatching { appContext?.unregisterReceiver(usbPermissionReceiver) }
            usbReceiverRegistered = false
        }
        appContext = null
    }

    /**
     * Connect a heart rate strap, if the rider has enabled the feature and paired one.
     *
     * Silent about failure by design. A strap is an optional accessory and its absence is the normal
     * case; raising an error for "no strap paired" would put a warning in front of every rider who
     * has simply never owned one. The settings screen reports what was found, which is where someone
     * looking for it will look.
     */
    private fun openHeartRate(app: Context) {
        StrideSettings.attach(app)
        if (!StrideSettings.heartRateStrap) return
        if (heartRate?.connected == true) return
        if (!heartRateOpening.compareAndSet(false, true)) return
        try {
            heartRate?.let { runCatching { it.close() } }
            heartRate = try {
                HeartRateSensor.open(app)
            } catch (t: Throwable) {
                Log.w(TAG, "heart rate strap failed to open", t)
                null
            }
            heartRate?.let { Log.i(TAG, "heart rate strap connected: ${it.deviceName}") }
        } finally {
            // Timed from the end of the attempt, not the start. An attempt that took the full GATT
            // timeout has already spent the interval, and restarting immediately would busy the
            // radio on behalf of a strap that is not there.
            nextHeartRateOpenAt = SystemClock.elapsedRealtime() + HEART_RATE_REOPEN_INTERVAL_MS
            heartRateOpening.set(false)
        }
    }

    /**
     * Turn the strap on or off at the rider's request.
     *
     * Disabling closes the link immediately rather than merely ignoring it, so a rider who turns it
     * off gets their strap's battery back.
     */
    fun retargetHeartRate() {
        val app = appContext ?: return
        connectHandler?.post {
            if (StrideSettings.heartRateStrap) {
                openHeartRate(app)
            } else {
                heartRate?.let { runCatching { it.close() } }
                heartRate = null
            }
        }
    }

    /**
     * The rider's incline spacing, or the default when the settings store cannot be reached.
     *
     * Attaching here rather than assuming: this runs on the poll thread, which starts before the
     * settings screen has ever been opened, and [StrideSettings.requirePrefs] throws rather than
     * guessing. Falling back to FINE on a link with no context is the same answer a fresh install
     * gets, so a missing context can only ever produce the column that shipped — never a rearranged
     * one the rider did not ask for.
     */
    private fun inclineSpacing(): InclineSpacing {
        val app = appContext ?: return InclineSpacing.FINE
        StrideSettings.attach(app)
        return StrideSettings.inclineSpacing
    }

    /**
     * Drop the cached incline ladder because the rider re-spaced it.
     *
     * Only the incline axis, and no transport churn: the machine's range has not changed, so closing
     * and re-opening the link to re-derive one column would throw away every reading and every
     * handshake for a display choice.
     *
     * The generation is bumped unconditionally, unlike the guarded bump in [closeTransport]. That
     * guard exists to stop a *timer* from rebuilding the overlay when nothing changed; this is a
     * rider's own tap, and the fallback ladder they see until the re-ask lands is itself spaced by
     * the new choice — so there is always something to rebuild for.
     *
     * [fetchPresetsOnce] would notice the change on its own within a poll or two. This is what makes
     * it happen while the rider is still looking at the screen.
     */
    fun refreshInclinePresets() {
        inclinePresetsCache = null
        inclinePresetsSpacing = null
        inclinePresetsFetched = false
        nextInclinePresetAskAt = 0L
        presetsGeneration.incrementAndGet()
    }

    /**
     * Counts completed [retarget]s, so a caller can tell when a switch has actually landed.
     *
     * The switch is asynchronous — it closes a link, opens another, and runs a handshake that can
     * take seconds on BLE — but the settings screen wants to show what the new transport found. It
     * cannot simply re-read after the call returns, because at that moment nothing has happened yet.
     * Bumped at the *end* of the work, so observing a change means the new link is fully open and
     * every value derived from it is the new one.
     */
    private val retargetSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** @see retargetSeq */
    val retargetCount: Int get() = retargetSeq.get()

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
            // The rider just asked for this transport, so a permission dialog is expected rather
            // than a surprise, and a previous denial should not silence it forever.
            usbPermissionAsked = false
            nextReopenAt = 0L
            // A deliberate switch starts the retry budget over: whatever made the previous transport
            // fail says nothing about this one, and a rider should never be made to wait out a
            // backoff earned by a link they just abandoned.
            reopenFailures = 0
            openTransport(app)
            armReopen()
            // Connect immediately rather than waiting for a poll to notice. A rider who flips the
            // switch is watching the screen right then, and the difference between "controls live
            // now" and "controls live in up to two seconds" is the difference between the setting
            // looking like it worked and looking broken. closeTransport() has already cleared the
            // backoff and the success TTL, so this attempt is never skipped as too-soon or
            // short-circuited by the previous transport's handshake.
            reconnect()
            retargetSeq.incrementAndGet()
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
        // Before reading the setting, because on a console the rider has never configured the
        // setting *is* the detection. Runs once per process and is a no-op afterwards.
        StrideSettings.detectTransport(app)
        when (StrideSettings.transport) {
            StrideSettings.Transport.GLASSOS -> {
                val c = GlassOsClient(app)
                client = c
                // GlassOS cannot be asked for the machine's limits, so the fixed ceiling stands.
                MachineCoordinator.applyMachineLimits(null)
                MachineCoordinator.rebind(GlassOsCommands(c))
            }
            StrideSettings.Transport.DIRECT -> openDirect(app)
            StrideSettings.Transport.FTMS -> openFtms(app)
        }
    }

    /**
     * Bring up the FTMS path: find a machine, and only then hand the coordinator something that can
     * move a belt.
     *
     * Shorter than [openDirect] because FTMS needs no framing probe. The register path has to
     * confirm it understands the wire before a write means anything; FTMS is a published profile
     * whose `RequestControl` is itself the handshake, and the machine either grants control or does
     * not. Failure leaves the coordinator unbound rather than bound to a half-open session, because
     * an unbound coordinator refuses commands — the correct answer when we could not establish that
     * the machine is listening.
     */
    private fun openFtms(app: Context) {
        val transport = try {
            FtmsTransport.open(app)
        } catch (t: Throwable) {
            Log.w(TAG, "ftms transport failed to open", t)
            null
        }
        if (transport == null) {
            openFailure = FTMS_NO_MACHINE
            MachineCoordinator.rebind(null)
            return
        }

        openFailure = null
        ftmsTransport = transport
        ftms = FtmsClient(transport)
        val commands = FtmsMachineCommands(transport)
        // The machine's own ceiling, so the clamp becomes the intersection of ours and theirs.
        // Null when it did not publish both ranges, which leaves Stride's fixed ceiling standing
        // alone rather than inventing a limit the machine never agreed to.
        MachineCoordinator.applyMachineLimits(commands.limits())
        MachineCoordinator.rebind(commands)
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
            openFailure = directNoTransportDetail(app)
            // A console whose USB device is present but ungranted looks exactly like one that is
            // absent, and the grant can only come from a dialog somebody has to raise. Nothing
            // raised it, so direct-over-USB could never open on a fresh install however many times
            // the rider tried. Asked once per rider action rather than once per retry: this also
            // runs from the poll's discovery retry, and a permission dialog every two seconds is
            // its own kind of broken.
            if (!usbPermissionAsked) {
                usbPermissionAsked = true
                runCatching { UsbSerialTransport.requestPermission(app) }
                    .onFailure { Log.w(TAG, "requesting USB permission failed", it) }
            }
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
            openFailure = DIRECT_NO_ANSWER
            session.close()
            directSession = null
            MachineCoordinator.rebind(null)
            return
        }

        openFailure = null
        direct = DirectMachineClient(session)
        // The machine's own ceiling, so the clamp becomes the intersection of ours and theirs.
        MachineCoordinator.applyMachineLimits(session.probe.limits)
        MachineCoordinator.rebind(DirectMachineCommands(session))
    }

    /**
     * Throttles [reopenIfDropped]'s discovery retry. Cleared by [closeTransport] and [retarget] so a
     * deliberate switch is never made to wait out the previous one's backoff.
     */
    private var nextReopenAt = 0L

    /** Consecutive failed reopen attempts, which is what [reopenBackoffMs] grows against. */
    private var reopenFailures = 0

    /** Throttles [reopenHeartRateIfDropped]. Not cleared by a transport switch; see that method. */
    @Volatile private var nextHeartRateOpenAt = 0L

    /**
     * True while a strap connect is in flight.
     *
     * Without it, a connect that outlives its own retry interval lets the next poll queue a second
     * one behind it, and a strap that is paired but switched off -- ten seconds per attempt --
     * accumulates them faster than they drain.
     */
    private val heartRateOpening = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether this rider action has already raised the USB permission dialog. */
    private var usbPermissionAsked = false

    /**
     * Wakes the link the moment Android reports the USB grant.
     *
     * Without this the grant landed silently and only took effect on whatever retry came next,
     * which is a five-second wait staring at a screen that says nothing was found. Acting on it
     * immediately is the difference between "I allowed it and it connected" and "I allowed it and
     * nothing happened", and only one of those gets reported as working.
     */
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbSerialTransport.ACTION_PERMISSION) return
            val claimed = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val app = appContext ?: return
            // Trust the USB service, not the broadcast.
            //
            // Below API 33 a receiver cannot be registered as not-exported, so this action is
            // something any installed app can send, with any extras it likes. Acting on the extra
            // alone would let anything on the console force Stride to tear down and rebuild its
            // machine link at will — and closing a transport clears the coordinator's queue, which
            // could include a stop the rider had just asked for. Asking `hasPermission` costs one
            // Binder call and is a fact rather than a claim.
            UsbSerialTransport.permissionSettled()
            val granted = claimed && UsbSerialTransport.hasConsolePermission(app)
            Log.i(TAG, "usb permission broadcast: claimed=$claimed, actually granted=$granted")
            if (!granted) return
            // Only meaningful while the direct path is the one selected. A grant arriving for a
            // console being driven over GlassOS is not a reason to disturb a working link.
            if (StrideSettings.transport != StrideSettings.Transport.DIRECT) return
            // On the machine thread, which is the single owner of every transport field. The
            // receiver runs on a binder thread, and posting this anywhere else would let a close
            // and an open race whatever the poll was already doing — orphaning a GATT handle, or
            // closing a link another thread had just installed.
            handler?.post {
                // The grant is what the previous attempt was missing, so let the next one happen
                // now rather than waiting out a backoff earned by a different problem.
                usbPermissionAsked = false
                nextReopenAt = 0L
                reopenFailures = 0
                closeTransport()
                openTransport(app)
                armReopen()
            }
        }
    }

    @Volatile private var usbReceiverRegistered = false

    private fun registerUsbPermissionReceiver(app: Context) {
        if (usbReceiverRegistered) return
        val filter = IntentFilter(UsbSerialTransport.ACTION_PERMISSION)
        try {
            // NOT_EXPORTED from API 33: this listens for a broadcast Stride itself asked Android to
            // send back, so nothing outside the app has any business delivering it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(usbPermissionReceiver, filter)
            }
            usbReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "could not register the USB permission receiver", t)
        }
    }

    /**
     * Whether clearing the preset caches is a change the overlay needs to hear about.
     *
     * Pulled out and made internal so the rule can be tested: the bug it fixes is invisible in a
     * unit test of anything larger, because what went wrong was a *rate* — the whole overlay being
     * torn down and rebuilt every five seconds on a console where no transport could be opened.
     * There was nothing to clear on any of those passes.
     */
    internal fun presetsWorthAnnouncing(
        incline: List<Double>?,
        speed: List<Double>?,
        inclineFetched: Boolean,
        speedFetched: Boolean,
    ): Boolean = incline != null || speed != null || inclineFetched || speedFetched

    private fun closeTransport() {
        MachineCoordinator.rebind(null)
        // A reading taken through the old transport says nothing about the new one, and the belt
        // edge is built from exactly two readings. Carried across a switch, GlassOS reporting
        // WORKOUT and a freshly opened direct link reporting anything else is a manufactured
        // "the rider pressed Stop" — from two different wires, seconds apart.
        WorkoutMachineCoupling.forgetConsole()
        direct = null
        directSession?.let { runCatching { it.close() } }
        directSession = null
        ftms = null
        // Closed rather than merely dropped, for the same reason the GlassOS client is: clearing the
        // field stops future work from finding it, but only the close stops a GATT link from staying
        // subscribed and delivering notifications into a transport nobody reads.
        ftmsTransport?.let { runCatching { it.close() } }
        ftmsTransport = null
        openFailure = null
        // Closed, not merely dropped. Clearing the field stops *future* work from finding it; the
        // close is what stops work that is already in flight on another thread from finishing a
        // call it started before the rider switched away. See GlassOsClient.close.
        client?.let { runCatching { it.close() } }
        client = null
        timedSnapshot = null
        // Cleared with the snapshot, for the same reason: a reading belongs to the machine that
        // produced it. [readingSeq] is deliberately *not* reset — it is a monotonic ordering used
        // to tell a reading taken before a stop from one taken after it, and restarting the count
        // would make a fresh reading on the new transport compare as older than a stop issued on
        // the previous one.
        latestObservation = null
        // A different machine has to earn the benefit of the doubt again. Carrying this across a
        // transport swap would let an honest console vouch for the register of the one that
        // replaced it.
        everReportedMotion = false
        // A different transport is a different machine, so what the old one said about having a fan
        // is not evidence about the new one. Deliberately not tied to the presets generation below:
        // clearing this changes a cell's visibility inside an existing window, not the set of
        // windows, so it must not drag the whole chrome through a rebuild.
        fanSeen = false
        // Only announce a preset change when there was something to lose.
        //
        // The overlay rebuilds its entire chrome whenever this generation moves, which is correct
        // when real quick-picks arrive and replace the fallback ladder. But this method is also what
        // the poll calls every few seconds while a transport cannot be opened at all — a rider on a
        // console where the direct path finds nothing was getting the whole overlay torn down and
        // rebuilt on a five-second cycle, which reads exactly as it sounds: flashing. Nothing had
        // changed on any of those passes, because there were never any presets in the first place.
        val hadPresets = presetsWorthAnnouncing(
            incline = inclinePresetsCache,
            speed = speedPresetsCache,
            inclineFetched = inclinePresetsFetched,
            speedFetched = speedPresetsFetched,
        )
        inclinePresetsCache = null
        speedPresetsCache = null
        inclinePresetsFetched = false
        speedPresetsFetched = false
        // Cleared with the answer it described. Leaving it set would let a new transport's first
        // pass believe a cache that no longer exists was built for the current spacing.
        inclinePresetsSpacing = null
        nextInclinePresetAskAt = 0L
        nextSpeedPresetAskAt = 0L
        if (hadPresets) presetsGeneration.incrementAndGet()
        nextReopenAt = 0L
        // Deliberately NOT clearing reopenFailures here. This method is called *inside* every retry,
        // immediately before the attempt whose result decides the next backoff, so resetting it here
        // would pin the counter at one and the interval at a constant — which is the same trap
        // nextReopenAt fell into. A rider deliberately switching transports clears it in retarget().
        synchronized(connectLock) {
            connectFailures = 0
            nextConnectAt = 0L
            lastAttachedAt = 0L
            // A new transport is a new question: it has not attached yet, and its grace starts now.
            everAttached = false
            linkOpenedAt = SystemClock.elapsedRealtime()
            // Any handshake still in flight belongs to a link that no longer exists. Clearing
            // the flag here rather than waiting for it to finish means a re-attach is never
            // blocked by the tail of the previous one.
            connectInFlight = false
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            val read = try {
                client?.read() ?: direct?.read() ?: ftms?.read()
            } catch (t: Throwable) {
                // A failed read means "we do not know". Never a crash, and never a stale number.
                null
            }
            if (read != null) {
                val at = System.currentTimeMillis()
                timedSnapshot = TimedSnapshot(read, at)
                // Published as one object, after the snapshot, so a stop confirmation gets a speed
                // and a distance that came off the same poll. See Observation.
                readingSeq += 1
                latestObservation = Observation(readingSeq, at, read.speedMph, read.distanceMiles)
                // Latched, never cleared by a later zero. The question this answers is not "is the
                // belt moving now" — that is what the snapshot is for — but "does this console's
                // speed register ever say anything but zero". See everReportedMotion and issue #34.
                if ((read.speedMph ?: 0.0) > BELT_MOVING_MPH) everReportedMotion = true
                // Positive evidence only, and latched: see [fanSeen]. Either answer proves a fan
                // exists, and neither can be un-proved by a poll that failed to reach the console.
                if (read.fanWritable == true || read.fanState != null) fanSeen = true
                // A real reading over GlassOS settles the question the probe could not. Without
                // this, a console whose daemon started *after* an inconclusive probe kept the
                // unresolved state forever - harmless for the transport, which is already right,
                // but the settings screen went on claiming there was no GlassOS service while
                // GlassOS was plainly working.
                StrideSettings.resolveTransportFromReading()
                // The console is not only a thing Stride commands; it has its own Stop button under
                // the rider's hand. This is where pressing it reaches the overlay.
                WorkoutMachineCoupling.observeConsole(read.consoleState)
                // Presets are static for the machine, so fetch them once on the same background
                // thread as the poll rather than inventing a second worker. A read having just
                // succeeded means the link is up, so this is the cheapest moment to try.
                fetchPresetsOnce()
            } else {
                // Nothing came back, so nothing is known about the belt. Dropping the remembered
                // reading stops a link that recovers minutes later from delivering a stale edge
                // built out of two readings with a gap between them.
                WorkoutMachineCoupling.forgetConsole()
                reopenIfDropped()
            }
            // Outside the read check on purpose: a strap is not part of the machine link, so its
            // reconnect must not be gated on the machine having failed to answer. A rider whose
            // treadmill is reporting perfectly and whose strap slipped off still wants it back.
            reopenHeartRateIfDropped()
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
            //
            // Also while a stop is waiting to be confirmed, which is the one moment a slow poll
            // would be actively harmful: a console walking to WORKOUT_RESULTS reports a belt that
            // "may not be moving" and would drop this to two seconds, so the two agreeing readings
            // a confirmation needs would take four — long enough to time out and escalate a stop
            // that worked perfectly. See MachineCoordinator.stopConfirmationPending.
            val moving = read?.let { GlassOsClient.ConsoleState.beltMayBeMoving(it.consoleState) }
            val fast = moving == true || MachineCoordinator.stopConfirmationPending
            handler?.postDelayed(this, if (fast) 500L else 2_000L)
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
            // Latched for the life of the link. Once a console has attached even once, a later
            // DISCONNECTED is a real fault rather than a slow start, and must be reported as one
            // immediately instead of being excused for another minute.
            everAttached = true
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
    /**
     * Reopen the direct link when it has dropped — **or when there was never one to drop.**
     *
     * The second half is the important one. `openDirect` leaves no session at all if no device
     * answered, so guarding on an existing session meant that selecting DIRECT before the console
     * was reachable produced a dead setting that could only be revived by toggling it again. Every
     * ordinary way that happens — the treadmill powered on afterwards, a BLE console still pairing,
     * a USB cable seated late, the permission grant arriving from the dialog we raise below — is a
     * few seconds of waiting, which is exactly the case that used to require the rider to know the
     * cure was to flip the switch twice.
     *
     * Rate-limited because, unlike the dropped-session case, this runs when nothing is attached at
     * all, and USB enumeration plus a handshake attempt on every poll is real work to do forever on
     * a console that simply has no treadmill wired to it.
     */
    private fun reopenIfDropped() {
        val app = appContext ?: return
        // Which transports even have a link that can drop. GlassOS talks to a local socket and opens
        // one per call, so there is nothing to re-establish; the other two hold a physical link.
        val dropped = when (StrideSettings.transport) {
            // Nothing to reopen, but this is the state a console sits in when detection could not
            // decide -- GlassOS is where an inconclusive answer falls back to. Ask again from here,
            // because "the daemon had not bound yet" and "the USB device had not enumerated yet"
            // both look exactly like this and both fix themselves within seconds.
            StrideSettings.Transport.GLASSOS -> {
                redetectIfUnresolved(app)
                return
            }
            StrideSettings.Transport.DIRECT -> directSession?.connected != true
            // Added with FTMS. Without this branch an FTMS machine that went out of range, was
            // switched off, or dropped its BLE link stayed dead until the rider restarted Stride or
            // toggled the transport, because the retry was written for the direct path alone.
            StrideSettings.Transport.FTMS -> ftmsTransport?.connected != true
        }
        if (!dropped) return

        if (SystemClock.elapsedRealtime() < nextReopenAt) return

        Log.i(
            TAG,
            "${StrideSettings.transport} transport dropped or absent; reopening " +
                "(attempt ${reopenFailures + 1})",
        )
        closeTransport()
        openTransport(app)
        armReopen()
        // Timed from the *end* of the attempt, and set after closeTransport rather than before it.
        // closeTransport deliberately clears this so a rider switching transports never waits out
        // the previous one's backoff — which also meant a throttle set before it was wiped on the
        // way past, and the "every few seconds" retry ran on every poll instead.

    }

    /**
     * Ask the hardware again when nothing has been established yet.
     *
     * Only runs while the transport is **automatic** and the link is not producing readings, so a
     * rider who chose GlassOS deliberately is never second-guessed, and a console that is working is
     * never re-probed. Detection caches itself as soon as it finds something, so this stops on its
     * own the moment it succeeds.
     *
     * Posted to the connect thread: the probe opens a socket, and the poll thread is what every
     * metric on screen depends on.
     */
    private fun redetectIfUnresolved(app: Context) {
        if (!StrideSettings.transportIsAutomatic) return
        if (StrideSettings.transportResolved) return
        val now = SystemClock.elapsedRealtime()
        if (now < nextDetectAt) return
        nextDetectAt = now + REDETECT_INTERVAL_MS
        connectHandler?.post {
            val before = StrideSettings.transport
            StrideSettings.detectTransport(app)
            val after = StrideSettings.transport
            if (after != before) {
                Log.i(TAG, "detection resolved to $after; retargeting")
                retarget()
            }
        }
    }

    /**
     * Record how an open attempt went and decide when the next one may happen.
     *
     * Called after **every** open, not only the poll's retry. `attach` and `retarget` used to open a
     * transport and leave the deadline at zero, so the very next poll immediately ran a second full
     * close-and-open — two consecutive BLE passes, each of which can take ten seconds per bonded
     * device, before anything backed off at all. The settings screen waits nine seconds for a switch
     * to land, so a rider could be told the change had not worked while the first attempt was still
     * running.
     *
     * The backoff is computed from the failure count *before* it grows, so the first retry after a
     * failure is prompt and only repeated failures stretch out.
     */
    private fun armReopen() {
        val opened = directSession != null || ftmsTransport != null || client != null
        val waitFor = if (opened) 0 else reopenFailures
        reopenFailures = if (opened) 0 else (reopenFailures + 1).coerceAtMost(MAX_REOPEN_FAILURES)
        nextReopenAt = SystemClock.elapsedRealtime() + reopenBackoffMs(waitFor)
    }

    /** Throttles [redetectIfUnresolved]. */
    @Volatile private var nextDetectAt = 0L

    /**
     * Reconnect a heart rate strap that has dropped.
     *
     * Separate from [reopenIfDropped] because a strap is not a machine transport: it must be retried
     * whichever transport is selected, and must not be torn down when one is switched.
     *
     * The work is posted to the connect thread rather than run here. A strap that is paired but
     * switched off blocks its GATT connect for the full timeout, and spending that on the poll
     * thread would stall speed, incline and distance for ten seconds while heart rate — the one
     * metric nothing else depends on — decided it was absent.
     */
    private fun reopenHeartRateIfDropped() {
        if (heartRate?.connected == true) return
        if (heartRateOpening.get()) return
        if (SystemClock.elapsedRealtime() < nextHeartRateOpenAt) return
        val app = appContext ?: return
        // The enabled check lives in openHeartRate, which attaches settings first. Reading the flag
        // here would touch SharedPreferences from the poll thread before anything guarantees
        // StrideSettings has been attached, and that throws rather than returning a default.
        heartRateHandler?.post { openHeartRate(app) }
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
     * A null answer means the question could not be asked, and leaves that axis unfetched so a later
     * poll retries it. The two axes are fetched **independently**: a machine that answers about speed
     * but not incline — a flat treadmill, or a console whose incline control list is missing — must
     * still get its speed rail, and coupling them meant one null answer suppressed both.
     *
     * An empty list is a real answer and is cached as one, so callers can still tell "this machine
     * publishes none" from "we have not asked". It is **not** treated as the machine's last word,
     * which is the fix for a rider looking at two empty quick-pick columns: GlassOS answers
     * `GetControls` out of the live workout, so an idle console returns an empty `ControlList` and
     * only starts publishing its real buttons once something is running. Latching the first empty
     * answer meant the rails could never fill in, for the whole life of the link.
     *
     * [presetsGeneration] is bumped only when the answer actually differs from the one already held.
     * A re-ask that returns the same empty list changes nothing on screen, and bumping the
     * generation for it would rebuild the entire overlay chrome on a timer — the flashing that
     * [closeTransport] had to be taught not to cause.
     *
     * A console that has lost its own treadmill is asked once and then left alone. It has nothing to
     * publish and every RPC to it can block for the full read timeout; re-asking on a timer would
     * spend the poll thread — the thread every metric on screen depends on — waiting for two answers
     * that cannot exist yet. The first ask still happens, because "never asked" has to become
     * "asked" for the rails to know which they are looking at.
     *
     * The spacing check comes **before** the both-fetched early return, and has to. Placing it after
     * would make the one case it exists for unreachable: a spacing change that raced an in-flight
     * ask leaves both axes fetched, which is exactly when the early return fires.
     */
    private fun fetchPresetsOnce() {
        // Read once per pass, and pass the same value down. Reading it again when the answer lands
        // would tag the result with whatever the setting says *then*, which is the stale-forever bug
        // this tag exists to prevent rather than a fix for it.
        val spacing = inclineSpacing()
        if (spacing != inclinePresetsSpacing) {
            inclinePresetsFetched = false
            // The empty-answer backoff belongs to the old spacing's answer. Leaving it armed would
            // hold a rider's freshly chosen column back by an interval they did nothing to earn.
            nextInclinePresetAskAt = 0L
        }
        if (inclinePresetsFetched && speedPresetsFetched) return
        val now = SystemClock.elapsedRealtime()
        val detached = consoleDetached
        if (!inclinePresetsFetched && mayAskPresets(inclinePresetsCache, nextInclinePresetAskAt, now, detached)) {
            MachineCoordinator.ask { it.inclinePresets(spacing) }?.let { answer ->
                val changed = answer != inclinePresetsCache || spacing != inclinePresetsSpacing
                inclinePresetsCache = answer
                // Before the fetched flag: a pass that sees the latch must be able to see what was
                // latched, or it cannot tell a current answer from one built for the old spacing.
                inclinePresetsSpacing = spacing
                if (answer.isEmpty()) {
                    nextInclinePresetAskAt = now + EMPTY_PRESET_RETRY_MS
                } else {
                    inclinePresetsFetched = true
                }
                if (changed) presetsGeneration.incrementAndGet()
            }
        }
        if (!speedPresetsFetched && mayAskPresets(speedPresetsCache, nextSpeedPresetAskAt, now, detached)) {
            MachineCoordinator.ask { it.speedPresetsMph() }?.let { answer ->
                val changed = answer != speedPresetsCache
                speedPresetsCache = answer
                if (answer.isEmpty()) {
                    nextSpeedPresetAskAt = now + EMPTY_PRESET_RETRY_MS
                } else {
                    speedPresetsFetched = true
                }
                if (changed) presetsGeneration.incrementAndGet()
            }
        }
    }

    /**
     * Whether one preset axis may be asked on this pass.
     *
     * Pulled out as a pure function because it is the guard between "the rails fill in as soon as
     * the machine has something to publish" and "the poll thread spends its life waiting on a
     * console that has nothing to say", and those pull in opposite directions.
     *
     * A [cache] of null has never been answered, so it is always asked: the rails need "asked, and
     * the answer was none" to be distinguishable from "not asked yet", and only an ask produces
     * that. Past the first answer the clock applies — and on a [detached] console it never comes
     * round again, because a head unit that has lost its treadmill cannot start publishing quick
     * picks for it, and every RPC to it can block for the full read timeout.
     */
    internal fun mayAskPresets(
        cache: List<Double>?,
        nextAskAt: Long,
        nowMs: Long,
        detached: Boolean,
    ): Boolean = when {
        cache == null -> true
        detached -> false
        else -> nowMs >= nextAskAt
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
     * than a generic "can't reach the console". [machineDetail] is set by the handshake and is
     * already written for a rider to read.
     */
    fun unavailableReason(): String {
        // The direct path's own conclusion outranks the generic sentences, but only on the direct
        // path: CONSOLE_DETACHED_NOTICE talks about GlassOS having no treadmill attached, which is
        // not a thing that can be true when we are not talking to GlassOS at all.
        val detail = machineDetail
        if (!canCommand() && detail != null) return detail
        return when {
            consoleStarting -> CONSOLE_STARTING_NOTICE
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

    /**
     * Whether a machine actually answered on whichever non-GlassOS transport is selected.
     *
     * Not "is a session bound". A direct session is deliberately bound even when nothing answered,
     * because that is what gives [DirectMachineCommands.connect] somewhere to retry from when the
     * treadmill is powered on a minute later. Reporting that as linked would put "the treadmill
     * answered" on the settings screen next to a treadmill that never did.
     *
     * FTMS has no equivalent half-open state: [openFtms] hands back a transport only once the GATT
     * link is up and its characteristics are subscribed, so a live link is the whole answer there.
     */
    val machineLinked: Boolean
        get() = directSession?.lastConnect?.connected == true ||
            ftmsTransport?.connected == true

    /**
     * What the machine itself said it supports, for the settings screen to display.
     *
     * Null when no non-GlassOS transport has been opened — which the screen must show as "not tried"
     * rather than "unsupported". The values are the machine's own answer, so this is reporting rather
     * than predicting. That distinction is the whole point: the screen used to state flatly that
     * incline and fan would not work, which was a guess, and on any machine that implements them it
     * was simply false.
     *
     * Both transports can answer it, from different evidence: the direct path decodes a
     * supported-register bitmask out of `DEVICE_INFO`, and FTMS reads the `Fitness Machine Feature`
     * bits and its two supported-range characteristics. Same question, same shape of answer.
     */
    fun machineCapabilities(): Map<String, Any?>? {
        directSession?.let { session ->
            val limits = session.probe.limits
            return mapOf(
                "speed" to session.supports(Control.SPEED),
                "incline" to session.supports(Control.INCLINE),
                "fan" to session.supports(Control.FAN),
                "transport" to session.transportName,
                // The machine's own range, read from MIN_KPH/MAX_KPH and MIN_GRADE/MAX_GRADE. This
                // is the rider's answer to "what can this thing actually do", and it is worth
                // showing because it is the strongest evidence the link is real: a plausible range
                // means the frames are being decoded correctly, and a nonsense one means they are
                // not.
                "minSpeedMph" to limits?.minSpeedMph,
                "maxSpeedMph" to limits?.maxSpeedMph,
                "minIncline" to limits?.minInclinePercent,
                "maxIncline" to limits?.maxInclinePercent,
            )
        }
        val transport = ftmsTransport ?: return null
        val features = transport.features
        val speed = transport.speedRange
        val incline = transport.inclinationRange
        return mapOf(
            // Whether a *target* is accepted, not whether the value is reported. A machine can
            // stream its speed while refusing to be told one, and showing the reporting bit here
            // would promise a control that always refuses.
            "speed" to features?.supportsSpeedTarget,
            "incline" to features?.supportsInclineTarget,
            // Not unknown: the fitness machine profile has no fan concept at all.
            "fan" to false,
            "transport" to transport.name,
            // What the machine said it is, from the data characteristic it exposed. Worth showing:
            // a rider who selected FTMS and sees "rower" has learned that Stride bound to the wrong
            // peripheral far faster than they would from a blank speed readout.
            "machineType" to transport.machineType.label,
            "minSpeedMph" to speed?.let { FtmsValues.kphToMph(it.minKph) },
            "maxSpeedMph" to speed?.let { FtmsValues.kphToMph(it.maxKph) },
            "minIncline" to incline?.minPercent,
            "maxIncline" to incline?.maxPercent,
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
