package fr.eande.witmotion

/**
 * A single timestamped IMU reading from the WT9011DCL sensor.
 */
data class ImuSample(
    val timestamp: Long,
    val acceleration: FloatArray? = null,  // [ax, ay, az] in g
    val gyroscope: FloatArray? = null,     // [wx, wy, wz] in °/s
    val euler: FloatArray? = null,         // [roll, pitch, yaw] in °
    val quaternion: FloatArray? = null     // [q0, q1, q2, q3]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImuSample) return false
        return timestamp == other.timestamp
    }
    override fun hashCode(): Int = timestamp.hashCode()
}
