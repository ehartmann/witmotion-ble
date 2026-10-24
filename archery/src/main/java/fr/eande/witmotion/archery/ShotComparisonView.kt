package fr.eande.witmotion.archery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * Overlays the hold and release of several shots on one 2D plot (pitch vs yaw, relative to each
 * shot's hold center), one color per shot, with a ring at each release point: tight, overlapping
 * traces and grouped rings mean consistent shots. Shows the last [MAX_SHOTS] shots it is given.
 * Draws no localizable text besides the callers' labels; see [ShotComparison] for the numbers.
 */
public class ShotComparisonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    public companion object {
        public const val MAX_SHOTS: Int = 8
    }

    public data class ShotTrace(
        val label: String,
        val points: List<ShotTraceView.TracePoint>
    )

    internal var shots: List<ShotTrace> = emptyList()
        private set

    private val bgPaint = Paint().apply { color = Color.argb(220, 0, 0, 0); style = Paint.Style.FILL }
    private val ringPaint = Paint().apply { color = Color.argb(40, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val crosshairPaint = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 1f }
    private val labelPaint = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true }
    private val dotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val releasePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }

    private val shotColors = intArrayOf(
        Color.rgb(33, 150, 243),   // Blue
        Color.rgb(244, 67, 54),    // Red
        Color.rgb(76, 175, 80),    // Green
        Color.rgb(255, 152, 0),    // Orange
        Color.rgb(156, 39, 176),   // Purple
        Color.rgb(0, 188, 212),    // Cyan
        Color.rgb(255, 235, 59),   // Yellow
        Color.rgb(121, 85, 72)     // Brown
    )

    public fun setShots(traces: List<ShotTrace>) {
        shots = traces.takeLast(MAX_SHOTS)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 40f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        for (i in 1..5) canvas.drawCircle(cx, cy, radius * i / 5f, ringPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crosshairPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crosshairPaint)

        val aimPoints = shots.flatMap { shot -> shot.points.filter { it.phase == ShotPhase.HOLD || it.phase == ShotPhase.RELEASE } }
        if (aimPoints.isEmpty()) return

        // One scale for all shots, so their sizes compare
        val maxDev = aimPoints.maxOf { maxOf(abs(it.yaw), abs(it.pitch)) }.coerceAtLeast(1f)
        val scale = radius / maxDev

        shots.forEachIndexed { i, shot ->
            val color = shotColors[i % shotColors.size]
            dotPaint.color = Color.argb(180, Color.red(color), Color.green(color), Color.blue(color))
            for (point in shot.points) {
                if (point.phase != ShotPhase.HOLD && point.phase != ShotPhase.RELEASE) continue
                canvas.drawCircle(cx + point.yaw * scale, cy - point.pitch * scale, 2.5f, dotPaint)
            }
            ShotComparison.releasePoint(shot.points)?.let {
                releasePaint.color = color
                canvas.drawCircle(cx + it.yaw * scale, cy - it.pitch * scale, 7f, releasePaint)
            }
        }

        // Legend (bottom)
        var legendX = 8f
        val legendY = height - 8f
        shots.forEachIndexed { i, shot ->
            dotPaint.color = shotColors[i % shotColors.size]
            canvas.drawCircle(legendX + 5f, legendY - 5f, 4f, dotPaint)
            canvas.drawText(shot.label, legendX + 14f, legendY, labelPaint)
            legendX += labelPaint.measureText(shot.label) + 24f
        }
    }
}
