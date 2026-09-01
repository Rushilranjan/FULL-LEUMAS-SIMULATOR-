package com.leumas.robot.domain

import com.leumas.robot.protocol.SlotMap
import com.leumas.robot.protocol.TransferKind

/**
 * What a module is doing, as far as the MCU has told us.
 *
 * The states here mirror what the wire protocol can actually tell us — RUN, DONE and the two
 * transfer directions. Solid Sequence 1 uses only OFF / RUNNING (blue) / READY (green) on the
 * rig and carries no "waiting" state, so there is nothing here to represent one. Spec §13 forbids
 * inventing process state, so we never guess at anything the MCU has not reported.
 */
enum class ModuleState {
    /** Nothing heard about this slot since the last sequence start. */
    UNKNOWN,

    /** Between RUN and DONE. */
    RUNNING,

    /** Run phase finished. NOT "retired" — the slot may still be the source of a later MOVE. */
    DONE,

    /** Currently the source of an in-flight transfer. */
    TRANSFER_SOURCE,

    /** Currently the destination of an in-flight transfer. */
    TRANSFER_DESTINATION,
}

/** An in-flight transfer, robot-carried or conveyor-carried. */
data class ActiveTransfer(
    val from: Int,
    val to: Int,
    val kind: TransferKind,
    val startedAtMs: Long,
) {
    val fromName: String get() = SlotMap.displayName(from)
    val toName: String get() = SlotMap.displayName(to)
}

/**
 * Everything the app knows about the plant, derived solely from MCU events.
 *
 * Immutable so the UI can diff cheaply and so [PlantStateReducer] stays a pure function.
 */
data class PlantState(
    val controllerReady: Boolean = false,
    val currentSequence: Int? = null,
    val moduleStates: Map<Int, ModuleState> = emptyMap(),
    val currentTransfer: ActiveTransfer? = null,
    val lotComplete: Boolean = false,
    /** Slot the MCU most recently reported as running, for the status line. */
    val lastRunSlot: Int? = null,
) {
    fun stateOf(slot: Int): ModuleState = moduleStates[slot] ?: ModuleState.UNKNOWN

    val runningSlots: List<Int>
        get() = moduleStates.filterValues { it == ModuleState.RUNNING }.keys.sorted()

    /** One-line description of what the plant is doing, for the status overlay (spec §23). */
    val operationText: String
        get() = when {
            lotComplete -> "LOT COMPLETE"
            currentTransfer != null -> with(currentTransfer) {
                val verb = if (kind == TransferKind.ROBOT) "TRANSFERRING" else "PROCESS TRANSFER"
                "$verb: MODULE $from → MODULE $to"
            }
            runningSlots.isNotEmpty() ->
                "RUNNING MODULE " + runningSlots.joinToString(", ")
            controllerReady -> "IDLE"
            else -> "WAITING FOR CONTROLLER"
        }
}
