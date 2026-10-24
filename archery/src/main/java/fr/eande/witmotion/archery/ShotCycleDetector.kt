package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample

/**
 * Detects individual shot cycles from a stream of IMU samples.
 *
 * A shot runs from the start of the last draw before the release (earlier draw/hold back and forth,
 * handling the bow, is left out) to [afterReleaseMs] after the release (a typical shot: about 6 s
 * of draw and aim, then 3 s of follow-through), and is emitted as soon as that follow-through
 * time has elapsed. [BowMovementAnalyzer] finds the phases
 * (DRAW -> HOLD -> RELEASE -> FOLLOW_THROUGH) over the buffered samples.
 *
 * All limits are durations, so the behaviour does not depend on the sensor rate. While no draw is
 * in progress only the last [idleKeepMs] are kept, which bounds memory and the phase-detection
 * cost over a long session; a draw that reaches no release within [maxDrawMs] is dropped (stuck
 * draw, bow handled without shooting).
 *
 * Thread safety: [onSample] and [flush] are synchronized to allow safe calls from
 * BLE callback and main threads respectively. `onShotDetected` is called on the thread that
 * completed the shot (the BLE thread for [onSample]), after the lock is released: a slow
 * consumer delays that thread but may call back into the detector from another thread.
 */
public class ShotCycleDetector(
    private val analyzer: BowMovementAnalyzer = BowMovementAnalyzer(),
    private val afterReleaseMs: Long = 3_000,
    private val maxDrawMs: Long = 30_000,
    private val idleKeepMs: Long = 2_000,
    private val onShotDetected: (DetectedShot) -> Unit
) {
    public data class DetectedShot(
        val samples: List<ImuSample>,
        val metrics: BowMovementMetrics?
    )

    public companion object {
        /**
         * Runs detection over a finished recording, including a shot cut short by its end.
         * A Sequence lets callers stream a long recording instead of loading it whole.
         */
        public fun detectAll(
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

    /** Sees the buffer when a draw window closes, so [DetectionDiagnostics] can explain it; never changes the detection. */
    internal interface Observer {
        /** A shot is emitted from buffer index [from]. */
        fun shot(buffer: List<ImuSample>, from: Int)

        /** The window is dropped up to buffer index [upTo] (exclusive); [stuck] when it gave up for [maxDrawMs]. */
        fun dropped(buffer: List<ImuSample>, upTo: Int, stuck: Boolean)
    }

    internal var observer: Observer? = null

    private val lock = Any()
    private val buffer = ArrayList<ImuSample>()
    private var shotStartIndex: Int? = null
    private var releaseSeen = false

    /** Samples held right now; for tests of the memory bound. */
    internal val bufferedSamples: Int get() = synchronized(lock) { buffer.size }

    public fun onSample(sample: ImuSample) {
        synchronized(lock) { process(sample) }?.let(onShotDetected)
    }

    private fun process(sample: ImuSample): DetectedShot? {
        // timestamps restart from 0 after ImuSensorManager.resetRecordingTimestamp
        if (buffer.isNotEmpty() && sample.timestamp < buffer.last().timestamp) reset()
        buffer.add(sample)
        if (shotStartIndex == null) {
            val keepFrom = buffer.indexOfFirst { sample.timestamp - it.timestamp <= idleKeepMs }
            if (keepFrom > 0) buffer.subList(0, keepFrom).clear()
        }

        val phases = analyzer.detectPhases(buffer)
        val start = shotStartIndex
            ?: phases.firstOrNull { it.phase == ShotPhase.DRAW }?.startIndex?.also { shotStartIndex = it }
            ?: return null

        val release = phases.firstOrNull { it.phase == ShotPhase.RELEASE && it.startIndex >= start }
        if (release == null) {
            if (sample.timestamp - buffer[start].timestamp > maxDrawMs) restartAtLatestDraw(phases, start)
            return null
        }
        releaseSeen = true
        if (sample.timestamp - buffer[release.startIndex].timestamp >= afterReleaseMs) {
            return emitShot(lastDrawStart(phases, release) ?: start)
        }
        return null
    }

    /**
     * Call when stopping recording: emits the shot in progress if its release was seen, with
     * whatever follow-through was recorded.
     */
    public fun flush() {
        synchronized(lock) { finish() }?.let(onShotDetected)
    }

    private fun finish(): DetectedShot? {
        val start = shotStartIndex
        if (start == null || !releaseSeen) {
            if (start != null) observer?.dropped(buffer, buffer.size, stuck = false)
            reset()
            return null
        }
        val phases = analyzer.detectPhases(buffer)
        val release = phases.firstOrNull { it.phase == ShotPhase.RELEASE && it.startIndex >= start }
        return emitShot(release?.let { lastDrawStart(phases, it) } ?: start)
    }

    /**
     * The shot clock starts at the first movement after the previous shot, often the bow being
     * lowered; a slow archer then releases more than [maxDrawMs] later. Rather than dropping the
     * draw in progress, the clock restarts at the latest movement; with none recent, all is dropped.
     */
    private fun restartAtLatestDraw(phases: List<BowMovementAnalyzer.PhaseSegment>, start: Int) {
        val latest = phases.lastOrNull { it.phase == ShotPhase.DRAW }?.startIndex
        if (latest != null && latest > start && buffer.last().timestamp - buffer[latest].timestamp <= maxDrawMs) {
            observer?.dropped(buffer, latest, stuck = true)
            buffer.subList(0, latest).clear()
            shotStartIndex = 0
            releaseSeen = false
        } else {
            observer?.dropped(buffer, buffer.size, stuck = true)
            reset()
        }
    }

    /** Start of the DRAW segment right before the HOLD that ends with [release]. */
    private fun lastDrawStart(phases: List<BowMovementAnalyzer.PhaseSegment>, release: BowMovementAnalyzer.PhaseSegment): Int? {
        val r = phases.indexOf(release)
        val hold = phases.getOrNull(r - 1)?.takeIf { it.phase == ShotPhase.HOLD } ?: return null
        return phases.getOrNull(phases.indexOf(hold) - 1)?.takeIf { it.phase == ShotPhase.DRAW }?.startIndex
    }

    private fun emitShot(start: Int): DetectedShot {
        observer?.shot(buffer, start)
        val shotSamples = buffer.subList(start, buffer.size).toList()
        reset()
        return DetectedShot(shotSamples, analyzer.analyze(shotSamples).metrics)
    }

    private fun reset() {
        buffer.clear()
        shotStartIndex = null
        releaseSeen = false
    }
}
