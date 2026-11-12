package fr.eande.witmotion.archery

import com.google.common.truth.Truth.assertThat
import fr.eande.witmotion.ImuSample
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

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

    /** [ms] of 50 Hz samples from [start]: [gyro] °/s, gravity [tilt] degrees from the aim (+z), given the ms into the stretch. */
    private fun stretch(start: Long, ms: Long, gyro: Float, tilt: (Long) -> Double = { 0.0 }) =
        (0 until ms step 20).map { dt ->
            val rad = Math.toRadians(tilt(dt))
            ImuSample(start + dt, acceleration = floatArrayOf(0f, sin(rad).toFloat(), cos(rad).toFloat()), gyroscope = floatArrayOf(gyro, 0f, 0f))
        }

    /** From [start]: the bow rising 3 s from 60° off the aim, still [pauseMs], draw 2 s, hold 3 s, the release shock, 3.1 s of follow-through. */
    private fun raiseToShot(start: Long, pauseMs: Long = 0): List<ImuSample> {
        val draw = start + 3_000 + pauseMs
        return stretch(start, 3_000, 25f) { dt -> 60.0 * (1 - dt / 3_000.0) } + stretch(start + 3_000, pauseMs, 0f) +
            stretch(draw, 2_000, 25f) + stretch(draw + 2_000, 3_000, 1f) +
            ImuSample(draw + 5_000, acceleration = floatArrayOf(3f, 0f, 1f), gyroscope = floatArrayOf(1f, 0f, 0f)) +
            stretch(draw + 5_020, 3_100, 2f)
    }

    private fun detectRealistic(samples: List<ImuSample>, maxDrawMs: Long = 30_000): ShotCycleDetector.DetectedShot {
        val detector = detector(analyzer = BowMovementAnalyzer(), afterReleaseMs = 3_000, maxDrawMs = maxDrawMs)
        samples.forEach(detector::onSample)
        return detectedShots.single()
    }

    @Test
    fun `a shot with a raise starts a second before it and carries its boundaries`() {
        // ShotBoundariesTest's blended raise and draw, after 2 s at rest
        val stream = stretch(0, 2_000, 0f) { 60.0 } + raiseToShot(2_000)
        val b = BowMovementAnalyzer().shotBoundaries(stream)!!

        val shot = detectRealistic(stream)

        assertThat(shot.samples.first().timestamp).isEqualTo(2_000L - ShotCycleDetector.RAISE_LEAD_MS)
        assertThat(listOf(shot.raiseStartTimestamp, shot.drawStartTimestamp, shot.holdStartTimestamp, shot.releaseTimestamp))
            .containsExactlyElementsIn(listOf(b.raiseStartIndex!!, b.drawStartIndex, b.holdStartIndex, b.releaseIndex).map { stream[it].timestamp })
            .inOrder()
        assertThat(shot.raiseStartTimestamp).isEqualTo(2_000L)
        assertThat(shot.drawStartTimestamp).isIn(4_500L..4_600L)
        assertThat(shot.holdStartTimestamp).isEqualTo(7_000L)
        assertThat(shot.releaseTimestamp).isEqualTo(10_000L)
    }

    @Test
    fun `a 5 s raise under 20 deg per s and a 0_8 s pause keep their 1 s lead only with the 10 s buffer`() {
        val draw = 2_000L + 5_000 + 800
        val stream = stretch(0, 2_000, 0f) { 60.0 } + stretch(2_000, 5_000, 15f) { dt -> 60.0 * (1 - dt / 5_000.0) } +
            stretch(7_000, 800, 0f) + stretch(draw, 2_000, 25f) + stretch(draw + 2_000, 3_000, 1f) +
            ImuSample(draw + 5_000, acceleration = floatArrayOf(3f, 0f, 1f), gyroscope = floatArrayOf(1f, 0f, 0f)) +
            stretch(draw + 5_020, 3_100, 2f)

        val shot = detectRealistic(stream)
        assertThat(shot.raiseStartTimestamp).isNotNull()
        assertThat(shot.samples.first().timestamp).isEqualTo(shot.raiseStartTimestamp!! - 1_000)

        detectedShots.clear()
        val short = ShotCycleDetector(BowMovementAnalyzer(), afterReleaseMs = 3_000, idleKeepMs = 6_000) { detectedShots.add(it) }
        stream.forEach(short::onSample)
        val cut = detectedShots.single()
        assertThat(cut.raiseStartTimestamp).isNotNull()
        assertThat(cut.samples.first().timestamp).isGreaterThan(cut.raiseStartTimestamp!! - 1_000)
    }

    @Test
    fun `a raise, a pause, then a draw keeps the hold of the aim in the metrics, not the pause`() {
        val shot = detectRealistic(stretch(0, 2_000, 0f) { 60.0 } + raiseToShot(2_000, pauseMs = 1_000))

        assertThat(shot.samples.first().timestamp).isEqualTo(shot.raiseStartTimestamp!! - ShotCycleDetector.RAISE_LEAD_MS)
        assertThat(shot.metrics!!.holdTimeMs).isEqualTo(2_980L)
    }

    @Test
    fun `a stuck-draw restart keeps the raise of the shot in progress`() {
        // The bow lowered at 0 starts the clock, rests 24 s, rises at 24.7 s: the 30 s restart falls in the hold
        val stream = stretch(0, 700, 30f) { 60.0 } + stretch(700, 24_000, 0f) { 60.0 } + raiseToShot(24_700)

        val shot = detectRealistic(stream)

        assertThat(shot.raiseStartTimestamp).isEqualTo(24_700L)
        assertThat(shot.samples.first().timestamp).isEqualTo(24_700L - ShotCycleDetector.RAISE_LEAD_MS)
        assertThat(shot.drawStartTimestamp).isIn(27_200L..27_300L)
        assertThat(shot.releaseTimestamp).isEqualTo(32_700L)
    }

    @Test
    fun `a stuck-draw restart keeps exactly idleKeepMs before the latest draw and restarts the clock there`() {
        // As above, but the bow is held at the aim with no release
        val stream = stretch(0, 700, 30f) { 60.0 } + stretch(700, 24_000, 0f) { 60.0 } +
            stretch(24_700, 3_000, 25f) { dt -> 60.0 * (1 - dt / 3_000.0) } + stretch(27_700, 2_000, 25f) + stretch(29_700, 31_000, 1f)
        val detector = detector(analyzer = BowMovementAnalyzer(), afterReleaseMs = 3_000)
        val stuckDrops = mutableListOf<Long>()
        detector.observer = object : ShotCycleDetector.Observer {
            override fun shot(buffer: List<ImuSample>, from: Int) = Unit
            override fun dropped(buffer: List<ImuSample>, upTo: Int, stuck: Boolean) { if (stuck) stuckDrops += buffer.last().timestamp }
        }

        stream.filter { it.timestamp <= 30_020 }.forEach(detector::onSample)
        assertThat(detector.bufferedSamples).isEqualTo((30_020 - 14_700) / 20 + 1)   // from 24 700 - 10 000
        stream.filter { it.timestamp > 30_020 }.forEach(detector::onSample)

        // Restarted at 30 020; the clock then runs from the raise at 24 700, so all is dropped 30 s later
        assertThat(stuckDrops).containsExactly(30_020L, 54_720L).inOrder()
        assertThat(detectedShots).isEmpty()
    }

    @Test
    fun `a window cut inside a raise by a clock reset has no raise and a draw start at the aim`() {
        // The clock restarts from 0 halfway up the raise
        val reset = stretch(1_000_000, 1_000, 0f) { 60.0 } + raiseToShot(-1_500).filter { it.timestamp >= 0 }

        val shot = detectRealistic(reset, maxDrawMs = 9_000)

        assertThat(shot.raiseStartTimestamp).isNull()
        assertThat(shot.samples.first().timestamp).isEqualTo(0L)
        assertThat(shot.drawStartTimestamp).isIn(1_000L..1_100L)
    }

    @Test
    fun `a raise less than RAISE_LEAD_MS after the oldest sample starts the shot at that sample`() {
        val shot = detectRealistic(stretch(0, 500, 0f) { 60.0 } + raiseToShot(500))

        assertThat(shot.raiseStartTimestamp).isEqualTo(500L)
        assertThat(shot.samples.first().timestamp).isEqualTo(0L)
    }

    @Test
    fun `flush emits a shot cut short with its raise and boundaries`() {
        val detector = detector(analyzer = BowMovementAnalyzer(), afterReleaseMs = 3_000)
        (stretch(0, 2_000, 0f) { 60.0 } + raiseToShot(2_000)).filter { it.timestamp <= 11_000 }.forEach(detector::onSample)
        assertThat(detectedShots).isEmpty()

        detector.flush()

        val shot = detectedShots.single()
        assertThat(shot.samples.first().timestamp).isEqualTo(1_000L)
        assertThat(listOf(shot.raiseStartTimestamp, shot.holdStartTimestamp, shot.releaseTimestamp))
            .containsExactly(2_000L, 7_000L, 10_000L).inOrder()
        assertThat(shot.drawStartTimestamp).isIn(4_500L..4_600L)
    }

    @Test
    fun `a shot without a raise starts at its last draw as before`() {
        val detector = detector()
        detector.feed(200, 30f)                            // draw, let down
        detector.feed(400, 1f)
        val lastDrawAt = t
        detector.shot()

        val shot = detectedShots.single()
        assertThat(shot.samples.first().timestamp).isEqualTo(lastDrawAt)
        assertThat(shot.samples.last().timestamp).isEqualTo(lastDrawAt + 600 + 400)
        assertThat(shot.raiseStartTimestamp).isNull()
        assertThat(shot.drawStartTimestamp).isEqualTo(lastDrawAt)
        assertThat(shot.holdStartTimestamp).isEqualTo(lastDrawAt + 200)
        assertThat(shot.releaseTimestamp).isEqualTo(lastDrawAt + 600)
    }

    @Test
    fun `a shot more than maxDraw after handling the bow is still found`() {
        // 2026-10-20, 100 Hz: the bow lowered after a shot started the clock, the next release came 30.2 s later
        val detector = detector(maxDrawMs = 30_000)
        detector.feed(700, 30f)    // bow lowered after the previous shot
        detector.feed(17_000, 6f)  // waiting, bow moving a little
        detector.feed(2_000, 1f)   // still
        detector.feed(2_500, 30f)  // raise and draw
        detector.feed(9_800, 1f)   // long aim: the release comes 32 s after the handling
        detector.release()
        detector.feed(600, 2f)

        assertThat(detectedShots).hasSize(1)
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
    fun `a shot starts at the last draw, earlier bow handling left out`() {
        val detector = detector()
        detector.feed(200, 30f)                            // draw, let down
        detector.feed(400, 1f)
        val lastDrawAt = t
        detector.shot()

        assertThat(detectedShots).hasSize(1)
        assertThat(detectedShots[0].samples.first().timestamp).isEqualTo(lastDrawAt)
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
    fun `a negative idleKeepMs is rejected at construction`() {
        val thrown = runCatching { ShotCycleDetector(idleKeepMs = -1) { } }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        ShotCycleDetector(idleKeepMs = 0) { }
    }

    @Test
    fun `memory stays bounded while waiting for a draw`() {
        val detector = detector()
        detector.feed(600_000, 0f)                         // 10 minutes connected, at rest

        assertThat(detector.bufferedSamples).isEqualTo(10_000 / 20 + 1)    // the last 10 s, for a raise before the draw
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

    @Test
    fun `the shot callback runs outside the detector lock`() {
        var readFinishedInCallback = false
        lateinit var detector: ShotCycleDetector
        detector = ShotCycleDetector(quickAnalyzer, afterReleaseMs = 400) { shot ->
            detectedShots.add(shot)
            Thread { detector.bufferedSamples }.apply { start(); join(1_000); readFinishedInCallback = !isAlive }
        }
        detector.shot()

        assertThat(detectedShots).hasSize(1)
        assertThat(readFinishedInCallback).isTrue()
    }

    @Test
    fun `timestamps restarting from zero reset the buffer`() {
        val detector = detector()
        repeat(500) { detector.onSample(sample(1_000_000L + it * 20L)) }
        detector.onSample(sample(0))
        detector.onSample(sample(20))

        assertThat(detector.bufferedSamples).isEqualTo(2)
    }

    @Test
    fun `a clock restart with a draw pending reports the dropped window`() {
        val detector = detector()
        var dropped = 0
        detector.observer = object : ShotCycleDetector.Observer {
            override fun shot(buffer: List<ImuSample>, from: Int) = Unit
            override fun dropped(buffer: List<ImuSample>, upTo: Int, stuck: Boolean) { dropped++ }
        }
        detector.feed(400, 30f)
        detector.onSample(sample(0))

        assertThat(dropped).isEqualTo(1)
    }
}
