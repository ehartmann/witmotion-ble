package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import kotlin.math.sqrt

/**
 * Detects individual shot cycles from a stream of IMU samples.
 *
 * Buffers samples, runs BowMovementAnalyzer incrementally, and emits
 * a [DetectedShot] each time a complete cycle (DRAW->HOLD->RELEASE->FOLLOW_THROUGH)
 * is detected followed by return to idle movement.
 *
 * Note: [BowMovementAnalyzer.detectPhases] treats FOLLOW_THROUGH as a terminal state
 * (it never transitions back to IDLE). This detector therefore monitors raw gyroscope
 * magnitude after follow-through is reached: once movement drops below [idleGyroThreshold]
 * for [idleConfirmSamples] consecutive samples, the shot is considered complete.
 *
 * Thread safety: [onSample] and [flush] are synchronized to allow safe calls from
 * BLE callback and main threads respectively.
 */
class ShotCycleDetector(
    private val analyzer: BowMovementAnalyzer = BowMovementAnalyzer(),
    private val idleGyroThreshold: Float = 5f,
    private val idleConfirmSamples: Int = 10,
    private val maxBufferSize: Int = 500,
    private val onShotDetected: (DetectedShot) -> Unit
) {
    data class DetectedShot(
        val samples: List<ImuSample>,
        val metrics: BowMovementMetrics?
    )

    private val lock = Any()
    private val buffer = mutableListOf<ImuSample>()
    private var shotStartIndex: Int? = null
    private var reachedFollowThrough = false
    private var consecutiveIdleSamples = 0

    fun onSample(sample: ImuSample): Unit = synchronized(lock) {
        buffer.add(sample)

        // Prevent unbounded buffer growth (stuck draw, noisy sensor)
        if (buffer.size > maxBufferSize && shotStartIndex != null && !reachedFollowThrough) {
            reset()
            return
        }

        val phases = analyzer.detectPhases(buffer)
        if (phases.isEmpty()) return

        val lastPhase = phases.last().phase

        // Track shot start on first DRAW
        if (shotStartIndex == null && lastPhase == ShotPhase.DRAW) {
            shotStartIndex = phases.last().startIndex
        }

        // Track follow-through
        if (shotStartIndex != null && lastPhase == ShotPhase.FOLLOW_THROUGH && !reachedFollowThrough) {
            reachedFollowThrough = true
        }

        // After follow-through, check for return to idle via raw gyro magnitude
        if (reachedFollowThrough) {
            val gyroMag = gyroMagnitude(sample)
            if (gyroMag < idleGyroThreshold) {
                consecutiveIdleSamples++
            } else {
                consecutiveIdleSamples = 0
            }

            if (consecutiveIdleSamples >= idleConfirmSamples) {
                emitShot()
            }
        }
    }

    /**
     * Call when stopping recording to emit any in-progress shot
     * that has reached at least FOLLOW_THROUGH.
     * On flush, all samples including the follow-through tail are included.
     */
    fun flush() = synchronized(lock) {
        if (reachedFollowThrough && shotStartIndex != null) {
            consecutiveIdleSamples = 0
            emitShot()
        } else {
            reset()
        }
    }

    private fun emitShot() {
        val start = shotStartIndex ?: return

        // End the shot at the sample just before idle confirmation started
        val endIdx = (buffer.size - 1 - consecutiveIdleSamples).coerceAtLeast(start)

        if (endIdx <= start) {
            reset()
            return
        }

        val shotSamples = buffer.subList(start, endIdx + 1).toList()
        val analysis = analyzer.analyze(shotSamples)

        onShotDetected(DetectedShot(shotSamples, analysis.metrics))

        // Keep remaining samples for potential next shot detection
        val keepFrom = endIdx + 1
        if (keepFrom < buffer.size) {
            val keep = buffer.subList(keepFrom, buffer.size).toList()
            buffer.clear()
            buffer.addAll(keep)
        } else {
            buffer.clear()
        }

        shotStartIndex = null
        reachedFollowThrough = false
        consecutiveIdleSamples = 0
    }

    private fun reset() {
        buffer.clear()
        shotStartIndex = null
        reachedFollowThrough = false
        consecutiveIdleSamples = 0
    }

    private fun gyroMagnitude(sample: ImuSample): Float {
        val g = sample.gyroscope ?: return 0f
        return sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
    }
}
