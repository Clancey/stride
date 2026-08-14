package io.stride.spikes

import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var channel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SpikeBridge.CHANNEL).also {
            it.setMethodCallHandler(SpikeBridge(applicationContext))
        }
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
}
