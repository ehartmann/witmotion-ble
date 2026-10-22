package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClockCheckTest {

    private fun collector(sensorMs: List<Long>, recvStepMs: Double = 5.0) = ClockCollector().also { c ->
        c.open()
        sensorMs.forEachIndexed { i, ms -> c.add(listOf(ms), (i * recvStepMs).toLong()) }
    }

    @Test
    fun `a steady 200 Hz counter gives 200 Hz, no drift and no gap`() {
        val r = collector(List(401) { it * 5L }).result()

        assertThat(r.samples).isEqualTo(401)
        assertThat(r.sensorRateHz).isWithin(0.01).of(200.0)
        assertThat(r.driftPpm).isWithin(1.0).of(0.0)
        assertThat(r.gaps).isEqualTo(0)
        assertThat(r.maxGapMs).isEqualTo(0)
    }

    @Test
    fun `a sensor counting 0_75 percent slower than the phone shows as rate and drift`() {
        // the phone sees one frame every 5 ms, the sensor counts 5.0377 ms per frame
        val r = collector(List(2001) { (it * 5.0377).toLong() }).result()

        assertThat(r.sensorRateHz).isWithin(0.1).of(198.5)
        assertThat(r.driftPpm).isWithin(100.0).of(7_500.0)
    }

    @Test
    fun `the 32-bit counter wraps without a gap`() {
        val start = 0xFFFF_FF00L
        val r = collector(List(100) { (start + it * 10L) and 0xFFFF_FFFFL }).result()

        assertThat(r.sensorRateHz).isWithin(0.01).of(100.0)
        assertThat(r.gaps).isEqualTo(0)
    }

    @Test
    fun `three lost frames are one gap of 20 ms`() {
        val times = (0..50).map { it * 5L }.filter { it !in listOf(100L, 105L, 110L) }
        val r = collector(times).result()

        assertThat(r.gaps).isEqualTo(1)
        assertThat(r.maxGapMs).isEqualTo(20)
    }

    @Test
    fun `fewer than two samples give an empty result`() {
        val r = collector(listOf(7L)).result()

        assertThat(r.samples).isEqualTo(1)
        assertThat(r.sensorRateHz).isEqualTo(0.0)
        assertThat(r.gaps).isEqualTo(0)
    }

    @Test
    fun `counters are ignored until the collector is opened and after it is closed`() {
        val c = ClockCollector()
        c.add(listOf(1L), 0)
        c.open()
        c.add(listOf(2L), 1)
        c.close()
        c.add(listOf(3L), 2)

        assertThat(c.result().samples).isEqualTo(1)
    }

    @Test
    fun `a single glitch between frames is rejected once and the rate holds`() {
        val c = ClockCollector().also { it.open() }
        (0..100).forEach { i ->
            c.add(listOf(i * 10L), i * 10L)
            if (i == 50) c.add(listOf(0x7A3B_9C11L), i * 10L)
        }

        val r = c.result()

        assertThat(r.rejected).isEqualTo(1)
        assertThat(r.samples).isEqualTo(101)
        assertThat(r.sensorRateHz).isWithin(0.01).of(100.0)
    }

    @Test
    fun `a counter restart is followed after two rejected steps`() {
        val c = ClockCollector().also { it.open() }
        (0..9).forEach { c.add(listOf(1_000_000L + it * 10L), it * 10L) }
        (0..9).forEach { c.add(listOf(it * 10L), 100L + it * 10L) } // the sensor rebooted

        val r = c.result()

        assertThat(r.rejected).isEqualTo(2)
        assertThat(r.samples).isEqualTo(18)
    }

    @Test
    fun `mostly equal counters do not make every step a gap`() {
        val r = collector(listOf(0L, 0L, 0L, 0L, 1L, 2L)).result()

        assertThat(r.gaps).isEqualTo(0)
    }

    @Test
    fun `the check switches to timestamps, collects, and restores the angles`() = runTest {
        val sent = mutableListOf<OutputContent>()
        val c = ClockCollector()

        val result = ClockCheck.run(30_000, c, { sent += it }) { _, ms ->
            if (ms == 30_000L) (0..9).forEach { c.add(listOf(it * 10L), it * 10L) }
        }

        assertThat(sent).containsExactly(OutputContent.TIMESTAMP, OutputContent.ANGLES).inOrder()
        assertThat(result.samples).isEqualTo(10)
        assertThat(result.sensorRateHz).isWithin(0.01).of(100.0)
        assertThat(result.aborted).isFalse()
    }

    @Test
    fun `the angles are restored when the check fails`() = runTest {
        val sent = mutableListOf<OutputContent>()

        val failure = runCatching {
            ClockCheck.run(30_000, ClockCollector(), { sent += it }) { _, ms ->
                if (ms == 30_000L) error("link lost")
            }
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("link lost")
        assertThat(sent).containsExactly(OutputContent.TIMESTAMP, OutputContent.ANGLES).inOrder()
    }

    @Test
    fun `cancelling a check suspended in its wait still restores the angles`() = runTest {
        val sent = mutableListOf<OutputContent>()
        val job = launch { ClockCheck.run(30_000, ClockCollector(), { sent += it }) }

        advanceTimeBy(5_000)
        assertThat(sent).containsExactly(OutputContent.TIMESTAMP)
        job.cancel()
        job.join()

        assertThat(sent).containsExactly(OutputContent.TIMESTAMP, OutputContent.ANGLES).inOrder()
        assertThat(currentTime).isLessThan(30_000)
    }

    @Test
    fun `the settle after a cancelled check still runs to its end`() = runTest {
        var settles = 0
        val job = launch {
            ClockCheck.run(30_000, ClockCollector(), { }) { _, ms ->
                delay(ms)
                if (ms == ClockCheck.RESTORE_SETTLE_MS) settles++
            }
        }

        advanceTimeBy(5_000)
        job.cancel()
        job.join()

        assertThat(settles).isEqualTo(2) // the switch settle, then the restore settle
    }

    @Test
    fun `a disconnect during the check ends it at once as aborted and clears the slot`() = runTest {
        val sent = mutableListOf<OutputContent>()
        val slot = ClockCheckSlot()
        val c = slot.begin()
        val result = async { ClockCheck.run(30_000, c, { sent += it }) }

        advanceTimeBy(5_000)
        c.add(listOf(0L), 0)
        slot.abort() // what disconnect() and the disconnected callback do

        val r = result.await()
        assertThat(r.aborted).isTrue()
        assertThat(currentTime).isLessThan(6_000)
        assertThat(slot.collector).isNull()
        // the link is gone: the angles are written by the next connect, not by a dropped write
        assertThat(sent).containsExactly(OutputContent.TIMESTAMP)
    }

    @Test
    fun `a connect during the check aborts it and the next frames are read as angles`() = runTest {
        val slot = ClockCheckSlot()
        val c = slot.begin()
        val result = async { ClockCheck.run(30_000, c, { }) }
        advanceTimeBy(2_000)

        slot.abort() // connect() does this first

        assertThat(result.await().aborted).isTrue()
        val angleFrame = byteArrayOf(0x55, 0x61) + ByteArray(18) { 0x10 }
        val packets = ImuDataParser.parseCounting(angleFrame, timestamps = slot.collector != null).packets
        assertThat(packets.map { it.type }).contains(ImuDataParser.TYPE_EULER)
        assertThat(packets.mapNotNull { it.sensorMs }).isEmpty()
    }

    @Test
    fun `a second check cannot start while one runs, and can after it ends`() {
        val slot = ClockCheckSlot()
        val first = slot.begin()

        assertThat(runCatching { slot.begin() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        slot.end(first)

        assertThat(slot.begin()).isNotNull()
    }

    @Test
    fun `every connect writes the angles first`() {
        val commands = ImuSensorManager.settingsCommands(OutputRate.HZ_100, Installation.HORIZONTAL, AXIS9, Bandwidth.HZ_42)
            .map { it.joinToString(" ") { b -> "%02X".format(b) } }

        assertThat(commands.first()).isEqualTo("FF AA 96 00 00")
        assertThat(commands).containsAtLeast("FF AA 03 09 00", "FF AA 1F 03 00", "FF AA 27 1F 00")
        assertThat(commands).hasSize(9)
    }

    private val AXIS9 = Algorithm.AXIS_9
}
