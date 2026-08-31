/**
 * config.h — sequence-engine tuning file.
 *
 * Slot -> role mapping, which slots each sequence activates, and the timings.
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
 * 1. Module slots (solid line). Edit the roles to match the physical rig.
 *    Slot index here is 0-based internally; the human/BLE slot number is
 *    (index + 1), matching the requirements ("slot 1", "slot 3", ...).
 * ==================================================================== */
/* Two physical rows of modules. The SOLID line (top row) occupies the first
 * slots; the LIQUID line (bottom row) follows it. Only one line runs per
 * sequence.
 *   Solid  (slots 1..6):  Macro1, Macro2, Gravimetric, Blender1, Blender2, Packaging
 *   Liquid (slots 7..11): LMacro1, LMacro2, Vessel1, Vessel2, Bottling            */
#define MODULE_COUNT        11         /* 6 solid + 5 liquid                  */

/* Module roles/types (see MODULE_TYPES[] in config.c). T_VESSEL and T_BOTTLING
 * are the liquid line's analogues of the solid line's T_BLENDER and T_PACKAGING
 * (same engine behaviour, different physical modules). */
typedef enum {
    T_NONE = 0,
    T_MACRO,        /* feeder — shared by both lines (solid & liquid macros) */
    T_GRAVIMETRIC,  /* solid line only                                       */
    T_BLENDER,      /* solid line middle stage                               */
    T_PACKAGING,    /* solid line final stage (exit)                         */
    T_VESSEL,       /* liquid line middle stage (reaction vessel)            */
    T_BOTTLING      /* liquid line final stage (exit)                        */
} ModType;

/* Number of distinct ModType values (sizes the per-type counters). */
#define MOD_TYPE_COUNT      (T_BOTTLING + 1)

/* Slot -> role. Index 0 == slot 1. Adjust the one or two slots that differ. */
extern const ModType MODULE_TYPES[MODULE_COUNT];

/* ---- Rig pixel mapping (see SLOT_LEDS[] in config.c) --------------------
 * Where each slot lives on the one 90-pixel WS2812B strip.
 *
 *   mod_a..mod_b     the module's OWN pixels — its running/transfer/idle colour
 *   st_base..st_end  the status run, filled solid white "up to" this module.
 *                    CUMULATIVE, not per-gap: slot 2's fill covers slot 1's too.
 *                    Each line has its own base (solid 0, liquid 22), so the
 *                    idle line's status pixels are never touched.
 *
 * Both are inclusive. Every index must be < the strip's LED count. */
typedef struct {
    uint8_t mod_a, mod_b;
    uint8_t st_base, st_end;
} SlotLeds;
extern const SlotLeds SLOT_LEDS[MODULE_COUNT];

/* The ship-out runs past the last module of each line, where the "lot shipped"
 * flow animation plays. Inclusive. */
#define SOLID_EXIT_A    17u
#define SOLID_EXIT_B    21u
#define LIQUID_EXIT_A   39u
#define LIQUID_EXIT_B   43u

/* Brightness of an idle (red) module during a sequence, in percent. 100 = the
 * idle modules are as bright as the working ones. */
#define SEQ_IDLE_PCT   100u

/* Brightness of the status run, in percent. The status LEDs are context, not
 * the subject: kept well under the modules so the eye goes to the working
 * module rather than to the line between them. Applied as a PER-PIXEL
 * percentage, so it is independent of the module pixels, which stay at 100%.
 * The master (LED_MASTER_PCT_DEFAULT in led_strip.h) is 100 precisely so this
 * split is the only thing separating the two. */
#define SEQ_STATUS_PCT  10u

/* Sequence 1 (single-blender bottleneck demo). Active slots:
 *   1 = Macro1, 2 = Macro2, 3 = Gravimetric, 4 = Blender1, 6 = Packaging.
 * Blender2 (slot 5) is intentionally LEFT OUT so both batches must share the
 * one blender — that is what makes Macro2's batch WAIT while Blender1 is busy. Two lots are injected; the single blender serializes
 * them. Values are 0-based slot indices. */
#define SEQ1_ACTIVE_MASK    ( (1u<<0) | (1u<<1) | (1u<<2) | (1u<<3) | (1u<<5) ) /* 1,2,3,4,6 */

/* Sequence 2 (throughput via more modules). Same two batches, but Blender2
 * (slot 5) is now active too, so each batch gets its own blender and they run
 * in PARALLEL — no bottleneck wait. This is the whole point of Seq 2: adding a
 * module removes the wait and raises production. */
#define SEQ2_ACTIVE_MASK    ( (1u<<0) | (1u<<1) | (1u<<2) | (1u<<3) | (1u<<4) | (1u<<5) ) /* all 6 */

/* ---- Liquid line (1.5) -------------------------------------------------
 * Same story as the solid line, one stage shorter (no gravimetric): the feed is
 * just a macro. Flow: LMacro -> Vessel -> Bottling -> exit.
 *   Seq 4 (single-vessel bottleneck): LMacro1, LMacro2, Vessel1, Bottling active;
 *          Vessel2 (slot 10) LEFT OUT, so batch B's macro must WAIT
 *          for Vessel1 to empty — the same bottleneck lesson as Seq 1.
 *   Seq 5 (two vessels): Vessel2 also active, so the two batches run in PARALLEL
 *          with no wait — the same "add a module, raise production" lesson as Seq 2.
 * Slots (0-based bit): 6=LMacro1, 7=LMacro2, 8=Vessel1, 9=Vessel2, 10=Bottling. */
#define SEQ4_ACTIVE_MASK    ( (1u<<6) | (1u<<7) | (1u<<8) | (1u<<10) )            /* 7,8,9,11 */
#define SEQ5_ACTIVE_MASK    ( (1u<<6) | (1u<<7) | (1u<<8) | (1u<<9) | (1u<<10) )  /* 7..11    */

/* How many batches (lots) a sequence pushes through per button press. Two
 * batches make the bottleneck (Seq 1/4) and the parallel relief (Seq 2/5)
 * obvious. Looping many lots is the deferred "Seq 3" idea — kept to 2 for now.
 * (SOLID_LOTS_PER_RUN kept as the name; it applies to the liquid line too.) */
#define SOLID_LOTS_PER_RUN  2

/* Button cycle order. Seq 3 (continuous looping) is deferred, so it is skipped:
 * the button walks 1 -> 2 -> 4 -> 5 -> 1 ... (solid bottleneck, solid parallel,
 * liquid bottleneck, liquid parallel). */
#define SEQ_CYCLE_COUNT     4
extern const uint8_t SEQ_CYCLE[SEQ_CYCLE_COUNT];

/* ======================================================================
 * 2. Timings (milliseconds). Calm, legible pace for the demo audience.
 * ==================================================================== */
#define RUN_TIME_MS         20000u  /* how long a module "runs"                */
#define TRANSFER_ROBOT_MS   42000u  /* ONE robot trip (macro->blender, etc.)   */
#define TRANSFER_SWIPE_MS   15000u  /* blender -> packaging hand-off           */
#define EXIT_SWIPE_MS       20000u  /* final "lot shipped" hand-off            */
#define BUTTON_DEBOUNCE_MS     25u

/* The hand-off fill lights ONE status pixel at a time, spread evenly across
 * TRANSFER_SWIPE_MS. The spans are short (5 px for Blender1 -> Packaging, 3 px
 * for Blender2 -> Packaging), so 15 s gives 3-5 s per pixel: slow enough to
 * read as material flowing rather than as an animation playing. Keep this equal
 * to the Android Timings.TRANSFER_SWIPE_MS.
 *
 * The exit is NOT a status display — it is a flow animation, so it keeps the
 * moving head. It sweeps its 5-pixel run EXIT_FLOW_PASSES times over
 * EXIT_SWIPE_MS and then latches solid. Keep EXIT_SWIPE_MS equal to the Android
 * Timings.EXIT_SWIPE_MS. */

/* How many times the exit head crosses its run before the run latches solid.
 * Fewer passes = a slower, more deliberate sweep. */
#define EXIT_FLOW_PASSES     5u

/* Fading tail behind the exit head, in pixels. The exit run is only 5 px, so a
 * tail this long makes the whole run one moving gradient rather than a dot.
 * Must satisfy EXIT_FLOW_TAIL + 1 <= LED_OVERLAY_MAX (see led_fx.c). */
#define EXIT_FLOW_TAIL       4u

/* Set the robot trip time (TRANSFER_ROBOT_MS) to match the Android robot's move
 * animation.
 *
 * Change 11.4: raised 4000 -> 20000 so the arm gets a full ~20 s per trip to
 * play its travel/pick/place/return-home animation legibly (req. point 11.4).
 *
 * Change 13.8: raised again 20000 -> 34000. Requirement 13.8 asks for a visible
 * powder/liquid collection animation at the source and a matching pour at the
 * destination, up to ~10 s each. The Android animation is 32 s per trip
 * (Timings.DEFAULT_ROBOT_ANIMATION_MS) with ~7.5 s of collect and ~7.5 s of
 * pour; the 2 s of slack here is the same jitter margin change 12.2 introduced,
 * so two back-to-back trips never overlap. NOTE: this is expected to be retuned
 * per sequence once the collect/pour pacing is settled on the rig.
 *
 * Change 15.2: raised again 34000 -> 42000. Each reach now runs a three-coordinate
 * path (transit -> approach -> target) and comes fully to rest at the via
 * point, per requirement 15.2 asking for slow, graceful motion with a pause
 * between coordinates. The Android animation is 40 s per trip; the 2 s of slack
 * here is the same jitter margin change 12.2 introduced.
 * This is the ONLY scheduling knob that changes: every LP_XFER trip arms
 * lot->timer with trip_ms (= this) in lotmgr.c, so the whole per-lot schedule —
 * each module's RUN window, the WAIT hand-off, the destination RUN that follows
 * a transfer — shifts with it automatically and stays byte-for-byte in step with
 * what the Android app animates. RUN_TIME_MS is deliberately left at 20 s: the
 * module run pace is unchanged, only the transfer window grows. On a two-source
 * stage (macro + gravimetric -> blender) the robot makes two sequential 20 s
 * trips (~40 s), which is expected and correct. Keep this value equal to the
 * Android Timings.TRANSFER_ROBOT_MS. */

/* ======================================================================
 * 3. Engine limits (implementation detail — invisible to the audience).
 * ==================================================================== */
#define MAX_LOTS            4    /* concurrent lots the pipeline can hold    */
#define MAX_PARALLEL        4    /* modules a single stage can occupy        */
#define MAX_STAGES          4    /* stages in a recipe                       */

#endif /* APP_CONFIG_H */
