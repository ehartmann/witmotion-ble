package fr.eande.witmotion.archery

import fr.eande.witmotion.ImuSample

/** Live aiming trace: where the bow pointed during the last few seconds, relative to the mean aim. */
object LiveAim {

    /** One trace point in degrees from the mean aim of the window, and how old it is. */
    data class Point(val yaw: Float, val pitch: Float, val ageMs: Long)

    /** A typical aim lasts about 6 s before the release. */
    const val DEFAULT_WINDOW_MS = 6_000L

    /**
     * Points of the samples within [windowMs] of the latest one, oldest first, relative to their
     * mean (yaw measured as the shortest angle, so a heading crossing 180° stays continuous).
     */
    fun trail(samples: List<ImuSample>, windowMs: Long = DEFAULT_WINDOW_MS): List<Point> {
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
