package com.leumas.robot.protocol

/**
 * Reassembles complete protocol lines out of arbitrarily chopped BLE notification payloads.
 *
 * This is mandatory, not defensive polish. The BT05 negotiates a 23-byte MTU (20-byte payload),
 * and the firmware's `HAL_UART_Transmit` calls are back-to-back and unbuffered, so both of these
 * are routine:
 *
 *  - one message split across packets:  `"MOV"` then `"E:2>6\r\n"`
 *  - several messages in one packet:    `"SEQ:1\r\nRUN:1\r\nRUN:2\r\n"`
 *
 * Note the firmware terminates with **CRLF**, not the bare LF that spec §3/§19 and the firmware
 * README both claim. Splitting on `'\n'` alone leaves a trailing `'\r'` on every line and makes
 * every exact-match parse fail, so trimming here is load-bearing.
 *
 * Not thread-safe; confine it to a single collector coroutine.
 */
class LineAssembler(
    private val maxBufferedChars: Int = MAX_BUFFERED_CHARS,
) {
    private val buffer = StringBuilder()

    /** Number of times the buffer was discarded for growing without a terminator. */
    var overflowDiscards: Int = 0
        private set

    /**
     * Feed one notification payload; returns whatever complete, non-empty lines it completed.
     *
     * Bytes are decoded as ISO-8859-1 so the mapping byte→char is total and lossless. The
     * protocol is pure ASCII, and anything that is not stays intact for the debug log rather
     * than being mangled into replacement characters.
     */
    fun append(chunk: ByteArray): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(String(chunk, Charsets.ISO_8859_1))

        val lines = mutableListOf<String>()
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline < 0) break
            val raw = buffer.substring(0, newline)
            buffer.delete(0, newline + 1)
            // trim() removes the CR as well as any stray spaces the module might insert.
            val line = raw.trim()
            if (line.isNotEmpty()) lines.add(line)
        }

        // A stream of garbage with no terminator must not grow without bound.
        if (buffer.length > maxBufferedChars) {
            buffer.setLength(0)
            overflowDiscards++
        }
        return lines
    }

    /** Drop any partial line. Call on disconnect so a fragment cannot bleed into the next session. */
    fun reset() {
        buffer.setLength(0)
    }

    companion object {
        const val MAX_BUFFERED_CHARS = 4096
    }
}
