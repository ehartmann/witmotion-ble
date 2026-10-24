package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Live aiming trace: where the bow pointed during the last few seconds, relative to the mean aim. */
public object LiveAim {

    /** One trace point in degrees from the mean aim of the window, and how old it is. */
    public data class Point(val yaw: Float, val pitch: Float, val ageMs: Long)

    /** A typical aim lasts about 6 s before the release. */
    public const val DEFAULT_WINDOW_MS: Long = 6_000L

    /**
     * Points of the samples within [windowMs] of the latest one, oldest first, relative to their
     * mean (yaw measured as the shortest angle, so a heading crossing 180° stays continuous).
     */
    public fun trail(samples: List<ImuSample>, windowMs: Long = DEFAULT_WINDOW_MS): List<Point> {
        val latest = samples.lastOrNull()?.timestamp ?: return emptyList()
        val recent = samples.filter { it.euler != null && latest - it.timestamp <= windowMs }
        if (recent.isEmpty()) return emptyList()

        val anchor = recent.first().euler!![2]
        val yaws = recent.map { wrapDegrees(it.euler!![2] - anchor) }
        val pitches = recent.map { it.euler!![1] }
        val meanYaw = yaws.average().toFloat()
        val meanPitch = pitches.average().toFloat()
        return recent.indices.map { i -> Point(yaws[i] - meanYaw, pitches[i] - meanPitch, latest - recent[i].timestamp) }
    }
}

/** [degrees] brought into [-180, 180): the shortest signed angle. */
internal fun wrapDegrees(degrees: Float): Float = ((degrees + 540f) % 360f) - 180f

/** Mean of angles in degrees across the +/-180 wrap: +179 and -179 give 180, not 0. NaN when [degrees] is empty. */
internal fun circularMean(degrees: List<Float>): Float {
    if (degrees.isEmpty()) return Float.NaN
    val radians = degrees.map { Math.toRadians(it.toDouble()) }
    return Math.toDegrees(atan2(radians.sumOf { sin(it) }, radians.sumOf { cos(it) })).toFloat()
}
