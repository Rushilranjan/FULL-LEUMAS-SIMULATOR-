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
 *
 * Change 15.2: COLLECTION and DUMP raised 10000 -> 20000 each, so one carry
 * (collect + dump, the window a single MOVE cue covers) is 40 s rather than
 * 20 s. The Android arm animates a three-coordinate reach - transit, approach,
 * target - coming fully to rest at the via point, which does not read as slow
 * and graceful in 20 s. Timings.DEFAULT_ROBOT_ANIMATION_MS is 40 s and this is
 * its firmware-side account of the same carry; the two must move together.
 * Lengthening only loosens both _Static_asserts in solid_seq1.c.
 *
 * DUMP carries 2 s more than COLLECTION so the carry is 42 s against a 40 s
 * animation. That 2 s is the jitter margin change 12.2 introduced: the app
 * animates one MOVE for the full carry, so with the two exactly equal the
 * next cue can land a render-tick before the arm reaches home, which queues a
 * transfer and triggers the catch-up speed-up mid-rotation. The slack has to
 * sit on this side because the animation length is fixed by requirement 15.2. */
#define SS1_INITIAL_PROCESS_MS   10000u  /* Module 2 + 3 both running at start */
#define SS1_COLLECTION_MS        20000u  /* robot collecting from a module     */
#define SS1_DUMP_MS              22000u  /* robot dumping into the blender     */

/* ---- Windows the requirement leaves open. Tune freely. ------------------- */
/* How far into the macro -> blender dump the gravimetric finishes and goes
 * GREEN. Must be < SS1_DUMP_MS (asserted in solid_seq1.c). Scaled with the
 * 15.2 dump change (5000 -> 10000) to hold it at the dump midpoint, so the
 * green lands at the same place in the rhythm as before. */
#define SS1_GRAV_READY_AT_MS     10000u
/* How long the blender runs between "both materials in" (BLUE) and "ready to
 * ship to packaging" (GREEN). Without a real window here the blender BLUE would
 * be drawn and overwritten in the same frame and nobody would ever see it. */
#define SS1_BLEND_RUN_MS         10000u
/* How long packaging takes to finish after the last material reaches it. */
#define SS1_PACKAGING_FINISH_MS   5000u
/* Blank board between cycles, so the reset is something the audience watches
 * happen rather than a jump cut. */
#define SS1_CYCLE_GAP_MS          2000u

/* ---- Animation pace ------------------------------------------------------
 * These are for a client-facing demo watched from 2-3 m away, NOT for an
 * Arduino LED chase. Slow beats fast every time: the audience must be able to
 * follow the direction material is moving.
 *
 * To retune the whole demo, change the two STEP_MS values and nothing else:
 *   SLOWER   CHARGE_STEP 600, FLOW_STEP 1000
 *   NORMAL   CHARGE_STEP 400, FLOW_STEP  700   <- as shipped
 *   FASTER   CHARGE_STEP 250, FLOW_STEP  450
 * Every duration below is read in exactly one place, so there are no magic
 * numbers anywhere in the animation code. */

/* Recharge (Macro 0..5, Gravimetric 0..9): a head crawls from the first
 * uncharged pixel to the end of the span, then the next chunk latches solid
 * behind it and the following pass starts further along. */
#define SS1_CHARGE_STEP_MS         400u  /* dwell on each pixel as the head moves */
#define SS1_CHARGE_STAGE_MS       1500u  /* pause after a chunk latches solid     */
#define SS1_CHARGE_CHUNK             2u  /* pixels that go solid per stage        */
#define SS1_CHARGE_HOLD_MS        1000u  /* hold once the whole span is solid     */

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
