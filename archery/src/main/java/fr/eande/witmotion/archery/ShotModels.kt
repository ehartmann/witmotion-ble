package fr.eande.witmotion.archery

/**
 * Phases of an archery shot cycle, detected from IMU data.
 */
public enum class ShotPhase {
    IDLE,
    DRAW,
    HOLD,
    RELEASE,
    FOLLOW_THROUGH,

    /** The bow rising from its rest to the aim. [BowMovementAnalyzer.detectPhases] never emits it: see [ShotBoundaries]. */
    RAISE
}

/**
 * Sample indices where a shot's phases start, from [BowMovementAnalyzer.shotBoundaries].
 * [raiseStartIndex] is null when no raise is found (the bow was already up) or the samples begin during it.
 */
public data class ShotBoundaries(
    val raiseStartIndex: Int?,
    val drawStartIndex: Int,
    val holdStartIndex: Int,
    val releaseIndex: Int
)

/**
 * Metrics computed for a single shot from IMU data.
 */
public data class BowMovementMetrics(
    val holdScore: Float,          // 0-100, stability during hold
    val releaseScore: Float,       // 0-100, quality of release
    val followThroughScore: Float, // 0-100, post-shot form
    val totalScore: Float,         // weighted composite
    val holdTimeMs: Long,          // duration at full draw
    val cantAngle: Float,          // mean roll during hold (°)
    val holdStdDev: Float          // gyroscope std dev during hold
)
