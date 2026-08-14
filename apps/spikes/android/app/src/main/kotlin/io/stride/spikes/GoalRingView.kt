package io.stride.spikes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The goal ring: a dotted circular track with an arc swept over it for progress, and the headline
 * figure in the middle.
 *
 * The dots are drawn rather than a faint stroked circle because a continuous grey ring reads as an
 * unfilled bar the rider keeps checking; a dotted one reads as a distance, and the swept arc has
 * something to measure itself against.
 */
class GoalRingView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(126, 141, 176)
        style = Paint.Style.FILL
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 142, 232)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(178, 190, 214)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.18f
    }
    private val figurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val suffixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(206, 214, 234)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.14f
    }
    private val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 165, 192)
        textAlign = Paint.Align.CENTER
    }

    private val arcBounds = RectF()

    // Cached so repeated dim writes fade against the original value instead of compounding.
    private val baseAlphas = listOf(
        dotPaint, arcPaint, titlePaint, figurePaint, suffixPaint, captionPaint, footPaint,
    ).map { it.alpha }

    /** Progress across the goal, 0f..1f. Values outside are clamped. */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /** Small heading above the figure, e.g. "DISTANCE GOAL". */
    var title: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Line under the figure, e.g. "COMPLETE". */
    var caption: String = "COMPLETE"
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Optional line below the ring's centre block, e.g. an ETA. Blank hides it. */
    var footnote: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Opacity multiplier 0f..1f applied to everything drawn. */
    var dim: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scrimRadius = -1f

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        listOf(dotPaint, arcPaint, titlePaint, figurePaint, suffixPaint, captionPaint, footPaint)
            .forEachIndexed { index, paint ->
                paint.alpha = (baseAlphas[index] * dim).toInt().coerceIn(0, 255)
            }

        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f - 6f * density
        if (radius <= 0f) return

        // The ring floats over whatever app is underneath, and a film poster or album grid will
        // happily put white text on white. A soft radial scrim, fading out well before the dots,
        // buys the figure its contrast back without drawing a visible plate around it.
        if (scrimRadius != radius) {
            scrimRadius = radius
            scrimPaint.shader = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(0xE6060B10.toInt(), 0xB3060B10.toInt(), 0x00060B10),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        scrimPaint.alpha = (235 * dim).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, scrimPaint)

        val dotRadius = 2.1f * density
        val dotCount = 64
        for (i in 0 until dotCount) {
            // Start at twelve o'clock so the swept arc and the dots share an origin the eye finds.
            val angle = (-Math.PI / 2.0) + (2.0 * Math.PI * i / dotCount)
            canvas.drawCircle(
                cx + (radius * cos(angle)).toFloat(),
                cy + (radius * sin(angle)).toFloat(),
                dotRadius,
                dotPaint,
            )
        }

        if (progress > 0f) {
            arcPaint.strokeWidth = 5f * density
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(arcBounds, -90f, 360f * progress, false, arcPaint)
        }

        titlePaint.textSize = 15f * density
        figurePaint.textSize = 58f * density
        suffixPaint.textSize = 22f * density
        captionPaint.textSize = 16f * density
        footPaint.textSize = 15f * density

        val percent = (progress * 100f).toInt().toString()
        // The figure is centred on the number alone, then the unit is hung off its right edge, so
        // the number stays put as it grows from one digit to three instead of sliding sideways.
        val figureWidth = figurePaint.measureText(percent)
        val figureBaseline = cy + 18f * density

        if (title.isNotEmpty()) {
            canvas.drawText(title, cx, figureBaseline - 62f * density, titlePaint)
        }
        canvas.drawText(percent, cx, figureBaseline, figurePaint)
        canvas.drawText("%", cx + figureWidth / 2f + 6f * density, figureBaseline - 26f * density, suffixPaint)
        if (caption.isNotEmpty()) {
            canvas.drawText(caption, cx, figureBaseline + 30f * density, captionPaint)
        }
        if (footnote.isNotEmpty()) {
            canvas.drawText(footnote, cx, figureBaseline + 56f * density, footPaint)
        }
    }
}
