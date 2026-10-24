package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Classifies common archery shooting errors from IMU data patterns.
 *
 * Inspired by Mantis X8's error classification system. Each error type
 * is detected by analyzing specific movement patterns during the shot phases.
 */
object ShotErrorClassifier {

    /**
     * Known archery shooting errors and their descriptions.
     */
    enum class ShotError(val label: String, val description: String) {
        ANTICIPATION("Anticipation", "Movement before the release — archer expects the shot and tenses up"),
        FLINCH("Flinch", "Sudden jerky movement at release — common in beginners"),
        BREAKING_WRIST("Breaking wrist", "Wrist rotation during or after release — grip too tight"),
        DROPPING_ARM("Dropping arm", "Bow arm drops immediately after release — no follow-through"),
        CREEPING("Creeping", "Slow forward draw hand movement during hold — losing draw length"),
        CLEAN("Clean shot", "Good execution with minimal parasitic movement")
    }

    data class ClassificationResult(
        val primaryError: ShotError,
        val confidence: Float,      // 0-1
        val secondaryError: ShotError? = null,
        val details: String = ""
    )

    /**
     * Classify a shot from IMU samples and detected phases.
     */
    fun classify(
        samples: List<ImuSample>,
        phases: List<BowMovementAnalyzer.PhaseSegment>
    ): ClassificationResult {
        if (samples.size < 10) return ClassificationResult(ShotError.CLEAN, 0f, details = "Insufficient data")

        val holdSegment = phases.find { it.phase == ShotPhase.HOLD }
        val releaseSegment = phases.find { it.phase == ShotPhase.RELEASE }
        val ftSegment = phases.find { it.phase == ShotPhase.FOLLOW_THROUGH }

        if (holdSegment == null) return ClassificationResult(ShotError.CLEAN, 0.3f, details = "No hold phase detected")

        val holdSamples = samples.subListSafe(holdSegment.startIndex, holdSegment.endIndex + 1)
        val releaseSamples = releaseSegment?.let { samples.subListSafe(it.startIndex, it.endIndex + 1) }
        val ftSamples = ftSegment?.let { samples.subListSafe(it.startIndex, it.endIndex + 1) }

        // Check for each error pattern
        val errors = mutableListOf<Pair<ShotError, Float>>()

        // Anticipation: movement in the last 200ms of hold BEFORE release
        val anticipation = checkAnticipation(holdSamples)
        if (anticipation > 0.5f) errors.add(ShotError.ANTICIPATION to anticipation)

        // Flinch: large sudden jerk at release
        val flinch = checkFlinch(samples, releaseSegment)
        if (flinch > 0.5f) errors.add(ShotError.FLINCH to flinch)

        // Breaking wrist: roll change > threshold during release
        val breakingWrist = checkBreakingWrist(holdSamples, releaseSamples)
        if (breakingWrist > 0.5f) errors.add(ShotError.BREAKING_WRIST to breakingWrist)

        // Dropping arm: pitch drops > threshold in follow-through
        val droppingArm = checkDroppingArm(holdSamples, ftSamples)
        if (droppingArm > 0.5f) errors.add(ShotError.DROPPING_ARM to droppingArm)

        // Creeping: slow forward movement during hold
        val creeping = checkCreeping(holdSamples)
        if (creeping > 0.5f) errors.add(ShotError.CREEPING to creeping)

        // No error found: a clean verdict needs every input the checks read
        val missing = listOfNotNull(
            "No release".takeIf { releaseSegment == null },
            "No follow-through".takeIf { ftSegment == null },
            "No euler angles".takeIf { samples.none { it.euler != null } }
        )
        if (errors.isEmpty() && missing.isNotEmpty()) {
            return ClassificationResult(ShotError.CLEAN, 0.3f, details = missing.joinToString())
        }
        if (errors.isEmpty()) {
            return ClassificationResult(ShotError.CLEAN, 0.9f, details = "Good shot execution")
        }

        // Sort by confidence and return primary + secondary
        errors.sortByDescending { it.second }
        return ClassificationResult(
            primaryError = errors[0].first,
            confidence = errors[0].second,
            secondaryError = errors.getOrNull(1)?.first,
            details = errors.joinToString(", ") { "${it.first.label} (${(it.second * 100).toInt()}%)" }
        )
    }

    // --- Error detection algorithms ---

    /**
     * Anticipation: gyroscope increases in the last 30% of hold.
     */
    private fun checkAnticipation(holdSamples: List<ImuSample>): Float {
        if (holdSamples.size < 10) return 0f

        val splitPoint = holdSamples.size * 7 / 10
        val earlyHold = holdSamples.subList(0, splitPoint)
        val lateHold = holdSamples.subList(splitPoint, holdSamples.size)

        val earlyGyroAvg = earlyHold.map { gyroMag(it) }.average().toFloat()
        val lateGyroAvg = lateHold.map { gyroMag(it) }.average().toFloat()

        // If late hold is 2x noisier than early hold → anticipation
        val ratio = if (earlyGyroAvg > 0.1f) lateGyroAvg / earlyGyroAvg else 0f
        return ((ratio - 1.5f) / 1.5f).coerceIn(0f, 1f)
    }

    /**
     * Flinch: very large acceleration spike at release.
     */
    private fun checkFlinch(samples: List<ImuSample>, releaseSegment: BowMovementAnalyzer.PhaseSegment?): Float {
        if (releaseSegment == null) return 0f

        val analyzer = BowMovementAnalyzer()
        val maxSpike = (releaseSegment.startIndex..minOf(releaseSegment.endIndex, samples.lastIndex))
            .maxOfOrNull { analyzer.accelJump(samples, it) } ?: 0f

        // Normal release: 2-5g spike. Flinch: > 8g spike
        return ((maxSpike - 5f) / 5f).coerceIn(0f, 1f)
    }

    /**
     * Breaking wrist: roll angle changes > 5° between hold and release.
     */
    private fun checkBreakingWrist(holdSamples: List<ImuSample>, releaseSamples: List<ImuSample>?): Float {
        if (releaseSamples == null) return 0f

        val holdRoll = holdSamples.mapNotNull { it.euler?.get(0) }.average().toFloat()
        val releaseRoll = releaseSamples.mapNotNull { it.euler?.get(0) }.average().toFloat()

        if (holdRoll.isNaN() || releaseRoll.isNaN()) return 0f

        val rollChange = abs(releaseRoll - holdRoll)
        return ((rollChange - 3f) / 7f).coerceIn(0f, 1f)
    }

    /**
     * Dropping arm: pitch drops > 5° in follow-through vs hold.
     */
    private fun checkDroppingArm(holdSamples: List<ImuSample>, ftSamples: List<ImuSample>?): Float {
        if (ftSamples == null || ftSamples.isEmpty()) return 0f

        val holdPitch = holdSamples.mapNotNull { it.euler?.get(1) }.average().toFloat()
        val ftPitch = ftSamples.mapNotNull { it.euler?.get(1) }.average().toFloat()

        if (holdPitch.isNaN() || ftPitch.isNaN()) return 0f

        val pitchDrop = holdPitch - ftPitch // positive = arm dropped
        return ((pitchDrop - 3f) / 7f).coerceIn(0f, 1f)
    }

    /**
     * Creeping: consistent drift in one direction during hold (losing draw length).
     */
    private fun checkCreeping(holdSamples: List<ImuSample>): Float {
        if (holdSamples.size < 10) return 0f

        val pitchValues = holdSamples.mapNotNull { it.euler?.get(1) }
        if (pitchValues.size < 5) return 0f

        // Calculate drift: difference between first and last quarter
        val firstQuarter = pitchValues.take(pitchValues.size / 4).average().toFloat()
        val lastQuarter = pitchValues.takeLast(pitchValues.size / 4).average().toFloat()
        val drift = abs(lastQuarter - firstQuarter)

        return ((drift - 1f) / 3f).coerceIn(0f, 1f)
    }

    // --- Utilities ---

    private fun gyroMag(sample: ImuSample): Float {
        val g = sample.gyroscope ?: return 0f
        return sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
    }

    private fun <T> List<T>.subListSafe(from: Int, to: Int): List<T> {
        val f = from.coerceIn(0, size)
        val t = to.coerceIn(f, size)
        return subList(f, t)
    }
}
