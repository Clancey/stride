package io.stride.spikes

import android.service.notification.NotificationListenerService

/**
 * S5 spike.
 *
 * MediaSessionManager.getActiveSessions() requires the caller to be an enabled notification
 * listener. This class exists only to be that listener - it does no notification handling.
 *
 * Enable without a Settings UI:
 *   adb shell cmd notification allow_listener \
 *     io.stride.spikes/io.stride.spikes.StrideNotificationListener
 */
class StrideNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
    }

    override fun onListenerDisconnected() {
        isConnected = false
        super.onListenerDisconnected()
    }
}
