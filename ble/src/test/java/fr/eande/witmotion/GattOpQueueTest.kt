package fr.eande.witmotion

import android.bluetooth.BluetoothGatt
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

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
}
