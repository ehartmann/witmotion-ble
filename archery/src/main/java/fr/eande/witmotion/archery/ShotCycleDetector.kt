package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample

/**
 * Detects individual shot cycles from a stream of IMU samples.
 *
 * A shot runs from the start of the draw to [afterReleaseMs] after the release (a typical shot:
 * about 6 s of draw and aim, then 3 s of follow-through), and is emitted as soon as that
 * follow-through time has elapsed. [BowMovementAnalyzer] finds the phases
 * (DRAW -> HOLD -> RELEASE -> FOLLOW_THROUGH) over the buffered samples.
 *
 * All limits are durations, so the behaviour does not depend on the sensor rate. While no draw is
 * in progress only the last [idleKeepMs] are kept, which bounds memory and the phase-detection
 * cost over a long session; a draw that reaches no release within [maxDrawMs] is dropped (stuck
 * draw, bow handled without shooting).
 *
 * Thread safety: [onSample] and [flush] are synchronized to allow safe calls from
 * BLE callback and main threads respectively.
 */
class ShotCycleDetector(
    private val analyzer: BowMovementAnalyzer = BowMovementAnalyzer(),
    private val afterReleaseMs: Long = 3_000,
    private val maxDrawMs: Long = 30_000,
    private val idleKeepMs: Long = 2_000,
    private val onShotDetected: (DetectedShot) -> Unit
) {
    data class DetectedShot(
        val samples: List<ImuSample>,
        val metrics: BowMovementMetrics?
    )

    companion object {
        /**
         * Runs detection over a finished recording, including a shot cut short by its end.
         * A Sequence lets callers stream a long recording instead of loading it whole.
         */
        fun detectAll(
            samples: Sequence<ImuSample>,
            analyzer: BowMovementAnalyzer = BowMovementAnalyzer(),
            afterReleaseMs: Long = 3_000
        ): List<DetectedShot> {
            val shots = mutableListOf<DetectedShot>()
            val detector = ShotCycleDetector(analyzer = analyzer, afterReleaseMs = afterReleaseMs) { shots += it }
            samples.forEach(detector::onSample)
            detector.flush()
            return shots
        }
    }

    private val lock = Any()
    private val buffer = ArrayList<ImuSample>()
    private var shotStartIndex: Int? = null
    private var releaseSeen = false

    /** Samples held right now; for tests of the memory bound. */
    internal val bufferedSamples: Int get() = synchronized(lock) { buffer.size }

    fun onSample(sample: ImuSample): Unit = synchronized(lock) {
        buffer.add(sample)
        if (shotStartIndex == null) {
            val keepFrom = buffer.indexOfFirst { sample.timestamp - it.timestamp <= idleKeepMs }
            if (keepFrom > 0) buffer.subList(0, keepFrom).clear()
        }

        val phases = analyzer.detectPhases(buffer)
        val start = shotStartIndex
            ?: phases.firstOrNull { it.phase == ShotPhase.DRAW }?.startIndex?.also { shotStartIndex = it }
            ?: return

        val release = phases.firstOrNull { it.phase == ShotPhase.RELEASE && it.startIndex >= start }
        if (release == null) {
            if (sample.timestamp - buffer[start].timestamp > maxDrawMs) reset()
            return
        }
        releaseSeen = true
        if (sample.timestamp - buffer[release.startIndex].timestamp >= afterReleaseMs) emitShot(start)
    }

    /**
     * Call when stopping recording: emits the shot in progress if its release was seen, with
     * whatever follow-through was recorded.
     */
    fun flush() = synchronized(lock) {
        val start = shotStartIndex
        if (start != null && releaseSeen) emitShot(start) else reset()
    }

    private fun emitShot(start: Int) {
        val shotSamples = buffer.subList(start, buffer.size).toList()
        reset()
        onShotDetected(DetectedShot(shotSamples, analyzer.analyze(shotSamples).metrics))
    }

    private fun reset() {
        buffer.clear()
        shotStartIndex = null
        releaseSeen = false
    }
}
