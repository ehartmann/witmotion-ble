package fr.eande.witmotion.archery

import kotlin.math.sqrt

/**
 * Shot-to-shot consistency, from traces relative to each shot's hold center
 * ([ShotTraceView.samplesToTrace]): how still each hold was, and how repeatable the release was.
 */
object ShotComparison {

    data class Summary(
        /** Mean over the shots of [holdSpread], in degrees; null when no shot has a hold. */
        val meanHoldSpread: Float?,
        /** RMS distance (°) of the shots' release points from their mean; null under 2 releases. */
        val releaseScatter: Float?
    )

    /** RMS distance (°) of the hold points from the hold center: the smaller, the stiller the aim. */
    fun holdSpread(points: List<ShotTraceView.TracePoint>): Float? {
        val hold = points.filter { it.phase == ShotPhase.HOLD }
        if (hold.isEmpty()) return null
        return sqrt(hold.map { it.yaw * it.yaw + it.pitch * it.pitch }.average()).toFloat()
    }

    /** Where the bow pointed, relative to the hold center, when the release started. */
    fun releasePoint(points: List<ShotTraceView.TracePoint>): ShotTraceView.TracePoint? =
        points.firstOrNull { it.phase == ShotPhase.RELEASE }

    fun summarize(shots: List<List<ShotTraceView.TracePoint>>): Summary {
        val spreads = shots.mapNotNull(::holdSpread)
        val releases = shots.mapNotNull(::releasePoint)
        val scatter = if (releases.size < 2) {
            null
        } else {
            val meanYaw = releases.map { it.yaw }.average()
            val meanPitch = releases.map { it.pitch }.average()
            sqrt(releases.map { (it.yaw - meanYaw).let { d -> d * d } + (it.pitch - meanPitch).let { d -> d * d } }.average())
                .toFloat()
        }
        return Summary(spreads.takeIf { it.isNotEmpty() }?.average()?.toFloat(), scatter)
    }
}
