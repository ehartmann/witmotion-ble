package fr.eande.witmotion.archery

import com.google.common.truth.Truth.assertThat
import fr.eande.witmotion.ImuSample
import fr.eande.witmotion.archery.ShotPhase.DRAW
import fr.eande.witmotion.archery.ShotPhase.HOLD
import fr.eande.witmotion.archery.ShotPhase.IDLE
import org.junit.Test

class LivePhaseTest {

    /** A live phase and the timestamp of the sample that reported it. */
    private data class Report(val at: Long, val live: LivePhase)

    private val reports = mutableListOf<Report>()
    private var t = 0L
    private val minHoldMs = BowMovementAnalyzer().minHoldMs

    private val detector = ShotCycleDetector(afterReleaseMs = 3_000, onPhase = { reports += Report(t, it) }) { }

    private fun sample(time: Long, gyro: Float, accel: FloatArray = floatArrayOf(0f, 0f, 1f)) =
        ImuSample(timestamp = time, gyroscope = floatArrayOf(gyro, 0f, 0f), acceleration = accel)

    /** [durationMs] of 50 Hz samples at [gyro] °/s. */
    private fun feed(durationMs: Long, gyro: Float) {
        val end = t + durationMs
        while (t < end) {
            detector.onSample(sample(t, gyro))
            t += 20
        }
    }

    @Test
    fun `HOLD is entered once the bow stayed calm for the minimum hold`() {
        feed(1_000, 0f)
        feed(1_000, 30f)
        val t0 = t
        feed(3_000, 1f)

        assertThat(reports.map { it.live.phase }).containsExactly(DRAW, HOLD).inOrder()
        val hold = reports.last()
        assertThat(hold.at).isIn(t0 + minHoldMs - 20..t0 + minHoldMs + 20)
        assertThat(hold.live.sinceTimestamp).isEqualTo(t0)
    }

    @Test
    fun `a let-down leaves HOLD and a new draw gives a new hold`() {
        feed(1_000, 30f)
        feed(1_000, 1f)
        feed(500, 30f)    // let-down
        feed(1_000, 30f)  // new draw
        feed(1_000, 1f)

        assertThat(reports.map { it.live.phase }).containsExactly(DRAW, HOLD, DRAW, HOLD).inOrder()
        assertThat(reports[3].live.sinceTimestamp).isGreaterThan(reports[1].live.sinceTimestamp)
    }

    @Test
    fun `the release leaves HOLD within 300 ms`() {
        feed(1_000, 30f)
        feed(3_000, 1f)
        val spikeAt = t
        detector.onSample(sample(t, 1f, accel = floatArrayOf(3f, 0f, 1f)))
        t += 20
        feed(3_000, 2f)   // ends with the sample that emits the shot

        val afterHold = reports[reports.indexOfLast { it.live.phase == HOLD } + 1]
        assertThat(afterHold.at - spikeAt).isAtMost(300L)
        assertThat(afterHold.live.phase).isNotEqualTo(HOLD)
        assertThat(reports.last()).isEqualTo(Report(spikeAt + 3_000, LivePhase(IDLE, spikeAt + 3_000)))
    }

    @Test
    fun `a link gap during the hold resets to IDLE and the old hold is never reported again`() {
        feed(1_000, 30f)
        feed(1_000, 1f)
        t += 2_000        // no samples
        val resumedAt = t
        feed(3_000, 1f)   // still calm: the hold begun before the gap goes on
        feed(1_000, 30f)
        feed(1_000, 1f)

        val after = reports.filter { it.at >= resumedAt }
        assertThat(after.first()).isEqualTo(Report(resumedAt, LivePhase(IDLE, resumedAt)))
        assertThat(after.map { it.live.phase }).containsExactly(IDLE, DRAW, HOLD).inOrder()
        assertThat(after.last().live.sinceTimestamp).isAtLeast(resumedAt + 4_000)
    }

    @Test
    fun `timestamps restarting from zero reset the live phase`() {
        t = 1_000_000
        feed(500, 0f)
        t += 2_000        // a link gap earlier in the recording
        feed(1_000, 30f)
        feed(1_000, 1f)
        assertThat(reports.last().live.phase).isEqualTo(HOLD)

        t = 0
        feed(3_000, 1f)   // calm across the restart
        feed(1_000, 30f)
        feed(1_000, 1f)

        assertThat(reports.filter { it.at < 1_000_000 }.map { it.live })
            .containsExactly(LivePhase(IDLE, 0), LivePhase(DRAW, 3_000), LivePhase(HOLD, 4_000)).inOrder()
    }

    @Test
    fun `the phase callback runs outside the detector lock`() {
        var readFinishedInCallback = false
        lateinit var locked: ShotCycleDetector
        locked = ShotCycleDetector(onPhase = {
            Thread { locked.bufferedSamples }.apply { start(); join(1_000); readFinishedInCallback = !isAlive }
        }) { }

        locked.onSample(sample(0, 30f))

        assertThat(readFinishedInCallback).isTrue()
    }
}
