package fr.eande.witmotion

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import fr.eande.witmotion.ImuSensorManager.ConnectionState

/**
 * Scans for registered IMU devices and auto-connects to the first one found (or, on request,
 * only when a single one answers).
 *
 * Call [tryAutoConnect] for the app-start connect, as often as the app likes. It also
 * watches [ImuSensorManager.unexpectedDisconnects] for the whole app lifetime (a process
 * singleton held by WitnessApp, like [ImuSensorManager] itself) and retries with [retryAfterUnexpectedDisconnect]
 * when the sensor drops mid-session instead of leaving it disconnected until the app restarts.
 */
public class ImuAutoConnector(
    private val sensorManager: ImuSensorManager,
    private val store: RegisteredImuStore,
    private val scope: CoroutineScope
) {
    public companion object {
        private const val TAG = "ImuAutoConnector"
        private const val SCAN_DURATION_MS = 5000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_BACKOFF_MS = 3000L
    }

    /** Surfaced so a screen can show a Snackbar while a mid-session reconnect is in flight. */
    public sealed interface ReconnectEvent {
        public data object Started : ReconnectEvent
        public data object Succeeded : ReconnectEvent
        public data object Exhausted : ReconnectEvent
    }

    private var autoConnectJob: Job? = null

    private val _reconnectEvents = MutableSharedFlow<ReconnectEvent>(extraBufferCapacity = 1)
    public val reconnectEvents: SharedFlow<ReconnectEvent> = _reconnectEvents.asSharedFlow()

    init {
        // shortcut: one collector for the process lifetime since WitnessApp creates this class
        // once — revisit only if ImuAutoConnector ever stops being a singleton.
        scope.launch {
            sensorManager.unexpectedDisconnects.collect { retryAfterUnexpectedDisconnect() }
        }
    }

    /**
     * Single scan-and-connect attempt against registered devices: the first one found, or with
     * [onlyIfSingle] the only one that answered during the whole scan (none when several did).
     * True once the link is up; false when nothing was found or the connect failed or took
     * more than [CONNECT_TIMEOUT_MS].
     */
    private suspend fun attemptConnect(onlyIfSingle: Boolean = false): Boolean {
        if (sensorManager.isConnected()) return true
        if (store.getAll().isEmpty()) return false

        var connecting = false
        val chosen = CompletableDeferred<Unit>()
        val found = linkedMapOf<String, ImuSensorDevice>()
        sensorManager.onDeviceDiscovered = { device ->
            if (!connecting && store.isRegistered(device.address)) {
                if (onlyIfSingle) {
                    found[device.address] = device
                } else {
                    connecting = true
                    Log.d(TAG, "Found registered device: ${device.name} (${device.address})")
                    sensorManager.connect(device)
                    chosen.complete(Unit)
                }
            }
        }
        try {
            sensorManager.startScan(store.getAll().map { it.address })
            withTimeoutOrNull(SCAN_DURATION_MS) { chosen.await() }
            sensorManager.onDeviceDiscovered = null
            if (onlyIfSingle) {
                found.values.singleOrNull()?.let { device ->
                    connecting = true
                    Log.d(TAG, "Only registered device found: ${device.name} (${device.address})")
                    sensorManager.stopScan()
                    sensorManager.connect(device)
                } ?: Log.d(TAG, "${found.size} registered devices found: not choosing")
            }
            if (!connecting) return false
            return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                sensorManager.connectionState.first { it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED }
            } == ConnectionState.CONNECTED
        } finally {
            sensorManager.onDeviceDiscovered = null
            sensorManager.stopScan()
        }
    }

    /**
     * One scan-and-connect attempt, unless connected or connecting; an attempt already in flight (a reconnect
     * a caller on a timer must not cut short) is returned instead of a new one. Null when there
     * is nothing to attempt.
     */
    public fun tryAutoConnect(onlyIfSingle: Boolean = false): Job? {
        if (sensorManager.isConnected() || sensorManager.isConnecting()) return null
        if (store.getAll().isEmpty()) return null
        autoConnectJob?.takeIf { it.isActive }?.let { return it }

        return scope.launch {
            Log.d(TAG, "Starting auto-connect scan...")
            if (!attemptConnect(onlyIfSingle)) {
                Log.d(TAG, "Auto-connect scan finished — no device connected")
            }
        }.also { autoConnectJob = it }
    }

    /**
     * Retries a bounded number of times (with backoff) after ImuSensorManager reports an
     * unprompted disconnect. Safe to call redundantly: idempotent-guarded by
     * [attemptConnect] and cancels any in-flight attempt of its own first.
     */
    public fun retryAfterUnexpectedDisconnect() {
        if (sensorManager.isConnected()) return
        if (store.getAll().isEmpty()) return

        val previous = autoConnectJob?.also { it.cancel() }
        autoConnectJob = scope.launch {
            previous?.join() // its cleanup must not stop this attempt's scan or clear its callback
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

    public fun cancel() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        sensorManager.onDeviceDiscovered = null
    }
}
