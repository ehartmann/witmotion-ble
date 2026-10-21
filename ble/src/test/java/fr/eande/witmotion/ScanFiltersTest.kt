package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanFiltersTest {

    @Test
    fun `one address filter per valid registered address`() {
        val filters = ImuSensorManager.scanFilters(listOf("AA:BB:CC:DD:EE:FF", "not-an-address", "11:22:33:44:55:66"))

        assertThat(filters.map { it.deviceAddress }).containsExactly("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66").inOrder()
    }
}
