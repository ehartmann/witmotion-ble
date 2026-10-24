package fr.eande.witmotion

import android.bluetooth.BluetoothGatt
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class GattOpQueueTest {

    private val gatt = mockk<BluetoothGatt>()
    private val queue = GattOpQueue()
    private val ran = mutableListOf<String>()

    private fun op(name: String, started: Boolean = true): (BluetoothGatt) -> Boolean = {
        ran += name
        started
    }

    @Test
    fun `enqueue on an idle queue starts the op at once`() {
        queue.enqueue(gatt, op("cccd"))
        assertThat(ran).containsExactly("cccd")
    }

    @Test
    fun `ops wait for the in-flight one to complete`() {
        queue.enqueue(gatt, op("cccd"))
        queue.enqueue(gatt, op("unlock"))
        assertThat(ran).containsExactly("cccd")

        queue.next(gatt) // cccd completed
        assertThat(ran).containsExactly("cccd", "unlock").inOrder()
    }

    @Test
    fun `op that fails to start is skipped so the queue never stalls`() {
        queue.enqueue(gatt, op("cccd"))
        queue.enqueue(gatt, op("unlock", started = false))
        queue.enqueue(gatt, op("rate"))
        queue.enqueue(gatt, op("battery"))

        queue.next(gatt)

        assertThat(ran).containsExactly("cccd", "unlock", "rate").inOrder()
    }

    @Test
    fun `queue is idle again once the last op completes`() {
        queue.enqueue(gatt, op("rate"))
        queue.next(gatt) // rate completed, nothing left

        queue.enqueue(gatt, op("calibrate"))

        assertThat(ran).containsExactly("rate", "calibrate").inOrder()
    }

    @Test
    fun `clear drops pending ops and an in-flight op that will never complete`() {
        queue.enqueue(gatt, op("lost-write")) // its callback never comes: link dropped
        queue.enqueue(gatt, op("stale"))
        queue.clear()

        queue.enqueue(gatt, op("fresh"))

        assertThat(ran).containsExactly("lost-write", "fresh").inOrder()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `an op whose completion never comes is given up after 2 s, so the queue never stalls`() {
        queue.enqueue(gatt, op("lost-write"))
        queue.enqueue(gatt, op("battery"))

        idle(1_900)
        assertThat(ran).containsExactly("lost-write")
        idle(200)

        assertThat(ran).containsExactly("lost-write", "battery").inOrder()
    }

    @Test
    fun `an op that completes in time is not timed out again`() {
        queue.enqueue(gatt, op("a"))
        idle(1_500)
        queue.next(gatt) // a completed
        queue.enqueue(gatt, op("b"))
        queue.enqueue(gatt, op("c"))

        idle(1_000) // 2.5 s since a started, 1 s since b did

        assertThat(ran).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `an op that throws counts as not started`() {
        queue.enqueue(gatt) { ran += "throws"; error("DeadObjectException") }
        queue.enqueue(gatt, op("after"))

        assertThat(ran).containsExactly("throws", "after").inOrder()
    }

    @Test
    fun `clear cancels the timeout of the dropped in-flight op`() {
        queue.enqueue(gatt, op("old"))
        queue.clear()
        queue.enqueue(gatt, op("fresh"))
        queue.enqueue(gatt, op("next"))

        idle(1_900) // 'old' would time out here

        assertThat(ran).containsExactly("old", "fresh").inOrder()
    }

    @Test
    fun `a timeout is handed to the owner when it wants to drop the link instead of moving on`() {
        val timedOut = mutableListOf<BluetoothGatt>()
        val owned = GattOpQueue { timedOut += it }
        owned.enqueue(gatt, op("lost-write"))
        owned.enqueue(gatt, op("battery"))

        idle(2_100)

        assertThat(timedOut).containsExactly(gatt)
        assertThat(ran).containsExactly("lost-write")
    }
}
