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
         * `solid_seq1_start()` resets everything on the firmware side, and pressing the button mid-run
         * re-emits SEQ:n without gracefully unwinding the previous run. So everything we believed
         * about module states and any in-flight transfer is now stale. The animation controller is
         * aborted separately by the caller.
         */
        is McuEvent.SequenceStarted -> {
            // Change 13.6/13.7: the recipe and the progress bar are both reset here, from the
            // sequence number alone. Recipe.forSequence is a lookup in a hard-coded table mirroring
            // the firmware; nothing is being predicted about what the MCU will do next.
            val recipe = Recipe.forSequence(event.sequence)
            PlantState(
                controllerReady = true,
                currentSequence = event.sequence,
                recipe = recipe,
                cycleProgress = CycleProgress.start(recipe),
            )
        }

        /*
         * Idempotent by construction: the warm-macro path emits RUN twice for the same slot with
         * no DONE in between, so this must be safe to apply repeatedly.
         */
        is McuEvent.ModuleRun -> {
            val (progress, stage) = CycleProgress.advance(
                current = state.cycleProgress,
                recipe = state.recipe,
                slot = event.slot,
                previousStage = state.stageIndex,
            )
            state.copy(
                moduleStates = state.moduleStates + (event.slot to ModuleState.RUNNING),
                lastRunSlot = event.slot,
                lotComplete = false,
                cycleProgress = progress,
                stageIndex = stage,
            )
        }

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
         * Solid Sequence 1 sends this once per CYCLE - four times per run - so it advances the
         * progress bar a segment rather than filling it. stageIndex resets with it so the next
         * cycle first RUN starts the fill from the beginning again.
         *
         * Fires at the START of the exit flow, not at its end — the firmware sends nothing
         * when the swipe finishes. Callers that want the on-screen completion to land with the
         * physical strip should hold for EXIT_SWIPE_MS after this.
         */
        is McuEvent.LotDone -> state.copy(
            lotComplete = true,
            currentTransfer = null,
            cycleProgress = CycleProgress.completeCycle(state.cycleProgress),
            stageIndex = -1,
        )

        is McuEvent.Unknown -> state
    }

    /** Clears the in-flight transfer once its animation finishes. */
    fun clearTransfer(state: PlantState): PlantState =
        if (state.currentTransfer == null) state else state.copy(currentTransfer = null)
}
