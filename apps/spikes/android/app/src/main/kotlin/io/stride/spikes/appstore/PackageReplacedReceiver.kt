package io.stride.spikes.appstore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import io.stride.spikes.MainActivity
import io.stride.spikes.OverlayService

/**
 * Brings Stride back after Stride updates itself.
 *
 * The platform restarts the process specifically to deliver ACTION_MY_PACKAGE_REPLACED, so this
 * runs even though the install just killed everything we had.
 *
 * BACKGROUND ACTIVITY START: since Android 10 an app in the background generally may not call
 * startActivity. Stride is exempt because it holds SYSTEM_ALERT_WINDOW - the same grant the overlay
 * already requires, checked below. If the rider has not granted it, the activity start is likely to
 * be swallowed, so the overlay is started first and the launch is attempted regardless: a silent
 * no-op is no worse than the stranded console we get by doing nothing.
 *
 * SAFETY: relaunching the launcher UI cannot move the belt. This process has no motor path, and
 * WorkoutSession comes back idle after a cold start.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!RelaunchPolicy.shouldRelaunch(intent.action)) return

        // Re-arm the periodic check first. WorkManager's records survive the update, but a
        // just-updated console is the one we least want to leave un-scheduled.
        AppstoreWorker.ensureScheduled(context)

        // Order matters: the overlay is what gives the rider Back and Home. Restore the way out
        // before restoring the thing they need a way out of.
        if (Settings.canDrawOverlays(context)) {
            runCatching {
                context.startForegroundService(
                    Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START)
                )
            }.onFailure { Log.w(TAG, "overlay restart after update failed", it) }
        }

        // This is the Home press the console cannot make for itself.
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }.onFailure { Log.w(TAG, "relaunch after update failed", it) }
    }

    private companion object {
        const val TAG = "StrideRelaunch"
    }
}
