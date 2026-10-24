package fr.eande.witmotion

import android.bluetooth.BluetoothGatt
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Android runs one GATT operation at a time per connection: a write or read issued while
 * another is in flight is rejected. Each queued op returns whether it started. [enqueue]
 * starts the op at once when nothing is in flight; otherwise the op waits until the caller
 * calls [next] from the in-flight op's completion callback, or until [OP_TIMEOUT_MS] has
 * passed: the stack sometimes loses a completion callback.
 */
class GattOpQueue {
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var busy = false
    private var generation = 0
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Drops pending ops; call on (re)connect and disconnect, when no callback will arrive. */
    @Synchronized
    fun clear() {
        ops.clear()
        busy = false
        generation++
    }

    @Synchronized
    fun enqueue(gatt: BluetoothGatt, op: (BluetoothGatt) -> Boolean) {
        ops.addLast(op)
        if (!busy) next(gatt)
    }

    /** Starts the next op that accepts to start; ops that fail to start or throw are dropped. */
    @Synchronized
    fun next(gatt: BluetoothGatt) {
        while (true) {
            val op = ops.removeFirstOrNull() ?: break
            val started = try {
                op(gatt)
            } catch (e: Exception) {
                Log.w(TAG, "GATT op failed to start", e)
                false
            }
            if (started) {
                busy = true
                val inFlight = ++generation
                handler.postDelayed({ timeOut(gatt, inFlight) }, OP_TIMEOUT_MS)
                return
            }
        }
        busy = false
        generation++
    }

    @Synchronized
    private fun timeOut(gatt: BluetoothGatt, inFlight: Int) {
        if (!busy || generation != inFlight) return
        Log.w(TAG, "GATT op got no completion in $OP_TIMEOUT_MS ms, moving on")
        next(gatt)
    }

    private companion object {
        const val TAG = "GattOpQueue"
        const val OP_TIMEOUT_MS = 2_000L
    }
}
