package fr.eande.witmotion

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The sensor's clock measured against the phone's: [sensorRateHz] from the counter alone,
 * [driftPpm] the counter's slope against the phone's clock (positive: the sensor counts faster;
 * arrival jitter limits its resolution to a few hundred ppm over 30 s), [gaps] the steps over
 * 1.5 times the median step (lost frames) and [maxGapMs] the largest of them, 0 without any.
 */
data class ClockCheckResult(
    val samples: Int,
    val sensorRateHz: Double,
    val driftPpm: Double,
    val gaps: Int,
    val maxGapMs: Long
)

/** Collects the sensor's millisecond counters with their arrival times while a check runs. */
internal class ClockCollector {
    private val sensorMs = ArrayList<Long>()
    private val receivedMs = ArrayList<Long>()
    private var open = false

    @Synchronized fun open() { open = true }

    @Synchronized fun close() { open = false }

    /** Counters of one notification, all received at [receivedAt] (ms on the phone's monotonic clock). */
    @Synchronized fun add(counters: List<Long>, receivedAt: Long) {
        if (!open) return
        counters.forEach { sensorMs += it; receivedMs += receivedAt }
    }

    @Synchronized fun result(): ClockCheckResult {
        val n = sensorMs.size
        if (n < 2) return ClockCheckResult(n, 0.0, 0.0, 0, 0)
        val elapsed = DoubleArray(n)
        val steps = ArrayList<Long>(n - 1)
        for (i in 1 until n) {
            val step = (sensorMs[i] - sensorMs[i - 1]) and COUNTER_MASK
            // a step of half the range or more is a counter reset: assume the previous step
            val valid = if (step >= COUNTER_MASK / 2) steps.lastOrNull() ?: 0L else step
            steps += valid
            elapsed[i] = elapsed[i - 1] + valid
        }
        val period = steps.sorted()[steps.size / 2]
        val gapSteps = steps.filter { it > period * GAP_FACTOR }
        return ClockCheckResult(
            samples = n,
            sensorRateHz = if (elapsed[n - 1] > 0) (n - 1) * 1000.0 / elapsed[n - 1] else 0.0,
            driftPpm = (slope(receivedMs.map { it.toDouble() }, elapsed.toList()) - 1) * 1e6,
            gaps = gapSteps.size,
            maxGapMs = gapSteps.maxOrNull() ?: 0L
        )
    }

    /** Least-squares slope of [y] against [x]; 1 when [x] does not move. */
    private fun slope(x: List<Double>, y: List<Double>): Double {
        val mx = x.average()
        val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return 1.0
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sxx
    }

    private companion object {
        const val COUNTER_MASK = 0xFFFF_FFFFL
        const val GAP_FACTOR = 1.5
    }
}

internal object ClockCheck {
    /** Time for the sensor to apply the switch to timestamps before the first counter is kept. */
    const val SWITCH_SETTLE_MS = 500L
    /** Time for the sensor to apply the switch back before the parser leaves the timestamp layout. */
    const val RESTORE_SETTLE_MS = 500L

    /**
     * Switches the output to timestamps through [setContent], collects for [durationMs], and
     * always switches back to the angles, whatever ends the check (an error, a cancellation).
     */
    suspend fun run(
        durationMs: Long,
        collector: ClockCollector,
        setContent: (OutputContent) -> Unit,
        wait: suspend (Long) -> Unit = { delay(it) }
    ): ClockCheckResult {
        try {
            setContent(OutputContent.TIMESTAMP)
            wait(SWITCH_SETTLE_MS)
            collector.open()
            wait(durationMs)
            collector.close()
            return collector.result()
        } finally {
            collector.close()
            setContent(OutputContent.ANGLES)
            withContext(NonCancellable) { wait(RESTORE_SETTLE_MS) }
        }
    }
}
