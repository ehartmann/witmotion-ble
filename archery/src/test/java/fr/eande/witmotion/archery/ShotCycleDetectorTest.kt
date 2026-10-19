package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class ShotCycleDetectorTest {

    private lateinit var detector: ShotCycleDetector
    private val detectedShots = mutableListOf<ShotCycleDetector.DetectedShot>()

    @Before
    fun setUp() {
        detectedShots.clear()
        detector = ShotCycleDetector(
            analyzer = BowMovementAnalyzer(
                holdThreshold = 5f,
                drawThreshold = 20f,
                releaseAccelThreshold = 2f,
                minHoldMs = 100,
                followThroughMs = 200
            ),
            idleConfirmSamples = 5,
            onShotDetected = { detectedShots.add(it) }
        )
    }

    private fun sample(
        time: Long,
        gyro: FloatArray = floatArrayOf(0f, 0f, 0f),
        accel: FloatArray = floatArrayOf(0f, 0f, 1f),
        euler: FloatArray = floatArrayOf(0f, 0f, 0f)
    ) = ImuSample(
        timestamp = time,
        gyroscope = gyro,
        acceleration = accel,
        euler = euler
    )

    private fun feedFullShotCycle(startTime: Long): Long {
        var t = startTime
        // DRAW
        repeat(10) { detector.onSample(sample(t, gyro = floatArrayOf(30f, 0f, 0f))); t += 20 }
        // HOLD
        repeat(20) {
            detector.onSample(sample(t, gyro = floatArrayOf(1f, 0f, 0f), euler = floatArrayOf(2f, 0f, 0f)))
            t += 20
        }
        // RELEASE
        detector.onSample(sample(t, gyro = floatArrayOf(1f, 0f, 0f), accel = floatArrayOf(5f, 2f, 3f)))
        t += 20
        // FOLLOW-THROUGH (enough to exceed followThroughMs = 200ms)
        repeat(15) {
            detector.onSample(sample(t, gyro = floatArrayOf(2f, 0f, 0f)))
            t += 20
        }
        return t
    }

    @Test
    fun `detects complete shot cycle`() {
        var t = 0L
        // IDLE
        repeat(5) { detector.onSample(sample(t)); t += 20 }
        t = feedFullShotCycle(t)
        // Return to IDLE to trigger shot finalization
        repeat(10) { detector.onSample(sample(t)); t += 20 }

        assertThat(detectedShots).hasSize(1)
        assertThat(detectedShots[0].samples.size).isGreaterThan(10)
        assertThat(detectedShots[0].metrics).isNotNull()
        assertThat(detectedShots[0].metrics!!.holdScore).isGreaterThan(0f)
        // Verify shot starts at DRAW, not IDLE
        val firstSampleGyro = detectedShots[0].samples.first().gyroscope!![0]
        assertThat(firstSampleGyro).isGreaterThan(20f)
    }

    @Test
    fun `no shot detected without release`() {
        var t = 0L
        // IDLE
        repeat(5) { detector.onSample(sample(t)); t += 20 }
        // DRAW
        repeat(10) { detector.onSample(sample(t, gyro = floatArrayOf(30f, 0f, 0f))); t += 20 }
        // Back to IDLE without hold/release
        repeat(10) { detector.onSample(sample(t)); t += 20 }

        assertThat(detectedShots).isEmpty()
    }

    @Test
    fun `no shot detected with hold but no release`() {
        var t = 0L
        // DRAW
        repeat(10) { detector.onSample(sample(t, gyro = floatArrayOf(30f, 0f, 0f))); t += 20 }
        // HOLD (stable, no accel spike)
        repeat(20) {
            detector.onSample(sample(t, gyro = floatArrayOf(1f, 0f, 0f)))
            t += 20
        }
        // Back to IDLE (let-down, no release)
        repeat(15) { detector.onSample(sample(t)); t += 20 }

        assertThat(detectedShots).isEmpty()
    }

    @Test
    fun `detects two consecutive shots`() {
        var t = 0L

        repeat(2) {
            t = feedFullShotCycle(t)
            // IDLE gap between shots
            repeat(10) { detector.onSample(sample(t)); t += 20 }
        }

        assertThat(detectedShots).hasSize(2)
    }

    @Test
    fun `flush emits in-progress shot if past follow-through`() {
        var t = 0L
        t = feedFullShotCycle(t)
        // No return to IDLE — flush
        detector.flush()

        assertThat(detectedShots).hasSize(1)
        assertThat(detectedShots[0].metrics).isNotNull()
    }

    @Test
    fun `buffer resets on stuck draw exceeding max size`() {
        val smallBufferDetector = ShotCycleDetector(
            analyzer = BowMovementAnalyzer(
                holdThreshold = 5f,
                drawThreshold = 20f,
                releaseAccelThreshold = 2f,
                minHoldMs = 100,
                followThroughMs = 200
            ),
            maxBufferSize = 100,
            idleConfirmSamples = 5,
            onShotDetected = { detectedShots.add(it) }
        )

        var t = 0L
        // DRAW that never transitions to HOLD (stuck — exceeds maxBufferSize)
        repeat(110) {
            smallBufferDetector.onSample(sample(t, gyro = floatArrayOf(30f, 0f, 0f)))
            t += 20
        }
        // Should have reset — now a valid shot should still work
        // DRAW
        repeat(10) { smallBufferDetector.onSample(sample(t, gyro = floatArrayOf(30f, 0f, 0f))); t += 20 }
        // HOLD
        repeat(20) {
            smallBufferDetector.onSample(sample(t, gyro = floatArrayOf(1f, 0f, 0f), euler = floatArrayOf(2f, 0f, 0f)))
            t += 20
        }
        // RELEASE
        smallBufferDetector.onSample(sample(t, gyro = floatArrayOf(1f, 0f, 0f), accel = floatArrayOf(5f, 2f, 3f)))
        t += 20
        // FOLLOW-THROUGH
        repeat(15) {
            smallBufferDetector.onSample(sample(t, gyro = floatArrayOf(2f, 0f, 0f)))
            t += 20
        }
        // IDLE
        repeat(10) { smallBufferDetector.onSample(sample(t)); t += 20 }

        // No shot from the stuck draw, but the valid shot should be detected
        assertThat(detectedShots).hasSize(1)
    }
}
