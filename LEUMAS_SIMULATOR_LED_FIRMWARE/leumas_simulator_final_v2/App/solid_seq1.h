/**
 * solid_seq1.h — Solid Sequence 1, the hardcoded production demo.
 *
 * A small deterministic production-process state machine: four complete cycles
 * of macro and gravimetric material being collected by the robot, blended,
 * packaged and shipped out, with the two feeder modules recharging in the gaps.
 * After the fourth cycle it parks the board and stops. It never starts a fifth.
 *
 * ---------------------------------------------------------------------------
 * SHAPE
 * ---------------------------------------------------------------------------
 *   step chain        one linear list of steps, one timer, in solid_seq1.c
 *   module state      module.c — OFF / RUNNING (blue) / READY (green)
 *   charge animator   the progressive recharge, macro then gravimetric
 *   flow animator     material moving, blender -> packaging then out
 *
 * The two animators run on their own clocks, independently of the step chain
 * and of each other, which is what lets the macro recharge play while the robot
 * is busy collecting from the gravimetric. Nothing blocks: there is not a single
 * HAL_Delay in here, and every wait is a SoftTimer or a predicate.
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
