package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImuDataParserTest {

    /**
     * Build a valid 11-byte packet with correct checksum.
     */
    private fun buildPacket(type: Byte, d0: Short, d1: Short, d2: Short, d3: Short = 0): ByteArray {
        val packet = ByteArray(11)
        packet[0] = 0x55
        packet[1] = type
        packet[2] = (d0.toInt() and 0xFF).toByte()
        packet[3] = (d0.toInt() shr 8 and 0xFF).toByte()
        packet[4] = (d1.toInt() and 0xFF).toByte()
        packet[5] = (d1.toInt() shr 8 and 0xFF).toByte()
        packet[6] = (d2.toInt() and 0xFF).toByte()
        packet[7] = (d2.toInt() shr 8 and 0xFF).toByte()
        packet[8] = (d3.toInt() and 0xFF).toByte()
        packet[9] = (d3.toInt() shr 8 and 0xFF).toByte()
        // Checksum = sum of bytes 0-9 mod 256
        var sum = 0
        for (i in 0 until 10) sum += packet[i].toInt() and 0xFF
        packet[10] = (sum and 0xFF).toByte()
        return packet
    }

    @Test
    fun `parse empty data returns empty list`() {
        assertThat(ImuDataParser.parse(byteArrayOf())).isEmpty()
    }

    @Test
    fun `parse single acceleration packet`() {
        // ax = 0 (raw 0), ay = 0, az = 32768/32768*16 = 16g (raw 32767 ≈ 16g)
        val packet = buildPacket(ImuDataParser.TYPE_ACCELERATION, 0, 0, 16384) // 16384/32768*16 = 8g
        val results = ImuDataParser.parse(packet)

        assertThat(results).hasSize(1)
        assertThat(results[0].type).isEqualTo(ImuDataParser.TYPE_ACCELERATION)
        assertThat(results[0].values[0]).isWithin(0.01f).of(0f)    // ax
        assertThat(results[0].values[1]).isWithin(0.01f).of(0f)    // ay
        assertThat(results[0].values[2]).isWithin(0.01f).of(8f)    // az = 8g
    }

    @Test
    fun `parse euler packet converts to degrees`() {
        // Roll = 16384 raw → 16384/32768*180 = 90°
        val packet = buildPacket(ImuDataParser.TYPE_EULER, 16384, 0, (-16384).toShort())
        val results = ImuDataParser.parse(packet)

        assertThat(results).hasSize(1)
        assertThat(results[0].values[0]).isWithin(0.01f).of(90f)   // roll
        assertThat(results[0].values[1]).isWithin(0.01f).of(0f)    // pitch
        assertThat(results[0].values[2]).isWithin(0.01f).of(-90f)  // yaw
    }

    @Test
    fun `parse gyroscope packet converts to degrees per second`() {
        // wx = 3277 raw → 3277/32768*2000 ≈ 200 °/s
        val packet = buildPacket(ImuDataParser.TYPE_GYROSCOPE, 3277, 0, 0)
        val results = ImuDataParser.parse(packet)

        assertThat(results).hasSize(1)
        assertThat(results[0].values[0]).isWithin(1f).of(200f)
    }

    @Test
    fun `parse quaternion packet normalizes`() {
        // q0 = 32767 raw → 32767/32768 ≈ 1.0
        val packet = buildPacket(ImuDataParser.TYPE_QUATERNION, 32767, 0, 0, 0)
        val results = ImuDataParser.parse(packet)

        assertThat(results).hasSize(1)
        assertThat(results[0].values[0]).isWithin(0.001f).of(1f)
        assertThat(results[0].values[1]).isWithin(0.001f).of(0f)
    }

    @Test
    fun `parse multiple concatenated packets`() {
        val accel = buildPacket(ImuDataParser.TYPE_ACCELERATION, 0, 0, 16384)
        val euler = buildPacket(ImuDataParser.TYPE_EULER, 16384, 0, 0)
        val combined = accel + euler

        val results = ImuDataParser.parse(combined)
        assertThat(results).hasSize(2)
        assertThat(results[0].type).isEqualTo(ImuDataParser.TYPE_ACCELERATION)
        assertThat(results[1].type).isEqualTo(ImuDataParser.TYPE_EULER)
    }

    @Test
    fun `invalid checksum skips packet`() {
        val packet = buildPacket(ImuDataParser.TYPE_ACCELERATION, 0, 0, 0)
        packet[10] = 0x00 // corrupt checksum
        val results = ImuDataParser.parse(packet)

        assertThat(results).isEmpty()
    }

    @Test
    fun `garbage before valid packet is skipped`() {
        val garbage = byteArrayOf(0x00, 0xFF.toByte(), 0x42)
        val valid = buildPacket(ImuDataParser.TYPE_EULER, 0, 0, 0)
        val data = garbage + valid

        val results = ImuDataParser.parse(data)
        assertThat(results).hasSize(1)
        assertThat(results[0].type).isEqualTo(ImuDataParser.TYPE_EULER)
    }

    @Test
    fun `packetsToSample merges packets correctly`() {
        val packets = listOf(
            ImuDataParser.ParsedPacket(ImuDataParser.TYPE_ACCELERATION, floatArrayOf(0f, 0f, 1f)),
            ImuDataParser.ParsedPacket(ImuDataParser.TYPE_EULER, floatArrayOf(5f, 2f, -1f)),
            ImuDataParser.ParsedPacket(ImuDataParser.TYPE_GYROSCOPE, floatArrayOf(0.1f, 0f, 0f))
        )

        val sample = ImuDataParser.packetsToSample(packets, 1234L)

        assertThat(sample.timestamp).isEqualTo(1234L)
        assertThat(sample.acceleration).isNotNull()
        assertThat(sample.acceleration!![2]).isEqualTo(1f)
        assertThat(sample.euler).isNotNull()
        assertThat(sample.euler!![0]).isEqualTo(5f)
        assertThat(sample.gyroscope).isNotNull()
        assertThat(sample.quaternion).isNull()
    }

    @Test
    fun `readInt16 handles negative values`() {
        // -1 in little-endian = [0xFF, 0xFF]
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertThat(ImuDataParser.readInt16(data, 0)).isEqualTo(-1)
    }

    @Test
    fun `readInt16 handles positive values`() {
        // 256 in little-endian = [0x00, 0x01]
        val data = byteArrayOf(0x00, 0x01)
        assertThat(ImuDataParser.readInt16(data, 0)).isEqualTo(256)
    }

    @Test
    fun `verifyChecksum returns true for valid packet`() {
        val packet = buildPacket(ImuDataParser.TYPE_ACCELERATION, 100, 200, 300)
        assertThat(ImuDataParser.verifyChecksum(packet, 0)).isTrue()
    }

    @Test
    fun `verifyChecksum returns false for truncated data`() {
        assertThat(ImuDataParser.verifyChecksum(byteArrayOf(0x55), 0)).isFalse()
    }

    private fun buildCombinedPacket(vararg raw: Short): ByteArray {
        val packet = ByteArray(20)
        packet[0] = 0x55
        packet[1] = 0x61
        raw.forEachIndexed { i, v ->
            packet[2 + i * 2] = (v.toInt() and 0xFF).toByte()
            packet[3 + i * 2] = (v.toInt() shr 8 and 0xFF).toByte()
        }
        return packet
    }

    @Test
    fun `parse BLE 5 combined packet into acceleration gyroscope and euler`() {
        // az = 8g, wz = 200 deg/s, roll = 90 deg, yaw = -90 deg
        val packet = buildCombinedPacket(0, 0, 16384, 0, 0, 3277, 16384, 0, (-16384).toShort())

        val sample = ImuDataParser.packetsToSample(ImuDataParser.parse(packet), 42L)

        assertThat(sample.acceleration!![2]).isWithin(0.01f).of(8f)
        assertThat(sample.gyroscope!![2]).isWithin(0.1f).of(200f)
        assertThat(sample.euler!![0]).isWithin(0.01f).of(90f)
        assertThat(sample.euler[2]).isWithin(0.01f).of(-90f)
        assertThat(sample.quaternion).isNull()
    }

    @Test
    fun `truncated combined packet is skipped`() {
        val packet = buildCombinedPacket(0, 0, 16384).copyOf(15)
        assertThat(ImuDataParser.parse(packet)).isEmpty()
    }

    @Test
    fun `counts a BLE 5 combined frame`() {
        val (_, counts) = ImuDataParser.parseCounting(buildCombinedPacket(0, 0, 16384))
        assertThat(counts).isEqualTo(ImuDataParser.FrameCounts(combined = 1, legacy = 0, skippedBytes = 0))
    }

    @Test
    fun `counts legacy frames, leading garbage and a frame cut by the notification end`() {
        val accel = buildPacket(ImuDataParser.TYPE_ACCELERATION, 0, 0, 0)
        val euler = buildPacket(ImuDataParser.TYPE_EULER, 0, 0, 0)
        val data = byteArrayOf(1, 2, 3) + accel + euler + euler.copyOf(5)

        val (packets, counts) = ImuDataParser.parseCounting(data)

        assertThat(packets).hasSize(2)
        assertThat(counts).isEqualTo(ImuDataParser.FrameCounts(combined = 0, legacy = 2, skippedBytes = 3 + 5))
    }

    @Test
    fun `a frame with a bad checksum counts as skipped bytes`() {
        val bad = buildPacket(ImuDataParser.TYPE_EULER, 1, 2, 3).also { it[10] = (it[10] + 1).toByte() }

        val (packets, counts) = ImuDataParser.parseCounting(bad)

        assertThat(packets).isEmpty()
        assertThat(counts).isEqualTo(ImuDataParser.FrameCounts(combined = 0, legacy = 0, skippedBytes = 11))
    }
}
