package com.leumas.robot.sim

/** One scripted moment in a replayed trace. */
data class TraceStep(val atMs: Long, val messages: List<String>)

/**
 * Scripted wire traces for development without hardware.
 *
 * Timings are derived from the firmware's `Core/Inc/config.h` constants (`RUN_TIME_MS` 20 s,
 * `TRANSFER_ROBOT_MS` 20 s — change 11.4: ~17 s transfer + ~3 s return-to-home,
 * `TRANSFER_SWIPE_MS` / `EXIT_SWIPE_MS` 10 s). Robot `MOVE`s are therefore spaced 20 s apart so the
 * one shared arm never overlaps trips or triggers catch-up — the animation budget
 * ([Timings.DEFAULT_ROBOT_ANIMATION_MS], 18.5 s) fits inside that with ~1.5 s to spare, so the arm
 * settles at home before the next cue instead of speeding up. They are *representative*, not exact:
 * the real controller has a 25 ms render tick plus blocking UART time, so treat real hardware as
 * the arbiter.
 *
 * Messages inside one [TraceStep] are emitted back-to-back, exactly as the firmware does when
 * several `ble_*` calls land in the same tick.
 */
object WireTrace {

    /**
     * Sequence 1 — the bottleneck story, batch A.
     *
     * Note the duplicate `RUN:1`: the warm-up loop lights every macro, then lot injection emits
     * `RUN` again for the macro its batch claims. Any handler that assumes RUN/DONE alternate
     * strictly will break here.
     */
    val SEQ1: List<TraceStep> = listOf(
        TraceStep(0, listOf("SEQ:1", "RUN:1", "RUN:2", "RUN:1", "RUN:3")),
        TraceStep(20_000, listOf("DONE:1", "DONE:3", "DONE:2")),
        TraceStep(20_040, listOf("MOVE:1>4")),    // robot, 20 s
        TraceStep(40_040, listOf("MOVE:3>4")),    // robot, 20 s — arrives exactly 20 s after the last
        TraceStep(60_040, listOf("RUN:4")),
        TraceStep(80_040, listOf("DONE:4")),
        TraceStep(80_080, listOf("MOVE:4>6")),    // strip swipe, 10 s — must NOT move the arm
        TraceStep(90_080, listOf("RUN:6")),
        TraceStep(110_080, listOf("DONE:6")),
        TraceStep(110_120, listOf("LOT:DONE")),   // start of the 10 s exit swipe
    )

    /** Sequence 2 — two blenders, so both batches flow with no bottleneck wait. */
    val SEQ2: List<TraceStep> = listOf(
        TraceStep(0, listOf("SEQ:2", "RUN:1", "RUN:2", "RUN:1", "RUN:3")),
        TraceStep(20_000, listOf("DONE:1", "DONE:2", "DONE:3")),
        TraceStep(20_040, listOf("MOVE:1>4")),    // robot
        TraceStep(40_040, listOf("MOVE:3>4")),    // robot, +20 s
        TraceStep(60_040, listOf("RUN:4")),       // blender1 fed
        TraceStep(60_080, listOf("MOVE:2>5")),    // robot, +20 s after 3>4
        TraceStep(80_040, listOf("DONE:4")),
        TraceStep(80_080, listOf("MOVE:4>6")),    // blender1 -> packaging swipe
        TraceStep(80_120, listOf("MOVE:3>5")),    // robot, +20 s after 2>5
        TraceStep(100_120, listOf("RUN:5")),      // blender2 fed
        TraceStep(120_120, listOf("DONE:5")),
        TraceStep(120_160, listOf("MOVE:5>6")),   // blender2 -> packaging swipe
        TraceStep(130_160, listOf("RUN:6")),      // packaging runs once both blenders are in
        TraceStep(150_160, listOf("DONE:6")),
        TraceStep(150_200, listOf("LOT:DONE")),
    )

    /** Sequence 4 — the liquid line with a single vessel. Exercises slots 7..11. */
    val SEQ4: List<TraceStep> = listOf(
        TraceStep(0, listOf("SEQ:4", "RUN:7", "RUN:8", "RUN:7")),
        TraceStep(20_000, listOf("DONE:7", "DONE:8")),
        TraceStep(20_040, listOf("MOVE:7>9")),    // robot, 20 s
        TraceStep(40_040, listOf("RUN:9")),
        TraceStep(60_040, listOf("DONE:9")),
        TraceStep(60_080, listOf("MOVE:9>11")),   // strip swipe — no arm
        TraceStep(70_080, listOf("RUN:11")),
        TraceStep(90_080, listOf("DONE:11")),
        TraceStep(90_120, listOf("LOT:DONE")),
    )

    /** Sequence 5 — two vessels in parallel. Exercises slot 10. */
    val SEQ5: List<TraceStep> = listOf(
        TraceStep(0, listOf("SEQ:5", "RUN:7", "RUN:8")),
        TraceStep(20_000, listOf("DONE:7", "DONE:8")),
        TraceStep(20_040, listOf("MOVE:7>9")),    // robot
        TraceStep(40_040, listOf("MOVE:8>10")),   // robot, +20 s
        TraceStep(60_040, listOf("RUN:9", "RUN:10")),
        TraceStep(80_040, listOf("DONE:9", "DONE:10")),
        TraceStep(80_080, listOf("MOVE:9>11")),   // vessel1 -> bottling swipe
        TraceStep(90_080, listOf("MOVE:10>11")),  // vessel2 -> bottling swipe
        TraceStep(100_080, listOf("RUN:11")),
        TraceStep(120_080, listOf("DONE:11")),
        TraceStep(120_120, listOf("LOT:DONE")),
    )

    /**
     * Deliberately hostile input. Spec §20 requires the app stay stable through all of this, and
     * the debug log should show each line being rejected rather than the app misbehaving.
     */
    val HOSTILE: List<TraceStep> = listOf(
        TraceStep(0, listOf("HELLO", "ABC", "INVALID DATA")),
        TraceStep(500, listOf("MOVE:", "MOVE:2", "RUN:", "DONE:", "SEQ:")),
        TraceStep(1_000, listOf("MOVE:99>1", "RUN:0", "RUN:12", "DONE:255")),
        TraceStep(1_500, listOf("run:3", "RUN: 3", "MOVE:2 > 6")),
        TraceStep(2_000, listOf("SEQ:1", "RUN:3")),   // still works afterwards
    )

    val ALL: Map<String, List<TraceStep>> = linkedMapOf(
        "Sequence 1 — bottleneck" to SEQ1,
        "Sequence 2 — two blenders" to SEQ2,
        "Sequence 4 — liquid, one vessel" to SEQ4,
        "Sequence 5 — liquid, two vessels" to SEQ5,
        "Malformed input" to HOSTILE,
    )
}
