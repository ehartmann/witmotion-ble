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
 * The phase the bow is in as samples arrive, from [ShotCycleDetector]'s `onPhase`. [sinceTimestamp] is,
 * for a HOLD, its first calm sample (the origin of `holdTimeMs`), else the sample where the phase was first seen.
 * HOLD means calm after a movement, not aiming: a lowered bow at rest also reads HOLD, so check the tilt.
 */
public data class LivePhase(val phase: ShotPhase, val sinceTimestamp: Long)

/**
 * Sample indices where a shot's phases start, from [BowMovementAnalyzer.shotBoundaries].
 * [raiseStartIndex] is null when no raise is found: no calm rest 20° off the aim within 10 s before the draw
 * start (the bow was already up), a link gap or the first sample comes first, or the raise, from that rest to
 * the draw start, lasts under 0.5 s or over `raiseMaxMs` (bow handling).
 */
public data class ShotBoundaries(
    val raiseStartIndex: Int?,
    val drawStartIndex: Int,
    val holdStartIndex: Int,
    val releaseIndex: Int
)

/**
 * The bow's rotation after the release, from [BowMovementAnalyzer.releaseRotation].
 * [amplitudeDeg] is the largest angle turned since the release, [peakDps] the fastest rotation,
 * [stopMs] the time after the peak for the speed to fall from 50 % to 10 % of it, null while it stays above 10 %.
 */
public data class ReleaseRotation(val amplitudeDeg: Float, val peakDps: Float, val stopMs: Long?)

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
