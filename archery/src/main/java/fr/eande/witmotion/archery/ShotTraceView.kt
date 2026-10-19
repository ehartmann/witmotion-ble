package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 2D circular trace visualization for bow movement during a shot.
 * Shows pitch (vertical) vs yaw (horizontal) movement, color-coded by shot phase.
 *
 * Similar to Mantis X8's trace display: a centered bullseye with the
 * bow movement path drawn as colored dots.
 *
 * Colors:
 *   Gray  = DRAW
 *   Blue  = HOLD (aiming)
 *   Red   = RELEASE
 *   Green = FOLLOW-THROUGH
 */
class ShotTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TracePoint(
        val yaw: Float,      // horizontal movement (°)
        val pitch: Float,    // vertical movement (°)
        val phase: ShotPhase
    )

    private var tracePoints: List<TracePoint> = emptyList()
    private var metrics: BowMovementMetrics? = null
    private var visiblePointCount: Int = Int.MAX_VALUE
    private var animator: ValueAnimator? = null

    private val bgPaint = Paint().apply { color = Color.argb(220, 0, 0, 0); style = Paint.Style.FILL }
    private val ringPaint = Paint().apply { color = Color.argb(40, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val crosshairPaint = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 1f }
    private val labelPaint = Paint().apply { color = Color.WHITE; textSize = 24f; isAntiAlias = true }
    private val scorePaint = Paint().apply { color = Color.WHITE; textSize = 32f; isAntiAlias = true; textAlign = Paint.Align.CENTER }

    private val phaseColors = mapOf(
        ShotPhase.IDLE to Color.DKGRAY,
        ShotPhase.DRAW to Color.rgb(158, 158, 158),     // Gray
        ShotPhase.HOLD to Color.rgb(33, 150, 243),      // Blue
        ShotPhase.RELEASE to Color.rgb(244, 67, 54),    // Red
        ShotPhase.FOLLOW_THROUGH to Color.rgb(76, 175, 80) // Green
    )

    private val dotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    init {
        // Tap to replay animation
        setOnClickListener { startReplay() }
    }

    fun setTrace(points: List<TracePoint>, metrics: BowMovementMetrics? = null) {
        this.tracePoints = points
        this.metrics = metrics
        this.visiblePointCount = points.size
        invalidate()
    }

    /**
     * Replay the trace with animation: points appear one by one over [durationMs].
     */
    fun startReplay(durationMs: Long = 3000L) {
        animator?.cancel()
        if (tracePoints.isEmpty()) return

        animator = ValueAnimator.ofInt(0, tracePoints.size).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                visiblePointCount = anim.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    fun stopReplay() {
        animator?.cancel()
        visiblePointCount = tracePoints.size
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Stop a running replay so its update listener (which retains this view
        // and keeps invalidating it) doesn't outlive the detached view.
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 40f

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Concentric rings (target-style)
        for (i in 1..5) {
            canvas.drawCircle(cx, cy, radius * i / 5f, ringPaint)
        }

        // Crosshair
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crosshairPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crosshairPaint)

        if (tracePoints.isEmpty()) {
            canvas.drawText("No IMU data", cx, cy, scorePaint)
            return
        }

        // Scale: find max deviation to fit all points
        val maxDev = tracePoints.maxOf { maxOf(kotlin.math.abs(it.yaw), kotlin.math.abs(it.pitch)) }
            .coerceAtLeast(1f)
        val scale = radius / maxDev

        // Draw trace dots (up to visiblePointCount for animation)
        val pointsToDraw = if (visiblePointCount < tracePoints.size) tracePoints.subList(0, visiblePointCount) else tracePoints
        for (point in pointsToDraw) {
            val x = cx + point.yaw * scale
            val y = cy - point.pitch * scale // pitch up = screen up
            dotPaint.color = phaseColors[point.phase] ?: Color.WHITE
            canvas.drawCircle(x, y, 3f, dotPaint)
        }

        // Legend (bottom)
        var legendX = 16f
        val legendY = height - 12f
        for ((phase, color) in phaseColors) {
            if (phase == ShotPhase.IDLE) continue
            dotPaint.color = color
            canvas.drawCircle(legendX + 6f, legendY - 6f, 5f, dotPaint)
            val label = phase.name.lowercase().replaceFirstChar { it.uppercase() }
            canvas.drawText(label, legendX + 16f, legendY, labelPaint)
            legendX += labelPaint.measureText(label) + 28f
        }

        // Score (top-right)
        val m = metrics
        if (m != null) {
            scorePaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Score: %.0f".format(m.totalScore), width - 16f, 36f, scorePaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Hold: %.0f  Release: %.0f  FT: %.0f".format(
                m.holdScore, m.releaseScore, m.followThroughScore), width - 16f, 64f, labelPaint)
            canvas.drawText("Cant: %.1f°  Hold: %.1fs".format(
                m.cantAngle, m.holdTimeMs / 1000f), width - 16f, 88f, labelPaint)
            scorePaint.textAlign = Paint.Align.CENTER
            labelPaint.textAlign = Paint.Align.LEFT
        }
    }

    companion object {
        /**
         * Convert ImuSamples + phase segments into TracePoints for this view.
         */
        fun samplesToTrace(
            samples: List<ImuSample>,
            phases: List<BowMovementAnalyzer.PhaseSegment>
        ): List<TracePoint> {
            // Build a phase lookup: index → ShotPhase
            val phaseMap = IntArray(samples.size) { ShotPhase.IDLE.ordinal }
            for (segment in phases) {
                for (i in segment.startIndex..minOf(segment.endIndex, samples.size - 1)) {
                    phaseMap[i] = segment.phase.ordinal
                }
            }

            // Find hold center (reference point for relative movement)
            val holdSamples = samples.filterIndexed { i, _ -> phaseMap[i] == ShotPhase.HOLD.ordinal }
            val refYaw = holdSamples.mapNotNull { it.euler?.get(2) }.average().toFloat()
                .let { if (it.isNaN()) 0f else it }
            val refPitch = holdSamples.mapNotNull { it.euler?.get(1) }.average().toFloat()
                .let { if (it.isNaN()) 0f else it }

            return samples.mapIndexed { i, sample ->
                val euler = sample.euler
                TracePoint(
                    yaw = (euler?.get(2) ?: 0f) - refYaw,
                    pitch = (euler?.get(1) ?: 0f) - refPitch,
                    phase = ShotPhase.entries[phaseMap[i]]
                )
            }
        }
    }
}
