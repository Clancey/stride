package io.stride.spikes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    /** What [repair] calls the fix for a console that cannot go home. */
    const val NAVIGATION = "navigation"

    /**
     * `Settings.Secure.USER_SETUP_COMPLETE`, named here because the constant is `@hide`.
     *
     * Set to 1 by the setup wizard. A console that never ran one leaves it at 0 forever, and Android
     * reads that as "setup still in progress" and refuses to launch a home activity at all.
     */
    private const val USER_SETUP_COMPLETE = "user_setup_complete"

    /**
     * Whether Android believes this device has finished being set up.
     *
     * Only ever read to decide whether to repair it. A console that answers 0 here has a HOME key
     * that silently does nothing, however healthy everything else is.
     */
    fun userSetupComplete(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, USER_SETUP_COMPLETE, 0) == 1

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
    // ------------------------------------------------------------------ self-repair

    /**
     * True when Stride can put its own grants back without involving the rider.
     *
     * WRITE_SECURE_SETTINGS is development-tier: no dialog can ever grant it, only a one-time adb
     * command. It then survives reinstalls, which is the whole point — the thing that keeps
     * clearing these grants is the reinstall itself.
     */
    fun canRepair(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Put back whatever Android dropped, and report what was actually restored.
     *
     * Reinstalling Stride clears `enabled_accessibility_services`. Back and Recents then stop
     * working with no error and no visible sign, on a console with no physical buttons — so the
     * rider discovers it while stuck inside a full-screen video app. Asking them to walk through
     * Settings every time is a workaround for a problem the app can simply fix.
     *
     * This only ever *adds* Stride's own component to the two lists. It never removes another app's
     * entry and never touches anything else, because a launcher quietly rewriting system settings
     * beyond its own row is exactly the behaviour that makes this permission dangerous.
     *
     * Returns the ids repaired, empty when there was nothing to do or no permission to do it.
     */
    fun repair(context: Context): List<String> {
        if (!canRepair(context)) return emptyList()
        val repaired = mutableListOf<String>()
        if (!userSetupComplete(context)) {
            // Android refuses to start *any* home activity while it believes setup is unfinished —
            // `ActivityTaskManagerService` logs "Not going home because user setup is in progress"
            // and drops the request. On a console that never ran a setup wizard the flag is simply
            // never set, and the effect is not subtle: the HOME key does nothing, Stride's own Home
            // button does nothing, and a rider who opens Netflix on a machine with no physical
            // buttons cannot get back out. Reported as "most of the buttons don't work", which is
            // what it looks like from the outside.
            //
            // `device_provisioned` is deliberately not touched. It was already 1 on the console this
            // was found on, the two flags mean different things, and this class's rule is to change
            // the narrowest thing that fixes the fault.
            if (writeSecure(context, USER_SETUP_COMPLETE, "1")) {
                repaired += NAVIGATION
            }
        }
        if (!hasAccessibility(context)) {
            val self = ComponentName(context, StrideAccessibilityService::class.java)
                .flattenToString()
            if (addToSecureList(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, self)) {
                // The list alone is not enough: the master switch gates whether any of it runs.
                writeSecure(context, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                repaired += ACCESSIBILITY
            }
        }
        if (!hasNotificationListener(context)) {
            val self = ComponentName(context, StrideNotificationListener::class.java)
                .flattenToString()
            if (addToSecureList(context, "enabled_notification_listeners", self)) {
                repaired += NOTIFICATIONS
            }
        }
        return repaired
    }

    /** Append [entry] to a colon-separated secure list, preserving every other app's entries. */
    private fun addToSecureList(context: Context, key: String, entry: String): Boolean {
        val current = Settings.Secure.getString(context.contentResolver, key)
        val merged = mergeSecureList(current, entry) ?: return true
        return writeSecure(context, key, merged)
    }

    /**
     * The new value for a colon-separated secure list, or null when [entry] is already present.
     *
     * Kept pure and separate because this is the dangerous line in the whole class: these lists are
     * shared with every other app on the device, and dropping someone else's accessibility service
     * because we rewrote the key carelessly would be a far worse bug than the one we are fixing.
     * Blank segments are dropped — Android leaves trailing colons behind, and re-appending them
     * grows the value without bound across repairs.
     */
    internal fun mergeSecureList(current: String?, entry: String): String? {
        val entries = current.orEmpty().split(':').filter { it.isNotBlank() }
        if (entries.contains(entry)) return null
        return (entries + entry).joinToString(":")
    }

    private fun writeSecure(context: Context, key: String, value: String): Boolean = try {
        Settings.Secure.putString(context.contentResolver, key, value)
    } catch (_: SecurityException) {
        // Racing a revoked grant is not worth crashing the launcher over; the setup card is still
        // watching and will ask the rider instead.
        false
    }

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
