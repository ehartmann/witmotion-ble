package fr.eande.witmotion.archery

/**
 * Phases of an archery shot cycle, detected from IMU data.
 */
enum class ShotPhase {
    IDLE,
    DRAW,
    HOLD,
    RELEASE,
    FOLLOW_THROUGH
}

/**
 * Metrics computed for a single shot from IMU data.
 */
data class BowMovementMetrics(
    val holdScore: Float,          // 0-100, stability during hold
    val releaseScore: Float,       // 0-100, quality of release
    val followThroughScore: Float, // 0-100, post-shot form
    val totalScore: Float,         // weighted composite
    val holdTimeMs: Long,          // duration at full draw
    val cantAngle: Float,          // mean roll during hold (°)
    val holdStdDev: Float          // gyroscope std dev during hold
)
