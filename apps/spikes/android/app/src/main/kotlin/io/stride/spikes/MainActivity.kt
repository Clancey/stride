package io.stride.spikes

import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

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
        launcherForeground = true
        OverlayService.refreshChrome()
    }

    override fun onPause() {
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
