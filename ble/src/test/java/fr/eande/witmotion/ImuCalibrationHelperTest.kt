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
}
