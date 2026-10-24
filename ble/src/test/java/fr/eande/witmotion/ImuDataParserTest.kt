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
    fun `a notification packing four 55 61 frames gives four samples, one period apart`() {
        // 100 Hz: the sensor packs 4 frames per notification; roll tells them apart
        val notification = (1..4).map { buildCombinedPacket(0, 0, 0, 0, 0, 0, (it * 1000).toShort()) }
            .reduce(ByteArray::plus)

        val samples = ImuDataParser.packetsToSamples(ImuDataParser.parse(notification), receivedAt = 1_000, periodMs = 10)

        assertThat(samples.map { it.timestamp }).containsExactly(970L, 980L, 990L, 1_000L).inOrder()
        assertThat(samples.map { it.euler!![0] }).isInStrictOrder()
        assertThat(samples.all { it.acceleration != null && it.gyroscope != null }).isTrue()
    }

    @Test
    fun `packed frames always come after the previous sample`() {
        val notification = buildCombinedPacket(0) + buildCombinedPacket(0)

        val samples = ImuDataParser.packetsToSamples(ImuDataParser.parse(notification), receivedAt = 1_000, periodMs = 20, previous = 990)

        assertThat(samples.map { it.timestamp }).isInStrictOrder()
        assertThat(samples.first().timestamp).isGreaterThan(990L)
    }

    /** 200 Hz: 8 frames per notification every 40 ms, arriving up to 15 ms early or late. */
    private fun jitteryStream(seconds: Int, gapAtMs: Long? = null, assumedPeriodMs: Long = 5): List<Pair<Long, List<ImuSample>>> {
        val frames = (1..8).map { buildCombinedPacket(0) }.reduce(ByteArray::plus)
        val jitter = listOf(0L, 15L, -12L, 4L, -15L, 9L, -3L, 12L)
        var previous = Long.MIN_VALUE
        val out = mutableListOf<Pair<Long, List<ImuSample>>>()
        var k = 0
        var nominal = 40L
        while (nominal <= seconds * 1_000L) {
            if (gapAtMs != null && nominal in gapAtMs until gapAtMs + 1_000) { nominal += 40; continue }
            val arrival = nominal + jitter[k++ % jitter.size]
            val samples = ImuDataParser.packetsToSamples(ImuDataParser.parse(frames), receivedAt = arrival, periodMs = assumedPeriodMs, previous = previous)
            previous = samples.last().timestamp
            out += arrival to samples
            nominal += 40
        }
        return out
    }

    @Test
    fun `a jittery 200 Hz stream gets regular increasing timestamps`() {
        val stream = jitteryStream(seconds = 10)
        val times = stream.flatMap { (_, samples) -> samples.map { it.timestamp } }

        val steps = times.zipWithNext { a, b -> b - a }
        assertThat(steps.min()).isAtLeast(3L)
        assertThat(steps.max()).isAtMost(8L)
        val rate = (times.size - 1) * 1000.0 / (times.last() - times.first())
        assertThat(rate).isWithin(2.0).of(200.0)
        stream.forEach { (arrival, samples) -> assertThat(samples.last().timestamp - arrival).isIn(-30L..30L) }
    }

    @Test
    fun `a sensor faster than the assumed rate does not run the clock ahead of real time`() {
        // 200 Hz real, 50 Hz assumed (the rate write was lost)
        val stream = jitteryStream(seconds = 10, assumedPeriodMs = 20)

        stream.forEach { (arrival, samples) -> assertThat(samples.last().timestamp - arrival).isAtMost(250L) }
        assertThat(stream.flatMap { (_, samples) -> samples.map { it.timestamp } }).isInStrictOrder()
    }

    @Test
    fun `the clock jumps after a link gap instead of drifting`() {
        val stream = jitteryStream(seconds = 4, gapAtMs = 2_000)
        val after = stream.first { (arrival, _) -> arrival > 3_000 }

        assertThat(after.second.last().timestamp - after.first).isIn(-30L..30L)
    }

    @Test
    fun `legacy packets of one notification still make one sample`() {
        val notification = buildPacket(ImuDataParser.TYPE_ACCELERATION, 0, 0, 2048) +
            buildPacket(ImuDataParser.TYPE_GYROSCOPE, 0, 0, 0) +
            buildPacket(ImuDataParser.TYPE_EULER, 0, 0, 0)

        val samples = ImuDataParser.packetsToSamples(ImuDataParser.parse(notification), receivedAt = 500, periodMs = 20)

        assertThat(samples).hasSize(1)
        assertThat(samples[0].timestamp).isEqualTo(500L)
        assertThat(samples[0].acceleration).isNotNull()
        assertThat(samples[0].euler).isNotNull()
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
    fun `a 55 61 inside a payload after a lost byte is not taken for a frame`() {
        // ax = 0x6155 puts 55 61 in the payload; the first header byte of frame A is lost
        val lost = buildCombinedPacket(0x6155, 0, 0, 0, 0, 0, 0, 0, 0).copyOfRange(1, 20)
        val real = buildCombinedPacket(0, 0, 16384, 0, 0, 0, 0, 0, 0)

        val (packets, counts) = ImuDataParser.parseCounting(lost + real)

        assertThat(counts.combined).isEqualTo(1)
        assertThat(packets.first { it.type == ImuDataParser.TYPE_ACCELERATION }.values[2]).isWithin(0.01f).of(8f)
    }

    @Test
    fun `a combined frame after a stray byte is still taken when it ends the notification`() {
        val real = buildCombinedPacket(0, 0, 16384, 0, 0, 0, 0, 0, 0)

        val (_, counts) = ImuDataParser.parseCounting(byteArrayOf(7) + real)

        assertThat(counts).isEqualTo(ImuDataParser.FrameCounts(combined = 1, legacy = 0, skippedBytes = 1))
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

    @Test
    fun `decodes a 55 71 register reply (protocol example, magnetic field)`() {
        // Example from the WT9011DCL-BT50 protocol section 2.8: x = 0x0168 = 360 -> 2.4 uT
        val reply = byteArrayOf(0x55, 0x71, 0x3A, 0x00, 0x68, 0x01, 0x69, 0x00, 0x7A, 0x00) + ByteArray(10)

        val parsed = ImuDataParser.parseCounting(reply)

        assertThat(parsed.packets).isEmpty()
        assertThat(parsed.registers).containsExactly(
            ImuDataParser.RegisterReply(0x3A, listOf(360, 105, 122, 0, 0, 0, 0, 0))
        )
        assertThat(parsed.counts.skippedBytes).isEqualTo(0)
    }

    @Test
    fun `battery percentage follows the protocol voltage table`() {
        val cases = mapOf(
            420 to 100, 397 to 100, 396 to 90, 393 to 90, 392 to 75, 387 to 75, 383 to 60,
            380 to 50, 378 to 40, 375 to 30, 371 to 20, 369 to 15, 360 to 10, 345 to 5, 339 to 0
        )
        cases.forEach { (centivolts, percent) ->
            assertThat(ImuDataParser.batteryPercent(centivolts)).isEqualTo(percent)
        }
    }
}

class ImuDataParserTimestampTest {

    /** [padding] 0 gives the 18-byte layout of the protocol table, 2 the 20-byte one. */
    private fun timestampFrame(ms: Long, padding: Int, ax: Short = 0): ByteArray {
        val frame = ByteArray(18 + padding)
        frame[0] = 0x55
        frame[1] = 0x61
        frame[2] = (ax.toInt() and 0xFF).toByte()
        frame[3] = (ax.toInt() shr 8 and 0xFF).toByte()
        for (i in 0..3) frame[14 + i] = (ms shr (8 * i) and 0xFF).toByte()
        return frame
    }

    private fun sensorTimes(notification: ByteArray) =
        ImuDataParser.parseCounting(notification, timestamps = true).packets.mapNotNull { it.sensorMs }

    @Test
    fun `four 18-byte timestamp frames in one notification`() {
        val notification = (0L..3L).map { timestampFrame(1_000 + it * 5, 0) }.reduce(ByteArray::plus)
        val parsed = ImuDataParser.parseCounting(notification, timestamps = true)

        assertThat(parsed.packets.mapNotNull { it.sensorMs }).containsExactly(1000L, 1005L, 1010L, 1015L).inOrder()
        assertThat(parsed.counts.combined).isEqualTo(4)
        assertThat(parsed.counts.timestampFrameSize).isEqualTo(18)
        assertThat(parsed.counts.skippedBytes).isEqualTo(0)
    }

    @Test
    fun `four 20-byte timestamp frames in one notification`() {
        val notification = (0L..3L).map { timestampFrame(1_000 + it * 5, 2) }.reduce(ByteArray::plus)
        val parsed = ImuDataParser.parseCounting(notification, timestamps = true)

        assertThat(parsed.packets.mapNotNull { it.sensorMs }).containsExactly(1000L, 1005L, 1010L, 1015L).inOrder()
        assertThat(parsed.counts.timestampFrameSize).isEqualTo(20)
        assertThat(parsed.counts.skippedBytes).isEqualTo(0)
    }

    @Test
    fun `a single frame of either length`() {
        assertThat(sensorTimes(timestampFrame(42, 0))).containsExactly(42L)
        assertThat(sensorTimes(timestampFrame(42, 2))).containsExactly(42L)
    }

    @Test
    fun `the counter is unsigned above 2^31`() {
        assertThat(sensorTimes(timestampFrame(0xFFFF_FFF0L, 0))).containsExactly(0xFFFF_FFF0L)
    }

    @Test
    fun `acceleration is still decoded and each frame becomes a sample carrying its counter`() {
        val notification = timestampFrame(500, 0, ax = 2048) + timestampFrame(505, 0, ax = 2048)
        val samples = ImuDataParser.packetsToSamples(
            ImuDataParser.parseCounting(notification, timestamps = true).packets, receivedAt = 1_000, periodMs = 5
        )

        assertThat(samples.map { it.sensorTimeMs }).containsExactly(500L, 505L).inOrder()
        assertThat(samples[0].acceleration!![0]).isWithin(1e-4f).of(1f)
        assertThat(samples[0].euler).isNull()
    }

    @Test
    fun `without the timestamp mode the same header is a normal frame`() {
        val parsed = ImuDataParser.parseCounting(timestampFrame(500, 2))

        assertThat(parsed.packets.mapNotNull { it.sensorMs }).isEmpty()
        assertThat(parsed.packets.map { it.type }).contains(ImuDataParser.TYPE_EULER)
    }

    @Test
    fun `a register reply is still decoded in timestamp mode`() {
        val reply = byteArrayOf(0x55, 0x71, 0x1F, 0x00, 0x03, 0x00) + ByteArray(14)
        val parsed = ImuDataParser.parseCounting(timestampFrame(1, 0) + reply, timestamps = true)

        assertThat(parsed.registers.single().startRegister).isEqualTo(0x1F)
        assertThat(parsed.registers.single().values[0]).isEqualTo(3)
    }

    @Test
    fun `an angle frame arriving in timestamp mode does not crash the parser`() {
        val angleStream = ByteArray(20) { if (it == 0) 0x55 else if (it == 1) 0x61 else 0x11 }

        ImuDataParser.parseCounting(angleStream + angleStream, timestamps = true)
    }
}
