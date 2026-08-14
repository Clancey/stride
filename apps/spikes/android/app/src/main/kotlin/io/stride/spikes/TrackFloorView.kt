package io.stride.spikes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The centrepiece of the NordicTrack/iFit stock treadmill workout screen: a running track drawn as
 * an oval seen in perspective, lying flat like a floor, with a map-pin marker showing where the
 * runner currently is around the lap.
 *
 * The oval is defined in a normalised "ground plane" space and projected to screen through a single
 * reusable foreshortening so the far side reads as further away than the near side — it is a floor,
 * not a top-down 2D ellipse. Nothing is allocated in [onDraw]; every Paint/Path/Matrix/Shader is a
 * reused instance field because the host redraws this view on a ticker.
 */
class TrackFloorView(context: Context) : View(context) {

    /** Lap progress, 0f..1f. Values outside are wrapped into range. Invalidates on change. */
    var progress: Float = 0f
        set(value) {
            val wrapped = floorMod(value, 1f)
            if (wrapped != field) {
                field = wrapped
                invalidate()
            }
        }

    /** Large label line, e.g. "¼ mile". Invalidates on change. */
    var lapTitle: String = ""
        set(value) {
            if (value != field) {
                field = value
                invalidate()
            }
        }

    /** Small label line under it, e.g. "track length". Invalidates on change. */
    var lapSubtitle: String = ""
        set(value) {
            if (value != field) {
                field = value
                invalidate()
            }
        }

    /** Overall opacity multiplier 0f..1f applied to everything drawn. Default 1f. Invalidates on change. */
    var dim: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    private val density: Float = resources.displayMetrics.density

    // A small vanishing-point gain: near (bottom) samples are widened and far (top) samples are
    // narrowed by (1 + k * gy), which is what sells the oval as receding along the ground rather
    // than lying flat against the glass. Kept low so the loop never crosses over on itself.
    private val perspectiveK: Float = 0.30f

    // The oval's inner boundary as a fraction of the outer radius. The gap between the two is the
    // visible lane; a large hole in the middle is what makes this a stadium loop and not a blob.
    private val innerScale: Float = 0.74f

    private val outlineSamples: Int = 160

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(90, 0xC4, 0x8B, 0xC8)
    }
    private val startLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = Color.WHITE
    }
    private val markerBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x3F, 0xE0, 0xC8)
    }
    private val markerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(0x1A, 0x2E, 0x2B)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = Color.rgb(0xDA, 0xD6, 0xF2)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        color = Color.rgb(0x95, 0x91, 0xB4)
    }

    // Base alphas are cached so that repeated [dim] writes multiply against the design alpha each
    // frame instead of compounding (0.5 dim twice would otherwise fade everything to nothing).
    private val fillBaseAlpha = 255
    private val rimBaseAlpha = 90
    private val startLineBaseAlpha = 235
    private val markerBodyBaseAlpha = 255
    private val markerInnerBaseAlpha = 255
    private val chevronBaseAlpha = 255
    private val titleBaseAlpha = 255
    private val subtitleBaseAlpha = 255

    private val lanePath = Path()
    private val markerPath = Path()
    private val startLinePath = Path()
    private val markerMatrix = Matrix()
    private val gradientRect = RectF()

    private var fillShader: Shader? = null
    private var shaderTop = Float.NaN
    private var shaderBottom = Float.NaN

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val cx = w * 0.5f
        // Bias the centre upward: the loop foreshortens downward, so a low centre would clip the
        // near edge and leave dead space up top where the labels actually live.
        val cy = h * 0.44f
        val radiusX = w * 0.34f
        val radiusY = h * 0.32f
        val laneTop = cy - radiusY * (1f - perspectiveK)
        val laneBottom = cy + radiusY * (1f + perspectiveK)

        applyDim()
        ensureShader(laneTop, laneBottom, radiusY)

        buildLane(cx, cy, radiusX, radiusY)
        canvas.drawPath(lanePath, fillPaint)
        canvas.drawPath(lanePath, rimPaint)

        drawStartLine(canvas, cx, cy, radiusX, radiusY)
        drawMarker(canvas, cx, cy, radiusX, radiusY)
        drawLabels(canvas, w, h)
    }

    private fun buildLane(cx: Float, cy: Float, radiusX: Float, radiusY: Float) {
        lanePath.reset()
        lanePath.fillType = Path.FillType.EVEN_ODD

        // Outer contour forward, inner contour also forward: two nested closed loops under
        // EVEN_ODD winding punch a genuine hole rather than filling the interior solid.
        var t = 0f
        val step = (2f * PI.toFloat()) / outlineSamples
        for (i in 0..outlineSamples) {
            val sx = projX(cx, radiusX, t, 1f)
            val sy = projY(cy, radiusY, t)
            if (i == 0) lanePath.moveTo(sx, sy) else lanePath.lineTo(sx, sy)
            t += step
        }
        lanePath.close()

        t = 0f
        for (i in 0..outlineSamples) {
            val sx = projX(cx, radiusX, t, innerScale)
            val sy = innerProjY(cy, radiusY, t)
            if (i == 0) lanePath.moveTo(sx, sy) else lanePath.lineTo(sx, sy)
            t += step
        }
        lanePath.close()
    }

    private fun drawStartLine(canvas: Canvas, cx: Float, cy: Float, radiusX: Float, radiusY: Float) {
        // Left-hand straight sits near t = PI; two short bars perpendicular to travel mimic the
        // painted start/finish stripe crossing the lane.
        startLinePaint.strokeWidth = 2f * density
        val bars = 2
        val spacing = 0.012f
        startLinePath.reset()
        for (b in 0 until bars) {
            val t = PI.toFloat() + (b - 0.5f) * spacing * 2f
            val ox = projX(cx, radiusX, t, 1f)
            val oy = projY(cy, radiusY, t)
            val ix = projX(cx, radiusX, t, innerScale)
            val iy = innerProjY(cy, radiusY, t)
            startLinePath.moveTo(ox, oy)
            startLinePath.lineTo(ix, iy)
        }
        canvas.drawPath(startLinePath, startLinePaint)
    }

    private fun drawMarker(canvas: Canvas, cx: Float, cy: Float, radiusX: Float, radiusY: Float) {
        val t = progress * 2f * PI.toFloat()
        val midScale = (1f + innerScale) * 0.5f
        val px = projX(cx, radiusX, t, midScale)
        val py = (projY(cy, radiusY, t) + innerProjY(cy, radiusY, t)) * 0.5f

        val ahead = t + 0.05f
        val ax = projX(cx, radiusX, ahead, midScale)
        val ay = (projY(cy, radiusY, ahead) + innerProjY(cy, radiusY, ahead)) * 0.5f
        val headingDeg = Math.toDegrees(atan2((ay - py).toDouble(), (ax - px).toDouble())).toFloat()

        val pinLen = radiusY * 0.42f
        val headR = pinLen * 0.42f

        markerPath.reset()
        markerPath.moveTo(0f, 0f)
        markerPath.lineTo(-headR * 0.62f, -pinLen + headR)
        markerPath.lineTo(headR * 0.62f, -pinLen + headR)
        markerPath.close()
        markerPath.addCircle(0f, -pinLen + headR, headR, Path.Direction.CW)

        markerMatrix.reset()
        markerMatrix.postRotate(headingDeg + 90f)
        markerMatrix.postTranslate(px, py)

        canvas.save()
        canvas.concat(markerMatrix)
        canvas.drawPath(markerPath, markerBodyPaint)
        canvas.drawCircle(0f, -pinLen + headR, headR * 0.62f, markerInnerPaint)

        // Chevron drawn in the marker's local up-axis; the marker rotation already aligns "up" with
        // the direction of travel, so a fixed up-pointing chevron reads as pointing where you run.
        chevronPaint.strokeWidth = headR * 0.24f
        val chy = -pinLen + headR
        val cw = headR * 0.5f
        canvas.drawLine(-cw, chy + cw * 0.5f, 0f, chy - cw * 0.6f, chevronPaint)
        canvas.drawLine(cw, chy + cw * 0.5f, 0f, chy - cw * 0.6f, chevronPaint)
        canvas.restore()
    }

    /**
     * Lap copy goes in the hole, not the corner.
     *
     * Set outside the loop it collides with whatever the oval is drawn over; the middle of the
     * ring is the one region the figure itself guarantees is empty, and it is where a stadium
     * infield would put the same information.
     */
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastScrimRadius = -1f

    private fun drawLabels(canvas: Canvas, w: Int, h: Int) {
        titlePaint.textSize = h * 0.105f
        subtitlePaint.textSize = h * 0.052f
        val x = w * 0.5f
        val titleY = h * 0.44f + titlePaint.textSize * 0.36f
        val subtitleY = titleY + subtitlePaint.textSize * 1.55f
        // Infield text sits directly on the app underneath. Over a poster wall or an album grid
        // it disappears entirely, so it gets its own soft scrim rather than relying on the
        // translucent lane, which is nowhere near the middle.
        val scrimRadius = h * 0.28f
        if (scrimRadius != lastScrimRadius) {
            lastScrimRadius = scrimRadius
            scrimPaint.shader = RadialGradient(
                x,
                h * 0.44f,
                scrimRadius,
                intArrayOf(0xCC060B10.toInt(), 0x80060B10.toInt(), 0x00060B10),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(x, h * 0.44f, scrimRadius, scrimPaint)
        if (lapTitle.isNotEmpty()) canvas.drawText(lapTitle, x, titleY, titlePaint)
        if (lapSubtitle.isNotEmpty()) canvas.drawText(lapSubtitle, x, subtitleY, subtitlePaint)
    }

    private fun projX(cx: Float, radiusX: Float, t: Float, scale: Float): Float {
        val gx = cos(t) * scale
        val gy = sin(t)
        return cx + gx * (1f + perspectiveK * gy) * radiusX
    }

    private fun projY(cy: Float, radiusY: Float, t: Float): Float {
        return cy + sin(t) * radiusY
    }

    private fun innerProjY(cy: Float, radiusY: Float, t: Float): Float {
        return cy + sin(t) * radiusY * innerScale
    }

    private fun ensureShader(top: Float, bottom: Float, radiusY: Float) {
        if (fillShader == null || top != shaderTop || bottom != shaderBottom) {
            // Translucent, and brightest at the near edge. A flat opaque band reads as a plastic
            // ring lying on the glass; letting the far side sink into whatever is playing
            // underneath is what makes it read as ground receding away from the rider.
            fillShader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(
                    Color.argb(56, 0x6C, 0x64, 0xBE),
                    Color.argb(132, 0x8A, 0x83, 0xDC),
                    Color.argb(214, 0xAD, 0xA6, 0xF6),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            shaderTop = top
            shaderBottom = bottom
            fillPaint.shader = fillShader
            gradientRect.set(0f, top, 0f, bottom)
        }
    }

    private fun applyDim() {
        fillPaint.alpha = scaledAlpha(fillBaseAlpha)
        rimPaint.alpha = scaledAlpha(rimBaseAlpha)
        rimPaint.strokeWidth = 1.5f * density
        startLinePaint.alpha = scaledAlpha(startLineBaseAlpha)
        markerBodyPaint.alpha = scaledAlpha(markerBodyBaseAlpha)
        markerInnerPaint.alpha = scaledAlpha(markerInnerBaseAlpha)
        chevronPaint.alpha = scaledAlpha(chevronBaseAlpha)
        titlePaint.alpha = scaledAlpha(titleBaseAlpha)
        subtitlePaint.alpha = scaledAlpha(subtitleBaseAlpha)
    }

    private fun scaledAlpha(base: Int): Int = (base * dim).toInt().coerceIn(0, 255)

    private fun floorMod(value: Float, mod: Float): Float {
        val r = value % mod
        return if (r < 0f) r + mod else r
    }
}
