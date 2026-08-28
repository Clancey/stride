package io.stride.spikes

import android.content.Context
import android.content.SharedPreferences

/**
 * Every setting that must outlive the process.
 *
 * Until now Stride kept its preferences in companion-object fields, which meant a crash, a
 * low-memory kill, or an `adb install -r` silently reset the rider's choices. That is tolerable for
 * a spike and not tolerable for the machine's only launcher: the console reboots, and whatever the
 * rider chose has to still be true afterwards.
 *
 * Deliberately not a general key/value bag exposed to Dart. Each setting is named here, typed here,
 * and carries its own default, so there is exactly one place to look for what Stride remembers and
 * what it does when it has never been told.
 */
object StrideSettings {

    private const val FILE = "stride.settings"

    private const val KEY_TRACK_FLOOR = "overlay.trackFloor"
    private const val KEY_TRACK_BACKDROP = "overlay.trackBackdrop"
    private const val KEY_TRANSPORT = "machine.transport"
    private const val KEY_FAN = "machine.fanState"
    private const val KEY_HR_STRAP = "sensor.heartRateStrap"
    private const val KEY_INCLINE_SPACING = "presets.inclineSpacing"

    /**
     * Persisted because a safety warning that a low-memory kill can clear is not a latch.
     *
     * `docs/PLAN.md` §3.1 says a latched safety state "requires an explicit local reset". If the
     * overlay service is killed while an unconfirmed stop is outstanding, the app must come back up
     * still saying "USE THE SAFETY KEY" rather than offering to start a belt it never confirmed had
     * stopped. See [StopEscalation].
     */
    private const val KEY_STOP_ESCALATION = "safety.stopEscalation"

    /**
     * `Settings.Global.BOOT_COUNT` at the moment [KEY_STOP_ESCALATION] was raised.
     *
     * What lets [StopEscalation.restore] tell a mere process kill (the case the persistence above
     * exists for -- the console never rebooted, so nothing new can be learned by waiting) apart from
     * an actual reboot (where the motor controller is a separate board on USB and may or may not
     * have reset with it, so it is worth actually asking the console rather than either assuming).
     */
    private const val KEY_STOP_ESCALATION_BOOT_COUNT = "safety.stopEscalationBootCount"

    /** How Stride is permitted to reach the machine. */
    enum class Transport {
        /** Through iFit's GlassOS gRPC server. The default, and the only one that is implemented. */
        GLASSOS,

        /**
         * Straight at the register protocol underneath GlassOS.
         *
         * Selecting this does **not** make it work: [FitProCodec] can serialize registers and
         * nothing more — it opens no serial port, no BLE connection, no socket. This exists so the
         * gate is built, persisted and honest *before* a transport is written behind it, rather
         * than being retrofitted onto working code later.
         */
        DIRECT,

        /**
         * The Bluetooth SIG **Fitness Machine Service**, to equipment that is not iFit's.
         *
         * The only transport here that reaches a machine Stride is *not running on*. Selecting it
         * does not make it work: the machine must be paired in Android's Bluetooth settings first,
         * because Stride deliberately does not hold the location permission that BLE scanning
         * requires below Android 12. See `FtmsTransport`.
         */
        FTMS,
        ;

        companion object {
            fun parse(raw: String?): Transport =
                entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: GLASSOS
        }
    }

    private lateinit var prefs: SharedPreferences

    /**
     * Bind the store to a context. Safe to call repeatedly; the first call wins.
     *
     * Uses the application context so a settings screen that is later destroyed cannot take the
     * store down with it.
     */
    @Synchronized
    fun attach(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(): SharedPreferences {
        check(::prefs.isInitialized) { "StrideSettings.attach() must run before any setting is read" }
        return prefs
    }

    /**
     * The rider's explicit track-floor choice, or null for "decide automatically".
     *
     * Three-state on purpose. A plain boolean cannot tell "the rider wants the floor off" apart
     * from "the rider has never said", and those two must behave differently: only the first should
     * survive a video starting and stopping.
     */
    var trackFloor: Boolean?
        get() = requirePrefs().let { p ->
            if (!p.contains(KEY_TRACK_FLOOR)) null else p.getBoolean(KEY_TRACK_FLOOR, false)
        }
        set(value) {
            requirePrefs().edit().apply {
                if (value == null) remove(KEY_TRACK_FLOOR) else putBoolean(KEY_TRACK_FLOOR, value)
            }.apply()
        }

    /**
     * The unacknowledged stop escalation, as a [StopUnconfirmed] name, or null when there is none.
     *
     * Stored as the enum's *name* rather than its ordinal so that reordering the reasons cannot
     * silently turn one warning into another across an update; [StopEscalation.restore] keeps the
     * latch raised for a name it does not recognise rather than dropping it.
     */
    var stopEscalation: String?
        get() = requirePrefs().getString(KEY_STOP_ESCALATION, null)
        set(value) {
            requirePrefs().edit().apply {
                if (value == null) remove(KEY_STOP_ESCALATION) else putString(KEY_STOP_ESCALATION, value)
                // Committed synchronously, unlike every other setting here. This one is written
                // from the path that has just failed to confirm a treadmill stopped, and the very
                // next thing that may happen is the process being killed — which is precisely the
                // case the persistence exists for. An asynchronous apply() can lose that write.
            }.commit()
        }

    /** See [KEY_STOP_ESCALATION_BOOT_COUNT]. -1 (never a real boot count) rather than null-by-absence. */
    var stopEscalationBootCount: Int
        get() = requirePrefs().getInt(KEY_STOP_ESCALATION_BOOT_COUNT, -1)
        set(value) {
            requirePrefs().edit().apply {
                if (value < 0) remove(KEY_STOP_ESCALATION_BOOT_COUNT) else putInt(KEY_STOP_ESCALATION_BOOT_COUNT, value)
            }.commit()
        }

    /**
     * Whether Stride's own launcher stands down to a plain backdrop while the track floor is drawn.
     *
     * Two-state, unlike [trackFloor], and that is not an oversight. There is no useful "decide
     * automatically" here: the launcher is only ever behind the track when the rider has already
     * asked for the track to be there, so the automatic answer and the "leave my launcher alone"
     * answer are the same answer.
     *
     * False by default, because the alternative default would hide the app grid, the settings
     * button and the workout panel on a console with no physical Home or Back button, for riders
     * who never asked for any of that.
     *
     * Read by the launcher rather than by the overlay: the overlay draws nothing for this setting,
     * so caching a copy of it in [OverlayService] would be state nothing reads.
     */
    var trackBackdrop: Boolean
        get() = requirePrefs().getBoolean(KEY_TRACK_BACKDROP, false)
        set(value) {
            requirePrefs().edit().putBoolean(KEY_TRACK_BACKDROP, value).apply()
        }

    /**
     * The fan setting to restore when a workout starts.
     *
     * Null until the rider picks one. Null is meaningful: on a machine that can match fan speed to
     * effort, "never chosen" should become Auto rather than an arbitrary fixed speed, and that
     * decision needs to know the difference.
     */
    var fanState: Int?
        get() = requirePrefs().let { p ->
            if (!p.contains(KEY_FAN)) null else p.getInt(KEY_FAN, GlassOsCommands.FAN_MEDIUM)
        }
        set(value) {
            requirePrefs().edit().apply {
                if (value == null) remove(KEY_FAN) else putInt(KEY_FAN, value)
            }.apply()
        }

    /**
     * Which transport Stride may use. Defaults to [Transport.GLASSOS] on a fresh install and on any
     * unreadable value: the safe answer to "what did the rider mean" is the one that is implemented.
     */
    var transport: Transport
        get() = transportChoice ?: detectedTransport ?: Transport.GLASSOS
        set(value) {
            requirePrefs().edit().putString(KEY_TRANSPORT, value.name).apply()
        }

    /**
     * What the rider explicitly picked, or null if they never have.
     *
     * The distinction is the whole point and it used to be lost: [Transport.parse] answered
     * `GLASSOS` both for "the rider chose iFit" and for "nobody has ever chosen", so there was no
     * way to detect a sensible default without overriding a real decision. A rider who deliberately
     * selects iFit on a console where it does not work is entitled to have that stick.
     */
    val transportChoice: Transport?
        get() = requirePrefs().getString(KEY_TRANSPORT, null)
            ?.let { raw -> Transport.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }

    /**
     * What the hardware says it should be, cached for the life of the process.
     *
     * Cached because it is read on every settings load and every transport open, and the probe
     * behind it opens a socket and enumerates USB. It cannot change without the console being
     * re-cabled or rebooted, both of which restart this process.
     */
    @Volatile private var detectedTransport: Transport? = null

    /**
     * Run detection and remember it, but **only once it has actually found something**.
     *
     * An inconclusive answer — no GlassOS on the port and no FitPro1 console enumerated — is not
     * cached, so the next attempt asks again. Caching it would mean a console whose USB device
     * appeared a moment after Stride started, or whose daemon was still binding, stayed on the
     * wrong transport until somebody power-cycled the machine.
     *
     * Blocking — it opens a socket — so callers must be off the main thread.
     */
    fun detectTransport(context: Context) {
        if (detectedTransport != null) return
        val result = TransportDetector.detect(context)
        if (result.confident) detectedTransport = result.transport
    }

    /** True when the transport in use was detected rather than chosen. For the settings screen. */
    val transportIsAutomatic: Boolean get() = transportChoice == null

    /** True once detection has actually established what this console is. */
    val transportResolved: Boolean get() = detectedTransport != null

    /**
     * Settle detection because GlassOS is demonstrably working.
     *
     * A live reading is better evidence than the port probe that produced the unresolved state, and
     * it is the one case the probe can miss: a daemon that bound its port a moment after Stride
     * looked. Only meaningful while nothing has been established, so this cannot override a
     * FitPro1 console that was already found.
     */
    fun resolveTransportFromReading() {
        if (detectedTransport != null) return
        detectedTransport = Transport.GLASSOS
        TransportDetector.noteGlassOsWorking()
    }

    /**
     * Whether Stride should connect to a paired Bluetooth heart rate strap.
     *
     * Off by default, and deliberately a separate setting from [transport] rather than part of it: a
     * strap is an accessory, not a way of reaching the machine, and a rider on any transport can
     * wear one. Off by default because connecting to somebody's chest strap is not something to do
     * because they installed a launcher.
     */
    var heartRateStrap: Boolean
        get() = requirePrefs().getBoolean(KEY_HR_STRAP, false)
        set(value) {
            requirePrefs().edit().putBoolean(KEY_HR_STRAP, value).apply()
        }

    /**
     * How the incline quick-pick column is spaced.
     *
     * [InclineSpacing.FINE] on a fresh install and on any value that no longer
     * parses, because the default has to be the column that shipped before this was a choice — a
     * rider who never opened this setting must not find their buttons rearranged by an update.
     *
     * Stored as the enum name rather than a step in percent. A number would look like it could be
     * anything, and it cannot: the coarse option is *two* steps either side of flat, and writing
     * "5" into a preference would lose the half of it that makes decline usable.
     */
    var inclineSpacing: InclineSpacing
        get() = InclineSpacing.parse(
            requirePrefs().getString(KEY_INCLINE_SPACING, null),
        )
        set(value) {
            requirePrefs().edit().putString(KEY_INCLINE_SPACING, value.name).apply()
        }

    /**
     * True when the direct register path is selected.
     *
     * Read this as "the rider has opted in", never as "the direct path is working". Whether it is
     * working is a question only the handshake can answer — `MachineLink.machineLinked` and
     * `MachineLink.machineCapabilities()` hold that answer, and it varies by machine. Any caller
     * that treats this as a capability check will be wrong.
     */
    val directHardwareAccess: Boolean
        get() = transport == Transport.DIRECT

    /**
     * Whether code exists behind the selected transport.
     *
     * Both transports are implemented now, so this is constant — kept because the settings screen
     * reads it and because a future third option would need it again. It says nothing about whether
     * the selected transport is currently *connected*: the direct path depends on a cable or a
     * paired radio being present, and a machine that is not plugged in is a link failure, not an
     * unimplemented feature. Those two were conflated while the direct path was a stub, and the
     * screen inherited the confusion.
     */
    val transportImplemented: Boolean
        get() = true
}
