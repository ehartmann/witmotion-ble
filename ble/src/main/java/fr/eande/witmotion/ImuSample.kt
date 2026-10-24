package fr.eande.witmotion

/**
 * A single timestamped IMU reading from the WT9011DCL sensor.
 *
 * [timestamp] is in ms on the monotonic [android.os.SystemClock.elapsedRealtime] clock (relative
 * to the last ImuSensorManager.resetRecordingTimestamp call, if any): unlike the wall clock it
 * never jumps when the phone adjusts its time, so differences between samples stay valid.
 */
public data class ImuSample(
    val timestamp: Long,
    val acceleration: FloatArray? = null,  // [ax, ay, az] in g
    val gyroscope: FloatArray? = null,     // [wx, wy, wz] in °/s
    val euler: FloatArray? = null,         // [roll, pitch, yaw] in °
    val quaternion: FloatArray? = null,    // [q0, q1, q2, q3]
    val sensorTimeMs: Long? = null         // the sensor's own counter, only during a clock check
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImuSample) return false
        return timestamp == other.timestamp && sensorTimeMs == other.sensorTimeMs &&
            acceleration.contentEquals(other.acceleration) && gyroscope.contentEquals(other.gyroscope) &&
            euler.contentEquals(other.euler) && quaternion.contentEquals(other.quaternion)
    }

    override fun hashCode(): Int {
        var h = timestamp.hashCode()
        h = 31 * h + sensorTimeMs.hashCode()
        h = 31 * h + acceleration.contentHashCode()
        h = 31 * h + gyroscope.contentHashCode()
        h = 31 * h + euler.contentHashCode()
        return 31 * h + quaternion.contentHashCode()
    }
}
