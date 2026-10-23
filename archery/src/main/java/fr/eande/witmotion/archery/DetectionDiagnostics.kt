package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample

/** What [DetectionDiagnostics.explain] found in a recording. Times are sample timestamps in ms. */
data class DetectionReport(
    /** The shots [ShotCycleDetector.detectAll] emits on the same samples, as time windows. */
    val shots: List<DetectedWindow>,
    /** Draws that did not become a shot, each with the reason. */
    val nearMisses: List<NearMiss>,
    /** Acceleration jumps from `weakSpikeG` up to the release threshold, outside any detected shot's release. */
    val weakSpikes: List<Spike>
)

data class DetectedWindow(val drawStartMs: Long, val releaseMs: Long, val endMs: Long)

/**
 * A draw that did not become a shot. [holdMs] and [holdMeanGyroDps] describe the longest hold of the
 * attempt (null without one). [maxAccelJumpG] is the largest acceleration jump from the sample after
 * that hold's start to where the attempt ends: the hold's exit plus `releaseWindowMs` (200 ms, the
 * analyzer's own look-ahead for a release whose gyro crosses first) when movement ended it, or the
 * cut of the window or recording otherwise; over the whole attempt when there was no hold.
 */
data class NearMiss(
    val drawStartMs: Long,
    val endMs: Long,
    val maxDrawGyroDps: Float,
    val holdMs: Long?,
    val holdMeanGyroDps: Float?,
    val maxAccelJumpG: Float,
    val reason: Reason
)

/**
 * Why an attempt was not a shot. When several fit, the first in this list wins:
 * 1. [DRAW_TOO_LONG]: the detector gave up on the window (`maxDrawMs`, 30 s from its first draw, so the
 *    attempt itself can be shorter) and the attempt never held for `minHoldMs`. A long still hold with
 *    no release is not this one: it is [SPIKE_TOO_WEAK] or [NO_SPIKE].
 * 2. [HOLD_TOO_SHORT]: a release-grade spike (above the release threshold, 2 g, or movement with such a
 *    spike just ahead, as the analyzer reads a release) came before a hold reached `minHoldMs`
 *    (500 ms). Every hold of the attempt is checked, and the attempt ends there even when a shot
 *    follows: a premature release is reported whatever comes after.
 * 3. [SPIKE_TOO_WEAK]: a hold reached `minHoldMs`, the largest jump stayed under 2 g but at or above
 *    `weakSpikeG`.
 * 4. [NO_SPIKE]: a hold reached `minHoldMs` and no jump reached `weakSpikeG`.
 * 5. [MOVED_DURING_HOLD]: the attempt ends with no hold ever reaching `minHoldMs` and no release-grade
 *    spike, after holds broken by movement. It ends with the recording, or when the detector gives up
 *    on the window (a let-down, a stuck draw). It is not reported for the short broken holds before a
 *    shot: that is normal aiming. With a confirmed hold the reason is [SPIKE_TOO_WEAK] or [NO_SPIKE].
 */
enum class Reason { HOLD_TOO_SHORT, SPIKE_TOO_WEAK, NO_SPIKE, MOVED_DURING_HOLD, DRAW_TOO_LONG }

data class Spike(val tMs: Long, val jumpG: Float)

/**
 * Explains a recording with the detector's own thresholds and math: it runs a [ShotCycleDetector] and
 * reads the windows it closes, so `shots` is what [ShotCycleDetector.detectAll] emits, by construction.
 *
 * An attempt starts at a draw and runs through any hold shorter than `minHoldMs` (the bow moves again)
 * until a hold of at least `minHoldMs` ends by movement, or the window is dropped or the recording
 * ends. Short holds before the final draw of a shot belong to that shot, not to a near miss. An
 * attempt cut by the end of the recording while still moving, with no hold, is not reported.
 *
 * [afterReleaseMs] must equal the value passed to [ShotCycleDetector.detectAll] for `shots` to agree.
 *
 * Memory is the detector's buffer (at most `maxDrawMs` plus `afterReleaseMs` of samples) plus the
 * samples of the last 20 ms: nothing grows with the length of the recording but the report.
 */
object DetectionDiagnostics {
    fun explain(
        samples: Sequence<ImuSample>,
        analyzer: BowMovementAnalyzer = BowMovementAnalyzer(),
        weakSpikeG: Float = 1f,
        afterReleaseMs: Long = 3_000
    ): DetectionReport {
        val run = DiagnosticsRun(analyzer, weakSpikeG, afterReleaseMs)
        samples.forEach(run::onSample)
        return run.finish()
    }
}

internal class DiagnosticsRun(
    private val analyzer: BowMovementAnalyzer,
    private val weakSpikeG: Float,
    afterReleaseMs: Long
) : ShotCycleDetector.Observer {

    private class Attempt(val firstDraw: Int) {
        val holds = mutableListOf<BowMovementAnalyzer.PhaseSegment>()
        var broken = false
    }

    private val shots = mutableListOf<DetectedWindow>()
    private val nearMisses = mutableListOf<NearMiss>()
    private val spikes = mutableListOf<Spike>()
    private val weakSpikes = mutableListOf<Spike>()
    private val detector = ShotCycleDetector(analyzer = analyzer, afterReleaseMs = afterReleaseMs) { }.also { it.observer = this }

    // The last samples, enough for accelJump to look back over its window
    private val recent = ArrayDeque<ImuSample>()
    private var spikeT = 0L
    private var spikeLastT = 0L
    private var spikeJump = 0f
    private var spikeOpen = false

    var peakBufferedSamples = 0
        private set

    /** Weak spikes not yet known to be outside the next shot's release; for tests of the memory bound. */
    val pendingSpikes: Int get() = spikes.size

    fun onSample(sample: ImuSample) {
        recent.addLast(sample)
        while (recent.size > 2 && sample.timestamp - recent[1].timestamp > analyzer.jumpWindowMs) recent.removeFirst()
        trackSpike(sample.timestamp, analyzer.accelJump(recent, recent.lastIndex))
        detector.onSample(sample)
        peakBufferedSamples = maxOf(peakBufferedSamples, detector.bufferedSamples + recent.size)
    }

    fun finish(): DetectionReport {
        detector.flush()
        closeSpike()
        return DetectionReport(shots.toList(), nearMisses.toList(), weakSpikes + spikes)
    }

    // Consecutive samples of one jump all read high (accelJump looks back over a window): keep the peak
    private fun trackSpike(t: Long, jump: Float) {
        if (jump < weakSpikeG) return
        if (spikeOpen && t - spikeLastT > analyzer.jumpWindowMs) closeSpike()
        if (!spikeOpen || jump > spikeJump) {
            spikeJump = jump
            spikeT = t
        }
        spikeOpen = true
        spikeLastT = t
    }

    private fun closeSpike() {
        val inLastShot = shots.lastOrNull()?.let { spikeT in it.releaseMs..it.endMs } == true
        if (spikeOpen && spikeJump < analyzer.releaseAccelThreshold && !inLastShot) spikes += Spike(spikeT, spikeJump)
        spikeOpen = false
    }

    override fun shot(buffer: List<ImuSample>, from: Int) {
        val phases = analyzer.detectPhases(buffer)
        val release = phases.firstOrNull { it.phase == ShotPhase.RELEASE && it.startIndex >= from }
        val window = DetectedWindow(buffer[from].timestamp, buffer[release?.startIndex ?: from].timestamp, buffer.last().timestamp)
        shots += window
        // A spike before this release cannot fall in a later shot
        spikes.filterTo(weakSpikes) { it.tMs !in window.releaseMs..window.endMs }
        spikes.clear()
        explainAttempts(buffer, phases, upTo = from, keepOpen = false, stuck = false)
    }

    override fun dropped(buffer: List<ImuSample>, upTo: Int, stuck: Boolean) {
        explainAttempts(buffer, analyzer.detectPhases(buffer), upTo, keepOpen = true, stuck = stuck)
    }

    private fun explainAttempts(
        buffer: List<ImuSample>,
        phases: List<BowMovementAnalyzer.PhaseSegment>,
        upTo: Int,
        keepOpen: Boolean,
        stuck: Boolean
    ) {
        var attempt: Attempt? = null
        for ((k, seg) in phases.withIndex()) {
            if (seg.startIndex >= upTo || seg.phase == ShotPhase.RELEASE || seg.phase == ShotPhase.FOLLOW_THROUGH) break
            if (seg.phase == ShotPhase.DRAW && attempt == null) attempt = Attempt(seg.startIndex)
            if (seg.phase != ShotPhase.HOLD) continue
            val current = attempt ?: continue
            current.holds += seg
            val moved = phases.getOrNull(k + 1)?.phase == ShotPhase.DRAW
            if (moved) current.broken = true
            val confirmed = holdMs(buffer, seg) >= analyzer.minHoldMs
            val premature = !confirmed && releaseSpikeEarly(buffer, seg, buffer.lastIndex)
            if (premature || (confirmed && moved)) {
                explain(buffer, current, minOf(seg.endIndex + 1, buffer.lastIndex), stuck = false)?.let(nearMisses::add)
                attempt = null
            }
        }
        // What is left when a shot follows is the draw of that shot
        if (keepOpen) attempt?.let { explain(buffer, it, upTo - 1, stuck)?.let(nearMisses::add) }
    }

    /** How long the hold had lasted when it ended, as the analyzer compares it to minHoldMs. */
    private fun holdMs(buffer: List<ImuSample>, hold: BowMovementAnalyzer.PhaseSegment): Long =
        buffer[minOf(hold.endIndex + 1, buffer.lastIndex)].timestamp - buffer[hold.startIndex].timestamp

    // The analyzer ignores spikes at the first hold sample (the draw state), and so do we
    private fun holdScan(buffer: List<ImuSample>, hold: BowMovementAnalyzer.PhaseSegment, to: Int) =
        (hold.startIndex + 1)..minOf(hold.endIndex + 1, to, buffer.lastIndex)

    private fun releaseSpikeEarly(buffer: List<ImuSample>, hold: BowMovementAnalyzer.PhaseSegment, to: Int) =
        holdScan(buffer, hold, to).any {
            buffer[it].timestamp - buffer[hold.startIndex].timestamp < analyzer.minHoldMs && analyzer.isReleaseStart(buffer, it)
        }

    private fun explain(buffer: List<ImuSample>, attempt: Attempt, endIndex: Int, stuck: Boolean): NearMiss? {
        val confirmed = attempt.holds.lastOrNull()?.takeIf { holdMs(buffer, it) >= analyzer.minHoldMs }
        val longest = confirmed ?: attempt.holds.maxByOrNull { holdMs(buffer, it) }

        val reason = when {
            stuck && confirmed == null -> Reason.DRAW_TOO_LONG
            attempt.holds.any { releaseSpikeEarly(buffer, it, endIndex) } -> Reason.HOLD_TOO_SHORT
            confirmed != null -> null
            attempt.broken -> Reason.MOVED_DURING_HOLD
            else -> return null
        }

        val maxJump = if (longest == null) {
            (attempt.firstDraw..minOf(endIndex, buffer.lastIndex)).maxOf { analyzer.accelJump(buffer, it) }
        } else {
            val exit = longest.endIndex + 1
            val scanned = holdScan(buffer, longest, endIndex).maxOfOrNull { analyzer.accelJump(buffer, it) } ?: 0f
            // The gyro crosses first in a real release: the jump comes up to releaseWindowMs after the exit
            if (exit <= endIndex) maxOf(scanned, analyzer.maxJumpWithin(buffer, exit, analyzer.releaseWindowMs)) else scanned
        }
        val drawEnd = minOf(confirmed?.startIndex ?: (endIndex + 1), buffer.size)
        val maxDrawGyro = (attempt.firstDraw until drawEnd).maxOfOrNull { analyzer.gyroMagnitude(buffer[it]) } ?: 0f

        return NearMiss(
            drawStartMs = buffer[attempt.firstDraw].timestamp,
            endMs = buffer[minOf(endIndex, buffer.lastIndex)].timestamp,
            maxDrawGyroDps = maxDrawGyro,
            holdMs = longest?.let { holdMs(buffer, it) },
            holdMeanGyroDps = longest?.let { hold ->
                var sum = 0f
                for (i in hold.startIndex..hold.endIndex) sum += analyzer.gyroMagnitude(buffer[i])
                sum / (hold.endIndex - hold.startIndex + 1)
            },
            maxAccelJumpG = maxJump,
            reason = reason ?: if (maxJump >= weakSpikeG) Reason.SPIKE_TOO_WEAK else Reason.NO_SPIKE
        )
    }
}
