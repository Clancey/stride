package io.stride.spikes

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.stride.spikes.appstore.AppstoreWorker
import io.stride.spikes.appstore.StrideAppstoreService

class MainActivity : FlutterActivity() {
    companion object {
        /**
         * True while Stride's own launcher is the thing on screen.
         *
         * The overlay uses this to stand down its decorative surfaces. The accessibility service
         * cannot answer this question — it deliberately ignores our own package — and the
         * Activity itself is the only component that reliably knows.
         */
        @Volatile
        var launcherForeground: Boolean = false
            private set

        /**
         * The live Activity, when there is one.
         *
         * Only an Activity can raise the system's "make this your home app?" dialog — the role
         * request is a no-op from an application context. The bridge holds an application context
         * by design, so it borrows this and falls back to a Settings deep link when Stride is not
         * on screen.
         */
        @Volatile
        var current: MainActivity? = null
            private set

        private const val REQUEST_HOME_ROLE = 4801
    }

    /**
     * Ask Android to make Stride the home app, using the dialog built for exactly this.
     *
     * The alternative — dropping the rider into the Settings app and hoping they find the right
     * row — is what this replaces. Returns false if the role is unavailable or already held, so
     * the caller can fall back rather than leave the button doing nothing.
     */
    fun requestHomeRole(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roles = getSystemService(RoleManager::class.java) ?: return false
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME)) return false
        if (roles.isRoleHeld(RoleManager.ROLE_HOME)) return false
        return try {
            startActivityForResult(
                roles.createRequestRoleIntent(RoleManager.ROLE_HOME),
                REQUEST_HOME_ROLE,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var channel: MethodChannel? = null
    private var workoutStateListener: ((WorkoutSession.State) -> Unit)? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Idempotent, and deliberately also done by OverlayService: either surface can be the one
        // on screen, and whichever comes up first should start reading the machine.
        StrideSettings.attach(applicationContext)
        MachineLink.attach(applicationContext)
        // The periodic update check is registered here as well as on boot: a console that is never
        // rebooted (the common case - it is plugged in) would otherwise only ever check once.
        // enqueueUniquePeriodicWork(KEEP) makes this idempotent.
        AppstoreWorker.ensureScheduled(applicationContext)
        // ...and check right now, if the last check is old enough to be worth repeating. The
        // periodic worker alone means a console that has just been power-cycled shows "No catalog
        // check has completed yet" until its first ~6h tick, which is exactly when someone is most
        // likely to be looking for an update. The staleness guard is what keeps this from becoming
        // a catalog fetch on every glance at the launcher.
        StrideAppstoreService.checkOnStart(applicationContext)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SpikeBridge.CHANNEL).also {
            it.setMethodCallHandler(SpikeBridge(applicationContext))
            registerWorkoutStateListener(it)
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        removeWorkoutStateListener()
        channel?.setMethodCallHandler(null)
        channel = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onResume() {
        super.onResume()
        current = this
        // Cheap, and the one moment we know the rider is looking at Stride: if a reinstall wiped a
        // grant, put it back now rather than waiting for them to discover a dead Back button.
        StridePermissions.repair(applicationContext)
        hideNavigationBar()
        launcherForeground = true
        OverlayService.refreshChrome()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Immersive mode is given up whenever the window loses focus — a dialog, the USB permission
        // prompt, the shade — so it has to be re-asserted rather than set once.
        if (hasFocus) hideNavigationBar()
    }

    /**
     * Hide Android's navigation bar while Stride's launcher is on screen.
     *
     * Stride supplies Back, Home and Recents itself, on a bottom bar sized for a treadmill console;
     * the system's own row underneath it is a second set of the same three controls, in a strip
     * small enough to be a nuisance at arm's length, occupying space the overlay had been laid out
     * to use.
     *
     * It was not there before, and the reason is worth writing down. This console never ran a setup
     * wizard, so `user_setup_complete` was 0 — and SystemUI hides the navigation bar in that state.
     * Stride was getting a full-screen launcher for free, as a side effect of a flag that also made
     * Android refuse to start *any* home activity ("Not going home because user setup is in
     * progress"). Repairing that flag is what makes the Home button work at all, and it is what
     * brought the bar back. So the bar is hidden deliberately here rather than by accident there.
     *
     * Only the navigation bar. The status bar was visible before and stays visible, because the
     * overlay's top strip is laid out beneath it and hiding it would move everything.
     */
    private fun hideNavigationBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.navigationBars())
                    // Swiping still reveals it transiently, so nothing becomes unreachable for
                    // someone who actually wants the system's own controls.
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        } catch (_: Exception) {
            // A launcher that cannot hide a system bar is still a working launcher.
        }
    }

    override fun onPause() {
        if (current === this) current = null
        launcherForeground = false
        OverlayService.refreshChrome()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isHomeRelaunch(intent)) return

        val activeChannel = channel ?: return
        // singleTask HOME launches reuse this Activity, so Dart must be told explicitly to unwind
        // to the launcher root. Post anyway: the channel reply path must stay on the UI thread.
        mainHandler.post {
            activeChannel.invokeMethod("onHomePressed", null)
        }
    }

    private fun isHomeRelaunch(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_MAIN) return false
        val categories = intent.categories
        return categories == null ||
            categories.contains(Intent.CATEGORY_HOME) ||
            categories.contains(Intent.CATEGORY_LAUNCHER)
    }

    private fun registerWorkoutStateListener(activeChannel: MethodChannel) {
        removeWorkoutStateListener()
        val listener: (WorkoutSession.State) -> Unit = { state ->
            mainHandler.post {
                if (channel === activeChannel) {
                    activeChannel.invokeMethod("onWorkoutStateChanged", state.channelName())
                }
            }
        }
        workoutStateListener = listener
        WorkoutSession.addListener(listener)
    }

    private fun removeWorkoutStateListener() {
        workoutStateListener?.let(WorkoutSession::removeListener)
        workoutStateListener = null
    }
}
