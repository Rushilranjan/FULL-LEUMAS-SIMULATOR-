package com.leumas.robot.protocol

/**
 * Mirrors the timing constants in the firmware's `Core/Inc/config.h`.
 *
 * These are *not* used to predict MCU behaviour — the MCU remains the source of truth and the app
 * only reacts to events (spec §13). They exist so the visuals can be paced to match what the
 * hardware is doing, and so the demo/simulator mode can replay a realistic trace.
 */
object Timings {

    /** `RUN_TIME_MS` — how long a module stays green. */
    const val RUN_MS = 20_000L

    /** `TRANSFER_ROBOT_MS` — one robot trip. Also the budget our animation must fit inside. */
    const val TRANSFER_ROBOT_MS = 42_000L

    /**
     * `TRANSFER_SWIPE_MS` — blender→packaging / vessel→bottling strip hand-off.
     *
     * The firmware fills this span one status LED at a time, evenly across the whole window, so
     * the conveyor band here must last exactly as long or the two visibly drift apart.
     */
    const val TRANSFER_SWIPE_MS = 15_000L

    /** `EXIT_SWIPE_MS` — the final "lot shipped" flow, started by `LOT:DONE`. */
    const val EXIT_SWIPE_MS = 20_000L

    /**
     * Default duration of one full robot-transfer animation.
     *
     * Set comfortably under [TRANSFER_ROBOT_MS]. Change 13.8 raised this from 18.5 s so the arm has
     * room for a legible material-collection and pour on top of the travel, lift, retract and
     * return-to-home legs. Change 15.2 raised it again, 32 s -> 40 s: each reach now goes through a
     * three-coordinate path (transit, approach, target) and comes to rest at the via point, which
     * the requirement asks for explicitly and which cannot be done gracefully in the old budget. The firmware's per-trip budget moved with it (20 s -> 34 s in
     * `Core/Inc/config.h`), keeping the ~2 s of slack introduced by change 12.2: that slack absorbs
     * the controller's render-tick and UART jitter so two back-to-back trips never overlap and
     * trigger the catch-up speed-up, which used to show up as a jerk mid-rotation. The arm settles
     * at home for a beat before the next cue instead.
     *
     * The user intends to retune the firmware side per sequence later; this constant and
     * `TRANSFER_ROBOT_MS` must move together. Spec §11 requires it be configurable.
     */
    const val DEFAULT_ROBOT_ANIMATION_MS = 40_000L

    /**
     * Ceiling on how much the animation may be sped up to catch up with a queued transfer.
     *
     * Kept gentle: with the animation already near the firmware budget, a real backlog is rare, and
     * a low cap means that when one does happen the arm speeds up smoothly rather than lurching. It
     * degrades to "a little brisker", never to a teleport (spec §10).
     */
    const val MAX_CATCHUP_FACTOR = 1.3f
}
