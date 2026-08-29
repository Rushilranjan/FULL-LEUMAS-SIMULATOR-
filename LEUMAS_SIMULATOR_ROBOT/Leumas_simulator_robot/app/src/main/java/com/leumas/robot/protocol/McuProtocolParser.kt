package com.leumas.robot.protocol

/**
 * Turns one assembled line into an [McuEvent].
 *
 * Contract: **never throws, for any input.** Spec §20 requires the app stay stable in the face of
 * `HELLO`, `MOVE:`, `MOVE:2`, `RUN:`, `INVALID DATA` and anything else that turns up on the wire.
 * Every unrecognised or out-of-range line becomes [McuEvent.Unknown] with a reason, which the
 * debug screen surfaces.
 *
 * Matching is exact and case-sensitive, matching the firmware's fixed `printf` formats in
 * `App/ble.c`. We deliberately do not accept loose variants: silently tolerating a message the
 * firmware cannot actually produce would hide a real protocol mismatch.
 */
object McuProtocolParser {

    fun parse(line: String): McuEvent = when {
        line == "READY" -> McuEvent.Ready

        // Checked before the SEQ/RUN/DONE prefixes so it can never be mistaken for one of them.
        line == "LOT:DONE" -> McuEvent.LotDone

        line.startsWith(PREFIX_SEQ) -> parseSequence(line)
        line.startsWith(PREFIX_RUN) -> parseSlot(line, PREFIX_RUN, McuEvent::ModuleRun)
        line.startsWith(PREFIX_DONE) -> parseSlot(line, PREFIX_DONE, McuEvent::ModuleDone)
        line.startsWith(PREFIX_MOVE) -> parseMove(line)

        else -> McuEvent.Unknown(line, "unrecognised message")
    }

    private fun parseSequence(line: String): McuEvent {
        val n = line.removePrefix(PREFIX_SEQ).toIntOrNull()
            ?: return McuEvent.Unknown(line, "SEQ payload is not a number")
        // Not range-checked against {1,2,4,5}: a future firmware may add sequences, and showing
        // an unexpected sequence number is more honest than discarding it.
        return McuEvent.SequenceStarted(n)
    }

    private fun parseSlot(
        line: String,
        prefix: String,
        build: (Int) -> McuEvent,
    ): McuEvent {
        val slot = line.removePrefix(prefix).toIntOrNull()
            ?: return McuEvent.Unknown(line, "${prefix.dropLast(1)} payload is not a number")
        if (!SlotMap.isValidSlot(slot)) {
            return McuEvent.Unknown(line, "slot $slot out of range 1..${SlotMap.MAX_SLOT}")
        }
        return build(slot)
    }

    private fun parseMove(line: String): McuEvent {
        val body = line.removePrefix(PREFIX_MOVE)
        val sep = body.indexOf('>')
        if (sep <= 0 || sep == body.lastIndex) {
            return McuEvent.Unknown(line, "MOVE is not of the form a>b")
        }
        val from = body.substring(0, sep).toIntOrNull()
            ?: return McuEvent.Unknown(line, "MOVE source is not a number")
        val to = body.substring(sep + 1).toIntOrNull()
            ?: return McuEvent.Unknown(line, "MOVE destination is not a number")
        if (!SlotMap.isValidSlot(from) || !SlotMap.isValidSlot(to)) {
            return McuEvent.Unknown(line, "MOVE $from>$to outside 1..${SlotMap.MAX_SLOT}")
        }
        return McuEvent.Move(from, to, SlotMap.transferKind(to))
    }

    private const val PREFIX_SEQ = "SEQ:"
    private const val PREFIX_RUN = "RUN:"
    private const val PREFIX_DONE = "DONE:"
    private const val PREFIX_MOVE = "MOVE:"
}
