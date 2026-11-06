package fr.eande.witmotion.archery

import com.google.common.truth.Truth.assertThat
import fr.eande.witmotion.ImuSample
import org.junit.Test

class ReleaseRotationTest {

    private val analyzer = BowMovementAnalyzer()

    /** 50 Hz samples for [seconds], spinning about x at [dps] (time in seconds). */
    private fun spin(seconds: Double, dps: (Double) -> Float): List<ImuSample> =
        (0..(seconds * 50).toInt()).map { i ->
            ImuSample(timestamp = i * 20L, gyroscope = floatArrayOf(dps(i / 50.0), 0f, 0f))
        }

    @Test fun `a free rotation settles smoothly`() {
        val r = analyzer.releaseRotation(spin(3.0) { t -> if (t <= 1.0) 100f else maxOf(0.0, 100.0 * (2.0 - t)).toFloat() }, 0)!!
        assertThat(r.amplitudeDeg).isWithin(3f).of(150f)
        assertThat(r.peakDps).isWithin(1f).of(100f)
        assertThat(r.stopMs!!.toFloat()).isWithin(20f).of(400f)
    }

    @Test fun `a rotation stopped dead`() {
        val r = analyzer.releaseRotation(spin(3.0) { t -> if (t <= 1.0) 100f else 0f }, 0)!!
        assertThat(r.amplitudeDeg).isWithin(3f).of(100f)
        assertThat(r.stopMs!!).isAtMost(20L)
    }

    @Test fun `a rotation still going at the end has no stop`() {
        val r = analyzer.releaseRotation(spin(3.0) { 100f }, 0)!!
        assertThat(r.stopMs).isNull()
    }

    @Test fun `no gyroscope in the window gives null`() {
        val samples = (0..150).map { ImuSample(timestamp = it * 20L) }
        assertThat(analyzer.releaseRotation(samples, 0)).isNull()
    }
}
