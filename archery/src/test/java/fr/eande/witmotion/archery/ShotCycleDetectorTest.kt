package fr.eande.witmotion.archery

import com.google.common.truth.Truth.assertThat
import fr.eande.witmotion.ImuSample
import org.junit.Test

class ShotCycleDetectorTest {

    private val detectedShots = mutableListOf<ShotCycleDetector.DetectedShot>()
    private var t = 0L

    /** Short phases for the edge-case tests; the realistic tests use the default analyzer. */
    private val quickAnalyzer = BowMovementAnalyzer(minHoldMs = 100, followThroughMs = 200)

    private fun detector(
        analyzer: BowMovementAnalyzer = quickAnalyzer,
        afterReleaseMs: Long = 400,
        maxDrawMs: Long = 30_000
    ) = ShotCycleDetector(analyzer, afterReleaseMs = afterReleaseMs, maxDrawMs = maxDrawMs) { detectedShots.add(it) }

    private fun sample(
        time: Long,
        gyro: Float = 0f,
        accel: FloatArray = floatArrayOf(0f, 0f, 1f)
    ) = ImuSample(timestamp = time, gyroscope = floatArrayOf(gyro, 0f, 0f), acceleration = accel, euler = floatArrayOf(2f, 0f, 0f))

    /** Feeds [durationMs] of samples every [stepMs] with the given angular velocity. */
    private fun ShotCycleDetector.feed(durationMs: Long, gyro: Float, stepMs: Long = 20) {
        val end = t + durationMs
        while (t < end) {
            onSample(sample(t, gyro))
            t += stepMs
        }
    }

    private fun ShotCycleDetector.release(stepMs: Long = 20) {
        onSample(sample(t, 1f, accel = floatArrayOf(5f, 2f, 3f)))
        t += stepMs
    }

    /** Draw, hold, release and [followMs] of follow-through. */
    private fun ShotCycleDetector.shot(drawMs: Long = 200, holdMs: Long = 400, followMs: Long = 600, stepMs: Long = 20) {
        feed(drawMs, 30f, stepMs)
        feed(holdMs, 1f, stepMs)
        release(stepMs)
        feed(followMs, 2f, stepMs)
    }

    @Test
    fun `a typical 9 s shot is emitted 3 s after the release, draw included`() {
        val detector = detector(analyzer = BowMovementAnalyzer(), afterReleaseMs = 3_000)
        detector.feed(1_000, 0f)                           // idle
        detector.feed(1_000, 30f)                          // draw
        detector.feed(5_000, 1f)                           // aim
        val releaseAt = t
        detector.release()
        detector.feed(2_900, 2f)                           // follow-through so far
        assertThat(detectedShots).isEmpty()

        detector.feed(200, 2f)                             // 3 s after the release

        assertThat(detectedShots).hasSize(1)
        val shot = detectedShots[0]
        assertThat(shot.samples.first().gyroscope!![0]).isEqualTo(30f)   // starts at the draw
        assertThat(shot.samples.last().timestamp - releaseAt).isAtLeast(3_000L)
        assertThat(shot.samples.last().timestamp - shot.samples.first().timestamp).isAtLeast(9_000L)
        assertThat(shot.metrics).isNotNull()
    }

    @Test
    fun `limits are durations, so a 100 Hz sensor gives the same shot`() {
        val detector = detector(analyzer = BowMovementAnalyzer(), afterReleaseMs = 3_000)
        detector.shot(drawMs = 1_000, holdMs = 5_000, followMs = 3_100, stepMs = 10)

        assertThat(detectedShots).hasSize(1)
        assertThat(detectedShots[0].samples.size).isGreaterThan(900)
    }

    @Test
    fun `a 25 Hz shot whose gyro reacts before the spike is detected`() {
        val detector = detector(analyzer = BowMovementAnalyzer(), afterReleaseMs = 3_000)
        detector.feed(2_000, 30f, stepMs = 40)
        detector.feed(5_000, 1f, stepMs = 40)
        detector.onSample(sample(t, 40f)); t += 40         // the bow turns first...
        detector.release(stepMs = 40)                      // ...then the string jolt
        detector.feed(3_100, 2f, stepMs = 40)

        assertThat(detectedShots).hasSize(1)
    }

    @Test
    fun `no shot without a release`() {
        val detector = detector()
        detector.feed(100, 0f)
        detector.feed(200, 30f)
        detector.feed(400, 1f)                             // hold, then let down
        detector.feed(300, 0f)

        assertThat(detectedShots).isEmpty()
    }

    @Test
    fun `detects two consecutive shots`() {
        val detector = detector()
        repeat(2) {
            detector.shot()
            detector.feed(200, 0f)
        }

        assertThat(detectedShots).hasSize(2)
        assertThat(detectedShots.map { it.metrics }).doesNotContain(null)
    }

    @Test
    fun `flush emits a shot whose follow-through was cut short`() {
        val detector = detector(afterReleaseMs = 3_000)
        detector.shot(followMs = 300)
        assertThat(detectedShots).isEmpty()

        detector.flush()

        assertThat(detectedShots).hasSize(1)
        assertThat(detectedShots[0].metrics).isNotNull()
    }

    @Test
    fun `a draw with no release within maxDrawMs is dropped, and the next shot still counts`() {
        val detector = detector(maxDrawMs = 2_000)
        detector.feed(2_200, 30f)                          // handled without shooting

        detector.feed(100, 0f)
        detector.shot()

        assertThat(detectedShots).hasSize(1)
        assertThat(detectedShots[0].samples.first().timestamp).isAtLeast(2_000L) // the stuck first 2 s are gone
    }

    @Test
    fun `memory stays bounded while waiting for a draw`() {
        val detector = detector()
        detector.feed(600_000, 0f)                         // 10 minutes connected, at rest

        assertThat(detector.bufferedSamples).isAtMost(2_000 / 20 + 1)
    }

    @Test
    fun `detectAll finds every shot of a finished recording`() {
        val recording = mutableListOf<ImuSample>()
        fun add(durationMs: Long, gyro: Float, accel: FloatArray = floatArrayOf(0f, 0f, 1f)) {
            val end = t + durationMs
            while (t < end) {
                recording += sample(t, gyro, accel); t += 20
            }
        }
        repeat(2) { shot ->
            add(100, 0f)                                    // idle
            add(200, 30f)                                   // draw
            add(400, 1f)                                    // hold
            add(20, 1f, accel = floatArrayOf(5f, 2f, 3f))   // release
            add(if (shot == 0) 600 else 200, 2f)            // shot 2 is cut by the end
        }

        val shots = ShotCycleDetector.detectAll(recording.asSequence(), quickAnalyzer, afterReleaseMs = 400)

        assertThat(shots).hasSize(2)
        assertThat(shots.map { it.metrics }).doesNotContain(null)
    }

    @Test
    fun `detectAll on a recording without a shot returns nothing`() {
        val idle = List(100) { sample(it * 20L) }
        assertThat(ShotCycleDetector.detectAll(idle.asSequence())).isEmpty()
    }
}
