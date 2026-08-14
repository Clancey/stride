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
    private const val KEY_TRANSPORT = "machine.transport"
    private const val KEY_FAN = "machine.fanState"

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
        get() = Transport.parse(requirePrefs().getString(KEY_TRANSPORT, null))
        set(value) {
            requirePrefs().edit().putString(KEY_TRANSPORT, value.name).apply()
        }

    /**
     * True when the direct register path is selected.
     *
     * Read this as "the rider has opted in", never as "the direct path is working". Nothing is
     * connected behind it yet, and any caller that treats this as a capability check will be wrong.
     */
    val directHardwareAccess: Boolean
        get() = transport == Transport.DIRECT

    /** Whether a transport actually exists behind [transport]. Only GlassOS is implemented. */
    val transportImplemented: Boolean
        get() = transport == Transport.GLASSOS
}
