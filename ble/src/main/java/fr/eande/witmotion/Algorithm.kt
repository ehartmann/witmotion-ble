package fr.eande.witmotion

/**
 * How the sensor computes its heading (register 0x24, AXIS6, in WitMotion's standard
 * communication protocol; the WT9011DCL-BT50 protocol v25-04-06 at hand does not list it).
 */
enum class Algorithm(val code: Int) {
    /** Heading from the magnetometer: absolute, but disturbed near steel (indoor halls). */
    AXIS_9(0x00),

    /** Heading integrated from the gyroscope: relative, drifts slowly, ignores magnetic fields. */
    AXIS_6(0x01);

    /** `FF AA 24 AXIS6 00`; needs the config unlock first. */
    val command: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x24, code.toByte(), 0x00)

    companion object {
        fun fromCode(code: Int): Algorithm? = entries.firstOrNull { it.code == code }
    }
}
