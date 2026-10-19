package fr.eande.witmotion

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Covers the mid-session reconnect behavior added to close the gap where an unexpected BLE
 * drop (out of range, low battery, sensor sleep) left the sensor disconnected until the app
 * was restarted -- ImuAutoConnector.tryAutoConnect() previously ran only once, from
 * MainActivity.onCreate.
 *
 * ImuAutoConnector is constructed on `backgroundScope`: its init block starts a collector on
 * ImuSensorManager.unexpectedDisconnects that runs for the connector's whole lifetime by
 * design, which runTest's own scope would otherwise flag as an uncompleted coroutine.
 *
 * Notable gotcha, found by tracing an actual stuck test rather than assumed: advanceUntilIdle()
 * does NOT reliably drive a delay() inside a backgroundScope coroutine once the main test body
 * has no more of its own work queued -- it can return while that coroutine is still parked
 * inside delay(). advanceTimeBy(duration) + runCurrent() does drive it, and is what every test
 * here uses instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImuAutoConnectorTest {

    private lateinit var store: RegisteredImuStore
    private lateinit var sensorManager: ImuSensorManager
    private lateinit var unexpectedDisconnects: MutableSharedFlow<Unit>
    private val onDeviceDiscoveredSlot = slot<(ImuSensorDevice) -> Unit>()

    // Covers every attempt's scan delay plus the backoff between them; see MAX_RECONNECT_ATTEMPTS
    // / RECONNECT_BACKOFF_MS in ImuAutoConnector -- 3 attempts of 5s scans + (3s + 6s) backoff.
    private val fullRetrySequenceMs = 30_000L

    @Before
    fun setUp() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        store = RegisteredImuStore(prefs)

        unexpectedDisconnects = MutableSharedFlow(extraBufferCapacity = 1)
        sensorManager = mockk(relaxed = true) {
            every { unexpectedDisconnects } returns this@ImuAutoConnectorTest.unexpectedDisconnects
            every { onDeviceDiscovered = capture(onDeviceDiscoveredSlot) } just Runs
        }
    }

    @Test
    fun `retryAfterUnexpectedDisconnect does nothing when already connected`() = runTest {
        every { sensorManager.isConnected() } returns true
        store.register("AA:BB:CC:DD:EE:FF", "WT9011DCL")
        val connector = ImuAutoConnector(sensorManager, store, backgroundScope)

        connector.retryAfterUnexpectedDisconnect()
        advanceTimeBy(fullRetrySequenceMs)
        runCurrent()

        verify(exactly = 0) { sensorManager.startScan() }
    }

    @Test
    fun `retryAfterUnexpectedDisconnect does nothing with no registered devices`() = runTest {
        every { sensorManager.isConnected() } returns false
        val connector = ImuAutoConnector(sensorManager, store, backgroundScope)

        connector.retryAfterUnexpectedDisconnect()
        advanceTimeBy(fullRetrySequenceMs)
        runCurrent()

        verify(exactly = 0) { sensorManager.startScan() }
    }

    @Test
    fun `retryAfterUnexpectedDisconnect gives up after the bounded number of attempts`() = runTest {
        every { sensorManager.isConnected() } returns false
        store.register("AA:BB:CC:DD:EE:FF", "WT9011DCL")
        val connector = ImuAutoConnector(sensorManager, store, backgroundScope)
        val events = mutableListOf<ImuAutoConnector.ReconnectEvent>()
        backgroundScope.launch { connector.reconnectEvents.collect { events.add(it) } }

        connector.retryAfterUnexpectedDisconnect()
        // Discovery is never simulated, so every attempt fails -- the loop must stop on its
        // own rather than scanning forever.
        advanceTimeBy(fullRetrySequenceMs)
        runCurrent()

        verify(exactly = 3) { sensorManager.startScan() }
        assertThat(events).containsExactly(
            ImuAutoConnector.ReconnectEvent.Started,
            ImuAutoConnector.ReconnectEvent.Exhausted
        ).inOrder()
    }

    @Test
    fun `retryAfterUnexpectedDisconnect succeeds when a registered device is discovered`() = runTest {
        every { sensorManager.isConnected() } returns false
        store.register("AA:BB:CC:DD:EE:FF", "WT9011DCL")
        val connector = ImuAutoConnector(sensorManager, store, backgroundScope)
        val events = mutableListOf<ImuAutoConnector.ReconnectEvent>()
        backgroundScope.launch { connector.reconnectEvents.collect { events.add(it) } }

        connector.retryAfterUnexpectedDisconnect()
        runCurrent() // let the first attempt run up to its scan delay, capturing onDeviceDiscovered
        onDeviceDiscoveredSlot.captured.invoke(ImuSensorDevice("AA:BB:CC:DD:EE:FF", "WT9011DCL", rssi = -50))
        advanceTimeBy(fullRetrySequenceMs)
        runCurrent()

        verify(exactly = 1) { sensorManager.connect(match { it.address == "AA:BB:CC:DD:EE:FF" }) }
        assertThat(events).containsExactly(
            ImuAutoConnector.ReconnectEvent.Started,
            ImuAutoConnector.ReconnectEvent.Succeeded
        ).inOrder()
    }

    @Test
    fun `an unexpected disconnect from ImuSensorManager triggers a reconnect attempt`() = runTest {
        every { sensorManager.isConnected() } returns false
        store.register("AA:BB:CC:DD:EE:FF", "WT9011DCL")
        ImuAutoConnector(sensorManager, store, backgroundScope)
        runCurrent() // let the init-block collector actually subscribe before emitting

        unexpectedDisconnects.tryEmit(Unit)
        advanceTimeBy(fullRetrySequenceMs)
        runCurrent()

        verify(atLeast = 1) { sensorManager.startScan() }
    }
}
