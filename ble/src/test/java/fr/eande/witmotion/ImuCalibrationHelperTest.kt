package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Command bytes from the WT9011DCL-BT50 communication protocol v25-04-06, section 3.2. */
class ImuCalibrationHelperTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `calibration commands match the protocol`() {
        assertThat(hex(ImuCalibrationHelper.ACCEL_CAL_START)).isEqualTo("FF AA 01 01 00")
        assertThat(hex(ImuCalibrationHelper.MAG_CAL_START)).isEqualTo("FF AA 01 07 00")
        assertThat(hex(ImuCalibrationHelper.CAL_EXIT)).isEqualTo("FF AA 01 00 00")
        assertThat(hex(ImuSensorManager.SAVE_CONFIG)).isEqualTo("FF AA 00 00 00")
    }

    @Test
    fun `config and register-read commands match the protocol`() {
        assertThat(hex(ImuSensorManager.UNLOCK)).isEqualTo("FF AA 69 88 B5")
        assertThat(hex(OutputRate.HZ_50.command)).isEqualTo("FF AA 03 08 00")
        assertThat(hex(OutputRate.HZ_100.command)).isEqualTo("FF AA 03 09 00")
        assertThat(hex(OutputRate.HZ_200.command)).isEqualTo("FF AA 03 0B 00")
        assertThat(hex(ImuSensorManager.READ_BATTERY)).isEqualTo("FF AA 27 64 00")
        assertThat(hex(ImuSensorManager.READ_RATE)).isEqualTo("FF AA 27 03 00")
        assertThat(hex(Installation.HORIZONTAL.command)).isEqualTo("FF AA 23 00 00")
        assertThat(hex(Installation.VERTICAL.command)).isEqualTo("FF AA 23 01 00")
        assertThat(hex(ImuSensorManager.READ_INSTALLATION)).isEqualTo("FF AA 27 23 00")
    }

    @Test
    fun `a rate register read back maps to its rate`() {
        assertThat(OutputRate.fromCode(0x08)).isEqualTo(OutputRate.HZ_50)
        assertThat(OutputRate.fromCode(0x0B)).isEqualTo(OutputRate.HZ_200)
        assertThat(OutputRate.fromCode(0x0A)).isNull()
        assertThat(Installation.fromCode(0x01)).isEqualTo(Installation.VERTICAL)
        assertThat(Installation.fromCode(0x02)).isNull()
    }
}
