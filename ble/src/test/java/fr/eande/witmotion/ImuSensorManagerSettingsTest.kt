package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImuSensorManagerSettingsTest {
    private val h = GattHarness()
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    /** Connected, the link-up read answered horizontal (nothing saved, no wait left), the writes so far forgotten. */
    private fun linkedUp() {
        h.connect()
        h.notify(GattHarness.registerReply(0x23, 0x00))
        h.drain()
        h.forgetWrites()
    }

    @Test
    fun `a battery reply sets batteryVolts beside batteryLevel`() {
        h.connect()
        assertThat(h.manager.batteryVolts.value).isNull()

        h.notify(GattHarness.registerReply(0x64, 392))

        assertThat(h.manager.batteryVolts.value).isWithin(1e-4f).of(3.92f)
        assertThat(h.manager.batteryLevel.value).isEqualTo(75)
    }
}
