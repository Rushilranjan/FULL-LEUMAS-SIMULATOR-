package com.leumas.robot.protocol

import com.leumas.robot.domain.RobotPhase

/**
 * Mirrors the timing constants in the firmware's `Core/Inc/config.h` (the `SS1_*` block driving
 * `App/solid_seq1.c`, Solid Sequence 1).
 *
 * These are *not* used to predict MCU behaviour — the MCU remains the source of truth and the app
 * only reacts to events (spec §13). They exist so the visuals can be paced to match what the
 * hardware is doing, and so demo mode can replay a realistic trace.
 *
 * The one genuine contract with the firmware lives in [RobotPhase], not here: those phase durations
 * and the firmware's `SS1_ARM_*` block are the same numbers, and the firmware asserts the total.
 */
object Timings {

    /**
     * `SS1_INITIAL_PROCESS_MS` / `SS1_COLLECTION_MS` / `SS1_DUMP_MS` / `SS1_BLEND_RUN_MS` /
     * `SS1_PACKAGING_FINISH_MS` — the fixed 20 s window a module or a carry occupies.
     *
     * Informational; nothing consumes it. The collection and dump windows are the ones that matter
     * to the arm, and those reach it as [RobotPhase.COLLECTING] and [RobotPhase.DUMPING].
     */
    const val PROCESS_MS = 20_000L

    /**
     * The tightest spacing between two consecutive robot cues, and therefore the budget one arm
     * animation must fit inside.
     *
     * The firmware paces a carry off the same phase table the arm animates, then waits
     * `SS1_ARM_HOME_HOLD_MS` (3 s) at rail centre before cueing the next one. So back-to-back
     * carries — `MOVE:2>5` then `MOVE:3>5` — arrive 61.5 s apart against a 58.5 s animation. The
     * 3 s of slack is what absorbs UART and render-tick jitter, so two trips never overlap and the
     * catch-up path in [com.leumas.robot.domain.RobotStateMachine] is never entered in normal
     * running.
     */
    const val TRANSFER_ROBOT_MS = 61_500L

    /**
     * How long the app runs its own "lot shipped" flourish after `LOT:DONE`.
     *
     * This is an app-side presentation choice, not a firmware mirror: the packaging→output flow
     * (`SS1_FLOW_OUTPUT_PASSES`) is drawn on the board strip and finishes *before* `LOT:DONE`
     * fires, and the next cycle begins ~`SS1_CYCLE_GAP_MS` later — so on a multi-cycle run this
     * flourish is superseded by the next cycle rather than running its full length.
     */
    const val EXIT_SWIPE_MS = 20_000L

    /**
     * Default duration of one full robot-transfer animation: 58.5 s.
     *
     * Derived from [RobotPhase] rather than written out, so the phase table stays the single place
     * the arm's timing is defined and this can never drift from it. Roughly 18.5 s of that is
     * travel, pick, place and the trip home; the other 40 s is the arm standing at a module for the
     * collection and the dump, which is what makes the rig's LED changes line up with where the arm
     * actually is. Spec §11 requires this be configurable — it still is, via the phase table.
     */
    val DEFAULT_ROBOT_ANIMATION_MS: Long = RobotPhase.TOTAL_MS

    /**
     * Ceiling on how much the animation may be sped up to catch up with a queued transfer.
     *
     * Kept gentle: the firmware's pacing means a real backlog should not occur, so this only ever
     * runs after a dropped notification or a mid-run restart. A low cap means that when one does
     * happen the arm speeds up smoothly rather than lurching. It degrades to "a little brisker",
     * never to a teleport (spec §10).
     */
    const val MAX_CATCHUP_FACTOR = 1.3f
}
