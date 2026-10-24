package fr.eande.witmotion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The sensor's clock measured against the phone's: [sensorRateHz] from the counter alone,
 * [driftPpm] the counter's slope against the phone's clock (positive: the sensor counts faster;
 * arrival jitter limits its resolution to a few hundred ppm over 30 s), [gaps] the number of
 * steps over 1.5 times the median step (events: one gap can span several lost frames) and
 * [maxGapMs] the largest of them, 0 without any. [rejected] counts the counters that were not a
 * plausible step after the previous one (angle bytes read as a counter, a reset) and left out
 * of the rest. [aborted] means the link dropped or reconnected during the check: the other
 * fields are partial.
 */
public data class ClockCheckResult(
    val samples: Int,
    val sensorRateHz: Double,
    val driftPpm: Double,
    val gaps: Int,
    val maxGapMs: Long,
    val rejected: Int = 0,
    val aborted: Boolean = false
)

/** Collects the sensor's millisecond counters with their arrival times while a check runs. */
internal class ClockCollector {
    private val receivedMs = ArrayList<Long>()
    private val elapsedMs = ArrayList<Long>()
    private val steps = ArrayList<Long>()
    private var last = -1L
    private var rejected = 0
    private var streak = 0
    private var open = false
    private val abortSignal = CompletableDeferred<Unit>()

    val isAborted: Boolean get() = abortSignal.isCompleted

    @Synchronized fun open() { if (!isAborted) open = true }

    @Synchronized fun close() { open = false }

    /** Ends the check at once: closes the collector and wakes [awaitAbort]. */
    @Synchronized fun abort() {
        open = false
        abortSignal.complete(Unit)
    }

    /** Waits [ms], or less when the check is aborted. */
    suspend fun awaitAbort(ms: Long) {
        withTimeoutOrNull(ms) { abortSignal.await() }
    }

    /** Counters of one notification, all received at [receivedAt] (ms on the phone's monotonic clock). */
    @Synchronized fun add(counters: List<Long>, receivedAt: Long) {
        if (!open) return
        counters.forEach { addCounter(it, receivedAt) }
    }

    private fun addCounter(ms: Long, receivedAt: Long) {
        var step = 0L
        if (last >= 0) {
            step = (ms - last) and COUNTER_MASK
            if (step > MAX_STEP_MS) {
                // three in a row are not a glitch: the counter restarted, so measure from here on
                if (++streak < RESYNC_AFTER) {
                    rejected++
                    return
                }
                step = steps.lastOrNull() ?: 0L
            }
            steps += step
        }
        streak = 0
        last = ms
        receivedMs += receivedAt
        elapsedMs += (elapsedMs.lastOrNull() ?: 0L) + step
    }

    @Synchronized fun result(): ClockCheckResult {
        val n = receivedMs.size
        if (n < 2) return ClockCheckResult(n, 0.0, 0.0, 0, 0, rejected, isAborted)
        val total = elapsedMs.last()
        val period = maxOf(steps.sorted()[steps.size / 2], 1L)
        val gapSteps = steps.filter { it > period * GAP_FACTOR }
        return ClockCheckResult(
            samples = n,
            sensorRateHz = if (total > 0) (n - 1) * 1000.0 / total else 0.0,
            driftPpm = (slope(receivedMs.map { it.toDouble() }, elapsedMs.map { it.toDouble() }) - 1) * 1e6,
            gaps = gapSteps.size,
            maxGapMs = gapSteps.maxOrNull() ?: 0L,
            rejected = rejected,
            aborted = isAborted
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
        /** A longer step is a link drop at best: no clock is that far behind between two frames. */
        const val MAX_STEP_MS = 5_000L
        const val RESYNC_AFTER = 3
    }
}

/** The check in progress, if any: one at a time, and cleared when the link goes away. */
internal class ClockCheckSlot {
    @Volatile var collector: ClockCollector? = null
        private set

    @Synchronized fun begin(): ClockCollector {
        check(collector == null) { "A clock check is already running" }
        return ClockCollector().also { collector = it }
    }

    @Synchronized fun end(finished: ClockCollector) {
        if (collector === finished) collector = null
    }

    /** The link dropped or is being replaced: ends the running check as aborted. */
    @Synchronized fun abort() {
        collector?.abort()
        collector = null
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
     * When [collector] is aborted (the link dropped) it returns at once with
     * [ClockCheckResult.aborted] and sends nothing more: the next connect writes the angles.
     */
    suspend fun run(
        durationMs: Long,
        collector: ClockCollector,
        setContent: (OutputContent) -> Unit,
        wait: suspend (ClockCollector, Long) -> Unit = { c, ms -> c.awaitAbort(ms) }
    ): ClockCheckResult {
        try {
            setContent(OutputContent.TIMESTAMP)
            wait(collector, SWITCH_SETTLE_MS)
            collector.open()
            wait(collector, durationMs)
            collector.close()
            return collector.result()
        } finally {
            collector.close()
            if (!collector.isAborted) {
                setContent(OutputContent.ANGLES)
                withContext(NonCancellable) { wait(collector, RESTORE_SETTLE_MS) }
            }
        }
    }
}
