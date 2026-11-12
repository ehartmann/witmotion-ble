package fr.eande.witmotion

import android.annotation.SuppressLint
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Why a link that was up went down without a disconnect() call. */
public enum class DropCause {
    /** The Android stack reported the link down, or it did not answer the stream watchdog's probe. */
    LINK_LOST,
    /** No sample for STREAM_TIMEOUT_MS while the sensor still answered on the link. */
    STREAM_SILENT,
}

public data class LinkDrop(
    /** The sensor's address; null when no device was reached. */
    val address: String?,
    val cause: DropCause,
    /** [ImuSample.timestamp] of the last sample on this link; null when none came. */
    val lastSampleAt: Long?,
)

/**
 * Manages BLE connection to a WitMotion WT9011DCL IMU sensor.
 *
 * Responsibilities:
 * - Scan for WT9011DCL devices
 * - Connect/disconnect via GATT
 * - Subscribe to data notifications (characteristic FFE4)
 * - Send commands via characteristic FFE9 (output rate, calibration), one GATT op at a time
 * - Deliver parsed ImuSample to listeners
 */
@SuppressLint("MissingPermission") // Permissions checked by caller
public class ImuSensorManager(private val context: Context) {

    public companion object {
        /** One filter per valid address; the others are dropped. */
        public fun scanFilters(addresses: Collection<String>): List<ScanFilter> =
            addresses.filter(BluetoothAdapter::checkBluetoothAddress).map { ScanFilter.Builder().setDeviceAddress(it).build() }

        private const val TAG = "ImuSensorManager"
        private const val DEVICE_NAME_PREFIX = "WT"

        public val SERVICE_UUID: UUID = UUID.fromString("0000FFE5-0000-1000-8000-00805F9A34FB")
        public val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9A34FB")
        public val WRITE_CHAR_UUID: UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9A34FB")
        public val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val BATTERY_REFRESH_MS = 60_000L
        private const val DISCOVERY_RETRY_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val NO_STREAM_TIMEOUT_MS = 5_000L
        internal const val STREAM_TIMEOUT_MS = 1_500L
        internal const val STREAM_CHECK_MS = 250L
        internal const val PROBE_TIMEOUT_MS = 1_000L
        // WitMotion's SDK waits 100 ms after the unlock and after each config write
        private const val COMMAND_SPACING_MS = 100L

        // WitMotion device-config commands: [0xFF] [0xAA] [RegAddr] [DataL] [DataH].
        // 0x69/0xB588 unlocks config writes; the output rate commands are in OutputRate.
        public val UNLOCK: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x69, 0x88.toByte(), 0xB5.toByte())
        public val SAVE_CONFIG: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x00, 0x00, 0x00)
        // Register read (no unlock needed): the sensor answers with a 55 71 notification
        public val READ_BATTERY: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, ImuDataParser.REG_BATTERY_VOLTAGE.toByte(), 0x00)
        private const val REG_OUTPUT_RATE = 0x03
        public val READ_RATE: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, REG_OUTPUT_RATE.toByte(), 0x00)
        private const val REG_INSTALLATION = 0x23
        public val READ_INSTALLATION: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, REG_INSTALLATION.toByte(), 0x00)
        private const val REG_BANDWIDTH = 0x1F
        public val READ_BANDWIDTH: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, REG_BANDWIDTH.toByte(), 0x00)
        private const val REG_ALGORITHM = 0x24
        public val READ_ALGORITHM: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, REG_ALGORITHM.toByte(), 0x00)

        /** The commands sent after the unlock on every connect: the angles first, so a sensor left on timestamps stops at once. */
        internal fun settingsCommands(rate: OutputRate, installation: Installation, algorithm: Algorithm, bandwidth: Bandwidth) = listOf(
            OutputContent.ANGLES.command, rate.command, installation.command, algorithm.command, bandwidth.command,
            READ_RATE, READ_INSTALLATION, READ_ALGORITHM, READ_BANDWIDTH
        )
    }

    // State
    private val _discoveredDevices = MutableStateFlow<List<ImuSensorDevice>>(emptyList())
    public val discoveredDevices: StateFlow<List<ImuSensorDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    public val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<ImuSensorDevice?>(null)
    public val connectedDevice: StateFlow<ImuSensorDevice?> = _connectedDevice.asStateFlow()

    private val _batteryLevel = MutableStateFlow(-1) // -1 = unknown
    public val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    /**
     * Since the last connect: which frame format arrives, how much of the stream is not usable,
     * and the link and rate settings actually in effect (null until reported).
     */
    public data class LinkStats(
        val notifications: Long = 0,
        val combinedFrames: Long = 0,
        val legacyFrames: Long = 0,
        val skippedBytes: Long = 0,
        val connectionIntervalMs: Float? = null,
        val peripheralLatency: Int? = null,
        /** Register 0x03 read back after setting the rate; see [OutputRate.fromCode]. */
        val outputRateCode: Int? = null,
        /** Register 0x23 read back after setting the installation; see [Installation.fromCode]. */
        val installationCode: Int? = null,
        /** Register 0x24 read back after setting the algorithm; see [Algorithm.fromCode]. */
        val algorithmCode: Int? = null,
        /** Register 0x1F read back after setting the bandwidth; see [Bandwidth.fromCode]. The protocol does not document this read. */
        val bandwidthCode: Int? = null,
        /** Set on the first rate read-back that differs from [outputRate] (the timestamps are suspect), cleared when a later one matches. */
        val rateMismatch: Boolean = false
    )

    private class Readback(val name: String, val describe: (Int) -> Any?, val store: (Int) -> (LinkStats) -> LinkStats)

    private val registerReadbacks: Map<Int, Readback> = mapOf(
        REG_OUTPUT_RATE to Readback("Output rate", { c -> OutputRate.fromCode(c)?.let { "${it.hz} Hz" } }) { c -> { it.copy(outputRateCode = c) } },
        REG_INSTALLATION to Readback("Installation", Installation::fromCode) { c -> { it.copy(installationCode = c) } },
        REG_BANDWIDTH to Readback("Bandwidth", { c -> Bandwidth.fromCode(c)?.let { "${it.hz} Hz" } }) { c -> { it.copy(bandwidthCode = c) } },
        REG_ALGORITHM to Readback("Algorithm", Algorithm::fromCode) { c -> { it.copy(algorithmCode = c) } }
    )

    private val _linkStats = MutableStateFlow(LinkStats())
    public val linkStats: StateFlow<LinkStats> = _linkStats.asStateFlow()

    private val _lastSample = MutableStateFlow<ImuSample?>(null)
    /** Most recent parsed IMU sample. Observe for live display. */
    public val lastSample: StateFlow<ImuSample?> = _lastSample.asStateFlow()

    private val _unexpectedDisconnects = MutableSharedFlow<LinkDrop>(extraBufferCapacity = 1)
    /**
     * Emits when the sensor drops for any reason other than an explicit [disconnect] call —
     * out of range, low battery, sensor auto-sleep. A SharedFlow rather than a callback slot
     * so more than one collector (ImuAutoConnector today, potentially others later) can react
     * without overwriting each other, unlike a single nullable lambda. Each drop says its
     * cause, the sensor's address and the time of the link's last sample.
     */
    public val unexpectedDisconnects: SharedFlow<LinkDrop> = _unexpectedDisconnects.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var userInitiatedDisconnect = false
    @Volatile private var dropCause = DropCause.LINK_LOST
    @Volatile private var lastSampleAt: Long? = null
    private val gattOps = GattOpQueue { g -> dropLink(g) }

    /** Both swapped together, so a reset can never meet a delivery's half-updated pair. */
    private class RecordingClock(val start: Long, val lastSample: Long)
    private val recordingClock = AtomicReference(RecordingClock(0L, Long.MIN_VALUE))
    private val failedListeners: MutableSet<Any> = ConcurrentHashMap.newKeySet()
    @Volatile private var streamSeen = false
    @Volatile private var notifyCccd: BluetoothGattDescriptor? = null
    @Volatile private var lastPacketAt = 0L
    @Volatile private var streamWatched = false
    @Volatile private var probedGatt: BluetoothGatt? = null
    @Volatile private var discoveryRetried = false
    @Volatile private var rateRewritten = false
    @Volatile private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { stopScan() }
    private val connectWatchdog: Runnable = Runnable {
        if (_connectionState.value == ConnectionState.CONNECTING) {
            Log.e(TAG, "Connect did not complete in $CONNECT_TIMEOUT_MS ms")
            dropLink(gatt)
        }
    }
    private val noStreamWatchdog: Runnable = Runnable {
        if (!streamSeen) {
            Log.e(TAG, "No notification $NO_STREAM_TIMEOUT_MS ms after enabling them")
            dropLink(gatt)
        }
    }

    private val streamWatchdog = object : Runnable {
        override fun run() {
            val g = gatt
            val cccd = notifyCccd
            if (g == null || cccd == null || _connectionState.value != ConnectionState.CONNECTED) {
                streamWatched = false
                return
            }
            if (SystemClock.elapsedRealtime() - lastPacketAt < STREAM_TIMEOUT_MS) {
                mainHandler.postDelayed(this, STREAM_CHECK_MS)
                return
            }
            Log.w(TAG, "No sample for $STREAM_TIMEOUT_MS ms, probing the link")
            probedGatt = g
            mainHandler.postDelayed(probeTimeout, PROBE_TIMEOUT_MS)
            gattOps.enqueue(g) {
                val started = try { it.readDescriptor(cccd) } catch (e: Exception) { false }
                if (!started) dropLink(it)
                started
            }
        }
    }
    private val probeTimeout: Runnable = Runnable {
        val probed = probedGatt
        if (probed != null && gatt === probed) dropLink(probed)
    }

    private fun startStreamWatch() {
        if (streamWatched) return
        streamWatched = true
        mainHandler.postDelayed(streamWatchdog, STREAM_CHECK_MS)
    }

    private fun stopStreamWatch() {
        notifyCccd = null
        probedGatt = null
        streamWatched = false
        mainHandler.removeCallbacks(streamWatchdog)
        mainHandler.removeCallbacks(probeTimeout)
    }

    /**
     * Ends a link that is stuck as an unexpected drop. The stack may never report the end of a
     * link that was never up, so the disconnected callback is run by hand; [g] null (connectGatt
     * returned nothing) has no callback to run.
     */
    internal fun dropLink(g: BluetoothGatt?, cause: DropCause = DropCause.LINK_LOST) {
        if (g == null) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _unexpectedDisconnects.tryEmit(LinkDrop(null, DropCause.LINK_LOST, null))
            return
        }
        dropCause = cause
        try {
            g.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "GATT disconnect failed", e)
        }
        gattCallback.onConnectionStateChange(g, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
    }

    /**
     * The link is up once the notifications are enabled or the first one arrives, whichever
     * comes first: a sensor reporting a failed enable that streams anyway must not loop.
     * The settings are written now, so a setter called while connecting is never lost.
     */
    private fun markConnected(g: BluetoothGatt) {
        if (_connectionState.value == ConnectionState.CONNECTED) return
        mainHandler.removeCallbacks(connectWatchdog)
        _connectionState.value = ConnectionState.CONNECTED
        _connectedDevice.value = ImuSensorDevice(
            address = g.device.address,
            name = g.device.name ?: "WT9011DCL"
        )
        Log.d(TAG, "Connected to ${g.device.name}")
        applySettings()
        // Read now, then every minute: the level drains over a long session
        mainHandler.removeCallbacks(batteryRefresh)
        mainHandler.post(batteryRefresh)
    }
    private val batteryRefresh = object : Runnable {
        override fun run() {
            readBattery()
            mainHandler.postDelayed(this, BATTERY_REFRESH_MS)
        }
    }

    // Data callbacks. A list, not a single nullable slot: scoring and video recording each
    // register their own listener independently, so one starting/stopping never silently
    // starves the other of samples.
    private val sampleListeners = CopyOnWriteArrayList<(ImuSample) -> Unit>()

    /** Registers a listener for parsed samples. Call [removeSampleListener] with the same
     *  instance to stop receiving them. */
    public fun addSampleListener(listener: (ImuSample) -> Unit) {
        sampleListeners.add(listener)
    }

    public fun removeSampleListener(listener: (ImuSample) -> Unit) {
        sampleListeners.remove(listener)
        failedListeners.remove(listener)
    }

    /** Called when a device is discovered during scan. Used by ImuAutoConnector. */
    @Volatile internal var onDeviceDiscovered: ((ImuSensorDevice) -> Unit)? = null

    public enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, SCANNING
    }

    // --- Scanning ---

    /** True while a scan runs, whoever started it. */
    internal val scanning: Boolean get() = isScanning

    /** Incremented by each scan that starts: a scan is the caller's own only while this still holds its value. */
    @Volatile internal var scanGeneration = 0
        private set

    /** Scans for every WitMotion sensor, or only for [addresses]: Android pauses an unfiltered scan while the screen is off. */
    public fun startScan(addresses: Collection<String>? = null) {
        scanFor(addresses, SCAN_TIMEOUT_MS)
    }

    /** [startScan] that stops by itself after [timeoutMs]. */
    internal fun scanFor(addresses: Collection<String>?, timeoutMs: Long) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: run {
            Log.e(TAG, "Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth disabled")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE scanner not available")
            return
        }

        if (isScanning) return
        isScanning = true
        setScanState(ConnectionState.SCANNING)
        _discoveredDevices.value = emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(addresses?.let(::scanFilters)?.ifEmpty { null }, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan denied (missing runtime permission?)", e)
            isScanning = false
            setScanState(ConnectionState.DISCONNECTED)
            return
        }
        scanGeneration++
        Log.d(TAG, "BLE scan started")

        // Auto-stop after timeout (handler is reusable and cancelable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, timeoutMs)
    }

    public fun stopScan() {
        if (!isScanning) return
        isScanning = false

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE stopScan denied", e)
        }

        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        Log.d(TAG, "BLE scan stopped")
    }

    /** The scan shares the enum with the link: it may only move the state while no link is up or coming up. */
    private fun setScanState(state: ConnectionState) {
        _connectionState.update { if (it == ConnectionState.DISCONNECTED || it == ConnectionState.SCANNING) state else it }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            if (!name.startsWith(DEVICE_NAME_PREFIX)) return

            val existing = _discoveredDevices.value.toMutableList()
            if (existing.none { it.address == device.address }) {
                existing.add(ImuSensorDevice(
                    address = device.address,
                    name = name,
                    rssi = result.rssi
                ))
                _discoveredDevices.value = existing
                onDeviceDiscovered?.invoke(ImuSensorDevice(address = device.address, name = name, rssi = result.rssi))
                Log.d(TAG, "Discovered: $name (${device.address}, ${result.rssi} dBm)")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            isScanning = false
            setScanState(ConnectionState.DISCONNECTED)
        }
    }

    // --- Connection ---

    public fun connect(device: ImuSensorDevice): Unit = open(device, pending = false)

    private fun open(device: ImuSensorDevice, pending: Boolean) {
        stopScan()
        pendingConnect = pending
        userInitiatedDisconnect = false
        lastSampleAt = null
        _connectionState.value = ConnectionState.CONNECTING
        mainHandler.removeCallbacks(connectWatchdog)
        mainHandler.removeCallbacks(noStreamWatchdog)
        stopStreamWatch()
        if (!pending) mainHandler.postDelayed(connectWatchdog, CONNECT_TIMEOUT_MS)
        clockChecks.abort()

        gatt?.let { old ->
            try {
                old.disconnect()
                old.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing previous GATT", e)
            }
        }
        gatt = null
        writeCharacteristic = null
        gattOps.clear()

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: run {
            pendingConnect = false
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val bleDevice = try {
            if (pending && isRandomStatic(device.address) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                adapter.getRemoteLeDevice(device.address, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            } else {
                adapter.getRemoteDevice(device.address)
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid BLE address", e)
            pendingConnect = false
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        val runConnect = Runnable {
            try {
                gatt = openGatt(bleDevice, pending)
                if (gatt == null && pending) {
                    pendingConnect = false
                    dropLink(null)
                }
                Log.d(TAG, "Connecting to ${device.name}...")
            } catch (e: SecurityException) {
                Log.e(TAG, "connectGatt denied (missing runtime permission?)", e)
                pendingConnect = false
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runConnect.run()
        } else {
            mainHandler.post(runConnect)
        }
    }

    @Volatile private var pendingConnect = false

    internal var openGatt: (device: BluetoothDevice, autoConnect: Boolean) -> BluetoothGatt? = { device, autoConnect ->
        // The API 37 settings-based overload can replace this once minSdk reaches 37
        @Suppress("DEPRECATION")
        device.connectGatt(context.applicationContext, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Connect directly to a device by BLE address, without prior scan.
     * Used for auto-reconnecting to registered devices.
     *
     * With [pending], Android keeps the request open until the sensor is back in range: the state stays
     * CONNECTING with no give-up, and the 20 s connect watchdog starts only once the link is up.
     * Android can end a pending request without a callback (for example when Bluetooth is turned off and
     * on), so a caller should renew it periodically.
     */
    public fun connectByAddress(address: String, name: String = "WT9011DCL", pending: Boolean = false) {
        open(ImuSensorDevice(address = address, name = name), pending)
    }

    /**
     * True while a [connectByAddress] request with `pending = true` waits for the sensor. Set by that call;
     * cleared when the link comes up (STATE_CONNECTED), by [disconnect], by any other connect, when the
     * request cannot be opened (no adapter, invalid address, no GATT, SecurityException) and when the
     * stack ends it with a callback. Android can also end a request silently: the flag then stays true
     * with state CONNECTING, so a caller renews it periodically, as [ImuAutoConnector] does.
     *
     * The state is CONNECTING both while the request waits and while the link, once up, is being set up
     * (service discovery, notification enable): this tells them apart, so a caller does not start a direct
     * connect that would close the GATT being set up.
     */
    public val pendingConnectWaiting: Boolean get() = pendingConnect

    /** Ends the last connect if it is a pending one; true when it did. */
    internal fun cancelPendingConnect(): Boolean {
        if (!pendingConnect) return false
        disconnect()
        return true
    }

    public fun disconnect() {
        pendingConnect = false
        userInitiatedDisconnect = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        gattOps.clear()
        mainHandler.removeCallbacks(batteryRefresh)
        mainHandler.removeCallbacks(connectWatchdog)
        mainHandler.removeCallbacks(noStreamWatchdog)
        stopStreamWatch()
        _connectionState.value = ConnectionState.DISCONNECTED
        clockChecks.abort()
        _connectedDevice.value = null
        Log.d(TAG, "Disconnected")
    }

    internal val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected, discovering services...")
                    _linkStats.value = LinkStats()
                    discoveryRetried = false
                    rateRewritten = false
                    streamSeen = false
                    lastSampleAt = null
                    stopStreamWatch()
                    if (pendingConnect) {
                        pendingConnect = false
                        mainHandler.removeCallbacks(connectWatchdog)
                        mainHandler.postDelayed(connectWatchdog, CONNECT_TIMEOUT_MS)
                    }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    val address = gatt.device?.address
                    val cause = dropCause
                    dropCause = DropCause.LINK_LOST
                    _connectionState.value = ConnectionState.DISCONNECTED
                    // not a late callback from a GATT that connect() already replaced
                    if (this@ImuSensorManager.gatt.let { it == null || it == gatt }) clockChecks.abort()
                    _connectedDevice.value = null
                    writeCharacteristic = null
                    gattOps.clear()
                    mainHandler.removeCallbacks(batteryRefresh)
                    mainHandler.removeCallbacks(connectWatchdog)
                    mainHandler.removeCallbacks(noStreamWatchdog)
                    stopStreamWatch()
                    if (this@ImuSensorManager.gatt == gatt) {
                        pendingConnect = false
                        try {
                            gatt.close()
                        } catch (e: Exception) {
                            Log.w(TAG, "GATT close failed", e)
                        }
                        this@ImuSensorManager.gatt = null
                    }
                    // A user tapping "Disconnect" already tore the connection down itself
                    // (see disconnect()) — only an unprompted drop should trigger a reconnect.
                    if (userInitiatedDisconnect) {
                        userInitiatedDisconnect = false
                    } else {
                        _unexpectedDisconnects.tryEmit(LinkDrop(address, cause, lastSampleAt))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                gatt.disconnect() // reported as an unexpected drop, so ImuAutoConnector retries
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                // A first discovery right after connecting can come back empty (seen on a Galaxy
                // S22): retry once, then drop the link as unexpected so ImuAutoConnector reconnects
                if (!discoveryRetried) {
                    discoveryRetried = true
                    Log.w(TAG, "IMU service not found, retrying discovery")
                    mainHandler.postDelayed({ gatt.discoverServices() }, DISCOVERY_RETRY_MS)
                } else {
                    Log.e(TAG, "IMU service not found")
                    gatt.disconnect()
                }
                return
            }

            val notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID)
            val cccd = notifyChar?.getDescriptor(CCCD_UUID)
            if (notifyChar == null || cccd == null) {
                Log.e(TAG, "IMU notify characteristic not found")
                gatt.disconnect()
                return
            }

            // The default connection interval (about 30 ms) caps the link below the sensor's 50 Hz;
            // asked once discovery is done, as a request racing the discovery is not always honored
            val fast = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            Log.d(TAG, "High connection priority requested: $fast")

            writeCharacteristic = service.getCharacteristic(WRITE_CHAR_UUID)

            gattOps.clear()
            notifyCccd = cccd
            gatt.setCharacteristicNotification(notifyChar, true)
            gattOps.enqueue(gatt) { g ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Notifications enabled (status=$status)")
            // a late callback from a GATT that connect() or a drop already replaced
            if (this@ImuSensorManager.gatt != gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) markConnected(gatt)
            if (!streamSeen) {
                mainHandler.removeCallbacks(noStreamWatchdog)
                mainHandler.postDelayed(noStreamWatchdog, NO_STREAM_TIMEOUT_MS)
            }
            gattOps.next(gatt)
        }

        @Deprecated("Deprecated in API 33+, kept for backward compatibility with minSdk 26")
        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = probeAnswered(gatt, status)

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) =
            probeAnswered(gatt, status)

        /** The link answers while no sample comes: the sensor's stream is silent, not the link lost. */
        private fun probeAnswered(gatt: BluetoothGatt, status: Int) {
            if (this@ImuSensorManager.gatt != gatt || probedGatt !== gatt) return
            probedGatt = null
            mainHandler.removeCallbacks(probeTimeout)
            gattOps.next(gatt)
            dropLink(gatt, if (status == BluetoothGatt.GATT_SUCCESS) DropCause.STREAM_SILENT else DropCause.LINK_LOST)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            gattOps.next(gatt)
        }

        /**
         * Hidden in the SDK but called by the framework since API 26 (Nordic's Android-BLE-Library
         * relies on it too): the only report of the parameters actually negotiated, which can differ
         * from what requestConnectionPriority asked for. [interval] is in 1.25 ms units.
         */
        @Keep
        @Suppress("unused")
        fun onConnectionUpdated(gatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            Log.d(TAG, "Connection updated: interval ${interval * 1.25f} ms, latency $latency, timeout ${timeout * 10} ms, status $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _linkStats.update { it.copy(connectionIntervalMs = interval * 1.25f, peripheralLatency = latency) }
            }
        }

        @Deprecated("Deprecated in API 33+, kept for backward compatibility with minSdk 26")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                deliverNotify(characteristic.value ?: return)
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            deliverNotify(value)
        }

        private fun deliverNotify(data: ByteArray) {
            if (!streamSeen) {
                streamSeen = true
                mainHandler.removeCallbacks(noStreamWatchdog)
                gatt?.let { if (_connectionState.value == ConnectionState.CONNECTING) markConnected(it) }
            }
            val check = clockChecks.collector
            val (packets, counts, registers) = ImuDataParser.parseCounting(data, timestamps = check != null)
            if (check != null) {
                check.add(packets.mapNotNull { it.sensorMs }, SystemClock.elapsedRealtime())
                if (counts.timestampFrameSize != 0 && !timestampSizeLogged) {
                    timestampSizeLogged = true
                    Log.i(TAG, "Timestamp frames are ${counts.timestampFrameSize} bytes, ${counts.combined} in a ${data.size}-byte notification")
                }
            }
            registers.firstOrNull { it.startRegister == ImuDataParser.REG_BATTERY_VOLTAGE }?.let {
                _batteryLevel.value = ImuDataParser.batteryPercent(it.values[0])
                Log.d(TAG, "Battery: ${it.values[0] / 100f} V, ${_batteryLevel.value}%")
            }
            for ((register, readback) in registerReadbacks) {
                val code = registers.firstOrNull { it.startRegister == register }?.values?.get(0) ?: continue
                Log.d(TAG, "${readback.name} register: 0x%02X (%s)".format(code, readback.describe(code) ?: "?"))
                val mismatch = register == REG_OUTPUT_RATE && OutputRate.fromCode(code) != outputRate
                _linkStats.update { readback.store(code)(it).let { s -> if (register == REG_OUTPUT_RATE) s.copy(rateMismatch = mismatch) else s } }
                if (mismatch && !rateRewritten) {
                    rateRewritten = true
                    Log.w(TAG, "Output rate is 0x%02X, not $outputRate: writing it again".format(code))
                    configureRate()
                }
            }
            _linkStats.update {
                it.copy(
                    notifications = it.notifications + 1,
                    combinedFrames = it.combinedFrames + counts.combined,
                    legacyFrames = it.legacyFrames + counts.legacy,
                    skippedBytes = it.skippedBytes + counts.skippedBytes
                )
            }
            if (packets.isNotEmpty()) {
                val clock = recordingClock.get()
                val samples = ImuDataParser.packetsToSamples(
                    packets,
                    SystemClock.elapsedRealtime() - clock.start,
                    1000L / outputRate.hz,
                    clock.lastSample
                )
                // a reset since the read wins: its clock is not to be overwritten
                recordingClock.compareAndSet(clock, RecordingClock(clock.start, samples.last().timestamp))
                samples.forEach { sample ->
                    sampleListeners.forEach { listener ->
                        runCatching { listener(sample) }.onFailure {
                            if (failedListeners.add(listener)) Log.e(TAG, "sample listener failed (further failures not logged)", it)
                        }
                    }
                }
                lastSampleAt = samples.last().timestamp
                lastPacketAt = SystemClock.elapsedRealtime()
                startStreamWatch()
                _lastSample.value = samples.last()
            }
        }
    }

    // --- Commands ---

    /**
     * Queues WitMotion `FF AA reg L H` commands, preceded by the config unlock. Writes run one
     * at a time; a command sent while disconnected is dropped.
     */
    public fun sendCommands(vararg commands: ByteArray): Unit = enqueuePaced(listOf(UNLOCK) + commands)

    /** Writes [commands] one at a time, [COMMAND_SPACING_MS] apart. */
    private fun enqueuePaced(commands: List<ByteArray>) {
        val g = gatt ?: return
        commands.forEachIndexed { i, command ->
            if (i > 0) {
                gattOps.enqueue(g) {
                    mainHandler.postDelayed({ if (gatt === g) gattOps.next(g) }, COMMAND_SPACING_MS)
                    true
                }
            }
            gattOps.enqueue(g) { sendCommand(command) }
        }
    }

    /** The WT9011DCL has no GATT Battery Service: the level comes from register 0x64 (see deliverNotify). */
    private fun readBattery() = enqueuePaced(listOf(READ_BATTERY))

    /** Output rate set on every connect, and at once when changed while connected. Not saved to the sensor's flash. */
    @Volatile
    public var outputRate: OutputRate = OutputRate.HZ_50
        set(value) {
            field = value
            rateRewritten = false
            if (isConnected()) configureRate()
        }

    /** Sets [outputRate], then reads register 0x03 back into [linkStats]. */
    public fun configureRate(): Unit = sendCommands(outputRate.command, READ_RATE)

    /**
     * How the sensor is mounted, written on every connect and at once when changed while
     * connected: the sensor keeps the last value written, even by another app, so leaving it
     * unwritten would make the angles depend on that. Not saved to the sensor's flash.
     */
    @Volatile
    public var installation: Installation = Installation.HORIZONTAL
        set(value) {
            field = value
            if (isConnected()) configureInstallation()
        }

    /** Sets [installation], then reads register 0x23 back into [linkStats]. */
    public fun configureInstallation(): Unit = sendCommands(installation.command, READ_INSTALLATION)

    /**
     * 6 or 9-axis heading, written on every connect and at once when changed while connected,
     * like [installation]. Not saved to the sensor's flash.
     */
    @Volatile
    public var algorithm: Algorithm = Algorithm.AXIS_9
        set(value) {
            field = value
            if (isConnected()) configureAlgorithm()
        }

    /** Sets [algorithm], then reads register 0x24 back into [linkStats]. */
    public fun configureAlgorithm(): Unit = sendCommands(algorithm.command, READ_ALGORITHM)

    /**
     * Cut-off of the sensor's accelerometer and gyroscope filter, written on every connect and
     * at once when changed while connected. Not saved to the sensor's flash. The sensor's own
     * default is 20 Hz, which delays and smooths a 20 ms event: see [Bandwidth.suggestedFor].
     */
    @Volatile
    public var bandwidth: Bandwidth = Bandwidth.HZ_20
        set(value) {
            field = value
            if (isConnected()) configureBandwidth()
        }

    /** Sets [bandwidth], then reads register 0x1F back into [linkStats]. */
    public fun configureBandwidth(): Unit = sendCommands(bandwidth.command, READ_BANDWIDTH)

    /**
     * On connect: [outputRate], [installation], [algorithm] and [bandwidth] under one unlock, all
     * read back, the output content first: a clock check cut short (app killed, link lost) leaves
     * the sensor on timestamps, which would otherwise outlive the app.
     */
    private fun applySettings() =
        sendCommands(*settingsCommands(outputRate, installation, algorithm, bandwidth).toTypedArray())

    internal val clockChecks = ClockCheckSlot()
    @Volatile private var timestampSizeLogged = false

    /**
     * Measures the sensor's clock for [durationMs]: the 0x61 frames carry the sensor's
     * millisecond counter instead of the angles (register 0x96 = 2), then the angles are
     * restored, also on failure or cancellation, and on every connect.
     *
     * While it runs, samples carry [ImuSample.sensorTimeMs] and no Euler angles, and for about
     * half a second after the end the angles are still absent. The manager cannot tell which
     * sample listener records, so the caller must not record during a check. Needs a connected
     * sensor, and one check at a time. A disconnect or a new connect ends the check at once with
     * [ClockCheckResult.aborted] set; the sensor gets the angles back on the next connect.
     */
    public suspend fun runClockCheck(durationMs: Long = 30_000): ClockCheckResult {
        check(isConnected()) { "Not connected" }
        val collector = clockChecks.begin()
        timestampSizeLogged = false
        try {
            return ClockCheck.run(durationMs, collector, { sendCommands(it.command) })
        } finally {
            clockChecks.end(collector)
        }
    }

    /**
     * Makes [ImuSample.timestamp] relative to now; until called, timestamps are
     * [SystemClock.elapsedRealtime] values (ms since boot).
     */
    public fun resetRecordingTimestamp() {
        recordingClock.set(RecordingClock(SystemClock.elapsedRealtime(), Long.MIN_VALUE))
    }

    /** Returns whether the write was started; its completion arrives in onCharacteristicWrite. */
    private fun sendCommand(command: ByteArray): Boolean {
        val char = writeCharacteristic ?: run {
            Log.w(TAG, "Write characteristic not available")
            return false
        }
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = command
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }

    public fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    public fun isConnecting(): Boolean = _connectionState.value == ConnectionState.CONNECTING
}

internal fun isRandomStatic(address: String): Boolean =
    address.take(2).toIntOrNull(16)?.let { it and 0xC0 == 0xC0 } ?: false
