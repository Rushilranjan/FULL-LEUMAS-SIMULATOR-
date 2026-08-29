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
     * A new sequence started. Only 1, 2, 4 and 5 are reachable — `SEQ_CYCLE` skips 3.
     *
     * Treat as a hard reset: `lotmgr_start` wipes all lot state, and a mid-run button press
     * re-emits this, so any in-flight animation must be abandoned.
     */
    data class SequenceStarted(val sequence: Int) : McuEvent

    /**
     * A module entered its run phase.
     *
     * May repeat for the same slot with no intervening [ModuleDone]: the warm-macro path emits
     * `RUN` from both the power-on warm-up loop and lot injection. Handling must be idempotent.
     */
    data class ModuleRun(val slot: Int) : McuEvent

    /**
     * A module's run phase ended.
     *
     * This does **not** mean the module is retired — it may still be the source of a later
     * [Move]. It also cannot be distinguished from "waiting on a bottleneck": the MCU shows that
     * as pulsing yellow on the LEDs, but the wire protocol carries no such state.
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
     * The lot has shipped.
     *
     * Fires at the **start** of the 10 s exit swipe, once per button press. Nothing is sent when
     * that swipe finishes.
     */
    data object LotDone : McuEvent

    /** Unparseable or out-of-range input. Logged for the debug screen, otherwise ignored. */
    data class Unknown(val raw: String, val reason: String) : McuEvent
}
