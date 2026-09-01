/**
 * config.h — Solid Sequence 1 tuning file.
 *
 * Slot -> role mapping, which slots the sequence uses, and every timing and
 * animation knob it has.
 *
 * Also the RIG's pixel mapping — which LEDs on the strip are which module, and
 * which are the status run between them (SLOT_LEDS below). The strip's own
 * TUNING (pixel count, brightness, current budget, bit timing) is not here; it
 * lives in one block at the top of App/led_strip.h.
 *
 * Target: STM32F767ZI Nucleo, STM32CubeIDE / HAL.
 */
#ifndef APP_CONFIG_H
#define APP_CONFIG_H

#include <stdint.h>

/* ======================================================================
 * 1. Module slots. Edit the roles to match the physical rig.
 *    Slot index here is 0-based internally; the human/BLE slot number is
 *    (index + 1), matching the requirements ("Module 2", "Module 5", ...).
 * ==================================================================== */
/* Two physical rows of modules. The SOLID line (top row) occupies the first
 * slots; the LIQUID line (bottom row) follows it.
 *   Solid  (slots 1..6):  Macro1, Macro2, Gravimetric, Blender1, Blender2, Packaging
 *   Liquid (slots 7..11): LMacro1, LMacro2, Vessel1, Vessel2, Bottling
 *
 * Solid Sequence 1 drives slots 2, 3, 5 and 6 only. Everything else — including
 * the whole liquid line — stays dark. The liquid mapping is kept intact because
 * a liquid sequence will be added later; nothing here needs to change for it. */
#define MODULE_COUNT        11         /* 6 solid + 5 liquid                  */

/* Module roles/types (see MODULE_TYPES[] in config.c). T_VESSEL and T_BOTTLING
 * are the liquid line analogues of T_BLENDER and T_PACKAGING; they are unused
 * until the liquid sequence lands. */
typedef enum {
    T_NONE = 0,
    T_MACRO,        /* feeder — shared by both lines (solid & liquid macros) */
    T_GRAVIMETRIC,  /* solid line only                                       */
    T_BLENDER,      /* solid line middle stage                               */
    T_PACKAGING,    /* solid line final stage (exit)                         */
    T_VESSEL,       /* liquid line middle stage (reaction vessel)            */
    T_BOTTLING      /* liquid line final stage (exit)                        */
} ModType;

/* Number of distinct ModType values. */
#define MOD_TYPE_COUNT      (T_BOTTLING + 1)

/* Slot -> role. Index 0 == slot 1. Adjust the one or two slots that differ. */
extern const ModType MODULE_TYPES[MODULE_COUNT];

/* ---- Rig pixel mapping (see SLOT_LEDS[] in config.c) --------------------
 * Where each slot lives on the one 90-pixel WS2812B strip.
 *
 *   mod_a..mod_b     the module OWN pixels — its blue/green/dark state
 *   st_base..st_end  the status run reaching up to this module. CUMULATIVE,
 *                    not per-gap: slot 3 span covers slots 1 and 2 as well.
 *                    Each line has its own base (solid 0, liquid 22).
 *
 * Solid Sequence 1 takes ALL FOUR of its strip spans straight from this table
 * (see SS1_STRIP_* in solid_seq1.c) — the requirement LED indices and this map
 * are the same numbers, so there is exactly one source of truth for the wiring.
 *
 * Both are inclusive. Every index must be < the strip LED count. */
typedef struct {
    uint8_t mod_a, mod_b;
    uint8_t st_base, st_end;
} SlotLeds;
extern const SlotLeds SLOT_LEDS[MODULE_COUNT];

/* The ship-out runs past the last module of each line, where the material
 * leaves the system. Inclusive. */
#define SOLID_EXIT_A    17u
#define SOLID_EXIT_B    21u
#define LIQUID_EXIT_A   39u
#define LIQUID_EXIT_B   43u

/* Brightness of the strip, in percent, applied PER PIXEL over a master of 100.
 * The requirement fixes this at 1%: the strip is context, not the subject, and
 * the eye must go to the modules. 1% of 255 resolves to 3/255 on the wire —
 * dim, but the dimmest value that is still reliably lit. The modules stay at
 * 100% (SS1_MODULE_PCT); the split between the two IS the visual hierarchy,
 * which is why the master must stay at 100 and never be used to dim anything. */
#define SEQ_STRIP_PCT    1u

/* ======================================================================
 * 2. SOLID SEQUENCE 1 — the only sequence.
 *
 * A hardcoded, deterministic production process: 4 cycles, then STOP. The
 * physical layout is fixed and must not be changed here.
 * ==================================================================== */

/* Slots the sequence drives. 0-based indices into MODULE_TYPES / SLOT_LEDS;
 * the human and BLE number is (index + 1), which is what the requirement calls
 * "Module 2/3/5/6". */
/* Sequence 2 adds the two solid modules sequence 1 never touched: macro 1 and
 * blender 1. Names are the rig's, not a sequence's -- both sequences index the
 * same physical line. */
#define SS1_SLOT_MACRO1        0u   /* "Module 1" — Macro1,      px 45-47 */
#define SS1_SLOT_BLENDER1      3u   /* "Module 4" — Blender1,    px 57-58 */
#define SS1_SLOT_MACRO         1u   /* "Module 2" — Macro2,      px 49-51 */
#define SS1_SLOT_GRAVIMETRIC   2u   /* "Module 3" — Gravimetric, px 53-55 */
#define SS1_SLOT_BLENDER       4u   /* "Module 5" — Blender2,    px 61-62 */
#define SS1_SLOT_PACKAGING     5u   /* "Module 6" — Packaging,   px 76-77 */

/* The liquid line. Sequence 3 runs one macro, one vessel and the bottling
 * module along it; the rest are mapped and waiting for a sequence of their
 * own. */
#define SS1_SLOT_LMACRO1       6u   /* LMacro1,   px 85-87, status 22-26  */
#define SS1_SLOT_LMACRO2       7u   /* LMacro2,   px 80-82, status 22-31  */
#define SS1_SLOT_VESSEL1       8u   /* Vessel1,   px 70-71, status 22-33  */
#define SS1_SLOT_VESSEL2       9u   /* Vessel2,   px 66-67, status 22-35  */
#define SS1_SLOT_BOTTLING     10u   /* Bottling,  px 73-74, status 22-38  */

/* Everything else on the board is dark for the whole run. */
/* Every solid-line module a sequence may light. Sequence 1 uses four of these
 * and sequence 2 uses all six; the mask is the union, because it exists to keep
 * the LIQUID line dark rather than to describe any one sequence. */
#define SS1_ACTIVE_MASK   ( (1u << SS1_SLOT_MACRO1)   | (1u << SS1_SLOT_MACRO)        | \
                            (1u << SS1_SLOT_GRAVIMETRIC) | (1u << SS1_SLOT_BLENDER1)  | \
                            (1u << SS1_SLOT_BLENDER) | (1u << SS1_SLOT_PACKAGING)     | \
                            (1u << SS1_SLOT_LMACRO2) | (1u << SS1_SLOT_VESSEL2)       | \
                            (1u << SS1_SLOT_BOTTLING) )

/* Module pixels are ALWAYS full brightness — the strip percentage above never
 * applies to them. */
#define SS1_MODULE_PCT       100u

/* Complete production cycles per run. After the last one the sequence parks the
 * board and enters COMPLETE; it never rolls into another cycle on its own. */
#define SS1_CYCLES             4u

/* ---- FIXED process durations. The requirement pins these; do not shorten. --
 * All 20 s: a module holds each of its states long enough to be read from
 * across a room. */
#define SS1_INITIAL_PROCESS_MS   20000u  /* Module 2 + 3 both running at start */
/* Change 17.6: SS1_COLLECTION_MS and SS1_DUMP_MS are gone. They described a
 * standing dwell the arm no longer has -- this branch's app folds collecting
 * into PICKING and pouring into PLACING, so adding them on top of the phase
 * table double-counted and produced the 58.5 s arm the app never animated.
 *
 * The dwell is now SS1_ARM_PICK_MS / SS1_ARM_PLACE_MS, 7.4 s each. If the demo
 * wants the old 20 s stand-and-collect back, it has to be lengthened in the
 * app's RobotPhase fractions first and mirrored here -- doing it on the board
 * alone is exactly what put the two out of step. */

/* ---- Robot arm mirror ----------------------------------------------------
 * The BLE link is transmit-only: no ARRIVED comes back, so the firmware cannot
 * ask the Android app where the arm is. The only way the board and the arm can
 * agree is for BOTH to be driven from these numbers.
 *
 * Each constant below is one phase of RobotPhase in
 *   LEUMAS_SIMULATOR_ROBOT/.../domain/RobotStateMachine.kt
 * in the same order. COLLECTING and DUMPING are SS1_COLLECTION_MS and
 * SS1_DUMP_MS above — the arm physically stands at the module for those, which
 * is the whole point: every module colour change below fires while the arm is
 * visibly in the right place.
 *
 * Change one of these and you MUST change RobotPhase to match. The total is
 * asserted in solid_seq1.c so a one-sided edit fails the build. */
/* Change 17.6 -- RE-DERIVED FROM THE APP'S PHASE TABLE, WHICH IS 40 s.
 *
 * These used to describe a 58.5 s arm with explicit 20 s COLLECTING and DUMPING
 * dwells. The Android app on this branch does not have that model: its
 * RobotPhase is a fraction table over DEFAULT_ROBOT_ANIMATION_MS = 40 s, with
 * the collect dwell folded into PICKING and the pour into PLACING. The two
 * halves were therefore 20.5 s apart on every carry -- the board cued the next
 * MOVE at 61.5 s while the arm had finished animating at 40 s, so it stood
 * still for a third of every trip. That, not SS1_ARM_HOME_HOLD_MS, is why the
 * robot looked idle.
 *
 * Each value below is the app's fraction times 40 s, in the app's order:
 *
 *     MOVING_TO_SOURCE            0.095
 *     POSITIONING_AT_SOURCE       0.065
 *     PICKING                     0.185   <- the collect dwell lives here
 *     LIFTING                     0.090
 *     MOVING_TO_DESTINATION       0.105
 *     POSITIONING_AT_DESTINATION  0.065
 *     PLACING                     0.185   <- the pour dwell lives here
 *     RETRACTING                  0.110
 *     RETURNING_HOME              0.100
 *
 * Change a fraction or the 40 s in RobotStateMachine.kt and these must move
 * with it; the assert on SS1_ARM_TOTAL_MS in solid_seq1.c is the tripwire. */
#define SS1_ARM_TO_SOURCE_MS      3800u  /* MOVING_TO_SOURCE            0.095  */
#define SS1_ARM_POS_SOURCE_MS     2600u  /* POSITIONING_AT_SOURCE       0.065  */
#define SS1_ARM_PICK_MS           7400u  /* PICKING — grip and collect  0.185  */
#define SS1_ARM_LIFT_MS           3600u  /* LIFTING                     0.090  */
#define SS1_ARM_TO_DEST_MS        4200u  /* MOVING_TO_DESTINATION       0.105  */
#define SS1_ARM_POS_DEST_MS       2600u  /* POSITIONING_AT_DESTINATION  0.065  */
#define SS1_ARM_PLACE_MS          7400u  /* PLACING — release and pour  0.185  */
#define SS1_ARM_RETRACT_MS        4400u  /* RETRACTING                  0.110  */
#define SS1_ARM_RETURN_MS         4000u  /* RETURNING_HOME              0.100  */

/* A visible rest at rail centre before the next carry is cued. Also the slack
 * that absorbs UART and render-tick jitter, so two carries can never overlap
 * and trip the app's catch-up speed-up. */
#define SS1_ARM_HOME_HOLD_MS      2000u

/* Milestones along a carry, as offsets from the MOVE cue. Derived — do not
 * hand-edit; change the phases above instead. */
/* Change 17.6: the dwells are no longer added on top -- PICKING and PLACING
 * ARE the dwells, because that is how the app animates them. */
#define SS1_ARM_COLLECT_END_MS  ( SS1_ARM_TO_SOURCE_MS + SS1_ARM_POS_SOURCE_MS  \
                                + SS1_ARM_PICK_MS )                       /* 13800 */
#define SS1_ARM_AT_DEST_MS      ( SS1_ARM_COLLECT_END_MS + SS1_ARM_LIFT_MS      \
                                + SS1_ARM_TO_DEST_MS )                    /* 21600 */
#define SS1_ARM_DUMP_END_MS     ( SS1_ARM_AT_DEST_MS + SS1_ARM_POS_DEST_MS      \
                                + SS1_ARM_PLACE_MS )                      /* 31600 */
#define SS1_ARM_TOTAL_MS        ( SS1_ARM_DUMP_END_MS + SS1_ARM_RETRACT_MS      \
                                + SS1_ARM_RETURN_MS )                     /* 58500 */

/* ---- Windows the requirement leaves open. Tune freely. -------------------
 *
 * SS1_GRAV_READY_AT_MS used to live here: the gravimetric was forced green part
 * way through the macro's dump, on a clock of its own, purely so it would be
 * ready when the arm came for it. Change 12.1 removed the need — each feeder
 * now recharges and produces on its own timer the moment the arm empties it, so
 * "ready" is something the module arrives at rather than something the chain
 * declares on its behalf. */
/* How long the blender runs between "both materials in" (BLUE) and "ready to
 * ship to packaging" (GREEN). Without a real window here the blender BLUE would
 * be drawn and overwritten in the same frame and nobody would ever see it.
 * Also the window the gravimetric recharge has to finish in.
 *
 * Change 11.3: was 20 s. These two are the only stretches of the cycle with no
 * robot in them, so they were where the demo sagged — the arm parks at rail
 * centre and the audience waits on a colour change. Shortened to 12 s, which
 * still reads clearly from across a room but stops the board-only phases
 * dominating the cycle. */
/* How long a blender stays blue once it has everything it needs.
 *
 * Change 16.4 made this the ENTIRE time it spends blue -- it is dark while it
 * merely holds a partial batch -- so this is not a window the audience waits
 * through, it IS the blend. Change 21 then set a floor: every pour must leave
 * the module lit for at least ten seconds, long enough to be certain it lit at
 * all, before it goes green and the hand-off flow starts on the same pass. */
#define SS1_BLEND_RUN_MS         10000u
/* How long packaging takes to finish after the last material reaches it.
 * Change 11.3: was 20 s, same reasoning as the blender above. */
#define SS1_PACKAGING_FINISH_MS  12000u
/* SS1_CYCLE_GAP_MS used to live here: a blank board between cycles, so the
 * reset was something the audience watched happen. Change 13.2 removed the
 * reset itself — a cycle boundary is now just a lot shipping while the arm
 * is already carrying the next cycle's material — so there is no gap left
 * to time. */

/* ---- Animation pace ------------------------------------------------------
 * These are for a client-facing demo watched from 2-3 m away, NOT for an
 * Arduino LED chase. Slow beats fast every time: the audience must be able to
 * follow the direction material is moving.
 *
 * To retune the whole demo, change the three STEP/FILL values and nothing else:
 *   SLOWER   FILL_MACRO 700, FILL_GRAV 450, FLOW_STEP 1000
 *   NORMAL   FILL_MACRO 420, FILL_GRAV 300, FLOW_STEP  700   <- as shipped
 *   FASTER   FILL_MACRO 300, FILL_GRAV 210, FLOW_STEP  450
 * Every duration below is read in exactly one place, so there are no magic
 * numbers anywhere in the animation code. */

/* Recharge (Macro 0..5, Gravimetric 0..9). Change 11.2: ONE stage, not two.
 *
 *   pixels latch on one at a time from the base of the span to its end, each
 *   staying lit — 1 solid, 2 solid, 3 solid ... — then the whole span blanks
 *   and the pass starts again. SS1_CHARGE_REPEATS passes in all.
 *
 * The old "first pixel / up to the middle / all of them" block stage is gone.
 * It read as a stutter rather than as a level filling up, and on the 10-pixel
 * gravimetric span the middle block landed in a different place than it did on
 * the macro's 6, so the two recharges did not look like the same animation.
 *
 * Then the span goes DARK and stays dark: a recharged module is signalled by
 * its own LED going blue, not by leaving strip pixels lit.
 *
 * The gravimetric span is 10 pixels against the macro's 6, so it fills at a
 * faster per-pixel rate — otherwise it would drag — while still taking
 * slightly longer overall. Both facts are asserted in solid_seq1.c. */
/* Macro 1's status run is only two pixels (0..1 -- the fills are cumulative, so
 * the first module on the line has the shortest). A slower per-pixel dwell keeps
 * its recharge from being over before the eye finds it, while still leaving it
 * the quickest of the three, which is what the map says it should be. */
/* Liquid macro 2's status run is TEN pixels (22..31), and sequence 3 collects it
 * on every carry -- so it has one carry, not two, to recharge and produce in.
 * That is what sets this: 3 * 10 * 300 + 1000 = 10 s, leaving 2 s of margin on
 * the 32 s window. The assert in solid_seq.c is the real guard. */
#define SS1_CHARGE_FILL_LIQUID_MS  300u  /* per pixel, 10-pixel span          */
#define SS1_CHARGE_FILL_MACRO1_MS  700u  /* per pixel, 2-pixel span           */
#define SS1_CHARGE_FILL_MACRO_MS   420u  /* per pixel, 6-pixel span           */
/* Change 12.3: eased off from 300 — the gravimetric was reading as a flicker
 * over ten pixels. Still quicker per pixel than the macro, and still the longer
 * animation overall, both of which solid_seq1.c asserts. */
#define SS1_CHARGE_FILL_GRAV_MS    340u  /* per pixel, 10-pixel span          */
#define SS1_CHARGE_BLANK_MS        500u  /* dark gap between passes           */
#define SS1_CHARGE_REPEATS           3u  /* how many times the fill repeats   */

/* Total run time of one recharge, in ms. Used by the build-time assertions
 * that keep each recharge inside the window it has to finish in.
 *
 * Every pass costs len*step; the blank sits BETWEEN passes, so there are
 * REPEATS-1 of them and the animation ends on the blank that clears the span. */
#define SS1_CHARGE_MS(len, step)                                              \
    ( SS1_CHARGE_REPEATS * (len) * (step)                                     \
      + (SS1_CHARGE_REPEATS - 1u) * SS1_CHARGE_BLANK_MS )

/* Material flow (Blender -> Packaging 14..16, Packaging -> output 17..21): the
 * span fills one pixel at a time, holds, blanks, and repeats. */
#define SS1_FLOW_STEP_MS           700u  /* dwell per pixel added, and the full-span hold */
#define SS1_FLOW_BLANK_MS         1200u  /* all-dark gap before the next pass             */
#define SS1_FLOW_XFER_PASSES         3u  /* blender -> packaging repeats (change 17.7)    */
#define SS1_FLOW_OUTPUT_PASSES       3u  /* packaging -> output repeats  (change 17.7)    */

/* Change 17.3: how far AHEAD of the arm's collect-end milestone the source
 * module goes dark.
 *
 * It should go out the instant the robot has taken its material, and on the rig
 * it was reading late. The cause turned out to be the 20.5 s arm mismatch fixed
 * in 17.6, not a skew needing trimming: the board was turning the module off at
 * 26.3 s while the app's arm had finished collecting at 13.8 s. With the two
 * models reconciled the module goes dark exactly when the arm stops collecting,
 * so this is 0 and correcting nothing.
 *
 * It is kept, at zero, because the two sides remain open-loop clocks with no
 * handshake and a small standing skew can reappear on different hardware. Raise
 * it to pull the blackout earlier; it moves the S_CARRY_MOVE / S_CARRY_CROSS
 * boundary only, so the carry still tiles to exactly SS1_T_CARRY and nothing
 * downstream shifts. */
#define SS1_COLLECT_OFF_LEAD_MS      0u

/* How long a module shows blue to acknowledge material landing in it, before
 * going dark again to wait for the rest of the batch.
 *
 * Change 21 raised this 5 s -> 10 s, matching SS1_BLEND_RUN_MS: the requirement
 * is that a dump ALWAYS leaves the receiving module blue for at least ten
 * seconds, whether or not that pour completes the batch. Still far short of the
 * 42 s carry, so it cannot become the stale lit module change 16.4 removed --
 * the module is dark again long before the next pour arrives. */
#define SS1_RECEIVE_ACK_MS       10000u

/* Emit RUN:/DONE: module cues to the Android robot as well as the four MOVE:
 * cues per cycle. Set to 0 for MOVE-only if the app would rather not see them.
 * The MOVE cues are unconditional either way — they are what drives the arm. */
#define SS1_EMIT_RUN_DONE            1

/* ======================================================================
 * 3. Misc.
 * ==================================================================== */
#define BUTTON_DEBOUNCE_MS     25u

#endif /* APP_CONFIG_H */
