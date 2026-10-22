package fr.eande.witmotion

/** Accelerometer and gyroscope filter cut-off, register 0x1F (WT9011DCL-BT50 communication protocol v25-04-06, section 3.9). */
enum class Bandwidth(val hz: Int, val code: Int) {
    HZ_256(256, 0),
    HZ_188(188, 1),
    HZ_98(98, 2),
    HZ_42(42, 3),
    HZ_20(20, 4),
    HZ_10(10, 5),
    HZ_5(5, 6);

    /** `FF AA 1F BW 00`; needs the config unlock first. */
    val command: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x1F, code.toByte(), 0x00)

    companion object {
        /** Bits 15:4 of the register are reserved, so only the low nibble is read. */
        fun fromCode(code: Int): Bandwidth? = entries.firstOrNull { it.code == code and 0x0F }

        /** The highest cut-off under half the output rate: 98 Hz at 200 Hz, 42 Hz at 100 Hz, 20 Hz below. */
        fun suggestedFor(rateHz: Int): Bandwidth = when {
            rateHz >= 200 -> HZ_98
            rateHz >= 100 -> HZ_42
            else -> HZ_20
        }
    }
}

/** What the 0x61 frames carry, register 0x96 (section 3.11): the angles, or the sensor's millisecond counter in their place. */
enum class OutputContent(val code: Int) {
    ANGLES(0),
    TIMESTAMP(2);

    /** `FF AA 96 CONTENT 00`; needs the config unlock first. */
    val command: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x96.toByte(), code.toByte(), 0x00)
}
