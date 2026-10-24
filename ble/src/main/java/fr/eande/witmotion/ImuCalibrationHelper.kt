package fr.eande.witmotion

import android.util.Log

/**
 * Sends calibration commands to the WT9011DCL sensor via ImuSensorManager.
 *
 * Calibration procedure:
 * 1. Accelerometer: place flat, call [startAccelCalibration], wait 5s, call [finishCalibration]
 * 2. Magnetometer: call [startMagCalibration], rotate in figure-8 for 30s, call [finishCalibration]
 * 3. Gyroscope: automatic at startup when stationary
 */
public class ImuCalibrationHelper(private val sensorManager: ImuSensorManager) {

    public companion object {
        private const val TAG = "ImuCalibration"

        // Register 0x01 (CALSW), per the WT9011DCL-BT50 protocol v25-04-06 section 3.2:
        // 1 = accelerometer, 7 = magnetic field, 0 = end calibration
        public val ACCEL_CAL_START: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x01, 0x01, 0x00)
        public val MAG_CAL_START: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x01, 0x07, 0x00)
        public val CAL_EXIT: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x01, 0x00, 0x00)
    }

    public fun startAccelCalibration() {
        Log.d(TAG, "Starting accelerometer calibration")
        sensorManager.sendCommands(ACCEL_CAL_START)
    }

    public fun startMagCalibration() {
        Log.d(TAG, "Starting magnetometer calibration")
        sensorManager.sendCommands(MAG_CAL_START)
    }

    /** Ends any active calibration and saves the result to the sensor's flash. */
    public fun finishCalibration() {
        Log.d(TAG, "Finishing calibration")
        sensorManager.sendCommands(CAL_EXIT, ImuSensorManager.SAVE_CONFIG)
    }
}
