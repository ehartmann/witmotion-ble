package fr.eande.witmotion.archery

import com.google.common.truth.Truth.assertThat
import fr.eande.witmotion.ImuSample
import org.junit.Test
import kotlin.math.abs

class CircularMeanTest {

    private fun sample(time: Long, roll: Float, gyro: Float = 0.5f, accel: Float = 1f) = ImuSample(
        timestamp = time,
        gyroscope = floatArrayOf(gyro, 0f, 0f),
        acceleration = floatArrayOf(0f, 0f, accel),
        euler = floatArrayOf(roll, 10f, 0f)
    )

    @Test
    fun `mean of +179 and -179 is about 180`() {
        assertThat(abs(circularMean(listOf(179f, -179f)))).isWithin(0.1f).of(180f)
    }

    @Test
    fun `mean of angles that do not wrap is the usual mean`() {
        assertThat(circularMean(listOf(10f, 20f, 30f))).isWithin(0.01f).of(20f)
    }

    @Test
    fun `mean of nothing is NaN`() {
        assertThat(circularMean(emptyList()).isNaN()).isTrue()
    }

    @Test
    fun `cant angle of a roll straddling the wrap is about 180`() {
        val samples = (0 until 10).map { sample(it * 20L, roll = if (it % 2 == 0) 179f else -179f, gyro = 25f) } +
            (10 until 40).map { sample(it * 20L, roll = if (it % 2 == 0) 179f else -179f) }
        val analyzer = BowMovementAnalyzer()

        val cant = analyzer.computeMetrics(samples, analyzer.detectPhases(samples))!!.cantAngle

        assertThat(abs(cant)).isWithin(1f).of(180f)
    }

    @Test
    fun `a roll crossing the wrap between hold and release is not a broken wrist`() {
        val samples = mutableListOf<ImuSample>()
        var t = 0L
        repeat(10) { samples += sample(t, 179f, gyro = 25f); t += 20 }
        repeat(30) { samples += sample(t, 179f); t += 20 }
        repeat(45) { samples += sample(t, -179f, accel = if (it == 0) 4f else 1f); t += 20 }

        val result = ShotErrorClassifier.classify(samples, BowMovementAnalyzer().detectPhases(samples))

        assertThat(result.primaryError).isEqualTo(ShotErrorClassifier.ShotError.CLEAN)
    }
}
