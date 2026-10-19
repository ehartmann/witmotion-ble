package fr.eande.witmotion

import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        private const val TAG = "ImuSensorManager"
        private const val DEVICE_NAME_PREFIX = "WT"

        val SERVICE_UUID: UUID = UUID.fromString("0000FFE5-0000-1000-8000-00805F9A34FB")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9A34FB")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9A34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val BATTERY_REFRESH_MS = 60_000L

        // WitMotion device-config commands: [0xFF] [0xAA] [RegAddr] [DataL] [DataH].
        // Register 0x03 is the output rate (0x08 = 50 Hz); 0x69/0xB588 unlocks config writes.
        val UNLOCK = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x69, 0x88.toByte(), 0xB5.toByte())
        val SET_RATE_50HZ = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x03, 0x08, 0x00)
        val SAVE_CONFIG = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x00, 0x00, 0x00)
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

    fun startScan() {
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
            scanner.startScan(null, settings, scanCallback)
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
                Log.e(TAG, "IMU service not found")
                disconnect()
                return
            }

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
            configureRate()
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

        @Deprecated("Deprecated in API 33+, kept for backward compatibility with minSdk 26")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_UUID) {
                val level = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                _batteryLevel.value = level
                Log.d(TAG, "Battery level: $level%")
            }
            gattOps.next(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Notifications enabled (status=$status)")
            gattOps.next(gatt)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            gattOps.next(gatt)
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
            val packets = ImuDataParser.parse(data)
            if (packets.isNotEmpty()) {
                val sample = ImuDataParser.packetsToSample(packets, System.currentTimeMillis() - recordingStartTime)
                _lastSample.value = sample
                sampleListeners.forEach { it(sample) }
            }
        }
    }

    // --- Commands ---

    /**
     * Queues WitMotion `FF AA reg L H` commands, preceded by the config unlock. Writes run one
     * at a time; a command sent while disconnected is dropped.
     */
    fun sendCommands(vararg commands: ByteArray) {
        val g = gatt ?: return
        for (command in listOf(UNLOCK) + commands) gattOps.enqueue(g) { sendCommand(command) }
    }

    private fun readBattery() {
        val g = gatt ?: return
        val battery = g.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID) ?: return
        gattOps.enqueue(g) { it.readCharacteristic(battery) }
    }

    /** Sets 50 Hz output. Called automatically on connect; not saved to the sensor's flash. */
    fun configureRate() = sendCommands(SET_RATE_50HZ)

    /** Makes [ImuSample.timestamp] relative to now; until called, timestamps are epoch ms. */
    fun resetRecordingTimestamp() {
        recordingStartTime = System.currentTimeMillis()
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
