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
#define SS1_SLOT_MACRO         1u   /* "Module 2" — Macro2,      px 49-51 */
#define SS1_SLOT_GRAVIMETRIC   2u   /* "Module 3" — Gravimetric, px 53-55 */
#define SS1_SLOT_BLENDER       4u   /* "Module 5" — Blender2,    px 61-62 */
#define SS1_SLOT_PACKAGING     5u   /* "Module 6" — Packaging,   px 76-77 */

/* Everything else on the board is dark for the whole run. */
#define SS1_ACTIVE_MASK   ( (1u << SS1_SLOT_MACRO)   | (1u << SS1_SLOT_GRAVIMETRIC) | \
                            (1u << SS1_SLOT_BLENDER) | (1u << SS1_SLOT_PACKAGING) )

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
#define SS1_COLLECTION_MS        20000u  /* robot collecting from a module     */
#define SS1_DUMP_MS              20000u  /* robot dumping into the blender     */

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
#define SS1_ARM_TO_SOURCE_MS      3150u  /* MOVING_TO_SOURCE                   */
#define SS1_ARM_POS_SOURCE_MS     1300u  /* POSITIONING_AT_SOURCE              */
#define SS1_ARM_PICK_MS           1850u  /* PICKING — gripper closes           */
                                         /* COLLECTING = SS1_COLLECTION_MS     */
#define SS1_ARM_LIFT_MS           1100u  /* LIFTING                            */
#define SS1_ARM_TO_DEST_MS        3700u  /* MOVING_TO_DESTINATION              */
#define SS1_ARM_POS_DEST_MS       1100u  /* POSITIONING_AT_DESTINATION         */
#define SS1_ARM_PLACE_MS          1850u  /* PLACING — gripper opens            */
                                         /* DUMPING = SS1_DUMP_MS              */
#define SS1_ARM_RETRACT_MS        1500u  /* RETRACTING                         */
#define SS1_ARM_RETURN_MS         2950u  /* RETURNING_HOME                     */

/* A visible rest at rail centre before the next carry is cued. Also the slack
 * that absorbs UART and render-tick jitter, so two carries can never overlap
 * and trip the app's catch-up speed-up. */
#define SS1_ARM_HOME_HOLD_MS      3000u

/* Milestones along a carry, as offsets from the MOVE cue. Derived — do not
 * hand-edit; change the phases above instead. */
#define SS1_ARM_COLLECT_END_MS  ( SS1_ARM_TO_SOURCE_MS + SS1_ARM_POS_SOURCE_MS  \
                                + SS1_ARM_PICK_MS + SS1_COLLECTION_MS )   /* 26300 */
#define SS1_ARM_AT_DEST_MS      ( SS1_ARM_COLLECT_END_MS + SS1_ARM_LIFT_MS      \
                                + SS1_ARM_TO_DEST_MS )                    /* 31100 */
#define SS1_ARM_DUMP_END_MS     ( SS1_ARM_AT_DEST_MS + SS1_ARM_POS_DEST_MS      \
                                + SS1_ARM_PLACE_MS + SS1_DUMP_MS )        /* 54050 */
#define SS1_ARM_TOTAL_MS        ( SS1_ARM_DUMP_END_MS + SS1_ARM_RETRACT_MS      \
                                + SS1_ARM_RETURN_MS )                     /* 58500 */

/* ---- Windows the requirement leaves open. Tune freely. ------------------- */
/* How far into the DUMP HOLD the gravimetric finishes and goes GREEN. Measured
 * from the moment the arm reaches the blender, so it lands while the arm is
 * standing there dumping. Must be < the dump hold (asserted in solid_seq1.c). */
#define SS1_GRAV_READY_AT_MS     13000u
/* How long the blender runs between "both materials in" (BLUE) and "ready to
 * ship to packaging" (GREEN). Without a real window here the blender BLUE would
 * be drawn and overwritten in the same frame and nobody would ever see it.
 * Also the window the gravimetric recharge has to finish in. */
#define SS1_BLEND_RUN_MS         20000u
/* How long packaging takes to finish after the last material reaches it. */
#define SS1_PACKAGING_FINISH_MS  20000u
/* Blank board between cycles, so the reset is something the audience watches
 * happen rather than a jump cut. */
#define SS1_CYCLE_GAP_MS          3000u

/* ---- Animation pace ------------------------------------------------------
 * These are for a client-facing demo watched from 2-3 m away, NOT for an
 * Arduino LED chase. Slow beats fast every time: the audience must be able to
 * follow the direction material is moving.
 *
 * To retune the whole demo, change the three STEP/FILL values and nothing else:
 *   SLOWER   FILL_MACRO 950, FILL_GRAV 600, FLOW_STEP 1000
 *   NORMAL   FILL_MACRO 700, FILL_GRAV 450, FLOW_STEP  700   <- as shipped
 *   FASTER   FILL_MACRO 450, FILL_GRAV 300, FLOW_STEP  450
 * Every duration below is read in exactly one place, so there are no magic
 * numbers anywhere in the animation code. */

/* Recharge (Macro 0..5, Gravimetric 0..9), in two stages:
 *
 *   STAGE 1  pixels latch on one at a time from the base of the span to its
 *            end, each staying lit, then the whole span blanks.
 *   STAGE 2  three growing blocks — the first pixel, then up to the middle,
 *            then the whole span — each lit then blanked. The group of three
 *            runs SS1_CHARGE_BLOCK_REPEATS times.
 *
 * Then the span goes DARK and stays dark: a recharged module is signalled by
 * its own LED going blue, not by leaving strip pixels lit.
 *
 * The gravimetric span is 10 pixels against the macro's 6, so it fills at a
 * faster per-pixel rate — otherwise it would drag — while still taking
 * slightly longer overall. Both facts are asserted in solid_seq1.c. */
#define SS1_CHARGE_FILL_MACRO_MS   700u  /* stage 1, per pixel, 6-pixel span  */
#define SS1_CHARGE_FILL_GRAV_MS    450u  /* stage 1, per pixel, 10-pixel span */
#define SS1_CHARGE_BLANK_MS        400u  /* dark pause between the two stages */
#define SS1_CHARGE_BLOCK_ON_MS     700u  /* stage 2, how long a block is lit  */
#define SS1_CHARGE_BLOCK_OFF_MS    400u  /* stage 2, dark gap after a block   */
#define SS1_CHARGE_BLOCKS            3u  /* blocks per group: 1st / mid / all */
#define SS1_CHARGE_BLOCK_REPEATS     3u  /* how many times the group repeats  */

/* Total run time of one recharge, in ms. Used by the build-time assertions
 * that keep each recharge inside the window it has to finish in. */
#define SS1_CHARGE_MS(len, step)                                              \
    ( (len) * (step) + SS1_CHARGE_BLANK_MS                                    \
      + SS1_CHARGE_BLOCK_REPEATS * SS1_CHARGE_BLOCKS                          \
        * (SS1_CHARGE_BLOCK_ON_MS + SS1_CHARGE_BLOCK_OFF_MS) )

/* Material flow (Blender -> Packaging 14..16, Packaging -> output 17..21): the
 * span fills one pixel at a time, holds, blanks, and repeats. */
#define SS1_FLOW_STEP_MS           700u  /* dwell per pixel added, and the full-span hold */
#define SS1_FLOW_BLANK_MS         1200u  /* all-dark gap before the next pass             */
#define SS1_FLOW_XFER_PASSES         4u  /* blender -> packaging repeats (fixed by spec)  */
#define SS1_FLOW_OUTPUT_PASSES       2u  /* packaging -> output repeats                   */

/* Emit RUN:/DONE: module cues to the Android robot as well as the four MOVE:
 * cues per cycle. Set to 0 for MOVE-only if the app would rather not see them.
 * The MOVE cues are unconditional either way — they are what drives the arm. */
#define SS1_EMIT_RUN_DONE            1

/* ======================================================================
 * 3. Misc.
 * ==================================================================== */
#define BUTTON_DEBOUNCE_MS     25u

#endif /* APP_CONFIG_H */
