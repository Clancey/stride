package io.stride.spikes.appstore

import android.content.Intent

/**
 * Pure policy for "should Stride bring itself back after being replaced?".
 *
 * WHY THIS EXISTS AT ALL: on a phone, updating the HOME app is harmless. The system kills it, you
 * are left on whatever was foreground, and pressing Home starts the freshly installed launcher.
 * The Home *button* is the recovery path, and every other launcher relies on it.
 *
 * This console has no Home button. Stride's own overlay is the only Home affordance, and the
 * overlay dies with the process. So after a self-update the rider is stranded in whatever app was
 * behind Stride with no way back - which is precisely what happens if nothing here runs.
 *
 * A separate updater *process* would not help: Android tears down every process of a package it
 * replaces, including one declared with android:process. Only a separate *package* survives, and
 * that is a second APK with its own install and its own REQUEST_INSTALL_PACKAGES grant. This is the
 * mechanism the platform provides instead, and it is enough.
 */
object RelaunchPolicy {

    /**
     * Only ACTION_MY_PACKAGE_REPLACED means "you were just updated". Deliberately NOT
     * ACTION_PACKAGE_REPLACED, which fires for every other app on the device too - relaunching the
     * launcher because Spotify updated would yank a rider out of Spotify mid-run.
     */
    fun shouldRelaunch(action: String?): Boolean = action == Intent.ACTION_MY_PACKAGE_REPLACED
}
