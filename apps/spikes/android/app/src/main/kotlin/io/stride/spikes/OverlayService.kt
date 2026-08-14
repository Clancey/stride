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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
 *  1. Edge rails      - separate touchable windows at the left, right, top, and bottom edges.
 *  2. Edge strips     - thin, touchable, capture the start of an edge swipe.
 *  3. Handle tab      - the always-present bottom-center recovery affordance.
 *
 * THESIS: Stride is an edge-only workout console that refuses to cover the media surface or imply
 * treadmill authority. OWN-WORLD: near-black rounded slabs, amber incline language, cyan speed
 * language, and blunt locked states. STORY: the runner sees one honest timer, unknown machine
 * metrics, working media volume, and clear instructions to use the console/safety key for the belt.
 * FIRST VIEWPORT: a top floating metrics pill, left incline rail, right speed rail, bottom action bar,
 * and no center window at all. FORM: iFit's edge information architecture translated into Stride's
 * safety-first glass console.
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

        /** Non-zero first-frame top inset until the metrics pill reports its real laid-out height. */
        private const val HUD_TOP_ESTIMATE_DP = 126f

        /** Non-zero first-frame bottom inset until the bottom bar reports its real laid-out height. */
        private const val HUD_BOTTOM_ESTIMATE_DP = 112f

        private const val HANDLE_WIDTH_DP = 48f
        private const val HANDLE_HEIGHT_DP = 88f

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

        /** Last measured top edge chrome height. The bridge uses this to inset Flutter independently. */
        @Volatile
        var hudTopPx: Int = 0
            private set

        /** Last measured bottom edge chrome height. The bridge uses this to inset Flutter independently. */
        @Volatile
        var hudBottomPx: Int = 0
            private set

        /** Compatibility for older Flutter/bridge lanes while they migrate to hudTopPx/hudBottomPx. */
        @Volatile
        var hudHeightPx: Int = 0
            private set

        /**
         * Last measured width of the incline rail down the left edge.
         *
         * Reported separately from the top and bottom insets because the rails are what actually
         * cover the launcher's app grid, and a launcher that lays out tiles underneath an opaque
         * rail hides the very thing it exists to show.
         */
        @Volatile
        var hudLeftPx: Int = 0
            private set

        /** Last measured width of the speed rail down the right edge. */
        @Volatile
        var hudRightPx: Int = 0
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
    private lateinit var systemAudio: SystemAudio
    private val mainHandler = Handler(Looper.getMainLooper())
    private var topMetricsView: View? = null
    private var leftInclineView: View? = null
    private var rightSpeedView: View? = null
    private var bottomBarView: View? = null
    private var handleView: TextView? = null
    private val edgeViews = mutableListOf<View>()
    private var chromeVisible: Boolean = true
    private var metricsVisible: Boolean = true
    private var elapsedHeroView: TextView? = null
    private var bottomStateView: TextView? = null
    private var primaryTransportButton: TextView? = null
    private var endTransportButton: TextView? = null
    private var volumeValueView: TextView? = null

    private val amber = Color.rgb(255, 178, 55)
    private val amberMuted = Color.rgb(255, 222, 171)
    private val cyan = Color.rgb(40, 199, 255)
    private val cyanMuted = Color.rgb(190, 234, 255)

    private val workoutListener: (WorkoutSession.State) -> Unit = {
        mainHandler.post {
            updateWorkoutUi()
            scheduleElapsedTicker()
        }
    }

    private val elapsedTicker = object : Runnable {
        override fun run() {
            updateElapsedDisplays()
            if (WorkoutSession.state == WorkoutSession.State.RUNNING) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    /**
     * A readout whose value comes from the machine, with the closure that re-reads it.
     *
     * These refresh on their own clock, not on the workout timer's, because the belt's speed is a
     * fact about the machine and not about whether Stride's own session happens to be running. A
     * readout that only updated while our timer ran would freeze at the moment someone pauses
     * Stride and keeps walking.
     *
     * The sizes and colours are carried per readout rather than assumed, because the top pills and
     * the side rails style themselves differently, and a refresh that imposed one style on the
     * other would quietly redesign the overlay a second after it appeared.
     */
    private data class MachineCell(
        val root: View,
        val unit: String,
        val valueColor: Int,
        val blankColor: Int,
        val valueSize: Float,
        val blankSize: Float,
        val read: () -> String,
    )

    private val machineCells = mutableListOf<MachineCell>()
    private var machineNoticeView: TextView? = null

    private val machineTicker = object : Runnable {
        override fun run() {
            updateMachineMetrics()
            // Re-arm while any machine-backed readout is on screen. Keying this to the top strip
            // alone would freeze the side rails whenever the metrics were toggled off.
            if (machineCells.isNotEmpty()) mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun updateMachineMetrics() {
        machineCells.forEach { entry ->
            val view = entry.root.findViewWithTag<TextView>("value") ?: return@forEach
            val value = entry.read()
            val blank = value == MachineLink.NO_READING
            view.text = if (entry.unit.isEmpty() || blank) value else "$value ${entry.unit}"
            // "Not measured" is a long string and must never be styled like a confident reading.
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (blank) entry.blankSize else entry.valueSize)
            view.setTextColor(if (blank) entry.blankColor else entry.valueColor)
        }
        machineNoticeView?.text = MachineLink.metricsNotice
    }

    /** Register a top metric pill for live refresh. */
    private fun trackPill(cell: View, unit: String, accent: Int, read: () -> String) {
        machineCells += MachineCell(
            root = cell,
            unit = unit,
            valueColor = accent,
            blankColor = Color.rgb(218, 226, 235),
            valueSize = 30f,
            blankSize = 18f,
            read = read,
        )
    }

    /** Register a side rail's headline readout for live refresh. Rails draw their unit separately. */
    private fun trackRail(rail: View?, read: () -> String) {
        val root = rail ?: return
        machineCells += MachineCell(
            root = root,
            unit = "",
            valueColor = Color.WHITE,
            blankColor = Color.WHITE,
            valueSize = 30f,
            blankSize = 16f,
            read = read,
        )
    }

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
        systemAudio = SystemAudio(this)
        WorkoutSession.addListener(workoutListener)
        // Start reading the machine here rather than in the Activity: the overlay outlives the
        // launcher UI, and the metrics on it are exactly what someone mid-run is looking at.
        MachineLink.attach(this)
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
        WorkoutSession.removeListener(workoutListener)
        mainHandler.removeCallbacks(elapsedTicker)
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
        // Edge strips are independent from the rails, so swipe recovery still works when all visible
        // chrome is hidden and no center overlay window exists.
        addEdgeStrip(Gravity.START)
        addEdgeStrip(Gravity.END)
        isRunning = true
        chromeVisible = true
        metricsVisible = true
        rebuildChromeViews()
        addHandle()
        updateWorkoutUi()
        scheduleElapsedTicker()
    }

    private fun hideOverlays() {
        removeChromeViews()
        handleView?.let { safeRemove(it) }
        handleView = null
        edgeViews.forEach { safeRemove(it) }
        edgeViews.clear()
        elapsedHeroView = null
        bottomStateView = null
        primaryTransportButton = null
        endTransportButton = null
        volumeValueView = null
        publishTopInset(0)
        publishBottomInset(0)
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

    private fun textView(
        text: String,
        sizeSp: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false,
        gravity: Int = Gravity.START,
    ): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = sizeSp
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.gravity = gravity
        includeFontPadding = false
    }

    private fun stateLabel(): String = when (WorkoutSession.state) {
        WorkoutSession.State.IDLE -> "Ready"
        WorkoutSession.State.RUNNING -> "Running"
        WorkoutSession.State.PAUSED -> "Timer paused"
    }

    private fun scheduleElapsedTicker() {
        mainHandler.removeCallbacks(elapsedTicker)
        updateElapsedDisplays()
        if (WorkoutSession.state == WorkoutSession.State.RUNNING) {
            mainHandler.postDelayed(elapsedTicker, 1000L)
        }
    }

    private fun updateElapsedDisplays() {
        val elapsed = WorkoutSession.formatElapsed(WorkoutSession.elapsedMs())
        elapsedHeroView?.text = elapsed
    }

    private fun updateWorkoutUi() {
        bottomStateView?.text = stateLabel()
        updateElapsedDisplays()
        updateTransportButtons()
        updateVolumeViews()
    }

    private fun updateTransportButtons() {
        val primary = primaryTransportButton ?: return
        when (WorkoutSession.state) {
            WorkoutSession.State.IDLE -> {
                configureActionText(
                    primary,
                    label = "Start timer",
                    primary = true,
                    enabled = true,
                ) {
                    WorkoutSession.start()
                    lastGesture = "timer started"
                }
                configureActionText(
                    endTransportButton,
                    label = "End workout",
                    primary = false,
                    enabled = false,
                ) {}
            }

            WorkoutSession.State.RUNNING -> {
                configureActionText(
                    primary,
                    label = "Pause timer",
                    primary = true,
                    enabled = true,
                ) {
                    WorkoutSession.pause()
                    lastGesture = "timer paused"
                }
                configureActionText(
                    endTransportButton,
                    label = "End workout",
                    primary = false,
                    enabled = true,
                ) {
                    WorkoutSession.stop()
                    lastGesture = "workout ended"
                }
            }

            WorkoutSession.State.PAUSED -> {
                configureActionText(
                    primary,
                    label = "Resume timer",
                    primary = true,
                    enabled = true,
                ) {
                    WorkoutSession.resume()
                    lastGesture = "timer resumed"
                }
                configureActionText(
                    endTransportButton,
                    label = "End workout",
                    primary = false,
                    enabled = true,
                ) {
                    WorkoutSession.stop()
                    lastGesture = "workout ended"
                }
            }
        }
    }

    private fun configureActionText(
        button: TextView?,
        label: String,
        primary: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        button ?: return
        button.text = label
        button.isEnabled = enabled
        button.isClickable = enabled
        button.alpha = if (enabled) 1f else 0.5f
        button.setTextColor(if (enabled) Color.WHITE else Color.rgb(150, 161, 178))
        button.background = rippleRounded(
            color = when {
                !enabled -> Color.rgb(30, 36, 45)
                primary -> Color.rgb(20, 109, 255)
                else -> Color.rgb(48, 58, 74)
            },
            radius = 24f,
            strokeColor = if (enabled) Color.argb(180, 178, 211, 255) else Color.argb(120, 93, 105, 124),
        )
        button.setOnClickListener(if (enabled) View.OnClickListener { onClick() } else null)
    }

    private fun updateVolumeViews() {
        volumeValueView?.text = volumeText()
    }

    private fun changeVolume(delta: Int) {
        val snapshot = systemAudio.snapshot()
        val current = snapshot["level"] ?: 0
        systemAudio.setLevel(current + delta)
        updateVolumeViews()
        lastGesture = "media volume ${if (delta > 0) "up" else "down"}"
    }

    private fun volumeText(): String {
        val snapshot = systemAudio.snapshot()
        val level = snapshot["level"] ?: 0
        val max = snapshot["max"] ?: 0
        return String.format(Locale.US, "%d / %d", level, max)
    }

    private fun showMachineControlUnavailable() {
        lastGesture = "machine command blocked: disconnected"
        Toast.makeText(this, MachineLink.CONTROL_LOCKED_NOTICE, Toast.LENGTH_SHORT).show()
    }

    private fun distanceText(): String =
        MachineLink.distanceMiles?.let { String.format(Locale.US, "%.2f", it) }
            ?: MachineLink.NO_READING

    private fun paceText(): String =
        MachineLink.paceMinPerMile?.let { pace ->
            val minutes = pace.toInt()
            val seconds = ((pace - minutes) * 60.0).roundToInt()
            String.format(Locale.US, "%d:%02d", minutes + seconds / 60, seconds % 60)
        } ?: MachineLink.NO_READING

    private fun speedText(): String =
        MachineLink.speedMph?.let { String.format(Locale.US, "%.1f", it) }
            ?: MachineLink.NO_READING

    private fun inclineText(): String =
        MachineLink.inclinePercent?.let { String.format(Locale.US, "%.1f", it) }
            ?: MachineLink.NO_READING

    private fun caloriesText(): String =
        MachineLink.calories?.let { String.format(Locale.US, "%.0f", it) }
            ?: MachineLink.NO_READING

    private fun baseParams(width: Int, height: Int, gravity: Int): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = gravity
        return params
    }

    private fun publishTopInset(value: Int) {
        hudTopPx = value
        hudHeightPx = value
        repositionRails()
    }

    private fun publishBottomInset(value: Int) {
        hudBottomPx = value
        repositionRails()
    }

    private fun publishSideInset(left: Boolean, value: Int) {
        if (left) hudLeftPx = value else hudRightPx = value
    }

    private fun publishInsetFromLayout(view: View, top: Boolean) {
        val measured = view.height
        if (measured > 0) {
            if (top) publishTopInset(measured) else publishBottomInset(measured)
        }
    }

    private fun showChrome() {
        if (chromeVisible) return
        chromeVisible = true
        rebuildChromeViews()
        updateHandle()
        lastGesture = "overlay chrome shown"
    }

    private fun hideChrome() {
        if (!chromeVisible) return
        chromeVisible = false
        removeChromeViews()
        publishTopInset(0)
        publishBottomInset(0)
        updateHandle()
        lastGesture = "overlay chrome hidden"
    }

    private fun rebuildChromeViews() {
        removeChromeViews()
        if (!chromeVisible) return
        if (metricsVisible) addTopMetrics()
        else addCollapsedMetricsToggle()
        addInclineRail()
        addSpeedRail()
        addBottomBar()
        updateWorkoutUi()
        scheduleElapsedTicker()
        // Restart the machine ticker against the freshly built views. removeCallbacks first so a
        // rebuild cannot leave two tickers running and double the poll rate.
        mainHandler.removeCallbacks(machineTicker)
        mainHandler.post(machineTicker)
    }

    private fun removeChromeViews() {
        listOfNotNull(topMetricsView, leftInclineView, rightSpeedView, bottomBarView).forEach { safeRemove(it) }
        topMetricsView = null
        leftInclineView = null
        rightSpeedView = null
        bottomBarView = null
        elapsedHeroView = null
        bottomStateView = null
        primaryTransportButton = null
        endTransportButton = null
        volumeValueView = null
        // These hold detached views once the windows are gone. Not clearing them would keep every
        // pill from every rebuild alive and let the ticker write into views nobody can see.
        machineCells.clear()
        machineNoticeView = null
        // Every edge is free again. Leaving stale insets published would strand the launcher with
        // dead margins where the chrome used to be, which is most obvious right after the user
        // hides the overlay to watch something.
        publishTopInset(0)
        publishBottomInset(0)
        publishSideInset(left = true, value = 0)
        publishSideInset(left = false, value = 0)
    }

    private fun addTopMetrics() {
        publishTopInset(dp(HUD_TOP_ESTIMATE_DP))
        val root = FrameLayout(this).apply {
            setPadding(dp(142f), dp(12f), dp(142f), dp(0f))
            addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
                val laidOutHeight = bottom - top
                if (laidOutHeight > 0) publishTopInset(laidOutHeight) else publishInsetFromLayout(view, top = true)
            }
        }

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Color.argb(238, 4, 10, 22), 34f, Color.argb(140, 120, 146, 178))
            elevation = dp(12f).toFloat()
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
        }

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(70f),
            )
        }
        metrics.addView(metricPillCell("Incline", inclineText(), "%", amber, 1f).also {
            trackPill(it, "%", amber) { inclineText() }
        })
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("Miles", distanceText(), "", Color.rgb(190, 160, 255), 1f).also {
            trackPill(it, "", Color.rgb(190, 160, 255)) { distanceText() }
        })
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("Pace/mi", paceText(), "", Color.rgb(78, 232, 220), 1f).also {
            trackPill(it, "", Color.rgb(78, 232, 220)) { paceText() }
        })
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("Elapsed", WorkoutSession.formatElapsed(WorkoutSession.elapsedMs()), "", Color.WHITE, 1.28f).also {
            elapsedHeroView = it.findViewWithTag("value")
        })
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("Cals", caloriesText(), "", Color.rgb(255, 186, 76), 1f).also {
            trackPill(it, "", Color.rgb(255, 186, 76)) { caloriesText() }
        })
        metrics.addView(metricDivider())
        // Vertical gain needs incline integrated over distance, which we do not compute yet. It
        // stays honestly blank rather than being faked from a single incline sample.
        metrics.addView(metricPillCell("Vert gain", MachineLink.NO_READING, "ft", Color.rgb(104, 235, 126), 1f))
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("Speed", speedText(), "mph", cyan, 1f).also {
            trackPill(it, "mph", cyan) { speedText() }
        })
        pill.addView(metrics)

        val noticeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(8f)
            layoutParams = lp
        }
        noticeRow.addView(textView(MachineLink.metricsNotice, 14f, Color.rgb(231, 222, 205), bold = true).apply {
            machineNoticeView = this
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        noticeRow.addView(smallPillButton("Hide metrics", Color.rgb(22, 31, 44), Color.rgb(192, 212, 238)) {
            metricsVisible = false
            rebuildChromeViews()
            lastGesture = "metrics hidden"
        })
        pill.addView(noticeRow)
        root.addView(pill)

        val params = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        try {
            windowManager.addView(root, params)
            topMetricsView = root
            root.post { publishInsetFromLayout(root, top = true) }
        } catch (_: Exception) {
            topMetricsView = null
            publishTopInset(0)
        }
    }

    private fun addCollapsedMetricsToggle() {
        publishTopInset(dp(72f))
        val root = FrameLayout(this).apply {
            setPadding(dp(0f), dp(12f), dp(0f), dp(0f))
            addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
                val laidOutHeight = bottom - top
                if (laidOutHeight > 0) publishTopInset(laidOutHeight) else publishInsetFromLayout(view, top = true)
            }
        }
        root.addView(smallPillButton("Show metrics", Color.rgb(8, 16, 28), Color.rgb(226, 238, 255)) {
            metricsVisible = true
            rebuildChromeViews()
            lastGesture = "metrics shown"
        }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(150f), dp(72f), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        })
        val params = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        try {
            windowManager.addView(root, params)
            topMetricsView = root
            root.post { publishInsetFromLayout(root, top = true) }
        } catch (_: Exception) {
            topMetricsView = null
            publishTopInset(0)
        }
    }

    private fun metricPillCell(
        label: String,
        value: String,
        unit: String,
        accent: Int,
        weight: Float,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        val display = if (unit.isEmpty() || value == MachineLink.NO_READING) value else "$value $unit"
        addView(textView(display, if (label == "Elapsed") 38f else if (value == MachineLink.NO_READING) 18f else 30f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            tag = "value"
            maxLines = 1
            setTextColor(if (label == "Elapsed") Color.WHITE else if (value == MachineLink.NO_READING) Color.rgb(218, 226, 235) else accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        })
        addView(textView(label, 12f, Color.rgb(176, 190, 210), bold = true, gravity = Gravity.CENTER).apply {
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
    }

    private fun metricDivider(): View = View(this).apply {
        setBackgroundColor(Color.argb(130, 107, 128, 154))
        layoutParams = LinearLayout.LayoutParams(dp(1f), LinearLayout.LayoutParams.MATCH_PARENT)
    }

    private fun addInclineRail() {
        val current = MachineLink.inclinePercent?.roundToInt()?.toString()
        leftInclineView = addRail(
            title = "INCLINE",
            value = inclineText(),
            unit = "%",
            accent = amber,
            muted = amberMuted,
            entries = listOf("12", "11", "10", "9", "8", "7", "6", "5", "4", "3", "2", "1", "0", "-1", "-2", "-3"),
            entrySuffix = "%",
            currentEntry = current,
            gravity = Gravity.START or Gravity.TOP,
        )
        trackRail(leftInclineView) { inclineText() }
    }

    private fun addSpeedRail() {
        val current = MachineLink.speedMph?.roundToInt()?.toString()
        rightSpeedView = addRail(
            title = "SPEED",
            value = speedText(),
            unit = "MPH",
            accent = cyan,
            muted = cyanMuted,
            entries = listOf("12", "11", "10", "9", "8", "7", "6", "5", "4", "3", "2", "1"),
            entrySuffix = "",
            currentEntry = current,
            gravity = Gravity.END or Gravity.TOP,
        )
        trackRail(rightSpeedView) { speedText() }
    }

    /**
     * Vertical bounds for a side rail: the gap between the top chrome and the bottom bar.
     *
     * Prefers the heights the top and bottom bars actually reported after layout, falling back to
     * the DP estimates only before the first measurement lands. The estimates alone were wrong —
     * the top chrome carries a notice line *below* the metrics pill that they did not account for,
     * so the rails were drawn over it.
     *
     * Deliberately no minimum height. The previous `coerceAtLeast(560dp)` guaranteed an overlap
     * rather than preventing one: whenever the real gap was smaller than the floor, the rail was
     * forced to extend under the bottom bar. A rail that has to scroll is fine; a rail that hides
     * the transport controls is not.
     */
    private fun railBounds(): Pair<Int, Int> {
        val screenHeight = resources.displayMetrics.heightPixels
        val measuredTop = hudTopPx
        val top = when {
            measuredTop > 0 -> measuredTop
            metricsVisible -> dp(HUD_TOP_ESTIMATE_DP)
            else -> dp(72f)
        }
        val bottom = if (hudBottomPx > 0) hudBottomPx else dp(HUD_BOTTOM_ESTIMATE_DP)
        val gap = dp(12f)
        val y = top + gap
        val height = (screenHeight - y - bottom - gap).coerceAtLeast(dp(120f))
        return y to height
    }

    /** Re-place the rails once the top or bottom chrome reports its true height. */
    private fun repositionRails() {
        val (y, height) = railBounds()
        listOfNotNull(leftInclineView, rightSpeedView).forEach { rail ->
            val lp = rail.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            if (lp.y == y && lp.height == height) return@forEach
            lp.y = y
            lp.height = height
            try {
                windowManager.updateViewLayout(rail, lp)
            } catch (_: Exception) {
                // The rail is already gone; the next rebuild will place it correctly.
            }
        }
    }

    private fun addRail(
        title: String,
        value: String,
        unit: String,
        accent: Int,
        muted: Int,
        entries: List<String>,
        entrySuffix: String,
        currentEntry: String?,
        gravity: Int,
    ): View? {
        val (railTop, railHeight) = railBounds()
        var railSettling = false
        var ignoreClicksUntilMs = 0L
        val clearSettling = Runnable { railSettling = false }
        var activeView: View? = null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
        }
        content.addView(textView(title, 16f, accent, bold = true, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        content.addView(textView(value, if (value == MachineLink.NO_READING) 16f else 30f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            tag = "value"
            maxLines = 2
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8f)
            layoutParams = lp
        })
        content.addView(textView(unit, 12f, muted, bold = true, gravity = Gravity.CENTER).apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(2f)
            layoutParams = lp
        })
        content.addView(textView("LOCKED", 13f, Color.rgb(7, 12, 20), bold = true, gravity = Gravity.CENTER).apply {
            background = roundedRect(accent, 12f)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28f))
            lp.topMargin = dp(10f)
            layoutParams = lp
        })
        entries.forEach { entry ->
            val active = currentEntry == entry
            val button = railEntryButton(
                label = "$entry$entrySuffix",
                accent = accent,
                active = active,
            ) {
                if (railSettling || SystemClock.uptimeMillis() < ignoreClicksUntilMs) return@railEntryButton
                showMachineControlUnavailable()
            }
            if (active) activeView = button
            content.addView(button)
        }
        content.addView(textView("Use console", 12f, muted, bold = true, gravity = Gravity.CENTER).apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10f)
            layoutParams = lp
        })

        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        val rail = ScrollView(this).apply {
            // The card lives on the window-sized ScrollView, not on the taller scrolling content.
            // Drawn on the content, its bottom edge and rounded corners sit hundreds of pixels
            // below the window, so the rail reads as a box that runs on underneath the bottom bar
            // even when its window is correctly bounded above it.
            background = roundedRect(Color.argb(238, 5, 10, 22), 36f, Color.argb(210, Color.red(accent), Color.green(accent), Color.blue(accent)))
            // A pill guillotined flat at the edge looks like clipping by the bar. Faded, it reads
            // as what it is: a list with more below.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(32f))
            isFillViewport = false
            isClickable = true
            setOnScrollChangeListener { _, _, _, _, _ ->
                railSettling = true
                mainHandler.removeCallbacks(clearSettling)
                mainHandler.postDelayed(clearSettling, 260L)
            }
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && railSettling) {
                    (view as ScrollView).fling(0)
                    railSettling = false
                    ignoreClicksUntilMs = SystemClock.uptimeMillis() + 300L
                    true
                } else {
                    false
                }
            }
            setOnClickListener {
                if (railSettling || SystemClock.uptimeMillis() < ignoreClicksUntilMs) return@setOnClickListener
                showMachineControlUnavailable()
            }
            addView(content)
        }
        val params = baseParams(dp(180f), railHeight, gravity)
        params.x = dp(24f)
        params.y = railTop
        try {
            windowManager.addView(rail, params)
            // Report the edge this rail occupies, offset included, so Flutter can inset its grid
            // out from under it. Measured after layout rather than assumed from dp(180f), because
            // the rail is what is actually on screen and the constant is only what we asked for.
            rail.post {
                val occupied = params.x + (if (rail.width > 0) rail.width else dp(180f))
                publishSideInset(left = (gravity and Gravity.START) == Gravity.START, value = occupied)
                activeView?.let { active ->
                    val target = (active.top - (rail.height - active.height) / 2).coerceAtLeast(0)
                    rail.scrollTo(0, target)
                }
            }
            return rail
        } catch (_: Exception) {
            return null
        }
    }

    private fun railEntryButton(label: String, accent: Int, active: Boolean, onClick: () -> Unit): TextView =
        textView(label, 26f, if (active) Color.rgb(5, 10, 18) else Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "$label locked. ${MachineLink.CONTROL_LOCKED_NOTICE}"
            background = rippleRounded(
                color = if (active) accent else Color.rgb(17, 25, 36),
                radius = 28f,
                strokeColor = if (active) Color.WHITE else Color.argb(190, Color.red(accent), Color.green(accent), Color.blue(accent)),
            )
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76f))
            lp.topMargin = dp(8f)
            layoutParams = lp
            minimumHeight = dp(72f)
        }

    private fun addBottomBar() {
        publishBottomInset(dp(HUD_BOTTOM_ESTIMATE_DP))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Color.argb(238, 4, 9, 18), 0f, Color.argb(110, 90, 112, 142))
            setPadding(dp(22f), dp(10f), dp(22f), dp(10f))
            minimumHeight = dp(HUD_BOTTOM_ESTIMATE_DP)
            addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
                val laidOutHeight = bottom - top
                if (laidOutHeight > 0) publishBottomInset(laidOutHeight) else publishInsetFromLayout(view, top = false)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(bottomNavButton("Back", "‹") {
            val svc = StrideAccessibilityService.instance
            lastGesture = if (svc == null) {
                "BACK failed: accessibility service not connected"
            } else if (svc.goBack()) "BACK ok" else "BACK rejected"
        })
        row.addView(bottomNavButton("Home", "⌂") {
            goHomeFromService()
            lastGesture = "HOME ok"
        })
        row.addView(bottomNavButton("Recents", "▣") {
            val svc = StrideAccessibilityService.instance
            lastGesture = if (svc == null) {
                "RECENTS failed: accessibility service not connected"
            } else if (svc.goRecents()) "RECENTS ok" else "RECENTS rejected"
        })
        row.addView(timerCluster())
        row.addView(volumeCluster())
        row.addView(fanCluster())
        row.addView(smallPillButton("Hide overlay", Color.rgb(42, 25, 18), Color.rgb(255, 222, 190)) {
            hideChrome()
        }.apply {
            val lp = layoutParams as LinearLayout.LayoutParams
            lp.marginStart = dp(10f)
            layoutParams = lp
        })
        root.addView(row)
        root.addView(textView(MachineLink.NO_CONTROL_NOTICE, 14f, Color.rgb(238, 226, 202), bold = true, gravity = Gravity.CENTER).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(8f)
            layoutParams = lp
        })

        val params = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        )
        try {
            windowManager.addView(root, params)
            bottomBarView = root
            root.post { publishInsetFromLayout(root, top = false) }
        } catch (_: Exception) {
            bottomBarView = null
            publishBottomInset(0)
        }
    }

    private fun bottomNavButton(label: String, icon: String, onClick: () -> Unit): View {
        val buttonWidth = if (resources.displayMetrics.widthPixels < dp(1500f)) dp(86f) else dp(96f)
        return navSurfaceButton(
            label = label,
            icon = icon,
            subtitle = null,
            width = buttonWidth,
            height = dp(80f),
            compact = true,
            onClick = onClick,
        ).apply {
            val lp = LinearLayout.LayoutParams(buttonWidth, dp(80f))
            lp.marginEnd = dp(10f)
            layoutParams = lp
        }
    }

    private fun timerCluster(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedRect(Color.rgb(10, 19, 32), 28f, Color.argb(120, 108, 132, 164))
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        val lp = LinearLayout.LayoutParams(0, dp(88f), 1.3f)
        lp.marginStart = dp(6f)
        lp.marginEnd = dp(12f)
        layoutParams = lp
        primaryTransportButton = textView("", 20f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(72f), 1f)
            minimumHeight = dp(72f)
        }
        addView(primaryTransportButton)
        endTransportButton = textView("", 18f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            val endLp = LinearLayout.LayoutParams(dp(134f), dp(72f))
            endLp.marginStart = dp(10f)
            layoutParams = endLp
            minimumHeight = dp(72f)
        }
        addView(endTransportButton)
        bottomStateView = textView(stateLabel(), 13f, Color.rgb(198, 215, 238), bold = true, gravity = Gravity.CENTER).apply {
            val stateLp = LinearLayout.LayoutParams(dp(90f), LinearLayout.LayoutParams.MATCH_PARENT)
            stateLp.marginStart = dp(10f)
            layoutParams = stateLp
        }
        addView(bottomStateView)
    }

    private fun volumeCluster(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedRect(Color.rgb(8, 30, 48), 28f, Color.argb(170, 67, 170, 235))
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        val lp = LinearLayout.LayoutParams(0, dp(88f), 0.95f)
        lp.marginEnd = dp(12f)
        layoutParams = lp
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            addView(textView("Media volume", 14f, Color.rgb(205, 233, 255), bold = true))
            volumeValueView = textView(volumeText(), 24f, Color.WHITE, bold = true).apply {
                val valueLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                valueLp.topMargin = dp(6f)
                layoutParams = valueLp
            }
            addView(volumeValueView)
        })
        addView(controlButton("▼", enabled = true) { changeVolume(-1) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(72f), dp(72f))
        })
        addView(controlButton("▲", enabled = true) { changeVolume(1) }.apply {
            val plusLp = LinearLayout.LayoutParams(dp(72f), dp(72f))
            plusLp.marginStart = dp(8f)
            layoutParams = plusLp
        })
    }

    private fun fanCluster(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = "Fan locked. ${MachineLink.CONTROL_LOCKED_NOTICE}"
        background = roundedRect(Color.rgb(35, 24, 13), 28f, Color.argb(210, 255, 178, 55))
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        setOnClickListener { showMachineControlUnavailable() }
        val lp = LinearLayout.LayoutParams(0, dp(88f), 0.52f)
        lp.marginEnd = dp(10f)
        layoutParams = lp
        addView(textView("Fan", 14f, amberMuted, bold = true, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(textView("Locked", 21f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            val lockedLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lockedLp.topMargin = dp(7f)
            layoutParams = lockedLp
        })
    }

    private fun smallPillButton(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        textView(label, 14f, textColor, bold = true, gravity = Gravity.CENTER).apply {
            isClickable = true
            isFocusable = true
            contentDescription = label
            background = rippleRounded(fill, 20f, Color.argb(120, 134, 158, 188))
            setPadding(dp(14f), dp(0f), dp(14f), dp(0f))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(72f))
            minimumHeight = dp(72f)
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
            radius = 22f,
            strokeColor = Color.argb(125, 126, 148, 176),
        )
        elevation = dp(if (compact) 2f else 4f).toFloat()
        setPadding(dp(if (compact) 8f else 18f), dp(8f), dp(if (compact) 8f else 18f), dp(8f))
        layoutParams = LinearLayout.LayoutParams(width, height)
        addView(TextView(context).apply {
            text = icon
            setTextColor(Color.WHITE)
            textSize = if (compact) 26f else 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                if (compact) LinearLayout.LayoutParams.MATCH_PARENT else dp(44f),
                if (compact) dp(28f) else LinearLayout.LayoutParams.MATCH_PARENT,
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
            textSize = if (compact) 13f else 18f
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

    private fun controlButton(label: String, enabled: Boolean, onClick: (() -> Unit)?): TextView =
        textView(label, 32f, if (enabled) Color.WHITE else Color.rgb(255, 222, 171), bold = true, gravity = Gravity.CENTER)
            .apply {
                minimumWidth = dp(72f)
                minimumHeight = dp(72f)
                isEnabled = true
                isClickable = onClick != null
                background = rippleRounded(
                    color = if (enabled) Color.rgb(26, 92, 197) else Color.rgb(66, 43, 18),
                    radius = 22f,
                    strokeColor = if (enabled) Color.argb(180, 164, 202, 255)
                    else Color.rgb(255, 178, 55),
                )
                if (onClick != null) setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(dp(72f), dp(72f))
            }

    private fun addHandle() {
        handleView?.let { safeRemove(it) }
        if (chromeVisible) {
            handleView = null
            return
        }
        val handle = textView("⌃", 28f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Show Stride overlay"
            background = rippleRounded(Color.argb(244, 8, 16, 28), 22f, Color.argb(190, 182, 207, 238))
            setOnClickListener { showChrome() }
            elevation = dp(16f).toFloat()
        }
        val params = baseParams(dp(HANDLE_WIDTH_DP), dp(HANDLE_HEIGHT_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        params.y = dp(0f)
        try {
            windowManager.addView(handle, params)
            handleView = handle
        } catch (_: Exception) {
            handleView = null
        }
    }

    private fun updateHandle() {
        // The handle is the recovery affordance for a hidden overlay, so it only earns its place
        // when the chrome is actually hidden. While the bottom bar is up it carries its own "Hide
        // overlay" button, and a second control doing the same job sat centred on top of the
        // transport row — covering the End workout button and the safety notice behind it.
        if (chromeVisible) {
            handleView?.let { safeRemove(it) }
            handleView = null
            return
        }
        val handle = handleView ?: run {
            addHandle()
            return
        }
        handle.text = "⌃"
        handle.contentDescription = "Show Stride overlay"
        val lp = handle.layoutParams as? WindowManager.LayoutParams ?: return
        lp.y = dp(0f)
        try {
            windowManager.updateViewLayout(handle, lp)
        } catch (_: Exception) {
            addHandle()
        }
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
            // Invisible by design: when chrome is hidden the bottom handle is the only visible affordance.
            setBackgroundColor(Color.TRANSPARENT)
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
        if (!chromeVisible) {
            showChrome()
            lastGesture = "edge swipe restored overlay chrome"
        } else {
            lastGesture = "edge swipe: overlay chrome already visible"
        }
    }


}
