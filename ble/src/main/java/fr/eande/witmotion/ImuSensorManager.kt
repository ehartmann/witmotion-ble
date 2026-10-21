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
import android.app.PendingIntent
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
import java.util.concurrent.CopyOnWriteArrayList

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
class ImuSensorManager(private val context: Context) {

    companion object {
        /** One filter per valid address; the others are dropped. */
        fun scanFilters(addresses: Collection<String>): List<ScanFilter> =
            addresses.filter(BluetoothAdapter::checkBluetoothAddress).map { ScanFilter.Builder().setDeviceAddress(it).build() }

        private const val TAG = "ImuSensorManager"
        private const val DEVICE_NAME_PREFIX = "WT"

        val SERVICE_UUID: UUID = UUID.fromString("0000FFE5-0000-1000-8000-00805F9A34FB")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9A34FB")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9A34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val BATTERY_REFRESH_MS = 60_000L
        private const val DISCOVERY_RETRY_MS = 500L
        // WitMotion's SDK waits 100 ms after the unlock and after each config write
        private const val COMMAND_SPACING_MS = 100L

        // WitMotion device-config commands: [0xFF] [0xAA] [RegAddr] [DataL] [DataH].
        // 0x69/0xB588 unlocks config writes; the output rate commands are in OutputRate.
        val UNLOCK = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x69, 0x88.toByte(), 0xB5.toByte())
        val SAVE_CONFIG = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x00, 0x00, 0x00)
        // Register read (no unlock needed): the sensor answers with a 55 71 notification
        val READ_BATTERY = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, ImuDataParser.REG_BATTERY_VOLTAGE.toByte(), 0x00)
        private const val REG_OUTPUT_RATE = 0x03
        val READ_RATE = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, REG_OUTPUT_RATE.toByte(), 0x00)
        private const val REG_INSTALLATION = 0x23
        val READ_INSTALLATION = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, REG_INSTALLATION.toByte(), 0x00)
        private const val REG_ALGORITHM = 0x24
        val READ_ALGORITHM = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, REG_ALGORITHM.toByte(), 0x00)
    }

    // State
    private val _discoveredDevices = MutableStateFlow<List<ImuSensorDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ImuSensorDevice>> = _discoveredDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<ImuSensorDevice?>(null)
    val connectedDevice: StateFlow<ImuSensorDevice?> = _connectedDevice.asStateFlow()

    private val _batteryLevel = MutableStateFlow(-1) // -1 = unknown
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    /**
     * Since the last connect: which frame format arrives, how much of the stream is not usable,
     * and the link and rate settings actually in effect (null until reported).
     */
    data class LinkStats(
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
        val algorithmCode: Int? = null
    )

    private val _linkStats = MutableStateFlow(LinkStats())
    val linkStats: StateFlow<LinkStats> = _linkStats.asStateFlow()

    private val _lastSample = MutableStateFlow<ImuSample?>(null)
    /** Most recent parsed IMU sample. Observe for live display. */
    val lastSample: StateFlow<ImuSample?> = _lastSample.asStateFlow()

    private val _unexpectedDisconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * Emits when the sensor drops for any reason other than an explicit [disconnect] call —
     * out of range, low battery, sensor auto-sleep. A SharedFlow rather than a callback slot
     * so more than one collector (ImuAutoConnector today, potentially others later) can react
     * without overwriting each other, unlike a single nullable lambda.
     */
    val unexpectedDisconnects: SharedFlow<Unit> = _unexpectedDisconnects.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var userInitiatedDisconnect = false
    private val gattOps = GattOpQueue()
    @Volatile private var recordingStartTime = 0L
    private var lastSampleTimestamp = Long.MIN_VALUE
    @Volatile private var discoveryRetried = false
    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { stopScan() }
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
    fun addSampleListener(listener: (ImuSample) -> Unit) {
        sampleListeners.add(listener)
    }

    fun removeSampleListener(listener: (ImuSample) -> Unit) {
        sampleListeners.remove(listener)
    }

    /** Called when a device is discovered during scan. Used by ImuAutoConnector. */
    var onDeviceDiscovered: ((ImuSensorDevice) -> Unit)? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, SCANNING
    }

    // --- Scanning ---

    /** Scans for every WitMotion sensor, or only for [addresses]: Android pauses an unfiltered scan while the screen is off. */
    fun startScan(addresses: Collection<String>? = null) {
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
        _connectionState.value = ConnectionState.SCANNING
        _discoveredDevices.value = emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(addresses?.let(::scanFilters)?.ifEmpty { null }, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan denied (missing runtime permission?)", e)
            isScanning = false
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        Log.d(TAG, "BLE scan started")

        // Auto-stop after timeout (handler is reusable and cancelable)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    /**
     * A scan the system keeps for [addresses], reporting to [callback] (see BluetoothLeScanner's
     * EXTRA_LIST_SCAN_RESULT) when one of them appears, with the screen off or the app in the
     * background or not running. False without Bluetooth, permission or a valid address.
     */
    fun startBackgroundScan(addresses: Collection<String>, callback: PendingIntent): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.takeIf { it.isEnabled } ?: return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        val filters = scanFilters(addresses).ifEmpty { return false }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            // Without offloaded filtering FIRST_MATCH fails; ALL_MATCHES then reports every few seconds
            .setCallbackType(if (adapter.isOffloadedFilteringSupported) ScanSettings.CALLBACK_TYPE_FIRST_MATCH else ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        return try {
            // A scan left by an earlier process holds the same PendingIntent: replace it rather than stack a second one
            scanner.stopScan(callback)
            (scanner.startScan(filters, settings, callback) == 0).also { Log.d(TAG, "Background scan for ${filters.size} sensor(s): $it") }
        } catch (e: SecurityException) {
            Log.e(TAG, "Background scan denied", e)
            false
        }
    }

    fun stopBackgroundScan(callback: PendingIntent) {
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(callback)
            Log.d(TAG, "Background scan stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "Background stopScan denied", e)
        }
    }

    fun stopScan() {
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
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // --- Connection ---

    fun connect(device: ImuSensorDevice) {
        stopScan()
        userInitiatedDisconnect = false
        _connectionState.value = ConnectionState.CONNECTING

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
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val bleDevice = try {
            adapter.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid BLE address", e)
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        val appContext = context.applicationContext
        val callback = gattCallback
        val runConnect = Runnable {
            try {
                // The API 37 settings-based overload can replace this once minSdk reaches 37
                @Suppress("DEPRECATION")
                gatt = bleDevice.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                Log.d(TAG, "Connecting to ${device.name}...")
            } catch (e: SecurityException) {
                Log.e(TAG, "connectGatt denied (missing runtime permission?)", e)
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runConnect.run()
        } else {
            mainHandler.post(runConnect)
        }
    }

    /**
     * Connect directly to a device by BLE address, without prior scan.
     * Used for auto-reconnecting to registered devices.
     */
    fun connectByAddress(address: String, name: String = "WT9011DCL") {
        connect(ImuSensorDevice(address = address, name = name))
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        gattOps.clear()
        mainHandler.removeCallbacks(batteryRefresh)
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevice.value = null
        Log.d(TAG, "Disconnected")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected, discovering services...")
                    _linkStats.value = LinkStats()
                    discoveryRetried = false
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _connectedDevice.value = null
                    writeCharacteristic = null
                    gattOps.clear()
                    mainHandler.removeCallbacks(batteryRefresh)
                    if (this@ImuSensorManager.gatt == gatt) {
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
                        _unexpectedDisconnects.tryEmit(Unit)
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

            // The default connection interval (about 30 ms) caps the link below the sensor's 50 Hz;
            // asked once discovery is done, as a request racing the discovery is not always honored
            val fast = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            Log.d(TAG, "High connection priority requested: $fast")

            writeCharacteristic = service.getCharacteristic(WRITE_CHAR_UUID)

            gattOps.clear()
            service.getCharacteristic(NOTIFY_CHAR_UUID)?.let { notifyChar ->
                gatt.setCharacteristicNotification(notifyChar, true)
                notifyChar.getDescriptor(CCCD_UUID)?.let { descriptor ->
                    gattOps.enqueue(gatt) { g ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(descriptor)
                        }
                    }
                }
            }
            applySettings()
            // Read now, then every minute: the level drains over a long session
            mainHandler.removeCallbacks(batteryRefresh)
            mainHandler.post(batteryRefresh)

            _connectionState.value = ConnectionState.CONNECTED
            _connectedDevice.value = ImuSensorDevice(
                address = gatt.device.address,
                name = gatt.device.name ?: "WT9011DCL"
            )
            Log.d(TAG, "Connected to ${gatt.device.name}")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Notifications enabled (status=$status)")
            gattOps.next(gatt)
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
            val (packets, counts, registers) = ImuDataParser.parseCounting(data)
            registers.firstOrNull { it.startRegister == ImuDataParser.REG_BATTERY_VOLTAGE }?.let {
                _batteryLevel.value = ImuDataParser.batteryPercent(it.values[0])
                Log.d(TAG, "Battery: ${it.values[0] / 100f} V, ${_batteryLevel.value}%")
            }
            registers.firstOrNull { it.startRegister == REG_OUTPUT_RATE }?.let { reply ->
                val code = reply.values[0]
                Log.d(TAG, "Output rate register: 0x%02X (%s Hz)".format(code, OutputRate.fromCode(code)?.hz ?: "?"))
                _linkStats.update { it.copy(outputRateCode = code) }
            }
            registers.firstOrNull { it.startRegister == REG_INSTALLATION }?.let { reply ->
                val code = reply.values[0]
                Log.d(TAG, "Installation register: 0x%02X (%s)".format(code, Installation.fromCode(code) ?: "?"))
                _linkStats.update { it.copy(installationCode = code) }
            }
            registers.firstOrNull { it.startRegister == REG_ALGORITHM }?.let { reply ->
                val code = reply.values[0]
                Log.d(TAG, "Algorithm register: 0x%02X (%s)".format(code, Algorithm.fromCode(code) ?: "?"))
                _linkStats.update { it.copy(algorithmCode = code) }
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
                val samples = ImuDataParser.packetsToSamples(
                    packets,
                    SystemClock.elapsedRealtime() - recordingStartTime,
                    1000L / outputRate.hz,
                    lastSampleTimestamp
                )
                lastSampleTimestamp = samples.last().timestamp
                samples.forEach { sample -> sampleListeners.forEach { it(sample) } }
                _lastSample.value = samples.last()
            }
        }
    }

    // --- Commands ---

    /**
     * Queues WitMotion `FF AA reg L H` commands, preceded by the config unlock. Writes run one
     * at a time; a command sent while disconnected is dropped.
     */
    fun sendCommands(vararg commands: ByteArray) = enqueuePaced(listOf(UNLOCK) + commands)

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
    var outputRate: OutputRate = OutputRate.HZ_50
        set(value) {
            field = value
            if (isConnected()) configureRate()
        }

    /** Sets [outputRate], then reads register 0x03 back into [linkStats]. */
    fun configureRate() = sendCommands(outputRate.command, READ_RATE)

    /**
     * How the sensor is mounted, written on every connect and at once when changed while
     * connected: the sensor keeps the last value written, even by another app, so leaving it
     * unwritten would make the angles depend on that. Not saved to the sensor's flash.
     */
    @Volatile
    var installation: Installation = Installation.HORIZONTAL
        set(value) {
            field = value
            if (isConnected()) configureInstallation()
        }

    /** Sets [installation], then reads register 0x23 back into [linkStats]. */
    fun configureInstallation() = sendCommands(installation.command, READ_INSTALLATION)

    /**
     * 6 or 9-axis heading, written on every connect and at once when changed while connected,
     * like [installation]. Not saved to the sensor's flash.
     */
    @Volatile
    var algorithm: Algorithm = Algorithm.AXIS_9
        set(value) {
            field = value
            if (isConnected()) configureAlgorithm()
        }

    /** Sets [algorithm], then reads register 0x24 back into [linkStats]. */
    fun configureAlgorithm() = sendCommands(algorithm.command, READ_ALGORITHM)

    /** On connect: [outputRate], [installation] and [algorithm] under one unlock, all read back. */
    private fun applySettings() =
        sendCommands(outputRate.command, installation.command, algorithm.command, READ_RATE, READ_INSTALLATION, READ_ALGORITHM)

    /**
     * Makes [ImuSample.timestamp] relative to now; until called, timestamps are
     * [SystemClock.elapsedRealtime] values (ms since boot).
     */
    fun resetRecordingTimestamp() {
        recordingStartTime = SystemClock.elapsedRealtime()
        lastSampleTimestamp = Long.MIN_VALUE
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

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}
