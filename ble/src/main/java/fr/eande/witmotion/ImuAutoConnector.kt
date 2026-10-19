package fr.eande.witmotion

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Scans for registered IMU devices and auto-connects to the first one found.
 *
 * Call [tryAutoConnect] once from MainActivity.onCreate for the app-start connect. It also
 * watches [ImuSensorManager.unexpectedDisconnects] for the whole app lifetime (a process
 * singleton held by WitnessApp, like [ImuSensorManager] itself) and retries with [retryAfterUnexpectedDisconnect]
 * when the sensor drops mid-session instead of leaving it disconnected until the app restarts.
 */
class ImuAutoConnector(
    private val sensorManager: ImuSensorManager,
    private val store: RegisteredImuStore,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ImuAutoConnector"
        private const val SCAN_DURATION_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_BACKOFF_MS = 3000L
    }

    /** Surfaced so a screen can show a Snackbar while a mid-session reconnect is in flight. */
    sealed interface ReconnectEvent {
        data object Started : ReconnectEvent
        data object Succeeded : ReconnectEvent
        data object Exhausted : ReconnectEvent
    }

    private var autoConnectJob: Job? = null

    private val _reconnectEvents = MutableSharedFlow<ReconnectEvent>(extraBufferCapacity = 1)
    val reconnectEvents: SharedFlow<ReconnectEvent> = _reconnectEvents.asSharedFlow()

    init {
        // shortcut: one collector for the process lifetime since WitnessApp creates this class
        // once — revisit only if ImuAutoConnector ever stops being a singleton.
        scope.launch {
            sensorManager.unexpectedDisconnects.collect { retryAfterUnexpectedDisconnect() }
        }
    }

    /** Single scan-and-connect attempt against registered devices. */
    private suspend fun attemptConnect(): Boolean {
        if (sensorManager.isConnected()) return true
        if (store.getAll().isEmpty()) return false

        var connected = false
        sensorManager.onDeviceDiscovered = { device ->
            if (!connected && store.isRegistered(device.address)) {
                connected = true
                Log.d(TAG, "Found registered device: ${device.name} (${device.address})")
                sensorManager.connect(device)
            }
        }
        sensorManager.startScan()
        delay(SCAN_DURATION_MS)
        if (!connected) sensorManager.stopScan()
        sensorManager.onDeviceDiscovered = null
        return connected
    }

    fun tryAutoConnect() {
        if (sensorManager.isConnected()) return
        if (store.getAll().isEmpty()) return

        autoConnectJob?.cancel()
        autoConnectJob = scope.launch {
            Log.d(TAG, "Starting auto-connect scan...")
            if (!attemptConnect()) {
                Log.d(TAG, "Auto-connect scan finished — no registered device found")
            }
        }
    }

    /**
     * Retries a bounded number of times (with backoff) after ImuSensorManager reports an
     * unprompted disconnect. Safe to call redundantly: idempotent-guarded by
     * [attemptConnect] and cancels any in-flight attempt of its own first.
     */
    fun retryAfterUnexpectedDisconnect() {
        if (sensorManager.isConnected()) return
        if (store.getAll().isEmpty()) return

        autoConnectJob?.cancel()
        autoConnectJob = scope.launch {
            _reconnectEvents.emit(ReconnectEvent.Started)
            repeat(MAX_RECONNECT_ATTEMPTS) { attempt ->
                if (attemptConnect()) {
                    _reconnectEvents.emit(ReconnectEvent.Succeeded)
                    return@launch
                }
                if (attempt < MAX_RECONNECT_ATTEMPTS - 1) {
                    delay(RECONNECT_BACKOFF_MS * (attempt + 1))
                }
            }
            Log.d(TAG, "Reconnect exhausted after $MAX_RECONNECT_ATTEMPTS attempts")
            _reconnectEvents.emit(ReconnectEvent.Exhausted)
        }
    }

    fun cancel() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        sensorManager.onDeviceDiscovered = null
    }
}
