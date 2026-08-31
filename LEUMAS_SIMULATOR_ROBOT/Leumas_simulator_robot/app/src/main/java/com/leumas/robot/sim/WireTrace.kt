package com.leumas.robot.sim

/** One scripted moment in a replayed trace. */
data class TraceStep(val atMs: Long, val messages: List<String>)

/**
 * Scripted wire traces for development without hardware.
 *
 * STALE - these traces predate the firmware's Solid Sequence 1 rewrite and have NOT been
 * rebuilt yet. They still script the old world in three ways:
 *
 *  - sequences 2, 4 and 5 no longer exist; `seq` now accepts only 1
 *  - the slot mapping changed: SS1 cues `MOVE:2>5` and `MOVE:3>5`, not `MOVE:1>4` / `3>4`
 *  - `MOVE` cues are spaced 42 s here; the firmware carry is now exactly 40 s
 *    ([com.leumas.robot.protocol.Timings.CARRY_MS]), and the swipe/exit windows moved too
 *    (blender->packaging 15 s -> 13.2 s, exit 20 s -> 9.4 s)
 *
 * Simulator-only, so nothing on the rig depends on it, but a replay will not match real
 * hardware until SOLID_1 is rewritten against the SS1 step chain and the other traces are
 * deleted. See `App/solid_seq1.c` for the authoritative order of cues. Each trace runs a **complete** sequence, from the
 * opening `SEQ:n` through to `LOT:DONE`, so the whole animation can be reviewed end to end without
 * hardware.
 *
 * They are *representative*, not exact: the real controller has a 25 ms render tick plus blocking
 * UART time, so treat real hardware as the arbiter.
 *
 * Messages inside one [TraceStep] are emitted back-to-back, exactly as the firmware does when
 * several `ble_*` calls land in the same tick.
 */
object WireTrace {

    /**
     * Sequence 1 — the bottleneck story: one blender, so the two feeds queue for it.
     *
     * Note the duplicate `RUN:1`: the warm-up loop lights every macro, then lot injection emits
     * `RUN` again for the macro its batch claims. Any handler that assumes RUN/DONE alternate
     * strictly will break here.
     */
    val SEQ1: List<TraceStep> = listOf(
        TraceStep(0, listOf("SEQ:1", "RUN:1", "RUN:2", "RUN:1", "RUN:3")),
        TraceStep(20_000, listOf("DONE:1", "DONE:3", "DONE:2")),
        TraceStep(20_040, listOf("MOVE:1>4")),     // robot, 34 s
        TraceStep(62_040, listOf("MOVE:3>4")),     // robot, exactly one trip later
        TraceStep(104_040, listOf("RUN:4")),       // blender fed by both feeds
        TraceStep(124_040, listOf("DONE:4")),
        TraceStep(124_080, listOf("MOVE:4>6")),    // strip swipe, 15 s — must NOT move the arm
        TraceStep(139_080, listOf("RUN:6")),
        TraceStep(159_080, listOf("DONE:6")),
        TraceStep(159_120, listOf("LOT:DONE")),    // start of the 20 s exit flow
    )

    /** Sequence 2 — two blenders, so both batches flow with no bottleneck wait. */
    val SEQ2: List<TraceStep> = listOf(
        TraceStep(0, listOf("SEQ:2", "RUN:1", "RUN:2", "RUN:1", "RUN:3")),
        TraceStep(20_000, listOf("DONE:1", "DONE:2", "DONE:3")),
        TraceStep(20_040, listOf("MOVE:1>4")),     // robot
        TraceStep(62_040, listOf("MOVE:3>4")),     // robot, +42 s
        TraceStep(104_040, listOf("RUN:4")),       // blender1 fed
        TraceStep(104_080, listOf("MOVE:2>5")),    // robot, +42 s after 3>4
        TraceStep(124_040, listOf("DONE:4")),
        TraceStep(124_080, listOf("MOVE:4>6")),    // blender1 -> packaging swipe, 15 s
        TraceStep(146_080, listOf("MOVE:3>5")),    // robot, +42 s after 2>5
        TraceStep(188_080, listOf("RUN:5")),       // blender2 fed
        TraceStep(208_080, listOf("DONE:5")),
        TraceStep(208_120, listOf("MOVE:5>6")),    // blender2 -> packaging swipe; the strip mover
                                                   // is long free by now, so this is not delayed
        TraceStep(223_120, listOf("RUN:6")),       // packaging runs once both blenders are in
        TraceStep(243_120, listOf("DONE:6")),
        TraceStep(243_160, listOf("LOT:DONE")),
    )

    /** Sequence 4 — the liquid line with a single vessel. Exercises slots 7..11. */
    val SEQ4: List<TraceStep> = listOf(
        TraceStep(0, listOf("SEQ:4", "RUN:7", "RUN:8", "RUN:7")),
        TraceStep(20_000, listOf("DONE:7", "DONE:8")),
        TraceStep(20_040, listOf("MOVE:7>9")),     // robot, 42 s
        TraceStep(62_040, listOf("RUN:9")),
        TraceStep(82_040, listOf("DONE:9")),
        TraceStep(82_080, listOf("MOVE:9>11")),    // strip swipe, 15 s — no arm
        TraceStep(97_080, listOf("RUN:11")),
        TraceStep(117_080, listOf("DONE:11")),
        TraceStep(117_120, listOf("LOT:DONE")),
    )

    /** Sequence 5 — two vessels in parallel. Exercises slot 10. */
    val SEQ5: List<TraceStep> = listOf(
        TraceStep(0, listOf("SEQ:5", "RUN:7", "RUN:8")),
        TraceStep(20_000, listOf("DONE:7", "DONE:8")),
        TraceStep(20_040, listOf("MOVE:7>9")),     // robot
        TraceStep(62_040, listOf("MOVE:8>10", "RUN:9")),  // robot, +42 s; vessel1 starts as the
                                                   // first trip lands
        TraceStep(82_040, listOf("DONE:9")),
        TraceStep(82_080, listOf("MOVE:9>11")),    // vessel1 -> bottling swipe, 15 s
        TraceStep(104_040, listOf("RUN:10")),      // vessel2 fed by the second trip
        TraceStep(124_040, listOf("DONE:10")),
        TraceStep(124_080, listOf("MOVE:10>11")),  // strip mover free by now
        TraceStep(139_080, listOf("RUN:11")),
        TraceStep(159_080, listOf("DONE:11")),
        TraceStep(159_120, listOf("LOT:DONE")),
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
