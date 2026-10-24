package fr.eande.witmotion

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    @Test
    fun `a rate read back that differs is written again once and flagged until it matches`() {
        h.connect()
        h.forgetWrites()
        val rateWrites = { h.written.count { it.contentEquals(h.manager.outputRate.command) } }

        h.notify(GattHarness.registerReply(0x03, OutputRate.HZ_200.code))
        h.drain()
        assertThat(rateWrites()).isEqualTo(1)
        assertThat(h.manager.linkStats.value.rateMismatch).isTrue()

        h.notify(GattHarness.registerReply(0x03, OutputRate.HZ_200.code))
        h.drain()
        assertThat(rateWrites()).isEqualTo(1)
        assertThat(h.manager.linkStats.value.rateMismatch).isTrue()

        h.notify(GattHarness.registerReply(0x03, h.manager.outputRate.code))
        assertThat(h.manager.linkStats.value.rateMismatch).isFalse()
    }

    private val scanner = mockk<BluetoothLeScanner>(relaxed = true)
    private val scanCallback = slot<ScanCallback>()

    private fun scanningHarness(): GattHarness {
        val adapter = mockk<BluetoothAdapter> {
            every { isEnabled } returns true
            every { bluetoothLeScanner } returns scanner
        }
        val bluetooth = mockk<BluetoothManager> { every { this@mockk.adapter } returns adapter }
        val context = mockk<Context> { every { getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetooth }
        every { scanner.startScan(any(), any(), capture(scanCallback)) } just Runs
        return GattHarness(ImuSensorManager(context))
    }

    @Test
    fun `a scan leaves a live connection's state alone`() {
        val h = scanningHarness()
        h.connect()
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)

        h.manager.startScan()
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)
        h.manager.stopScan()
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)

        h.manager.startScan()
        scanCallback.captured.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)
    }

    @Test
    fun `a scan from the disconnected state still reports scanning, then disconnected`() {
        val manager = scanningHarness().manager

        manager.startScan()
        assertThat(manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.SCANNING)

        manager.stopScan()
        assertThat(manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.DISCONNECTED)
    }

    private val device = ImuSensorDevice(address = "AA:BB:CC:DD:EE:FF", name = "WT9011DCL")

    private fun CoroutineScope.countDrops(manager: ImuSensorManager): () -> Int {
        var drops = 0
        launch { manager.unexpectedDisconnects.collect { drops++ } }
        return { drops }
    }

    @Test
    fun `the link is not connected until the notifications are enabled`() {
        h.manager.connect(device)
        h.injectGatt()
        h.discoverServices()
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTING)

        h.descriptorWritten()

        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)
        assertThat(h.manager.connectedDevice.value).isNotNull()
    }

    @Test
    fun `a failed notification enable drops the link instead of recording nothing`() {
        h.discoverServices()

        h.descriptorWritten(status = 133)

        verify { h.gatt.disconnect() }
        assertThat(h.manager.connectionState.value).isNotEqualTo(ImuSensorManager.ConnectionState.CONNECTED)
        assertThat(h.manager.connectedDevice.value).isNull()
    }

    @Test
    fun `a connect that never completes is dropped as unexpected after 20 s`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val drops = scope.countDrops(h.manager)
        h.manager.connect(device)
        h.injectGatt()

        h.idle(19_900)
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTING)
        h.idle(200)

        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.DISCONNECTED)
        assertThat(drops()).isEqualTo(1)
        verify { h.gatt.disconnect() }
        scope.cancel()
    }

    @Test
    fun `a link that came up is not dropped by the connect watchdog`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val drops = scope.countDrops(h.manager)
        h.manager.connect(device)
        h.injectGatt()
        h.connect()

        h.idle(30_000)

        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)
        assertThat(drops()).isEqualTo(0)
        scope.cancel()
    }
}
