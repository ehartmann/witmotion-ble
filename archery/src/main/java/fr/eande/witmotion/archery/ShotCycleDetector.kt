package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample

/**
 * Detects individual shot cycles from a stream of IMU samples.
 *
 * A shot runs from [RAISE_LEAD_MS] before the raise of the bow, when
 * [BowMovementAnalyzer.shotBoundaries] finds one, or else from the start of the last draw before the
 * release (earlier draw/hold back and forth, handling the bow, is left out), to [afterReleaseMs]
 * after the release (a typical shot: about 6 s of draw and aim, then 3 s of follow-through), and is
 * emitted as soon as that follow-through time has elapsed. [BowMovementAnalyzer] finds the phases
 * (DRAW -> HOLD -> RELEASE -> FOLLOW_THROUGH) over the buffered samples.
 *
 * All limits are durations, so the behaviour does not depend on the sensor rate. While no draw is
 * in progress only the last [idleKeepMs] are kept, enough for a raise and its pause before the draw,
 * which bounds memory and the phase-detection cost over a long session. A raise too slow to read as a
 * draw (under about 20°/s) must fit in [idleKeepMs] with its pause and the rest before it: a longer one
 * reads unknown, and one within [RAISE_LEAD_MS] of that limit has its samples start at the buffer's start.
 * When no release comes within [maxDrawMs] of the first draw (the bow handled without shooting, or
 * lowered after the previous shot), detection restarts at the latest draw and keeps [idleKeepMs] before
 * it, so the raise of the shot in progress stays; with no newer draw in the last [maxDrawMs], all is dropped.
 *
 * `onPhase` reports the [LivePhase] once, when it changes: a HOLD once the bow stayed calm for the
 * analyzer's `minHoldMs` (DRAW until then), and IDLE after a reset (a shot emitted, [flush], timestamps
 * restarting, all dropped past [maxDrawMs]) or a link gap (over 1 s without samples). A HOLD begun before
 * such a gap is never reported. A restart at the latest draw leaves the live phase unchanged. When
 * timestamps restart and the first new sample is already moving, DRAW is reported with no IDLE before it.
 * At low sample rates a transient DRAW of up to `releaseWindowMs` can be reported before RELEASE.
 *
 * Thread safety: [onSample] and [flush] are synchronized to allow safe calls from
 * BLE callback and main threads respectively. `onPhase`, then `onShotDetected`, are called on the
 * thread that made the change (the BLE thread for [onSample]), after the lock is released: a slow
 * consumer delays that thread but may call back into the detector from another thread. `onShotDetected`
 * runs even if `onPhase` throws. Deliveries from two threads may arrive out of order: after [flush] the
 * detector is IDLE, but the consumer's last delivery may be an older phase, such as a HOLD computed on the
 * BLE thread just before, so reset the consumer's state when recording stops.
 */
public class ShotCycleDetector(
    private val analyzer: BowMovementAnalyzer = BowMovementAnalyzer(),
    private val afterReleaseMs: Long = 3_000,
    private val maxDrawMs: Long = 30_000,
    private val idleKeepMs: Long = 6_000,
    private val onPhase: (LivePhase) -> Unit = {},
    private val onShotDetected: (DetectedShot) -> Unit
) {
    /**
     * A shot's samples, from [RAISE_LEAD_MS] before its raise when it has one, else from its last draw,
     * and its metrics, measured from its last draw. The timestamps are where
     * [BowMovementAnalyzer.shotBoundaries] puts the shot's raise, draw, hold and release, null when not found.
     */
    public data class DetectedShot(
        val samples: List<ImuSample>,
        val metrics: BowMovementMetrics?,
        val raiseStartTimestamp: Long? = null,
        val drawStartTimestamp: Long? = null,
        val holdStartTimestamp: Long? = null,
        val releaseTimestamp: Long? = null
    )

    public companion object {
        /** A shot's samples start this long before its raise, or at the oldest sample kept. */
        public const val RAISE_LEAD_MS: Long = 1_000

        /**
         * A longer silence between two samples is a link gap: the live phase restarts from IDLE, and
         * [BowMovementAnalyzer.shotBoundaries] does not date a raise across it.
         */
        internal const val MAX_GAP_MS: Long = 1_000

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
    private var livePhase = ShotPhase.IDLE
    private var liveSince = 0L
    private var reported = LivePhase(ShotPhase.IDLE, 0L)
    /** A HOLD begun before this time, the first sample after a link gap, is not live. */
    private var floorT = Long.MIN_VALUE

    /** Samples held right now; for tests of the memory bound. */
    internal val bufferedSamples: Int get() = synchronized(lock) { buffer.size }

    public fun onSample(sample: ImuSample): Unit = locked { process(sample) }

    /** Runs [step] under the lock, then the callbacks outside it; the shot is delivered even if `onPhase` throws. */
    private inline fun locked(step: () -> DetectedShot?) {
        var phase: LivePhase? = null
        val shot = synchronized(lock) { step().also { phase = phaseChange() } }
        // The shot has already left the buffer: it would be lost for good
        try {
            phase?.let(onPhase)
        } finally {
            shot?.let(onShotDetected)
        }
    }

    /** The live phase to report, null when it did not change (a HOLD changes with its start too). */
    private fun phaseChange(): LivePhase? {
        if (livePhase == reported.phase && (livePhase != ShotPhase.HOLD || liveSince == reported.sinceTimestamp)) return null
        return LivePhase(livePhase, liveSince).also { reported = it }
    }

    private fun updateLivePhase(sample: ImuSample, phases: List<BowMovementAnalyzer.PhaseSegment>, gap: Boolean) {
        val last = phases.last()
        val start = buffer[last.startIndex].timestamp
        livePhase = when {
            gap -> ShotPhase.IDLE
            last.phase != ShotPhase.HOLD -> last.phase
            start < floorT -> ShotPhase.IDLE
            sample.timestamp - start < analyzer.minHoldMs -> ShotPhase.DRAW
            else -> ShotPhase.HOLD
        }
        liveSince = if (livePhase == ShotPhase.HOLD) start else sample.timestamp
    }

    private fun process(sample: ImuSample): DetectedShot? {
        // timestamps restart from 0 after ImuSensorManager.resetRecordingTimestamp
        if (buffer.isNotEmpty() && sample.timestamp < buffer.last().timestamp) {
            if (shotStartIndex != null) observer?.dropped(buffer, buffer.size, stuck = false)
            reset()
        }
        val gap = buffer.isNotEmpty() && sample.timestamp - buffer.last().timestamp > MAX_GAP_MS
        if (gap) floorT = sample.timestamp
        buffer.add(sample)
        if (shotStartIndex == null) {
            val keepFrom = buffer.indexOfFirst { sample.timestamp - it.timestamp <= idleKeepMs }
            if (keepFrom > 0) buffer.subList(0, keepFrom).clear()
        }

        val phases = analyzer.detectPhases(buffer)
        updateLivePhase(sample, phases, gap)
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
            return emitShot(lastDrawStart(phases, release) ?: start, phases)
        }
        return null
    }

    /**
     * Call when stopping recording: emits the shot in progress if its release was seen, with
     * whatever follow-through was recorded.
     */
    public fun flush(): Unit = locked { finish() }

    private fun finish(): DetectedShot? {
        val start = shotStartIndex
        if (start == null || !releaseSeen) {
            if (start != null) observer?.dropped(buffer, buffer.size, stuck = false)
            reset()
            return null
        }
        val phases = analyzer.detectPhases(buffer)
        val release = phases.firstOrNull { it.phase == ShotPhase.RELEASE && it.startIndex >= start }
        return emitShot(release?.let { lastDrawStart(phases, it) } ?: start, phases)
    }

    /**
     * The shot clock starts at the first movement after the previous shot, often the bow being
     * lowered; a slow archer then releases more than [maxDrawMs] later. Rather than dropping the
     * draw in progress, the clock restarts at the latest movement, keeping [idleKeepMs] before it as
     * while idle (the latest movement is often the raise, which needs its rest); with none recent, all
     * is dropped.
     */
    private fun restartAtLatestDraw(phases: List<BowMovementAnalyzer.PhaseSegment>, start: Int) {
        val latest = phases.lastOrNull { it.phase == ShotPhase.DRAW }?.startIndex
        if (latest != null && latest > start && buffer.last().timestamp - buffer[latest].timestamp <= maxDrawMs) {
            val keepFrom = buffer.indexOfFirst { it.timestamp >= buffer[latest].timestamp - idleKeepMs }
            observer?.dropped(buffer, latest, stuck = true)
            buffer.subList(0, keepFrom).clear()
            shotStartIndex = latest - keepFrom
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

    private fun emitShot(drawStart: Int, phases: List<BowMovementAnalyzer.PhaseSegment>): DetectedShot {
        val b = analyzer.shotBoundaries(buffer, phases)
        fun time(i: Int?) = i?.let { buffer[it].timestamp }
        val raise = time(b?.raiseStartIndex)
        val start = raise?.let { r -> buffer.indexOfFirst { it.timestamp >= r - RAISE_LEAD_MS } } ?: drawStart
        observer?.shot(buffer, start)
        val shot = DetectedShot(
            samples = buffer.subList(start, buffer.size).toList(),
            // From the last draw: a still pause between the raise and the draw is not the aim's hold
            metrics = analyzer.analyze(buffer.subList(drawStart, buffer.size)).metrics,
            raiseStartTimestamp = raise,
            drawStartTimestamp = time(b?.drawStartIndex),
            holdStartTimestamp = time(b?.holdStartIndex),
            releaseTimestamp = time(b?.releaseIndex)
        )
        reset()
        return shot
    }

    private fun reset() {
        livePhase = ShotPhase.IDLE
        if (buffer.isNotEmpty()) liveSince = buffer.last().timestamp
        floorT = Long.MIN_VALUE
        buffer.clear()
        shotStartIndex = null
        releaseSeen = false
    }
}
