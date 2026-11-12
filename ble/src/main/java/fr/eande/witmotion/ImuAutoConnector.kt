package fr.eande.witmotion

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
 * While [keep] is active, that scan-based retry is replaced by unlimited pending connects to the kept sensor,
 * helped by a scan filtered on that sensor's address.
 */
public class ImuAutoConnector internal constructor(
    private val sensorManager: ImuSensorManager,
    private val store: RegisteredImuStore,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long
) {
    public constructor(
        sensorManager: ImuSensorManager,
        store: RegisteredImuStore,
        scope: CoroutineScope
    ) : this(sensorManager, store, scope, { System.nanoTime() / 1_000_000 })

    public companion object {
        private const val TAG = "ImuAutoConnector"
        private const val SCAN_DURATION_MS = 5000L
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_BACKOFF_MS = 3000L
        internal const val KEEP_RETRY_MS = 2_000L
        internal const val KEEP_RENEW_MS = 30_000L
        internal const val KEEP_SCAN_FULL_MS = 120_000L
        internal const val KEEP_SCAN_WINDOW_MS = 10_000L
    }

    /** Surfaced so a screen can show a Snackbar while a mid-session reconnect is in flight. */
    public sealed interface ReconnectEvent {
        public data object Started : ReconnectEvent
        public data object Succeeded : ReconnectEvent
        public data object Exhausted : ReconnectEvent
    }

    private var autoConnectJob: Job? = null
    private var retryJob: Job? = null
    private var keepJob: Job? = null

    private class Kept(val address: String, val name: String)

    @Volatile private var kept: Kept? = null

    /** True while the scan running is one the keeper started: it never stops another one. */
    @Volatile private var keeperScan = false

    private enum class Wake { CONNECTED, DISCONNECTED, SEEN }

    /** Set when a retry gave up: the drop of its own last connect must not start another one. */
    @Volatile private var retryExhausted = false

    private val _reconnectEvents = MutableSharedFlow<ReconnectEvent>(extraBufferCapacity = 1)
    /**
     * Mid-session reconnect progress. While keeping ([keep]): [ReconnectEvent.Started] on each drop,
     * [ReconnectEvent.Succeeded] when the link is back, never [ReconnectEvent.Exhausted]; the keeper's scan (see [keep])
     * runs behind these events and adds none.
     */
    public val reconnectEvents: SharedFlow<ReconnectEvent> = _reconnectEvents.asSharedFlow()

    init {
        // shortcut: one collector for the process lifetime since WitnessApp creates this class
        // once — revisit only if ImuAutoConnector ever stops being a singleton.
        scope.launch {
            // the manager's one-slot buffer is shared by all collectors: a second drop of a burst can be lost to a
            // slow one, which is harmless here because the keeper reacts the same way to any drop
            sensorManager.unexpectedDisconnects.collect {
                if (kept != null) startKeepLoop(announce = true) else retryAfterUnexpectedDisconnect()
            }
        }
        scope.launch {
            sensorManager.connectedDevice.collect { device ->
                if (device != null && kept != null) kept = Kept(device.address, device.name)
            }
        }
        scope.launch {
            sensorManager.connectionState.collect { if (it == ConnectionState.CONNECTED || it == ConnectionState.CONNECTING) retryExhausted = false }
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

        retryExhausted = false
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
        if (kept != null) return
        if (sensorManager.isConnected()) return
        if (store.getAll().isEmpty()) return
        // a drop during a retry is handled by the attempt running: restarting would reset the budget
        if (retryJob?.isActive == true || retryExhausted) return

        val previous = autoConnectJob?.also { it.cancel() }
        val job = scope.launch {
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
            retryExhausted = true
            _reconnectEvents.emit(ReconnectEvent.Exhausted)
        }
        autoConnectJob = job
        retryJob = job
    }

    /**
     * Keeps the link to [address] up until [stopKeeping]: connects at once when no link is up or coming up, and
     * after every unexpected drop issues a pending connect to the kept address, never to another registered sensor.
     * A failed pending connect is issued again [KEEP_RETRY_MS] later, with no limit; a pending connect still waiting is
     * renewed every [KEEP_RENEW_MS]. While keeping, the scan-based retry does not run. A sensor connected by other means
     * while keeping becomes the kept one.
     *
     * Android's own wait for a pending connect can take 10 to 17 s to notice the sensor. So while a pending connect waits,
     * the keeper also scans for the kept address, and connects directly (no pending request, bounded by the manager's own
     * connect watchdog) as soon as the scan sees it. The scan runs continuously for the first [KEEP_SCAN_FULL_MS] of a
     * loop, restarted at each pending (re)issue (one scan start each), then only [KEEP_SCAN_WINDOW_MS] after each one.
     * It stops when the link is up, at [stopKeeping] and before the direct connect. The keeper starts no scan while another
     * is running and stops only its own. A failed direct connect goes back to the pending connect after [KEEP_RETRY_MS].
     */
    public fun keep(address: String, name: String) {
        keepJob?.cancel()
        stopKeeperScan()
        autoConnectJob?.cancel()
        kept = Kept(address, name)
        if (!sensorManager.isConnected() && !sensorManager.isConnecting()) startKeepLoop(announce = false)
    }

    /** Stops keeping and cancels the keeper's own pending connect; a link that is up stays up. */
    public fun stopKeeping() {
        val wasKeeping = kept != null
        kept = null
        keepJob?.cancel()
        keepJob = null
        stopKeeperScan()
        if (wasKeeping) sensorManager.cancelPendingConnect()
    }

    private fun stopKeeperScan() {
        if (!keeperScan) return
        keeperScan = false
        if (sensorManager.scanning) sensorManager.stopScan()
    }

    private fun startKeepLoop(announce: Boolean) {
        if (keepJob?.isActive == true) return
        keepJob = scope.launch {
            val loopStart = clockMs()
            if (announce) _reconnectEvents.emit(ReconnectEvent.Started)
            while (true) {
                if (sensorManager.isConnected()) {
                    stopKeeperScan()
                    if (announce) _reconnectEvents.emit(ReconnectEvent.Succeeded)
                    return@launch
                }
                val target = kept ?: return@launch
                sensorManager.connectByAddress(target.address, target.name, pending = true)
                // connecting stopped any scan running: a scan found now is not the keeper's, so it is left alone
                keeperScan = false
                if (!sensorManager.scanning) {
                    val full = clockMs() - loopStart < KEEP_SCAN_FULL_MS
                    sensorManager.startScan(listOf(target.address), if (full) KEEP_RENEW_MS else KEEP_SCAN_WINDOW_MS)
                    keeperScan = sensorManager.scanning
                }
                var seenIgnored = false
                var renew = false
                while (!renew) {
                    val states = sensorManager.connectionState
                        .filter { it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED }
                        .map { if (it == ConnectionState.CONNECTED) Wake.CONNECTED else Wake.DISCONNECTED }
                    val wake = withTimeoutOrNull(KEEP_RENEW_MS) {
                        if (keeperScan && !seenIgnored) {
                            val seen = sensorManager.discoveredDevices
                                .filter { devices -> devices.any { it.address.equals(target.address, ignoreCase = true) } }
                                .map { Wake.SEEN }
                            merge(states, seen).first()
                        } else {
                            states.first()
                        }
                    }
                    when (wake) {
                        Wake.CONNECTED -> {
                            stopKeeperScan()
                            if (announce) _reconnectEvents.emit(ReconnectEvent.Succeeded)
                            return@launch
                        }
                        Wake.DISCONNECTED -> { stopKeeperScan(); delay(KEEP_RETRY_MS); renew = true }
                        Wake.SEEN -> {
                            if (!sensorManager.pendingConnectWaiting) {
                                seenIgnored = true
                                continue
                            }
                            stopKeeperScan()
                            sensorManager.connectByAddress(target.address, target.name, pending = false)
                            // the manager's 20 s watchdog bounds this wait
                            val result = sensorManager.connectionState
                                .first { it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED }
                            if (result == ConnectionState.CONNECTED) {
                                if (announce) _reconnectEvents.emit(ReconnectEvent.Succeeded)
                                return@launch
                            }
                            delay(KEEP_RETRY_MS)
                            renew = true
                        }
                        // past the pending request, the manager's own 20 s watchdog bounds the wait
                        null -> renew = sensorManager.pendingConnectWaiting
                    }
                }
            }
        }
    }

    public fun cancel() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        sensorManager.onDeviceDiscovered = null
    }
}
