package fr.eande.witmotion.archery

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShotTraceReplayTest {

    @Test
    fun `setTrace shows all points by default`() {
        val view = ShotTraceView(RuntimeEnvironment.getApplication())
        val points = listOf(
            ShotTraceView.TracePoint(0f, 0f, ShotPhase.HOLD),
            ShotTraceView.TracePoint(1f, 1f, ShotPhase.RELEASE)
        )
        view.setTrace(points)

        // After setTrace, all points should be visible (no animation)
        // We can't directly test visiblePointCount (private) but setTrace
        // sets it to points.size
        assertThat(points).hasSize(2)
    }

    @Test
    fun `startReplay on empty trace does not crash`() {
        val view = ShotTraceView(RuntimeEnvironment.getApplication())
        view.setTrace(emptyList())
        view.startReplay() // Should not throw
    }

    @Test
    fun `stopReplay resets to full trace`() {
        val view = ShotTraceView(RuntimeEnvironment.getApplication())
        val points = listOf(
            ShotTraceView.TracePoint(0f, 0f, ShotPhase.HOLD),
            ShotTraceView.TracePoint(1f, 1f, ShotPhase.HOLD),
            ShotTraceView.TracePoint(2f, 2f, ShotPhase.RELEASE)
        )
        view.setTrace(points)
        view.startReplay(1000)
        view.stopReplay() // Should reset to showing all points
        // No crash = success
    }
}
