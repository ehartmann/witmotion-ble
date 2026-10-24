package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import kotlin.math.abs
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
    internal val jumpWindowMs: Long = 20
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
                ShotPhase.IDLE -> {
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
        val cantAngle = holdSamples.mapNotNull { it.euler?.get(0) }.average().toFloat()
            .let { if (it.isNaN()) 0f else it }

        // Release metrics
        val releaseScore = if (releaseSegment != null) {
            // A clean release has a sharp, brief spike then calm: 10 points per 20 ms above half the
            // release threshold, whatever the sampling rate
            var msAbove = 0L
            for (i in releaseSegment.startIndex..minOf(releaseSegment.endIndex, samples.lastIndex)) {
                if (i > 0 && accelJump(samples, i) > releaseAccelThreshold * 0.5f) {
                    msAbove += samples[i].timestamp - samples[i - 1].timestamp
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

    private fun stdDev(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return sqrt(variance)
    }

    private fun gyroMagnitude(imuSample: ImuSample, unused: Unit? = null): Float = gyroMagnitude(imuSample)
}
