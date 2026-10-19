package fr.eande.witmotion.archery

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.common.truth.Truth.assertThat
import fr.eande.witmotion.ImuSample
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class LiveAimTest {

    private fun at(t: Long, pitch: Float, yaw: Float) = ImuSample(timestamp = t, euler = floatArrayOf(0f, pitch, yaw))

    @Test
    fun `trail is relative to the mean aim and keeps only the window`() {
        val samples = listOf(at(0, 50f, 50f)) + listOf(at(10_000, 10f, 20f), at(11_000, 12f, 22f), at(12_000, 14f, 24f))

        val trail = LiveAim.trail(samples, windowMs = 6_000)

        assertThat(trail.map { it.ageMs }).containsExactly(2_000L, 1_000L, 0L).inOrder()
        assertThat(trail.map { it.pitch }).containsExactly(-2f, 0f, 2f).inOrder()
        assertThat(trail.map { it.yaw }).containsExactly(-2f, 0f, 2f).inOrder()
    }

    @Test
    fun `a heading crossing 180 degrees stays continuous`() {
        val samples = listOf(at(0, 0f, 179.6f), at(20, 0f, -179.8f), at(40, 0f, 179.9f), at(60, 0f, -179.7f))

        assertThat(LiveAim.trail(samples).maxOf { abs(it.yaw) }).isLessThan(1f)
    }

    @Test
    fun `no euler angles, no trail`() {
        assertThat(LiveAim.trail(emptyList())).isEmpty()
        assertThat(LiveAim.trail(listOf(ImuSample(timestamp = 0)))).isEmpty()
    }

    @Test
    fun `view draws a trail and an empty state without crashing`() {
        val view = LiveAimView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, 400, 400)

        view.setSamples(List(300) { at(it * 20L, (it % 7) * 0.1f, (it % 5) * 0.1f) })
        assertThat(view.trail).hasSize(300)
        view.draw(Canvas(Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)))

        view.setSamples(emptyList())
        view.draw(Canvas(Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)))
        assertThat(view.trail).isEmpty()
    }
}
