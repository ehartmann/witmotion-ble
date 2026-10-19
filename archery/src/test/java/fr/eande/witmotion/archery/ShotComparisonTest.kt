package fr.eande.witmotion.archery

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.common.truth.Truth.assertThat
import fr.eande.witmotion.archery.ShotTraceView.TracePoint
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ShotComparisonTest {

    private fun hold(vararg yawPitch: Pair<Float, Float>) = yawPitch.map { (y, p) -> TracePoint(y, p, ShotPhase.HOLD) }
    private fun release(yaw: Float, pitch: Float) = TracePoint(yaw, pitch, ShotPhase.RELEASE)

    @Test
    fun `hold spread is the RMS distance of the hold points from the center`() {
        val points = hold(3f to 4f, -3f to -4f) + TracePoint(50f, 50f, ShotPhase.DRAW)

        assertThat(ShotComparison.holdSpread(points)).isWithin(1e-4f).of(5f)
        assertThat(ShotComparison.holdSpread(listOf(TracePoint(1f, 1f, ShotPhase.DRAW)))).isNull()
    }

    @Test
    fun `release scatter measures how far the release points are from their mean`() {
        val shots = listOf(
            hold(0f to 0f) + release(1f, 0f),
            hold(0f to 0f) + release(-1f, 0f),
            hold(0f to 0f) + release(0f, 1f) + release(9f, 9f), // only the first release point counts
            hold(0f to 0f) + release(0f, -1f)
        )

        val summary = ShotComparison.summarize(shots)

        assertThat(summary.releaseScatter).isWithin(1e-4f).of(1f)
        assertThat(summary.meanHoldSpread).isWithin(1e-4f).of(0f)
    }

    @Test
    fun `no scatter under two releases and no hold spread without a hold`() {
        val summary = ShotComparison.summarize(listOf(listOf(release(1f, 1f))))

        assertThat(summary.releaseScatter).isNull()
        assertThat(summary.meanHoldSpread).isNull()
        assertThat(ShotComparison.summarize(emptyList())).isEqualTo(ShotComparison.Summary(null, null))
    }

    @Test
    fun `view keeps the last eight shots and draws without crashing`() {
        val view = ShotComparisonView(RuntimeEnvironment.getApplication())
        val shots = (1..10).map { n -> ShotComparisonView.ShotTrace("#$n", hold(n * 0.1f to 0f) + release(n * 0.2f, 0.1f)) }

        view.setShots(shots)
        view.layout(0, 0, 400, 400)
        view.draw(Canvas(Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)))
        view.setShots(emptyList())
        view.draw(Canvas(Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)))

        view.setShots(shots)
        assertThat(view.shots.map { it.label }).containsExactly("#3", "#4", "#5", "#6", "#7", "#8", "#9", "#10").inOrder()
    }
}
