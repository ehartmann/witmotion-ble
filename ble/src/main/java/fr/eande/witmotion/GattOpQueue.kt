package fr.eande.witmotion

import android.bluetooth.BluetoothGatt

/**
 * Android runs one GATT operation at a time per connection: a write or read issued while
 * another is in flight is rejected. Each queued op returns whether it started. [enqueue]
 * starts the op at once when nothing is in flight; otherwise the op waits until the caller
 * calls [next] from the in-flight op's completion callback.
 */
class GattOpQueue {
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var busy = false

    /** Drops pending ops; call on (re)connect and disconnect, when no callback will arrive. */
    @Synchronized
    fun clear() {
        ops.clear()
        busy = false
    }

    @Synchronized
    fun enqueue(gatt: BluetoothGatt, op: (BluetoothGatt) -> Boolean) {
        ops.addLast(op)
        if (!busy) next(gatt)
    }

    /** Starts the next op that accepts to start; ops that fail to start are dropped. */
    @Synchronized
    fun next(gatt: BluetoothGatt) {
        while (true) {
            val op = ops.removeFirstOrNull() ?: break
            if (op(gatt)) {
                busy = true
                return
            }
        }
        busy = false
    }
}
