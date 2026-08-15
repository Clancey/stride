package io.stride.spikes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The centrepiece of the NordicTrack/iFit stock treadmill workout screen: a running track drawn as
 * an oval seen in perspective, lying flat like a floor, with a marker showing where the runner
 * currently is around the lap.
 *
 * ### The geometry
 *
 * Where the track *is* lives in [TrackGeometry]: a genuine ground plane seen from a low camera
 * rather than a squashed 2D ellipse, with a constant-width lane and a solved fit onto whatever box
 * this view is given. That is the half of this surface that can be checked without a treadmill, so
 * it is kept separate and unit-tested. This class is the other half: what colour it all is.
 *
 * ### Cost
 *
 * Everything that only depends on size is computed once in [rebuildGeometry]: the sample tables,
 * the lane path, the lane markings, the start line, the shaders and the label metrics. [onDraw]
 * allocates nothing; it walks cached arrays and reuses instance Paints, Paths and Shaders, because
 * the host redraws this view on a one-second ticker and animates the marker between those ticks.
 */
class TrackFloorView(context: Context) : View(context) {

    /**
     * Lap progress 0f..1f measured from the start line, or null when the machine cannot tell us.
     *
     * Null is drawn as an empty track — no marker, no completed band — never as zero. "We cannot
     * see how far you have run" and "you have run nothing" are different claims and a marker parked
     * on the start line makes the second one.
     *
     * Successive known values are animated rather than jumped, because the feed behind this is a
     * one-second poll of a distance register and a marker that teleports once a second reads as
     * broken. A jump of more than [SNAP_FRACTION] of a lap is treated as a seek or a new session
     * and lands immediately.
     */
    var progress: Float? = null
        set(value) {
            val next = value?.let { floorMod(it, 1f) }
            if (next == field) return
            val previous = field
            field = next
            applyProgress(next, previous)
        }

    /** Small line above the title, e.g. "LAP 3". Empty hides it. Invalidates on change. */
    var lapBadge: String = ""
        set(value) {
            if (value == field) return
            field = value
            layoutLabels()
            invalidate()
        }

    /** Large label line, e.g. "¼ mile". Invalidates on change. */
    var lapTitle: String = ""
        set(value) {
            if (value == field) return
            field = value
            layoutLabels()
            invalidate()
        }

    /** Small label line under it, e.g. "track length". Invalidates on change. */
    var lapSubtitle: String = ""
        set(value) {
            if (value == field) return
            field = value
            layoutLabels()
            invalidate()
        }

    /** Overall opacity multiplier 0f..1f applied to everything drawn. Default 1f. */
    var dim: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    private companion object {
        /** Samples around the full lap. 288 keeps the outline smooth at console width. */
        const val SAMPLES = TrackGeometry.DEFAULT_SAMPLES

        /** Fraction of a lap the start/finish chequer covers. */
        const val CHEQUER_SPAN = 0.014f
        const val CHEQUER_COLUMNS = 6
        const val CHEQUER_ROWS = 2

        /** Matches the host's one-second poll, so the marker arrives just as the next sample lands. */
        const val PROGRESS_ANIM_MS = 1000L

        /** Beyond this much of a lap in one sample it is a seek or a fresh session, not running. */
        const val SNAP_FRACTION = 0.34f
    }

    private val density: Float = resources.displayMetrics.density

    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0xD6, 0xD0, 0xFF)
        strokeWidth = 1.6f * resources.displayMetrics.density
    }
    private val bandEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0xCF, 0xFF, 0xF4)
        strokeWidth = 1.4f * resources.displayMetrics.density
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        strokeWidth = 1.6f * resources.displayMetrics.density
    }
    private val chequerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x04, 0x08, 0x0C)
    }
    private val markerBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x5C, 0xE8, 0xD2)
    }
    private val markerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.16f
        color = Color.rgb(0x7A, 0xEA, 0xD6)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = Color.rgb(0xE6, 0xE2, 0xFC)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = Color.rgb(0x9A, 0x96, 0xBA)
    }

    // Design alphas are held separately so repeated [dim] writes multiply against the design value
    // each frame instead of compounding — 0.5 twice would otherwise fade everything to nothing.
    private val baseAlphas = intArrayOf(255, 255, 120, 90, 60, 235, 255, 90, 255, 255, 255, 255, 255)
    private val dimmable = arrayOf(
        lanePaint, bandPaint, outerRimPaint, bandEdgePaint, dashPaint, chequerPaint,
        scrimPaint, shadowPaint, markerBodyPaint, markerInnerPaint, badgePaint, titlePaint,
        subtitlePaint,
    )

    private val lanePath = Path()
    private val bandPath = Path()
    private val dashPath = Path()
    private val chequerPath = Path()
    private val markerPath = Path()
    private val fontMetrics = Paint.FontMetrics()

    // Screen-space sample tables, indexed by travel fraction from the start line. Built once per
    // size so a frame is a walk over arrays rather than 1700 trig calls.
    private val outerX = FloatArray(SAMPLES + 1)
    private val outerY = FloatArray(SAMPLES + 1)
    private val innerX = FloatArray(SAMPLES + 1)
    private val innerY = FloatArray(SAMPLES + 1)

    private val geometry = TrackGeometry()
    private var geometryReady = false

    private var badgeY = 0f
    private var titleY = 0f
    private var subtitleY = 0f

    private var shownProgress = 0f
    private var animFrom = 0f
    private var animSpan = 0f
    private var animStartMs = 0L

    private val animTick = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.uptimeMillis() - animStartMs
            if (elapsed >= PROGRESS_ANIM_MS) {
                shownProgress = floorMod(animFrom + animSpan, 1f)
            } else {
                shownProgress = floorMod(animFrom + animSpan * (elapsed.toFloat() / PROGRESS_ANIM_MS), 1f)
                postOnAnimation(this)
            }
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry(w, h)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The ticker is posted to the view's own animation queue; a detached view that keeps
        // reposting one keeps the whole overlay window alive for nothing.
        removeCallbacks(animTick)
    }

    override fun onDraw(canvas: Canvas) {
        if (!geometryReady) return
        applyDim()

        canvas.drawPath(lanePath, lanePaint)
        val hasProgress = progress != null
        if (hasProgress) drawCompletedBand(canvas)
        canvas.drawPath(lanePath, outerRimPaint)
        canvas.drawPath(dashPath, dashPaint)
        canvas.drawPath(chequerPath, chequerPaint)
        drawInfield(canvas)
        if (hasProgress) drawMarker(canvas)
    }

    /**
     * Ask the geometry to fit the new size, fill the screen-space sample tables from it, and build
     * everything else that only depends on size. Called on every size change and nowhere else.
     */
    private fun rebuildGeometry(w: Int, h: Int) {
        geometryReady = false
        if (w <= 0 || h <= 0) return
        if (!geometry.fit(w.toFloat(), h.toFloat(), 8f * density, 6f * density)) return

        for (i in 0..SAMPLES) {
            val u = i.toFloat() / SAMPLES
            geometry.project(u, 1f)
            outerX[i] = geometry.x
            outerY[i] = geometry.y
            geometry.project(u, -1f)
            innerX[i] = geometry.x
            innerY[i] = geometry.y
        }

        buildLanePath()
        buildDashPath()
        buildChequerPath()
        buildShaders(geometry.outerTop, geometry.outerBottom)

        geometryReady = true
        layoutLabels()
    }

    /** Outer contour forward, inner contour backward: one closed ring with a genuine hole. */
    private fun buildLanePath() {
        lanePath.reset()
        lanePath.moveTo(outerX[0], outerY[0])
        for (i in 1..SAMPLES) lanePath.lineTo(outerX[i], outerY[i])
        for (i in SAMPLES downTo 0) lanePath.lineTo(innerX[i], innerY[i])
        lanePath.close()
    }

    /** Broken centre line. Track markings are what stop a coloured ring reading as a progress bar. */
    private fun buildDashPath() {
        dashPath.reset()
        val stride = 8
        var i = 0
        while (i < SAMPLES) {
            geometry.project(i.toFloat() / SAMPLES, 0f)
            dashPath.moveTo(geometry.x, geometry.y)
            val end = min(i + stride / 2, SAMPLES)
            geometry.project(end.toFloat() / SAMPLES, 0f)
            dashPath.lineTo(geometry.x, geometry.y)
            i += stride
        }
    }

    /** The painted start/finish stripe, as chequered squares laid across the lane. */
    private fun buildChequerPath() {
        chequerPath.reset()
        for (row in 0 until CHEQUER_ROWS) {
            for (col in 0 until CHEQUER_COLUMNS) {
                if ((row + col) % 2 != 0) continue
                val u0 = -CHEQUER_SPAN / 2f + CHEQUER_SPAN * row / CHEQUER_ROWS
                val u1 = u0 + CHEQUER_SPAN / CHEQUER_ROWS
                val s0 = -1f + 2f * col / CHEQUER_COLUMNS
                val s1 = -1f + 2f * (col + 1) / CHEQUER_COLUMNS
                geometry.project(u0, s0)
                chequerPath.moveTo(geometry.x, geometry.y)
                geometry.project(u0, s1)
                chequerPath.lineTo(geometry.x, geometry.y)
                geometry.project(u1, s1)
                chequerPath.lineTo(geometry.x, geometry.y)
                geometry.project(u1, s0)
                chequerPath.lineTo(geometry.x, geometry.y)
                chequerPath.close()
            }
        }
    }

    /**
     * Translucent and brightest at the near edge.
     *
     * A flat opaque band reads as a plastic ring lying on the glass; letting the far side sink into
     * whatever is playing underneath is what makes it read as ground receding away from the rider.
     * The infield scrim is the exception — text over an album grid needs something opaque behind it.
     */
    private fun buildShaders(top: Float, bottom: Float) {
        lanePaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Color.argb(76, 0x6C, 0x65, 0xBE),
                Color.argb(134, 0x8A, 0x83, 0xDC),
                Color.argb(205, 0xAD, 0xA6, 0xF6),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        bandPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                Color.argb(78, 0x40, 0xC8, 0xB8),
                Color.argb(128, 0x50, 0xD8, 0xC4),
                Color.argb(190, 0x68, 0xEE, 0xD6),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        val scrimRadius = max(geometry.infieldWidth, geometry.infieldHeight) * 0.42f
        if (scrimRadius > 0f) {
            scrimPaint.shader = RadialGradient(
                geometry.infieldCenterX,
                geometry.infieldCenterY,
                scrimRadius,
                intArrayOf(0xD8060B10.toInt(), 0x8C060B10.toInt(), 0x00060B10),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    /**
     * Size the infield copy against the hole it sits in, not against the view.
     *
     * The hole is measured, and its centre is above the view's centre because perspective pushes
     * the near straight down. Text placed on the view's centre line sat on the near lane.
     */
    private fun layoutLabels() {
        if (!geometryReady || geometry.infieldHeight <= 0f) return
        badgePaint.textSize = geometry.infieldHeight * 0.078f
        subtitlePaint.textSize = geometry.infieldHeight * 0.088f
        titlePaint.textSize = geometry.infieldHeight * 0.215f

        // The title is the only line long enough to reach the lane. Shrink rather than clip: a
        // truncated "¼ mile" is a different claim about the track.
        val room = geometry.infieldWidth * 0.78f
        if (lapTitle.isNotEmpty()) {
            val measured = titlePaint.measureText(lapTitle)
            if (measured > room) titlePaint.textSize *= room / measured
        }

        badgeY = geometry.infieldCenterY - geometry.infieldHeight * 0.20f
        titleY = geometry.infieldCenterY + geometry.infieldHeight * 0.01f
        subtitleY = geometry.infieldCenterY + geometry.infieldHeight * 0.18f
    }

    private fun drawCompletedBand(canvas: Canvas) {
        val p = shownProgress
        if (p <= 0.0005f) return
        val last = (p * SAMPLES).toInt().coerceIn(0, SAMPLES)

        bandPath.reset()
        bandPath.moveTo(outerX[0], outerY[0])
        for (i in 1..last) bandPath.lineTo(outerX[i], outerY[i])
        geometry.project(p, 1f)
        bandPath.lineTo(geometry.x, geometry.y)
        geometry.project(p, -1f)
        bandPath.lineTo(geometry.x, geometry.y)
        for (i in last downTo 0) bandPath.lineTo(innerX[i], innerY[i])
        bandPath.close()

        canvas.drawPath(bandPath, bandPaint)
        canvas.drawPath(bandPath, bandEdgePaint)
    }

    private fun drawInfield(canvas: Canvas) {
        if (scrimPaint.shader != null) {
            canvas.drawCircle(
                geometry.infieldCenterX,
                geometry.infieldCenterY,
                max(geometry.infieldWidth, geometry.infieldHeight) * 0.42f,
                scrimPaint,
            )
        }
        if (lapBadge.isNotEmpty()) drawCentred(canvas, lapBadge, badgePaint, badgeY)
        if (lapTitle.isNotEmpty()) drawCentred(canvas, lapTitle, titlePaint, titleY)
        if (lapSubtitle.isNotEmpty()) drawCentred(canvas, lapSubtitle, subtitlePaint, subtitleY)
    }

    private fun drawCentred(canvas: Canvas, text: String, paint: Paint, centreY: Float) {
        paint.getFontMetrics(fontMetrics)
        canvas.drawText(text, geometry.infieldCenterX, centreY - (fontMetrics.ascent + fontMetrics.descent) / 2f, paint)
    }

    /**
     * An upright pin standing on the lane, sized by the lane it stands in.
     *
     * Sizing off the local lane width rather than a constant is what carries the perspective into
     * the marker: the same pin is large on the near straight and small across the infield, which is
     * the cue that says the two sides of the loop are at different distances. It does not rotate —
     * a pin is stuck in the ground, and the earlier version that turned to face travel spent the
     * far half of every lap lying on its side.
     */
    private fun drawMarker(canvas: Canvas) {
        val p = shownProgress
        val pin = geometry.laneWidthAt(p) * TrackGeometry.PIN_SCALE
        if (pin <= 0f) return
        geometry.project(p, 0f)
        val mx = geometry.x
        val my = geometry.y

        val half = pin * TrackGeometry.PIN_HALF_WIDTH
        val drop = pin * TrackGeometry.PIN_SHADOW_DROP
        val headR = pin * TrackGeometry.PIN_HEAD_RADIUS
        val headY = my - pin * TrackGeometry.PIN_HEAD_OFFSET

        canvas.drawOval(mx - half, my - drop, mx + half, my + drop, shadowPaint)
        // Body: a triangle from the ground point up to the head, capped by the head circle.
        markerPath.reset()
        markerPath.moveTo(mx, my)
        markerPath.lineTo(mx - half, my - pin * 0.58f)
        markerPath.lineTo(mx + half, my - pin * 0.58f)
        markerPath.close()
        canvas.drawPath(markerPath, markerBodyPaint)
        canvas.drawCircle(mx, headY, headR, markerBodyPaint)
        canvas.drawCircle(mx, headY, headR * 0.42f, markerInnerPaint)
    }

    /**
     * Take a new lap position, animating toward it unless the jump says it is not running.
     *
     * [previous] being null means the track has been dark — there was nothing on screen to animate
     * from, so the first known position lands rather than sweeping in from the start line.
     */
    private fun applyProgress(next: Float?, previous: Float?) {
        removeCallbacks(animTick)
        if (next == null) {
            invalidate()
            return
        }
        // Distance only ever grows, so the marker only ever runs forward; the wrap past the start
        // line is a small forward step, not a lap-long sprint backwards.
        val delta = floorMod(next - shownProgress, 1f)
        if (previous == null || delta > SNAP_FRACTION || windowToken == null) {
            shownProgress = next
            invalidate()
            return
        }
        animFrom = shownProgress
        animSpan = delta
        animStartMs = SystemClock.uptimeMillis()
        postOnAnimation(animTick)
    }

    private fun applyDim() {
        for (i in dimmable.indices) {
            dimmable[i].alpha = (baseAlphas[i] * dim).toInt().coerceIn(0, 255)
        }
    }

    private fun floorMod(value: Float, mod: Float): Float {
        val r = value % mod
        return if (r < 0f) r + mod else r
    }
}
