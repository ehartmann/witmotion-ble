package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The notification and connect pipeline of [ImuSensorManager], through its GATT callback. */
@RunWith(RobolectricTestRunner::class)
class ImuSensorManagerPipelineTest {

    private val h = GattHarness()

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `a packed notification gives one listener call per frame, in increasing time, and counts in linkStats`() {
        val samples = mutableListOf<ImuSample>()
        h.manager.addSampleListener { samples += it }
        h.connect()

        h.notify(GattHarness.frame(1000) + GattHarness.frame(2000))

        assertThat(samples).hasSize(2)
        assertThat(samples.map { it.timestamp }).isInStrictOrder()
        assertThat(samples.map { it.euler!![0] }).isInStrictOrder()
        assertThat(h.manager.lastSample.value).isEqualTo(samples.last())
        val stats = h.manager.linkStats.value
        assertThat(stats.notifications).isEqualTo(1)
        assertThat(stats.combinedFrames).isEqualTo(2)
    }

    @Test
    fun `register replies update the codes in linkStats`() {
        h.connect()

        h.notify(GattHarness.registerReply(0x03, 0x0B))
        h.notify(GattHarness.registerReply(0x23, 0x01))
        h.notify(GattHarness.registerReply(0x24, 0x00))
        h.notify(GattHarness.registerReply(0x1F, 0x02))

        val stats = h.manager.linkStats.value
        assertThat(stats.outputRateCode).isEqualTo(0x0B)
        assertThat(stats.installationCode).isEqualTo(0x01)
        assertThat(stats.algorithmCode).isEqualTo(0x00)
        assertThat(stats.bandwidthCode).isEqualTo(0x02)
    }

    @Test
    fun `a battery reply updates the level`() {
        h.connect()

        h.notify(GattHarness.registerReply(0x64, 400))

        assertThat(h.manager.batteryLevel.value).isEqualTo(100)
    }

    @Test
    fun `discovering the services writes the unlock, the settings and the reads, 100 ms apart`() {
        h.connect()

        val expected = listOf(ImuSensorManager.UNLOCK) +
            ImuSensorManager.settingsCommands(h.manager.outputRate, h.manager.installation, h.manager.algorithm, h.manager.bandwidth) +
            ImuSensorManager.READ_BATTERY
        assertThat(h.written.map(::hex)).containsExactlyElementsIn(expected.map(::hex)).inOrder()
        // the battery read follows the last setting at once: it is not one of the paced commands
        assertThat(h.writeTimes.dropLast(1).zipWithNext { a, b -> b - a }.all { it >= 100 }).isTrue()
    }

    @Test
    fun `the calibration helper sends the unlock then its command`() {
        h.connect()
        h.forgetWrites()
        val helper = ImuCalibrationHelper(h.manager)

        helper.startAccelCalibration()
        h.drain()
        helper.startMagCalibration()
        h.drain()
        helper.finishCalibration()
        h.drain()

        assertThat(h.written.map(::hex)).containsExactly(
            "FF AA 69 88 B5", "FF AA 01 01 00",
            "FF AA 69 88 B5", "FF AA 01 07 00",
            "FF AA 69 88 B5", "FF AA 01 00 00", "FF AA 00 00 00"
        ).inOrder()
    }
}
