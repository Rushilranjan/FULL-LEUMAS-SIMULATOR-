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
    const val TRANSFER_ROBOT_MS = 20_000L

    /** `TRANSFER_SWIPE_MS` — blender→packaging / vessel→bottling strip swipe. */
    const val TRANSFER_SWIPE_MS = 10_000L

    /** `EXIT_SWIPE_MS` — the final "lot shipped" swipe, started by `LOT:DONE`. */
    const val EXIT_SWIPE_MS = 10_000L

    /**
     * Default duration of one full robot-transfer animation.
     *
     * Set comfortably under [TRANSFER_ROBOT_MS]. The firmware gives the arm a 20 000 ms budget per
     * trip (change 11.4: ~15 s of transfer plus a ~3 s return-to-home so the arm parks at rail centre
     * after every transfer), and on the solid line it sends `MOVE:1>4` and `MOVE:3>4` exactly that far
     * apart. At 18.5 s the transfer is still deliberately slow and legible for an audience (spec §11),
     * and the ~1.5 s of slack is deliberate (change 12.2): it absorbs the firmware's render-tick /
     * UART jitter so two back-to-back trips never overlap and trigger the catch-up speed-up, which
     * was showing up as a jerk mid-rotation. The arm instead settles at home for a beat before the
     * next cue. Spec §11 requires this be configurable.
     */
    const val DEFAULT_ROBOT_ANIMATION_MS = 18_500L

    /**
     * Ceiling on how much the animation may be sped up to catch up with a queued transfer.
     *
     * Kept gentle: with the animation already near the firmware budget, a real backlog is rare, and
     * a low cap means that when one does happen the arm speeds up smoothly rather than lurching. It
     * degrades to "a little brisker", never to a teleport (spec §10).
     */
    const val MAX_CATCHUP_FACTOR = 1.3f
}
