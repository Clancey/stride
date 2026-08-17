package io.stride.spikes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Hands the console's GSF device id to `adb`, so the registration can be driven from a computer.
 *
 * The id is what Google's uncertified-device page wants, and there is no way to read it from a
 * shell: the provider requires `READ_GSERVICES`, which the shell user does not hold, and the
 * database behind it needs root. Stride holds the permission, so it can answer on the shell's
 * behalf — that is all this is.
 *
 * ```
 * adb shell am broadcast -a io.stride.spikes.DUMP_CERTIFICATION
 *   -> Broadcast completed: result=0, data="3849284900098157545"
 * ```
 *
 * Guarded by `android.permission.DUMP` in the manifest rather than left open. The shell user holds
 * DUMP, so `adb` reaches it; an ordinary app on the console does not, so this does not quietly turn
 * a stable device identifier into something any installed app can read. That matters more here than
 * on a phone, because the whole point of this console is that it runs other people's software.
 *
 * The answer goes back as the broadcast's result data, not to logcat. `am broadcast` prints result
 * data directly, so the caller gets one parseable line instead of scraping a log that is also
 * world-readable and would leave the id sitting in a buffer long after anyone needed it.
 */
class CertificationDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultData = resultFor(PlayCertification.androidId(context.contentResolver))
    }

    companion object {
        const val ACTION = "io.stride.spikes.DUMP_CERTIFICATION"

        /**
         * What the shell sees. Distinguishes the two "no id" cases, because they need opposite
         * things from the operator: no Google apps at all means install the Play bundle first,
         * while a missing id means GSF has not reached Google yet and wants a network and a reboot.
         * A bare empty string for both would send half of them down the wrong path.
         */
        internal fun resultFor(id: String?): String = id ?: "none"
    }
}
