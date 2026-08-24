package io.stride.spikes

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Which transport this console should use when the rider has not said.
 *
 * ## Why this exists
 *
 * The default used to be [StrideSettings.Transport.GLASSOS] on every machine, because GlassOS was
 * the only implementation. That is now wrong in a way that produces a completely dead app rather
 * than a degraded one: a pre-GlassOS console — an X22i and its siblings — has no GlassOS daemon at
 * all, so Stride defaulted to talking to something that was never there, showed `Not measured` for
 * every metric, and offered a rider no clue that the fix was a setting three screens down.
 *
 * ## The rule, and why it leans the way it does
 *
 * 1. **GlassOS answering → GLASSOS.** Proven by a socket, not assumed from the credentials: those
 *    are bundled in this APK and load perfectly well on a console that has no daemon, so
 *    `isLinked()` is not evidence of anything about *this* machine.
 * 2. **Otherwise, an iFit console on USB → DIRECT.**
 * 3. **Otherwise → GLASSOS**, which is where it started.
 *
 * The order is deliberately conservative, and the reason is worth being explicit about: a
 * GlassOS machine such as the Commercial 1750 *also* has an ICON console on USB, so testing USB
 * first would move a machine that works today onto a register path that has never been run against
 * real hardware. GlassOS is checked first so that machine keeps exactly what it has, and only a
 * console where GlassOS is demonstrably absent is sent anywhere else.
 */
object TransportDetector {

    private const val TAG = "TransportDetector"

    /** The loopback port GlassOS listens on. */
    private const val GLASSOS_PORT = 54321

    /**
     * How long to wait for the loopback connect.
     *
     * Generous for a connection to this same machine, where the answer is normally instant either
     * way: a refused port fails in about a millisecond, and the only reason to wait at all is a
     * daemon still coming up during boot. Nothing blocks on this — it runs on the connect thread.
     */
    private const val PROBE_TIMEOUT_MS = 300

    /**
     * Work out what this console should use, or null if the question cannot be answered.
     *
     * Null is not a failure to be defaulted away: callers keep the stored setting when they get it,
     * so a probe that could not run leaves the rider exactly where they were.
     */
    /**
     * A detection result, and whether it is worth remembering.
     *
     * [confident] is the important half. "GlassOS answered" and "there is a FitPro1 console on USB"
     * are both statements about hardware that was actually observed. "Neither, so use GlassOS" is
     * not a finding at all — it is a fallback, and caching it for the life of the process is how a
     * console that enumerated its USB device a second after Stride started stays unreachable until
     * somebody reboots it.
     */
    data class Result(val transport: StrideSettings.Transport, val confident: Boolean)

    fun detect(context: Context): Result {
        val decision = decide(context)
        // Recorded as the probe runs, so the settings screen can describe what was found without
        // repeating it. `describe` is read from the Flutter method channel, which is the main
        // thread, and a socket connect there is a NetworkOnMainThreadException rather than a slow
        // answer -- on a launcher with no Back button that is an unrecoverable screen.
        lastDescription = summarise(glassOs = glassOsSeen, usb = usbSeen)
        return decision
    }

    /** True when this console will never need asking again. */
    private fun confident(): Boolean = glassOsSeen || usbSeen == FitProCodec.Variant.FITPRO1

    /** The probe's own findings, remembered so [describe] never has to touch the network. */
    @Volatile private var glassOsSeen: Boolean = false

    @Volatile private var usbSeen: FitProCodec.Variant? = null

    @Volatile private var lastDescription: String? = null

    private fun decide(context: Context): Result {
        glassOsSeen = glassOsListening()
        usbSeen = usbConsole(context)
        if (glassOsSeen) {
            Log.i(TAG, "GlassOS answered on $GLASSOS_PORT; defaulting to GLASSOS")
            return Result(StrideSettings.Transport.GLASSOS, confident = true)
        }
        // **Only FitPro1 implies DIRECT.** A FitPro2 console is by definition a GlassOS-era machine
        // -- a Commercial 1750 has one on USB *and* runs the daemon -- so "FitPro2 on USB but the
        // port did not answer" is far more likely to mean the daemon has not finished binding than
        // that it will never exist. A closed loopback port is refused instantly, so this probe can
        // easily land in the second or so before GlassOS is up during boot; inferring DIRECT from
        // that would move a machine that works today onto a motor-control path that has never been
        // run against real hardware, and the cached answer would keep it there.
        //
        // A FitPro1 console is the opposite case: that generation has no GlassOS at all, so its
        // absence is a fact rather than a race.
        if (usbSeen == FitProCodec.Variant.FITPRO1) {
            Log.i(TAG, "no GlassOS and a FitPro1 console is on USB; defaulting to DIRECT")
            return Result(StrideSettings.Transport.DIRECT, confident = true)
        }
        Log.i(
            TAG,
            "inconclusive (glassOs=$glassOsSeen usb=$usbSeen); staying on GLASSOS and will re-check",
        )
        return Result(StrideSettings.Transport.GLASSOS, confident = false)
    }

    /**
     * Whether something is listening on the GlassOS port.
     *
     * A TCP connect and nothing more — no TLS, no RPC, no credentials. The question here is only
     * "does this console run the daemon", and answering it with a full handshake would make startup
     * wait on work that tells us nothing extra.
     */
    fun glassOsListening(): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", GLASSOS_PORT), PROBE_TIMEOUT_MS)
            true
        }
    } catch (t: Throwable) {
        false
    }

    /** The attached iFit console's variant, or null when there is not one. */
    fun usbConsole(context: Context): FitProCodec.Variant? {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val device = UsbSerialTransport.consoleDevice(manager) ?: return null
        return UsbSerialTransport.variantOf(device)
    }

    /**
     * A sentence describing what was found, for the settings screen.
     *
     * Worth showing rather than only acting on: a rider whose machine was detected as one thing and
     * behaves like another has learned more from this line than from any amount of retrying.
     */
    fun describe(): String =
        lastDescription ?: "Stride hasn't checked what this console runs yet."

    private fun summarise(glassOs: Boolean, usb: FitProCodec.Variant?): String {
        return when {
            glassOs && usb != null -> "iFit's GlassOS service is running, and a ${label(usb)} console is on USB."
            glassOs -> "iFit's GlassOS service is running on this console."
            usb != null -> "No GlassOS service, and a ${label(usb)} console is on USB."
            else -> "No GlassOS service and no iFit console on USB."
        }
    }

    private fun label(variant: FitProCodec.Variant): String = when (variant) {
        FitProCodec.Variant.FITPRO1 -> "FitPro1"
        FitProCodec.Variant.FITPRO2 -> "FitPro2"
    }
}
