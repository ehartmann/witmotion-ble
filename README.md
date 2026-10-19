# witmotion-ble

Android library for the **WitMotion WT9011DCL** Bluetooth LE IMU, shared by
Witness (sensor logger app) and Archer Companion.

| Module | Package | Content |
|---|---|---|
| `:ble` | `fr.eande.witmotion` | Scan, connect, auto-reconnect, registered sensors, frame parser, commands (output rate, calibration) |
| `:archery` | `fr.eande.witmotion.archery` | Shot phase detection, shot cycle detection, shot scoring and error classification, `ShotTraceView` |

## Usage

The library is consumed as a git submodule plus a Gradle included build:

```bash
git submodule add https://github.com/ehartmann/witmotion-ble.git witmotion-ble
```

```kotlin
// settings.gradle.kts
includeBuild("witmotion-ble")

// app/build.gradle.kts
implementation("fr.eande.witmotion:ble")
implementation("fr.eande.witmotion:archery") // optional, pulls :ble
```

Consumers must use the same Android Gradle Plugin and Kotlin versions as this
build (AGP refuses two AGP versions in one build), and enable submodules in CI
(`actions/checkout` with `submodules: true`).

```kotlin
val sensor = ImuSensorManager(context)
sensor.addSampleListener { sample -> /* BLE thread */ }
sensor.startScan()                      // discoveredDevices: StateFlow
sensor.connect(device)                  // connectionState, batteryLevel, lastSample
```

The app requests the runtime permissions (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on
API 31+, `ACCESS_FINE_LOCATION` below); the library manifest declares them.

## Sensor protocol

| Frame | Layout |
|---|---|
| BLE 5.0 default, 20 bytes | `55 61` + ax ay az wx wy wz roll pitch yaw (int16 LE) |
| Legacy, 11 bytes | `55 <type>` + 4 × int16 LE + checksum (`51` acc, `52` gyro, `53` angle, `54` mag, `59` quaternion) |

Scales: acceleration `raw / 32768 × 16` g, angular velocity `raw / 32768 × 2000` °/s,
angles `raw / 32768 × 180` °. GATT: service `FFE5`, notify `FFE4`, write `FFE9`.

Commands are `FF AA <register> <low> <high>`, always preceded by the unlock
`FF AA 69 88 B5`. On connect the library sets register `0x03` (output rate) to
`0x08` (50 Hz) without saving to flash. Calibration uses register `0x01`
(`1` accelerometer, `2` magnetometer, `0` exit) followed by a save (`FF AA 00 00 00`).
All GATT writes and reads go through `GattOpQueue`: Android rejects an operation
issued while another one is in flight.

## Build & test

```bash
./gradlew testDebugUnitTest lintDebug
```

JDK 21, AGP 9.4.1, Kotlin 2.4.21, compileSdk 36, minSdk 26.
