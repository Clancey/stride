package io.stride.spikes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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

        /** The running overlay, so the launcher can drive it over the method channel. */
        private var active: OverlayService? = null

        /**
         * Whether the rider has explicitly chosen a track-floor state this session.
         *
         * Until they do, the floor follows what is playing underneath: it is a decorative surface
         * in the middle of the screen, which is exactly where a film is. An explicit choice is
         * remembered and stops the automatic suppression second-guessing it. Held here rather than
         * per-instance so the setting survives an overlay restart and is readable from the bridge.
         */
        @Volatile
        var trackFloorChosen: Boolean? = null
            private set

        /**
         * True when the floor is currently drawn — the rider's choice, or the automatic default.
         *
         * The default is deliberately narrow: a track floor is a picture of *motion*, so it earns
         * the middle of the screen only while a workout is under way, and it yields to video even
         * then. An explicit choice overrides both, in either direction.
         */
        fun trackFloorOn(context: Context): Boolean = trackFloorChosen
            ?: (WorkoutSession.state != WorkoutSession.State.IDLE &&
                !MainActivity.launcherForeground &&
                !MediaNowPlaying.videoIsPlaying(context))

        /** Set (or, with null, un-set) the rider's choice and redraw if the overlay is up. */
        fun setTrackFloor(chosen: Boolean?) {
            trackFloorChosen = chosen
            // The console reboots. A choice the rider made about their own screen has to still be
            // true afterwards, so this is written through rather than held in memory.
            StrideSettings.trackFloor = chosen
            refreshChrome()
        }

        /**
         * Rebuild the overlay's windows, if one is running.
         *
         * Needed whenever something outside the service changes what the chrome is made of —
         * setting a goal adds a ring, clearing one takes it away — as opposed to merely changing
         * what an existing view says.
         */
        fun refreshChrome() {
            active?.let { svc -> svc.mainHandler.post { svc.rebuildChromeViews() } }
        }

        private const val CHANNEL_ID = "stride_spikes_overlay"
        private const val NOTIFICATION_ID = 4321

        /** Minimum travel before an edge touch is treated as navigation rather than passed up. */
        private const val SWIPE_THRESHOLD_DP = 48f

        /** Width of the always-present touchable edge strips. */
        private const val EDGE_STRIP_WIDTH_DP = 20f

        /** Non-zero first-frame top inset until the metrics pill reports its real laid-out height. */
        private const val HUD_TOP_ESTIMATE_DP = 92f

        /** Non-zero first-frame bottom inset until the bottom bar reports its real laid-out height. */
        private const val HUD_BOTTOM_ESTIMATE_DP = 112f

        /** Width of a quick-pick column. */
        private const val RAIL_WIDTH_DP = 132f

        /**
         * How far past the end rungs a value may sit and still mark that rung.
         *
         * Small on purpose. Its whole job is to absorb rounding, not to pull a stopped belt onto
         * the lowest speed pill.
         */
        private const val RAIL_MARK_TOLERANCE = 0.25

        /** Wide enough for the five fan segments, which are the sheet's widest row. */
        private const val MENU_WIDTH_DP = 700f
        private val DESTRUCTIVE_INK = Color.rgb(255, 138, 128)

        /** Footprint of the track floor. Wide and shallow, so the oval reads as ground. */
        private const val FLOOR_WIDTH_DP = 1020f
        private const val FLOOR_HEIGHT_DP = 300f

        /** Diameter of the circular corner toggles, and the room the rails must leave them. */
        private const val CORNER_SIZE_DP = 84f

        /** Diameter of the goal ring window. */
        private const val RING_SIZE_DP = 260f

        /**
         * Bottom space the now-playing card occupies, reserved for Stride's own Flutter UI.
         *
         * The card floats over whatever is underneath, and third-party apps neither know nor care.
         * The launcher does: without this the card sat on top of a pinned tile and hid its label.
         */
        private const val NOW_PLAYING_RESERVE_DP = 130f

        /** Stock draws a quarter-mile lap, and the rider's sense of "a lap" should match it. */
        private const val LAP_MILES = 0.25
        private const val LAP_TITLE = "\u00BC mile"

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

        /**
         * Extra bottom space occupied by floating overlay cards, over and above the bottom bar.
         *
         * Kept separate from [hudBottomPx] on purpose: the bar height is what the overlay's own
         * windows anchor to, so folding the card's height into it would push the card up by its
         * own height every time it appeared. Only the inset published to Flutter adds this.
         */
        var hudBottomExtraPx: Int = 0
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
    private var inclineRail: RailBinding? = null
    private var speedRail: RailBinding? = null
    private var bottomBarView: View? = null
    private var handleView: TextView? = null
    private val edgeViews = mutableListOf<View>()
    private var chromeVisible: Boolean = true
    private var metricsVisible: Boolean = true
    private var railsVisible: Boolean = true
    private var trackFloorView: TrackFloorView? = null
    private var trackFloorRoot: View? = null
    private var goalRingView: GoalRingView? = null
    private var goalRingRoot: View? = null
    private var cornerLeftView: View? = null
    private var cornerRightView: View? = null
    private var nowPlayingRoot: View? = null
    private var nowPlayingArt: ImageView? = null
    private var nowPlayingTitle: TextView? = null
    private var nowPlayingArtist: TextView? = null
    private var nowPlayingPlay: TextView? = null

    private var elapsedHeroView: TextView? = null
    private var primaryTransportButton: TextView? = null
    private var endTransportButton: TextView? = null
    private var volumeValueView: TextView? = null
    private var moreMenuView: View? = null
    private var fixItView: View? = null
    private val fanSegmentViews = mutableMapOf<Int, TextView>()

    private val amber = Color.rgb(255, 178, 55)
    private val amberMuted = Color.rgb(255, 222, 171)
    private val cyan = Color.rgb(40, 199, 255)
    private val cyanMuted = Color.rgb(190, 234, 255)

    private val workoutListener: (WorkoutSession.State) -> Unit = {
        mainHandler.post {
            // The track floor and the goal ring only exist while a workout does, so a state change
            // is a structural change to the chrome, not just new text in it. Rebuilding only when
            // the answer actually flipped keeps pause/resume from tearing the overlay down twice.
            val structural = trackFloorWanted() != (trackFloorView != null) ||
                WorkoutGoal.trackable() != (goalRingView != null)
            if (chromeVisible && structural) {
                rebuildChromeViews()
            } else {
                updateWorkoutUi()
                scheduleElapsedTicker()
            }
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
            // Re-arm for as long as the chrome is on screen. Keying this to the metric cells alone
            // would freeze the track floor, the goal ring, and the now-playing card whenever the
            // rider collapsed the top strip — which is exactly when those are the only readout left.
            if (chromeVisible) mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun updateMachineMetrics() {
        syncRailHighlights()
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
        trackFloorView?.progress = lapProgress()
        goalRingView?.let { applyGoalRing(it) }
        refreshNowPlaying()
    }

    /**
     * Register a top metric pill for live refresh.
     *
     * The unit now lives in the label under the figure, so nothing is appended to the number
     * itself; the cell keeps an empty unit and the strip stays a column of bare numerals.
     */
    private fun trackPill(cell: View, read: () -> String) {
        machineCells += MachineCell(
            root = cell,
            unit = "",
            valueColor = Color.WHITE,
            blankColor = Color.rgb(150, 165, 188),
            valueSize = 31f,
            blankSize = 15f,
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
        active = this
        // Before anything reads a setting. The overlay can be started by BootReceiver with no
        // Activity ever having run, so it cannot assume the launcher attached the store first.
        StrideSettings.attach(this)
        // Android drops enabled_accessibility_services on reinstall, which silently kills Back and
        // Recents. If Stride holds WRITE_SECURE_SETTINGS it just puts them back here, before the
        // rider ever finds out. Without that grant this is a no-op and the setup card asks instead.
        StridePermissions.repair(this)
        trackFloorChosen = StrideSettings.trackFloor
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        systemAudio = SystemAudio(this)
        WorkoutSession.addListener(workoutListener)
        // Start reading the machine here rather than in the Activity: the overlay outlives the
        // launcher UI, and the metrics on it are exactly what someone mid-run is looking at.
        MachineLink.attach(this)
        // Also attached here, not only from the bridge: the overlay outlives the Flutter engine,
        // and the pause button on it must still stop the belt after the launcher UI is gone.
        WorkoutMachineCoupling.attach()
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
        // Only clear the shared handle if it still points at us: a restart can construct the new
        // service before the old one is destroyed, and nulling it then would strand the live one.
        if (active === this) active = null
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
        dismissMoreMenu()
        dismissFixIt()
        handleView?.let { safeRemove(it) }
        handleView = null
        edgeViews.forEach { safeRemove(it) }
        edgeViews.clear()
        elapsedHeroView = null
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
        updateElapsedDisplays()
        updateTransportButtons()
        updateVolumeViews()
    }

    private fun updateTransportButtons() {
        val primary = primaryTransportButton ?: return
        // Ending only makes sense once the rider has already stopped moving. While the belt runs,
        // Pause is the single thing worth reaching for, and a stop lives one tap deeper.
        if (WorkoutSession.state == WorkoutSession.State.PAUSED) {
            endTransportButton?.visibility = View.VISIBLE
        }
        when (WorkoutSession.state) {
            WorkoutSession.State.IDLE -> {
                configureActionText(
                    primary,
                    label = "Start workout",
                    primary = true,
                    enabled = true,
                ) {
                    WorkoutSession.start()
                    lastGesture = "timer started"
                }
                // Hidden rather than greyed out. A disabled "End workout" beside "Start workout"
                // is a control the rider has to read and dismiss every time they glance down.
                endTransportButton?.visibility = View.GONE
            }

            WorkoutSession.State.RUNNING -> {
                configureActionText(
                    primary,
                    label = "Pause workout",
                    primary = true,
                    enabled = true,
                ) {
                    WorkoutSession.pause()
                    lastGesture = "timer paused"
                }
                endTransportButton?.visibility = View.GONE
            }

            WorkoutSession.State.PAUSED -> {
                configureActionText(
                    primary,
                    label = "Resume workout",
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
                    destructive = true,
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
        destructive: Boolean = false,
        onClick: () -> Unit,
    ) {
        button ?: return
        button.text = label
        button.isEnabled = enabled
        button.isClickable = enabled
        button.alpha = if (enabled) 1f else 0.5f
        // Ending a workout is the only irreversible thing on this bar, so it carries the warning
        // colour in its text and edge. An outline rather than a red slab: it must read as serious
        // without competing with the primary action for the eye.
        button.setTextColor(
            when {
                !enabled -> Color.rgb(150, 161, 178)
                destructive -> DESTRUCTIVE_INK
                else -> Color.WHITE
            },
        )
        button.background = rippleRounded(
            color = when {
                !enabled -> Color.rgb(30, 36, 45)
                destructive -> Color.argb(60, 120, 30, 34)
                primary -> Color.rgb(20, 109, 255)
                else -> Color.rgb(48, 58, 74)
            },
            radius = 24f,
            strokeColor = when {
                !enabled -> Color.argb(120, 93, 105, 124)
                destructive -> DESTRUCTIVE_INK
                else -> Color.argb(180, 178, 211, 255)
            },
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

    /**
     * Back and Recents, or an explanation of why they did nothing.
     *
     * Android clears `enabled_accessibility_services` on its own — reinstalling Stride does it,
     * and so did uninstalling an unrelated app. Before this, Back simply stopped working: no
     * error, no log the rider can see, just a dead button on a console with no physical buttons.
     * That is how someone ends up stranded inside Netflix.
     *
     * So a failure is never silent. It says what broke and opens the page that fixes it.
     */
    private fun navigateOrExplain(label: String, action: (StrideAccessibilityService) -> Boolean) {
        val svc = StrideAccessibilityService.instance
        if (svc != null && action(svc)) {
            lastGesture = "$label ok"
            return
        }
        // Try to fix it outright before bothering the rider. When Stride holds WRITE_SECURE_SETTINGS
        // this turns a dead button into a working one with no dialog at all — though the service
        // still has to be bound by the system, so this press may be the one that pays for it.
        if (StridePermissions.repair(this).isNotEmpty()) {
            lastGesture = "$label restored the accessibility grant"
            StrideAccessibilityService.instance?.let {
                if (action(it)) return
            }
        }
        lastGesture = "$label failed: accessibility service not connected"
        showFixIt(
            title = "$label isn't working",
            body = "Android switched Stride's accessibility service off, and it is the only way to " +
                "send $label to another app. Turn it back on and this button works again.",
            where = "Find Stride Spikes in the list and switch it on.",
            actionLabel = "Open settings",
        ) {
            StridePermissions.openSettingsFor(this, StridePermissions.ACCESSIBILITY)
        }
    }

    /**
     * A modal the rider can act on, over whatever app is running.
     *
     * A Toast would be wrong here: it is unreadable at arm's length on a moving treadmill, and it
     * cannot carry the button that actually fixes the problem. Telling someone a permission is
     * missing without taking them to it is the same as not telling them.
     */
    private fun showFixIt(
        title: String,
        body: String,
        where: String?,
        actionLabel: String,
        onAction: () -> Unit,
    ) {
        dismissFixIt()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(196, 2, 5, 11))
            isClickable = true
            setOnClickListener { dismissFixIt() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Color.rgb(9, 14, 24), 30f, Color.argb(180, 214, 158, 62))
            setPadding(dp(34f), dp(28f), dp(34f), dp(28f))
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                dp(720f),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        card.addView(textView(title, 30f, Color.rgb(236, 242, 255), bold = true))
        card.addView(
            textView(body, 19f, Color.rgb(178, 192, 216)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12f) }
            },
        )
        if (where != null) {
            card.addView(
                textView(where, 19f, Color.rgb(214, 158, 62), bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10f) }
                },
            )
        }
        card.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(24f) }
                addView(menuAction("Not now") { dismissFixIt() })
                addView(
                    menuAction(actionLabel) {
                        dismissFixIt()
                        onAction()
                    }.apply {
                        setTextColor(Color.rgb(5, 10, 18))
                        background = rippleRounded(
                            color = Color.rgb(214, 158, 62),
                            radius = 22f,
                            strokeColor = Color.rgb(214, 158, 62),
                        )
                    },
                )
            },
        )
        scrim.addView(card)
        try {
            windowManager.addView(
                scrim,
                baseParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP or Gravity.START,
                ),
            )
            fixItView = scrim
        } catch (_: Exception) {
            fixItView = null
        }
    }

    private fun dismissFixIt() {
        fixItView?.let { safeRemove(it) }
        fixItView = null
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
        addTrackFloor()
        addGoalRing()
        if (railsVisible) {
            addInclineRail()
            addSpeedRail()
        }
        addCornerControls()
        addNowPlaying()
        addBottomBar()
        updateWorkoutUi()
        scheduleElapsedTicker()
        // Restart the machine ticker against the freshly built views. removeCallbacks first so a
        // rebuild cannot leave two tickers running and double the poll rate.
        mainHandler.removeCallbacks(machineTicker)
        mainHandler.post(machineTicker)
    }

    /**
     * Whether the track floor should currently be drawn.
     *
     * An explicit choice always wins. Absent one, the floor stays out of the way of video: it
     * occupies the middle of the screen, which is where a film is, and nobody put Netflix on to
     * watch a lap counter over it. Music is deliberately not treated the same way -- there is
     * nothing to occlude, and the floor is the more interesting thing to look at.
     */
    private fun trackFloorWanted(): Boolean = trackFloorOn(this)

    private fun addTrackFloor() {
        if (!trackFloorWanted()) return
        val floor = TrackFloorView(this).apply {
            lapTitle = LAP_TITLE
            lapSubtitle = "track length"
            progress = lapProgress()
            dim = 0.92f
        }
        val root = FrameLayout(this).apply { addView(floor) }
        // Explicitly untouchable: the floor covers the middle of the screen, and the app running
        // underneath owns every tap that lands there. A decorative surface that eats touches is a
        // broken remote control.
        //
        // Sized and anchored low on purpose. Stretched to the full window the oval reads as a
        // giant ring pasted over the screen; kept short and sitting on the bottom bar it reads as
        // what it is — a floor receding away from the rider.
        val params = baseParams(dp(FLOOR_WIDTH_DP), dp(FLOOR_HEIGHT_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        params.y = (hudBottomPx.takeIf { it > 0 } ?: dp(HUD_BOTTOM_ESTIMATE_DP)) + dp(8f)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.addView(root, params)
            trackFloorRoot = root
            trackFloorView = floor
        } catch (_: Exception) {
            trackFloorRoot = null
            trackFloorView = null
        }
    }

    /** Fraction of the current lap covered, from the machine's own distance reading. */
    private fun lapProgress(): Float {
        val miles = MachineLink.distanceMiles ?: return 0f
        val laps = miles / LAP_MILES
        return (laps - kotlin.math.floor(laps)).toFloat()
    }

    private fun addGoalRing() {
        if (!WorkoutGoal.trackable()) return
        val ring = GoalRingView(this).apply {
            title = if (WorkoutGoal.kind == WorkoutGoal.Kind.DISTANCE) "DISTANCE GOAL" else "TIME GOAL"
        }
        applyGoalRing(ring)
        val root = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(dp(RING_SIZE_DP), dp(RING_SIZE_DP)))
        }
        // Bottom-right, mirroring the now-playing card on the left. The ring started in the top
        // right and collided with the launcher's own header controls there; nothing else competes
        // for this corner, and it puts goal and media on the same baseline with the track floor
        // running between them.
        val params = baseParams(dp(RING_SIZE_DP), dp(RING_SIZE_DP), Gravity.BOTTOM or Gravity.END)
        params.x = dp(if (railsVisible) RAIL_WIDTH_DP + 24f else CORNER_SIZE_DP + 50f)
        params.y = (hudBottomPx.takeIf { it > 0 } ?: dp(HUD_BOTTOM_ESTIMATE_DP)) + dp(18f)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.addView(root, params)
            goalRingRoot = root
            goalRingView = ring
        } catch (_: Exception) {
            goalRingRoot = null
            goalRingView = null
        }
    }

    private fun applyGoalRing(ring: GoalRingView) {
        val fraction = WorkoutGoal.progressFraction()
        ring.progress = fraction?.toFloat() ?: 0f
        ring.caption = if (fraction == null) "NOT MEASURED" else "COMPLETE"
        val eta = WorkoutGoal.etaMs()
        ring.footnote = when {
            eta == null -> WorkoutGoal.targetLabel()
            eta <= 0L -> "Goal reached"
            else -> "${WorkoutSession.formatElapsed(eta)} to go"
        }
    }

    /**
     * The circular incline and speed buttons in the bottom corners.
     *
     * Stock opens a rotary fine-adjust dial from these. Stride cannot command the machine, so a
     * dial would be a control that does nothing; they show and hide their own quick-pick column
     * instead, which is the useful half of what stock does with that corner.
     */
    private fun addCornerControls() {
        cornerLeftView = addCornerControl(
            icon = R.drawable.ic_metric_incline,
            accent = amber,
            gravity = Gravity.START or Gravity.BOTTOM,
            description = if (railsVisible) "Hide incline and speed columns" else "Show incline and speed columns",
        )
        cornerRightView = addCornerControl(
            icon = R.drawable.ic_metric_speed,
            accent = cyan,
            gravity = Gravity.END or Gravity.BOTTOM,
            description = if (railsVisible) "Hide incline and speed columns" else "Show incline and speed columns",
        )
    }

    private fun addCornerControl(icon: Int, accent: Int, gravity: Int, description: String): View? {
        val size = dp(CORNER_SIZE_DP)
        val button = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(
                if (railsVisible) Color.rgb(8, 14, 26) else Color.argb(235, 226, 236, 252),
            )
            val inset = dp(22f)
            setPadding(inset, inset, inset, inset)
            background = rippleRounded(
                color = if (railsVisible) accent else Color.argb(226, 15, 22, 40),
                radius = 42f,
                strokeColor = if (railsVisible) null else Color.argb(150, 62, 76, 116),
            )
            contentDescription = description
            elevation = dp(10f).toFloat()
            setOnClickListener {
                railsVisible = !railsVisible
                rebuildChromeViews()
                lastGesture = if (railsVisible) "quick picks shown" else "quick picks hidden"
            }
        }
        val root = FrameLayout(this).apply { addView(button, FrameLayout.LayoutParams(size, size)) }
        val params = baseParams(size, size, gravity)
        params.x = dp(34f)
        params.y = (hudBottomPx.takeIf { it > 0 } ?: dp(HUD_BOTTOM_ESTIMATE_DP)) + dp(18f)
        return try {
            windowManager.addView(root, params)
            root
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A now-playing card for *music*, anchored above the bottom bar on the left.
     *
     * Deliberately not shown for video: a film already fills the screen with its own art and title,
     * so a card repeating them is noise laid over the thing the rider chose to watch. Music has no
     * on-screen presence at all, which is the gap this fills.
     */
    private fun addNowPlaying() {
        val snapshot = MediaNowPlaying.snapshot(this) ?: return
        if (snapshot.isVideo) return

        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedRect(Color.argb(255, 22, 28, 46), 16f, Color.argb(120, 70, 84, 124))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16f).toFloat())
                }
            }
        }
        val title = textView("", 21f, Color.WHITE, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val artist = textView("", 15f, Color.argb(215, 168, 182, 210)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(artist)
        }
        val play = mediaTransport("\u23F8") { MediaNowPlaying.playPause(this); refreshNowPlaying() }
        val service = this
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mediaTransport("\u23EE") {
                MediaNowPlaying.skipPrevious(service)
                service.refreshNowPlaying()
            })
            addView(play)
            addView(mediaTransport("\u23ED") {
                MediaNowPlaying.skipNext(service)
                service.refreshNowPlaying()
            })
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(14f), dp(18f), dp(14f))
            background = roundedRect(Color.argb(250, 12, 17, 32), 26f, Color.argb(140, 58, 72, 112))
            elevation = dp(10f).toFloat()
            addView(art, LinearLayout.LayoutParams(dp(84f), dp(84f)))
            addView(text, LinearLayout.LayoutParams(dp(300f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16f)
            })
            addView(transport, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10f) })
        }
        val root = FrameLayout(this).apply { addView(row) }
        val params = baseParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
        params.x = dp(if (railsVisible) RAIL_WIDTH_DP + 24f else CORNER_SIZE_DP + 50f)
        params.y = (hudBottomPx.takeIf { it > 0 } ?: dp(HUD_BOTTOM_ESTIMATE_DP)) + dp(18f)
        try {
            windowManager.addView(root, params)
            hudBottomExtraPx = dp(NOW_PLAYING_RESERVE_DP)
            nowPlayingRoot = root
            nowPlayingArt = art
            nowPlayingTitle = title
            nowPlayingArtist = artist
            nowPlayingPlay = play
            applyNowPlaying(snapshot)
        } catch (_: Exception) {
            clearNowPlayingRefs()
        }
    }

    private fun mediaTransport(glyph: String, onTap: () -> Unit): TextView {
        val size = dp(56f)
        return TextView(this).apply {
            text = glyph
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.argb(240, 226, 236, 252))
            gravity = Gravity.CENTER
            background = rippleRounded(Color.argb(210, 22, 30, 52), 28f, Color.argb(110, 62, 76, 116))
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(8f) }
            setOnClickListener { onTap() }
        }
    }

    private fun applyNowPlaying(snapshot: MediaNowPlaying.Snapshot) {
        nowPlayingTitle?.text = snapshot.title ?: "Playing"
        nowPlayingArtist?.text = listOfNotNull(
            snapshot.artist?.takeIf { it.isNotBlank() },
            snapshot.album?.takeIf { it.isNotBlank() },
        ).joinToString(" \u00B7 ").ifEmpty { snapshot.packageName }
        nowPlayingPlay?.text = if (snapshot.isPlaying) "\u23F8" else "\u25B6"
        val art = MediaNowPlaying.artwork(this)
        if (art != null) nowPlayingArt?.setImageBitmap(art) else nowPlayingArt?.setImageDrawable(null)
    }

    /**
     * Reconcile the card with reality. The card is added and removed rather than merely hidden,
     * because a touchable window parked over a media app would keep eating taps meant for it long
     * after the music stopped.
     */
    private fun refreshNowPlaying() {
        if (!chromeVisible) return
        val snapshot = MediaNowPlaying.snapshot(this)?.takeIf { !it.isVideo }
        if (snapshot == null) {
            nowPlayingRoot?.let { safeRemove(it) }
            clearNowPlayingRefs()
            return
        }
        if (nowPlayingRoot == null) addNowPlaying() else applyNowPlaying(snapshot)
    }

    private fun clearNowPlayingRefs() {
        hudBottomExtraPx = 0
        nowPlayingRoot = null
        nowPlayingArt = null
        nowPlayingTitle = null
        nowPlayingArtist = null
        nowPlayingPlay = null
    }

    private fun removeChromeViews() {
        listOfNotNull(
            topMetricsView, leftInclineView, rightSpeedView, bottomBarView,
            trackFloorRoot, goalRingRoot, cornerLeftView, cornerRightView, nowPlayingRoot,
        ).forEach { safeRemove(it) }
        topMetricsView = null
        leftInclineView = null
        rightSpeedView = null
        inclineRail = null
        speedRail = null
        bottomBarView = null
        trackFloorRoot = null
        trackFloorView = null
        goalRingRoot = null
        goalRingView = null
        cornerLeftView = null
        cornerRightView = null
        clearNowPlayingRefs()
        elapsedHeroView = null
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
            background = roundedRect(Color.argb(242, 8, 13, 28), 40f, Color.argb(90, 108, 128, 168))
            elevation = dp(14f).toFloat()
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
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
                dp(64f),
            )
        }
        metrics.addView(metricPillCell("incline %", inclineText(), R.drawable.ic_metric_incline, amber, 1f).also {
            trackPill(it) { inclineText() }
        })
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("miles", distanceText(), R.drawable.ic_metric_miles, Color.rgb(126, 162, 255), 1f).also {
            trackPill(it) { distanceText() }
        })
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("pace/mi", paceText(), R.drawable.ic_metric_pace, Color.rgb(78, 232, 190), 1f).also {
            trackPill(it) { paceText() }
        })
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("elapsed", WorkoutSession.formatElapsed(WorkoutSession.elapsedMs()), R.drawable.ic_metric_elapsed, Color.rgb(96, 186, 255), 1.12f).also {
            elapsedHeroView = it.findViewWithTag("value")
        })
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("cals (est)", caloriesText(), R.drawable.ic_metric_cals, Color.rgb(255, 143, 74), 1f).also {
            trackPill(it) { caloriesText() }
        })
        metrics.addView(metricDivider())
        // Vertical gain needs incline integrated over distance, which we do not compute yet. It
        // stays honestly blank rather than being faked from a single incline sample.
        metrics.addView(metricPillCell("vert gain (ft)", MachineLink.NO_READING, R.drawable.ic_metric_vertgain, Color.rgb(104, 235, 126), 1f))
        metrics.addView(metricDivider())
        metrics.addView(metricPillCell("speed mph", speedText(), R.drawable.ic_metric_speed, cyan, 1f).also {
            trackPill(it) { speedText() }
        })
        pill.addView(metrics)
        // No safety notice here. The bottom bar already carries it, and the same warning printed
        // twice on one screen is read as decoration -- which is exactly how a rider learns to stop
        // reading the one that matters.
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

    /**
     * One readout in the top strip: a tinted icon beside the figure, with the unit written into
     * the label beneath rather than trailing the number.
     *
     * The figure itself is always white and the colour is carried entirely by the icon. Tinting
     * seven numbers seven different colours reads as seven warnings; the rider needs to scan the
     * row, and identical numerals with a coloured glyph to anchor each one scans far faster.
     */
    private fun metricPillCell(
        label: String,
        value: String,
        icon: Int,
        accent: Int,
        weight: Float,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
        setPadding(dp(14f), 0, dp(6f), 0)
        addView(ImageView(this@OverlayService).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(accent)
            layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { rightMargin = dp(10f) }
        })
        addView(LinearLayout(this@OverlayService).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(textView(value, if (value == MachineLink.NO_READING) 15f else 31f, Color.WHITE, bold = true).apply {
                tag = "value"
                maxLines = 1
                setTextColor(if (value == MachineLink.NO_READING) Color.rgb(150, 165, 188) else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(textView(label, 13f, Color.rgb(139, 152, 174), bold = false).apply {
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
        })
    }

    /** Hairline between readouts, inset from the pill's top and bottom as in stock. */
    private fun metricDivider(): View = View(this).apply {
        setBackgroundColor(Color.argb(70, 128, 148, 184))
        layoutParams = LinearLayout.LayoutParams(dp(1f), LinearLayout.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(10f)
            bottomMargin = dp(10f)
        }
    }

    /**
     * Preset columns come from the machine when it has told us what it supports
     * (SpeedService/GetControls and InclineService/GetControls), and fall back to the
     * documented range for this equipment class only until that read lands.
     */
    private fun addInclineRail() {
        val presets = MachineLink.inclinePresets
            ?.map { it.roundToInt().toString() }
            ?: listOf("12", "10", "8", "6", "5", "4", "3", "2", "1", "0", "-1", "-2", "-3")
        val binding = addRail(
            accent = amber,
            entries = presets,
            entrySuffix = "%",
            currentEntry = MachineLink.inclinePercent?.roundToInt()?.toString(),
            gravity = Gravity.START or Gravity.TOP,
            onPick = { percent ->
                MachineCoordinator.setInclinePercent(percent)
                lastGesture = "incline -> $percent%"
            },
        )
        inclineRail = binding
        leftInclineView = binding?.scroll
    }

    private fun addSpeedRail() {
        val presets = MachineLink.speedPresets
            ?.map { it.roundToInt().toString() }
            ?: listOf("12", "10", "9", "8", "7", "6", "5", "4", "3", "2", "1")
        val binding = addRail(
            accent = cyan,
            entries = presets,
            entrySuffix = "",
            currentEntry = MachineLink.speedMph?.roundToInt()?.toString(),
            gravity = Gravity.END or Gravity.TOP,
            onPick = { mph ->
                MachineCoordinator.setSpeedMph(mph)
                lastGesture = "speed -> $mph mph"
            },
        )
        speedRail = binding
        rightSpeedView = binding?.scroll
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
        // The circular quick-pick toggles live in the same columns as the rails, just above the
        // bottom bar. Without reserving their footprint the last pill slides underneath one and
        // becomes unreadable and untappable at the same time.
        val corners = dp(CORNER_SIZE_DP + 18f + 12f)
        val height = (screenHeight - y - bottom - corners - gap).coerceAtLeast(dp(120f))
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

    /**
     * A quick-pick column: bare pills floating against the video, with no card behind them.
     *
     * The card that used to wrap these carried a title, the live reading and a LOCKED badge, all
     * of which the top strip already says once. Repeating them down the edge cost a broad opaque
     * slab over whatever the rider was watching, to tell them nothing new. Stock has no card here
     * for the same reason.
     */
    private fun addRail(
        accent: Int,
        entries: List<String>,
        entrySuffix: String,
        currentEntry: String?,
        gravity: Int,
        onPick: (Double) -> Unit,
    ): RailBinding? {
        val (railTop, railHeight) = railBounds()
        var railSettling = false
        var ignoreClicksUntilMs = 0L
        val clearSettling = Runnable { railSettling = false }
        var activeView: View? = null
        val buttons = LinkedHashMap<String, TextView>()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            this.gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        entries.forEach { entry ->
            val active = currentEntry == entry
            val button = railEntryButton(
                label = "$entry$entrySuffix",
                accent = accent,
                active = active,
            ) {
                if (railSettling || SystemClock.uptimeMillis() < ignoreClicksUntilMs) return@railEntryButton
                // The pill's own label is the source of the value, so what the rider sees is
                // exactly what gets sent. Parsing can only fail if a preset is not a number, in
                // which case sending nothing is the right answer.
                val value = entry.toDoubleOrNull()
                if (value == null || !MachineLink.canCommand()) {
                    showMachineControlUnavailable()
                    return@railEntryButton
                }
                onPick(value)
            }
            if (active) activeView = button
            buttons[entry] = button
            content.addView(button)
        }

        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        val rail = ScrollView(this).apply {
            // Faded rather than guillotined: a pill sliced flat at the boundary reads as clipped
            // by the bar above or below it, instead of as a list with more in it.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(40f))
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
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
            addView(content)
        }
        val params = baseParams(dp(RAIL_WIDTH_DP), railHeight, gravity)
        params.x = dp(30f)
        params.y = railTop
        val binding = RailBinding(accent = accent, buttons = buttons, scroll = rail)
        binding.applied = currentEntry
        binding.isSettling = { railSettling }
        try {
            windowManager.addView(rail, params)
            // Report the edge this rail occupies, offset included, so Flutter can inset its grid
            // out from under it. Measured after layout rather than assumed from the constant,
            // because the rail is what is actually on screen and the constant is only the ask.
            rail.post {
                val occupied = params.x + (if (rail.width > 0) rail.width else dp(RAIL_WIDTH_DP))
                publishSideInset(left = (gravity and Gravity.START) == Gravity.START, value = occupied)
                activeView?.let { active ->
                    val target = (active.top - (rail.height - active.height) / 2).coerceAtLeast(0)
                    rail.scrollTo(0, target)
                }
            }
            return binding
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * A live handle on one rail, so the highlight can follow the machine instead of freezing at the
     * value that happened to be true when the window was built.
     *
     * This matters beyond tidiness: the console's own physical buttons and any residual iFit
     * workout can move speed and incline without Stride asking. A rail that still marks the last
     * value *Stride* sent is telling the rider the belt is somewhere it is not, which is exactly the
     * kind of confident-but-wrong readout the safety-copy rule in MachineLink exists to prevent.
     */
    private class RailBinding(
        val accent: Int,
        val buttons: Map<String, TextView>,
        val scroll: ScrollView,
    ) {
        var applied: String? = null
        var isSettling: () -> Boolean = { false }
    }

    /**
     * Re-mark both rails from current telemetry. Text and background only — no view is added or
     * removed — so this is safe from the one-second tick, unlike a rebuild.
     */
    private fun syncRailHighlights() {
        applyRailHighlight(inclineRail, MachineLink.inclinePercent)
        applyRailHighlight(speedRail, MachineLink.speedMph)
    }

    /**
     * Mark the pill nearest the machine's actual value.
     *
     * Nearest rather than exact-match: the console's own buttons move in half steps, so an exact
     * string comparison against integer presets leaves the whole column unmarked at 2.5 mph — the
     * rider gets a scale with no position on it precisely when they are looking for one. The top
     * strip stays the authoritative number; the rail is a picker showing which rung they are on.
     */
    private fun applyRailHighlight(rail: RailBinding?, current: Double?) {
        val binding = rail ?: return
        val target = current?.let { value ->
            val rungs = binding.buttons.keys.mapNotNull { key -> key.toDoubleOrNull()?.let { key to it } }
            val lowest = rungs.minOfOrNull { it.second }
            val highest = rungs.maxOfOrNull { it.second }
            // Off the bottom of the scale is not the bottom rung. A stopped belt sits below the
            // lowest speed preset, and snapping it to "1" would light a pill claiming the machine
            // is walking when it is standing still. Nothing marked is the truthful answer there.
            val offScale = lowest == null || highest == null ||
                value < lowest - RAIL_MARK_TOLERANCE || value > highest + RAIL_MARK_TOLERANCE
            if (offScale) null else rungs.minByOrNull { abs(it.second - value) }?.first
        }
        if (binding.applied == target) return
        binding.applied?.let { previous ->
            binding.buttons[previous]?.let { styleRailEntry(it, binding.accent, active = false) }
        }
        val active = target?.let { binding.buttons[it] }
        active?.let { styleRailEntry(it, binding.accent, active = true) }
        binding.applied = target
        // Never yank the column out from under a rider mid-scroll; they are reaching for a value and
        // moving the list would make them miss it.
        if (active != null && !binding.isSettling()) {
            binding.scroll.post {
                val target = (active.top - (binding.scroll.height - active.height) / 2).coerceAtLeast(0)
                binding.scroll.smoothScrollTo(0, target)
            }
        }
    }

    /**
     * One quick-pick pill. Muted by default and accented only when it is the value the machine
     * currently reports, so the column reads as a scale with the rider's position marked on it
     * rather than as sixteen competing buttons.
     */
    private fun railEntryButton(label: String, accent: Int, active: Boolean, onClick: () -> Unit): TextView =
        textView(label, 30f, Color.rgb(206, 214, 232), bold = true, gravity = Gravity.CENTER).apply {
            isClickable = true
            isFocusable = true
            contentDescription = label
            styleRailEntry(this, accent, active)
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66f))
            lp.topMargin = dp(11f)
            layoutParams = lp
            minimumHeight = dp(66f)
        }

    /** The one place a rail pill's marked/unmarked look is defined, so build and refresh cannot drift. */
    private fun styleRailEntry(view: TextView, accent: Int, active: Boolean) {
        view.setTextColor(if (active) Color.rgb(5, 10, 18) else Color.rgb(206, 214, 232))
        view.background = rippleRounded(
            color = if (active) accent else Color.argb(224, 18, 25, 46),
            radius = 34f,
            strokeColor = if (active) Color.WHITE else Color.argb(150, 62, 76, 116),
        )
    }

    // ------------------------------------------------------------------ more menu

    /**
     * The occasional controls, one tap away instead of always on screen.
     *
     * A modal sheet rather than an expanding bar section: the sheet can be as tall as it needs to
     * be without moving the transport controls, and the transport controls staying exactly where
     * they were is the point. A rider reaching for pause must not find that opening a menu shifted
     * it.
     */
    private fun showMoreMenu() {
        if (moreMenuView != null) {
            dismissMoreMenu()
            return
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(168, 2, 5, 11))
            isClickable = true
            setOnClickListener { dismissMoreMenu() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(Color.rgb(9, 14, 24), 30f, Color.argb(150, 96, 118, 152))
            setPadding(dp(26f), dp(20f), dp(26f), dp(24f))
            // Swallows its own taps so a miss inside the sheet does not dismiss it.
            isClickable = true
            // Explicit width, not WRAP_CONTENT. A wrapping card measures its MATCH_PARENT rows
            // against a width it has not decided yet, and the sheet collapsed into a single column
            // with one fan pill and a three-line toggle. Sized to the widest row: five fan segments
            // plus the card's own padding.
            val lp = FrameLayout.LayoutParams(
                dp(MENU_WIDTH_DP),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            )
            lp.bottomMargin = (if (hudBottomPx > 0) hudBottomPx else dp(HUD_BOTTOM_ESTIMATE_DP)) + dp(16f)
            // Clear of the speed rail, so the sheet reads as sitting beside the column rather than
            // dropped on top of it.
            lp.marginEnd = dp(30f) + dp(RAIL_WIDTH_DP) + dp(18f)
            layoutParams = lp
        }

        card.addView(menuSectionLabel("Navigation", first = true))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(menuAction("‹  Back") {
                dismissMoreMenu()
                navigateOrExplain("Back") { it.goBack() }
            })
            addView(menuAction("⌂  Home") {
                dismissMoreMenu()
                goHomeFromService()
                lastGesture = "HOME ok"
            })
            addView(menuAction("▣  Recents") {
                dismissMoreMenu()
                navigateOrExplain("Recents") { it.goRecents() }
            })
        })

        card.addView(menuSectionLabel("Fan", first = false))
        card.addView(fanSegments())

        card.addView(menuSectionLabel("Media volume", first = false))
        card.addView(volumeCluster().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(88f),
            )
        })

        card.addView(menuSectionLabel("Overlay", first = false))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(menuToggle("Metrics", metricsVisible) {
                metricsVisible = !metricsVisible
                dismissMoreMenu()
                rebuildChromeViews()
                lastGesture = if (metricsVisible) "metrics shown" else "metrics hidden"
            })
            addView(menuToggle("Track floor", trackFloorWanted()) {
                trackFloorChosen = !trackFloorWanted()
                dismissMoreMenu()
                rebuildChromeViews()
                lastGesture = if (trackFloorChosen == true) "track floor shown" else "track floor hidden"
            })
        })

        scrim.addView(card)
        val params = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START,
        )
        try {
            windowManager.addView(scrim, params)
            moreMenuView = scrim
        } catch (_: Exception) {
            moreMenuView = null
        }
    }

    private fun dismissMoreMenu() {
        moreMenuView?.let { safeRemove(it) }
        moreMenuView = null
        // The volume readout lived in the sheet. Leaving the reference set would let the ticker
        // keep writing into a view that is no longer attached to anything.
        volumeValueView = null
        fanSegmentViews.clear()
    }

    /** A section heading. More space above than below, so the label binds to what it introduces. */
    private fun menuSectionLabel(title: String, first: Boolean): TextView =
        textView(title, 15f, Color.rgb(150, 168, 196), bold = true).apply {
            letterSpacing = 0.06f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = if (first) 0 else dp(26f)
            lp.bottomMargin = dp(10f)
            layoutParams = lp
        }

    /**
     * The fan, as a scale rather than a lock.
     *
     * This control used to read "Locked" because Stride had no fan implementation at all — the pill
     * was decoration describing a limitation. It now writes FanStateService, and Auto is offered
     * only when the machine says it can match fan speed to effort.
     */
    private fun fanSegments(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val current = MachineCoordinator.lastFanState ?: StrideSettings.fanState
        val states = listOf(
            GlassOsCommands.FAN_OFF,
            GlassOsCommands.FAN_LOW,
            GlassOsCommands.FAN_MEDIUM,
            GlassOsCommands.FAN_HIGH,
            GlassOsCommands.FAN_AUTO,
        )
        states.forEachIndexed { index, state ->
            val active = current == state
            val pill = textView(
                GlassOsCommands.fanStateName(state),
                19f,
                if (active) Color.rgb(28, 18, 4) else Color.rgb(206, 214, 232),
                bold = true,
                gravity = Gravity.CENTER,
            ).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "Fan ${GlassOsCommands.fanStateName(state)}"
                background = rippleRounded(
                    color = if (active) amber else Color.argb(224, 18, 25, 46),
                    radius = 26f,
                    strokeColor = if (active) Color.WHITE else Color.argb(140, 62, 76, 116),
                )
                val lp = LinearLayout.LayoutParams(dp(108f), dp(72f))
                if (index > 0) lp.marginStart = dp(8f)
                layoutParams = lp
                minimumHeight = dp(72f)
                setOnClickListener {
                    if (!MachineLink.canCommand()) {
                        showMachineControlUnavailable()
                        return@setOnClickListener
                    }
                    StrideSettings.fanState = state
                    MachineCoordinator.setFan(state)
                    lastGesture = "fan -> ${GlassOsCommands.fanStateName(state)}"
                    refreshFanSegments(state)
                }
            }
            fanSegmentViews[state] = pill
            addView(pill)
        }
    }

    private fun refreshFanSegments(selected: Int) {
        fanSegmentViews.forEach { (state, view) ->
            val active = state == selected
            view.setTextColor(if (active) Color.rgb(28, 18, 4) else Color.rgb(206, 214, 232))
            view.background = rippleRounded(
                color = if (active) amber else Color.argb(224, 18, 25, 46),
                radius = 26f,
                strokeColor = if (active) Color.WHITE else Color.argb(140, 62, 76, 116),
            )
        }
    }

    private fun menuToggle(label: String, on: Boolean, onClick: () -> Unit): TextView =
        textView(
            if (on) "$label  on" else "$label  off",
            17f,
            if (on) Color.rgb(190, 232, 255) else Color.rgb(150, 165, 188),
            bold = true,
            gravity = Gravity.CENTER,
        ).apply {
            isClickable = true
            isFocusable = true
            contentDescription = if (on) "$label on" else "$label off"
            background = rippleRounded(
                color = if (on) Color.rgb(20, 38, 52) else Color.rgb(20, 24, 33),
                radius = 24f,
                strokeColor = if (on) Color.argb(190, 90, 170, 214) else Color.argb(120, 78, 92, 118),
            )
            setPadding(dp(20f), 0, dp(20f), 0)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(72f))
            lp.marginEnd = dp(10f)
            layoutParams = lp
            minimumHeight = dp(72f)
            setOnClickListener { onClick() }
        }

    private fun menuAction(label: String, onClick: () -> Unit): TextView =
        textView(label, 17f, Color.rgb(226, 233, 245), bold = true, gravity = Gravity.CENTER).apply {
            isClickable = true
            isFocusable = true
            contentDescription = label
            background = rippleRounded(Color.rgb(20, 24, 33), 24f, Color.argb(120, 78, 92, 118))
            setPadding(dp(20f), 0, dp(20f), 0)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(72f))
            lp.marginEnd = dp(10f)
            layoutParams = lp
            minimumHeight = dp(72f)
            setOnClickListener { onClick() }
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
        // A frame rather than a row: the workout controls are centred against the whole bar, so
        // they sit under the rider's eye rather than off in one corner, and they stay put as the
        // transport changes width. The menu and the hide control ride the far edge independently,
        // which a single row could not do without the centre drifting whenever they resized.
        val row = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(
            timerCluster(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        // Three controls, and one of them is the workout. Back, Home and Recents moved into the
        // menu: they are reachable there and by the edge swipe, and keeping them on the bar meant
        // the belt controls shared a row with navigation for a machine that is mostly not being
        // navigated.
        // Back and Home ride the near edge. This console has no physical buttons at all, so these
        // are not conveniences -- they are the only way out of an app that has taken the screen.
        // They were briefly menu-only, which put the most-pressed control on the machine two taps
        // deep. Recents stays in the menu; it is the one a rider genuinely reaches for rarely.
        val navCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        navCluster.addView(bottomNavButton("Back", "‹", width = dp(104f)) {
            navigateOrExplain("Back") { it.goBack() }
        })
        navCluster.addView(bottomNavButton("Home", "⌂", width = dp(104f)) {
            goHomeFromService()
            lastGesture = "HOME ok"
        })
        row.addView(
            navCluster,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ),
        )
        val edgeCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        edgeCluster.addView(bottomNavButton("Menu", "⋯", width = dp(104f)) { showMoreMenu() })
        edgeCluster.addView(bottomNavButton("Hide overlay", "⌄", width = dp(150f)) { hideChrome() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        })
        row.addView(
            edgeCluster,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        root.addView(row)
        // Bound to machineNoticeView so the 1s ticker can keep it honest. Built from a constant it
        // froze at "doesn't control" and printed that beside a full row of "Not measured", which
        // claims we are reading the machine at the exact moment we are not.
        root.addView(textView(MachineLink.metricsNotice, 14f, Color.rgb(238, 226, 202), bold = true, gravity = Gravity.CENTER).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(8f)
            layoutParams = lp
            machineNoticeView = this
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

    private fun bottomNavButton(label: String, icon: String, width: Int = 0, onClick: () -> Unit): View {
        val buttonWidth = if (width > 0) width
            else if (resources.displayMetrics.widthPixels < dp(1500f)) dp(86f) else dp(96f)
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
        // Sized to the words, not to the row. Stretched across the full bar the primary action
        // read as a banner rather than something to press, and a button that wide gives a rider no
        // target to aim at — every part of it is equally the middle of nowhere.
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(88f))
        lp.marginStart = dp(12f)
        lp.marginEnd = dp(12f)
        layoutParams = lp
        primaryTransportButton = textView("", 22f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            layoutParams = LinearLayout.LayoutParams(dp(360f), dp(72f))
            minimumHeight = dp(72f)
        }
        addView(primaryTransportButton)
        endTransportButton = textView("", 19f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            val endLp = LinearLayout.LayoutParams(dp(224f), dp(72f))
            endLp.marginStart = dp(12f)
            layoutParams = endLp
            minimumHeight = dp(72f)
        }
        addView(endTransportButton)
        // No separate state chip. The primary button already reads "Start" or "Pause", so a
        // "Running" label beside it spent bar width restating the button next to it.
    }

    /**
     * Volume, as a value with two steppers.
     *
     * No inner title: the sheet's section heading already says "Media volume", and repeating it
     * inside the control put the same two words on screen twice in a row. Styled like the other
     * rows rather than in its own blue, so the sheet reads as one surface instead of a stack of
     * competing widgets.
     */
    private fun volumeCluster(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedRect(Color.rgb(20, 24, 33), 24f, Color.argb(120, 78, 92, 118))
        setPadding(dp(20f), dp(8f), dp(10f), dp(8f))
        volumeValueView = textView(volumeText(), 26f, Color.WHITE, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(volumeValueView)
        addView(controlButton("▼", enabled = true) { changeVolume(-1) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(72f), dp(72f))
        })
        addView(controlButton("▲", enabled = true) { changeVolume(1) }.apply {
            val plusLp = LinearLayout.LayoutParams(dp(72f), dp(72f))
            plusLp.marginStart = dp(8f)
            layoutParams = plusLp
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
