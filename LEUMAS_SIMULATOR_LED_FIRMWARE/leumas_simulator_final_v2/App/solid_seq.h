/**
 * solid_seq.h — the solid line's hardcoded production demos.
 *
 * ---------------------------------------------------------------------------
 * FOUR SEQUENCES, ONE ENGINE
 * ---------------------------------------------------------------------------
 * Change 20: this was `solid_seq1.c`, a single hardcoded sequence. There are now
 * four, and they are the SAME code driven by different data. A sequence is a
 * small const table — which feeders, which blenders, and the order the arm
 * carries between them — and everything else (the arm chain, the recharge and
 * flow animators, the feeder/blender/packaging actors, the cycle and lot
 * counting) is shared.
 *
 * That is deliberate and it is the requirement: the spec forbids duplicating
 * LED, robot or timing infrastructure. A second copy of the arm chain would
 * have been a second place for every future timing change to be applied, and
 * the first one to be forgotten.
 *
 *   SEQUENCE 1   macro + gravimetric -> blender 2 -> packaging.
 *                Two carries per cycle, one blender batch, one lot.
 *
 *   SEQUENCE 2   two parallel blend lines sharing one gravimetric:
 *                    macro 1 + gravimetric -> blender 1
 *                    macro 2 + gravimetric -> blender 2
 *                    both blenders          -> packaging
 *                Four carries per cycle, two blender batches, one lot. The
 *                gravimetric is collected TWICE per cycle and has to recharge
 *                in between, which is the tightest timing in the whole demo.
 *
 *   SEQUENCE 3   the LIQUID line: liquid macro 2 -> vessel 2 -> bottling, and
 *                out on the liquid exit run. ONE carry per cycle, so the single
 *                feeder is collected every carry and must recharge and produce
 *                inside one -- which is what forced the recharge to start when
 *                the arm lifts away rather than when it pours (change 23).
 *
 *   SEQUENCE 4   the whole liquid line, two dosing lines in parallel:
 *                    liquid macro 1 -> vessel 1
 *                    liquid macro 2 -> vessel 2
 *                    both vessels    -> bottling
 *                Two carries per cycle, two vessel batches, one lot. Sequence
 *                2's shape on the liquid side, and the only sequence that uses
 *                liquid macro 1 and vessel 1 -- before it, half the liquid rig
 *                was dark in every run. The two lines share nothing, so its
 *                timing is the most relaxed of the four.
 *
 *   SEQUENCE 5   BOTH LINES AT ONCE -- sequence 1 and sequence 3 together,
 *                same modules and same positions:
 *                    macro 2 + gravimetric -> blender 2 -> packaging
 *                    liquid macro 2        -> vessel 2  -> bottling
 *                Three carries per cycle, one batch on each line, and a lot is
 *                the PAIR: packaging and bottling each ship once per cycle and
 *                LOT:DONE waits for both. The arm is the only serial part, so
 *                the two plants close their batches underneath it and neither
 *                line ever waits for the other. Its timing is the loosest of
 *                the five, because three carries per cycle gives every feeder
 *                126 s to recharge.
 *
 * All five run SS1_CYCLES cycles and then park. None ever starts another.
 *
 * ---------------------------------------------------------------------------
 * SHAPE — concurrent actors, not one queue
 * ---------------------------------------------------------------------------
 *   robot chain       the ONLY serial part, because there is only one arm. It
 *                     works through the program's carry table back to back,
 *                     never waiting on the plant and never resetting.
 *   feeder x N        recharge, produce, then HOLD blue until released. A
 *                     feeder goes green only when the previous carry's material
 *                     has finished going into its blender, so the modules light
 *                     in the order the arm actually needs them.
 *   blender x N       collect their materials, blend, then hand off to
 *                     packaging on the strip. They share ONE strip animator and
 *                     queue for it, because their spans overlap.
 *   packaging         takes the program's batches, finishes the lot, ships it
 *                     out on the exit run and emits LOT:DONE.
 *   charge animator   the progressive recharge (ONE — every feeder's span is a
 *                     prefix of the line, so no two can ever play at once)
 *   flow animator x2  blender->packaging and packaging->exit. Disjoint spans,
 *                     so those two may overlap; the blenders share the first.
 *   module state      module.c — OFF / RUNNING (blue) / READY (green)
 *
 * Every actor owns its own state and timer and is polled every tick, so running
 * simultaneously is the default and anything sequential has to justify itself.
 * Nothing blocks: there is not a single HAL_Delay in here.
 *
 * ---------------------------------------------------------------------------
 * A CYCLE IS A LOT
 * ---------------------------------------------------------------------------
 * Nothing is reset at a cycle boundary and the board is never blanked. The arm
 * runs every cycle's carries back to back while the plant closes each lot
 * underneath it, a cycle behind. The counter advances when a lot ships, which
 * is also all the wire says about it: LOT:DONE and nothing else.
 *
 * ---------------------------------------------------------------------------
 * THE ROBOT
 * ---------------------------------------------------------------------------
 * The link to the Android robot carries no position information: MOVE:a>b goes
 * out, and no ARRIVED ever comes back. So the board cannot know where the arm
 * is, and both sides are instead driven from the same SS1_ARM_* numbers, which
 * mirror the app's RobotPhase table. One MOVE cue goes out per carry and the
 * app plays its own full travel/collect/pour/return animation against it. That
 * is also why the robot needs no reset between cycles: it parks itself.
 *
 * The one thing that DOES come back is START, which is what begins a run — see
 * ble.h. Selecting which sequence to run is the board button's job.
 */
#ifndef APP_SOLID_SEQ_H
#define APP_SOLID_SEQ_H

#include <stdint.h>
#include <stdbool.h>

/* How many sequences the board knows. Selection cycles through 1..this. */
#define SOLID_SEQ_COUNT   5u

typedef enum {
    SS1_IDLE = 0,    /* never started, or stopped                             */
    SS1_RUNNING,     /* a run is in progress                                  */
    SS1_COMPLETE     /* all cycles done; board parked, strip dark             */
} Ss1Status;

/* Start a run of sequence `seq` (1..SOLID_SEQ_COUNT) from cycle 1, taking the
 * strip. Safe to call at any time — everything is reset first, so a restart
 * mid-demo is clean. An unknown number is ignored rather than guessed at. */
void solid_seq_start(uint8_t seq);

/* One pass of the state machine and every animator. Call every main-loop pass,
 * BEFORE seq_leds_tick(). Never blocks. */
void solid_seq_tick(void);

/* Stop: park the modules, kill the animators, blank the strip and hand it back.
 * The ONLY teardown path — seq_leds_stop() alone would leave this file ticking
 * and still cueing the robot. */
void solid_seq_stop(void);

/* ---- selection (change 20.2) ---------------------------------------------
 * The board button does not start anything; it chooses. Each press advances to
 * the next sequence and the caller announces it with RECIPE:<n>, so the app can
 * show that recipe and offer START. Selecting while a run is in progress is
 * allowed and is how the operator switches: the run is stopped, because leaving
 * a sequence running underneath a recipe card for a different one would be a
 * lie about what the board is doing. */
void    solid_seq_select_next(void);
uint8_t solid_seq_selected(void);      /* 1..SOLID_SEQ_COUNT, never 0        */

Ss1Status   solid_seq_state(void);
uint8_t     solid_seq_running(void);   /* sequence being run, 0 when idle     */
uint8_t     solid_seq_cycle(void);     /* 1..SS1_CYCLES, 0 when idle          */
const char *solid_seq_step_name(void); /* for the `seq` console line          */

/* Packaging/bottling actor state, for the `mods` console line (change 30).
 *
 * The wire has no message for a module going dark and none at all for the
 * packaging actor's internal state, so a lot that stalls half-assembled is
 * invisible to both the app and the console -- which is exactly the bug this
 * was added to find. Writes the batches-in and batches-needed counts and
 * returns the state name. */
uint8_t     solid_seq_pack_count(void);
const char *solid_seq_pack_state(uint8_t index,
                                 uint8_t *batches_in, uint8_t *batches_needed);

#endif /* APP_SOLID_SEQ_H */
