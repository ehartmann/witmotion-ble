package fr.eande.witmotion

/**
 * How the sensor is mounted, which its angles are computed against (register 0x23,
 * WT9011DCL-BT50 communication protocol v25-04-06, section 3.7).
 */
enum class Installation(val code: Int) {
    /** Lying flat, label (Z axis) up. */
    HORIZONTAL(0x00),

    /**
     * Standing, with the Y axis arrow pointing up: mounted any other way, the sensor reports
     * no error but every angle is wrong.
     */
    VERTICAL(0x01);

    /** `FF AA 23 ORIENT 00`; needs the config unlock first. */
    val command: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x23, code.toByte(), 0x00)

    companion object {
        fun fromCode(code: Int): Installation? = entries.firstOrNull { it.code == code }
    }
}
