# witmotion-ble

Android library for the **WitMotion WT9011DCL** Bluetooth LE IMU, shared by
Witness (sensor logger app) and Archer Companion.

| Module | Package | Content |
|---|---|---|
| `:ble` | `fr.eande.witmotion` | Scan, connect, auto-reconnect, registered sensors, frame parser, commands (output rate, calibration) |
| `:archery` | `fr.eande.witmotion.archery` | Shot phase detection, shot cycle detection, shot scoring and error classification, `ShotTraceView`, `ShotComparisonView` and `ShotComparison` (shot-to-shot consistency) |

## Usage

Each `vX.Y.Z` tag publishes a GitHub Release with the AARs attached (Ivy descriptor,
sources jar and checksums alongside). Read them through an Ivy repository on the
release download URLs; the repository is public, so no token is needed:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        ivy {
            name = "witmotion-ble releases"
            url = uri("https://github.com/ehartmann/witmotion-ble/releases/download")
            patternLayout {
                artifact("v[revision]/[module]-[revision](-[classifier]).[ext]")
                ivy("v[revision]/[module]-[revision].[ext]")
            }
            metadataSources { ivyDescriptor() }
            content { includeGroup("fr.eande.witmotion") }
        }
    }
}

// app/build.gradle.kts
implementation("fr.eande.witmotion:archery:0.1.0") // pulls :ble; or fr.eande.witmotion:ble alone
```

To develop an app against a local checkout instead, add to its `settings.gradle.kts`
`if (providers.gradleProperty("witmotion.local").isPresent) includeBuild("../witmotion-ble")`
and build with `-Pwitmotion.local`; the included build then needs the same AGP version.

### Releasing

```bash
git tag v0.2.0 && git push origin v0.2.0
```

The Release workflow runs the unit tests, builds the AARs with that version and creates
the release, its notes listing the commits since the previous tag. Locally:
`./gradlew publishAllPublicationsToReleaseRepository -PlibVersion=0.2.0` writes the same
files to `build/release-repo/`.

### API

```kotlin
val sensor = ImuSensorManager(context)
sensor.addSampleListener { sample -> /* BLE thread */ }
sensor.startScan()                      // discoveredDevices: StateFlow
sensor.connect(device)                  // connectionState, batteryLevel, lastSample
sensor.outputRate = OutputRate.HZ_100   // set on every connect, at once if connected
                                        // linkStats: frame counts, negotiated interval, rate read back
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

Reference: WT9011DCL-BT50 Communication Protocol v25-04-06 and WitMotion's Python SDK.

Commands are `FF AA <register> <low> <high>`, preceded by the unlock `FF AA 69 88 B5`
and sent 100 ms apart, as the SDK does. On connect the library sets register `0x03`
(output rate) to `outputRate` (`0x08`, 50 Hz, by default) without saving to flash, then
reads it back (`FF AA 27 03 00`) into `linkStats.outputRateCode`. Calibration uses register
`0x01` (`1` accelerometer, `7` magnetic field, `0` end) followed by a save
(`FF AA 00 00 00`). The battery level is register `0x64`, read every minute with
`FF AA 27 64 00` (no unlock); the `55 71` reply carries the voltage in 0.01 V, mapped to a
percentage with the protocol's table. The sensor has no GATT Battery Service.
All GATT writes and reads go through `GattOpQueue`: Android rejects an operation
issued while another one is in flight.

### Measured rate

With 50 Hz set, a WT9011DCL on a Galaxy S22 delivers **24.9 Hz** (2026-10-20): frames
arrive in a fixed 30, 30, 60 ms pattern, i.e. one frame every 40 ms leaving on a 30 ms
connection-event grid, one 20-byte frame per notification (the protocol caps a BLE upload
at 20 bytes, so the negotiated MTU of 247 does not help). `CONNECTION_PRIORITY_HIGH`
(11.25–15 ms) is requested after service discovery and reaches the controller, yet the
grid stays at 30 ms. Neither WitMotion's documentation nor its SDKs mention a BLE rate cap
or connection parameters, and its Android SDK never requests a connection priority.

`linkStats` exposes the interval and latency the stack reports through the hidden
`BluetoothGattCallback.onConnectionUpdated` (as Nordic's Android-BLE-Library does) and the
rate register read back, to tell a link cap from a sensor cap.

## Build & test

```bash
./gradlew testDebugUnitTest lintDebug
```

JDK 21, AGP 9.4.1, Kotlin 2.4.21, compileSdk 36, minSdk 26.

## References

- WT9011DCL-BT50 Communication Protocol, manual v25-04-06 (WitMotion PDF, not published
  online) — frames `55 61` / `55 71`, commands, register table, output rates `0x01`–`0x0B`
  (section 3.4), "the maximum amount of data uploaded via Bluetooth is 20 Bytes each time"
  (section 1).
- WT9011DCL-BT5.0 Datasheet v23-0608 (WitMotion PDF) — 0.2–200 Hz output, 10 Hz by
  default, 115200 baud internal data interface.
- [WITMOTION/WitBluetooth_BWT901BLE5_0](https://github.com/WITMOTION/WitBluetooth_BWT901BLE5_0)
  — WitMotion's BLE 5.0 SDKs (Android, iOS, C#, Python, Unity); the Python SDK gives the
  UUIDs, register reads without unlock and the 100 ms spacing.
- [`DeviceModel.java` (Android SDK)](https://github.com/WITMOTION/WitBluetooth_BWT901BLE5_0/blob/9efaab0fdd6a06dc807bf80402e58aa91b431c6f/Android_Java/wit-example-ble5/WitSDK/src/main/java/com/wit/witsdk/Device/DeviceModel.java#L362-L388)
  — rates 1–200 Hz, 200 Hz = 11 (`0x0B`); `SendData` writes the rate then saves
  (`FF AA 00 00 00`) 100 ms later, without unlock; the SDK never calls
  `requestConnectionPriority`.
- [`Bwt901bleProcessor.cs` (C# SDK)](https://github.com/WITMOTION/WitBluetooth_BWT901BLE5_0/blob/9efaab0fdd6a06dc807bf80402e58aa91b431c6f/Windows_C%23/Wit.Example_BWT901BLE/ble5/Components/Bwt901bleProcessor.cs#L80-L107)
  — WitMotion's own clients poll registers `0x3A` (magnetic field), `0x51` (quaternion),
  `0x30` (time), and `0x64` / `0x40` (battery, temperature) in a loop.
- [WitMotion BLE 5.0 protocol (gitbook)](https://wit-motion.gitbook.io/witmotion-sdk/ble-5.0-protocol/bluetooth-5.0-communication-protocol.md)
  — register read `FF AA 27 XX 00`, `55 71` reply (start register + 8 registers).
- [WT901BLECL datasheet](https://cdn.robotshop.com/rbm/f83835f4-5e29-4ee0-9cc2-e49300031503/b/bf5c1f59-3b36-40a4-a5f0-e5a0eea52565/a8cd29da_wt901blecl-datasheet.pdf)
  — sister model: up to 200 Hz, 20 bytes per BLE upload; lists 200 Hz as `0x0A`, unlike
  the WT9011DCL protocol and SDK (`0x0B`).
- [Nordic Android-BLE-Library, `BleManagerHandler.java`](https://github.com/NordicSemiconductor/Android-BLE-Library/blob/da698f297c75784ca3932bc19777cde93ec7c975/ble/src/main/java/no/nordicsemi/android/ble/BleManagerHandler.java#L2915-L2922)
  — overrides the hidden `onConnectionUpdated(gatt, interval, latency, timeout, status)`
  with `@Keep` to learn the negotiated connection parameters.
- [`BluetoothGatt.requestConnectionPriority`](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestConnectionPriority(int))
  — the Android API used; the 9–12 × 1.25 ms it requests comes from the S22's Bluetooth
  stack log.
- [WITMOTION/WitStandardProtocol_JY901 issue #14](https://github.com/WITMOTION/WitStandardProtocol_JY901/issues/14)
  — anecdote on a wired unit: 100 Hz set, about 70 Hz received, unsteady.
- [ctroncozo/witmotion_ble](https://github.com/ctroncozo/witmotion_ble) — third-party
  Python BLE client supporting 20/50/100 Hz; no measured rates.
- [Changing WT901BLECL settings (Zenn)](https://zenn.dev/fastriver/articles/wt901blecl_change_settings)
  — writes the rate to `FFE9` without unlock or save, 0.2 s between register reads.
