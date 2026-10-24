---
title: "witmotion-ble: codebase analysis"
author: "Eric Hartmann"
date: "2026-10-24"
titlepage: true
titlepage-color: "1F3864"
titlepage-text-color: "FFFFFF"
titlepage-rule-color: "FFFFFF"
toc: true
toc-own-page: true
---

# witmotion-ble: codebase analysis (2026-10-24)

This analysis covers `main` @ 177f4c1 (release 0.5.1), both modules: `:ble`, the WT9011DCL Bluetooth stack, and `:archery`, shot detection and analysis. It looks at:

- unit tests;
- coverage;
- lint;
- a full read of the 22 main files.

Every finding needs a concrete case.

**Not checked.** No real sensor or phone was used. The following are argued from the code and from known Android behaviour, not reproduced:

- GATT callback ordering;
- status 133;
- scan throttling.

The detection thresholds (5 and 20 °/s, 2 g) were not checked against real shots. The consumers were read only for how they call `startScan` and `outputRate`: witness scans only from its sensor screen, and the rate is written at every connection by `applySettings`.

Effort: **S** under an hour, **M** about half a day, **L** a day or more. ✔ marks findings checked against the code a second time.


## Status (2026-10-24, end of day)

| Items | PR | Release |
|---|---|---|
| L1–L10, plus the review follow-ups (CONNECTED on the enable or the first notification, settings written at link-up, bounded reconnect budget, GATT op timeout drops the link, atomic clock reset, release-score gap cap) | #6 | 0.6.0 |
| Nice to have: dead code, one register table, shot callback outside the lock, detector reset on a clock restart, parser resync check, LiveAim trail, volatile/synchronized fields, release workflow pins and lint | #7 | 0.6.1 |

Not done: closing the GATT only after the disconnect callback (#18, only if seen on the phone). Still to check on the device: the new connect behaviour with the real sensor (first range session).

## Summary

The pure logic is solid, independent of the sample rate where it matters, and well tested:

- the frame parser: 400 000 random parses gave no crash;
- the shot cycle detector, at about 2.3 µs per sample on the host JVM;
- `BowMovementAnalyzer`;
- the diagnostics.

`:archery` covers 92 % of its lines. The risk sits in the Android layer: `ImuSensorManager` covers 32 % of its lines, and its notification path is not executed at all. Three things can silently corrupt or empty a recording:

- a wrong assumed rate;
- a scan during a connection;
- an unchecked GATT result.

Fix L1 to L3 and add the L8 tests first.

## Numbers

| Module | Tests | Failures | Lines | Branches |
|---|---|---|---|---|
| `:ble` | 92 | 0 | 64.7 % (485 / 750) | 53.4 % (213 / 399) |
| `:archery` | 67 | 0 | 92.2 % (593 / 643) | 76.8 % (400 / 521) |
| **Both** | **159** | **0** | **77.4 %** | **66.6 %** |

**Runs.** The tests take 11.8 s of test time and 8 s of warm Gradle wall time. The slowest classes are `ImuAutoConnectorTest` (5.1 s) and `LiveAimTest` (3.4 s), both under Robolectric. No instrumented tests exist.

**Coverage tooling.** AGP's `enableUnitTestCoverage` reports 0 % for every class loaded through Robolectric, so the figures above come from a manual JaCoCo 0.8.15 agent with `inclnolocationclasses=true`. It is the same workaround witness uses.

**Weakest classes:**

- `ImuSensorManager`: 233 of its 343 lines missed, 17 of its 155 branches covered;
- `ShotTraceView` (drawing): 46 of 112 lines missed;
- `ImuCalibrationHelper`: 23 % of lines.

**Lint.** 0 errors and 3 warnings: Gradle 9.8.1 has 9.8.2 available, and compileSdk 36 has 37 available.

## Must fix

**L1. The sample clock can run ahead of real time without limit** ✔ (`ImuDataParser.kt:197–216`, `ImuSensorManager.kt:438, 519`, effort S)

`packetsToSamples` dates samples on a regular clock from the assumed period. Two things keep it from following reality:

- each sample is placed at least half a period after the previous one;
- the clock restarts only when it falls more than 250 ms behind the arrival time.

**Case.** The sensor really runs at 200 Hz while `outputRate` says 50. After 10 s, the sample times are 9 969 ms ahead, and they never recover. Every duration comes out wrong by 2×: the hold, `minHoldMs` and the 20 ms jump window.

A mismatch comes mainly from a failed rate write. `onCharacteristicWrite` ignores `status`, and the rate read-back (`linkStats.outputRateCode`) is never compared with `outputRate`.

**Fix.**

- Restart the clock when it is ahead by more than `GAP_MS` too.
- Rewrite the rate once when the read-back differs, and expose `rateMismatch`.
- Add one test with the 200 Hz real / 50 Hz assumed packing.

**L2. Scanning overwrites the link state while the link is up** ✔ (`ImuSensorManager.kt:198, 231–233, 258–259`, effort S)

`startScan` sets SCANNING unconditionally. `stopScan` and `onScanFailed` then set DISCONNECTED.

**Case.** In witness, the Scan button of the sensor screen is tapped while connected. During the scan:

- `isConnected()` is false, so the rate, installation, algorithm and bandwidth setters skip their writes (see L1);
- `runClockCheck` throws;
- `ImuAutoConnector` can call `connect()`, which closes the live GATT.

**Fix.** Change the state in those three places only from DISCONNECTED or SCANNING, about 3 lines. This keeps the public enum unchanged.

**L3. GATT results are never checked, and nothing has a timeout** (`ImuSensorManager.kt:345, 375–440`, `GattOpQueue.kt:26–39`, effort M)

CONNECTED is set before the CCCD write completes. The `status` of descriptor and characteristic writes is ignored, as is the result of `discoverServices()`. `GattOpQueue` has no timeout.

**Case.**

- A failed CCCD write gives CONNECTED with no data, which records an empty session.
- One lost write callback jams the queue for the rest of the connection.
- A missing `onServicesDiscovered` leaves CONNECTING forever, and auto-reconnect never restarts.

**Fix.**

- Disconnect on a failed descriptor write, so the existing drop path reconnects.
- Give each op in the queue a 2 s timeout, and treat an op that throws as never started.
- Add a 20 s watchdog on CONNECTING.
- Set CONNECTED only once `onDescriptorWrite` succeeds.

## Should fix

| # | Finding | Where | Fix | Effort |
|---|---|---|---|---|
| L4 | One failing sample listener stops the next listeners for the rest of that notification. A display bug can remove samples from the recording. | `ImuSensorManager.kt:523` | `runCatching` around each listener call | S |
| L5 | `releaseScore` counts samples above a 1 g jump. The same physical shot scores 40, 30 and 70 at 200, 100 and 50 Hz. `checkFlinch` applies the same rule a different way. | `BowMovementAnalyzer.kt:169–173`, `ShotErrorClassifier` | Count milliseconds through `accelJump`, plus a test across rates | M |
| L6 | `ShotErrorClassifier` returns `CLEAN` at 0.9 confidence when the follow-through or the euler angles are missing. Nothing ever returns `PUSHING` or `HEELING`. | `ShotErrorClassifier.kt:49, 79–80` | Low confidence with "No follow-through"; drop or implement the two values (a breaking change) | S |
| L7 | `ImuAutoConnector` reports success when `connect()` is called, then waits a fixed 5 s. A connect that fails 6 s later has already said "reconnected", and every reconnect costs at least 5 s of data. | `ImuAutoConnector.kt:53–124` | Wait for CONNECTED or DISCONNECTED with a 15 s timeout; stop the scan in `finally` | M |
| L8 | No test covers the manager's notification and connect pipeline. L1 to L4 all live there. | `ImuSensorManager.kt` | Three tests through the `internal` `gattCallback` with a mocked `BluetoothGatt`: a packed notification, register replies, the `onServicesDiscovered` write sequence; plus `ImuCalibrationHelper` | M |
| L9 | Nothing detects an API break. The command `ByteArray`s are public and shared, so `UNLOCK[2] = 0` in one consumer corrupts every later write. `ImuSample.equals` compares only the timestamp. | `ImuSensorManager.kt:69–80`, `ImuSample.kt:14–24` | `explicitApi()`, internal command arrays (or copies), the binary-compatibility validator in CI, plain equality for `ImuSample` | M |
| L10 | Roll is averaged across the ±180° wrap: +179 and −179 give a cant of 1.8° instead of about 180°. This is harmless with the current mounting, and wrong for a sensor mounted face down. | `BowMovementAnalyzer.kt:156`, `ShotErrorClassifier.kt:133–149` | A `circularMean` next to `wrapDegrees` | S |

## Refactoring and nice to have

- **Dead code** (`BowMovementAnalyzer`): `maxAccelSpike` is computed and never read, the `gyroMagnitude(…, unused)` overload is unused, and an import is unused. The `indexOf` inside `count` is O(n²) and relies on the timestamp-only equality. Use `zipWithNext`. Effort S.
- **`deliverNotify`** repeats one block for each of four registers. Turn it into a map when a fifth register is added. Effort S.
- **The shot callback runs inside the detector lock on the BLE thread.** A slow consumer delays sampling for about 3 s after each shot. Call it after leaving the lock, and document the thread. Effort S.
- **The detector buffer is not trimmed after `resetRecordingTimestamp()`** when one detector is reused across recordings. Reset it when time goes backwards. Effort S.
- **`55 61` frames have no checksum.** After a lost byte, a `55 61` inside the payload becomes a phantom sample; the fuzz run accepted 554 of them from random bytes. Accept a resynchronised frame only when a `55` or the end of the notification follows it. Effort S.
- **`resetRecordingTimestamp` races with the binder thread** over two separate fields. Swap both at once through an `AtomicReference`. L1's fix also bounds the damage. Effort S.
- **`LiveAim.trail` filters the whole list on every frame.** Walk back from the end with `takeLastWhile`. Effort S.
- **`disconnect()` closes the GATT without waiting for the disconnect callback**, which is known to cause status 133 on some phones. Change it only if this is seen on the S22.
- **Smaller items:**
  - `ImuAutoConnector.isScanning` and `onDeviceDiscovered` are not volatile;
  - `RegisteredImuStore`'s read-modify-write is unsynchronised;
  - the release workflow pins only `setup-gradle` by SHA, and does not run lint.

## Suggested order

1. **One PR for L1 and L2**, as a patch release: the clock resync, the rate read-back check and the scan state guard, with their tests. Both apps pick it up with a version bump.
2. **L8**, the manager tests, then **L3** with the queue timeout covered by a test.
3. **L4, L6, L7, L10** as small fixes.
4. **L5 and L9 in a minor release.** They change scores or the API, so the release notes must say so.
