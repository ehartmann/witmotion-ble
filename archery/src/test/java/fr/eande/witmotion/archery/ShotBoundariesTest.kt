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
    fun `a pause longer than raisePauseMaxMs before the draw keeps no raise`() {
        val samples = stretch(0, 1_000, gyro = 0f) { 60.0 } + rise(1_000) + stretch(4_000, 2_500, gyro = 0f) + drawToRelease(6_500)

        val b = analyzer.shotBoundaries(samples)!!

        assertThat(b.raiseStartIndex).isNull()
        assertThat(b.drawStartIndex).isEqualTo(samples.indexAt(6_500))
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
