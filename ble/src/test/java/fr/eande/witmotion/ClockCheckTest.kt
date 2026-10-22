package fr.eande.witmotion

import com.google.common.truth.Truth.assertThat
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
    fun `a counter running 0_75 percent slow shows as rate and drift`() {
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
    fun `the check switches to timestamps, collects, and restores the angles`() = runTest {
        val sent = mutableListOf<OutputContent>()
        val c = ClockCollector()

        val result = ClockCheck.run(30_000, c, { sent += it }) { ms ->
            if (ms == 30_000L) (0..9).forEach { c.add(listOf(it * 10L), it * 10L) }
        }

        assertThat(sent).containsExactly(OutputContent.TIMESTAMP, OutputContent.ANGLES).inOrder()
        assertThat(result.samples).isEqualTo(10)
        assertThat(result.sensorRateHz).isWithin(0.01).of(100.0)
    }

    @Test
    fun `the angles are restored when the check fails`() = runTest {
        val sent = mutableListOf<OutputContent>()

        val failure = runCatching {
            ClockCheck.run(30_000, ClockCollector(), { sent += it }) { ms ->
                if (ms == 30_000L) error("link lost")
            }
        }.exceptionOrNull()

        assertThat(failure).hasMessageThat().isEqualTo("link lost")
        assertThat(sent).containsExactly(OutputContent.TIMESTAMP, OutputContent.ANGLES).inOrder()
    }

    @Test
    fun `the angles are restored when the check is cancelled`() = runTest {
        val sent = mutableListOf<OutputContent>()

        val failure = runCatching {
            ClockCheck.run(30_000, ClockCollector(), { sent += it }) { ms ->
                if (ms == 30_000L) throw kotlinx.coroutines.CancellationException("stopped")
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(sent.last()).isEqualTo(OutputContent.ANGLES)
    }
}
