package com.leumas.robot.domain

import com.leumas.robot.protocol.McuEvent

/**
 * Folds MCU events into [PlantState]. Pure — no clock of its own, no side effects — so the whole
 * plant model is unit-testable by replaying a wire trace.
 *
 * The MCU is the source of truth (spec §31). Nothing here may originate state; every field change
 * traces back to a received message.
 */
object PlantStateReducer {

    fun reduce(state: PlantState, event: McuEvent, nowMs: Long): PlantState = when (event) {

        is McuEvent.Ready -> state.copy(controllerReady = true)

        /*
         * A sequence start is DESTRUCTIVE, not additive.
         *
         * `solid_seq1_start()` clears all state on the firmware side, and pressing the button
         * mid-run re-emits SEQ:1 without gracefully unwinding the previous run. So everything we
         * believed about module states and any in-flight transfer is now stale. The animation
         * controller is aborted separately by the caller. Note this fires only on a real sequence
         * start, never on a plain cycle boundary within a run.
         */
        is McuEvent.SequenceStarted -> PlantState(
            controllerReady = true,
            currentSequence = event.sequence,
        )

        /*
         * Kept idempotent: the firmware de-dupes state changes so a slot is not normally re-RUN
         * without an intervening DONE, but applying RUN repeatedly must stay safe regardless.
         */
        is McuEvent.ModuleRun -> state.copy(
            moduleStates = state.moduleStates + (event.slot to ModuleState.RUNNING),
            lastRunSlot = event.slot,
            lotComplete = false,
        )

        is McuEvent.ModuleDone -> state.copy(
            moduleStates = state.moduleStates + (event.slot to ModuleState.DONE),
        )

        is McuEvent.Move -> state.copy(
            currentTransfer = ActiveTransfer(event.from, event.to, event.kind, nowMs),
            moduleStates = state.moduleStates +
                (event.from to ModuleState.TRANSFER_SOURCE) +
                (event.to to ModuleState.TRANSFER_DESTINATION),
        )

        /*
         * Fires once per cycle, after that cycle's packaging→output strip flow — four times over a
         * full run. The next cycle's RUN cues (which clear lotComplete) follow shortly after, so
         * on a multi-cycle run this state is normally short-lived rather than terminal.
         */
        is McuEvent.LotDone -> state.copy(
            lotComplete = true,
            currentTransfer = null,
        )

        is McuEvent.Unknown -> state
    }

    /** Clears the in-flight transfer once its animation finishes. */
    fun clearTransfer(state: PlantState): PlantState =
        if (state.currentTransfer == null) state else state.copy(currentTransfer = null)
}
