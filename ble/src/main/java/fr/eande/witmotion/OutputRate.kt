package fr.eande.witmotion

/** Output rates of register 0x03 (WT9011DCL-BT50 communication protocol v25-04-06, section 3.4). */
public enum class OutputRate(public val hz: Int, public val code: Int) {
    HZ_10(10, 0x06),
    HZ_20(20, 0x07),
    HZ_50(50, 0x08),
    HZ_100(100, 0x09),
    HZ_200(200, 0x0B);

    /** `FF AA 03 RATE 00`; needs the config unlock first. */
    public val command: ByteArray get() = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x03, code.toByte(), 0x00)

    public companion object {
        public fun fromCode(code: Int): OutputRate? = entries.firstOrNull { it.code == code }
    }
}
