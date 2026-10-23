package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class BowMovementAnalyzerTest {

    private lateinit var analyzer: BowMovementAnalyzer

    @Before
    fun setUp() {
        analyzer = BowMovementAnalyzer(
            holdThreshold = 5f,
            drawThreshold = 20f,
            releaseAccelThreshold = 2f,
            minHoldMs = 100,
            followThroughMs = 200
        )
    }

    private fun sample(
        time: Long,
        gyro: FloatArray = floatArrayOf(0f, 0f, 0f),
        accel: FloatArray = floatArrayOf(0f, 0f, 1f),
        euler: FloatArray = floatArrayOf(0f, 0f, 0f)
    ) = ImuSample(
        timestamp = time,
        gyroscope = gyro,
        acceleration = accel,
        euler = euler
    )

    @Test
    fun `too few samples returns empty analysis`() {
        val result = analyzer.analyze(listOf(sample(0), sample(1)))
        assertThat(result.phases).isEmpty()
        assertThat(result.metrics).isNull()
    }

    @Test
    fun `detects draw phase from high gyroscope`() {
        val samples = (0L..20L).map { t ->
            sample(t * 20, gyro = floatArrayOf(25f, 0f, 0f)) // above drawThreshold
        }
        val phases = analyzer.detectPhases(samples)

        assertThat(phases.any { it.phase == ShotPhase.DRAW }).isTrue()
    }

    @Test
    fun `detects hold after draw when gyroscope drops`() {
        val drawSamples = (0L..10L).map { t ->
            sample(t * 20, gyro = floatArrayOf(25f, 0f, 0f))
        }
        val holdSamples = (11L..30L).map { t ->
            sample(t * 20, gyro = floatArrayOf(1f, 0f, 0f))
        }
        val phases = analyzer.detectPhases(drawSamples + holdSamples)

        assertThat(phases.any { it.phase == ShotPhase.DRAW }).isTrue()
        assertThat(phases.any { it.phase == ShotPhase.HOLD }).isTrue()
    }

    @Test
    fun `detects release from acceleration spike`() {
        val holdSamples = (0L..10L).map { t ->
            sample(t * 20, gyro = floatArrayOf(25f, 0f, 0f)) // draw
        } + (11L..25L).map { t ->
            sample(t * 20, gyro = floatArrayOf(1f, 0f, 0f), accel = floatArrayOf(0f, 0f, 1f))
        }
        // Spike at sample 26
        val releaseSamples = listOf(
            sample(520, gyro = floatArrayOf(1f, 0f, 0f), accel = floatArrayOf(3f, 0f, 1f))
        )
        val afterSamples = (27L..40L).map { t ->
            sample(t * 20, gyro = floatArrayOf(2f, 0f, 0f), accel = floatArrayOf(0f, 0f, 1f))
        }

        val phases = analyzer.detectPhases(holdSamples + releaseSamples + afterSamples)
        assertThat(phases.any { it.phase == ShotPhase.RELEASE }).isTrue()
    }

    /**
     * Draw, a 400 ms hold, then the release shock as recorded on 2026-10-20: from 1 g to 3.6 g in
     * 10 ms, back to 1 g over the next 100 ms; sampled every [periodMs].
     */
    private fun shotWithRampedRelease(periodMs: Long): List<ImuSample> {
        val samples = mutableListOf<ImuSample>()
        var t = 0L
        while (t < 300) { samples += sample(t, gyro = floatArrayOf(25f, 0f, 0f)); t += periodMs }
        while (t < 700) { samples += sample(t, gyro = floatArrayOf(1f, 0f, 0f)); t += periodMs }
        val start = t
        while (t < start + 400) {
            val dt = (t - start).toFloat()
            val z = when {
                dt <= 10f -> 1f + 2.6f * dt / 10f
                dt <= 110f -> 3.6f - 2.6f * (dt - 10f) / 100f
                else -> 1f
            }
            samples += sample(t, gyro = floatArrayOf(if (dt == 0f) 1f else 8f, 0f, 0f), accel = floatArrayOf(0f, 0f, z))
            t += periodMs
        }
        return samples
    }

    @Test
    fun `a release ramping over 10 ms is found at 50, 100 and 200 Hz`() {
        for (period in listOf(20L, 10L, 5L)) {
            val phases = analyzer.detectPhases(shotWithRampedRelease(period))

            assertThat(phases.map { it.phase }).contains(ShotPhase.RELEASE)
        }
    }

    @Test
    fun `a slow change of acceleration during the hold is not a release`() {
        val draw = (0L..15L).map { sample(it * 5, gyro = floatArrayOf(25f, 0f, 0f)) }
        val hold = (16L..200L).map { sample(it * 5, gyro = floatArrayOf(1f, 0f, 0f), accel = floatArrayOf(0f, 0f, 1f + (it - 16) * 0.015f)) }

        assertThat(analyzer.detectPhases(draw + hold).map { it.phase }).doesNotContain(ShotPhase.RELEASE)
    }

    @Test
    fun `release detected when the gyro reacts a sample before the acceleration spike`() {
        // Seen on a WT9011DCL at ~25 Hz: the bow starts turning one sample before the accel jump
        val draw = (0L..5L).map { sample(it * 40, gyro = floatArrayOf(25f, 0f, 0f)) }
        val hold = (6L..12L).map { sample(it * 40, gyro = floatArrayOf(1f, 0f, 0f)) }
        val gyroFirst = sample(520, gyro = floatArrayOf(40f, 0f, 0f))
        val spike = sample(560, gyro = floatArrayOf(60f, 0f, 0f), accel = floatArrayOf(3f, 0f, 1f))
        val after = (15L..25L).map { sample(it * 40, gyro = floatArrayOf(2f, 0f, 0f)) }

        val phases = analyzer.detectPhases(draw + hold + gyroFirst + spike + after)

        assertThat(phases.map { it.phase }).containsAtLeast(ShotPhase.DRAW, ShotPhase.HOLD, ShotPhase.RELEASE).inOrder()
        assertThat(phases.single { it.phase == ShotPhase.RELEASE }.startIndex).isEqualTo(13)
    }

    @Test
    fun `gyro rising out of the hold without a spike is a new draw, not a release`() {
        val draw = (0L..5L).map { sample(it * 40, gyro = floatArrayOf(25f, 0f, 0f)) }
        val hold = (6L..12L).map { sample(it * 40, gyro = floatArrayOf(1f, 0f, 0f)) }
        val redraw = (13L..20L).map { sample(it * 40, gyro = floatArrayOf(30f, 0f, 0f)) }

        val phases = analyzer.detectPhases(draw + hold + redraw)

        assertThat(phases.map { it.phase }).doesNotContain(ShotPhase.RELEASE)
        assertThat(phases.last().phase).isEqualTo(ShotPhase.DRAW)
    }

    @Test
    fun `acceleration spike before minHoldMs does not trigger release`() {
        // Draw, then a momentary settle immediately followed by a jolt only 20 ms
        // into the hold (minHoldMs = 100) — must be ignored, not read as a release.
        val draw = (0L..10L).map { sample(it * 20, gyro = floatArrayOf(25f, 0f, 0f)) }
        val enterHold = sample(220, gyro = floatArrayOf(1f, 0f, 0f))
        val earlySpike = sample(240, gyro = floatArrayOf(1f, 0f, 0f), accel = floatArrayOf(5f, 0f, 1f))
        val keepHolding = (13L..30L).map { sample(it * 20, gyro = floatArrayOf(1f, 0f, 0f)) }

        val phases = analyzer.detectPhases(draw + enterHold + earlySpike + keepHolding)

        assertThat(phases.any { it.phase == ShotPhase.HOLD }).isTrue()
        assertThat(phases.any { it.phase == ShotPhase.RELEASE }).isFalse()
    }

    @Test
    fun `full shot cycle produces all phases`() {
        val samples = mutableListOf<ImuSample>()
        var t = 0L

        // IDLE (brief)
        repeat(3) { samples.add(sample(t, gyro = floatArrayOf(0f, 0f, 0f))); t += 20 }
        // DRAW
        repeat(10) { samples.add(sample(t, gyro = floatArrayOf(30f, 0f, 0f))); t += 20 }
        // HOLD
        repeat(20) { samples.add(sample(t, gyro = floatArrayOf(1f, 0f, 0f), euler = floatArrayOf(2f, 0f, 0f))); t += 20 }
        // RELEASE (accel spike)
        samples.add(sample(t, gyro = floatArrayOf(1f, 0f, 0f), accel = floatArrayOf(5f, 2f, 3f))); t += 20
        // FOLLOW-THROUGH
        repeat(15) { samples.add(sample(t, gyro = floatArrayOf(3f, 0f, 0f))); t += 20 }

        val analysis = analyzer.analyze(samples)

        assertThat(analysis.phases).isNotEmpty()
        val phaseTypes = analysis.phases.map { it.phase }.toSet()
        assertThat(phaseTypes).contains(ShotPhase.DRAW)
        assertThat(phaseTypes).contains(ShotPhase.HOLD)
    }

    @Test
    fun `metrics computed when hold phase exists`() {
        val samples = mutableListOf<ImuSample>()
        var t = 0L

        // DRAW
        repeat(10) { samples.add(sample(t, gyro = floatArrayOf(30f, 0f, 0f))); t += 20 }
        // HOLD (stable, low gyro, 2° cant)
        repeat(30) {
            samples.add(sample(t,
                gyro = floatArrayOf(0.5f + (it % 3) * 0.1f, 0f, 0f),
                euler = floatArrayOf(2.0f, 0f, 0f),
                accel = floatArrayOf(0f, 0f, 1f)
            ))
            t += 20
        }
        // RELEASE
        samples.add(sample(t, accel = floatArrayOf(4f, 1f, 2f), gyro = floatArrayOf(0.5f, 0f, 0f))); t += 20
        // FOLLOW-THROUGH
        repeat(15) { samples.add(sample(t, gyro = floatArrayOf(2f, 0f, 0f))); t += 20 }

        val analysis = analyzer.analyze(samples)
        val metrics = analysis.metrics

        assertThat(metrics).isNotNull()
        assertThat(metrics!!.holdScore).isGreaterThan(0f)
        assertThat(metrics.holdScore).isAtMost(100f)
        assertThat(metrics.totalScore).isGreaterThan(0f)
        assertThat(metrics.cantAngle).isWithin(0.5f).of(2f)
        assertThat(metrics.holdTimeMs).isGreaterThan(0L)
    }

    @Test
    fun `hold score decreases with more gyro noise`() {
        val stableSamples = mutableListOf<ImuSample>()
        val noisySamples = mutableListOf<ImuSample>()
        var t = 0L

        // Both start with draw
        repeat(10) {
            stableSamples.add(sample(t, gyro = floatArrayOf(30f, 0f, 0f)))
            noisySamples.add(sample(t, gyro = floatArrayOf(30f, 0f, 0f)))
            t += 20
        }

        // Stable hold (constant low gyro → low std dev)
        // Noisy hold (varying gyro → high std dev, but all below holdThreshold)
        repeat(30) { i ->
            stableSamples.add(sample(t, gyro = floatArrayOf(0.5f, 0f, 0f)))
            // Alternate between 1 and 4 °/s to create variance
            val noise = if (i % 2 == 0) 1f else 4f
            noisySamples.add(sample(t, gyro = floatArrayOf(noise, 0f, 0f)))
            t += 20
        }

        val stableAnalysis = analyzer.analyze(stableSamples)
        val noisyAnalysis = analyzer.analyze(noisySamples)

        assertThat(stableAnalysis.metrics).isNotNull()
        assertThat(noisyAnalysis.metrics).isNotNull()
        assertThat(stableAnalysis.metrics!!.holdScore).isGreaterThan(noisyAnalysis.metrics!!.holdScore)
    }

    @Test
    fun `release jump at the release start exceeds the release threshold at 50, 100 and 200 Hz`() {
        for (period in listOf(20L, 10L, 5L)) {
            val samples = shotWithRampedRelease(period)
            val release = analyzer.detectPhases(samples).first { it.phase == ShotPhase.RELEASE }.startIndex
            assertThat(analyzer.releaseJumpG(samples, release)).isGreaterThan(2f)
        }
    }

    @Test
    fun `release jump during a calm hold stays small`() {
        val samples = (0..100).map { sample(time = it * 10L) }
        assertThat(analyzer.releaseJumpG(samples, 50)).isLessThan(0.1f)
    }
}
