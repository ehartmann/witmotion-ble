package fr.eande.witmotion

/**
 * Represents a discovered or connected WT9011DCL IMU sensor.
 */
data class ImuSensorDevice(
    val address: String,
    val name: String,
    val rssi: Int = 0
)
