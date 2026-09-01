package com.leumas.robot.sim

/** One scripted moment in a replayed trace. */
data class TraceStep(val atMs: Long, val messages: List<String>)

/**
 * Scripted wire traces for development without hardware.
 *
 * These mirror what **Solid Sequence 1** (`App/solid_seq1.c`) actually puts on the wire. The
 * firmware is transmit-only and the app only ever reacts to it (spec §13, §31), so a trace is the
 * whole contract: get the message order and spacing right and the visuals follow.
 *
 * Two facts pace everything:
 *  - The two robot carries per half-cycle (`MOVE:2>5` then `MOVE:3>5`) are **61.5 s apart**. The
 *    firmware paces a carry off the same phase table the arm animates
 *    ([Timings.DEFAULT_ROBOT_ANIMATION_MS], 58.5 s) and then waits `SS1_ARM_HOME_HOLD_MS` (3 s) at
 *    rail centre, so the arm settles at home before the next cue instead of speeding up.
 *  - The blender→packaging and packaging→output flows are drawn on the **board LED strip only**;
 *    the firmware sends no `MOVE` for them, so the app never animates a conveyor or the arm for
 *    them — exactly as [SlotMap.transferKind] intends.
 *
 * These offsets are **generated from the firmware's own constants**, not estimated: the model in
 * the scratchpad parses `Core/Inc/config.h` and walks the step chain in `App/solid_seq1.c`. They
 * are still not exact to the millisecond — the real controller has a render tick plus blocking
 * UART time — so treat real hardware as the arbiter. Messages inside one [TraceStep] are emitted
 * back-to-back, as the firmware does when several `ble_*` calls land in the same tick, and are
 * listed in the order the firmware's step chain emits them.
 */
object WireTrace {

    /**
     * Solid Sequence 1 — one full production cycle (316.7 s), then the start of the next.
     *
     * The run emits `SEQ:1` **once** and then loops four cycles (this trace shows one and a bit);
     * note the absence of a second `SEQ:1` at 316.7 s — a new cycle is NOT a sequence restart, so
     * the app must not treat the cycle boundary as a hard reset.
     *
     * `LOT:DONE` fires **once per cycle** (four times per run), not once per run. The blender
     * (slot 5) legitimately cycles green→blue→green: ready-to-receive, then running, then
     * ready-to-ship. The two recharge completions surface as a bare `RUN:2` (102.3 s, macro
     * recharged) and `RUN:3` (150.4 s, gravimetric recharged) with no preceding `MOVE`.
     *
     * The long silences are the point: between `MOVE:2>5` at 20 s and `DONE:3` at 64.1 s the arm is
     * travelling to the macro, standing there for the 20 s collection, crossing to the blender and
     * starting to pour. Nothing on the wire should change while the arm is mid-carry — that is what
     * keeps the rig's LEDs and the arm agreeing about where it is.
     */
    val SOLID_SEQ1: List<TraceStep> = listOf(
        TraceStep(0, listOf("SEQ:1", "RUN:2", "RUN:3")),   // Module 2 + 3 running
        TraceStep(20_000, listOf("DONE:2", "MOVE:2>5")),   // macro ready; robot carries it to the blender
        TraceStep(64_100, listOf("DONE:3")),               // gravimetric finishes mid-dump
        TraceStep(74_050, listOf("RUN:5")),                // pour done, arm retracting -> blender runs
        TraceStep(81_500, listOf("MOVE:3>5")),             // robot carries the gravimetric
        TraceStep(102_300, listOf("RUN:2")),               // macro recharge complete -> running again
        TraceStep(107_800, listOf("DONE:5")),              // blender ready to receive
        TraceStep(135_550, listOf("RUN:5")),               // blender running (both materials in)
        TraceStep(150_350, listOf("RUN:3")),               // gravimetric recharge complete -> running
        TraceStep(155_550, listOf("DONE:5")),              // blender ready to ship -> strip flow 14..16
        TraceStep(168_750, listOf("RUN:6", "DONE:2", "MOVE:2>5")),  // packaging runs; 2nd macro carry
        TraceStep(212_850, listOf("DONE:3")),
        TraceStep(222_800, listOf("RUN:5")),
        TraceStep(230_250, listOf("MOVE:3>5")),            // 2nd gravimetric carry
        TraceStep(256_550, listOf("DONE:5")),
        TraceStep(284_300, listOf("RUN:5")),               // packaging finishing
        TraceStep(304_300, listOf("DONE:6")),              // packaging done -> strip flow 17..21 (output)
        TraceStep(313_700, listOf("LOT:DONE")),            // one cycle shipped
        TraceStep(316_700, listOf("RUN:2", "RUN:3")),      // next cycle begins — NB no SEQ:1
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
        "Solid Sequence 1" to SOLID_SEQ1,
        "Malformed input" to HOSTILE,
    )
}
