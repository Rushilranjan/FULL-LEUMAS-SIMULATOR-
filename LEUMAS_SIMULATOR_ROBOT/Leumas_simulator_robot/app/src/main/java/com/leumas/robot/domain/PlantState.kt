package com.leumas.robot.domain

import com.leumas.robot.protocol.SlotMap
import com.leumas.robot.protocol.TransferKind

/**
 * What a module is doing, as far as the MCU has told us.
 *
 * Note the deliberate absence of a WAITING state. The rig's LEDs distinguish "pulsing yellow,
 * blocked by the bottleneck" from "idle red", but the wire protocol carries only RUN and DONE —
 * there is no way to tell them apart from here. Spec §13 forbids inventing process state, so we
 * do not guess.
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
    /**
     * The recipe this sequence is running, looked up from `SEQ:<n>` (change 13.6). Null before the
     * first sequence cue, or for a sequence number we have no recipe for.
     */
    val recipe: Recipe? = null,
    /** Fill state of the bottom-centre progress bar (change 13.7). */
    val cycleProgress: CycleProgress? = null,
    /**
     * Index of the recipe stage the plant was last seen entering. Bookkeeping for
     * [CycleProgress.advance]; -1 before any stage has been entered.
     */
    val stageIndex: Int = -1,
) {
    fun stateOf(slot: Int): ModuleState = moduleStates[slot] ?: ModuleState.UNKNOWN

    val runningSlots: List<Int>
        get() = moduleStates.filterValues { it == ModuleState.RUNNING }.keys.sorted()

    /** One-line description of what the plant is doing, for the status overlay (spec §23). */
    val operationText: String
        get() = when {
            lotComplete -> "LOT COMPLETE"
            currentTransfer != null -> with(currentTransfer) {
                // "PROCESS TRANSFER: MODULE 4 → MODULE 6" overran the task card's single line on
                // device and ellipsised away the destination slot, which is the one number the
                // viewer needs. The shorter verb fits and still distinguishes a strip hand-off from
                // a robot trip (spec §14).
                val verb = if (kind == TransferKind.ROBOT) "TRANSFERRING" else "PROCESS"
                "$verb: MODULE $from → MODULE $to"
            }
            runningSlots.isNotEmpty() ->
                "RUNNING MODULE " + runningSlots.joinToString(", ")
            controllerReady -> "IDLE"
            else -> "WAITING FOR CONTROLLER"
        }
}
