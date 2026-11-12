package fr.eande.witmotion

import android.bluetooth.BluetoothGatt
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImuSensorManagerConnectTest {
    private lateinit var h: GattHarness
    private val opened = mutableListOf<Pair<String, Boolean>>()
    private val drops = mutableListOf<LinkDrop>()
    private val device = ImuSensorDevice(address = "3C:A3:08:12:34:56", name = "WT9011DCL")

    @Before
    fun setUp() {
        h = GattHarness()
        h.manager.openGatt = { d, auto -> opened += d.address to auto; h.gatt }
        CoroutineScope(Dispatchers.Unconfined).launch { h.manager.unexpectedDisconnects.collect { drops += it } }
    }

    @Test fun `isRandomStatic reads the top two bits`() {
        assertThat(isRandomStatic("DD:55:8A:E4:85:B9")).isTrue()
        assertThat(isRandomStatic("C0:00:00:00:00:01")).isTrue()
        assertThat(isRandomStatic("BF:FF:FF:FF:FF:FF")).isFalse()
        assertThat(isRandomStatic("3C:A3:08:12:34:56")).isFalse()
    }

    @Test fun `a pending connect asks for an auto connect and is not dropped at 20 s`() {
        h.manager.connectByAddress(device.address, pending = true)
        assertThat(opened.single()).isEqualTo("3C:A3:08:12:34:56" to true)
        h.idle(25_000)
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTING)
        assertThat(drops).isEmpty()
    }

    @Test fun `a pending connect to a random static address opens that address`() {
        h.manager.connectByAddress("DD:55:8A:E4:85:B9", pending = true)
        assertThat(opened.single().first).isEqualTo("DD:55:8A:E4:85:B9")
        assertThat(opened.single().second).isTrue()
    }

    @Test fun `a pending connect's watchdog starts when the link comes up`() {
        h.manager.connectByAddress(device.address, pending = true)
        h.manager.gattCallback.onConnectionStateChange(h.gatt, BluetoothGatt.GATT_SUCCESS, android.bluetooth.BluetoothProfile.STATE_CONNECTED)
        h.idle(19_999)
        assertThat(drops).isEmpty()
        h.idle(1)
        assertThat(drops.map { it.cause }).containsExactly(DropCause.LINK_LOST)
    }

    @Test fun `a direct connect still asks for a direct link`() {
        h.manager.connectByAddress(device.address)
        assertThat(opened.single().second).isFalse()
    }

    @Test fun `cancelPendingConnect cancels only the keeper's pending connect`() {
        h.manager.connectByAddress(device.address, pending = true)
        assertThat(h.manager.cancelPendingConnect()).isTrue()
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.DISCONNECTED)
        assertThat(drops).isEmpty()

        h.manager.connect(device)
        assertThat(h.manager.cancelPendingConnect()).isFalse()
        assertThat(h.manager.connectionState.value).isEqualTo(ImuSensorManager.ConnectionState.CONNECTING)
    }
}
