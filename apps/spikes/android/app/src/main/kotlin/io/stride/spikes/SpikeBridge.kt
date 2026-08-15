package io.stride.spikes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import android.view.KeyEvent
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.stride.spikes.appstore.AppstoreBridge
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors

internal fun WorkoutSession.State.channelName(): String =
    name.lowercase(Locale.US)

private fun Long.toChannelInt(): Int =
    coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * Platform bridge for the Phase 0 spike harness.
 *
 * Everything here answers a specific spike question from docs/PLAN.md section 6. Nothing here is
 * production Stride code - the real launcher puts device and workout state behind the Control and
 * Safety Coordinator (plan section 3.1), not behind ad-hoc method calls.
 */
class SpikeBridge(private val context: Context) : MethodChannel.MethodCallHandler {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val systemAudio = SystemAudio(context)
    private val appstore = AppstoreBridge(context)
    private val iconExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StrideIconRenderer")
    }
    private val iconCache = LruCache<String, ByteArray>(128)

    init {
        WorkoutMediaCoupling.attach(context)
        WorkoutMachineCoupling.attach()
    }

    companion object {
        const val CHANNEL = "io.stride.spikes/bridge"
        const val IFIT_CONSOLE_PACKAGE = "com.ifit.rivendell"

        private const val DEFAULT_ICON_SIZE_PX = 192

        /**
         * Launcher entries that exist on this console but are not apps a rider would ever want.
         *
         * Deliberately an explicit list rather than a "hide system apps" rule: most of what a
         * rider *does* want here is system-signed too, so a blanket rule would empty the grid.
         * This is a fixed appliance with a known image, so naming them is honest and reviewable.
         */
        internal val HIDDEN_PACKAGES = setOf(
            // Vendor and factory test tools shipped on the MediaTek board.
            "com.cvte.mt8390.simpletest",
            "com.mediatek.bluetooth",
            "com.mediatek.gnss.nonframeworklbs",
            "com.mesh.test.provisioner",
            // A background service with a launcher entry it should not have. Tapping it does
            // nothing useful, and it is what actually drives the treadmill.
            "com.ifit.glassos_service",
            // Developer harnesses: a WebView test shell and the systrace capture tool.
            "org.chromium.webview_shell",
            "com.android.traceur",
            // iFit. Its only CATEGORY_LAUNCHER entry is
            // com.ifit.val_workout_acceptance.view.WorkoutAcceptanceActivity — a factory
            // acceptance-test harness labelled "Workout Player", not the console experience — so
            // this tile never was the escape hatch it was once planned to be. Stride drives the
            // treadmill itself now, so the tile is gone. This hides it from the grid; it stays
            // installed, and nothing here disables or removes it.
            IFIT_CONSOLE_PACKAGE,
            // Android settings is reachable from Stride's own settings screen, which warns first
            // that our overlay is blanked in there. A pinned tile would skip that warning and drop
            // a rider into a screen with no Back or Home on a console with no physical buttons.
            "com.android.settings",
        )

        /**
         * Whether a launchable package should be kept out of the app grid and the pin picker.
         *
         * Pure so it can be tested without a PackageManager. [selfPackage] is passed rather than
         * hard-coded: Stride is the launcher, so offering to pin or launch ourselves is a no-op
         * tile that costs a rider a tap to discover it does nothing.
         */
        internal fun isHiddenFromLauncher(pkg: String, selfPackage: String): Boolean =
            pkg == selfPackage || pkg in HIDDEN_PACKAGES
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
                "hudHeightPx" -> result.success(hudHeightPx())
                "hudInsetsPx" -> result.success(hudInsetsPx())

                // --- S4: media apps present ---
                "listApps" -> result.success(listApps())
                "appIcon" -> appIcon(call, result)

                // --- S5: media session control ---
                "notificationListenerEnabled" -> result.success(StrideNotificationListener.isConnected)
                "mediaSessions" -> result.success(mediaSessions())
                "pauseAllPlaying" -> result.success(pauseAllPlaying())
                "resumePausedByUs" -> result.success(resumePausedByUs())
                "dispatchMediaKey" -> result.success(dispatchMediaKey(call.argument<Int>("keyCode")!!))

                // --- workout/state layer for overlay controls ---
                "workoutState" -> result.success(WorkoutSession.state.channelName())
                "workoutElapsedMs" -> result.success(WorkoutSession.elapsedMs().toChannelInt())
                "workoutStart" -> result.success(workoutStart())
                "workoutPause" -> result.success(workoutPause())
                "workoutResume" -> result.success(workoutResume())
                "workoutStop" -> result.success(workoutStop())
                "workoutCancelStart" -> result.success(workoutCancelStart())
                "volumeGet" -> result.success(systemAudio.snapshot())
                "volumeSet" -> result.success(volumeSet(call))
                "machineSnapshot" -> result.success(machineSnapshot())
                "goalSet" -> result.success(goalSet(call))
                "goalGet" -> result.success(goalGet())

                // --- Now playing: read the current session and drive its transport ---
                "nowPlaying" -> result.success(nowPlaying())
                "nowPlayingArtwork" -> result.success(nowPlayingArtwork())
                "mediaPlayPause" -> result.success(MediaNowPlaying.playPause(context))
                "mediaSkipNext" -> result.success(MediaNowPlaying.skipNext(context))
                "mediaSkipPrevious" -> result.success(MediaNowPlaying.skipPrevious(context))
                "trackFloorGet" -> result.success(trackFloorGet())
                "trackFloorSet" -> result.success(trackFloorSet(call))

                // --- settings + system grants ---
                "settingsGet" -> result.success(settingsGet())
                "transportSet" -> result.success(transportSet(call))
                "grantsGet" -> result.success(grantsGet())
                "grantsRepair" -> result.success(StridePermissions.repair(context))
                "grantOpenSettings" -> result.success(
                    // Repair first. When Stride can restore the grant itself there is no reason to
                    // send the rider into a Settings list to do it by hand — and on this console
                    // Android hides our overlay over those pages, so they arrive with no Back or
                    // Home button of ours to get out with.
                    StridePermissions.repair(context).contains(call.argument<String>("id")) ||
                        StridePermissions.openSettingsFor(
                            context,
                            call.argument<String>("id").orEmpty(),
                        ),
                )

                "openSystemSettings" -> result.success(openSystemSettings())

                // --- S10: navigation ---
                "accessibilityConnected" -> result.success(StrideAccessibilityService.isConnected())
                "accessibilityEnabledInSettings" -> result.success(accessibilityEnabledInSettings())
                "goBack" -> result.success(StrideAccessibilityService.instance?.goBack() ?: false)
                "goRecents" -> result.success(StrideAccessibilityService.instance?.goRecents() ?: false)
                "foregroundPackage" -> result.success(StrideAccessibilityService.foregroundPackage)

                "launchApp" -> result.success(launchApp(call.argument<String>("package")!!))
                "uninstallApp" -> result.success(
                    uninstallApp(call.argument<String>("package")!!)
                )

                // --- appstore: catalog, updates, installs (see appstore/StrideAppstoreService) ---
                "appstoreStatus" -> result.success(appstore.status())
                "appstoreCheckNow" -> result.success(appstore.checkNow())
                "appstoreInstall" -> result.success(
                    appstore.install(call.argument<String>("package")!!)
                )
                "appstoreCancel" -> result.success(appstore.cancel(call.argument<String>("package")!!))
                "appstoreInstallBundle" -> result.success(
                    appstore.installBundle(call.argument<String>("bundle")!!)
                )
                "appstoreClearBundle" -> result.success(appstore.clearBundle())
                "appstoreSetupChecklist" -> result.success(appstore.setupChecklist())
                "appstoreCanRequestInstalls" -> result.success(appstore.canRequestInstalls())
                "appstoreOpenInstallPermission" -> result.success(appstore.openInstallPermission())
                "appstoreCatalogUrl" -> result.success(appstore.catalogUrl())
                "appstoreSetCatalogUrl" -> result.success(
                    appstore.setCatalogUrl(call.argument<String>("url")!!)
                )

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
        // The system role dialog is the right answer when Stride is on screen: one tap, no
        // hunting through Settings. It is only available from an Activity, so fall back to the
        // Settings deep link when the request comes from anywhere else.
        if (MainActivity.current?.requestHomeRole() == true) return true
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

    private fun openSystemSettings(): Boolean {
        val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
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

    private fun hudHeightPx(): Int =
        if (OverlayService.isRunning) OverlayService.hudHeightPx else 0

    /**
     * Every edge the overlay currently occupies, in device pixels.
     *
     * Returned as one map rather than four channel calls so the four numbers Flutter lays out
     * against always describe the same instant. Fetching them separately would let a rebuild land
     * between two calls and inset the grid against a mix of the old and new chrome.
     */
    private fun hudInsetsPx(): Map<String, Int> {
        if (!OverlayService.isRunning) {
            return mapOf("top" to 0, "bottom" to 0, "left" to 0, "right" to 0)
        }
        return mapOf(
            "top" to OverlayService.hudTopPx,
            "bottom" to OverlayService.hudBottomPx + OverlayService.hudBottomExtraPx,
            "left" to OverlayService.hudLeftPx,
            "right" to OverlayService.hudRightPx,
        )
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
                if (isHiddenFromLauncher(pkg, context.packageName)) return@forEach
                val entry = out.getOrPut(pkg) {
                    mutableMapOf(
                        "package" to pkg,
                        "label" to ri.loadLabel(pm).toString(),
                        "activity" to ri.activityInfo.name,
                        "leanback" to false,
                        "hasMediaBrowserService" to false,
                        "removable" to isRemovable(pkg),
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

    private fun appIcon(call: MethodCall, result: MethodChannel.Result) {
        val pkg = call.argument<String>("package")
        if (pkg.isNullOrBlank()) {
            result.success(null)
            return
        }

        val sizePx = (call.argument<Int>("sizePx") ?: DEFAULT_ICON_SIZE_PX).coerceAtLeast(1)
        val cacheKey = "$pkg@$sizePx"
        val cached = iconCache.get(cacheKey)
        if (cached != null) {
            result.success(cached)
            return
        }

        try {
            iconExecutor.execute {
                val bytes = renderAppIcon(pkg, sizePx)
                if (bytes != null) iconCache.put(cacheKey, bytes)
                mainHandler.post {
                    result.success(bytes)
                }
            }
        } catch (_: Throwable) {
            result.success(null)
        }
    }

    private fun renderAppIcon(pkg: String, sizePx: Int): ByteArray? =
        try {
            drawableToPng(context.packageManager.getApplicationIcon(pkg), sizePx)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Throwable) {
            null
        }

    private fun drawableToPng(drawable: Drawable, sizePx: Int): ByteArray? {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            val oldBounds = Rect(drawable.bounds)
            try {
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
            } finally {
                drawable.setBounds(oldBounds)
            }
            ByteArrayOutputStream().use { stream ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    stream.toByteArray()
                } else {
                    null
                }
            }
        } finally {
            bitmap.recycle()
        }
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

    /**
     * Whether this package can be removed by the rider.
     *
     * System-image apps refuse `ACTION_DELETE` (or silently offer to remove updates only), so the
     * tile must not advertise a delete that the platform will reject. Stride itself is excluded for
     * the obvious reason: uninstalling the launcher from the launcher leaves the console with no
     * home screen.
     */
    private fun isRemovable(pkg: String): Boolean {
        if (pkg == context.packageName) return false
        return try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            info.flags and ApplicationInfo.FLAG_SYSTEM == 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Hand the package to the system uninstaller.
     *
     * `ACTION_DELETE` rather than `PackageInstaller.uninstall`: the system dialog is the
     * confirmation of record and needs no REQUEST_DELETE_PACKAGES grant. Stride asks the rider
     * first anyway, because this is a full-screen system activity and a rider mid-walk deserves to
     * know what is about to take over the screen.
     */
    private fun uninstallApp(pkg: String): Boolean {
        if (!isRemovable(pkg)) return false
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
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
        return WorkoutMediaCoupling.activeControllers(context)
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

    // ------------------------------------------------------------------ workout/state layer

    private fun workoutStart(): Boolean {
        if (WorkoutSession.state != WorkoutSession.State.IDLE) return false
        WorkoutSession.start()
        return true
    }

    private fun workoutPause(): Boolean {
        if (WorkoutSession.state != WorkoutSession.State.RUNNING) return false
        WorkoutSession.pause()
        return true
    }

    private fun workoutResume(): Boolean {
        if (WorkoutSession.state != WorkoutSession.State.PAUSED) return false
        WorkoutSession.resume()
        return true
    }

    private fun workoutStop(): Int = WorkoutSession.stop().toChannelInt()

    /**
     * Call off a start that has not been answered yet.
     *
     * Distinct from [workoutStop] in the way that matters to the rider: the goal they set survives.
     * A start the treadmill never acknowledged is not a workout they completed, so wiping their
     * target would punish them for the machine being slow.
     */
    private fun workoutCancelStart(): Boolean {
        if (WorkoutSession.state != WorkoutSession.State.STARTING) return false
        WorkoutSession.abandon()
        return true
    }

    private fun volumeSet(call: MethodCall): Boolean {
        val level = call.argument<Number>("level")?.toInt() ?: return false
        return systemAudio.setLevel(level)
    }

    // ------------------------------------------------------------- settings

    private fun settingsGet(): Map<String, Any?> {
        StrideSettings.attach(context)
        return mapOf(
            "trackFloor" to StrideSettings.trackFloor,
            "transport" to StrideSettings.transport.name.lowercase(Locale.US),
            // Reported separately from the choice itself. The rider can select the direct path;
            // that is not the same as it working, and the UI must be able to say so.
            "transportImplemented" to StrideSettings.transportImplemented,
        )
    }

    private fun transportSet(call: MethodCall): Boolean {
        StrideSettings.attach(context)
        val raw = call.argument<String>("transport") ?: return false
        // Reject an unknown value instead of silently falling back to GlassOS. A settings screen
        // that reports success while storing something else is worse than one that fails.
        val parsed = StrideSettings.Transport.entries
            .firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: return false
        StrideSettings.transport = parsed
        return true
    }

    private fun grantsGet(): List<Map<String, Any?>> =
        StridePermissions.all(context).map {
            mapOf(
                "id" to it.id,
                "label" to it.label,
                "granted" to it.granted,
                "consequence" to it.consequence,
            )
        }

    private fun machineSnapshot(): Map<String, Any?> = mapOf(
        "status" to MachineLink.status.name.lowercase(Locale.US),
        "reason" to MachineLink.reason,
        "speedMph" to MachineLink.speedMph,
        "inclinePercent" to MachineLink.inclinePercent,
        "distanceMiles" to MachineLink.distanceMiles,
        "paceMinPerMile" to MachineLink.paceMinPerMile,
        "calories" to MachineLink.calories,
        "elapsedSeconds" to MachineLink.elapsedSeconds,
        "consoleState" to MachineLink.consoleState,
        "beltMayBeMoving" to MachineLink.beltMayBeMoving,
        "fanLevel" to MachineLink.fanLevel,
        "canCommand" to MachineLink.canCommand(),
        // Resolved here, not in Dart. MachineLink owns every safety sentence and the rule for
        // choosing between them; a second copy of that rule in Dart is a second thing to get wrong.
        "metricsNotice" to MachineLink.metricsNotice,
    )

    // ------------------------------------------------------------------ goals

    private fun goalSet(call: MethodCall): Boolean {
        val kind = call.argument<String>("kind")?.lowercase(Locale.US) ?: return false
        val target = call.argument<Double>("target") ?: 0.0
        val applied = when (kind) {
            "none" -> { WorkoutGoal.clear(); true }
            // Rejecting a non-positive target here rather than storing it keeps the overlay from
            // ever rendering a goal that is already met the instant it is set.
            "time" -> if (target > 0) { WorkoutGoal.setTimeGoal((target * 1000).toLong()); true } else false
            "distance" -> if (target > 0) { WorkoutGoal.setDistanceGoal(target); true } else false
            else -> false
        }
        // The goal ring is a window, not a label, so the overlay has to be rebuilt for it to
        // appear or disappear at all.
        if (applied) OverlayService.refreshChrome()
        return applied
    }

    private fun goalGet(): Map<String, Any?> = mapOf(
        "kind" to WorkoutGoal.kind.name.lowercase(Locale.US),
        "target" to when (WorkoutGoal.kind) {
            WorkoutGoal.Kind.TIME -> WorkoutGoal.targetMs / 1000.0
            WorkoutGoal.Kind.DISTANCE -> WorkoutGoal.targetMiles
            else -> 0.0
        },
        // Null, not zero: "we cannot measure this yet" and "you have covered none of it" are
        // different facts, and the UI must be free to say so.
        "progress" to WorkoutGoal.progressFraction(),
        "remainingSeconds" to WorkoutGoal.remainingMs()?.let { it / 1000.0 },
        "remainingMiles" to WorkoutGoal.remainingMiles(),
        "etaSeconds" to WorkoutGoal.etaMs()?.let { it / 1000.0 },
        "label" to WorkoutGoal.targetLabel(),
    )

    // ------------------------------------------------------------------ now playing

    private fun nowPlaying(): Map<String, Any?>? {
        val snapshot = MediaNowPlaying.snapshot(context) ?: return null
        return mapOf(
            "package" to snapshot.packageName,
            "title" to snapshot.title,
            "artist" to snapshot.artist,
            "album" to snapshot.album,
            "isPlaying" to snapshot.isPlaying,
            "isVideo" to snapshot.isVideo,
            "durationMs" to snapshot.durationMs.toChannelInt(),
            "positionMs" to snapshot.positionMs.toChannelInt(),
            "canSkipNext" to snapshot.canSkipNext,
            "canSkipPrevious" to snapshot.canSkipPrevious,
        )
    }

    private fun nowPlayingArtwork(): ByteArray? {
        val art = MediaNowPlaying.artwork(context) ?: return null
        return ByteArrayOutputStream().use { out ->
            art.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    // ------------------------------------------------------------------ track floor

    private fun trackFloorGet(): Map<String, Any?> = mapOf(
        "on" to OverlayService.trackFloorOn(context),
        // Null means "nobody has chosen" — the UI shows the toggle following playback, not a
        // setting the rider picked, and that distinction is worth surfacing.
        "chosen" to OverlayService.trackFloorChosen,
        "videoPlaying" to MediaNowPlaying.videoIsPlaying(context),
    )

    private fun trackFloorSet(call: MethodCall): Boolean {
        OverlayService.setTrackFloor(call.argument<Boolean>("on"))
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

}
