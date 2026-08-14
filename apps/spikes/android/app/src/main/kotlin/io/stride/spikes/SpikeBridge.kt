package io.stride.spikes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Platform bridge for the Phase 0 spike harness.
 *
 * Everything here answers a specific spike question from docs/PLAN.md section 6. Nothing here is
 * production Stride code - the real launcher puts device and workout state behind the Control and
 * Safety Coordinator (plan section 3.1), not behind ad-hoc method calls.
 */
class SpikeBridge(private val context: Context) : MethodChannel.MethodCallHandler {

    companion object {
        const val CHANNEL = "io.stride.spikes/bridge"
        const val IFIT_CONSOLE_PACKAGE = "com.ifit.rivendell"
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "environment" -> result.success(environment())

                // --- S1: launcher ---
                "isDefaultHome" -> result.success(isDefaultHome())
                "homeCandidates" -> result.success(homeCandidates())
                "openHomeSettings" -> result.success(openHomeSettings())
                "goHome" -> result.success(goHome())

                // --- S3: overlay ---
                "canDrawOverlays" -> result.success(canDrawOverlays())
                "startOverlay" -> result.success(startOverlay())
                "stopOverlay" -> result.success(stopOverlay())
                "overlayStatus" -> result.success(overlayStatus())
                "resetOverlayCounters" -> result.success(resetOverlayCounters())

                // --- S4: media apps present ---
                "listApps" -> result.success(listApps())

                // --- S5: media session control ---
                "notificationListenerEnabled" -> result.success(StrideNotificationListener.isConnected)
                "mediaSessions" -> result.success(mediaSessions())
                "pauseAllPlaying" -> result.success(pauseAllPlaying())
                "resumePausedByUs" -> result.success(resumePausedByUs())
                "dispatchMediaKey" -> result.success(dispatchMediaKey(call.argument<Int>("keyCode")!!))

                // --- S10: navigation ---
                "accessibilityConnected" -> result.success(StrideAccessibilityService.isConnected())
                "accessibilityEnabledInSettings" -> result.success(accessibilityEnabledInSettings())
                "goBack" -> result.success(StrideAccessibilityService.instance?.goBack() ?: false)
                "goRecents" -> result.success(StrideAccessibilityService.instance?.goRecents() ?: false)
                "foregroundPackage" -> result.success(StrideAccessibilityService.foregroundPackage)

                // --- S2: locate the iFit console APK for cert extraction ---
                "ifitApkPaths" -> result.success(ifitApkPaths())

                "launchApp" -> result.success(launchApp(call.argument<String>("package")!!))

                else -> result.notImplemented()
            }
        } catch (t: Throwable) {
            result.error("SPIKE_ERROR", t.message, t.stackTraceToString())
        }
    }

    // ------------------------------------------------------------------ environment

    private fun environment(): Map<String, Any?> = mapOf(
        "sdkInt" to Build.VERSION.SDK_INT,
        "release" to Build.VERSION.RELEASE,
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "product" to Build.PRODUCT,
        "hardware" to Build.HARDWARE,
        "fingerprint" to Build.FINGERPRINT,
        "packageName" to context.packageName,
    )

    // ------------------------------------------------------------------ S1 launcher

    private fun resolveHome(): ResolveInfo? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

    private fun isDefaultHome(): Boolean =
        resolveHome()?.activityInfo?.packageName == context.packageName

    /**
     * Every installed HOME candidate. Critical for S1: this is how you confirm iFit is still a
     * reachable escape hatch before making Stride the default.
     */
    private fun homeCandidates(): List<Map<String, Any?>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current = resolveHome()?.activityInfo?.packageName
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map {
                mapOf(
                    "package" to it.activityInfo.packageName,
                    "activity" to it.activityInfo.name,
                    "label" to it.loadLabel(context.packageManager).toString(),
                    "isCurrentDefault" to (it.activityInfo.packageName == current),
                )
            }
    }

    private fun openHomeSettings(): Boolean {
        val candidates = listOf(
            Settings.ACTION_HOME_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (action in candidates) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        }
        return false
    }

    private fun goHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    // ------------------------------------------------------------------ S3 overlay

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun startOverlay(): Boolean {
        if (!canDrawOverlays()) return false
        val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        return true
    }

    private fun stopOverlay(): Boolean {
        context.stopService(Intent(context, OverlayService::class.java))
        return true
    }

    private fun overlayStatus(): Map<String, Any?> = mapOf(
        "running" to OverlayService.isRunning,
        "canDrawOverlays" to canDrawOverlays(),
        "lastGesture" to OverlayService.lastGesture,
        "edgeTouchCount" to OverlayService.edgeTouchCount,
        "navGestureCount" to OverlayService.navGestureCount,
        "stolenTouchCount" to OverlayService.stolenTouchCount,
        "cancelledGestureCount" to OverlayService.cancelledGestureCount,
        "lastTouchForegroundPackage" to OverlayService.lastTouchForegroundPackage,
    )

    private fun resetOverlayCounters(): Boolean {
        OverlayService.resetCounters()
        return true
    }

    // ------------------------------------------------------------------ S4 app inventory

    /**
     * Launchable apps, plus the evidence Stride uses to *rank* media likelihood.
     *
     * Ranking, not classification (plan section 3.6): many real media apps expose nothing
     * detectable until first launch, so a hard classifier is wrong in both directions.
     */
    private fun listApps(): List<Map<String, Any?>> {
        val pm = context.packageManager
        val out = linkedMapOf<String, MutableMap<String, Any?>>()

        fun collect(category: String) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            pm.queryIntentActivities(intent, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                val entry = out.getOrPut(pkg) {
                    mutableMapOf(
                        "package" to pkg,
                        "label" to ri.loadLabel(pm).toString(),
                        "activity" to ri.activityInfo.name,
                        "leanback" to false,
                        "hasMediaBrowserService" to false,
                    )
                }
                if (category == Intent.CATEGORY_LEANBACK_LAUNCHER) entry["leanback"] = true
            }
        }
        collect(Intent.CATEGORY_LAUNCHER)
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER)

        // MediaBrowserService is the strongest static signal of a media app.
        val browseIntent = Intent("android.media.browse.MediaBrowserService")
        pm.queryIntentServices(browseIntent, 0).forEach { ri ->
            out[ri.serviceInfo.packageName]?.put("hasMediaBrowserService", true)
        }

        return out.values.map { it.toMap() }
    }

    /**
     * Launch an app by package.
     *
     * getLaunchIntentForPackage only finds CATEGORY_LAUNCHER entry points, so TV-only apps
     * (e.g. Netflix Ninja) that expose only a LEANBACK_LAUNCHER activity would look unlaunchable.
     * Fall back to the leanback launch intent, then to an explicit component built from the recorded
     * leanback launcher activity, before giving up.
     */
    private fun launchApp(pkg: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: leanbackComponentIntent(pkg)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    /** Build an explicit launch intent from a package's LEANBACK_LAUNCHER activity, if it has one. */
    private fun leanbackComponentIntent(pkg: String): Intent? {
        val query = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(pkg)
        val ri = context.packageManager.queryIntentActivities(query, 0).firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
    }

    // ------------------------------------------------------------------ S5 media sessions

    private fun activeControllers(): List<MediaController> {
        if (!StrideNotificationListener.isConnected) return emptyList()
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, StrideNotificationListener::class.java)
        return msm.getActiveSessions(component)
    }

    private fun mediaSessions(): List<Map<String, Any?>> {
        val controllers = activeControllers()
        // Keep the ownership tracker's callbacks registered for every live session, even when the UI
        // is only observing, so state transitions are seen as they happen.
        MediaOwnershipTracker.sync(controllers)
        return controllers.map { c ->
            val state = c.playbackState?.state
            mapOf(
                "package" to c.packageName,
                "state" to (state ?: -1),
                "isPlaying" to (state == PlaybackState.STATE_PLAYING),
                "title" to c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                "artist" to c.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                "pausedByStride" to MediaOwnershipTracker.isOwned(c.sessionToken),
            )
        }
    }

    /**
     * Pause only what is actually playing, and remember exactly which sessions we touched.
     *
     * Ownership is tracked by [MediaOwnershipTracker] against MediaSession.Token identity and is only
     * recorded once the pause is actually observed, so resuming later restores only what Stride
     * paused and never media the user paused themselves (plan section 3.2 / Phase 3).
     */
    private fun pauseAllPlaying(): List<String> =
        MediaOwnershipTracker.pauseAllPlaying(activeControllers())

    private fun resumePausedByUs(): List<String> =
        MediaOwnershipTracker.resumePausedByUs(activeControllers())

    /** Last-resort fallback. Nondeterministic: whichever app the system thinks owns media keys. */
    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return true
    }

    // ------------------------------------------------------------------ S10 accessibility

    private fun accessibilityEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.startsWith("${context.packageName}/") }
    }

    // ------------------------------------------------------------------ S2 iFit APK

    /**
     * Locate the installed iFit console APK so the cert extractor can read it.
     *
     * Equivalent of `pm path com.ifit.rivendell`, but via PackageManager so it works without a
     * shell. Returns base APK plus split APKs.
     */
    private fun ifitApkPaths(): Map<String, Any?> {
        val pm = context.packageManager
        // Other iFit-family packages, in case this firmware names the console package differently.
        val related = pm.getInstalledApplications(0)
            .filter { it.packageName.startsWith("com.ifit") || it.packageName.contains("glassos") }
            .map { mapOf("package" to it.packageName, "sourceDir" to it.sourceDir) }

        return try {
            val info = pm.getApplicationInfo(IFIT_CONSOLE_PACKAGE, 0)
            val paths = mutableListOf(info.sourceDir)
            info.splitSourceDirs?.let { paths.addAll(it) }
            mapOf(
                "installed" to true,
                "package" to IFIT_CONSOLE_PACKAGE,
                "paths" to paths,
                "readable" to paths.map { java.io.File(it).canRead() },
                "related" to related,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            mapOf(
                "installed" to false,
                "package" to IFIT_CONSOLE_PACKAGE,
                "paths" to emptyList<String>(),
                "readable" to emptyList<Boolean>(),
                "related" to related,
            )
        }
    }
}
