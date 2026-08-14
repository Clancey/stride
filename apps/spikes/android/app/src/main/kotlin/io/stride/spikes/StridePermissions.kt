package io.stride.spikes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

/**
 * The system grants Stride cannot work without, and how to send the rider to the exact page that
 * fixes each one.
 *
 * These grants are not stable. Uninstalling an unrelated app cleared
 * `enabled_accessibility_services` on this console and took Stride's Back button with it, with no
 * error, no log, and no visible sign — the edge swipe simply stopped doing anything. A launcher on
 * a machine with no physical buttons cannot fail that way quietly.
 *
 * So each grant states, in the rider's terms, *what stops working* rather than what permission is
 * missing. "Notification access is disabled" tells them nothing; "Stride can't see or control
 * what's playing" tells them whether they care.
 */
object StridePermissions {

    /** A grant, whether we currently hold it, and what its absence costs. */
    data class Grant(
        val id: String,
        val label: String,
        val granted: Boolean,
        /** What the rider loses. Present tense, concrete, no permission jargon. */
        val consequence: String,
    )

    const val OVERLAY = "overlay"
    const val ACCESSIBILITY = "accessibility"
    const val NOTIFICATIONS = "notifications"

    fun all(context: Context): List<Grant> = listOf(
        Grant(
            id = OVERLAY,
            label = "Draw over other apps",
            granted = hasOverlay(context),
            consequence = "Without it the workout controls can't appear over Spotify or Jellyfin.",
        ),
        Grant(
            id = ACCESSIBILITY,
            label = "Accessibility service",
            granted = hasAccessibility(context),
            // The console has no physical Back button and there is no other way for a non-system
            // app to send one, so this is the one grant with no workaround at all.
            consequence = "Without it the edge swipe can't go Back or open Recents. This console " +
                "has no physical buttons, so apps become one-way trips.",
        ),
        Grant(
            id = NOTIFICATIONS,
            label = "Notification access",
            granted = hasNotificationListener(context),
            consequence = "Without it Stride can't show what's playing, and pausing a workout " +
                "won't pause your music.",
        ),
    )

    /** True when every grant is held. */
    fun allGranted(context: Context): Boolean = all(context).all { it.granted }

    fun hasOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * Read the enabled-services list rather than asking whether our service object is alive.
     *
     * The service can be bound and still absent from the list mid-transition, and the list is what
     * survives a reboot, so the list is the thing that actually answers "will Back work".
     */
    fun hasAccessibility(context: Context): Boolean {
        val expected = ComponentName(context, StrideAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            val parsed = ComponentName.unflattenFromString(entry) ?: continue
            if (parsed == expected) return true
        }
        return false
    }

    fun hasNotificationListener(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        }
    }

    /**
     * Open the Settings page that fixes [id]. Returns false when no page could be opened, so the
     * caller can say so instead of leaving the rider tapping a button that does nothing.
     *
     * Only the overlay page accepts a package, so that one lands on Stride's own row. The other two
     * open a list the rider has to find Stride in — which is why the UI must name the row to look
     * for rather than just saying "open settings".
     */
    fun openSettingsFor(context: Context, id: String): Boolean {
        val intent = when (id) {
            OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            NOTIFICATIONS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            else -> return false
        }
        // Started from a service or a non-activity context in some paths, and Settings must not
        // land inside Stride's own task or Home will bring the rider back to it.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
