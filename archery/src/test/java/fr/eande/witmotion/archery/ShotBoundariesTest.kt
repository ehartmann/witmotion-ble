package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import fr.eande.witmotion.archery.BowMovementAnalyzer.PhaseSegment
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShotBoundariesTest {

    private val analyzer = BowMovementAnalyzer()

    /** Gravity [deg] degrees from the aim (+z), tilted about the sensor's x axis. */
    private fun gravity(deg: Double) =
        floatArrayOf(0f, sin(Math.toRadians(deg)).toFloat(), cos(Math.toRadians(deg)).toFloat())

    /** [ms] of 50 Hz samples from [start]: [gyro] °/s, gravity [tilt] degrees from the aim, given the ms into the stretch. */
    private fun stretch(start: Long, ms: Long, gyro: Float, tilt: (Long) -> Double = { 0.0 }) =
        (0 until ms step 20).map { dt ->
            ImuSample(start + dt, acceleration = gravity(tilt(dt)), gyroscope = floatArrayOf(gyro, 0f, 0f))
        }

    /** The bow rising over [ms] from [fromDeg] off the aim to the aim. */
    private fun rise(start: Long, ms: Long = 3_000, fromDeg: Double = 60.0) =
        stretch(start, ms, gyro = 25f) { dt -> fromDeg * (1 - dt / ms.toDouble()) }

    /** From [start], at the aim: draw 2 s, hold 3 s, the release shock, 1 s of follow-through. */
    private fun drawToRelease(start: Long): List<ImuSample> =
        stretch(start, 2_000, gyro = 25f) +
            stretch(start + 2_000, 3_000, gyro = 1f) +
            ImuSample(start + 5_000, acceleration = floatArrayOf(3f, 0f, 1f), gyroscope = floatArrayOf(1f, 0f, 0f)) +
            stretch(start + 5_020, 1_000, gyro = 2f)

    /** Rest 1 s at 60° from the aim, rise 3 s, draw 2 s, hold 3 s, release. */
    private val blended = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000) + drawToRelease(4_000)

    /** Rest 1 s at 60° from the aim, rise 3 s, a still pause 1 s at the aim, then draw 2 s, hold 3 s, release. */
    private val raisePauseDraw = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000) + stretch(4_000, 1_000, gyro = 0f) + drawToRelease(5_000)

    private fun List<ImuSample>.indexAt(ms: Long) = indexOfFirst { it.timestamp == ms }

    @Test
    fun `a raise blended with the draw starts when the bow left its rest`() {
        val b = analyzer.shotBoundaries(blended)!!

        assertThat(blended[b.raiseStartIndex!!].timestamp).isIn(980L..1_020L)
        // The bow is within 10° of the aim from 3.5 s; the tilt is smoothed over 100 ms
        assertThat(blended[b.drawStartIndex].timestamp).isIn(3_500L..3_600L)
        assertThat(b.holdStartIndex).isEqualTo(blended.indexAt(6_000))
        assertThat(b.releaseIndex).isEqualTo(blended.indexAt(9_000))
    }

    @Test
    fun `a raise, a pause, then a draw`() {
        val samples = raisePauseDraw

        val b = analyzer.shotBoundaries(samples)!!

        assertThat(samples[b.raiseStartIndex!!].timestamp).isIn(980L..1_020L)
        assertThat(b.drawStartIndex).isEqualTo(samples.indexAt(5_000))
    }

    @Test
    fun `a still sample halfway up the raise or a twitch in the pause keeps the raise's start`() {
        // Seen on the 2026-10-20 recordings: one gyro sample under holdThreshold during a raise, sensor noise or a slow-down
        val dip = blended.map { if (it.timestamp == 2_500L) it.copy(gyroscope = floatArrayOf(2f, 0f, 0f)) else it }
        val twitch = raisePauseDraw.map { if (it.timestamp == 4_500L) it.copy(gyroscope = floatArrayOf(10f, 0f, 0f)) else it }

        for (samples in listOf(dip, twitch)) {
            val b = analyzer.shotBoundaries(samples)!!

            assertThat(b.raiseStartIndex).isNotNull()
            assertThat(samples[b.raiseStartIndex!!].timestamp).isIn(980L..1_020L)
        }
    }

    @Test
    fun `a bow already up has no raise`() {
        val drawnFromTheStart = drawToRelease(0)
        // Still 3 s, then turned at the aim's height: moving, but the gravity does not tilt
        val turnedAtTheAim = stretch(0, 3_000, gyro = 0f) + stretch(3_000, 1_000, gyro = 10f) + drawToRelease(4_000)

        for ((samples, drawStartMs) in listOf(drawnFromTheStart to 0L, turnedAtTheAim to 4_000L)) {
            val b = analyzer.shotBoundaries(samples)!!

            assertThat(b.raiseStartIndex).isNull()
            assertThat(b.drawStartIndex).isEqualTo(samples.indexAt(drawStartMs))
        }
    }

    @Test
    fun `a raise cut by the start of the samples is unknown`() {
        val samples = rise(0, ms = 1_500, fromDeg = 30.0) + drawToRelease(1_500)

        val b = analyzer.shotBoundaries(samples)!!

        assertThat(b.raiseStartIndex).isNull()
        // The bow is within 10° of the aim from 1 s; the tilt is smoothed over 100 ms
        assertThat(samples[b.drawStartIndex].timestamp).isIn(1_000L..1_100L)
    }

    @Test
    fun `a link gap over 1 s across the start of a raise makes it unknown, not later`() {
        // No samples from 0.8 s to 2.2 s: the first sample after the gap would read as a raise start
        val gapped = blended.filter { it.timestamp !in 800L until 2_200L }

        val b = analyzer.shotBoundaries(gapped)!!

        assertThat(b.raiseStartIndex).isNull()
        assertThat(gapped[b.drawStartIndex].timestamp).isIn(3_500L..3_600L)
    }

    @Test
    fun `a pause at the aim that makes the raise longer than 6 s keeps no raise`() {
        val samples = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000) + stretch(4_000, 3_500, gyro = 0f) + drawToRelease(7_500)

        val b = analyzer.shotBoundaries(samples)!!

        assertThat(b.raiseStartIndex).isNull()
        assertThat(b.drawStartIndex).isEqualTo(samples.indexAt(7_500))
    }

    @Test
    fun `session 39's shape - the raise runs from the rest's end to the draw start, across the calm at the aim`() {
        val samples = stretch(0, 1_000, gyro = 0f) { 25.0 } +
            stretch(1_000, 1_500, gyro = 50f) { dt -> 25.0 - 20.0 * dt / 1_500 } +
            stretch(2_500, 1_100, gyro = 0f) { 5.0 } +
            stretch(3_600, 200, gyro = 12f) { 5.0 } +
            drawToRelease(3_800)

        val b = analyzer.shotBoundaries(samples)!!

        assertThat(b.raiseStartIndex).isEqualTo(samples.indexAt(1_000))
        assertThat(b.drawStartIndex).isEqualTo(samples.indexAt(3_800))
        assertThat(samples[b.drawStartIndex].timestamp - samples[b.raiseStartIndex!!].timestamp).isIn(2_790L..2_810L)
    }

    @Test
    fun `a calm release jolt is not a rest`() {
        val jolt = ImuSample(500, acceleration = floatArrayOf(5f, 2f, 3f), gyroscope = floatArrayOf(1f, 0f, 0f))
        val joltThenDraw = stretch(0, 500, gyro = 1f) + jolt + stretch(520, 2_000, gyro = 1f) + drawToRelease(2_520)
        val joltThenRestThenRaise = stretch(0, 500, gyro = 1f) + jolt + stretch(520, 500, gyro = 1f) +
            stretch(1_020, 1_000, gyro = 0f) { 60.0 } + rise(2_020) + drawToRelease(5_020)

        assertThat(analyzer.shotBoundaries(joltThenDraw)!!.raiseStartIndex).isNull()
        val b = analyzer.shotBoundaries(joltThenRestThenRaise)!!
        assertThat(joltThenRestThenRaise[b.raiseStartIndex!!].timestamp).isIn(2_000L..2_040L)
    }

    @Test
    fun `raise and draw in one movement end the raise at raiseEnd, with no raiseEnd there is no raise`() {
        val b = analyzer.shotBoundaries(blended)!!
        assertThat(b.drawStartIndex).isGreaterThan(b.raiseStartIndex!!)
        assertThat(blended[b.drawStartIndex].timestamp - blended[b.raiseStartIndex!!].timestamp).isIn(2_480L..2_620L)

        // The draw never comes within 10 degrees of the aim before the hold
        val neverThere = stretch(0, 1_000, gyro = 0f) { 60.0 } +
            stretch(1_000, 2_000, gyro = 25f) { dt -> 60.0 - 40.0 * dt / 2_000 } +
            stretch(3_000, 3_000, gyro = 1f) +
            ImuSample(6_000, acceleration = floatArrayOf(3f, 0f, 1f), gyroscope = floatArrayOf(1f, 0f, 0f)) +
            stretch(6_020, 1_000, gyro = 2f)
        assertThat(analyzer.shotBoundaries(neverThere)!!.raiseStartIndex).isNull()
    }

    @Test
    fun `no calm rest 20 degrees off within 10 s - no raise`() {
        val samples = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000, ms = 1_000) + stretch(2_000, 10_000, gyro = 0f) + drawToRelease(12_000)

        assertThat(analyzer.shotBoundaries(samples)!!.raiseStartIndex).isNull()
    }

    @Test
    fun `a rest 6_5 s before the draw start - no raise`() {
        val samples = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000, ms = 1_000) + stretch(2_000, 5_500, gyro = 0f) + drawToRelease(7_500)

        assertThat(analyzer.shotBoundaries(samples)!!.raiseStartIndex).isNull()
    }

    @Test
    fun `a rest 0_4 s before the draw start - no raise`() {
        val samples = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000, ms = 400) + drawToRelease(1_400)

        assertThat(analyzer.shotBoundaries(samples)!!.raiseStartIndex).isNull()
    }

    @Test
    fun `a gap over 1 s between the rest and the draw - no raise`() {
        val gapped = raisePauseDraw.filter { it.timestamp !in 2_000L until 3_500L }

        assertThat(analyzer.shotBoundaries(gapped)!!.raiseStartIndex).isNull()
    }

    @Test
    fun `a short calm stop at 30 degrees during the raise - the raise starts after it`() {
        val samples = stretch(0, 1_000, gyro = 0f) { 60.0 } +
            stretch(1_000, 1_000, gyro = 25f) { dt -> 60.0 - 30.0 * dt / 1_000 } +
            stretch(2_000, 500, gyro = 0f) { 30.0 } +
            stretch(2_500, 1_500, gyro = 25f) { dt -> 30.0 * (1 - dt / 1_500.0) } +
            drawToRelease(4_000)

        val b = analyzer.shotBoundaries(samples)!!

        assertThat(samples[b.raiseStartIndex!!].timestamp).isIn(2_480L..2_520L)
    }

    @Test
    fun `a raise longer than raiseMaxMs is unknown, its draw start is unchanged`() {
        // Blended, the aim reached after about 6.7 s; paused, 7 s of rising then 1 s still
        val blendedSlow = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000, ms = 8_000) + drawToRelease(9_000)
        val pausedSlow = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000, ms = 7_000) + stretch(8_000, 1_000, gyro = 0f) + drawToRelease(9_000)

        for (samples in listOf(blendedSlow, pausedSlow)) {
            val patient = BowMovementAnalyzer(raiseMaxMs = 10_000).shotBoundaries(samples)!!
            val b = analyzer.shotBoundaries(samples)!!

            assertThat(samples[patient.raiseStartIndex!!].timestamp).isIn(980L..1_020L)
            assertThat(b.raiseStartIndex).isNull()
            assertThat(b.drawStartIndex).isEqualTo(patient.drawStartIndex)
        }
    }

    @Test
    fun `a blended movement from under raiseMinTiltDeg is no raise, its draw starts at the aim`() {
        val samples = stretch(0, 1_000, gyro = 0f) { 15.0 } + rise(1_000, fromDeg = 15.0) + drawToRelease(4_000)

        val b = analyzer.shotBoundaries(samples)!!

        assertThat(b.raiseStartIndex).isNull()
        // Within 10° of the aim from 2 s; the tilt is smoothed over 100 ms
        assertThat(samples[b.drawStartIndex].timestamp).isIn(2_000L..2_100L)
    }

    @Test
    fun `a jolt at the first moving sample after a rest at the aim is no raise`() {
        // A premature release breaks a short hold at the aim: the jolt tilts that one sample's gravity
        val samples = stretch(0, 300, gyro = 1f) +
            ImuSample(300, acceleration = floatArrayOf(5f, 2f, 3f), gyroscope = floatArrayOf(40f, 0f, 0f)) +
            stretch(320, 1_000, gyro = 30f) + stretch(1_320, 200, gyro = 0f) + drawToRelease(1_520)

        assertThat(analyzer.shotBoundaries(samples)!!.raiseStartIndex).isNull()
    }

    @Test
    fun `no release gives no boundaries`() {
        val samples = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000) +
            stretch(4_000, 2_000, gyro = 25f) + stretch(6_000, 3_000, gyro = 1f)

        assertThat(analyzer.shotBoundaries(samples)).isNull()
    }

    @Test
    fun `analyze measures the aim's hold, not the pause after a raise`() {
        assertThat(analyzer.analyze(raisePauseDraw).metrics!!.holdTimeMs).isEqualTo(2_980L)
    }

    @Test
    fun `classify reads the aim's hold, not the pause after a raise`() {
        // The pause held 10° above the aim: read as the hold, the bow would seem to drop after the release
        val samples = raisePauseDraw.map { it.copy(euler = floatArrayOf(0f, if (it.timestamp in 4_000L until 5_000L) 10f else 0f, 0f)) }
        val cut = samples.subList(samples.indexAt(5_000), samples.size)

        val atLastDraw = ShotErrorClassifier.classify(cut, analyzer.detectPhases(cut))

        assertThat(atLastDraw.primaryError).isEqualTo(ShotErrorClassifier.ShotError.CLEAN)
        assertThat(ShotErrorClassifier.classify(samples, analyzer.detectPhases(samples))).isEqualTo(atLastDraw)
    }

    @Test
    fun `the trace labels only the aim's hold as HOLD, not the pause after a raise`() {
        val trace = ShotTraceView.samplesToTrace(raisePauseDraw, analyzer.detectPhases(raisePauseDraw))

        val held = raisePauseDraw.filterIndexed { i, _ -> trace[i].phase == ShotPhase.HOLD }.map { it.timestamp }
        assertThat(held).containsExactlyElementsIn((7_000L..9_980L step 20).toList()).inOrder()
    }

    @Test
    fun `detectPhases is unchanged`() {
        assertThat(analyzer.detectPhases(blended)).isEqualTo(
            listOf(
                PhaseSegment(ShotPhase.DRAW, 50, 299),
                PhaseSegment(ShotPhase.HOLD, 300, 449),
                PhaseSegment(ShotPhase.RELEASE, 450, 475),
                PhaseSegment(ShotPhase.FOLLOW_THROUGH, 476, 500)
            )
        )
    }
}
