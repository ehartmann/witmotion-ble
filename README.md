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
implementation("fr.eande.witmotion:archery:0.6.0") // pulls :ble; or fr.eande.witmotion:ble alone
```

To develop an app against a local checkout instead, add to its `settings.gradle.kts`
`if (providers.gradleProperty("witmotion.local").isPresent) includeBuild("../witmotion-ble")`
and build with `-Pwitmotion.local`; the included build then needs the same AGP version.

### Releasing

```bash
git tag v0.6.0 && git push origin v0.6.0
```

The Release workflow runs the unit tests, builds the AARs with that version and creates
the release, its notes listing the commits since the previous tag. Locally:
`./gradlew publishAllPublicationsToReleaseRepository -PlibVersion=0.6.0` writes the same
files to `build/release-repo/`.

### API

```kotlin
val sensor = ImuSensorManager(context)
sensor.addSampleListener { sample -> /* BLE thread */ }
sensor.startScan()                      // discoveredDevices: StateFlow
sensor.startScan(addresses)             // only these sensors: Android pauses an unfiltered scan, screen off
sensor.connect(device)                  // connectionState, batteryLevel, lastSample
sensor.outputRate = OutputRate.HZ_100   // set on every connect, at once if connected
sensor.installation = Installation.VERTICAL  // likewise: standing sensor, Y arrow up
sensor.bandwidth = Bandwidth.suggestedFor(100)  // likewise: filter cut-off (register 0x1F), 20 Hz by default
                                        // linkStats: frame counts, negotiated interval, rate read back
val check = sensor.runClockCheck()      // suspend, 30 s: sensor clock rate, drift against the phone, lost frames

val connector = ImuAutoConnector(sensor, RegisteredImuStore(prefs), scope)
connector.tryAutoConnect()                     // first registered sensor that answers a 5 s scan
connector.tryAutoConnect(onlyIfSingle = true)  // only when a single registered sensor answers
                                               // returns the attempt (or the one in flight), null when
                                               // connected, connecting or nothing is registered
```

`runClockCheck` switches the 0x61 frames to the sensor's millisecond counter (register `0x96` = 2)
for the duration, which **replaces the Euler angles**: samples carry `sensorTimeMs` and no `euler`,
and the angles stay absent for about half a second after the end. The manager cannot tell which
listener records, so the app must not record during a check. The angles are restored in a
`finally` (error, cancellation) and `0x96` = 0 is written on every connect, so a check cut short
never leaves the sensor without angles. A disconnect or a new connect during a check ends it at
once, with `aborted` set in the result. The timestamp frame length (18 bytes in the protocol
table, maybe 20) is detected and logged (`Timestamp frames are N bytes`).

`ImuAutoConnector` also reconnects, up to three times, after an unexpected disconnect. Its
scans are filtered on the registered sensors, so they keep running with the screen off.

The app requests the runtime permissions (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on
API 31+, `ACCESS_FINE_LOCATION` below); the library manifest declares them.

### Why a shot was not detected

`DetectionDiagnostics.explain(samples)` runs the shot detector over a recording (any `Sequence<ImuSample>`,
streamed) and returns a `DetectionReport`: the `shots` the detector emits (the same ones as
`ShotCycleDetector.detectAll`, as `drawStartMs` / `releaseMs` / `endMs`), the `nearMisses` (draws that did
not become a shot, with a `Reason` and the figures behind it) and the `weakSpikes` (acceleration jumps
from `weakSpikeG`, 1 g by default, up to the release threshold, outside the release of a detected shot).
It uses the analyzer's own thresholds and math, so a tuned `BowMovementAnalyzer` is explained as it detects.

| Reason | Meaning (default thresholds) |
|---|---|
| `DRAW_TOO_LONG` | The detector gave up on the window (30 s from its first draw, so the attempt can be shorter) and the attempt never held for 500 ms |
| `HOLD_TOO_SHORT` | A jump over 2 g (or movement over 20 deg/s with such a jump just ahead) came before a hold reached 500 ms |
| `SPIKE_TOO_WEAK` | A hold reached 500 ms, the largest jump stayed under 2 g but reached `weakSpikeG` |
| `NO_SPIKE` | A hold reached 500 ms and no jump reached `weakSpikeG` |
| `MOVED_DURING_HOLD` | No hold ever reached 500 ms and no jump over 2 g came, after holds (gyro under 5 deg/s) broken by movement over 20 deg/s |

An attempt starts at a draw and runs through the holds shorter than 500 ms, until a hold of 500 ms or more
ends by movement, or the detector gives up on the window, or the recording ends. When several reasons fit,
the first of the table wins. `HOLD_TOO_SHORT` ends the attempt where it happens, even when a shot follows:
a premature release is always reported. `MOVED_DURING_HOLD` is reported only when the attempt ends without a
confirmed hold, a let-down or the end of the recording; the short broken holds before a real shot are normal
aiming and are not listed. With a confirmed hold the reason is `SPIKE_TOO_WEAK` or `NO_SPIKE`.

A `NearMiss` also carries `maxDrawGyroDps`, `holdMs`, `holdMeanGyroDps` and `maxAccelJumpG`: the largest jump
from the sample after the longest hold's start to the hold's exit plus 200 ms (the analyzer's look-ahead for a
release whose gyro crosses first) when movement ended it, or to the cut of the window or recording otherwise.
`afterReleaseMs` must equal the value given to `ShotCycleDetector.detectAll` for `shots` to agree.

`BowMovementAnalyzer.releaseJumpG(samples, releaseIndex)` returns the largest 20 ms acceleration jump, in g, from a release start over `releaseWindowMs` (200 ms by default): the per-shot release shock.

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
(output rate) to `outputRate` (`0x08`, 50 Hz, by default) and register `0x23`
(installation) to `installation` (`0`, horizontal, by default; `1`, vertical, needs the Y
axis arrow up) and register `0x24` (algorithm) to `algorithm` (`0`, 9-axis, magnetometer
heading, by default; `1`, 6-axis, heading integrated from the gyroscope), without saving to
flash, then reads them back (`FF AA 27 03 00`, `FF AA 27 23 00`, `FF AA 27 24 00`) into
`linkStats`. Register `0x1F` (accelerometer and gyroscope filter cut-off: 256, 188, 98, 42, 20,
10 or 5 Hz, `20` by default, `FF AA 1F <0-6> 00`) is written the same way from `bandwidth`;
the protocol does not document reading it back, so `linkStats.bandwidthCode` may stay null.

Writing the installation did not change the reported accelerations: lying flat,
the sensor read gravity on Z with register `0x23` read back as `1` (2026-10-21); keep the
default and interpret the axes in the app when only raw data is used. The installation and
the algorithm are written even at their defaults: the sensor keeps the last value written, even by another app. Register `0x24` is not in the
WT9011DCL-BT50 protocol at hand; its values come from WitMotion's standard protocol
(see References) and are confirmed by the read-back on the sensor. Calibration uses register
`0x01` (`1` accelerometer, `7` magnetic field, `0` end) followed by a save
(`FF AA 00 00 00`). The battery level is register `0x64`, read every minute with
`FF AA 27 64 00` (no unlock); the `55 71` reply carries the voltage in 0.01 V, mapped to a
percentage with the protocol's table. The sensor has no GATT Battery Service.
All GATT writes and reads go through `GattOpQueue`: Android rejects an operation
issued while another one is in flight.

### Packed frames and sample timestamps

From 50 Hz on, the sensor sends a notification about every 40 ms and packs the frames
sampled meanwhile into it: 2 at 50 Hz, 4 at 100 Hz (the manual: "50Hz~200Hz data package
transmission"; the MTU of 247 the sensor requests leaves room for them). The library makes
one sample per `55 61` frame and dates it on a regular clock: the previous sample plus one
sampling period, pulled an eighth of the way toward the time its arrival implies (the last
frame of a notification at reception, each earlier one a period before), at least half a
period after the previous sample, and restarted at the arrival times after a link gap of over
250 ms. Arrival jitter does not show and the clock still follows the sensor's actual rate.
Up to 0.2.5 the last frame was dated at reception, which repeated timestamps (1 to 18 % of
the samples at 50 to 200 Hz). Up to 0.2.3 it merged a
notification into a single sample, so every rate from 50 Hz up gave 25 Hz (measured on a
Galaxy S22, 2026-10-20).

About 4 s after connecting, the sensor sets the connection interval to 30 ms with a
peripheral latency of 10, overriding `CONNECTION_PRIORITY_HIGH`; with packing, that interval
is enough (RGu0/WT901 measured 198 Hz at 200 Hz). `linkStats` exposes the interval and
latency the stack reports through the hidden `BluetoothGattCallback.onConnectionUpdated`
(as Nordic's Android-BLE-Library does) and the rate register read back.

## Changelog

### 0.6.0

Scores change with L5, the API changes with L6 and L9; the rest are fixes.

- L1: the sample clock no longer runs ahead of real time when the sensor is faster than `outputRate`. The manager compares the rate register read back with `outputRate`, writes the rate again once per connect on a mismatch, and `LinkStats.rateMismatch` (new) tells an app to flag the session.
- L2: a scan no longer overwrites the state of a live link (`connectionState`, `isConnected()`, `runClockCheck`, auto-reconnect).
- L3: `CONNECTED` is reported once the notification enable succeeds or the first notification arrives, whichever comes first (the settings are written then, so a setter called while connecting is not lost); no notification within 5 s of the enable callback, a lost GATT callback (2 s per queued operation) or a connect that never completes (20 s) ends in a disconnect and the usual reconnect instead of a frozen link.
- L4: one failing sample listener no longer starves the others or `lastSample`.
- L5: **behaviour change.** `BowMovementMetrics.releaseScore` counts the milliseconds above the release threshold with the rate-independent `accelJump` (10 points per 20 ms, as before at 50 Hz) and `ShotErrorClassifier` takes its flinch spike from `accelJump`: the same release now scores within about 15 points at 50, 100 and 200 Hz, where it varied by 60 points. Scores and flinch detection change for recordings not sampled at 50 Hz.
- L6: **API change.** `ShotErrorClassifier` returns `CLEAN` with confidence 0.3 and details `No release`, `No follow-through` or `No euler angles` when no error is found and an input is missing, instead of `CLEAN` at 0.9. `ShotError.PUSHING` and `ShotError.HEELING`, never returned, are removed.
- L7: `ImuAutoConnector` reports success as soon as the link is up (waits for `CONNECTED` or `DISCONNECTED`, 25 s) instead of a fixed 5 s, and always stops its scan. A drop during a retry no longer restarts its 3-attempt budget, so `Exhausted` is reachable; after it, auto-reconnect resumes at the next `CONNECTING`, `CONNECTED` or `tryAutoConnect()`.
- L8: tests cover the notification and connect pipeline.
- L9: **API change.** Both modules build with `explicitApi()`. `ImuDataParser`, `GattOpQueue` and `ImuSensorManager.onDeviceDiscovered` are internal. The command byte arrays (`UNLOCK`, `SAVE_CONFIG`, `READ_*`, `ImuCalibrationHelper.*`) are getters returning a fresh array, source-compatible. `ImuSample.equals` compares every field instead of the timestamp only.
- L10: `cantAngle` and the breaking-wrist check average the roll across the +/-180 wrap (`circularMean`).

## Build & test

```bash
./gradlew testDebugUnitTest lintDebug
```

JDK 21, AGP 9.4.1, Kotlin 2.4.21, compileSdk 36, minSdk 26.

## References

- WT9011DCL-BT50 Communication Protocol, manual v25-04-06 (WitMotion PDF, not published
  online) — frames `55 61` / `55 71`, commands, register table, output rates `0x01`–`0x0B`
  (section 3.4), "the maximum amount of data uploaded via Bluetooth is 20 Bytes each time"
  (section 1), which no longer holds from 50 Hz on, where notifications pack several frames.
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
- [WIT standard communication protocol (gitbook)](https://wit-motion.gitbook.io/witmotion-sdk/wit-standard-protocol/wit-standard-communication-protocol)
  — register `0x24` AXIS6: `0` 9-axis (magnetic heading, absolute), `1` 6-axis (integrated
  heading, relative), example `FF AA 24 01 00`; register `0x23` ORIENT as used here.
- [WitMotion BLE 5.0 protocol (gitbook)](https://wit-motion.gitbook.io/witmotion-sdk/ble-5.0-protocol/bluetooth-5.0-communication-protocol.md)
  — register read `FF AA 27 XX 00`, `55 71` reply (start register + 8 registers).
- [RGu0/WT901](https://github.com/RGu0/WT901) — Python library tested on a WT901BLE67:
  49.45 / 99.26 / 198.43 Hz measured at 50 / 100 / 200 Hz; `0x0A` gives 99.29 Hz.
- [RGu0/WT901, `docs/protocol.md`](https://github.com/RGu0/WT901/blob/main/docs/protocol.md)
  — "50 Hz 以上一次 BLE 通知可能携带多帧" (above 50 Hz one notification may carry several
  frames), 4 frames per notification at 100 Hz, firmware `10080.1.22` in registers
  `0x2E`/`0x2F`, a connection interval of about 30 ms there too.
- [FreecityDong/WT9011DCL](https://github.com/FreecityDong/WT9011DCL) — WT901BLE67 on macOS:
  about 195 Hz received at 200 Hz; unlock, rate write and save.
- [BWT901BLECL5.0 manual (Manuals+)](https://manuals.plus/wit-motion/bwt901blecl5-0-9-axis-imu-sensor-manual)
  — "Data output frequency: 0.2Hz~200Hz, 50Hz~200Hz data package transmission, default
  10HZ."
- [WitBluetooth_BWT901BLE5_0 issue #6](https://github.com/WITMOTION/WitBluetooth_BWT901BLE5_0/issues/6)
  — a WT9011DCL-BT50 on firmware `10080.1.6` at 100 Hz, with a timestamped output content
  selected through register `0x96`. No firmware is published; none is needed for packing.
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
