package com.leumas.robot.sim

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a [WireTrace] as if it were arriving from the BT05.
 *
 * Feeds the *same* `LineAssembler` and parser the real BLE path uses, so demo mode exercises the
 * entire stack rather than bypassing it. Two details make the simulation faithful rather than
 * convenient:
 *
 *  - messages scheduled at the same instant are concatenated into one payload, as they are when
 *    several `ble_*` calls land in one firmware tick
 *  - payloads are chopped to [MTU_PAYLOAD] bytes, because the BT05 negotiates a 23-byte MTU and
 *    really does split a message across notifications
 *
 * If the app only ever worked against tidy one-message-per-packet input, it would break the first
 * time it met real hardware.
 */
class SimulatedSource(
    private val mtuPayload: Int = MTU_PAYLOAD,
) {
    fun replay(trace: List<TraceStep>, speed: Float = 1f): Flow<ByteArray> = flow {
        var previousAt = 0L
        for (step in trace) {
            val waitMs = ((step.atMs - previousAt) / speed).toLong().coerceAtLeast(0L)
            if (waitMs > 0) delay(waitMs)
            previousAt = step.atMs

            // The firmware terminates every message with CRLF, not bare LF.
            val payload = step.messages.joinToString("") { "$it\r\n" }.toByteArray(Charsets.ISO_8859_1)
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + mtuPayload, payload.size)
                emit(payload.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    companion object {
        /** ATT_MTU 23 minus the 3-byte notification header. */
        const val MTU_PAYLOAD = 20
    }
}
