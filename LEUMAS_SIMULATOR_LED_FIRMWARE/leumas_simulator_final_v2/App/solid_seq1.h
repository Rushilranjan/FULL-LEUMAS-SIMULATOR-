/**
 * solid_seq1.h — Solid Sequence 1, the hardcoded production demo.
 *
 * A small deterministic production-process state machine: four complete cycles
 * of macro and gravimetric material being collected by the robot, blended,
 * packaged and shipped out, with the two feeder modules recharging in the gaps.
 * After the fourth cycle it parks the board and stops. It never starts a fifth.
 *
 * ONE CYCLE IS ONE LOT (change 13.2/13.3): the macro and the gravimetric each
 * dumped into the blender once, blended, shipped to packaging, and put out on
 * the exit strip. Two carries, one batch, one LOT:DONE. Nothing is reset at a
 * cycle boundary and the board is never blanked — the arm runs all eight
 * carries back to back and the plant closes each lot underneath it, running a
 * cycle behind. The cycle counter advances when a lot ships, which is also all
 * the wire says about it: LOT:DONE and nothing else.
 *
 * ---------------------------------------------------------------------------
 * SHAPE — concurrent actors, not one queue
 * ---------------------------------------------------------------------------
 *   robot chain       the ONLY serial part, because there is only one arm:
 *                     two carries per cycle, eight in all, back to back, never
 *                     waiting on the plant and never resetting
 *   feeder x2         macro and gravimetric: recharge, produce, go green, each
 *                     on its own timer — except that the gravimetric holds blue
 *                     until the arm dumps the macro into the blender (13.1)
 *   blender           takes two materials, blends, ships to packaging
 *   packaging         takes ONE batch, finishes the lot, ships it out (13.3)
 *   charge animator   the progressive recharge (ONE — the spans overlap)
 *   flow animator x2  14..16 and 17..21, disjoint, so they may run at once
 *   module state      module.c — OFF / RUNNING (blue) / READY (green)
 *
 * Every actor owns its own state and its own timer and is polled every tick, so
 * running simultaneously is the default and anything sequential has to justify
 * itself. Exactly two things still are, and both are physical rather than
 * structural: the arm (there is one arm) and the two recharges (the macro's
 * 0..5 is a subset of the gravimetric's 0..9, so they cannot share the strip).
 * Both are enforced by _Static_assert in solid_seq1.c rather than assumed.
 *
 * Nothing blocks: there is not a single HAL_Delay in here, and every wait is a
 * SoftTimer or a predicate.
 *
 * ---------------------------------------------------------------------------
 * THE ROBOT
 * ---------------------------------------------------------------------------
 * The link to the Android robot is transmit-only: MOVE:a>b and nothing coming
 * back. There is no ARRIVED acknowledgement and no HOME command, so the robot
 * position is not something this file can know or ask for. One MOVE cue goes out
 * per material carry, when that carry begins, and the app plays its own full
 * travel/pick/place/return-home animation against it. That is also why the robot
 * needs no reset between cycles: it parks itself.
 *
 * There is no CYCLE message on the wire and no plan for one — the app counts
 * LOT:DONE to drive its cycle display, so the boundary costs nothing on either
 * side. solid_seq1_cycle() is the same number for the console.
 */
#ifndef APP_SOLID_SEQ1_H
#define APP_SOLID_SEQ1_H

#include <stdint.h>
#include <stdbool.h>

typedef enum {
    SS1_IDLE = 0,    /* never started, or stopped                             */
    SS1_RUNNING,     /* a cycle is in progress                                */
    SS1_COMPLETE     /* all SS1_CYCLES done; board parked, strip dark         */
} Ss1Status;

/* Start at cycle 1, step 0, and take the strip. Safe to call at any time —
 * everything is reset first, so a button press mid-demo restarts cleanly. */
void solid_seq1_start(void);

/* One pass of the state machine and both animators. Call every main-loop pass,
 * BEFORE seq_leds_tick(). Never blocks. */
void solid_seq1_tick(void);

/* Stop the demo: park the modules, kill both animators, blank the strip and
 * hand it back. This is the ONLY teardown path — calling seq_leds_stop() on its
 * own would leave this file ticking and still cueing the robot. */
void solid_seq1_stop(void);

Ss1Status   solid_seq1_state(void);
uint8_t     solid_seq1_cycle(void);       /* 1..SS1_CYCLES, 0 when idle       */
const char *solid_seq1_step_name(void);   /* for the `seq` console line       */

#endif /* APP_SOLID_SEQ1_H */
