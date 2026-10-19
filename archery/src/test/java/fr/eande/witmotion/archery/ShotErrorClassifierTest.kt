package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShotErrorClassifierTest {

    private fun sample(
        time: Long,
        gyro: FloatArray = floatArrayOf(0f, 0f, 0f),
        accel: FloatArray = floatArrayOf(0f, 0f, 1f),
        euler: FloatArray = floatArrayOf(0f, 0f, 0f)
    ) = ImuSample(timestamp = time, gyroscope = gyro, acceleration = accel, euler = euler)

    @Test
    fun `clean shot returns CLEAN with high confidence`() {
        val samples = mutableListOf<ImuSample>()
        var t = 0L
        // Draw
        repeat(10) { samples.add(sample(t, gyro = floatArrayOf(25f, 0f, 0f))); t += 20 }
        // Stable hold
        repeat(30) { samples.add(sample(t, gyro = floatArrayOf(0.5f, 0f, 0f), euler = floatArrayOf(2f, 10f, 0f))); t += 20 }
        // Clean release
        samples.add(sample(t, accel = floatArrayOf(3f, 0f, 1f), euler = floatArrayOf(2f, 10f, 0f))); t += 20
        // Follow-through (stable)
        repeat(15) { samples.add(sample(t, gyro = floatArrayOf(1f, 0f, 0f), euler = floatArrayOf(2f, 9.5f, 0f))); t += 20 }

        val analyzer = BowMovementAnalyzer()
        val phases = analyzer.detectPhases(samples)
        val result = ShotErrorClassifier.classify(samples, phases)

        assertThat(result.primaryError).isEqualTo(ShotErrorClassifier.ShotError.CLEAN)
    }

    @Test
    fun `flinch detected on large acceleration spike`() {
        val samples = mutableListOf<ImuSample>()
        var t = 0L
        repeat(10) { samples.add(sample(t, gyro = floatArrayOf(25f, 0f, 0f))); t += 20 }
        repeat(30) { samples.add(sample(t, gyro = floatArrayOf(0.5f, 0f, 0f), euler = floatArrayOf(0f, 10f, 0f))); t += 20 }
        // Flinch: massive spike
        samples.add(sample(t, accel = floatArrayOf(12f, 8f, 5f), euler = floatArrayOf(0f, 10f, 0f))); t += 20
        repeat(15) { samples.add(sample(t, gyro = floatArrayOf(1f, 0f, 0f), euler = floatArrayOf(0f, 10f, 0f))); t += 20 }

        val analyzer = BowMovementAnalyzer()
        val phases = analyzer.detectPhases(samples)
        val result = ShotErrorClassifier.classify(samples, phases)

        assertThat(result.primaryError).isEqualTo(ShotErrorClassifier.ShotError.FLINCH)
        assertThat(result.confidence).isGreaterThan(0.5f)
    }

    @Test
    fun `dropping arm detected on pitch drop in follow-through`() {
        val samples = mutableListOf<ImuSample>()
        var t = 0L
        repeat(10) { samples.add(sample(t, gyro = floatArrayOf(25f, 0f, 0f))); t += 20 }
        repeat(30) { samples.add(sample(t, gyro = floatArrayOf(0.5f, 0f, 0f), euler = floatArrayOf(0f, 20f, 0f))); t += 20 }
        samples.add(sample(t, accel = floatArrayOf(4f, 0f, 1f), euler = floatArrayOf(0f, 20f, 0f))); t += 20
        // Follow-through: pitch drops 15° (arm dropped) — needs >500ms = 30 samples at 20ms
        repeat(30) { samples.add(sample(t, gyro = floatArrayOf(2f, 0f, 0f), euler = floatArrayOf(0f, 5f, 0f))); t += 20 }

        val analyzer = BowMovementAnalyzer()
        val phases = analyzer.detectPhases(samples)
        val result = ShotErrorClassifier.classify(samples, phases)

        assertThat(result.primaryError).isEqualTo(ShotErrorClassifier.ShotError.DROPPING_ARM)
    }

    @Test
    fun `anticipation detected when late hold is noisy`() {
        val samples = mutableListOf<ImuSample>()
        var t = 0L
        repeat(10) { samples.add(sample(t, gyro = floatArrayOf(25f, 0f, 0f))); t += 20 }
        // Early hold: very stable
        repeat(20) { samples.add(sample(t, gyro = floatArrayOf(0.3f, 0f, 0f), euler = floatArrayOf(0f, 10f, 0f))); t += 20 }
        // Late hold: getting noisy (anticipation)
        repeat(10) { samples.add(sample(t, gyro = floatArrayOf(3f, 2f, 0f), euler = floatArrayOf(0f, 10f, 0f))); t += 20 }
        samples.add(sample(t, accel = floatArrayOf(4f, 0f, 1f))); t += 20
        repeat(10) { samples.add(sample(t, gyro = floatArrayOf(1f, 0f, 0f))); t += 20 }

        val analyzer = BowMovementAnalyzer()
        val phases = analyzer.detectPhases(samples)
        val result = ShotErrorClassifier.classify(samples, phases)

        assertThat(result.primaryError).isEqualTo(ShotErrorClassifier.ShotError.ANTICIPATION)
    }

    @Test
    fun `insufficient data returns CLEAN with low confidence`() {
        val result = ShotErrorClassifier.classify(emptyList(), emptyList())
        assertThat(result.primaryError).isEqualTo(ShotErrorClassifier.ShotError.CLEAN)
        assertThat(result.confidence).isLessThan(0.5f)
    }
}
