package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Register 0x1F (BANDWIDTH) and register 0x96 (output content), protocol v25-04-06, sections 3.9 and 3.11. */
class BandwidthTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `bandwidth commands write register 0x1F`() {
        assertThat(hex(Bandwidth.HZ_256.command)).isEqualTo("FF AA 1F 00 00")
        assertThat(hex(Bandwidth.HZ_188.command)).isEqualTo("FF AA 1F 01 00")
        assertThat(hex(Bandwidth.HZ_98.command)).isEqualTo("FF AA 1F 02 00")
        assertThat(hex(Bandwidth.HZ_42.command)).isEqualTo("FF AA 1F 03 00")
        assertThat(hex(Bandwidth.HZ_20.command)).isEqualTo("FF AA 1F 04 00")
        assertThat(hex(Bandwidth.HZ_10.command)).isEqualTo("FF AA 1F 05 00")
        assertThat(hex(Bandwidth.HZ_5.command)).isEqualTo("FF AA 1F 06 00")
        assertThat(hex(ImuSensorManager.READ_BANDWIDTH)).isEqualTo("FF AA 27 1F 00")
    }

    @Test
    fun `suggestedFor keeps the cut-off under the output Nyquist frequency`() {
        assertThat(Bandwidth.suggestedFor(200)).isEqualTo(Bandwidth.HZ_98)
        assertThat(Bandwidth.suggestedFor(100)).isEqualTo(Bandwidth.HZ_42)
        assertThat(Bandwidth.suggestedFor(50)).isEqualTo(Bandwidth.HZ_20)
        assertThat(Bandwidth.suggestedFor(10)).isEqualTo(Bandwidth.HZ_20)
    }

    @Test
    fun `fromCode reads the low nibble and rejects unknown values`() {
        assertThat(Bandwidth.fromCode(3)).isEqualTo(Bandwidth.HZ_42)
        assertThat(Bandwidth.fromCode(0x14)).isEqualTo(Bandwidth.HZ_20)
        assertThat(Bandwidth.fromCode(7)).isNull()
    }

    @Test
    fun `output content commands write register 0x96`() {
        assertThat(hex(OutputContent.ANGLES.command)).isEqualTo("FF AA 96 00 00")
        assertThat(hex(OutputContent.TIMESTAMP.command)).isEqualTo("FF AA 96 02 00")
    }
}
