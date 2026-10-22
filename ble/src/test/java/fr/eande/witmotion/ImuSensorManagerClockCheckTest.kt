package fr.eande.witmotion

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** A check must end as soon as the link goes away or is replaced: see [ClockCheckSlot.abort]. */
@RunWith(RobolectricTestRunner::class)
class ImuSensorManagerClockCheckTest {

    private lateinit var manager: ImuSensorManager

    @Before
    fun setUp() {
        manager = ImuSensorManager(RuntimeEnvironment.getApplication())
    }

    private fun assertAborted(check: ClockCollector) {
        assertThat(check.isAborted).isTrue()
        assertThat(manager.clockChecks.collector).isNull()
    }

    @Test
    fun `disconnect aborts a running check`() {
        val check = manager.clockChecks.begin()

        manager.disconnect()

        assertAborted(check)
    }

    @Test
    fun `the disconnected callback aborts a running check`() {
        val check = manager.clockChecks.begin()

        manager.gattCallback.onConnectionStateChange(mockk<BluetoothGatt>(relaxed = true), 0, BluetoothProfile.STATE_DISCONNECTED)

        assertAborted(check)
    }

    @Test
    fun `connect aborts a running check`() {
        val check = manager.clockChecks.begin()

        manager.connect(ImuSensorDevice(address = "AA:BB:CC:DD:EE:FF", name = "WT9011DCL"))

        assertAborted(check)
    }

    @Test
    fun `a late disconnected callback from a replaced GATT leaves the new link's check alone`() {
        manager.connect(ImuSensorDevice(address = "AA:BB:CC:DD:EE:FF", name = "WT9011DCL"))
        val check = manager.clockChecks.begin()

        manager.gattCallback.onConnectionStateChange(mockk<BluetoothGatt>(relaxed = true), 0, BluetoothProfile.STATE_DISCONNECTED)

        assertThat(check.isAborted).isFalse()
    }

    @Test
    fun `a connected callback leaves a running check alone`() {
        val check = manager.clockChecks.begin()

        manager.gattCallback.onConnectionStateChange(mockk<BluetoothGatt>(relaxed = true), 0, BluetoothProfile.STATE_CONNECTED)

        assertThat(check.isAborted).isFalse()
    }
}
