package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Analyzes bow movement from IMU samples to detect shot phases
 * and compute per-shot metrics (Mantis X8-style scoring).
 *
 * Phase detection uses gyroscope magnitude:
 * - DRAW:   |gyro| > drawThreshold (bow being drawn)
 * - HOLD:   |gyro| < holdThreshold for > minHoldMs (stable aiming)
 * - RELEASE: |accel_spike| > releaseThreshold (string departure)
 * - FOLLOW_THROUGH: releaseDetectedMs + followThroughMs
 *
 * Scoring (0-100):
 * - Hold score: based on gyroscope std dev (lower = better)
 * - Release score: based on acceleration spike magnitude (lower = cleaner)
 * - Follow-through score: based on return to hold position
 */
public class BowMovementAnalyzer(
    internal val holdThreshold: Float = 5f,        // °/s — below this = hold
    internal val drawThreshold: Float = 20f,        // °/s — above this = drawing
    internal val releaseAccelThreshold: Float = 2f, // g — spike above this = release
    internal val minHoldMs: Long = 500,             // minimum hold time to confirm
    private val followThroughMs: Long = 500,       // ms after release
    // At release the gyro often crosses drawThreshold one or two samples before the accel spike
    // (seen on the WT9011DCL at ~25 Hz): look this far ahead for the spike before calling it a draw
    internal val releaseWindowMs: Long = 200,
    /** The acceleration jump of a release is measured over this window, whatever the rate (see [accelJump]). */
    internal val jumpWindowMs: Long = 20,
    /** A raise starts with the gravity at least this far from the aim (°), see [shotBoundaries]. */
    internal val raiseMinTiltDeg: Float = 20f,
    /** A raise ends when the gravity comes within this angle of the aim (°). */
    internal val raiseEndTiltDeg: Float = 10f,
    /** Longest pause between a raise and the draw that still keeps the raise. */
    internal val raisePauseMaxMs: Long = 2_000
) {

    public data class ShotAnalysis(
        val phases: List<PhaseSegment>,
        val metrics: BowMovementMetrics?
    )

    public data class PhaseSegment(
        val phase: ShotPhase,
        val startIndex: Int,
        val endIndex: Int
    )

    /**
     * Analyze a sequence of IMU samples to detect phases and compute metrics.
     * Samples must be sorted by timestamp.
     */
    public fun analyze(samples: List<ImuSample>): ShotAnalysis {
        if (samples.size < 10) return ShotAnalysis(emptyList(), null)

        val phases = detectPhases(samples)
        val metrics = computeMetrics(samples, phases)

        return ShotAnalysis(phases, metrics)
    }

    /**
     * Detect shot phases from gyroscope and accelerometer data.
     */
    public fun detectPhases(samples: List<ImuSample>): List<PhaseSegment> {
        val segments = mutableListOf<PhaseSegment>()
        var currentPhase = ShotPhase.IDLE
        var phaseStart = 0
        var holdStartTime: Long? = null
        var releaseTime: Long? = null

        for (i in samples.indices) {
            val s = samples[i]
            val gyroMag = gyroMagnitude(s)
            val newPhase: ShotPhase

            when (currentPhase) {
                ShotPhase.IDLE, ShotPhase.RAISE -> {
                    newPhase = if (gyroMag > drawThreshold) ShotPhase.DRAW else ShotPhase.IDLE
                }
                ShotPhase.DRAW -> {
                    if (gyroMag < holdThreshold) {
                        holdStartTime = s.timestamp
                        newPhase = ShotPhase.HOLD
                    } else {
                        newPhase = ShotPhase.DRAW
                    }
                }
                ShotPhase.HOLD -> {
                    // A release is only accepted once the hold has been sustained for
                    // at least minHoldMs. This is what the class doc always promised
                    // ("HOLD: |gyro| < holdThreshold for > minHoldMs") but the code
                    // never enforced: previously a momentary mid-draw pause (one
                    // sub-threshold sample) followed by any >releaseAccel jolt flipped
                    // straight to RELEASE, emitting a bogus shot cycle.
                    val holdConfirmed = holdStartTime != null &&
                        s.timestamp - holdStartTime >= minHoldMs
                    if (holdConfirmed && isReleaseStart(samples, i)) {
                        releaseTime = s.timestamp
                        newPhase = ShotPhase.RELEASE
                    } else if (gyroMag > drawThreshold) {
                        // Movement resumed — false hold, back to draw
                        holdStartTime = null
                        newPhase = ShotPhase.DRAW
                    } else {
                        // Still holding (settling while within minHoldMs); ignore any
                        // acceleration spike until the hold is confirmed.
                        newPhase = ShotPhase.HOLD
                    }
                }
                ShotPhase.RELEASE -> {
                    newPhase = if (releaseTime != null && s.timestamp - releaseTime > followThroughMs) {
                        ShotPhase.FOLLOW_THROUGH
                    } else {
                        ShotPhase.RELEASE
                    }
                }
                ShotPhase.FOLLOW_THROUGH -> {
                    newPhase = ShotPhase.FOLLOW_THROUGH
                }
            }

            if (newPhase != currentPhase) {
                if (currentPhase != ShotPhase.IDLE || segments.isNotEmpty()) {
                    segments.add(PhaseSegment(currentPhase, phaseStart, i - 1))
                }
                currentPhase = newPhase
                phaseStart = i
            }
        }

        // Close last segment
        if (phaseStart < samples.size) {
            segments.add(PhaseSegment(currentPhase, phaseStart, samples.size - 1))
        }

        return segments
    }

    /**
     * Where the shot of [samples] starts its raise, draw, hold and release; null without a DRAW, a
     * HOLD and then a RELEASE. The tilt of a sample is the angle between its gravity (the mean
     * acceleration over the 100 ms ending at it) and the aim (the gravity over the first second of
     * the hold). A raise starts at least [raiseMinTiltDeg] off the aim, after a rest, and ends within
     * [raiseEndTiltDeg] of it: inside the DRAW segment when the archer raises and draws in one
     * movement, or before the DRAW, at most [raisePauseMaxMs] earlier. A DRAW segment that begins
     * more than [raiseEndTiltDeg] off the aim has its draw start where the tilt comes within it, even
     * when the raise start is unknown or the movement began under [raiseMinTiltDeg].
     */
    public fun shotBoundaries(samples: List<ImuSample>, phases: List<PhaseSegment> = detectPhases(samples)): ShotBoundaries? {
        val r = phases.indexOfFirst { it.phase == ShotPhase.RELEASE }
        val hold = phases.getOrNull(r - 1)?.takeIf { it.phase == ShotPhase.HOLD } ?: return null
        val draw = phases.getOrNull(r - 2)?.takeIf { it.phase == ShotPhase.DRAW } ?: return null

        val holdStartMs = samples[hold.startIndex].timestamp
        val aim = accelSum(samples, (hold.startIndex..hold.endIndex).takeWhile { samples[it].timestamp - holdStartMs < 1_000 }, unit = true)
        fun tilt(i: Int) =
            angleDeg(accelSum(samples, (i downTo 0).takeWhile { samples[i].timestamp - samples[it].timestamp < 100 }, unit = false), aim)
        fun moving(i: Int) = gyroMagnitude(samples[i]) >= holdThreshold

        val blended = tilt(draw.startIndex) > raiseEndTiltDeg
        val raiseEnd = if (blended) (draw.startIndex until hold.startIndex).firstOrNull { tilt(it) <= raiseEndTiltDeg } else null
        var s = draw.startIndex
        if (!blended) {
            val drawStartMs = samples[s].timestamp
            while (s > 0 && !moving(s - 1) && drawStartMs - samples[s - 1].timestamp <= raisePauseMaxMs) s--
        }
        while (s > 0 && moving(s - 1)) s--
        // s == 0: the samples begin during the raise, whose start is unknown
        val raised = s > 0 && tilt(s) >= raiseMinTiltDeg && (!blended || raiseEnd != null)

        return ShotBoundaries(
            raiseStartIndex = s.takeIf { raised },
            drawStartIndex = raiseEnd ?: draw.startIndex,
            holdStartIndex = hold.startIndex,
            releaseIndex = phases[r].startIndex
        )
    }

    /**
     * Compute per-shot metrics from the detected phases.
     */
    public fun computeMetrics(samples: List<ImuSample>, phases: List<PhaseSegment>): BowMovementMetrics? {
        val holdSegment = phases.find { it.phase == ShotPhase.HOLD } ?: return null
        val releaseSegment = phases.find { it.phase == ShotPhase.RELEASE }
        val ftSegment = phases.find { it.phase == ShotPhase.FOLLOW_THROUGH }

        // Hold metrics
        val holdSamples = samples.subList(holdSegment.startIndex, holdSegment.endIndex + 1)
        val holdGyroValues = holdSamples.mapNotNull { it.gyroscope?.let { g -> gyroMagnitude(it) } }
        val holdStdDev = stdDev(holdGyroValues)
        val holdScore = (100f - holdStdDev * 20f).coerceIn(0f, 100f)

        val holdTime = if (holdSamples.size >= 2) {
            holdSamples.last().timestamp - holdSamples.first().timestamp
        } else 0L

        // Cant angle (mean roll during hold)
        val cantAngle = circularMean(holdSamples.mapNotNull { it.euler?.get(0) })
            .let { if (it.isNaN()) 0f else it }

        // Release metrics
        val releaseScore = if (releaseSegment != null) {
            // A clean release has a sharp, brief spike then calm: 10 points per 20 ms above half the
            // release threshold, whatever the sampling rate
            var msAbove = 0L
            for (i in releaseSegment.startIndex..minOf(releaseSegment.endIndex, samples.lastIndex)) {
                if (i > 0 && accelJump(samples, i) > releaseAccelThreshold * 0.5f) {
                    msAbove += minOf(samples[i].timestamp - samples[i - 1].timestamp, jumpWindowMs)
                }
            }
            (100f - msAbove * 0.5f).coerceIn(0f, 100f)
        } else 0f

        // Follow-through metrics
        val ftScore = if (ftSegment != null) {
            val ftSamples = samples.subList(ftSegment.startIndex,
                minOf(ftSegment.endIndex + 1, samples.size))
            val ftGyroValues = ftSamples.mapNotNull { s -> s.gyroscope?.let { gyroMagnitude(s) } }
            val ftStdDev = stdDev(ftGyroValues)
            // Low movement after shot = good follow-through
            (100f - ftStdDev * 15f).coerceIn(0f, 100f)
        } else 0f

        val totalScore = holdScore * 0.4f + releaseScore * 0.4f + ftScore * 0.2f

        return BowMovementMetrics(
            holdScore = holdScore,
            releaseScore = releaseScore,
            followThroughScore = ftScore,
            totalScore = totalScore,
            holdTimeMs = holdTime,
            cantAngle = cantAngle,
            holdStdDev = holdStdDev
        )
    }

    // --- Utilities ---

    /** Whether sample [i] starts a release: a spike now, or movement with a spike just ahead. */
    internal fun isReleaseStart(samples: List<ImuSample>, i: Int): Boolean =
        accelJump(samples, i) > releaseAccelThreshold ||
            (gyroMagnitude(samples[i]) > drawThreshold && spikeWithin(samples, i, releaseWindowMs))

    /** Whether an acceleration spike happens from sample [from] to [windowMs] after it. */
    private fun spikeWithin(samples: List<ImuSample>, from: Int, windowMs: Long): Boolean =
        maxJumpWithin(samples, from, windowMs) > releaseAccelThreshold

    /** Largest 20 ms acceleration jump, in g, from [releaseIndex] over [releaseWindowMs]: the release shock of a shot. */
    public fun releaseJumpG(samples: List<ImuSample>, releaseIndex: Int): Float =
        maxJumpWithin(samples, releaseIndex, releaseWindowMs)

    /** Largest [accelJump] of the samples from [from] to [windowMs] after it. */
    internal fun maxJumpWithin(samples: List<ImuSample>, from: Int, windowMs: Long): Float {
        val start = samples[from].timestamp
        var largest = 0f
        var j = from
        while (j < samples.size && samples[j].timestamp - start <= windowMs) {
            largest = maxOf(largest, accelJump(samples, j))
            j++
        }
        return largest
    }

    /**
     * Largest change of the acceleration vector between sample [i] and any sample of the
     * [jumpWindowMs] before it. Between consecutive samples the jump shrinks with the rate (at
     * 200 Hz a release that ramps over 10 ms never jumps 2 g in 5 ms); over a fixed window it
     * does not.
     */
    internal fun accelJump(samples: List<ImuSample>, i: Int): Float {
        val current = samples[i]
        var largest = 0f
        var j = i - 1
        while (j >= 0 && current.timestamp - samples[j].timestamp <= jumpWindowMs) {
            largest = maxOf(largest, accelSpikeMagnitude(current, samples[j]))
            j--
        }
        // The previous sample counts even past the window, as before: a gap in the link must not hide a release
        if (j == i - 1 && i > 0) largest = accelSpikeMagnitude(current, samples[i - 1])
        return largest
    }

    internal fun gyroMagnitude(sample: ImuSample): Float {
        val g = sample.gyroscope ?: return 0f
        return sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
    }

    private fun accelSpikeMagnitude(current: ImuSample, previous: ImuSample?): Float {
        val a = current.acceleration ?: return 0f
        val p = previous?.acceleration ?: return 0f
        val dx = a[0] - p[0]
        val dy = a[1] - p[1]
        val dz = a[2] - p[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Sum of the accelerations of [indices], each scaled to 1 g when [unit]; samples without one add nothing. */
    private fun accelSum(samples: List<ImuSample>, indices: List<Int>, unit: Boolean): FloatArray {
        val sum = FloatArray(3)
        for (i in indices) {
            val a = samples[i].acceleration ?: continue
            val n = if (unit) norm(a) else 1f
            if (n == 0f) continue
            for (k in 0..2) sum[k] += a[k] / n
        }
        return sum
    }

    /** Angle between [a] and [b], in degrees; NaN when either is zero. */
    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (norm(a) * norm(b))
        return acos(cos.coerceIn(-1f, 1f)) * 180f / PI.toFloat()
    }

    private fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun stdDev(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return sqrt(variance)
    }
}
