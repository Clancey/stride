package io.stride.spikes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
 *  1. HUD strip      - touchable, holds nav buttons and an unambiguously inert diagnostic banner.
 *  2. Edge strips    - thin, touchable, capture the start of an edge swipe.
 *  3. Nav panel      - revealed by a completed edge swipe.
 *
 * SAFETY (plan section 5): this harness has no motor-control path and must never imply it does.
 * The HUD therefore shows no telemetry value and carries no control labelled "STOP". A person
 * standing on a moving belt must not be able to mistake this diagnostic surface for a working
 * control, so the belt state is stated as unknown and the physical safety key is named as the only
 * stop.
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

        /** Non-zero first-frame inset until the HUD reports its real laid-out height. */
        private const val HUD_HEIGHT_ESTIMATE_DP = 89f

        /** Fraction of the screen height the edge strips span, centred vertically. */
        private const val EDGE_STRIP_SPAN = 0.5f

        /**
         * Gesture-tracking strategy toggle for S3, so both approaches can be measured on the real
         * device.
         *
         * false (default, safer): do NOT resize the strip on ACTION_DOWN. Android delivers the whole
         * pointer stream (MOVE/UP, even outside the window bounds) to the window that received
         * ACTION_DOWN, so raw coordinates alone are enough to track the drag. Nothing ever grows to
         * full screen, so there is no way to strand a fullscreen window that swallows every touch on
         * a device with no Home button.
         *
         * true: grow the strip to full screen on ACTION_DOWN (the classic third-party gesture-nav
         * trick). Kept behind this flag purely to A/B it on hardware; the expansion path is made
         * crash-safe below so a failure can never leave a fullscreen window stranded.
         */
        private const val RESIZE_ON_DOWN = false

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Last measured HUD height. The bridge uses this to inset Flutter below the overlay. */
        @Volatile
        var hudHeightPx: Int = 0
            private set

        /** Diagnostics for the spike harness. */
        @Volatile
        var lastGesture: String = "none"
            private set

        /**
         * Separated interference counters (plan section 3.3, "the unavoidable cost").
         *
         * The old single edgeTouchCount conflated three very different things. They are split so the
         * tester can tell intentional navigation apart from genuinely stolen input:
         */

        /** Every ACTION_DOWN that landed in an edge strip. Each one is a touch taken from the app. */
        @Volatile
        var edgeTouchCount: Int = 0
            private set

        /** Touches that became a real navigation swipe. Intentional; the strip did its job. */
        @Volatile
        var navGestureCount: Int = 0
            private set

        /**
         * Touches that entered a strip but never navigated (short taps, tiny drags). This is the
         * pure-interference number: input stolen from the app underneath for no benefit, and it
         * cannot be re-injected without INJECT_EVENTS.
         */
        @Volatile
        var stolenTouchCount: Int = 0
            private set

        /** Gestures the system cancelled (ACTION_CANCEL). Cleanup only; never a completed swipe. */
        @Volatile
        var cancelledGestureCount: Int = 0
            private set

        /** Foreground package at the moment of the last edge touch, for per-app attribution. */
        @Volatile
        var lastTouchForegroundPackage: String? = null
            private set

        fun resetCounters() {
            edgeTouchCount = 0
            navGestureCount = 0
            stolenTouchCount = 0
            cancelledGestureCount = 0
            lastTouchForegroundPackage = null
            lastGesture = "counters reset"
        }
    }

    private lateinit var windowManager: WindowManager
    private var hudView: View? = null
    private var navPanelView: View? = null
    private var navPanelFromStart: Boolean = true
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
            .setContentText("Diagnostic overlay - no telemetry, no motor control")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    // ---------------------------------------------------------------- windows

    private fun showOverlays() {
        if (isRunning) return
        // Order matters for safety: edge strips are added first, the HUD last, so the HUD is the
        // topmost window in this app's overlay z-order. An edge strip that grows to full screen
        // (RESIZE_ON_DOWN) can therefore never sit above and intercept the HUD, and the nav buttons
        // stay reachable (plan section 5, hazard "navigation/gesture hides the stop control").
        addEdgeStrip(Gravity.START)
        addEdgeStrip(Gravity.END)
        addHud()
        isRunning = true
    }

    private fun hideOverlays() {
        listOfNotNull(hudView, navPanelView).forEach { safeRemove(it) }
        edgeViews.forEach { safeRemove(it) }
        edgeViews.clear()
        hudView = null
        navPanelView = null
        hudHeightPx = 0
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

    private fun roundedRect(color: Int, radius: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) {
                setStroke(dp(1f), strokeColor)
            }
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun rippleRounded(color: Int, radius: Float, strokeColor: Int? = null): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.argb(90, 255, 255, 255)),
            roundedRect(color, radius, strokeColor),
            roundedRect(Color.WHITE, radius),
        )

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

    private fun updateHudHeight(view: View) {
        val measuredHeight = view.height
        if (measuredHeight > 0) {
            hudHeightPx = measuredHeight
        }
    }

    /**
     * The always-visible strip.
     *
     * SAFETY (plan section 5): this is an inert diagnostic surface. It carries NO telemetry value
     * (showing "0.0 mph" would imply the belt is stopped) and NO control labelled "STOP" (a button
     * that does not actually stop the belt is worse than none on a machine that can injure someone).
     * The calm chrome treatment is deliberate product UI, but the copy remains explicit: navigation
     * only, no motor control, and the physical safety key is the real stop.
     */
    private fun addHud() {
        // The bridge needs an inset immediately, before the first layout pass completes.
        hudHeightPx = dp(HUD_HEIGHT_ESTIMATE_DP)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Color.argb(238, 6, 10, 15), 0f)
            addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
                val laidOutHeight = bottom - top
                if (laidOutHeight > 0) {
                    hudHeightPx = laidOutHeight
                } else {
                    updateHudHeight(view)
                }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(88f)
            setPadding(dp(20f), dp(8f), dp(18f), dp(8f))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(88f),
            )
        }

        val statusBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLine.addView(TextView(this).apply {
            text = "Stride overlay"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        })
        statusLine.addView(View(this).apply {
            background = oval(Color.rgb(255, 190, 92))
            val lp = LinearLayout.LayoutParams(dp(10f), dp(10f))
            lp.marginStart = dp(18f)
            lp.marginEnd = dp(8f)
            layoutParams = lp
        })
        statusLine.addView(TextView(this).apply {
            text = "Not connected"
            setTextColor(Color.rgb(255, 232, 198))
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        })
        statusBlock.addView(statusLine)
        statusBlock.addView(TextView(this).apply {
            text = "Navigation only — no telemetry, no motor control. Use the safety key to stop the treadmill."
            setTextColor(Color.rgb(216, 226, 239))
            textSize = 14f
            includeFontPadding = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(6f)
            layoutParams = lp
        })
        row.addView(statusBlock)

        row.addView(hudNavButton("Back", "‹") {
            val svc = StrideAccessibilityService.instance
            lastGesture = if (svc == null) {
                "BACK failed: accessibility service not connected"
            } else if (svc.goBack()) "BACK ok" else "BACK rejected"
        })
        row.addView(hudNavButton("Home", "⌂") {
            goHomeFromService()
            lastGesture = "HOME ok"
        })
        row.addView(hudNavButton("Recents", "▣") {
            val svc = StrideAccessibilityService.instance
            lastGesture = if (svc == null) {
                "RECENTS failed: accessibility service not connected"
            } else if (svc.goRecents()) "RECENTS ok" else "RECENTS rejected"
        })

        root.addView(row)
        root.addView(View(this).apply {
            setBackgroundColor(Color.argb(150, 118, 140, 170))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1f),
            )
        })

        val params = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        try {
            windowManager.addView(root, params)
            hudView = root
            root.post { updateHudHeight(root) }
        } catch (_: Exception) {
            // If SYSTEM_ALERT_WINDOW is not granted, addView throws. Fail soft rather than crash the
            // service; overlayStatus/canDrawOverlays already surfaces the permission problem.
            hudView = null
            hudHeightPx = 0
        }
    }

    private fun hudNavButton(label: String, icon: String, onClick: () -> Unit): View =
        navSurfaceButton(
            label = label,
            icon = icon,
            subtitle = null,
            width = dp(108f),
            height = dp(72f),
            compact = true,
            onClick = onClick,
        ).apply {
            val lp = LinearLayout.LayoutParams(dp(108f), dp(72f))
            lp.marginStart = dp(12f)
            layoutParams = lp
        }

    private fun panelNavButton(label: String, icon: String, subtitle: String, onClick: () -> Unit): View =
        navSurfaceButton(
            label = label,
            icon = icon,
            subtitle = subtitle,
            width = LinearLayout.LayoutParams.MATCH_PARENT,
            height = dp(84f),
            compact = false,
            onClick = onClick,
        ).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(84f),
            )
            lp.topMargin = dp(12f)
            layoutParams = lp
        }

    private fun navSurfaceButton(
        label: String,
        icon: String,
        subtitle: String?,
        width: Int,
        height: Int,
        compact: Boolean,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumWidth = if (width > 0) width else dp(72f)
        minimumHeight = dp(72f)
        isClickable = true
        isFocusable = true
        contentDescription = label
        background = rippleRounded(
            color = Color.rgb(18, 25, 34),
            radius = 18f,
            strokeColor = Color.argb(120, 126, 148, 176),
        )
        elevation = dp(if (compact) 2f else 4f).toFloat()
        setPadding(dp(if (compact) 8f else 18f), dp(8f), dp(if (compact) 8f else 18f), dp(8f))
        val lp = LinearLayout.LayoutParams(
            width,
            height,
        )
        layoutParams = lp
        addView(TextView(context).apply {
            text = icon
            setTextColor(Color.WHITE)
            textSize = if (compact) 27f else 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                if (compact) LinearLayout.LayoutParams.MATCH_PARENT else dp(44f),
                if (compact) dp(30f) else LinearLayout.LayoutParams.MATCH_PARENT,
            )
        })
        val labelBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                if (compact) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (compact) 0f else 1f,
            )
        }
        labelBlock.addView(TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (compact) 14f else 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = if (compact) Gravity.CENTER else Gravity.START
            includeFontPadding = false
        })
        if (subtitle != null) {
            labelBlock.addView(TextView(context).apply {
                text = subtitle
                setTextColor(Color.rgb(202, 216, 234))
                textSize = 14f
                includeFontPadding = false
                val subtitleLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                subtitleLp.topMargin = dp(4f)
                layoutParams = subtitleLp
            })
        }
        addView(labelBlock)
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
     * Raw screen X for a pointer index, safe on API 26-28.
     *
     * [MotionEvent.getRawX] with a pointer-index argument is API 29+. The console is API 26-28, where
     * calling it throws NoSuchMethodError and kills OverlayService outright. That is not a cosmetic
     * bug: on a machine with no physical Home or Back button the overlay is the only navigation, so
     * the crash strands the user in whatever app is foregrounded. Derive the raw coordinate from the
     * pointer-0 raw/local delta instead, which is exact because every pointer in one MotionEvent
     * shares the same window-to-screen offset.
     */
    private fun rawXCompat(event: MotionEvent, pointerIndex: Int): Float =
        if (pointerIndex == 0) event.rawX
        else event.getX(pointerIndex) + (event.rawX - event.getX(0))

    /** Raw screen Y for a pointer index, safe on API 26-28. See [rawXCompat]. */
    private fun rawYCompat(event: MotionEvent, pointerIndex: Int): Float =
        if (pointerIndex == 0) event.rawY
        else event.getY(pointerIndex) + (event.rawY - event.getY(0))

    /**
     * A thin, always-touchable strip at one edge.
     *
     * Gesture tracking is bound to a single pointer (the one that started the gesture). The cost is
     * real and is documented in plan section 3.3: any touch that lands in the strip is stolen from
     * the app underneath, because a non-system app cannot re-inject a swallowed touch without
     * INJECT_EVENTS.
     */
    private fun addEdgeStrip(gravity: Int) {
        val stripWidth = dp(EDGE_STRIP_WIDTH_DP)
        val screenHeight = resources.displayMetrics.heightPixels
        val stripHeight = (screenHeight * EDGE_STRIP_SPAN).toInt()

        val container = FrameLayout(this).apply {
            // Faintly visible during the spike so the interference cost is obvious to the tester.
            setBackgroundColor(Color.argb(26, 90, 176, 255))
        }

        val params = baseParams(stripWidth, stripHeight, gravity or Gravity.CENTER_VERTICAL)
        val fromStart = gravity == Gravity.START

        var activePointerId = MotionEvent.INVALID_POINTER_ID
        var downX = 0f
        var downY = 0f
        var expanded = false

        fun restoreStrip(view: View) {
            if (!expanded) return
            params.width = stripWidth
            params.height = stripHeight
            params.gravity = gravity or Gravity.CENTER_VERTICAL
            expanded = false
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
                // Cleanup path: if we cannot shrink the window back, it would be stranded at full
                // screen and swallow every touch on a device with no Home button. Recreate the strip
                // from scratch so the display is always recoverable.
                recreateEdgeStrip(view, gravity)
            }
        }

        fun expandToFullScreen(view: View) {
            val savedWidth = params.width
            val savedHeight = params.height
            val savedGravity = params.gravity
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.TOP or Gravity.START
            try {
                windowManager.updateViewLayout(view, params)
                expanded = true
            } catch (_: Exception) {
                // Expansion failed: restore the strip geometry immediately so we never leave a
                // half-applied fullscreen layout behind.
                params.width = savedWidth
                params.height = savedHeight
                params.gravity = savedGravity
                expanded = false
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                    recreateEdgeStrip(view, gravity)
                }
            }
        }

        fun evaluateGesture(rawX: Float, rawY: Float) {
            val dx = rawX - downX
            val dy = rawY - downY
            val threshold = dp(SWIPE_THRESHOLD_DP)
            val travelledInward = if (fromStart) dx > threshold else -dx > threshold
            val mostlyHorizontal = abs(dx) > abs(dy)

            if (travelledInward && mostlyHorizontal) {
                navGestureCount++
                lastGesture = "edge swipe from ${if (fromStart) "left" else "right"} " +
                    "(fg=${lastTouchForegroundPackage ?: "?"})"
                navPanelFromStart = fromStart
                toggleNavPanel()
            } else {
                stolenTouchCount++
                lastGesture = "edge touch stolen from app (travel ${dx.toInt()}px < " +
                    "threshold ${threshold}px, fg=${lastTouchForegroundPackage ?: "?"})"
            }
        }

        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                        // Already tracking a gesture; ignore a stray new primary down.
                        return@setOnTouchListener true
                    }
                    activePointerId = event.getPointerId(0)
                    downX = event.rawX
                    downY = event.rawY
                    edgeTouchCount++
                    lastTouchForegroundPackage = StrideAccessibilityService.foregroundPackage
                    if (RESIZE_ON_DOWN) expandToFullScreen(view)
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Multi-touch: reject the extra pointer. The gesture stays bound to the first
                    // pointer so a second finger cannot corrupt the tracked start/end coordinates.
                    true
                }

                MotionEvent.ACTION_MOVE -> true

                MotionEvent.ACTION_POINTER_UP -> {
                    // If the pointer that started the gesture is the one lifting, we can no longer
                    // trust the remaining pointers - cancel the gesture as cleanup, do not complete.
                    val liftedId = event.getPointerId(event.actionIndex)
                    if (liftedId == activePointerId) {
                        cancelledGestureCount++
                        lastGesture = "edge gesture abandoned (active pointer lifted mid multi-touch)"
                        restoreStrip(view)
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val ours = activePointerId != MotionEvent.INVALID_POINTER_ID &&
                        event.findPointerIndex(activePointerId) != -1
                    if (ours) {
                        val idx = event.findPointerIndex(activePointerId)
                        evaluateGesture(rawXCompat(event, idx), rawYCompat(event, idx))
                    }
                    restoreStrip(view)
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    // Cleanup only. A cancelled stream is NOT a completed gesture and must never open
                    // the nav panel.
                    cancelledGestureCount++
                    lastGesture = "edge gesture cancelled by system"
                    restoreStrip(view)
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            edgeViews.add(container)
        } catch (_: Exception) {
            // Missing SYSTEM_ALERT_WINDOW etc. - fail soft, do not crash the service.
        }
    }

    /**
     * Remove a possibly-stranded edge view and rebuild a fresh strip in its place. This is the
     * last-resort recovery so a failed resize can never leave a fullscreen window intercepting every
     * touch on a console with no Home button.
     */
    private fun recreateEdgeStrip(old: View, gravity: Int) {
        safeRemove(old)
        edgeViews.remove(old)
        addEdgeStrip(gravity)
    }

    private fun toggleNavPanel() {
        if (navPanelView != null) {
            hideNavPanel()
            return
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Color.argb(246, 10, 16, 23), 28f, Color.argb(150, 120, 146, 176))
            elevation = dp(14f).toFloat()
            setPadding(dp(22f), dp(22f), dp(22f), dp(22f))
        }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "System navigation"
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = "Overlay controls only. Machine state is not connected."
                setTextColor(Color.rgb(206, 220, 238))
                textSize = 14f
                includeFontPadding = false
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = dp(8f)
                layoutParams = lp
            })
        })
        panel.addView(panelNavButton("Back", "‹", "Return to the previous screen") {
            val svc = StrideAccessibilityService.instance
            lastGesture = if (svc == null) {
                "BACK failed: accessibility service not connected"
            } else if (svc.goBack()) "BACK ok" else "BACK rejected"
            hideNavPanel()
        })
        panel.addView(panelNavButton("Home", "⌂", "Open the Stride launcher") {
            goHomeFromService()
            lastGesture = "HOME ok"
            hideNavPanel()
        })
        panel.addView(panelNavButton("Recents", "▣", "Show Android recent apps") {
            val svc = StrideAccessibilityService.instance
            lastGesture = if (svc == null) {
                "RECENTS failed: accessibility service not connected"
            } else if (svc.goRecents()) "RECENTS ok" else "RECENTS rejected"
            hideNavPanel()
        })
        panel.addView(panelNavButton("Close panel", "×", "Hide these navigation controls") {
            hideNavPanel()
        })

        val params = baseParams(
            dp(408f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            (if (navPanelFromStart) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL,
        )
        params.x = dp(28f)
        panel.alpha = 0f
        panel.translationX = if (navPanelFromStart) -dp(48f).toFloat() else dp(48f).toFloat()
        try {
            windowManager.addView(panel, params)
            navPanelView = panel
            panel.post {
                panel.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        } catch (_: Exception) {
            navPanelView = null
        }
    }

    private fun hideNavPanel() {
        val panel = navPanelView ?: return
        navPanelView = null
        val exitX = if (navPanelFromStart) -dp(40f).toFloat() else dp(40f).toFloat()
        panel.animate()
            .alpha(0f)
            .translationX(exitX)
            .setDuration(150L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { safeRemove(panel) }
            .start()
    }
}
