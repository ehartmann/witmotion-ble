package fr.eande.witmotion

/**
 * Parses WitMotion WT9011DCL BLE packet data.
 *
 * Two frame formats, both little-endian int16 fields:
 *   - BLE 5.0 default (20 bytes, no checksum):
 *     [0x55] [0x61] ax ay az wx wy wz roll pitch yaw
 *   - Legacy serial (11 bytes):
 *     [0x55] [Type] [D0L] [D0H] [D1L] [D1H] [D2L] [D2H] [D3L] [D3H] [Checksum]
 *
 * Multiple packets may be concatenated in a single BLE notification.
 * This parser extracts all valid packets and returns typed ImuSample fields.
 */
object ImuDataParser {

    const val PACKET_SIZE = 11
    const val COMBINED_PACKET_SIZE = 20
    const val HEADER = 0x55.toByte()
    const val TYPE_COMBINED = 0x61.toByte()

    const val TYPE_ACCELERATION = 0x51.toByte()
    const val TYPE_GYROSCOPE = 0x52.toByte()
    const val TYPE_EULER = 0x53.toByte()
    const val TYPE_MAGNETOMETER = 0x54.toByte()
    const val TYPE_QUATERNION = 0x59.toByte()

    /**
     * Parsed result for a single packet.
     */
    data class ParsedPacket(
        val type: Byte,
        val values: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedPacket) return false
            return type == other.type && values.contentEquals(other.values)
        }
        override fun hashCode(): Int = 31 * type.hashCode() + values.contentHashCode()
    }

    /**
     * Parse all valid packets from a BLE notification byte array.
     * Returns a list of typed parsed packets.
     */
    fun parse(data: ByteArray): List<ParsedPacket> {
        val packets = mutableListOf<ParsedPacket>()
        var offset = 0

        while (offset + PACKET_SIZE <= data.size) {
            if (data[offset] != HEADER) {
                offset++
                continue
            }

            if (data[offset + 1] == TYPE_COMBINED && offset + COMBINED_PACKET_SIZE <= data.size) {
                packets.add(ParsedPacket(TYPE_ACCELERATION, parseAcceleration(data, offset)))
                packets.add(ParsedPacket(TYPE_GYROSCOPE, parseGyroscope(data, offset + 6)))
                packets.add(ParsedPacket(TYPE_EULER, parseEuler(data, offset + 12)))
                offset += COMBINED_PACKET_SIZE
                continue
            }

            if (!verifyChecksum(data, offset)) {
                offset++
                continue
            }

            val type = data[offset + 1]
            val values = when (type) {
                TYPE_ACCELERATION -> parseAcceleration(data, offset)
                TYPE_GYROSCOPE -> parseGyroscope(data, offset)
                TYPE_EULER -> parseEuler(data, offset)
                TYPE_QUATERNION -> parseQuaternion(data, offset)
                TYPE_MAGNETOMETER -> parseMagnetometer(data, offset)
                else -> null
            }

            if (values != null) {
                packets.add(ParsedPacket(type, values))
            }

            offset += PACKET_SIZE
        }

        return packets
    }

    /**
     * Merge parsed packets into an ImuSample with the given timestamp.
     */
    fun packetsToSample(packets: List<ParsedPacket>, timestamp: Long): ImuSample {
        var accel: FloatArray? = null
        var gyro: FloatArray? = null
        var euler: FloatArray? = null
        var quat: FloatArray? = null

        for (p in packets) {
            when (p.type) {
                TYPE_ACCELERATION -> accel = p.values
                TYPE_GYROSCOPE -> gyro = p.values
                TYPE_EULER -> euler = p.values
                TYPE_QUATERNION -> quat = p.values
            }
        }

        return ImuSample(
            timestamp = timestamp,
            acceleration = accel,
            gyroscope = gyro,
            euler = euler,
            quaternion = quat
        )
    }

    // --- Packet parsers ---

    private fun parseAcceleration(data: ByteArray, offset: Int): FloatArray {
        // Scale: raw / 32768 * 16g (WitMotion output scale)
        val ax = readInt16(data, offset + 2) / 32768f * 16f
        val ay = readInt16(data, offset + 4) / 32768f * 16f
        val az = readInt16(data, offset + 6) / 32768f * 16f
        return floatArrayOf(ax, ay, az)
    }

    private fun parseGyroscope(data: ByteArray, offset: Int): FloatArray {
        // Scale: raw / 32768 * 2000 °/s
        val wx = readInt16(data, offset + 2) / 32768f * 2000f
        val wy = readInt16(data, offset + 4) / 32768f * 2000f
        val wz = readInt16(data, offset + 6) / 32768f * 2000f
        return floatArrayOf(wx, wy, wz)
    }

    private fun parseEuler(data: ByteArray, offset: Int): FloatArray {
        // Scale: raw / 32768 * 180 °
        val roll = readInt16(data, offset + 2) / 32768f * 180f
        val pitch = readInt16(data, offset + 4) / 32768f * 180f
        val yaw = readInt16(data, offset + 6) / 32768f * 180f
        return floatArrayOf(roll, pitch, yaw)
    }

    private fun parseQuaternion(data: ByteArray, offset: Int): FloatArray {
        val q0 = readInt16(data, offset + 2) / 32768f
        val q1 = readInt16(data, offset + 4) / 32768f
        val q2 = readInt16(data, offset + 6) / 32768f
        val q3 = readInt16(data, offset + 8) / 32768f
        return floatArrayOf(q0, q1, q2, q3)
    }

    private fun parseMagnetometer(data: ByteArray, offset: Int): FloatArray {
        val mx = readInt16(data, offset + 2).toFloat()
        val my = readInt16(data, offset + 4).toFloat()
        val mz = readInt16(data, offset + 6).toFloat()
        return floatArrayOf(mx, my, mz)
    }

    // --- Utilities ---

    /**
     * Read a signed 16-bit little-endian integer from the byte array.
     */
    fun readInt16(data: ByteArray, offset: Int): Short {
        val low = data[offset].toInt() and 0xFF
        val high = data[offset + 1].toInt() and 0xFF
        return ((high shl 8) or low).toShort()
    }

    /**
     * Verify the checksum of a packet at the given offset.
     * Checksum = sum of bytes [0..9] modulo 256.
     */
    fun verifyChecksum(data: ByteArray, offset: Int): Boolean {
        if (offset + PACKET_SIZE > data.size) return false
        var sum = 0
        for (i in 0 until PACKET_SIZE - 1) {
            sum += data[offset + i].toInt() and 0xFF
        }
        return (sum and 0xFF) == (data[offset + PACKET_SIZE - 1].toInt() and 0xFF)
    }
}
