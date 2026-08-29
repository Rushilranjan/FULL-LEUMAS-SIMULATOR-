package com.leumas.robot.ble

import android.util.Log

/**
 * Serialises GATT operations, one in flight at a time.
 *
 * Android's BLE stack silently drops an operation issued while another is outstanding — no error,
 * no callback, the request simply never happens. `discoverServices`, `writeDescriptor`,
 * `requestMtu` and characteristic writes all share the single slot, so every one of them has to go
 * through here and the next may only start from the previous one's callback.
 *
 * Confined to the callback thread; not thread-safe by design, since interleaving is exactly the
 * bug this exists to prevent.
 */
class GattQueue {

    private data class Operation(val name: String, val action: () -> Boolean)

    private val pending = ArrayDeque<Operation>()
    private var inFlight: Operation? = null

    /** Queue an operation. [action] returns false if the stack rejected the request outright. */
    fun enqueue(name: String, action: () -> Boolean) {
        pending.addLast(Operation(name, action))
        if (inFlight == null) dispatchNext()
    }

    /** Call from the GATT callback that corresponds to the outstanding operation. */
    fun completeCurrent() {
        inFlight = null
        dispatchNext()
    }

    fun clear() {
        pending.clear()
        inFlight = null
    }

    private fun dispatchNext() {
        if (inFlight != null) return
        val next = pending.removeFirstOrNull() ?: return
        inFlight = next
        val accepted = runCatching { next.action() }.getOrElse { error ->
            Log.w(TAG, "GATT op '${next.name}' threw", error)
            false
        }
        if (!accepted) {
            // The stack refused to even start it, so no callback is coming. Move on rather than
            // stalling the queue forever.
            Log.w(TAG, "GATT op '${next.name}' was rejected by the stack")
            inFlight = null
            dispatchNext()
        }
    }

    private companion object {
        const val TAG = "GattQueue"
    }
}
