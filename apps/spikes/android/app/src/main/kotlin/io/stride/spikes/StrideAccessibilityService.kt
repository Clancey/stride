package io.stride.spikes

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * S10 spike.
 *
 * The 1750 console has no physical Home or Back button. Home is trivial (we are the home app),
 * but Back has exactly one implementation available to a non-system app:
 * performGlobalAction(GLOBAL_ACTION_BACK). `input keyevent 4` requires INJECT_EVENTS, which is
 * signature-level and unobtainable without /system access.
 *
 * The spike question is not "does this API exist" - it is whether the service can be enabled via
 * adb on this firmware and whether it SURVIVES REBOOT.
 */
class StrideAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: StrideAccessibilityService? = null
            private set

        /** Foreground package as last reported by a window-state change. */
        @Volatile
        var foregroundPackage: String? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                // Ignore our own overlay windows so we report the app underneath.
                if (pkg != packageName) foregroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {}

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
