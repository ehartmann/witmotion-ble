package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Register 0x24 (AXIS6) from WitMotion's standard communication protocol. */
class AlgorithmTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `command writes register 0x24`() {
        assertThat(hex(Algorithm.AXIS_9.command)).isEqualTo("FF AA 24 00 00")
        assertThat(hex(Algorithm.AXIS_6.command)).isEqualTo("FF AA 24 01 00")
        assertThat(hex(ImuSensorManager.READ_ALGORITHM)).isEqualTo("FF AA 27 24 00")
    }

    @Test
    fun `fromCode maps 0 and 1`() {
        assertThat(Algorithm.fromCode(0)).isEqualTo(Algorithm.AXIS_9)
        assertThat(Algorithm.fromCode(1)).isEqualTo(Algorithm.AXIS_6)
    }

    @Test
    fun `unknown code is null`() {
        assertThat(Algorithm.fromCode(2)).isNull()
    }

    @Test
    fun `a 55 71 reply for register 0x24 decodes its value`() {
        val reply = byteArrayOf(0x55, 0x71, 0x24, 0x00, 0x01, 0x00) + ByteArray(14)

        assertThat(ImuDataParser.parseCounting(reply).registers.single()).isEqualTo(
            ImuDataParser.RegisterReply(0x24, listOf(1, 0, 0, 0, 0, 0, 0, 0))
        )
    }
}
