package fr.eande.witmotion

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Looper
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Drives [ImuSensorManager.gattCallback] against a mocked [BluetoothGatt]: records every
 * command written, completes them one by one and moves the main looper's virtual clock.
 * Robolectric tests only.
 */
internal class GattHarness(val manager: ImuSensorManager = ImuSensorManager(RuntimeEnvironment.getApplication())) {
    val gatt = mockk<BluetoothGatt>(relaxed = true)
    val written = mutableListOf<ByteArray>()
    val writeTimes = mutableListOf<Long>()
    val cccd = mockk<BluetoothGattDescriptor>(relaxed = true)
    val notifyChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
    private val writeChar = mockk<BluetoothGattCharacteristic>(relaxed = true)
    private var legacyValue = ByteArray(0)
    private var completed = 0

    init {
        val service = mockk<BluetoothGattService>()
        every { service.getCharacteristic(ImuSensorManager.WRITE_CHAR_UUID) } returns writeChar
        every { service.getCharacteristic(ImuSensorManager.NOTIFY_CHAR_UUID) } returns notifyChar
        every { notifyChar.getDescriptor(ImuSensorManager.CCCD_UUID) } returns cccd
        every { cccd.uuid } returns ImuSensorManager.CCCD_UUID
        every { gatt.getService(ImuSensorManager.SERVICE_UUID) } returns service
        every { gatt.writeDescriptor(cccd, any<ByteArray>()) } returns BluetoothStatusCodes.SUCCESS
        every { gatt.writeDescriptor(cccd) } returns true
        every { gatt.writeCharacteristic(writeChar, any<ByteArray>(), any()) } answers {
            record(secondArg())
            BluetoothStatusCodes.SUCCESS
        }
        every { writeChar.setValue(any<ByteArray>()) } answers { legacyValue = firstArg(); true }
        every { gatt.writeCharacteristic(writeChar) } answers { record(legacyValue); true }
        injectGatt()
    }

    /** Leaves the manager without a link, as when connectGatt returned null. */
    fun clearGatt() = ImuSensorManager::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(manager, null)

    /** Makes [gatt] the manager's link again, after a connect() replaced it. */
    fun injectGatt() = ImuSensorManager::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(manager, gatt)

    private fun record(bytes: ByteArray) {
        written += bytes.copyOf()
        writeTimes += SystemClock.elapsedRealtime()
    }

    /** Forgets the writes recorded so far. */
    fun forgetWrites() {
        written.clear()
        writeTimes.clear()
        completed = 0
    }

    fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Link up and services found. */
    fun discoverServices() {
        manager.gattCallback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        manager.gattCallback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
    }

    fun descriptorWritten(status: Int = BluetoothGatt.GATT_SUCCESS) = manager.gattCallback.onDescriptorWrite(gatt, cccd, status)

    /** Completes every command written so far and those it releases, [ms] apart as the manager paces them. */
    fun drain(ms: Long = 100) {
        while (completed < written.size) {
            completed++
            manager.gattCallback.onCharacteristicWrite(gatt, writeChar, BluetoothGatt.GATT_SUCCESS)
            idle(ms)
        }
    }

    /** A full connect: services, notifications enabled, all the settings written. */
    fun connect() {
        discoverServices()
        descriptorWritten()
        drain()
    }

    fun notify(bytes: ByteArray) = manager.gattCallback.onCharacteristicChanged(gatt, notifyChar, bytes)

    companion object {
        fun int16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())

        /** A 55 61 frame; [roll] in raw units tells the frames apart. */
        fun frame(roll: Int = 0) = byteArrayOf(0x55, 0x61) + ByteArray(12) + int16(roll) + ByteArray(4)

        /** A 55 71 reply to a read of [register]: its value [code], then seven zeros. */
        fun registerReply(register: Int, code: Int) =
            byteArrayOf(0x55, 0x71) + int16(register) + int16(code) + ByteArray(14)
    }
}
