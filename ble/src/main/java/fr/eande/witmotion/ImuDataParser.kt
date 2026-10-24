package fr.eande.witmotion

import kotlin.math.abs

/**
 * Parses WitMotion WT9011DCL BLE packet data.
 *
 * Two frame formats, both little-endian int16 fields:
 *   - BLE 5.0 default (20 bytes, no checksum):
 *     [0x55] [0x61] ax ay az wx wy wz roll pitch yaw
 *   - Legacy serial (11 bytes):
 *     [0x55] [Type] [D0L] [D0H] [D1L] [D1H] [D2L] [D2H] [D3L] [D3H] [Checksum]
 *
 * Multiple packets may be concatenated in a single BLE notification: from 50 Hz on the
 * sensor packs several 55 61 frames into one (2 at 50 Hz, 4 at 100 Hz).
 * This parser extracts all valid packets and returns typed ImuSample fields.
 */
internal object ImuDataParser {

    const val PACKET_SIZE = 11
    const val COMBINED_PACKET_SIZE = 20
    const val HEADER = 0x55.toByte()
    const val TYPE_COMBINED = 0x61.toByte()
    /** 55 71 reply to a register read (FF AA 27 reg 00): start register + 8 register values, no checksum. */
    const val TYPE_REGISTERS = 0x71.toByte()
    const val REG_BATTERY_VOLTAGE = 0x64
    /** Packet type for the counter of a timestamp frame; not a wire value. */
    const val TYPE_SENSOR_TIME = 0x96.toByte()
    /** Timestamp frame (register 0x96 = 2): `55 61` + acc + gyro + counter, 18 bytes in the protocol table. */
    const val TIMESTAMP_FRAME_SIZE = 18

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
        val values: FloatArray,
        /** The sensor's millisecond counter, for a [TYPE_SENSOR_TIME] packet only. */
        val sensorMs: Long? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedPacket) return false
            return type == other.type && values.contentEquals(other.values) && sensorMs == other.sensorMs
        }
        override fun hashCode(): Int = 31 * type.hashCode() + values.contentHashCode() + sensorMs.hashCode()
    }

    /**
     * Parse all valid packets from a BLE notification byte array.
     * Returns a list of typed parsed packets.
     */
    fun parse(data: ByteArray): List<ParsedPacket> = parseCounting(data).packets

    /**
     * Frames found in one notification, for link diagnostics. [timestampFrameSize] is the stride
     * (18 or 20) of the timestamp frames found, 0 when there were none.
     */
    data class FrameCounts(val combined: Int, val legacy: Int, val skippedBytes: Int, val timestampFrameSize: Int = 0)

    /** A register-read reply: [values] are the 8 unsigned 16-bit registers from [startRegister] on. */
    data class RegisterReply(val startRegister: Int, val values: List<Int>)

    data class Parsed(
        val packets: List<ParsedPacket>,
        val counts: FrameCounts,
        val registers: List<RegisterReply> = emptyList()
    )

    /**
     * Like [parse], and counts the frames per format and the bytes that were not part of a
     * valid frame (garbage, bad checksum, or a frame cut by the end of the notification).
     *
     * A timestamp frame has the same header as the default one, so it is only recognised when
     * [timestamps] says the sensor was switched to it. Its length is not documented (18 bytes in
     * the table, maybe padded to 20): the stride is 18 when the next header follows at 18 or the
     * notification ends there, else 20.
     */
    fun parseCounting(data: ByteArray, timestamps: Boolean = false): Parsed {
        val packets = mutableListOf<ParsedPacket>()
        val registers = mutableListOf<RegisterReply>()
        var offset = 0
        var combined = 0
        var legacy = 0
        var skipped = 0
        var timestampStride = 0

        while (offset + PACKET_SIZE <= data.size) {
            if (data[offset] != HEADER) {
                offset++
                skipped++
                continue
            }

            if (timestamps && data[offset + 1] == TYPE_COMBINED) {
                val stride = timestampStride(data, offset)
                if (stride > 0) {
                    packets.add(ParsedPacket(TYPE_ACCELERATION, parseAcceleration(data, offset)))
                    packets.add(ParsedPacket(TYPE_GYROSCOPE, parseGyroscope(data, offset + 6)))
                    packets.add(ParsedPacket(TYPE_SENSOR_TIME, FloatArray(0), readUInt32(data, offset + 14)))
                    offset += stride
                    timestampStride = stride
                    combined++
                    continue
                }
            }

            if (!timestamps && data[offset + 1] == TYPE_COMBINED && offset + COMBINED_PACKET_SIZE <= data.size) {
                packets.add(ParsedPacket(TYPE_ACCELERATION, parseAcceleration(data, offset)))
                packets.add(ParsedPacket(TYPE_GYROSCOPE, parseGyroscope(data, offset + 6)))
                packets.add(ParsedPacket(TYPE_EULER, parseEuler(data, offset + 12)))
                offset += COMBINED_PACKET_SIZE
                combined++
                continue
            }

            if (data[offset + 1] == TYPE_REGISTERS && offset + COMBINED_PACKET_SIZE <= data.size) {
                registers.add(RegisterReply(readUInt16(data, offset + 2), List(8) { readUInt16(data, offset + 4 + it * 2) }))
                offset += COMBINED_PACKET_SIZE
                continue
            }

            if (!verifyChecksum(data, offset)) {
                offset++
                skipped++
                continue
            }
            legacy++

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

        skipped += data.size - offset
        return Parsed(packets, FrameCounts(combined, legacy, skipped, timestampStride), registers)
    }

    private fun timestampStride(data: ByteArray, offset: Int): Int {
        val rest = data.size - offset
        val headerAt18 = rest > TIMESTAMP_FRAME_SIZE + 1 && data[offset + 18] == HEADER &&
            (data[offset + 19] == TYPE_COMBINED || data[offset + 19] == TYPE_REGISTERS)
        return when {
            rest == TIMESTAMP_FRAME_SIZE || headerAt18 -> TIMESTAMP_FRAME_SIZE
            rest >= COMBINED_PACKET_SIZE -> COMBINED_PACKET_SIZE
            else -> 0
        }
    }

    /**
     * Battery percentage from register 0x64 (voltage in 0.01 V), per the voltage table of the
     * WT9011DCL-BT50 communication protocol (v25-04-06, section 3.10).
     */
    fun batteryPercent(centivolts: Int): Int = when {
        centivolts > 396 -> 100
        centivolts >= 393 -> 90
        centivolts >= 387 -> 75
        centivolts >= 382 -> 60
        centivolts >= 379 -> 50
        centivolts >= 377 -> 40
        centivolts >= 373 -> 30
        centivolts >= 370 -> 20
        centivolts >= 368 -> 15
        centivolts >= 350 -> 10
        centivolts >= 340 -> 5
        else -> 0
    }

    /**
     * Splits the packets of one notification into samples: a packet type seen again starts a
     * new sample, so each packed 55 61 frame is its own sample while legacy packets (acceleration,
     * gyro and angle sent separately) still make one.
     *
     * Timestamps follow a regular clock: each sample is [previous] plus [periodMs], pulled an
     * eighth of the way toward the time its arrival implies (the last sample at [receivedAt],
     * each earlier one a period before), so arrival jitter does not show while the clock still
     * tracks the sensor's actual rate. Samples are at least half a period apart. After a link gap
     * (the clock more than [GAP_MS] behind) the clock restarts at the arrival times; a clock more
     * than [GAP_MS] ahead (a sensor faster than [periodMs] says) is held to one millisecond a
     * sample until the arrival times catch up, as timestamps never go back. Without a
     * [previous] sample, the arrival times are used as they are.
     */
    fun packetsToSamples(
        packets: List<ParsedPacket>,
        receivedAt: Long,
        periodMs: Long,
        previous: Long = Long.MIN_VALUE
    ): List<ImuSample> {
        val groups = mutableListOf<MutableList<ParsedPacket>>()
        for (p in packets) {
            val current = groups.lastOrNull()
            if (current == null || current.any { it.type == p.type }) groups.add(mutableListOf(p)) else current.add(p)
        }
        var last = previous
        return groups.mapIndexed { i, group ->
            val arrival = receivedAt - (groups.size - 1 - i) * periodMs
            val t = if (last == Long.MIN_VALUE || abs(arrival - (last + periodMs)) > GAP_MS) {
                maxOf(arrival, if (last == Long.MIN_VALUE) Long.MIN_VALUE else last + 1)
            } else {
                val regular = last + periodMs
                maxOf(regular + (arrival - regular) / CLOCK_PULL, last + maxOf(1, periodMs / 2))
            }
            last = t
            packetsToSample(group, t)
        }
    }

    /** Fraction of the gap to the arrival time each sample closes. */
    private const val CLOCK_PULL = 8

    /** Behind the arrival by more than this, the clock restarts: the link dropped. Ahead by more, it waits for the arrival. */
    private const val GAP_MS = 250L

    /**
     * Merge parsed packets into an ImuSample with the given timestamp.
     */
    fun packetsToSample(packets: List<ParsedPacket>, timestamp: Long): ImuSample {
        var accel: FloatArray? = null
        var gyro: FloatArray? = null
        var euler: FloatArray? = null
        var quat: FloatArray? = null
        var sensorMs: Long? = null

        for (p in packets) {
            when (p.type) {
                TYPE_ACCELERATION -> accel = p.values
                TYPE_GYROSCOPE -> gyro = p.values
                TYPE_EULER -> euler = p.values
                TYPE_QUATERNION -> quat = p.values
                TYPE_SENSOR_TIME -> sensorMs = p.sensorMs
            }
        }

        return ImuSample(
            timestamp = timestamp,
            acceleration = accel,
            gyroscope = gyro,
            euler = euler,
            quaternion = quat,
            sensorTimeMs = sensorMs
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
    fun readUInt16(data: ByteArray, offset: Int): Int = readInt16(data, offset).toInt() and 0xFFFF

    fun readUInt32(data: ByteArray, offset: Int): Long =
        readUInt16(data, offset).toLong() or (readUInt16(data, offset + 2).toLong() shl 16)

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
