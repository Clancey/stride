package io.stride.spikes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * S3 + S10 spike: overlay windows and the edge-swipe gesture layer.
 *
 * Deliberately built from native Android views, not a second Flutter engine. S8 decides whether
 * Flutter is fast enough on this SoC for the always-visible strip; until then, native keeps the
 * spike honest about what the window mechanics can do.
 *
 * Three window types are used, because FLAG_NOT_TOUCHABLE is a whole-window flag - you cannot have
 * inert regions inside a single touchable window (this was a mistake in revision 1 of the plan):
 *
 *  1. HUD strip      - touchable, holds the always-reachable STOP control and nav buttons.
 *  2. Edge strips    - thin, touchable, capture the start of an edge swipe.
 *  3. Nav panel      - revealed by a completed edge swipe.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "io.stride.spikes.OVERLAY_START"
        const val ACTION_STOP = "io.stride.spikes.OVERLAY_STOP"

        private const val CHANNEL_ID = "stride_spikes_overlay"
        private const val NOTIFICATION_ID = 4321

        /** Minimum travel before an edge touch is treated as navigation rather than passed up. */
        private const val SWIPE_THRESHOLD_DP = 48f

        /** Width of the always-present touchable edge strips. */
        private const val EDGE_STRIP_WIDTH_DP = 20f

        /** Fraction of the screen height the edge strips span, centred vertically. */
        private const val EDGE_STRIP_SPAN = 0.5f

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Diagnostics for the spike harness. */
        @Volatile
        var lastGesture: String = "none"
            private set

        @Volatile
        var edgeTouchCount: Int = 0
            private set

        @Volatile
        var consumedGestureCount: Int = 0
            private set
    }

    private lateinit var windowManager: WindowManager
    private var hudView: View? = null
    private var navPanelView: View? = null
    private val edgeViews = mutableListOf<View>()

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> showOverlays()
        }
        // STICKY so S9 can observe whether the system restarts us under memory pressure.
        return START_STICKY
    }

    override fun onDestroy() {
        hideOverlays()
        isRunning = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stride overlay",
                NotificationManager.IMPORTANCE_MIN,
            )
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Stride spike overlay")
            .setContentText("Overlay and edge gestures active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    // ---------------------------------------------------------------- windows

    private fun showOverlays() {
        if (isRunning) return
        addHud()
        addEdgeStrip(Gravity.START)
        addEdgeStrip(Gravity.END)
        isRunning = true
    }

    private fun hideOverlays() {
        listOfNotNull(hudView, navPanelView).forEach { safeRemove(it) }
        edgeViews.forEach { safeRemove(it) }
        edgeViews.clear()
        hudView = null
        navPanelView = null
    }

    private fun safeRemove(v: View) {
        try {
            windowManager.removeView(v)
        } catch (_: IllegalArgumentException) {
            // Already detached.
        }
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    ).toInt()

    private fun baseParams(width: Int, height: Int, gravity: Int): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = gravity
        return params
    }

    /**
     * The always-visible strip. Carries the STOP control, which per plan section 5 must remain
     * reachable at all times and must never be occluded while the belt is moving.
     */
    private fun addHud() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(220, 12, 14, 18))
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            gravity = Gravity.CENTER_VERTICAL
        }

        val metrics = TextView(this).apply {
            text = "STRIDE SPIKE  ·  0.0 mph  ·  0.0 %"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(metrics)

        row.addView(navButton("BACK") {
            val svc = StrideAccessibilityService.instance
            lastGesture = if (svc == null) {
                "BACK failed: accessibility service not connected"
            } else if (svc.goBack()) "BACK ok" else "BACK rejected"
        })
        row.addView(navButton("HOME") {
            goHomeFromService()
            lastGesture = "HOME ok"
        })
        row.addView(navButton("RECENTS") {
            val svc = StrideAccessibilityService.instance
            lastGesture = if (svc == null) {
                "RECENTS failed: accessibility service not connected"
            } else if (svc.goRecents()) "RECENTS ok" else "RECENTS rejected"
        })
        row.addView(navButton("STOP") {
            lastGesture = "STOP pressed"
        }.apply { setBackgroundColor(Color.rgb(180, 30, 30)) })

        val params = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        windowManager.addView(row, params)
        hudView = row
    }

    private fun navButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(40, 44, 52))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.marginStart = dp(6f)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    private fun goHomeFromService() {
        // No accessibility needed: we are (or want to be) the home app.
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    /**
     * A thin, always-touchable strip at one edge.
     *
     * On ACTION_DOWN the window is grown to full screen so the rest of the drag can be tracked.
     * This is the standard third-party gesture-nav technique. The cost is real and is documented
     * in plan section 3.3: any touch that lands in the strip is stolen from the app underneath,
     * because a non-system app cannot re-inject a swallowed touch without INJECT_EVENTS.
     */
    private fun addEdgeStrip(gravity: Int) {
        val stripWidth = dp(EDGE_STRIP_WIDTH_DP)
        val screenHeight = resources.displayMetrics.heightPixels
        val stripHeight = (screenHeight * EDGE_STRIP_SPAN).toInt()

        val container = FrameLayout(this).apply {
            // Faintly visible during the spike so the interference cost is obvious to the tester.
            setBackgroundColor(Color.argb(40, 90, 200, 255))
        }

        val params = baseParams(stripWidth, stripHeight, gravity or Gravity.CENTER_VERTICAL)
        val fromStart = gravity == Gravity.START

        var downX = 0f
        var downY = 0f
        var expanded = false

        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    edgeTouchCount++
                    // Grow to full screen so the whole drag is tracked.
                    params.width = WindowManager.LayoutParams.MATCH_PARENT
                    params.height = WindowManager.LayoutParams.MATCH_PARENT
                    params.gravity = Gravity.TOP or Gravity.START
                    windowManager.updateViewLayout(view, params)
                    expanded = true
                    true
                }

                MotionEvent.ACTION_MOVE -> true

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val threshold = dp(SWIPE_THRESHOLD_DP)
                    val travelledInward = if (fromStart) dx > threshold else -dx > threshold
                    val mostlyHorizontal = abs(dx) > abs(dy)

                    if (travelledInward && mostlyHorizontal) {
                        consumedGestureCount++
                        lastGesture = "edge swipe from ${if (fromStart) "left" else "right"}"
                        toggleNavPanel()
                    } else {
                        lastGesture = "edge touch ignored (travel ${dx.toInt()}px, " +
                            "threshold ${threshold}px) - this touch was still stolen " +
                            "from the app underneath"
                    }

                    if (expanded) {
                        params.width = stripWidth
                        params.height = stripHeight
                        params.gravity = gravity or Gravity.CENTER_VERTICAL
                        windowManager.updateViewLayout(view, params)
                        expanded = false
                    }
                    true
                }

                else -> false
            }
        }

        windowManager.addView(container, params)
        edgeViews.add(container)
    }

    private fun toggleNavPanel() {
        navPanelView?.let {
            safeRemove(it)
            navPanelView = null
            return
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 18, 20, 26))
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        panel.addView(TextView(this).apply {
            text = "Stride navigation"
            setTextColor(Color.WHITE)
            textSize = 16f
        })
        panel.addView(navButton("BACK") {
            StrideAccessibilityService.instance?.goBack()
            toggleNavPanel()
        })
        panel.addView(navButton("HOME") {
            goHomeFromService()
            toggleNavPanel()
        })
        panel.addView(navButton("RECENTS") {
            StrideAccessibilityService.instance?.goRecents()
            toggleNavPanel()
        })
        panel.addView(navButton("CLOSE") { toggleNavPanel() })

        val params = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        windowManager.addView(panel, params)
        navPanelView = panel
    }
}
