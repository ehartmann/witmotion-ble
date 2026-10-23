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
 * attempt (null without one); [maxAccelJumpG] is the largest acceleration jump from that hold's start
 * to the end of the attempt (over the whole attempt when there was no hold).
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
 * 1. [DRAW_TOO_LONG]: the detector gave up on the draw after `maxDrawMs` (30 s) and it never held for
 *    `minHoldMs`. A long still hold with no release is not this one: it is [SPIKE_TOO_WEAK] or [NO_SPIKE].
 * 2. [MOVED_DURING_HOLD]: a hold was broken by movement above the draw threshold before `minHoldMs`
 *    (500 ms), with no release-grade spike on the way.
 * 3. [HOLD_TOO_SHORT]: a release-grade spike (above the release threshold, 2 g) came before the hold
 *    reached `minHoldMs`. It wins over [MOVED_DURING_HOLD] when the spike and the movement come
 *    together, which is what a release that is too early looks like.
 * 4. [SPIKE_TOO_WEAK]: the hold reached `minHoldMs` and the largest jump stayed under 2 g but at or
 *    above `weakSpikeG`.
 * 5. [NO_SPIKE]: the hold reached `minHoldMs` and no jump reached `weakSpikeG`.
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
    private val detector = ShotCycleDetector(analyzer = analyzer, afterReleaseMs = afterReleaseMs) { }.also { it.observer = this }

    // The last samples, enough for accelJump to look back over its window
    private val recent = ArrayDeque<ImuSample>()
    private var spikeT = 0L
    private var spikeLastT = 0L
    private var spikeJump = 0f
    private var spikeOpen = false

    var peakBufferedSamples = 0
        private set

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
        val weak = spikes.filter { spike -> shots.none { spike.tMs in it.releaseMs..it.endMs } }
        return DetectionReport(shots.toList(), nearMisses.toList(), weak)
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
        if (spikeOpen && spikeJump < analyzer.releaseAccelThreshold) spikes += Spike(spikeT, spikeJump)
        spikeOpen = false
    }

    override fun shot(buffer: List<ImuSample>, from: Int) {
        val phases = analyzer.detectPhases(buffer)
        val release = phases.firstOrNull { it.phase == ShotPhase.RELEASE && it.startIndex >= from }
        shots += DetectedWindow(buffer[from].timestamp, buffer[release?.startIndex ?: from].timestamp, buffer.last().timestamp)
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
            if (moved && holdMs(buffer, seg) >= analyzer.minHoldMs) {
                explain(buffer, current, seg.endIndex + 1, stuck = false)?.let(nearMisses::add)
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
    private fun spikes(buffer: List<ImuSample>, hold: BowMovementAnalyzer.PhaseSegment, to: Int) =
        (hold.startIndex + 1)..minOf(hold.endIndex + 1, to, buffer.lastIndex)

    private fun releaseSpikeEarly(buffer: List<ImuSample>, hold: BowMovementAnalyzer.PhaseSegment, to: Int) =
        spikes(buffer, hold, to).any {
            buffer[it].timestamp - buffer[hold.startIndex].timestamp < analyzer.minHoldMs && analyzer.isReleaseStart(buffer, it)
        }

    private fun explain(buffer: List<ImuSample>, attempt: Attempt, endIndex: Int, stuck: Boolean): NearMiss? {
        val confirmed = attempt.holds.lastOrNull()?.takeIf { holdMs(buffer, it) >= analyzer.minHoldMs }
        val longest = confirmed ?: attempt.holds.maxByOrNull { holdMs(buffer, it) }

        val reason = when {
            stuck && confirmed == null -> Reason.DRAW_TOO_LONG
            confirmed == null -> when {
                attempt.holds.any { releaseSpikeEarly(buffer, it, endIndex) } -> Reason.HOLD_TOO_SHORT
                attempt.broken -> Reason.MOVED_DURING_HOLD
                else -> return null
            }
            releaseSpikeEarly(buffer, confirmed, endIndex) -> Reason.HOLD_TOO_SHORT
            else -> null
        }

        val jumpRange = longest?.let { spikes(buffer, it, endIndex) } ?: attempt.firstDraw..minOf(endIndex, buffer.lastIndex)
        val maxJump = jumpRange.maxOfOrNull { analyzer.accelJump(buffer, it) } ?: 0f
        val drawEnd = minOf(confirmed?.startIndex ?: (endIndex + 1), buffer.size)
        val maxDrawGyro = (attempt.firstDraw until drawEnd).maxOfOrNull { analyzer.gyroMagnitude(buffer[it]) } ?: 0f

        return NearMiss(
            drawStartMs = buffer[attempt.firstDraw].timestamp,
            endMs = buffer[minOf(endIndex, buffer.lastIndex)].timestamp,
            maxDrawGyroDps = maxDrawGyro,
            holdMs = longest?.let { holdMs(buffer, it) },
            holdMeanGyroDps = longest?.let { hold ->
                (hold.startIndex..hold.endIndex).map { analyzer.gyroMagnitude(buffer[it]) }.average().toFloat()
            },
            maxAccelJumpG = maxJump,
            reason = reason ?: if (maxJump >= weakSpikeG) Reason.SPIKE_TOO_WEAK else Reason.NO_SPIKE
        )
    }
}
