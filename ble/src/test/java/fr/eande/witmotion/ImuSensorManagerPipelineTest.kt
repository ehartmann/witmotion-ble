package fr.eande.witmotion

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.content.Context
import android.os.SystemClock
import com.google.common.truth.Truth.assertThat
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import io.mockk.Runs
import io.mockk.clearMocks
import org.robolectric.shadows.ShadowLog
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The notification and connect pipeline of [ImuSensorManager], through its GATT callback. */
@RunWith(RobolectricTestRunner::class)
class ImuSensorManagerPipelineTest {

    private val h = GattHarness()
    private val dropped = mutableListOf<LinkDrop>()

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
    fun `a failing sample listener does not starve the others`() {
        val recorded = mutableListOf<ImuSample>()
        h.manager.addSampleListener { error("closed DB") }
        h.manager.addSampleListener { recorded += it }
        h.connect()

        h.notify(GattHarness.frame(1000) + GattHarness.frame(2000))

        assertThat(recorded).hasSize(2)
        assertThat(h.manager.lastSample.value).isEqualTo(recorded.last())
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

    private val saveSequence = listOf("FF AA 69 88 B5", "FF AA 23 00 00", "FF AA 00 00 00", "FF AA 27 23 00")

    private fun bootedWith(code: Int) {
        h.connect()
        h.forgetWrites()
        h.notify(GattHarness.registerReply(0x23, code))
        h.drain()
    }

    @Test
    fun `a vertical boot installation is read first, then saved horizontal`() {
        h.discoverServices()
        h.descriptorWritten()
        h.drain()
        val connectWrites = h.written.map(::hex)
        h.forgetWrites()
        h.notify(GattHarness.registerReply(0x23, 0x01))
        h.drain()

        assertThat(connectWrites.take(2)).containsExactly("FF AA 69 88 B5", "FF AA 27 23 00").inOrder()
        assertThat(connectWrites).contains("FF AA 23 00 00")
        assertThat(h.written.map(::hex)).containsExactlyElementsIn(saveSequence).inOrder()
        assertThat(h.manager.linkStats.value.installationAtLinkUp).isEqualTo(1)
        assertThat(h.manager.linkStats.value.restartNeeded).isTrue()
    }

    @Test
    fun `a horizontal boot installation saves nothing`() {
        bootedWith(0x00)

        assertThat(h.written).isEmpty()
        assertThat(h.manager.linkStats.value.installationAtLinkUp).isEqualTo(0)
        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
    }

    @Test
    fun `an unknown boot installation code counts as different`() {
        bootedWith(0x07)

        assertThat(h.written.map(::hex)).containsExactlyElementsIn(saveSequence).inOrder()
        assertThat(h.manager.linkStats.value.installationAtLinkUp).isEqualTo(7)
        assertThat(h.manager.linkStats.value.restartNeeded).isTrue()
    }

    @Test
    fun `the read back after the settings write never saves`() {
        bootedWith(0x01)
        h.forgetWrites()

        h.notify(GattHarness.registerReply(0x23, 0x00))
        h.notify(GattHarness.registerReply(0x23, 0x01))
        h.drain()

        assertThat(h.written).isEmpty()
    }

    private fun relink(address: String = "AA:BB:CC:DD:EE:FF") {
        h.manager.gattCallback.onConnectionStateChange(h.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        every { h.gatt.device.address } returns address
        h.injectGatt()
        h.connect()
    }

    @Test
    fun `a second link-up reads again, and the restart flag stays after a save`() {
        bootedWith(0x01)

        relink()
        assertThat(h.manager.linkStats.value.restartNeeded).isTrue()
        assertThat(h.manager.linkStats.value.installationAtLinkUp).isNull()
        h.forgetWrites()
        h.notify(GattHarness.registerReply(0x23, 0x00))
        h.drain()

        assertThat(h.written).isEmpty()
        assertThat(h.manager.linkStats.value.installationAtLinkUp).isEqualTo(0)
        assertThat(h.manager.linkStats.value.restartNeeded).isTrue()
    }

    @Test
    fun `acknowledgeRestart clears the flag for the connected address`() {
        bootedWith(0x01)

        h.manager.acknowledgeRestart()
        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
        relink()
        h.notify(GattHarness.registerReply(0x23, 0x00))

        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
    }

    @Test
    fun `another address is not affected by a save`() {
        bootedWith(0x01)

        relink("11:22:33:44:55:66")
        h.notify(GattHarness.registerReply(0x23, 0x00))

        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
    }

    @Test
    fun `a lost link-up reply leaves the post-write read back to be taken, nothing saved`() {
        h.connect()
        h.forgetWrites()

        h.notify(GattHarness.registerReply(0x23, 0x00))
        h.drain()

        assertThat(h.written).isEmpty()
        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
    }

    @Test
    fun `a clock check waits for the pending link-up read`() {
        kotlinx.coroutines.runBlocking {
        h.connect()
        val job = async { h.manager.runClockCheck(200) }

        kotlinx.coroutines.delay(300)
        assertThat(h.manager.clockChecks.collector).isNull()
        h.notify(GattHarness.registerReply(0x23, 0x00))
        kotlinx.coroutines.delay(300)
        assertThat(h.manager.clockChecks.collector).isNotNull()
        job.cancel()
        }
    }

    @Test
    fun `a vertical manager keeps a vertical boot installation`() {
        h.manager.installation = Installation.VERTICAL
        bootedWith(0x01)

        assertThat(h.written).isEmpty()
        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
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

        val expected = listOf(ImuSensorManager.UNLOCK, ImuSensorManager.READ_INSTALLATION) +
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
        launch { manager.unexpectedDisconnects.collect { drops++; dropped += it } }
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

    private fun connecting() {
        h.manager.connect(device)
        h.injectGatt()
        clearMocks(h.gatt, answers = false)
    }

    @Test
    fun `a failed notification enable that still streams is connected, not dropped`() {
        connecting()
        h.discoverServices()

        h.descriptorWritten(status = 133)
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTING)
        h.notify(GattHarness.frame())
        h.drain()
        h.stream(30_000)

        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)
        assertThat(h.manager.connectedDevice.value).isNotNull()
        verify(exactly = 0) { h.gatt.disconnect() }
    }

    @Test
    fun `a notification enable that reports success but streams nothing is dropped after 5 s`() {
        connecting()
        h.discoverServices()

        val enabledAt = SystemClock.elapsedRealtime()
        h.descriptorWritten()
        h.drain()
        h.idle(4_900 - (SystemClock.elapsedRealtime() - enabledAt))
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)
        h.idle(200)

        verify { h.gatt.disconnect() }
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.DISCONNECTED)
    }

    @Test
    fun `a failed notification enable that streams nothing is dropped 5 s after the callback`() {
        connecting()
        h.discoverServices()

        h.descriptorWritten(status = 133)
        h.idle(5_100)

        verify { h.gatt.disconnect() }
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.DISCONNECTED)
    }

    @Test
    fun `a setting changed while connecting is written once the link is up`() {
        connecting()
        h.discoverServices()

        h.manager.outputRate = OutputRate.HZ_100
        h.descriptorWritten()
        h.drain()

        assertThat(h.written.map(::hex)).contains(hex(OutputRate.HZ_100.command))
        assertThat(h.written.map(::hex)).doesNotContain(hex(OutputRate.HZ_50.command))
    }

    @Test
    fun `a lost write callback drops the link after 2 s so the reconnect re-applies everything`() {
        h.connect()
        h.notify(GattHarness.frame())
        h.manager.sendCommands(byteArrayOf(1, 2, 3, 4, 5))

        h.idle(1_900)
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)
        h.idle(200)

        verify { h.gatt.disconnect() }
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.DISCONNECTED)
    }

    @Test
    fun `a connectGatt that returned null does not leave the state connecting forever`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val drops = scope.countDrops(h.manager)
        h.manager.connect(device)
        h.clearGatt()

        h.idle(20_100)

        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.DISCONNECTED)
        assertThat(drops()).isEqualTo(1)
        scope.cancel()
    }

    @Test
    fun `a late descriptor callback from a closed GATT changes nothing`() {
        connecting()
        h.discoverServices()

        h.manager.gattCallback.onDescriptorWrite(mockk<BluetoothGatt>(relaxed = true), h.cccd, BluetoothGatt.GATT_SUCCESS)

        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTING)
        assertThat(h.written).isEmpty()
    }

    @Test
    fun `a sample listener that keeps failing is logged once`() {
        h.manager.addSampleListener { error("closed DB") }
        h.connect()
        ShadowLog.clear()

        repeat(3) { h.notify(GattHarness.frame()) }

        assertThat(ShadowLog.getLogsForTag("ImuSensorManager").count { it.type == android.util.Log.ERROR }).isEqualTo(1)
    }

    @Test
    fun `a recording reset restarts the timestamps from zero`() {
        h.connect()
        h.idle(5_000)
        h.notify(GattHarness.frame())
        val before = h.manager.lastSample.value!!.timestamp

        h.manager.resetRecordingTimestamp()
        h.notify(GattHarness.frame())

        assertThat(before).isAtLeast(5_000L)
        assertThat(h.manager.lastSample.value!!.timestamp).isAtMost(50L)
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
        assertThat(dropped.single().cause).isEqualTo(DropCause.LINK_LOST)
        assertThat(dropped.single().lastSampleAt).isNull()
        verify { h.gatt.disconnect() }
        scope.cancel()
    }

    @Test
    fun `a drop the stack reports is LINK_LOST with the link's address and last sample time`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val drops = mutableListOf<LinkDrop>()
        scope.launch { h.manager.unexpectedDisconnects.collect { drops += it } }
        h.connect()
        h.notify(GattHarness.frame(1))
        h.notify(GattHarness.frame(2))

        h.manager.gattCallback.onConnectionStateChange(h.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)

        assertThat(drops).containsExactly(LinkDrop("AA:BB:CC:DD:EE:FF", DropCause.LINK_LOST, h.manager.lastSample.value!!.timestamp))
        scope.cancel()
    }

    @Test
    fun `a disconnect() emits no drop`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val drops = mutableListOf<LinkDrop>()
        scope.launch { h.manager.unexpectedDisconnects.collect { drops += it } }
        h.manager.connect(device)
        h.injectGatt()
        h.connect()

        h.manager.disconnect()
        h.manager.gattCallback.onConnectionStateChange(h.gatt, 0, BluetoothProfile.STATE_DISCONNECTED)

        assertThat(drops).isEmpty()
        scope.cancel()
    }

    @Test
    fun `a link that came up is not dropped by the connect watchdog`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val drops = scope.countDrops(h.manager)
        h.manager.connect(device)
        h.injectGatt()
        h.connect()
        h.notify(GattHarness.frame())

        h.stream(30_000)

        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTED)
        assertThat(drops()).isEqualTo(0)
        scope.cancel()
    }

    private fun streamDrops(): MutableList<LinkDrop> {
        val drops = mutableListOf<LinkDrop>()
        CoroutineScope(Dispatchers.Unconfined).launch { h.manager.unexpectedDisconnects.collect { drops += it } }
        return drops
    }

    private fun connectedWithOneSample() {
        h.manager.connect(device)
        h.injectGatt()
        h.connect()
        h.notify(GattHarness.frame())
    }

    @Test
    fun `no sample for 1,5 s and the probe answered is STREAM_SILENT`() {
        val drops = streamDrops()
        connectedWithOneSample()

        h.idle(1_250)
        verify(exactly = 0) { h.gatt.readDescriptor(h.cccd) }
        h.idle(250)
        verify { h.gatt.readDescriptor(h.cccd) }
        h.descriptorRead()

        assertThat(drops.single().cause).isEqualTo(DropCause.STREAM_SILENT)
    }

    @Test
    fun `a probe unanswered within 1 s is LINK_LOST`() {
        val drops = streamDrops()
        connectedWithOneSample()

        h.idle(1_500)
        verify { h.gatt.readDescriptor(h.cccd) }
        h.idle(1_000)

        assertThat(drops.single().cause).isEqualTo(DropCause.LINK_LOST)
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.DISCONNECTED)
    }

    @Test
    fun `a probe that cannot start drops the link at once`() {
        every { h.gatt.readDescriptor(h.cccd) } returns false
        val drops = streamDrops()
        connectedWithOneSample()

        h.idle(1_250)
        assertThat(drops).isEmpty()
        h.idle(250)

        assertThat(drops.single().cause).isEqualTo(DropCause.LINK_LOST)
    }

    @Test
    fun `register replies alone do not keep the watchdog quiet`() {
        connectedWithOneSample()

        repeat(8) {
            h.notify(GattHarness.registerReply(0x64, 400))
            h.idle(200)
        }

        verify { h.gatt.readDescriptor(h.cccd) }
    }

    @Test
    fun `samples every 100 ms keep the link`() {
        val drops = streamDrops()
        connectedWithOneSample()

        h.stream(3_000)

        verify(exactly = 0) { h.gatt.readDescriptor(h.cccd) }
        assertThat(drops).isEmpty()
    }

    @Test
    fun `no watchdog before the first sample`() {
        h.manager.connect(device)
        h.injectGatt()
        h.connect()

        h.idle(4_000)

        verify(exactly = 0) { h.gatt.readDescriptor(h.cccd) }
    }

    @Test
    fun `disconnect() stops the watchdog, even while a probe is pending`() {
        val drops = streamDrops()
        connectedWithOneSample()
        h.idle(1_500)
        verify { h.gatt.readDescriptor(h.cccd) }

        clearMocks(h.gatt, answers = false)
        h.manager.disconnect()
        h.injectGatt()
        h.idle(3_000)

        assertThat(drops).isEmpty()
        verify(exactly = 1) { h.gatt.disconnect() }
    }

    @Test
    fun `a new connect() stops the watchdog`() {
        connectedWithOneSample()
        h.manager.connect(device)
        h.injectGatt()
        h.connect()
        h.idle(3_000)

        verify(exactly = 0) { h.gatt.readDescriptor(h.cccd) }
    }

    @Test
    fun `a probe pending when connect() starts does not drop the new link`() {
        val drops = streamDrops()
        connectedWithOneSample()
        h.idle(1_500)
        verify { h.gatt.readDescriptor(h.cccd) }

        h.manager.connect(device)
        h.injectGatt()
        h.idle(1_500)

        assertThat(drops).isEmpty()
    }

    @Test
    fun `a notification landing just after connect() does not start a watchdog on the new link`() {
        val drops = streamDrops()
        connectedWithOneSample()
        h.manager.connect(device)
        h.injectGatt()
        h.notify(GattHarness.frame())
        h.idle(2_500)

        assertThat(drops).isEmpty()
        verify(exactly = 0) { h.gatt.readDescriptor(h.cccd) }
    }

    @Test
    fun `a probe the stack fails is LINK_LOST, not STREAM_SILENT`() {
        val drops = streamDrops()
        connectedWithOneSample()
        h.idle(1_500)

        h.descriptorRead(status = 133)

        assertThat(drops.single().cause).isEqualTo(DropCause.LINK_LOST)
    }

    @Test
    fun `a stack drop while probing gives one drop`() {
        val drops = streamDrops()
        connectedWithOneSample()
        h.idle(1_500)
        verify { h.gatt.readDescriptor(h.cccd) }

        h.manager.gattCallback.onConnectionStateChange(h.gatt, 0, BluetoothProfile.STATE_DISCONNECTED)
        h.idle(1_000)

        assertThat(drops).hasSize(1)
    }
}
