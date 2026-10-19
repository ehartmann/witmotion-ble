package fr.eande.witmotion.archery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import fr.eande.witmotion.ImuSample
import kotlin.math.abs

/**
 * Live aiming view, Mantis X8 style: a target centered on the mean aim of the last [windowMs],
 * the bow's pitch (vertical) and yaw (horizontal) movement drawn as a trail that fades with age,
 * and the current position as a dot. The scale adapts to the movement, never below
 * [MIN_RANGE_DEGREES] so a steady hold is not magnified into noise.
 */
class LiveAimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MIN_RANGE_DEGREES = 0.5f
    }

    var windowMs: Long = LiveAim.DEFAULT_WINDOW_MS

    internal var trail: List<LiveAim.Point> = emptyList()
        private set

    private val bgPaint = Paint().apply { color = Color.argb(220, 0, 0, 0); style = Paint.Style.FILL }
    private val ringPaint = Paint().apply { color = Color.argb(40, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val crosshairPaint = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 1f }
    private val trailPaint = Paint().apply { strokeWidth = 3f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
    private val dotPaint = Paint().apply { color = Color.rgb(244, 67, 54); style = Paint.Style.FILL; isAntiAlias = true }
    private val labelPaint = Paint().apply { color = Color.argb(160, 255, 255, 255); textSize = 22f; isAntiAlias = true }

    fun setSamples(samples: List<ImuSample>) {
        trail = LiveAim.trail(samples, windowMs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 24f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        for (i in 1..5) canvas.drawCircle(cx, cy, radius * i / 5f, ringPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crosshairPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crosshairPaint)
        if (trail.isEmpty()) return

        val range = maxOf(MIN_RANGE_DEGREES, trail.maxOf { maxOf(abs(it.yaw), abs(it.pitch)) })
        val scale = radius / range
        fun x(p: LiveAim.Point) = cx + p.yaw * scale
        fun y(p: LiveAim.Point) = cy - p.pitch * scale

        trail.zipWithNext { a, b ->
            val freshness = 1f - (b.ageMs.toFloat() / windowMs).coerceIn(0f, 1f)
            trailPaint.color = Color.argb((40 + 215 * freshness).toInt(), 33, 150, 243)
            canvas.drawLine(x(a), y(a), x(b), y(b), trailPaint)
        }
        val current = trail.last()
        canvas.drawCircle(x(current), y(current), 8f, dotPaint)
        canvas.drawText("±%.1f°".format(range), 8f, 28f, labelPaint)
    }
}
