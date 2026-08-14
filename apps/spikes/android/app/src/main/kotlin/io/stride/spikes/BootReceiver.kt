package io.stride.spikes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * S3 spike: "does the overlay survive reboot?"
 *
 * RECEIVE_BOOT_COMPLETED was declared in the manifest but had no receiver, so the reboot-survival
 * question could not actually be answered. This restarts the draw-only overlay service on boot.
 *
 * SAFETY INVARIANT (plan section 5, rule 6 "no auto-start" of motion): this harness has no
 * motor-control path whatsoever - not even behind a flag - so nothing this receiver starts can move
 * the belt. It only (re)creates the inert diagnostic overlay windows. The "no auto-start" rule is
 * about belt motion; recreating a passive overlay does not violate it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Only bother if the overlay permission is actually granted; OverlayService fails soft
        // otherwise, but starting a service just to no-op is wasteful.
        if (!Settings.canDrawOverlays(context)) return

        val service = Intent(context, OverlayService::class.java)
            .setAction(OverlayService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
