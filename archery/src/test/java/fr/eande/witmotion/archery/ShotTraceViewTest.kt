package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShotTraceViewTest {

    @Test
    fun `samplesToTrace produces correct number of points`() {
        val samples = (0..9).map { i ->
            ImuSample(timestamp = i * 20L, euler = floatArrayOf(0f, i.toFloat(), i * 2f))
        }
        val phases = listOf(
            BowMovementAnalyzer.PhaseSegment(ShotPhase.HOLD, 0, 9)
        )

        val trace = ShotTraceView.samplesToTrace(samples, phases)
        assertThat(trace).hasSize(10)
    }

    @Test
    fun `trace points are relative to hold center`() {
        val samples = listOf(
            ImuSample(0, euler = floatArrayOf(0f, 10f, 20f)),
            ImuSample(20, euler = floatArrayOf(0f, 12f, 22f)),
            ImuSample(40, euler = floatArrayOf(0f, 8f, 18f))
        )
        val phases = listOf(
            BowMovementAnalyzer.PhaseSegment(ShotPhase.HOLD, 0, 2)
        )

        val trace = ShotTraceView.samplesToTrace(samples, phases)

        // Hold center: pitch=10, yaw=20 → points should be relative
        assertThat(trace[0].pitch).isWithin(0.1f).of(0f) // 10 - 10
        assertThat(trace[1].pitch).isWithin(0.1f).of(2f) // 12 - 10
        assertThat(trace[2].pitch).isWithin(0.1f).of(-2f) // 8 - 10
    }

    @Test
    fun `phase colors are assigned correctly`() {
        val samples = listOf(
            ImuSample(0, euler = floatArrayOf(0f, 0f, 0f)),
            ImuSample(20, euler = floatArrayOf(0f, 0f, 0f)),
            ImuSample(40, euler = floatArrayOf(0f, 0f, 0f))
        )
        val phases = listOf(
            BowMovementAnalyzer.PhaseSegment(ShotPhase.DRAW, 0, 0),
            BowMovementAnalyzer.PhaseSegment(ShotPhase.HOLD, 1, 1),
            BowMovementAnalyzer.PhaseSegment(ShotPhase.RELEASE, 2, 2)
        )

        val trace = ShotTraceView.samplesToTrace(samples, phases)
        assertThat(trace[0].phase).isEqualTo(ShotPhase.DRAW)
        assertThat(trace[1].phase).isEqualTo(ShotPhase.HOLD)
        assertThat(trace[2].phase).isEqualTo(ShotPhase.RELEASE)
    }

    @Test
    fun `empty samples produces empty trace`() {
        val trace = ShotTraceView.samplesToTrace(emptyList(), emptyList())
        assertThat(trace).isEmpty()
    }

    @Test
    fun `yaw stays continuous when the heading crosses 180 degrees`() {
        val yaws = listOf(179.5f, -179.5f, 179.8f, -179.9f, 179.9f)
        val samples = yaws.mapIndexed { i, yaw ->
            ImuSample(timestamp = i * 20L, euler = floatArrayOf(0f, 0f, yaw))
        }
        val hold = listOf(BowMovementAnalyzer.PhaseSegment(ShotPhase.HOLD, 0, samples.size - 1))

        val trace = ShotTraceView.samplesToTrace(samples, hold)

        assertThat(trace.maxOf { kotlin.math.abs(it.yaw) }).isLessThan(1f)
    }
}
