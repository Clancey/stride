package io.stride.spikes

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.stride.spikes.appstore.AppstoreWorker

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
        launcherForeground = true
        OverlayService.refreshChrome()
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
