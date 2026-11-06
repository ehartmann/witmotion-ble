package fr.eande.witmotion.archery

import com.google.common.truth.Truth.assertThat
import fr.eande.witmotion.ImuSample
import fr.eande.witmotion.archery.ShotPhase.DRAW
import fr.eande.witmotion.archery.ShotPhase.HOLD
import fr.eande.witmotion.archery.ShotPhase.IDLE
import fr.eande.witmotion.archery.ShotPhase.RELEASE
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
    private fun feed(durationMs: Long, gyro: Float, to: ShotCycleDetector = detector) {
        val end = t + durationMs
        while (t < end) {
            to.onSample(sample(t, gyro))
            t += 20
        }
    }

    /** Draw 1 s, hold 3 s and the release shock, into [to]; returns the shock's timestamp. */
    private fun drawHoldRelease(to: ShotCycleDetector): Long {
        feed(1_000, 30f, to)
        feed(3_000, 1f, to)
        val spikeAt = t
        to.onSample(sample(t, 1f, accel = floatArrayOf(3f, 0f, 1f)))
        t += 20
        return spikeAt
    }

    /** A detector that records both callbacks, in order, into [events]. */
    private fun recorder(events: MutableList<Any>) =
        ShotCycleDetector(afterReleaseMs = 3_000, onPhase = { events += it }) { events += it }

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
        assertThat(afterHold.live).isEqualTo(LivePhase(RELEASE, spikeAt))
        assertThat(reports.last()).isEqualTo(Report(spikeAt + 3_000, LivePhase(IDLE, spikeAt + 3_000)))
    }

    @Test
    fun `on the sample that emits a shot, onPhase reports IDLE before onShotDetected`() {
        val events = mutableListOf<Any>()
        val both = recorder(events)
        val spikeAt = drawHoldRelease(both)
        feed(3_000, 2f, both)   // ends with the sample that emits the shot

        assertThat(events.last()).isInstanceOf(ShotCycleDetector.DetectedShot::class.java)
        assertThat(events[events.size - 2]).isEqualTo(LivePhase(IDLE, spikeAt + 3_000))
    }

    @Test
    fun `an onPhase that throws still delivers the shot of the same sample`() {
        val shots = mutableListOf<ShotCycleDetector.DetectedShot>()
        val throwing = ShotCycleDetector(afterReleaseMs = 3_000, onPhase = { if (it.phase == IDLE) error("cue failed") }) { shots += it }
        drawHoldRelease(throwing)

        val thrown = runCatching { feed(3_000, 2f, throwing) }.exceptionOrNull()

        assertThat(thrown).hasMessageThat().isEqualTo("cue failed")
        assertThat(shots).hasSize(1)
    }

    @Test
    fun `flush after a HOLD with no release delivers IDLE`() {
        val events = mutableListOf<Any>()
        val both = recorder(events)
        feed(1_000, 30f, both)
        feed(3_000, 1f, both)
        assertThat((events.last() as LivePhase).phase).isEqualTo(HOLD)

        both.flush()

        assertThat(events.last()).isEqualTo(LivePhase(IDLE, t - 20))
    }

    @Test
    fun `flush after a release delivers IDLE, then the shot`() {
        val events = mutableListOf<Any>()
        val both = recorder(events)
        drawHoldRelease(both)
        feed(500, 2f, both)
        val before = events.size

        both.flush()

        val flushed = events.drop(before)
        assertThat(flushed).hasSize(2)
        assertThat(flushed[0]).isEqualTo(LivePhase(IDLE, t - 20))
        assertThat(flushed[1]).isInstanceOf(ShotCycleDetector.DetectedShot::class.java)
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
