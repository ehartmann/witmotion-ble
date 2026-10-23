package fr.eande.witmotion.archery

import com.google.common.truth.Truth.assertThat
import fr.eande.witmotion.ImuSample
import org.junit.Test

class DetectionDiagnosticsTest {

    private class Stream(private val stepMs: Long = 20) {
        val samples = mutableListOf<ImuSample>()
        var t = 0L

        fun at(gyro: Float, accel: FloatArray = REST) {
            samples += ImuSample(
                timestamp = t, gyroscope = floatArrayOf(gyro, 0f, 0f), acceleration = accel, euler = floatArrayOf(2f, 0f, 0f)
            )
            t += stepMs
        }

        fun feed(durationMs: Long, gyro: Float) {
            val end = t + durationMs
            while (t < end) at(gyro)
        }

        fun idle(ms: Long = 200) = feed(ms, 0f)
        fun draw(ms: Long = 1_000) = feed(ms, 30f)
        fun hold(ms: Long) = feed(ms, 1f)
        fun release() = at(1f, JOLT)
        fun weakSpike() = at(1f, WEAK_JOLT)
        fun followThrough(ms: Long = 3_200) = feed(ms, 2f)

        /** Idle, draw, hold, release, follow-through: a shot the detector emits. */
        fun shot(holdMs: Long = 1_000) {
            idle(); draw(); hold(holdMs); release(); followThrough()
        }

        fun explain() = DetectionDiagnostics.explain(samples.asSequence())
    }

    private companion object {
        val REST = floatArrayOf(0f, 0f, 1f)
        val JOLT = floatArrayOf(5f, 2f, 3f)          // 4.9 g from rest
        val WEAK_JOLT = floatArrayOf(0f, 0f, 2.5f)   // 1.5 g from rest
    }

    private fun Stream.onlyNearMiss(): NearMiss {
        val report = explain()
        assertThat(report.shots).isEmpty()
        assertThat(report.nearMisses).hasSize(1)
        return report.nearMisses.single()
    }

    @Test
    fun `a draw that never stops for over 30 s is DRAW_TOO_LONG`() {
        val s = Stream().apply { idle(); draw(31_000) }

        val miss = s.onlyNearMiss()

        assertThat(miss.reason).isEqualTo(Reason.DRAW_TOO_LONG)
        assertThat(miss.drawStartMs).isEqualTo(200L)
        assertThat(miss.holdMs).isNull()
        assertThat(miss.holdMeanGyroDps).isNull()
        assertThat(miss.maxDrawGyroDps).isWithin(0.01f).of(30f)
    }

    @Test
    fun `a hold broken by movement before 500 ms is MOVED_DURING_HOLD`() {
        val s = Stream().apply { idle(); draw(); hold(300); draw(1_000) }

        val miss = s.onlyNearMiss()

        assertThat(miss.reason).isEqualTo(Reason.MOVED_DURING_HOLD)
        assertThat(miss.holdMs).isAtMost(499L)
        assertThat(miss.holdMs).isAtLeast(300L)
    }

    @Test
    fun `a release spike before the hold reaches 500 ms is HOLD_TOO_SHORT`() {
        val s = Stream().apply { idle(); draw(); hold(300); release(); hold(1_500) }

        val miss = s.onlyNearMiss()

        assertThat(miss.reason).isEqualTo(Reason.HOLD_TOO_SHORT)
        assertThat(miss.maxAccelJumpG).isGreaterThan(2f)
        assertThat(miss.holdMs).isAtLeast(500L)
    }

    @Test
    fun `a release spike that breaks the hold by movement is HOLD_TOO_SHORT, not MOVED_DURING_HOLD`() {
        val s = Stream().apply { idle(); draw(); hold(300); at(40f, JOLT); draw(1_000) }

        assertThat(s.onlyNearMiss().reason).isEqualTo(Reason.HOLD_TOO_SHORT)
    }

    @Test
    fun `a confirmed hold with a 1_5 g jump is SPIKE_TOO_WEAK`() {
        val s = Stream().apply { idle(); draw(); hold(1_000); weakSpike(); hold(1_000); draw(300) }

        val miss = s.onlyNearMiss()

        assertThat(miss.reason).isEqualTo(Reason.SPIKE_TOO_WEAK)
        assertThat(miss.maxAccelJumpG).isWithin(0.01f).of(1.5f)
        assertThat(miss.holdMs).isAtLeast(2_000L)
        assertThat(miss.holdMeanGyroDps).isWithin(0.01f).of(1f)
    }

    @Test
    fun `a confirmed hold with nothing at or above weakSpikeG is NO_SPIKE`() {
        val s = Stream().apply { idle(); draw(); hold(1_000); draw(300) }

        val miss = s.onlyNearMiss()

        assertThat(miss.reason).isEqualTo(Reason.NO_SPIKE)
        assertThat(miss.maxAccelJumpG).isLessThan(1f)
        assertThat(miss.drawStartMs).isEqualTo(200L)
        assertThat(miss.endMs).isAtLeast(miss.drawStartMs + 2_000)
    }

    @Test
    fun `weakSpikeG moves the line between SPIKE_TOO_WEAK and NO_SPIKE`() {
        val s = Stream().apply { idle(); draw(); hold(1_000); weakSpike(); hold(1_000); draw(300) }

        val report = DetectionDiagnostics.explain(s.samples.asSequence(), weakSpikeG = 1.6f)

        assertThat(report.nearMisses.single().reason).isEqualTo(Reason.NO_SPIKE)
        assertThat(report.weakSpikes).isEmpty()
    }

    @Test
    fun `a clean shot is in shots and not in nearMisses`() {
        val s = Stream().apply { shot() }

        val report = s.explain()

        assertThat(report.nearMisses).isEmpty()
        assertThat(report.shots).hasSize(1)
        val window = report.shots.single()
        assertThat(window.drawStartMs).isEqualTo(200L)
        assertThat(window.releaseMs).isEqualTo(200L + 1_000 + 1_000)
        assertThat(window.endMs - window.releaseMs).isAtLeast(3_000L)
    }

    @Test
    fun `an earlier failed attempt before the shot is a near miss, the shot is not`() {
        val s = Stream().apply { idle(); draw(); hold(1_000); draw(300); shot() }

        val report = s.explain()

        assertThat(report.shots).hasSize(1)
        assertThat(report.nearMisses.map { it.reason }).containsExactly(Reason.NO_SPIKE)
        assertThat(report.nearMisses.single().endMs).isAtMost(report.shots.single().drawStartMs + 20)
    }

    @Test
    fun `a stuck handling window restarts at the latest draw, the shot is found and the stuck hold is explained`() {
        val s = Stream().apply {
            draw(700); feed(17_000, 6f); hold(2_000)
            draw(2_500); hold(9_800); release(); followThrough(3_200)
        }

        val report = s.explain()

        assertThat(report.shots).hasSize(1)
        assertThat(report.nearMisses.map { it.reason }).containsExactly(Reason.NO_SPIKE)
    }

    @Test
    fun `shots agree with detectAll on a mixed stream of 2 shots and 3 near misses`() {
        val s = Stream().apply {
            idle(); draw(); hold(1_000); draw(300)                       // NO_SPIKE
            idle(); draw(); hold(1_000); weakSpike(); hold(500); draw(300) // SPIKE_TOO_WEAK
            shot()
            idle(2_000); draw(); hold(300); release(); hold(1_500); draw(300) // HOLD_TOO_SHORT
            shot(holdMs = 2_000)
        }

        val report = s.explain()
        val detected = ShotCycleDetector.detectAll(s.samples.asSequence())

        assertThat(detected).hasSize(2)
        assertThat(report.shots.map { it.drawStartMs }).containsExactlyElementsIn(detected.map { it.samples.first().timestamp }).inOrder()
        assertThat(report.shots.map { it.endMs }).containsExactlyElementsIn(detected.map { it.samples.last().timestamp }).inOrder()
        assertThat(report.nearMisses.map { it.reason })
            .containsExactly(Reason.NO_SPIKE, Reason.SPIKE_TOO_WEAK, Reason.HOLD_TOO_SHORT).inOrder()
        report.shots.forEach { shot ->
            assertThat(report.nearMisses.filter { it.drawStartMs in shot.drawStartMs..shot.endMs }).isEmpty()
        }
    }

    @Test
    fun `weakSpikes lists a 1_5 g jump outside any shot and not the release of a detected shot`() {
        val s = Stream().apply { idle(); draw(); hold(1_000); weakSpike(); hold(1_000); draw(300); shot() }

        val report = s.explain()

        assertThat(report.shots).hasSize(1)
        assertThat(report.weakSpikes).hasSize(1)
        assertThat(report.weakSpikes.single().jumpG).isWithin(0.01f).of(1.5f)
        assertThat(report.weakSpikes.single().tMs).isLessThan(report.shots.single().drawStartMs)
    }

    @Test
    fun `a 30 min recording at 100 Hz is explained with a bounded buffer`() {
        val run = DiagnosticsRun(BowMovementAnalyzer(), weakSpikeG = 1f, afterReleaseMs = 3_000)
        var produced = 0
        val cycleMs = 20_000L
        val recording = sequence {
            var t = 0L
            while (t < 30 * 60_000L) {
                val local = t % cycleMs
                val gyro = when {
                    local < 1_000 -> 0f
                    local < 2_000 -> 30f
                    local < 5_000 -> 1f
                    else -> 2f
                }
                val accel = if (local == 5_000L) JOLT else REST
                produced++
                yield(ImuSample(timestamp = t, gyroscope = floatArrayOf(gyro, 0f, 0f), acceleration = accel))
                t += 10
            }
        }

        recording.forEach(run::onSample)
        val report = run.finish()

        assertThat(produced).isEqualTo(180_000)
        assertThat(report.shots).hasSize(90)
        assertThat(report.nearMisses).isEmpty()
        assertThat(run.peakBufferedSamples).isGreaterThan(0)
        assertThat(run.peakBufferedSamples).isAtMost(1_000)
    }

    private fun Stream.prematureRelease() {
        idle(); draw(); hold(300); at(40f, JOLT)
    }

    @Test
    fun `a premature release followed by a shot is still a HOLD_TOO_SHORT near miss`() {
        val s = Stream().apply { prematureRelease(); draw(1_000); shot() }

        val report = s.explain()

        assertThat(report.shots).hasSize(1)
        assertThat(report.nearMisses.map { it.reason }).containsExactly(Reason.HOLD_TOO_SHORT)
        assertThat(report.nearMisses.single().drawStartMs).isEqualTo(200L)
    }

    @Test
    fun `a premature release, 25 s of movement, then a shot (window restart) is still reported`() {
        val s = Stream().apply {
            prematureRelease(); draw(25_000); hold(200); draw(2_000); hold(1_000); release(); followThrough()
        }

        val report = s.explain()

        assertThat(report.shots).hasSize(1)
        assertThat(report.nearMisses.map { it.reason }).containsExactly(Reason.HOLD_TOO_SHORT)
    }

    @Test
    fun `a premature release then a let-down hold gives HOLD_TOO_SHORT then NO_SPIKE`() {
        val s = Stream().apply { prematureRelease(); draw(1_000); hold(1_000); draw(300) }

        val report = s.explain()

        assertThat(report.shots).isEmpty()
        assertThat(report.nearMisses.map { it.reason }).containsExactly(Reason.HOLD_TOO_SHORT, Reason.NO_SPIKE).inOrder()
    }

    @Test
    fun `short broken holds followed by a clean shot are normal aiming, not near misses`() {
        val s = Stream().apply { idle(); draw(); hold(300); draw(1_000); hold(200); draw(500); shot() }

        val report = s.explain()

        assertThat(report.shots).hasSize(1)
        assertThat(report.nearMisses).isEmpty()
    }

    @Test
    fun `short broken holds then a let-down and a held attempt are one attempt, NO_SPIKE`() {
        val s = Stream().apply { idle(); draw(); hold(300); draw(1_000); idle(); draw(); hold(1_000); draw(300) }

        assertThat(s.explain().nearMisses.map { it.reason }).containsExactly(Reason.NO_SPIKE)
    }

    @Test
    fun `a weak release where the gyro crosses first reads SPIKE_TOO_WEAK`() {
        val s = Stream().apply { idle(); draw(); hold(1_000); at(40f); at(40f, WEAK_JOLT); draw(1_000) }

        val miss = s.onlyNearMiss()

        assertThat(miss.reason).isEqualTo(Reason.SPIKE_TOO_WEAK)
        assertThat(miss.maxAccelJumpG).isWithin(0.01f).of(1.5f)
    }

    @Test
    fun `many weak spikes over a long stream keep the pending spikes bounded`() {
        val run = DiagnosticsRun(BowMovementAnalyzer(), weakSpikeG = 1f, afterReleaseMs = 3_000)
        var peakPending = 0
        val cycleMs = 20_000L
        var t = 0L
        while (t < 30 * 60_000L) {
            val local = t % cycleMs
            val gyro = when {
                local < 1_000 -> 0f
                local < 2_000 -> 30f
                local < 5_000 -> 1f
                else -> 2f
            }
            val accel = when {
                local == 5_000L -> JOLT
                local >= 10_000 && local % 1_000 == 0L -> WEAK_JOLT
                else -> REST
            }
            run.onSample(ImuSample(timestamp = t, gyroscope = floatArrayOf(gyro, 0f, 0f), acceleration = accel))
            peakPending = maxOf(peakPending, run.pendingSpikes)
            t += 10
        }
        val report = run.finish()

        assertThat(report.shots).hasSize(90)
        assertThat(report.weakSpikes).hasSize(900)
        assertThat(peakPending).isAtMost(12)
    }
}
