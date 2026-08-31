package com.leumas.robot.protocol

/**
 * Mirrors the timing constants in the firmware's `Core/Inc/config.h`.
 *
 * These are *not* used to predict MCU behaviour - the MCU remains the source of truth and the app
 * only reacts to events (spec 13). They exist so the visuals can be paced to match what the
 * hardware is doing, and so the demo/simulator mode can replay a realistic trace.
 *
 * The firmware rewrote its scheduler as Solid Sequence 1 (`App/solid_seq1.c`), which deleted the
 * old `RUN_TIME_MS` / `TRANSFER_ROBOT_MS` / `TRANSFER_SWIPE_MS` / `EXIT_SWIPE_MS` knobs and
 * replaced the one-duration-per-trip model with named `SS1_*` phases. Every constant below now
 * names the `SS1_*` value it mirrors; anything the firmware no longer has, and that nothing here
 * read, was dropped rather than left pointing at a macro that does not exist.
 */
object Timings {

    /**
     * `SS1_COLLECTION_MS` - the robot collecting material at the source module.
     *
     * First half of one carry. The collect animation (13.8) is paced against this.
     */
    const val COLLECTION_MS = 20_000L

    /**
     * `SS1_DUMP_MS` - the robot pouring that material into the blender.
     *
     * Second half of one carry. The pour animation (13.8) is paced against this. Carries 2 s
     * more than [COLLECTION_MS]: that is the jitter margin (change 12.2), parked on the dump
     * because requirement 15.2 fixes the animation length.
     */
    const val DUMP_MS = 22_000L

    /**
     * One complete carry: `SS1_COLLECTION_MS + SS1_DUMP_MS`.
     *
     * This is the window a single `MOVE` cue covers - travel, pick, place and the return home are
     * all this app's animation - and therefore also the spacing between consecutive `MOVE` cues.
     * Replaces the deleted `TRANSFER_ROBOT_MS`.
     *
     * 42 s against a 40 s [DEFAULT_ROBOT_ANIMATION_MS], restoring the ~2 s of slack change 12.2
     * introduced. With the two exactly equal the next cue can arrive a render-tick before the
     * arm reaches home, queueing a transfer and triggering [MAX_CATCHUP_FACTOR] mid-rotation.
     */
    const val CARRY_MS = COLLECTION_MS + DUMP_MS

    /**
     * Blender -> packaging strip hand-off, replacing the deleted `TRANSFER_SWIPE_MS`.
     *
     * Derived, not a firmware constant: the flow animator fills LEDs 14..16 one at a time and
     * repeats, so one pass is `span * SS1_FLOW_STEP_MS + SS1_FLOW_BLANK_MS` = 3*700 + 1200 = 3300 ms,
     * and `SS1_FLOW_XFER_PASSES` is 4. The conveyor band here must last exactly as long or the two
     * visibly drift apart.
     */
    const val TRANSFER_SWIPE_MS = 13_200L

    /**
     * The final "lot shipped" flow, started by `LOT:DONE`.
     *
     * Derived the same way over the exit span, LEDs 17..21: one pass is 5*700 + 1200 = 4700 ms and
     * `SS1_FLOW_OUTPUT_PASSES` is 2, so 9400 ms. This was 20 s while the firmware had a dedicated
     * `EXIT_SWIPE_MS`; the animator that replaced it is less than half that, so the progress
     * reported by `RobotViewModel.exitSwipeProgress` would otherwise run at half the strip's pace.
     */
    const val EXIT_SWIPE_MS = 9_400L

    /**
     * Default duration of one full robot-transfer animation.
     *
     * Change 13.8 raised this from 18.5 s so the arm has room for a legible material-collection and
     * pour on top of the travel, lift, retract and return-to-home legs. Change 15.2 raised it again,
     * 32 s -> 40 s: each reach now goes through a three-coordinate path (transit, approach, target)
     * and comes to rest at the via point, which the requirement asks for explicitly and which cannot
     * be done gracefully in the old budget.
     *
     * The firmware moved to meet this rather than the other way round: `SS1_COLLECTION_MS` and
     * `SS1_DUMP_MS` are 20 s each, so [CARRY_MS] is 40 s. This constant and those two must move
     * together. Spec 11 requires it be configurable.
     */
    const val DEFAULT_ROBOT_ANIMATION_MS = 40_000L

    /**
     * Ceiling on how much the animation may be sped up to catch up with a queued transfer.
     *
     * Kept gentle: the animation sits ~2 s under the firmware carry, so a real backlog is rare. A
     * low cap means that when one does happen the arm speeds up smoothly rather than lurching. It
     * degrades to "a little brisker", never to a teleport (spec 10).
     */
    const val MAX_CATCHUP_FACTOR = 1.3f
}
