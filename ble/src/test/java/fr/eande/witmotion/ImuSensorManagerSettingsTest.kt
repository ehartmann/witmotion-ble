package fr.eande.witmotion

import android.bluetooth.BluetoothProfile
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.system.measureTimeMillis

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

    private val infoReads = listOf("FF AA 27 2E 00", "FF AA 27 40 00", "FF AA 27 05 00", "FF AA 27 0D 00", "FF AA 27 64 00")

    /** The four info replies, in another order than the reads. */
    private fun answerInfo() {
        h.notify(GattHarness.registerReply(0x0D, 4))
        h.notify(GattHarness.registerReply(0x40, 0x0992))
        h.notify(GattHarness.registerReply(0x05, 12, 0xFFFB, 210, 0, 0, 0xFFFF, 30, 0xFFF4))
        h.notify(GattHarness.registerReply(0x2E, 0x0116, 0x89D8))
    }

    private val fullInfo = ImuSensorManager.SensorInfo(
        version1 = 0x0116, version2 = 0x89D8, firmware = "278.137.216", temperatureC = 24.5f,
        accelOffsets = listOf(12, -5, 210), gyroOffsets = listOf(0, 0, -1), magOffsets = listOf(30, -12, 4),
    )

    @Test
    fun `readInfo sends the five reads without the unlock, 100 ms apart, and decodes the replies`() = runBlocking<Unit> {
        linkedUp()
        val info = async(start = CoroutineStart.UNDISPATCHED) { h.manager.readInfo() }
        h.drain()
        answerInfo()

        assertThat(info.await()).isEqualTo(fullInfo)
        assertThat(h.written.map(::hex)).containsExactlyElementsIn(infoReads).inOrder()
        assertThat(h.writeTimes.zipWithNext { a, b -> b - a }.all { it >= 100 }).isTrue()
    }

    @Test
    fun `a missing reply leaves its fields null after the timeout, and a negative temperature is signed`() = runBlocking<Unit> {
        linkedUp()
        val info = async(start = CoroutineStart.UNDISPATCHED) { h.manager.readInfo(timeoutMs = 300) }
        h.drain()
        h.notify(GattHarness.registerReply(0x40, 0xFF38))
        h.notify(GattHarness.registerReply(0x05, 12, 0xFFFB, 210))

        assertThat(info.await()).isEqualTo(ImuSensorManager.SensorInfo(
            temperatureC = -2.0f, accelOffsets = listOf(12, -5, 210), gyroOffsets = listOf(0, 0, 0)))
    }

    @Test
    fun `readInfo while disconnected returns an empty SensorInfo at once and writes nothing`() = runBlocking<Unit> {
        val ms = measureTimeMillis { assertThat(h.manager.readInfo()).isEqualTo(ImuSensorManager.SensorInfo()) }
        assertThat(ms).isLessThan(500)
        assertThat(h.written).isEmpty()
    }

    @Test
    fun `info replies leave linkStats alone`() = runBlocking<Unit> {
        linkedUp()
        val before = h.manager.linkStats.value
        val info = async(start = CoroutineStart.UNDISPATCHED) { h.manager.readInfo() }
        h.drain()
        answerInfo()
        info.await()

        assertThat(h.manager.linkStats.value.copy(notifications = before.notifications)).isEqualTo(before)
    }

    @Test   // Review Focus 1
    fun `a link drop while readInfo waits leaves its fields null, even when a reply then comes`() = runBlocking<Unit> {
        linkedUp()
        val info = async(start = CoroutineStart.UNDISPATCHED) { h.manager.readInfo(timeoutMs = 300) }
        h.manager.disconnect()
        answerInfo()

        assertThat(info.await()).isEqualTo(ImuSensorManager.SensorInfo())
    }

    @Test   // Review Focus 2
    fun `two overlapping readInfo calls both get their replies`() = runBlocking<Unit> {
        linkedUp()
        val first = async(start = CoroutineStart.UNDISPATCHED) { h.manager.readInfo() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { h.manager.readInfo() }
        h.drain()
        answerInfo()
        delay(100)   // the first returns, the second sends its reads
        h.drain()
        answerInfo()

        assertThat(first.await()).isEqualTo(fullInfo)
        assertThat(second.await()).isEqualTo(fullInfo)
        assertThat(h.written.map(::hex)).containsExactlyElementsIn(infoReads + infoReads).inOrder()
    }

    @Test   // Review Focus 3
    fun `readInfo reads during a clock check`() = runBlocking<Unit> {
        linkedUp()
        h.manager.clockChecks.begin()
        val info = async(start = CoroutineStart.UNDISPATCHED) { h.manager.readInfo() }
        h.drain()
        answerInfo()

        assertThat(info.await()).isEqualTo(fullInfo)
    }

    private val unlock = "FF AA 69 88 B5"
    private val save = "FF AA 00 00 00"
    private val bootSave = listOf(unlock, "FF AA 23 00 00", save, "FF AA 27 23 00")

    private fun relink() {
        h.manager.gattCallback.onConnectionStateChange(h.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        h.injectGatt()
        h.connect()
    }

    @Test
    fun `saveConfig writes the unlock and the save, and raises no restart flag`() = runBlocking<Unit> {
        linkedUp()
        assertThat(h.manager.saveConfig()).isTrue()
        h.drain()

        assertThat(h.written.map(::hex)).containsExactly(unlock, save).inOrder()
        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
    }

    @Test
    fun `saveConfig waits for the pending link-up read and is written after its save`() = runBlocking<Unit> {
        h.connect()
        h.forgetWrites()
        val saved = async(start = CoroutineStart.UNDISPATCHED) { h.manager.saveConfig() }
        delay(200)
        assertThat(h.written).isEmpty()

        h.notify(GattHarness.registerReply(0x23, 0x01))
        delay(200)
        h.drain()

        assertThat(saved.await()).isTrue()
        assertThat(h.written.map(::hex)).containsExactlyElementsIn(bootSave + listOf(unlock, save)).inOrder()
    }

    @Test
    fun `saveConfig and forceInstallation refuse while disconnected and during a clock check`() = runBlocking<Unit> {
        assertThat(h.manager.saveConfig()).isFalse()
        assertThat(h.manager.forceInstallation()).isFalse()
        assertThat(h.written).isEmpty()

        linkedUp()
        h.manager.clockChecks.begin()
        assertThat(h.manager.saveConfig()).isFalse()
        assertThat(h.manager.forceInstallation()).isFalse()
        assertThat(h.written).isEmpty()
        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
    }

    @Test
    fun `a link drop during the wait for the link-up read refuses the save`() = runBlocking<Unit> {
        h.connect()
        h.forgetWrites()
        val saved = async(start = CoroutineStart.UNDISPATCHED) { h.manager.saveConfig() }
        h.manager.disconnect()

        assertThat(saved.await()).isFalse()
        assertThat(h.written).isEmpty()
    }

    @Test
    fun `forceInstallation writes the installation, the save and the read, and raises restartNeeded until acknowledged`() = runBlocking<Unit> {
        linkedUp()
        assertThat(h.manager.forceInstallation()).isTrue()
        h.drain()

        assertThat(h.written.map(::hex)).containsExactlyElementsIn(bootSave).inOrder()
        assertThat(h.manager.linkStats.value.restartNeeded).isTrue()
        relink()
        assertThat(h.manager.linkStats.value.restartNeeded).isTrue()
        h.manager.acknowledgeRestart()
        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
    }

    @Test
    fun `factoryReset writes the reset, then the unlock and the link-up settings with their read-backs, 100 ms apart`() = runBlocking<Unit> {
        linkedUp()
        assertThat(h.manager.factoryReset()).isTrue()
        h.drain()

        val settings = ImuSensorManager.settingsCommands(h.manager.outputRate, h.manager.installation, h.manager.algorithm, h.manager.bandwidth).map(::hex)
        assertThat(h.written.map(::hex)).containsExactlyElementsIn(listOf(unlock, "FF AA 00 01 00", unlock) + settings).inOrder()
        assertThat(h.writeTimes.zipWithNext { a, b -> b - a }.all { it >= 100 }).isTrue()
        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
    }

    @Test
    fun `factoryReset re-arms the rate rewrite`() = runBlocking<Unit> {
        linkedUp()
        h.notify(GattHarness.registerReply(0x03, OutputRate.HZ_200.code))
        h.drain()
        h.manager.factoryReset()
        h.drain()
        h.forgetWrites()

        h.notify(GattHarness.registerReply(0x03, OutputRate.HZ_200.code))
        h.drain()

        assertThat(h.written.count { it.contentEquals(h.manager.outputRate.command) }).isEqualTo(1)
    }

    @Test
    fun `factoryReset refuses while disconnected and during a clock check`() = runBlocking<Unit> {
        assertThat(h.manager.factoryReset()).isFalse()
        linkedUp()
        h.manager.clockChecks.begin()
        assertThat(h.manager.factoryReset()).isFalse()
        assertThat(h.written).isEmpty()
    }

    @Test
    fun `isValidName accepts WT then 1 to 14 letters, digits, - or _`() {
        val cases = mapOf(
            "WT" to false, "WTA" to true, "WT-Arc" to true, "WT_9" to true,
            "WT" + "A".repeat(14) to true, "WT" + "A".repeat(15) to false,
            "wtA" to false, "WT A" to false, "WTé" to false, "WT\r\n" to false,
        )
        cases.forEach { (name, valid) -> assertWithMessage(name).that(ImuSensorManager.isValidName(name)).isEqualTo(valid) }
    }

    @Test
    fun `writeName sends WT, the whole name and CR LF after the unlock, and raises no restart flag`() = runBlocking<Unit> {
        linkedUp()
        assertThat(h.manager.writeName("WT-Arc")).isTrue()
        h.drain()

        assertThat(h.written.map(::hex)).containsExactly(unlock, "57 54 57 54 2D 41 72 63 0D 0A").inOrder()
        assertThat(h.manager.linkStats.value.restartNeeded).isFalse()
    }

    @Test
    fun `the longest name is one 20-byte write`() = runBlocking<Unit> {
        linkedUp()
        h.manager.writeName("WT" + "A".repeat(14))
        h.drain()

        assertThat(h.written.last().size).isEqualTo(20)
    }

    @Test
    fun `writeName throws on an invalid name and writes nothing`() {
        linkedUp()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { h.manager.writeName("WT" + "A".repeat(15)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { h.manager.writeName("Arc") } }
        assertThat(h.written).isEmpty()
    }

    @Test
    fun `writeName refuses while disconnected and during a clock check`() = runBlocking<Unit> {
        assertThat(h.manager.writeName("WT-Arc")).isFalse()
        linkedUp()
        h.manager.clockChecks.begin()
        assertThat(h.manager.writeName("WT-Arc")).isFalse()
        assertThat(h.written).isEmpty()
    }
}
