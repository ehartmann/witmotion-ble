package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImuSampleTest {

    @Test
    fun `samples with the same timestamp but other readings are not equal`() {
        val a = ImuSample(timestamp = 100, acceleration = floatArrayOf(0f, 0f, 1f))
        val b = ImuSample(timestamp = 100, acceleration = floatArrayOf(0f, 0f, 2f))

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `samples with the same timestamp and readings are equal`() {
        val a = ImuSample(timestamp = 100, acceleration = floatArrayOf(0f, 0f, 1f), euler = floatArrayOf(1f, 2f, 3f))
        val b = ImuSample(timestamp = 100, acceleration = floatArrayOf(0f, 0f, 1f), euler = floatArrayOf(1f, 2f, 3f))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
