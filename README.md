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
(output rate) to `0x08` (50 Hz) without saving to flash. Calibration uses register
`0x01` (`1` accelerometer, `7` magnetic field, `0` end) followed by a save
(`FF AA 00 00 00`). The battery level is register `0x64`, read every minute with
`FF AA 27 64 00` (no unlock); the `55 71` reply carries the voltage in 0.01 V, mapped to a
percentage with the protocol's table. The sensor has no GATT Battery Service.
All GATT writes and reads go through `GattOpQueue`: Android rejects an operation
issued while another one is in flight.

## Build & test

```bash
./gradlew testDebugUnitTest lintDebug
```

JDK 21, AGP 9.4.1, Kotlin 2.4.21, compileSdk 36, minSdk 26.
