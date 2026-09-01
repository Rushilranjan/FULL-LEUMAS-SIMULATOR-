package com.leumas.robot.protocol

/**
 * One decoded line of the MCU's cue protocol.
 *
 * The MCU is transmit-only and is the source of truth (spec §31). These events are the *only*
 * thing that may drive the visualisation — the app must never synthesise process state of its own.
 */
sealed interface McuEvent {

    /**
     * Controller powered up and idle.
     *
     * Sent exactly once from `app_init()` at boot and **never re-sent**, so an app that connects
     * later will never see it. Never gate the UI on having received this.
     */
    data object Ready : McuEvent

    /**
     * A new sequence started. The current firmware only emits `SEQ:1` (Solid Sequence 1), and
     * only **once per run** — the four production cycles do NOT each re-emit it.
     *
     * Treat as a hard reset: `solid_seq1_start()` clears all state, and a mid-run button press
     * re-emits this, so any in-flight animation must be abandoned. A plain cycle boundary is not
     * a reset and carries no `SEQ`.
     */
    data class SequenceStarted(val sequence: Int) : McuEvent

    /**
     * A module entered its run phase (blue on the rig).
     *
     * The firmware de-dupes state changes, so a `RUN` is not normally repeated for the same slot
     * without an intervening [ModuleDone] — but handling is kept idempotent anyway, so a repeat
     * from a future firmware, or a bare recharge-complete `RUN`, can never desync the model.
     */
    data class ModuleRun(val slot: Int) : McuEvent

    /**
     * A module's run phase ended: it is now green — completed, ready for the robot to collect.
     *
     * This does **not** mean the module is retired — it may still be the source of a later
     * [Move], and a slot can cycle green→blue→green within one sequence (the blender does:
     * ready-to-receive, running, ready-to-ship).
     */
    data class ModuleDone(val slot: Int) : McuEvent

    /**
     * Material is being transferred between two slots.
     *
     * [kind] is inferred from the destination by [SlotMap.transferKind]; the wire message itself
     * is identical for robot trips and strip swipes.
     */
    data class Move(val from: Int, val to: Int, val kind: TransferKind) : McuEvent

    /**
     * A production cycle has shipped its lot.
     *
     * Fires **once per cycle** — four times over a full Solid Sequence 1 run, at the end of each
     * cycle after the packaging→output strip flow. It is not the end of the whole run; the next
     * cycle's `RUN` cues follow ~`SS1_CYCLE_GAP_MS` later (with no `SEQ`). Nothing is sent to mark
     * the very end of the fourth cycle beyond its own `LOT:DONE`.
     */
    data object LotDone : McuEvent

    /** Unparseable or out-of-range input. Logged for the debug screen, otherwise ignored. */
    data class Unknown(val raw: String, val reason: String) : McuEvent
}
